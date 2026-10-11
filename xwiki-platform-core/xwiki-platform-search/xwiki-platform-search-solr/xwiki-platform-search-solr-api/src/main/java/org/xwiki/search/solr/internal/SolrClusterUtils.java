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
package org.xwiki.search.solr.internal;

import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.search.solr.internal.api.SolrConfiguration;

/**
 * Helpers for the Solr listeners running in a cluster.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
final class SolrClusterUtils
{
    private SolrClusterUtils()
    {
        // Utility class.
    }

    /**
     * Indicate if the current event comes from another cluster member and the Solr index is shared by all the cluster
     * members.
     * <p>
     * A remote Solr server ({@code solr.type=remote}) is expected to be shared by all the cluster members, so the
     * member where a change happened is the only one that needs to send it to the index: doing it on each member only
     * sends redundant (and possibly out of order) updates for the same entities and multiplies the indexing work by
     * the number of members. An embedded Solr index belongs to the member running it, so it is updated for both local
     * and remote events.
     *
     * @param remoteObservationManagerContext used to know if the current event comes from another cluster member
     * @param solrConfiguration used to know the type of Solr server
     * @return {@code true} if the current event is a remote event and the Solr index is shared by all the cluster
     *         members, in which case the listeners updating the index should ignore it
     */
    static boolean isRemoteEventOnSharedIndex(RemoteObservationManagerContext remoteObservationManagerContext,
        SolrConfiguration solrConfiguration)
    {
        return remoteObservationManagerContext.isRemoteState()
            && RemoteSolr.TYPE.equals(solrConfiguration.getServerType());
    }
}
