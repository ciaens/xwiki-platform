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

import java.util.List;

import jakarta.annotation.Priority;

import org.junit.jupiter.api.Test;
import org.xwiki.component.descriptor.ComponentDescriptor;
import org.xwiki.extension.job.internal.InstallJob;
import org.xwiki.job.event.JobFinishingEvent;
import org.xwiki.observation.event.ApplicationStartedEvent;
import org.xwiki.observation.event.ApplicationStoppedEvent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Validate {@link UpgradeAndExitListener}.
 *
 * @version $Id$
 */
@ComponentTest
class UpgradeAndExitListenerTest
{
    @InjectMockComponents
    private UpgradeAndExitListener listener;

    @MockComponent
    private DistributionConfiguration distributionConfiguration;

    @MockComponent
    private UpgradeAndExitRunner runner;

    @Test
    void listenerDefinition()
    {
        assertEquals(UpgradeAndExitListener.NAME, this.listener.getName());
        assertEquals(3, this.listener.getEvents().size());
        assertTrue(this.listener.getEvents().get(0) instanceof ApplicationStartedEvent);
        // After the other listeners of ApplicationStartedEvent, including the eager initialization (2000)
        assertEquals(UpgradeAndExitListener.PRIORITY,
            UpgradeAndExitListener.class.getAnnotation(Priority.class).value());
        assertTrue(UpgradeAndExitListener.PRIORITY > ComponentDescriptor.DEFAULT_PRIORITY + 1000);
    }

    @Test
    void doesNothingWhenNotEnabled()
    {
        this.listener.onEvent(new ApplicationStartedEvent(), null, null);
        this.listener.onEvent(new ApplicationStoppedEvent(), null, null);
        this.listener.onEvent(new JobFinishingEvent(List.of("install"), InstallJob.JOBTYPE, null), null,
            new RuntimeException("failed"));

        verifyNoInteractions(this.runner);
    }

    @Test
    void forwardsTheEventsWhenEnabled()
    {
        when(this.distributionConfiguration.isExitAfterUpgrade()).thenReturn(true);

        this.listener.onEvent(new ApplicationStartedEvent(), null, null);
        verify(this.runner).start();

        RuntimeException error = new RuntimeException("conflict");
        this.listener.onEvent(new JobFinishingEvent(List.of("extension", "ui"), InstallJob.JOBTYPE, null), null,
            error);
        verify(this.runner).onJobFailed(List.of("extension", "ui"), InstallJob.JOBTYPE, error);

        this.listener.onEvent(new ApplicationStoppedEvent(), null, null);
        verify(this.runner).stop();
    }

    @Test
    void ignoresSuccessfulAndUncheckedJobs()
    {
        when(this.distributionConfiguration.isExitAfterUpgrade()).thenReturn(true);

        this.listener.onEvent(new JobFinishingEvent(List.of("extension", "ui"), InstallJob.JOBTYPE, null), null, null);
        this.listener.onEvent(new JobFinishingEvent(List.of("solr"), "solr", null), null, new RuntimeException("x"));

        verify(this.runner, never()).onJobFailed(anyList(), anyString(), any());
    }
}
