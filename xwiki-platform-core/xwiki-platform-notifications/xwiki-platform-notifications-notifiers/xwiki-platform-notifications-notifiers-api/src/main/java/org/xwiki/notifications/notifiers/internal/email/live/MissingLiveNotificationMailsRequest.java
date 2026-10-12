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
import java.util.List;

import org.xwiki.job.AbstractRequest;
import org.xwiki.job.Request;

/**
 * The request used to configure {@link MissingLiveNotificationMailsJob}.
 * 
 * @version $Id$
 * @since 12.6
 */
public class MissingLiveNotificationMailsRequest extends AbstractRequest
{
    /**
     * Serialization identifier.
     */
    private static final long serialVersionUID = 1L;

    private static final String PROPERTY_WIKI = "wiki";

    private static final String PROPERTY_REMOTE_OBSERVATION_IDS = "remoteObservationIds";

    private static final String PROPERTY_EVENT_DATE_FROM = "eventDateFrom";

    private static final String PROPERTY_EVENT_DATE_TO = "eventDateTo";

    private static final String PROPERTY_RECLAIM = "reclaim";

    /**
     * The default constructor.
     */
    public MissingLiveNotificationMailsRequest()
    {
    }

    /**
     * @param request the request to copy
     */
    public MissingLiveNotificationMailsRequest(Request request)
    {
        super(request);
    }

    /**
     * @param wiki the id of the wiki in which to search for user having enabled live notification mails
     */
    public MissingLiveNotificationMailsRequest(String wiki)
    {
        setWiki(wiki);

        // This job's log is not isolated so we only want important log
        setVerbose(false);
    }

    /**
     * @param wiki the id of the wiki in which to search for user having enabled live notification mails, or
     *            {@code null} for all the wikis
     * @param remoteObservationIds the remote observation identifiers of the cluster members whose events should be
     *            resumed (see {@link #setRemoteObservationIds(List)}), or {@code null} for all the events
     * @since 18.9.0RC1
     */
    public MissingLiveNotificationMailsRequest(String wiki, List<String> remoteObservationIds)
    {
        this(wiki);

        setRemoteObservationIds(remoteObservationIds);
    }

    /**
     * @param wikiId the wikiId to set
     */
    public void setWiki(String wikiId)
    {
        setProperty(PROPERTY_WIKI, wikiId);
    }

    /**
     * @return the wikiId, or {@code null} for all the wikis
     */
    public String getWiki()
    {
        return getProperty(PROPERTY_WIKI);
    }

    /**
     * Restricts the events whose mails are resumed to the events produced by some members of the cluster, according
     * to the remote observation identifier stored in each event (the {@code observation.remote.id} of the member which
     * produced it, which is also the member which created its mail entities).
     *
     * @param remoteObservationIds the remote observation identifiers of the cluster members whose events should be
     *            resumed, a {@code null} element standing for the events without remote observation identifier, or
     *            {@code null} for all the events whatever their remote observation identifier (the default)
     * @since 18.9.0RC1
     */
    public void setRemoteObservationIds(List<String> remoteObservationIds)
    {
        if (remoteObservationIds != null) {
            setProperty(PROPERTY_REMOTE_OBSERVATION_IDS, new ArrayList<>(remoteObservationIds));
        } else {
            removeProperty(PROPERTY_REMOTE_OBSERVATION_IDS);
        }
    }

    /**
     * @return the remote observation identifiers of the cluster members whose events should be resumed, a
     *         {@code null} element standing for the events without remote observation identifier, or {@code null} for
     *         all the events whatever their remote observation identifier
     * @since 18.9.0RC1
     */
    public List<String> getRemoteObservationIds()
    {
        return getProperty(PROPERTY_REMOTE_OBSERVATION_IDS);
    }

    /**
     * Restricts the events whose mails are resumed to the events produced in a date range. Used to take over the
     * pending mails of a cluster member gone for good: only the events it produced before it left, and not too long
     * before (see {@code observation.remote.reclaimMaxAge}).
     *
     * @param from the date from which the events are resumed (included), or {@code null} for no lower bound
     * @param to the date until which the events are resumed (excluded), or {@code null} for no upper bound
     * @since 18.9.0RC1
     */
    public void setEventDateRange(Date from, Date to)
    {
        setDateProperty(PROPERTY_EVENT_DATE_FROM, from);
        setDateProperty(PROPERTY_EVENT_DATE_TO, to);
    }

    private void setDateProperty(String key, Date date)
    {
        if (date != null) {
            setProperty(key, new Date(date.getTime()));
        } else {
            removeProperty(key);
        }
    }

    /**
     * @return the date from which the events are resumed (included), or {@code null} for no lower bound
     * @since 18.9.0RC1
     */
    public Date getEventDateFrom()
    {
        return getProperty(PROPERTY_EVENT_DATE_FROM);
    }

    /**
     * @return the date until which the events are resumed (excluded), or {@code null} for no upper bound
     * @since 18.9.0RC1
     */
    public Date getEventDateTo()
    {
        return getProperty(PROPERTY_EVENT_DATE_TO);
    }

    /**
     * Indicates that the job takes over the pending mails of cluster members gone for good (see
     * {@link org.xwiki.observation.remote.ClusterMemberDepartureManager}). The job then checks before each user that
     * each requested member is still gone for good and that the current instance is still the cluster singleton node,
     * and stops resuming the events of a member which came back (it resumes its own mails when it starts).
     *
     * @param reclaim {@code true} to take over the pending mails of cluster members gone for good, {@code false} (the
     *            default) to resume the mails without checking the members
     * @since 18.9.0RC1
     */
    public void setReclaim(boolean reclaim)
    {
        setProperty(PROPERTY_RECLAIM, reclaim);
    }

    /**
     * @return {@code true} if the job takes over the pending mails of cluster members gone for good
     * @see #setReclaim(boolean)
     * @since 18.9.0RC1
     */
    public boolean isReclaim()
    {
        return getProperty(PROPERTY_RECLAIM, false);
    }
}
