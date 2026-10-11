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
package org.xwiki.extension.index.internal;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.search.solr.internal.RemoteSolr;
import org.xwiki.search.solr.internal.api.SolrConfiguration;

/**
 * Indicates how the work updating the extension index (indexing, cleanup, security scan) is distributed between the
 * members of a cluster.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
@Component(roles = ExtensionIndexClusterConfiguration.class)
@Singleton
public class ExtensionIndexClusterConfiguration
{
    /**
     * The name of the property indicating how long to wait, in seconds, before indexing the extensions when the
     * current instance becomes the cluster singleton node.
     */
    public static final String PROPERTY_SINGLETON_GAIN_DELAY = "extension.index.indexOnSingletonGainDelay";

    /**
     * The default delay before indexing the extensions when the current instance becomes the cluster singleton node,
     * in seconds.
     */
    public static final int DEFAULT_SINGLETON_GAIN_DELAY = 300;

    @Inject
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @Inject
    private SolrConfiguration solrConfiguration;

    @Inject
    @Named("xwikiproperties")
    private ConfigurationSource configuration;

    /**
     * The configuration does not change at runtime: computed once.
     */
    private volatile Boolean singletonNodeRequired;

    /**
     * Indicates if the work updating the extension index must run on the cluster singleton node only. It's the case
     * when the extension index is shared by all the members of the cluster: remote observation is enabled and the
     * Solr server is remote (a remote Solr server is assumed to be shared by all the members). Without cluster, or
     * when each member has its own embedded Solr (and so its own extension index), each instance updates its own
     * index.
     *
     * @return {@code true} if the work updating the extension index must run on the cluster singleton node only
     * @since 18.9.0RC1
     */
    public boolean isSingletonNodeRequired()
    {
        Boolean required = this.singletonNodeRequired;
        if (required == null) {
            required = this.remoteObservationConfiguration.isEnabled()
                && RemoteSolr.TYPE.equals(this.solrConfiguration.getServerType());
            this.singletonNodeRequired = required;
        }

        return required;
    }

    /**
     * Indicates how long to wait before indexing the extensions when the current instance becomes the cluster
     * singleton node after startup (property {@value #PROPERTY_SINGLETON_GAIN_DELAY}), so that a short restart of the
     * preferred member does not trigger an indexing on another member.
     *
     * @return the delay in seconds, {@value #DEFAULT_SINGLETON_GAIN_DELAY} by default
     * @since 18.9.0RC1
     */
    public int getIndexOnSingletonGainDelay()
    {
        return this.configuration.getProperty(PROPERTY_SINGLETON_GAIN_DELAY, DEFAULT_SINGLETON_GAIN_DELAY);
    }
}
