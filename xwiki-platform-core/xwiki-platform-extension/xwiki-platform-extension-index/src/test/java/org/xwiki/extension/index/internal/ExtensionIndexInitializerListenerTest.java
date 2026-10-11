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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.context.internal.DefaultExecution;
import org.xwiki.context.internal.DefaultExecutionContextManager;
import org.xwiki.extension.index.internal.job.ExtensionIndexJobScheduler;
import org.xwiki.model.namespace.WikiNamespace;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExtensionIndexInitializerListener}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({ DefaultExecution.class, DefaultExecutionContextManager.class })
class ExtensionIndexInitializerListenerTest
{
    private static final long TIMEOUT = 10000;

    /**
     * How long to wait before checking that something did not happen asynchronously.
     */
    private static final long NOTHING_HAPPENS_DELAY = 500;

    private static final String GAIN_MESSAGE =
        "This instance became the cluster singleton node: the extensions will be indexed in [%s] seconds if it is"
            + " still the singleton node";

    @InjectMockComponents
    private ExtensionIndexInitializerListener listener;

    @MockComponent
    private ExtensionIndexJobScheduler scheduler;

    @MockComponent
    private ExtensionIndexClusterConfiguration clusterConfiguration;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @BeforeEach
    void beforeEach()
    {
        when(this.clusterConfiguration.isSingletonNodeRequired()).thenReturn(true);
    }

    @AfterEach
    void afterEach()
    {
        this.listener.dispose();
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
        assertEquals(3, this.listener.getEvents().size());
        assertEquals(ExtensionIndexInitializerListener.NAME, this.listener.getName());
    }

    @Test
    void singletonNodeNotRequired()
    {
        when(this.clusterConfiguration.isSingletonNodeRequired()).thenReturn(false);

        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.scheduler).initialize(new WikiNamespace("subwiki"));
        verify(this.scheduler).start(false);

        // The changes of singleton node are ignored
        notifySingletonNodeChanged(false);
        notifySingletonNodeChanged(true);

        verify(this.scheduler, never()).stop();
        verify(this.scheduler, after(NOTHING_HAPPENS_DELAY)).start(anyBoolean());
        verifyNoInteractions(this.singletonManager);
    }

    @Test
    void singletonNodeAtStartup()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.scheduler).initialize(new WikiNamespace("subwiki"));
        verify(this.scheduler).start(true);

        // Already taken into account
        notifySingletonNodeChanged(true);
        verify(this.scheduler, after(NOTHING_HAPPENS_DELAY)).start(anyBoolean());

        notifySingletonNodeChanged(false);
        verify(this.scheduler).stop();
    }

    @Test
    void otherMemberAtStartup()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verifyNoInteractions(this.scheduler);

        // Catch-up when the current instance becomes the singleton node (no delay configured)
        notifySingletonNodeChanged(true);
        verify(this.scheduler, timeout(TIMEOUT)).start(true);
        assertEquals(String.format(GAIN_MESSAGE, 0), this.logCapture.getMessage(0));

        notifySingletonNodeChanged(false);
        verify(this.scheduler).stop();

        // Started again when the current instance becomes the singleton node again
        notifySingletonNodeChanged(true);
        verify(this.scheduler, timeout(TIMEOUT).times(2)).start(true);
        assertEquals(String.format(GAIN_MESSAGE, 0), this.logCapture.getMessage(1));
    }

    @Test
    void singletonNodeLostBeforeTheDelay()
    {
        when(this.clusterConfiguration.getIndexOnSingletonGainDelay()).thenReturn(3600);
        when(this.singletonManager.isSingletonNode()).thenReturn(false);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        notifySingletonNodeChanged(true);
        assertEquals(String.format(GAIN_MESSAGE, 3600), this.logCapture.getMessage(0));

        // Losing the role cancels the pending start
        notifySingletonNodeChanged(false);
        verify(this.scheduler).stop();

        // The pending start was cancelled
        verify(this.scheduler, after(NOTHING_HAPPENS_DELAY).never()).start(anyBoolean());
    }

    @Test
    void notSingletonNodeAnymoreWhenTheDelayExpires()
    {
        when(this.clusterConfiguration.getIndexOnSingletonGainDelay()).thenReturn(1);
        when(this.singletonManager.isSingletonNode()).thenReturn(false);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        notifySingletonNodeChanged(true);
        assertEquals(String.format(GAIN_MESSAGE, 1), this.logCapture.getMessage(0));

        // The change is not notified yet when the delay expires
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        verify(this.singletonManager, timeout(TIMEOUT).times(3)).isSingletonNode();
        verify(this.scheduler, after(NOTHING_HAPPENS_DELAY).never()).start(anyBoolean());
    }

    @Test
    void singletonNodeGainedBeforeApplicationReady()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        // Makes the listener ready
        this.listener.onEvent(new WikiReadyEvent("wiki"), null, null);

        // The application is not ready yet: the indexer is started by the application ready event
        notifySingletonNodeChanged(true);
        verify(this.scheduler, after(NOTHING_HAPPENS_DELAY).never()).start(anyBoolean());

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        verify(this.scheduler).start(true);
    }

    @Test
    void remoteEventsIgnored()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);

        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        // A remote ApplicationReadyEvent does not mean that this instance is ready
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(false);
        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);
        notifySingletonNodeChanged(true);

        verify(this.scheduler, after(NOTHING_HAPPENS_DELAY).never()).start(anyBoolean());
        verify(this.scheduler, never()).initialize(new WikiNamespace("subwiki"));
    }
}
