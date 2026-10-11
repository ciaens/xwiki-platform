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
package org.xwiki.notifications.notifiers.internal.email.live;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.eventstream.EntityEvent;
import org.xwiki.eventstream.events.MailEntityAddedEvent;
import org.xwiki.notifications.NotificationConfiguration;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link PrefilteringLiveNotificationEmailListener}.
 * 
 * @version $Id$
 */
@ComponentTest
class PrefilteringLiveNotificationEmailListenerTest
{
    private static final MailEntityAddedEvent EVENT = new MailEntityAddedEvent();

    @InjectMockComponents
    private PrefilteringLiveNotificationEmailListener listener;

    @MockComponent
    private NotificationConfiguration notificationConfiguration;

    @MockComponent
    private RemoteObservationManagerContext remoteState;

    @MockComponent
    private PrefilteringLiveNotificationEmailManager manager;

    @MockComponent
    private WikiDescriptorManager wikis;

    @MockComponent
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @Test
    void onEvent()
    {
        EntityEvent event = mock(EntityEvent.class);

        when(this.remoteState.isRemoteState()).thenReturn(false);
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);

        this.listener.onEvent(EVENT, event, null);
        verify(this.manager).addEvent(event);

        when(this.remoteState.isRemoteState()).thenReturn(true);
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);

        this.listener.onEvent(EVENT, event, null);
        verify(this.manager).addEvent(event);

        when(this.remoteState.isRemoteState()).thenReturn(false);
        when(this.notificationConfiguration.isEnabled()).thenReturn(false);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);

        this.listener.onEvent(EVENT, event, null);
        verify(this.manager).addEvent(event);

        when(this.remoteState.isRemoteState()).thenReturn(false);
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(false);

        this.listener.onEvent(EVENT, event, null);
        verify(this.manager).addEvent(event);
    }

    @Test
    void resumeWithoutCluster()
    {
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);
        when(this.wikis.getMainWikiId()).thenReturn("xwiki");

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);

        // All the events, as before the cluster support
        verify(this.manager).addEvents("xwiki");
        verify(this.manager).addEvents("subwiki");
        verify(this.manager, never()).addEvents(any(), any());
    }

    @Test
    void resumeInCluster()
    {
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
        when(this.remoteObservationConfiguration.getId()).thenReturn("instance1");

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        this.listener.onEvent(new WikiReadyEvent("subwiki"), null, null);

        // Only the events produced by the current instance, in all the wikis when the application is ready (a subwiki
        // might never be loaded on this instance)
        verify(this.manager).addEvents(null, List.of("instance1"));
        verify(this.manager, times(1)).addEvents(any(), any());
        verify(this.manager, never()).addEvents(any());
    }

    @Test
    void resumeWhenMailsDisabled()
    {
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(false);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.manager, never()).addEvents(any());
        verify(this.manager, never()).addEvents(any(), any());
    }
}
