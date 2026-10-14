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
package org.xwiki.notifications.notifiers.internal.email.live;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.xwiki.component.annotation.Component;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventSearchResult;
import org.xwiki.eventstream.EventStore;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.query.SimpleEventQuery;
import org.xwiki.job.AbstractJob;
import org.xwiki.job.DefaultJobStatus;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.notifications.notifiers.internal.email.IntervalUsersManager;
import org.xwiki.notifications.preferences.NotificationEmailInterval;
import org.xwiki.observation.remote.ClusterMemberDepartureManager;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.query.QueryException;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

/**
 * Find and process events that were not send to users who enabled live mail yet.
 * <p>
 * The request indicates the wiki of the users (all the wikis when {@code null}) and the cluster members whose events
 * are resumed (see {@link MissingLiveNotificationMailsRequest#setRemoteObservationIds(List)}), optionally restricted to
 * a date range (see {@link MissingLiveNotificationMailsRequest#setEventDateRange(Date, Date)}). When it takes over the
 * pending mails of cluster members gone for good (see {@link MissingLiveNotificationMailsRequest#setReclaim(boolean)}),
 * it checks before each user that the members are still gone for good and that the current instance is still the
 * cluster singleton node.
 * 
 * @version $Id$
 * @since 12.6
 */
@Component
@Named(MissingLiveNotificationMailsJob.JOBTYPE)
public class MissingLiveNotificationMailsJob
    extends AbstractJob<MissingLiveNotificationMailsRequest, DefaultJobStatus<MissingLiveNotificationMailsRequest>>
{
    /**
     * The id of the job.
     */
    public static final String JOBTYPE = "notification.livemails.resume";

    @Inject
    private PrefilteringLiveNotificationEmailDispatcher dispatcher;

    @Inject
    private EventStore eventStore;

    @Inject
    private EntityReferenceSerializer<String> referenceSerializer;

    @Inject
    private IntervalUsersManager intervals;

    @Inject
    private WikiDescriptorManager wikis;

    @Inject
    private ClusterMemberDepartureManager departureManager;

    @Inject
    private ClusterSingletonManager singletonManager;

    /**
     * The remote observation identifiers whose events are still resumed: the requested ones, minus the cluster members
     * which came back when taking over the mails of members gone for good.
     */
    private List<String> remoteObservationIds;

    private boolean stopped;

    private long queuedMails;

    @Override
    public String getType()
    {
        return JOBTYPE;
    }

    @Override
    protected void runInternal() throws Exception
    {
        List<String> requestedIds = getRequest().getRemoteObservationIds();
        if (requestedIds != null && requestedIds.isEmpty()) {
            // No cluster member requested
            return;
        }
        this.remoteObservationIds = requestedIds != null ? new ArrayList<>(requestedIds) : null;

        String wiki = getRequest().getWiki();
        if (wiki != null) {
            resume(wiki);
        } else {
            resumeAllWikis();
        }

        if (getRequest().isReclaim()) {
            this.logger.info("Queued [{}] pending live notification mails of the cluster members {} gone for good",
                this.queuedMails, requestedIds);
        }
    }

    private void resumeAllWikis()
    {
        try {
            for (String wikiId : this.wikis.getAllIds()) {
                if (this.stopped) {
                    break;
                }

                try {
                    resume(wikiId);
                } catch (Exception e) {
                    // Whatever the failure in one wiki, resume the other wikis
                    this.logger.error("Failed to resume the live notification mails of the users of wiki [{}]",
                        wikiId, e);
                }
            }
        } catch (WikiManagerException e) {
            this.logger.error("Failed to get the wikis in which to resume the live notification mails", e);
        }
    }

    private void resume(String wiki) throws QueryException
    {
        List<DocumentReference> users = this.intervals.getUsers(NotificationEmailInterval.LIVE, wiki);

        for (DocumentReference user : users) {
            if (getRequest().isReclaim() && !checkReclaim()) {
                break;
            }

            SimpleEventQuery query = createQuery(user);

            try {
                EventSearchResult result = this.eventStore.search(query);

                result.stream().forEach(event -> {
                    this.dispatcher.addEvent(event, user);
                    this.queuedMails++;
                });
            } catch (EventStreamException e) {
                this.logger.error("Failed to get events to send by mail to the user [{}]", user, e);
            }
        }
    }

    /**
     * Checks, before resuming the mails of a user, that the pending mails of the requested cluster members still have
     * to be taken over by the current instance.
     *
     * @return {@code true} if the job goes on, {@code false} if it stops
     */
    private boolean checkReclaim()
    {
        if (!this.stopped) {
            if (!this.singletonManager.isSingletonNode()) {
                // The new cluster singleton node takes over the mails of the members which are still gone
                this.logger.info("Stopped taking over the pending live notification mails of the cluster members {}"
                    + " gone for good: the current instance is not the cluster singleton node anymore",
                    // Log arguments are kept in the job log: the list is modified afterwards
                    String.valueOf(this.remoteObservationIds));
                this.stopped = true;
            } else if (this.remoteObservationIds != null) {
                for (Iterator<String> it = this.remoteObservationIds.iterator(); it.hasNext();) {
                    String remoteObservationId = it.next();
                    if (!this.departureManager.isDeparted(remoteObservationId)) {
                        // The member resumes its own pending mails when it starts
                        this.logger.info("Stopped taking over the pending live notification mails of the cluster"
                            + " member [{}]: it is not gone anymore", remoteObservationId);
                        it.remove();
                    }
                }
                this.stopped = this.remoteObservationIds.isEmpty();
            }
        }

        return !this.stopped;
    }

    private SimpleEventQuery createQuery(DocumentReference user)
    {
        SimpleEventQuery query = new SimpleEventQuery();

        query.withMail(this.referenceSerializer.serialize(user));

        Date from = getRequest().getEventDateFrom();
        if (from != null) {
            query.greaterOrEq(Event.FIELD_DATE, from);
        }
        Date to = getRequest().getEventDateTo();
        if (to != null) {
            query.less(Event.FIELD_DATE, to);
        }

        if (this.remoteObservationIds != null) {
            // Only the events produced by the requested cluster members: the mails of the events produced by the other
            // members are waiting to be sent by those members
            query.open();
            for (Iterator<String> it = this.remoteObservationIds.iterator(); it.hasNext();) {
                query.eq(Event.FIELD_REMOTE_OBSERVATION_ID, it.next());
                if (it.hasNext()) {
                    query.or();
                }
            }
            query.close();
        }

        return query;
    }
}
