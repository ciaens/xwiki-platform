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
package org.xwiki.observation.remote.internal;

import java.util.List;

import jakarta.inject.Named;

import org.junit.jupiter.api.Test;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.observation.ObservationManager;
import org.xwiki.observation.event.ApplicationStoppedEvent;
import org.xwiki.observation.remote.LocalEventData;
import org.xwiki.observation.remote.NetworkAdapter;
import org.xwiki.observation.remote.RemoteEventData;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.observation.remote.converter.EventConverterManager;
import org.xwiki.observation.remote.test.TestEvent;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link DefaultRemoteObservationManager}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultRemoteObservationManagerTest
{
    private static final String ADAPTER_HINT = "test";

    @InjectMockComponents
    private DefaultRemoteObservationManager remoteObservationManager;

    @MockComponent
    private RemoteObservationManagerConfiguration configuration;

    @MockComponent
    @Named(ADAPTER_HINT)
    private NetworkAdapter networkAdapter;

    @MockComponent
    private EventConverterManager eventConverterManager;

    @MockComponent
    private ObservationManager observationManager;

    @MockComponent
    private RemoteObservationManagerContext remoteEventManagerContext;

    @MockComponent
    private Execution execution;

    @BeforeComponent
    void beforeComponent()
    {
        when(this.configuration.getNetworkAdapter()).thenReturn(ADAPTER_HINT);
        when(this.configuration.getChannels()).thenReturn(List.of());
        when(this.execution.getContext()).thenReturn(new ExecutionContext());
    }

    @Test
    void notifyApplicationStoppedEvent() throws Exception
    {
        LocalEventData localEvent = new LocalEventData(new ApplicationStoppedEvent(), null, null);
        // What the converters produce for the local event does not matter here, only the fact that it's sent
        RemoteEventData remoteEvent = new RemoteEventData(new TestEvent(), null, null);
        when(this.eventConverterManager.createRemoteEventData(localEvent)).thenReturn(remoteEvent);

        this.remoteObservationManager.notify(localEvent);

        // The event is still sent to the other members
        verify(this.networkAdapter).send(remoteEvent);
        // But the channels are not closed: that's the job of the network adapter when it's disposed, after the other
        // components had a chance to send their last events
        verify(this.networkAdapter, never()).stopAllChannels();
        verify(this.networkAdapter, never()).stopChannel(anyString());
    }

    @Test
    void notifyRemoteEventWhileStopping()
    {
        TestEvent event = new TestEvent();
        RemoteEventData remoteEvent = new RemoteEventData(event, "source", "data");
        LocalEventData localEvent = new LocalEventData(event, "source", "data");
        when(this.eventConverterManager.createLocalEventData(remoteEvent)).thenReturn(localEvent);

        this.remoteObservationManager.notify(remoteEvent);

        verify(this.observationManager).notify(event, "source", "data");

        // The application is stopping
        LocalEventData stopEvent = new LocalEventData(new ApplicationStoppedEvent(), null, null);
        this.remoteObservationManager.notify(stopEvent);

        // Remote events are not injected anymore
        this.remoteObservationManager.notify(remoteEvent);

        verify(this.observationManager, times(1)).notify(event, "source", "data");
        verify(this.remoteEventManagerContext, times(1)).pushRemoteState();
    }
}
