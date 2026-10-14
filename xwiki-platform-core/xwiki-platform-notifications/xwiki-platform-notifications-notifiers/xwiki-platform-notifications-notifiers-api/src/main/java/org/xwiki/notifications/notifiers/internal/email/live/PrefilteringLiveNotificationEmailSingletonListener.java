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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.notifications.NotificationConfiguration;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.event.Event;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;

/**
 * Resumes, on the cluster singleton node, the live notification mails which were not sent yet for the events without
 * remote observation identifier (events which don't belong to any member of the cluster, for example events stored by
 * an old version of XWiki), for all the wikis: when the application is ready and when the current instance becomes
 * the cluster singleton node. The mails of the events produced by a member of the cluster are resumed by that member
 * (see {@link PrefilteringLiveNotificationEmailListener}). Without cluster, this listener does nothing since
 * {@link PrefilteringLiveNotificationEmailListener} resumes all the mails.
 * <p>
 * Only the events which still have a mail entity (deleted when the mail is prepared for sending) are queued, and an
 * event which is already waiting in the queue of the current instance is not queued twice. An event can still be sent
 * twice if it's resumed between its dispatch and the preparation of its mail, or if it's also waiting in the queue of
 * another member (the previous singleton node when it lost the role while still running).
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component
@Singleton
@Named(PrefilteringLiveNotificationEmailSingletonListener.NAME)
public class PrefilteringLiveNotificationEmailSingletonListener extends AbstractClusterSingletonListener
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "Prefiltering Live Notification Email Singleton Listener";

    @Inject
    private NotificationConfiguration notificationConfiguration;

    @Inject
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @Inject
    private PrefilteringLiveNotificationEmailManager manager;

    /**
     * Constructs a new {@link PrefilteringLiveNotificationEmailSingletonListener}.
     */
    public PrefilteringLiveNotificationEmailSingletonListener()
    {
        super(NAME, new ApplicationReadyEvent());
    }

    @Override
    protected void processSingletonEvent(Event event, Object source, Object data)
    {
        resume();
    }

    @Override
    protected void onSingletonNodeGained()
    {
        // The previous singleton node might have stopped before sending these mails
        resume();
    }

    private void resume()
    {
        if (this.remoteObservationConfiguration.isEnabled() && this.notificationConfiguration.isEnabled()
            && this.notificationConfiguration.areEmailsEnabled()) {
            // All the wikis: the subwikis are loaded on the first request reaching them, which might never happen on
            // the singleton node (for example an instance dedicated to background tasks)
            // Don't block the event thread
            this.manager.addEvents(null, Collections.singletonList(null));
        }
    }
}
