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
package org.xwiki.index.internal;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.index.TaskManager;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.event.Event;
import org.xwiki.observation.remote.ClusterMemberDepartedEvent;
import org.xwiki.observation.remote.ClusterMemberDepartureManager;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiException;

import static org.apache.commons.lang3.exception.ExceptionUtils.getRootCauseMessage;

/**
 * Takes over the tasks queued by a cluster member which is gone for good. Each cluster member only executes the tasks
 * it queued itself (see {@link DefaultTasksManager}), so the tasks of a member which never comes back with the same
 * identifier would otherwise never be executed.
 * <ul>
 * <li>When the application is ready and when the current instance becomes the cluster singleton node, the identifiers
 * of the other cluster members which queued tasks (during the last
 * {@link RemoteObservationManagerConfiguration#getReclaimMaxAge()} plus
 * {@link RemoteObservationManagerConfiguration#getReclaimDelay()} seconds) are passed to
 * {@link ClusterMemberDepartureManager#watch(Collection)}, in case they left before the current instance started or
 * before it became the singleton node. The departures notified before the application is ready are taken over when
 * it's ready.</li>
 * <li>When a {@link ClusterMemberDepartedEvent} is received, if the member is still departed, its tasks queued before
 * it left (and not more than {@link RemoteObservationManagerConfiguration#getReclaimMaxAge()} seconds before) are
 * assigned to the current instance in the store, wiki by wiki, then added to the queue of the current instance. Since
 * the tasks are assigned in the store, a member which comes back does not execute them a second time. When the tasks
 * assigned in a wiki can't be added to the queue, adding them is retried regularly (and they are loaded anyway when
 * the current instance restarts).</li>
 * </ul>
 * The tasks keep the date at which they were queued: if the current instance leaves for good in turn, the tasks it
 * took over and did not execute yet are taken over by the next singleton node only if they were queued less than
 * {@link RemoteObservationManagerConfiguration#getReclaimMaxAge()} seconds before that second departure.
 * <p>
 * This work is done only on the cluster singleton node, in a dedicated thread. Nothing is done when remote observation
 * is disabled, when the members are never considered gone for good (negative
 * {@link RemoteObservationManagerConfiguration#getReclaimDelay()}), or when another implementation of
 * {@link TaskManager} is used.
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component
@Singleton
@Named(TasksDepartedMemberListener.NAME)
public class TasksDepartedMemberListener extends AbstractClusterSingletonListener implements Disposable
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "org.xwiki.index.internal.TasksDepartedMemberListener";

    /**
     * The delay in seconds before adding again to the queue the tasks assigned to the current instance which could not
     * be added to it.
     */
    static final long LOAD_RETRY_DELAY = 60;

    /**
     * The maximum number of seconds taken into account in a date window (about 100 years).
     */
    private static final long MAX_SECONDS = 3_153_600_000L;

    /**
     * How long to wait for the running work when the component is disposed, in seconds.
     */
    private static final long DISPOSE_TIMEOUT = 5;

    @Inject
    private ClusterMemberDepartureManager departureManager;

    @Inject
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private Provider<TasksStore> tasksStoreProvider;

    @Inject
    private Provider<TaskManager> taskManagerProvider;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private Logger logger;

    /**
     * Runs the scans of the store, the take overs and the retries, one at a time (the thread is only created when the
     * first task is submitted).
     */
    private ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XWiki index tasks of departed cluster members");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The departed members notified before the application is ready, taken over when it's ready.
     */
    private final Set<String> pendingDepartures = ConcurrentHashMap.newKeySet();

    /**
     * The wikis in which tasks were assigned to the current instance but could not be added to its queue. Only
     * accessed from the executor thread.
     */
    private final Set<String> wikisToLoad = new LinkedHashSet<>();

    private volatile boolean applicationReady;

    /**
     * Default constructor.
     */
    public TasksDepartedMemberListener()
    {
        super(NAME, new ApplicationReadyEvent(), new ClusterMemberDepartedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent && !this.remoteObservationManagerContext.isRemoteState()) {
            // Remember it even when the current instance is not the singleton node, to know if the store can be
            // scanned when the current instance becomes the singleton node
            this.applicationReady = true;
        }

        super.onEvent(event, source, data);
    }

    @Override
    protected void processSingletonEvent(Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent) {
            catchUp();
        } else if (event instanceof ClusterMemberDepartedEvent && data instanceof String memberId) {
            if (this.applicationReady) {
                submitTakeOver(memberId);
            } else {
                // The store can't be used yet
                this.pendingDepartures.add(memberId);
            }
        }
    }

    @Override
    protected void onSingletonNodeGained()
    {
        // Before the application is ready, the catch up is done when it's ready
        if (this.applicationReady) {
            catchUp();
        }
    }

    private boolean isEnabled()
    {
        return this.remoteObservationConfiguration.isEnabled()
            && this.remoteObservationConfiguration.getReclaimDelay() >= 0
            && this.remoteObservationConfiguration.getId() != null
            && this.taskManagerProvider.get() instanceof DefaultTasksManager;
    }

    private void catchUp()
    {
        if (isEnabled()) {
            submit(this::scan);
            for (String memberId : List.copyOf(this.pendingDepartures)) {
                this.pendingDepartures.remove(memberId);
                submit(() -> takeOver(memberId));
            }
        }
    }

    private void submitTakeOver(String memberId)
    {
        if (isEnabled()) {
            submit(() -> takeOver(memberId));
        }
    }

    private void submit(Runnable work)
    {
        try {
            this.executor.execute(new ExecutionContextRunnable(work, this.componentManager));
        } catch (RejectedExecutionException e) {
            // The component is disposed: the instance is stopping
            this.logger.debug("Ignoring the work submitted to a disposed listener", e);
        }
    }

    /**
     * @param reference the date from which the maximum age is counted
     * @param extraSeconds a number of seconds added to the maximum age
     * @return the date of the oldest tasks to take into account, {@code null} for no limit
     */
    private Date getOldest(Date reference, long extraSeconds)
    {
        long maxAge = this.remoteObservationConfiguration.getReclaimMaxAge();
        if (maxAge < 0) {
            return null;
        }

        // Clamp absurd values instead of overflowing
        long maxAgeMillis = TimeUnit.SECONDS.toMillis(Math.min(maxAge + Math.max(extraSeconds, 0), MAX_SECONDS));

        return new Date(Math.max(reference.getTime() - maxAgeMillis, 0));
    }

    private void scan()
    {
        String currentId = this.remoteObservationConfiguration.getId();
        // The departure date of a member, from which its window is counted, is at least the delay before now: don't
        // miss the members whose remaining tasks are all at the beginning of their window
        Date oldest = getOldest(new Date(), this.remoteObservationConfiguration.getReclaimDelay());
        Set<String> memberIds = new LinkedHashSet<>();
        for (String wikiId : getWikiIds()) {
            if (!this.singletonManager.isSingletonNode()) {
                // The next singleton node scans the store
                return;
            }

            try {
                memberIds.addAll(this.tasksStoreProvider.get().getInstanceIds(wikiId, currentId, oldest));
            } catch (XWikiException e) {
                this.logger.warn("Failed to list the cluster members with tasks in wiki [{}]. Cause: [{}].", wikiId,
                    getRootCauseMessage(e));
            }
        }

        if (!memberIds.isEmpty()) {
            this.logger.debug("Cluster members with tasks: {}", memberIds);
            this.departureManager.watch(memberIds);
        }
    }

    private boolean canTakeOver(String memberId)
    {
        return this.singletonManager.isSingletonNode() && this.departureManager.isDeparted(memberId);
    }

    private void takeOver(String memberId)
    {
        if (!isEnabled() || !canTakeOver(memberId)) {
            return;
        }

        Date departure = this.departureManager.getDepartureDate(memberId);
        if (departure == null) {
            // Came back meanwhile
            return;
        }

        String currentId = this.remoteObservationConfiguration.getId();
        Date oldest = getOldest(departure, 0);
        TasksStore tasksStore = this.tasksStoreProvider.get();
        long takenOver = 0;
        long left = 0;
        for (String wikiId : getWikiIds()) {
            // Checked before each wiki: a member which comes back handles its remaining tasks itself
            if (!canTakeOver(memberId)) {
                this.logger.info("Stopped taking over the tasks of cluster member [{}]: it came back or this instance"
                    + " is not the cluster singleton node anymore", memberId);
                break;
            }

            try {
                int count = tasksStore.reassignTasks(wikiId, memberId, currentId, oldest, departure);
                if (count > 0) {
                    takenOver += count;
                    load(wikiId, memberId, count);
                }
                left += tasksStore.countTasks(wikiId, memberId);
            } catch (XWikiException e) {
                this.logger.warn("Failed to take over the tasks of cluster member [{}] in wiki [{}]. Cause: [{}].",
                    memberId, wikiId, getRootCauseMessage(e));
            }
        }

        logTakeOver(memberId, departure, takenOver, left);
    }

    private void load(String wikiId, String memberId, int count)
    {
        try {
            getTasksManager().loadTasks(wikiId);
        } catch (XWikiException e) {
            this.logger.warn("[{}] tasks of cluster member [{}] were assigned to this instance in wiki [{}] but could"
                + " not be added to its queue: retrying in [{}] seconds (they are also loaded when this instance"
                + " restarts). Cause: [{}].", count, memberId, wikiId, LOAD_RETRY_DELAY, getRootCauseMessage(e));
            scheduleLoadRetry(wikiId);
        }
    }

    private void scheduleLoadRetry(String wikiId)
    {
        // Schedule a retry only if none is pending
        if (this.wikisToLoad.add(wikiId) && this.wikisToLoad.size() == 1) {
            try {
                this.executor.schedule(new ExecutionContextRunnable(this::retryLoads, this.componentManager),
                    LOAD_RETRY_DELAY, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                // The component is disposed: the tasks are loaded when this instance restarts
                this.logger.debug("Ignoring the retry of a disposed listener", e);
            }
        }
    }

    private void retryLoads()
    {
        List<String> wikiIds = List.copyOf(this.wikisToLoad);
        this.wikisToLoad.clear();
        for (String wikiId : wikiIds) {
            try {
                int count = getTasksManager().loadTasks(wikiId);
                this.logger.info("Added [{}] tasks taken over from departed cluster members in wiki [{}] to the queue",
                    count, wikiId);
            } catch (XWikiException e) {
                this.logger.debug("Failed again to add the tasks taken over in wiki [{}] to the queue", wikiId, e);
                scheduleLoadRetry(wikiId);
            }
        }
    }

    private DefaultTasksManager getTasksManager()
    {
        return (DefaultTasksManager) this.taskManagerProvider.get();
    }

    private void logTakeOver(String memberId, Date departure, long takenOver, long left)
    {
        if (takenOver > 0) {
            this.logger.info("Took over [{}] tasks queued by cluster member [{}], which is gone for good (absent since"
                + " [{}])", takenOver, memberId, departure);
        } else {
            this.logger.debug("No task to take over for cluster member [{}]", memberId);
        }
        if (left > 0) {
            this.logger.info("[{}] tasks queued by cluster member [{}] were not taken over because they are dated"
                + " more than [{}] seconds before its departure, or after it", left, memberId,
                this.remoteObservationConfiguration.getReclaimMaxAge());
        }
    }

    private List<String> getWikiIds()
    {
        try {
            return List.copyOf(this.wikiDescriptorManager.getAllIds());
        } catch (WikiManagerException e) {
            this.logger.warn(
                "Failed to list the wikis to take over the tasks of departed cluster members. Cause: [{}].",
                getRootCauseMessage(e));

            return List.of();
        }
    }

    @Override
    public void dispose()
    {
        // A running take over stops at the next wiki since the instance is not the singleton node anymore
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(DISPOSE_TIMEOUT, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
