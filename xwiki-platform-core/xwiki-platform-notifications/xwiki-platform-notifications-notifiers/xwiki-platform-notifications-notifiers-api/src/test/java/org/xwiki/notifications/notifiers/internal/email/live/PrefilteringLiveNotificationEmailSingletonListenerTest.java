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

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.notifications.NotificationConfiguration;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link PrefilteringLiveNotificationEmailSingletonListener}.
 *
 * @version $Id$
 */
@ComponentTest
class PrefilteringLiveNotificationEmailSingletonListenerTest
{
    @InjectMockComponents
    private PrefilteringLiveNotificationEmailSingletonListener listener;

    @MockComponent
    private NotificationConfiguration notificationConfiguration;

    @MockComponent
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @MockComponent
    private PrefilteringLiveNotificationEmailManager manager;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @BeforeEach
    void beforeEach()
    {
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
    }

    private void setSingletonNode(boolean singletonNode)
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(singletonNode);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, singletonNode);
    }

    private void verifyResumed(int times)
    {
        verify(this.manager, times(times)).addEvents(null, Collections.singletonList(null));
        verify(this.manager, times(times)).addEvents(any(), any());
    }

    @Test
    void singletonNodeAtStartup()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        // The events without remote observation identifier, in all the wikis
        verifyResumed(1);

        // Change already taken into account
        setSingletonNode(true);

        verifyResumed(1);
    }

    @Test
    void otherMemberAtStartup()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verifyResumed(0);
    }

    @Test
    void singletonNodeGainedAfterStartup()
    {
        // Changes before the application is ready are ignored (the ready event checks the state)
        setSingletonNode(true);
        setSingletonNode(false);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verifyResumed(0);

        // The previous singleton node may have left before sending its mails
        setSingletonNode(true);

        verifyResumed(1);

        // Nothing to do on loss: the mails already queued are sent by this instance
        setSingletonNode(false);

        verifyResumed(1);

        setSingletonNode(true);

        verifyResumed(2);
    }

    @Test
    void remoteEvent()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verifyResumed(0);
    }

    @Test
    void withoutCluster()
    {
        // PrefilteringLiveNotificationEmailListener resumes all the events
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(false);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verifyResumed(0);
    }

    @Test
    void mailsDisabled()
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(false);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);
        when(this.notificationConfiguration.isEnabled()).thenReturn(false);

        setSingletonNode(false);
        setSingletonNode(true);

        verifyResumed(0);
    }
}
