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

import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.extension.distribution.internal.DistributionManager.DistributionState;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link DistributionInitializerListener}.
 *
 * @version $Id$
 */
@ComponentTest
class DistributionInitializerListenerTest
{
    @InjectMockComponents
    private DistributionInitializerListener listener;

    @MockComponent
    private DistributionManager distributionManager;

    @MockComponent
    private DistributionConfiguration distributionConfiguration;

    @Test
    void nonInteractiveWizardStartedWhenReady()
    {
        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.UPGRADE);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.distributionManager).startFarmJob();
    }

    @Test
    void wizardNotStartedWhenReadyInExitAfterUpgradeMode()
    {
        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.UPGRADE);
        when(this.distributionConfiguration.isExitAfterUpgrade()).thenReturn(true);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.distributionManager, never()).startFarmJob();
    }
}
