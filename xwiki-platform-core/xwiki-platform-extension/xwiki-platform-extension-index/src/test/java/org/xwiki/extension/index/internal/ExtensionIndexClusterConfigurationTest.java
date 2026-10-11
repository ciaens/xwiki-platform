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

import javax.inject.Named;

import org.junit.jupiter.api.Test;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExtensionIndexClusterConfiguration}.
 *
 * @version $Id$
 */
@ComponentTest
class ExtensionIndexClusterConfigurationTest
{
    @InjectMockComponents
    private ExtensionIndexClusterConfiguration clusterConfiguration;

    @MockComponent
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @MockComponent
    private SolrConfiguration solrConfiguration;

    @MockComponent
    @Named("xwikiproperties")
    private ConfigurationSource configuration;

    @Test
    void isSingletonNodeRequiredWithClusterAndSharedIndex()
    {
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
        when(this.solrConfiguration.isIndexShared()).thenReturn(true);

        assertTrue(this.clusterConfiguration.isSingletonNodeRequired());
        assertTrue(this.clusterConfiguration.isSingletonNodeRequired());

        // Computed once
        verify(this.solrConfiguration, times(1)).isIndexShared();
    }

    @Test
    void isSingletonNodeRequiredWithOwnIndex()
    {
        // Each member has its own index
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
        when(this.solrConfiguration.isIndexShared()).thenReturn(false);

        assertFalse(this.clusterConfiguration.isSingletonNodeRequired());
    }

    @Test
    void isSingletonNodeRequiredWithoutCluster()
    {
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(false);
        when(this.solrConfiguration.isIndexShared()).thenReturn(true);

        assertFalse(this.clusterConfiguration.isSingletonNodeRequired());
    }

    @Test
    void getIndexOnSingletonGainDelay()
    {
        when(this.configuration.getProperty("extension.index.indexOnSingletonGainDelay", 300)).thenReturn(42);

        assertEquals(42, this.clusterConfiguration.getIndexOnSingletonGainDelay());
    }
}
