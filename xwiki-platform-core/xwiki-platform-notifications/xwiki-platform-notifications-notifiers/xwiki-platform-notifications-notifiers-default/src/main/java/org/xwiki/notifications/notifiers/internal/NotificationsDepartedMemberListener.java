/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.notifications.notifiers.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventSearchResult;
import org.xwiki.eventstream.EventStore;
import org.xwiki.eventstream.query.SimpleEventQuery;
import org.xwiki.eventstream.query.SortableEventQuery.SortClause.Order;
import org.xwiki.job.Job;
import org.xwiki.notifications.NotificationConfiguration;
import org.xwiki.notifications.notifiers.internal.email.live.MissingLiveNotificationMailsRequest;
import org.xwiki.notifications.notifiers.internal.email.live.PrefilteringLiveNotificationEmailManager;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.remote.ClusterMemberDepartedEvent;
import org.xwiki.observation.remote.ClusterMemberDepartureManager;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;

/**
 * Takes over, on the cluster singleton node, the notifications left pending by the cluster members gone for good (see
 * {@link ClusterMemberDepartureManager}): each member pre-filters the events it produced and resumes their pending live
 * notification mails, so nobody else would ever handle them once the member never comes back with the same identifier.
 * <ul>
 * <li>When the application is ready and when the current instance becomes the cluster singleton node, the events
 * stored since {@code observation.remote.reclaimMaxAge} are scanned for identifiers of members which are not part of
 * the cluster (one query per identifier, in the order of the identifiers), and those identifiers are watched (see
 * {@link ClusterMemberDepartureManager#watch(java.util.Collection)}) so that the members which left before the
 * current instance started are taken over too.</li>
 * <li>When members are gone for good ({@link ClusterMemberDepartedEvent}, the departures notified within a few seconds
 * being taken over together), the pending live notification mails of the events they produced from
 * {@code observation.remote.reclaimMaxAge} before they left until {@code observation.remote.reclaimDelay} after it are
 * resumed by one job, then, once the job is finished, their waiting events of the same period are pre-filtered (see
 * {@link UserEventDispatcher#addDepartedMember(String)}), which creates their alerts and their mails on the current
 * instance. Waiting for the job ensures that it does not find the mails created by that pre-filtering (it would send
 * them a second time).</li>
 * </ul>
 * A member is taken over once per departure while the current instance is the cluster singleton node: the
 * notifications of an already departed member which is watched again (for example by another scan) are ignored, and
 * everything is forgotten when the current instance stops being the cluster singleton node. The work is done by a
 * dedicated thread, never by the event thread nor the pre-filtering thread.
 * <p>
 * Nothing is done when remote observation or the notifications are disabled, or when
 * {@code observation.remote.reclaimDelay} is negative; the mails are not resumed when the mail notifications are
 * disabled. A member which comes back stops being taken over: the resume job checks it before each user and the
 * pre-filtering before each event, and the member then handles its own events.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
@Component
@Singleton
@Named(NotificationsDepartedMemberListener.NAME)
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public class NotificationsDepartedMemberListener extends AbstractClusterSingletonListener implements Disposable
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "org.xwiki.notifications.notifiers.internal.NotificationsDepartedMemberListener";

    /**
     * The maximum number of member identifiers found by a scan of the events (one query per identifier).
     */
    private static final int MAX_SCANNED_MEMBERS = 1000;

    /**
     * The maximum number of members whose mails are resumed by the same job.
     */
    private static final int MAX_JOB_MEMBERS = 20;

    /**
     * The time to wait for other departures before taking over the departed members, in milliseconds.
     */
    private static final long TAKE_OVER_DELAY = 5000;

    /**
     * The time after which a warning is logged when the mail resume job is still running, in milliseconds.
     */
    private static final long JOB_WARNING_DELAY = 600000;

    @Inject
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @Inject
    private NotificationConfiguration notificationConfiguration;

    @Inject
    private ClusterMemberDepartureManager departureManager;

    @Inject
    private UserEventDispatcherScheduler scheduler;

    @Inject
    private UserEventDispatcher dispatcher;

    @Inject
    private PrefilteringLiveNotificationEmailManager mailManager;

    @Inject
    private EventStore events;

    @Inject
    @Named("context")
    private ComponentManager componentManager;

    @Inject
    private Logger logger;

    private volatile boolean applicationReady;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Notifications departed cluster members");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);

        return thread;
    });

    private long takeOverDelay = TAKE_OVER_DELAY;

    private long jobWarningDelay = JOB_WARNING_DELAY;

    /**
     * Guards {@link #pendingDepartures}, {@link #takeOverScheduled}, {@link #takenOver} and {@link #tenure}.
     */
    private final Object stateLock = new Object();

    /**
     * The departed members waiting for the next take-over.
     */
    private final Set<String> pendingDepartures = new LinkedHashSet<>();

    private boolean takeOverScheduled;

    /**
     * The members taken over while the current instance is the cluster singleton node, with their departure date.
     */
    private final Map<String, Date> takenOver = new HashMap<>();

    /**
     * Incremented each time the current instance becomes or stops being the cluster singleton node.
     */
    private long tenure;

    /**
     * Constructs a new {@link NotificationsDepartedMemberListener}.
     */
    public NotificationsDepartedMemberListener()
    {
        super(NAME, new ApplicationReadyEvent(), new ClusterMemberDepartedEvent());
    }

    @Override
    public void onEvent(org.xwiki.observation.event.Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent) {
            // Remembered even when the current instance is not the singleton node, for the next gain
            this.applicationReady = true;
        }

        super.onEvent(event, source, data);
    }

    @Override
    protected void processSingletonEvent(org.xwiki.observation.event.Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent) {
            scan();
        } else if (event instanceof ClusterMemberDepartedEvent && this.applicationReady && isActive()) {
            synchronized (this.stateLock) {
                this.pendingDepartures.add((String) data);
                if (!this.takeOverScheduled) {
                    // Wait a bit for the departures notified together (for example when the cluster is scaled down)
                    this.takeOverScheduled = true;
                    submit(this::takeOver, this.takeOverDelay);
                }
            }
        }
    }

    @Override
    protected void onSingletonNodeGained()
    {
        synchronized (this.stateLock) {
            ++this.tenure;
        }

        // The previous singleton node might have stopped before taking over the members gone for good, or the current
        // instance ignored their departure while it was not the singleton node
        scan();
    }

    @Override
    protected void onSingletonNodeLost()
    {
        synchronized (this.stateLock) {
            ++this.tenure;
            this.pendingDepartures.clear();
            this.takenOver.clear();
            this.dispatcher.clearDepartedMembers();
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        this.executor.shutdownNow();
    }

    private boolean isActive()
    {
        return this.remoteObservationConfiguration.isEnabled() && this.notificationConfiguration.isEnabled()
            && this.remoteObservationConfiguration.getReclaimDelay() >= 0;
    }

    private void submit(Runnable task, long delay)
    {
        try {
            this.executor.schedule(new ExecutionContextRunnable(task, this.componentManager), delay,
                TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The component is disposed: the instance is stopping
            this.logger.debug("Ignoring a task received after the listener was disposed", e);
        }
    }

    private void scan()
    {
        if (this.applicationReady && isActive()) {
            // Don't block the event thread
            submit(this::scanEvents, 0);
        }
    }

    private Date getOldestDate(Date date)
    {
        long maxAge = this.remoteObservationConfiguration.getReclaimMaxAge();

        return maxAge >= 0 ? UserEventDispatcher.shift(date, -maxAge) : null;
    }

    private void scanEvents()
    {
        if (!this.singletonManager.isSingletonNode()) {
            return;
        }

        // Sorted, so that the queries are always the same for the same members
        List<String> currentMembers = new ArrayList<>(new TreeSet<>(this.departureManager.getMemberIds()));
        List<String> foundMembers = new ArrayList<>();
        Date oldest = getOldestDate(new Date());
        try {
            // One query per identifier, in the order of the identifiers: the event store has no way to list the
            // distinct values of a field, and the size of each query does not depend on the number of identifiers
            while (foundMembers.size() < MAX_SCANNED_MEMBERS) {
                String memberId = findMember(currentMembers, foundMembers, oldest);
                if (memberId == null) {
                    break;
                }
                foundMembers.add(memberId);
            }
        } catch (Exception e) {
            this.logger.error("Failed to search the events produced by the cluster members which are not part of the"
                + " cluster", e);
        }

        if (!foundMembers.isEmpty()) {
            this.logger.info("Found events produced by cluster members which are not part of the cluster {}, their"
                + " pending notifications will be taken over if they don't join", foundMembers);
            if (foundMembers.size() == MAX_SCANNED_MEMBERS) {
                this.logger.warn("Stopped searching for events produced by other cluster members after [{}] members",
                    MAX_SCANNED_MEMBERS);
            }

            this.departureManager.watch(foundMembers);
        }
    }

    private String findMember(List<String> currentMembers, List<String> foundMembers, Date oldest) throws Exception
    {
        String previousMember = foundMembers.isEmpty() ? null : foundMembers.get(foundMembers.size() - 1);

        SimpleEventQuery query = new SimpleEventQuery(0, 1);
        if (!this.notificationConfiguration.areEmailsEnabled()) {
            // Without mails, only the events which are waiting to be pre-filtered have something pending
            query.eq(Event.FIELD_PREFILTERED, false);
        }
        if (oldest != null) {
            query.greaterOrEq(Event.FIELD_DATE, oldest);
        }
        query.not().eq(Event.FIELD_REMOTE_OBSERVATION_ID, null);
        query.not().in(Event.FIELD_REMOTE_OBSERVATION_ID, currentMembers);
        if (previousMember != null) {
            query.greater(Event.FIELD_REMOTE_OBSERVATION_ID, previousMember);
        }
        query.addSort(Event.FIELD_REMOTE_OBSERVATION_ID, Order.ASC);

        try (EventSearchResult result = this.events.search(query, Set.of(Event.FIELD_REMOTE_OBSERVATION_ID))) {
            String memberId = result.stream().findFirst().map(Event::getRemoteObservationId).orElse(null);

            // Protect against a store which would not support the query
            return memberId != null && !currentMembers.contains(memberId) && !foundMembers.contains(memberId)
                ? memberId : null;
        }
    }

    private void takeOver()
    {
        List<String> memberIds;
        long currentTenure;
        synchronized (this.stateLock) {
            this.takeOverScheduled = false;
            memberIds = new ArrayList<>(this.pendingDepartures);
            this.pendingDepartures.clear();
            currentTenure = this.tenure;
        }

        if (!this.singletonManager.isSingletonNode()) {
            return;
        }

        // The members which are still departed and which were not taken over yet for that departure
        Map<String, Date> departures = new LinkedHashMap<>();
        for (String memberId : memberIds) {
            Date departure = this.departureManager.getDepartureDate(memberId);
            if (departure != null && this.departureManager.isDeparted(memberId)) {
                synchronized (this.stateLock) {
                    if (this.tenure == currentTenure && !departure.equals(this.takenOver.get(memberId))) {
                        this.takenOver.put(memberId, departure);
                        departures.put(memberId, departure);
                    }
                }
            }
        }

        if (!departures.isEmpty()) {
            try {
                takeOver(departures, currentTenure);
            } catch (InterruptedException e) {
                // The instance is stopping
                Thread.currentThread().interrupt();
            }
        }
    }

    private void takeOver(Map<String, Date> departures, long currentTenure) throws InterruptedException
    {
        for (Map.Entry<String, Date> departure : departures.entrySet()) {
            Date oldest = getOldestDate(departure.getValue());
            if (oldest != null) {
                this.logger.info("Taking over the pending notifications of the cluster member [{}] gone for good, for"
                    + " the events it produced from [{}] (it left at [{}])", departure.getKey(), oldest,
                    departure.getValue());
                logIgnoredEvents(departure.getKey(), oldest);
            } else {
                this.logger.info("Taking over the pending notifications of the cluster member [{}] gone for good (it"
                    + " left at [{}])", departure.getKey(), departure.getValue());
            }
        }

        if (this.notificationConfiguration.areEmailsEnabled()) {
            // Resume the pending mails first, so that the job does not find the mails created by the pre-filtering
            // below (it would send them a second time)
            for (List<Map.Entry<String, Date>> group : group(departures)) {
                resumeMails(group);
            }
        }

        // Pre-filter the waiting events of the members, unless the current instance stopped being the singleton node
        synchronized (this.stateLock) {
            if (this.tenure == currentTenure && this.singletonManager.isSingletonNode()) {
                departures.keySet().forEach(this.dispatcher::addDepartedMember);
            }
        }
        this.scheduler.flush();
    }

    /**
     * Groups the departures whose dates are close enough (within {@code observation.remote.reclaimDelay}) to share the
     * date range of a resume job.
     */
    private List<List<Map.Entry<String, Date>>> group(Map<String, Date> departures)
    {
        List<Map.Entry<String, Date>> sorted = new ArrayList<>(departures.entrySet());
        sorted.sort(Map.Entry.comparingByValue(Comparator.naturalOrder()));

        long span = TimeUnit.SECONDS.toMillis(this.remoteObservationConfiguration.getReclaimDelay());
        List<List<Map.Entry<String, Date>>> groups = new ArrayList<>();
        List<Map.Entry<String, Date>> current = null;
        for (Map.Entry<String, Date> departure : sorted) {
            if (current == null || current.size() >= MAX_JOB_MEMBERS
                || departure.getValue().getTime() - current.get(0).getValue().getTime() > span) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(departure);
        }

        return groups;
    }

    private void resumeMails(List<Map.Entry<String, Date>> group) throws InterruptedException
    {
        List<String> memberIds = group.stream().map(Map.Entry::getKey).toList();
        Date first = group.get(0).getValue();
        Date last = group.get(group.size() - 1).getValue();

        MissingLiveNotificationMailsRequest request = new MissingLiveNotificationMailsRequest(null, memberIds);
        request.setEventDateRange(getOldestDate(first),
            UserEventDispatcher.shift(last, this.remoteObservationConfiguration.getReclaimDelay()));
        request.setReclaim(true);
        Job job = this.mailManager.resumeMails(request);
        if (job != null && !job.join(this.jobWarningDelay, TimeUnit.MILLISECONDS)) {
            this.logger.warn("The resume of the pending live notification mails of the cluster members {} gone for"
                + " good is still running after [{}] seconds, their waiting events will be pre-filtered once it's"
                + " finished", memberIds, TimeUnit.MILLISECONDS.toSeconds(this.jobWarningDelay));
            job.join();
        }
    }

    private void logIgnoredEvents(String memberId, Date oldest)
    {
        SimpleEventQuery query = new SimpleEventQuery(0, 0);
        query.eq(Event.FIELD_PREFILTERED, false);
        query.eq(Event.FIELD_REMOTE_OBSERVATION_ID, memberId);
        query.less(Event.FIELD_DATE, oldest);
        try (EventSearchResult result = this.events.search(query)) {
            if (result.getTotalHits() > 0) {
                this.logger.info("[{}] waiting events produced by the cluster member [{}] before [{}] are not"
                    + " pre-filtered: they are older than observation.remote.reclaimMaxAge", result.getTotalHits(),
                    memberId, oldest);
            }
        } catch (Exception e) {
            this.logger.warn("Failed to count the old events produced by the cluster member [{}]: [{}]", memberId,
                ExceptionUtils.getRootCauseMessage(e));
        }
    }
}
