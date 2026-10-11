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
package org.xwiki.extension.security.internal.listener;

import java.util.List;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.extension.index.internal.ExtensionIndexClusterConfiguration;
import org.xwiki.extension.index.internal.job.ExtensionIndexJob;
import org.xwiki.extension.security.internal.ExtensionSecurityScheduler;
import org.xwiki.job.event.JobFinishedEvent;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExtensionSecurityInitializerListener}.
 *
 * @version $Id$
 */
@ComponentTest
class ExtensionSecurityInitializerListenerTest
{
    @InjectMockComponents
    private ExtensionSecurityInitializerListener listener;

    @MockComponent
    private Provider<ExtensionSecurityScheduler> schedulerProvider;

    @MockComponent
    private ExtensionSecurityScheduler scheduler;

    @MockComponent
    private ExtensionIndexClusterConfiguration clusterConfiguration;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @BeforeEach
    void beforeEach()
    {
        when(this.schedulerProvider.get()).thenReturn(this.scheduler);
        when(this.clusterConfiguration.isSingletonNodeRequired()).thenReturn(true);
    }

    private void indexJobFinished()
    {
        this.listener.onEvent(new JobFinishedEvent(List.of("extension", "index"), ExtensionIndexJob.JOB_TYPE, null),
            null, null);
    }

    private void otherJobFinished()
    {
        this.listener.onEvent(new JobFinishedEvent(List.of("other"), "other", null), null, null);
    }

    private void notifySingletonNodeChanged(boolean singletonNode)
    {
        // The listener reads the current state from the manager
        when(this.singletonManager.isSingletonNode()).thenReturn(singletonNode);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, singletonNode);
    }

    @Test
    void getEvents()
    {
        assertEquals(2, this.listener.getEvents().size());
        assertEquals(ExtensionSecurityInitializerListener.NAME, this.listener.getName());
    }

    @Test
    void singletonNodeNotRequired()
    {
        when(this.clusterConfiguration.isSingletonNodeRequired()).thenReturn(false);

        otherJobFinished();
        verifyNoInteractions(this.scheduler);

        indexJobFinished();
        verify(this.scheduler).start();

        // Remote events are ignored
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);
        indexJobFinished();
        verify(this.scheduler).start();

        // The changes of singleton node are ignored
        notifySingletonNodeChanged(false);
        verify(this.scheduler, never()).stop();
        verifyNoInteractions(this.singletonManager);
    }

    @Test
    void singletonNode()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        otherJobFinished();
        verifyNoInteractions(this.scheduler);

        indexJobFinished();
        verify(this.scheduler).start();

        notifySingletonNodeChanged(false);
        verify(this.scheduler).stop();

        // The scan starts again after the index job started by the new singleton node
        notifySingletonNodeChanged(true);
        verify(this.scheduler).start();

        indexJobFinished();
        verify(this.scheduler, times(2)).start();
    }

    @Test
    void otherMember()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        // For example an indexing explicitly started by an administrator on this instance
        indexJobFinished();
        verifyNoInteractions(this.scheduler);

        // The scan starts after the index job started by the new singleton node
        notifySingletonNodeChanged(true);
        verifyNoInteractions(this.scheduler);

        indexJobFinished();
        verify(this.scheduler).start();

        notifySingletonNodeChanged(false);
        verify(this.scheduler).stop();
    }
}
