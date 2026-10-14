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
package org.xwiki.extension.distribution.internal;

import jakarta.inject.Named;

import org.junit.jupiter.api.Test;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Validate {@link DistributionConfiguration}.
 *
 * @version $Id$
 */
@ComponentTest
class DistributionConfigurationTest
{
    @InjectMockComponents
    private DistributionConfiguration configuration;

    @MockComponent
    private ConfigurationSource configurationSource;

    @MockComponent
    @Named("xwikiproperties")
    private ConfigurationSource xwikiProperties;

    @Test
    void interactiveUnlessExitAfterUpgrade()
    {
        when(this.configurationSource.getProperty("distribution.job.interactive", true)).thenReturn(true);
        when(this.configurationSource.getProperty("distribution.job.interactive.wiki", true)).thenReturn(true);
        when(this.xwikiProperties.getProperty(DistributionConfiguration.PROPERTY_EXIT_AFTER_UPGRADE, false))
            .thenReturn(false);

        assertFalse(this.configuration.isExitAfterUpgrade());
        assertTrue(this.configuration.isInteractiveDistributionWizardEnabledForMainWiki());
        assertTrue(this.configuration.isInteractiveDistributionWizardEnabledForWiki());

        when(this.xwikiProperties.getProperty(DistributionConfiguration.PROPERTY_EXIT_AFTER_UPGRADE, false))
            .thenReturn(true);

        assertTrue(this.configuration.isExitAfterUpgrade());
        assertFalse(this.configuration.isInteractiveDistributionWizardEnabledForMainWiki());
        assertFalse(this.configuration.isInteractiveDistributionWizardEnabledForWiki());
    }
}
