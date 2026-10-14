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
package org.xwiki.notifications.notifiers.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.events.EventStreamAddedEvent;
import org.xwiki.notifications.NotificationConfiguration;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link UserEventDispatcherListener}.
 *
 * @version $Id$
 */
@ComponentTest
class UserEventDispatcherListenerTest
{
    @InjectMockComponents
    private UserEventDispatcherListener listener;

    @MockComponent
    private UserEventDispatcherScheduler scheduler;

    @MockComponent
    private NotificationConfiguration notificationConfiguration;

    @MockComponent
    private RemoteObservationManagerContext remoteState;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @BeforeEach
    void beforeEach()
    {
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
    }

    @Test
    void onApplicationReady()
    {
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.scheduler).initialize();
    }

    @Test
    void onEventStreamAdded()
    {
        Event event = mock(Event.class);

        this.listener.onEvent(new EventStreamAddedEvent(), event, null);

        verify(this.scheduler).onEvent(event);

        when(this.remoteState.isRemoteState()).thenReturn(true);
        Event remoteEvent = mock(Event.class);

        this.listener.onEvent(new EventStreamAddedEvent(), remoteEvent, null);

        verify(this.scheduler, never()).onEvent(remoteEvent);
    }

    @Test
    void onSingletonNodeGained()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, true);

        // Pre-filter the events without remote observation identifier left by the previous singleton node
        verify(this.scheduler).flush();
    }

    @Test
    void onSingletonNodeLost()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        // Outdated gain
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, true);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, false);

        verify(this.scheduler, never()).flush();
    }

    @Test
    void notificationsDisabled()
    {
        when(this.notificationConfiguration.isEnabled()).thenReturn(false);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        this.listener.onEvent(new EventStreamAddedEvent(), mock(Event.class), null);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, true);

        verify(this.scheduler, never()).initialize();
        verify(this.scheduler, never()).onEvent(any());
        verify(this.scheduler, never()).flush();
    }
}
