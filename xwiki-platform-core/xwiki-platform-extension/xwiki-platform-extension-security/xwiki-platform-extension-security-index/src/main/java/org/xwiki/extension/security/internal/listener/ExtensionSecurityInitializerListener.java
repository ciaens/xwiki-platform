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

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.extension.index.internal.ExtensionIndexClusterConfiguration;
import org.xwiki.extension.security.internal.ExtensionSecurityScheduler;
import org.xwiki.job.event.JobFinishedEvent;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.event.Event;

import static org.xwiki.extension.index.internal.job.ExtensionIndexJob.JOB_TYPE;

/**
 * Starts the {@link ExtensionSecurityScheduler} in charge of fetching known security vulnerabilities for installed
 * extensions once the extension indexing is done.
 * <p>
 * When the extension index is shared by the members of the cluster (see
 * {@link ExtensionIndexClusterConfiguration#isSingletonNodeRequired()}), the scheduler runs only on the cluster
 * singleton node: it's started when an extension indexing job finishes on the singleton node (including the indexing
 * started when the current instance becomes the singleton node), and it's stopped when the current instance stops
 * being the singleton node. Otherwise each instance scans its own index.
 *
 * @version $Id$
 * @since 15.5RC1
 */
@Component
@Named(ExtensionSecurityInitializerListener.NAME)
@Singleton
public class ExtensionSecurityInitializerListener extends AbstractClusterSingletonListener
{
    /**
     * The name of the event listener (and its component hint).
     */
    public static final String NAME = "ExtensionSecurityInitializerListener";

    @Inject
    private Provider<ExtensionSecurityScheduler> schedulerProvider;

    @Inject
    private ExtensionIndexClusterConfiguration clusterConfiguration;

    /**
     * Default constructor.
     */
    public ExtensionSecurityInitializerListener()
    {
        super(NAME, new JobFinishedEvent());
    }

    @Override
    protected boolean isSingletonNodeRequired()
    {
        return this.clusterConfiguration.isSingletonNodeRequired();
    }

    @Override
    protected void processSingletonEvent(Event event, Object source, Object data)
    {
        // The remote events are also received when the singleton node is not required
        if (isLocalIndexJobFinished(event)) {
            this.schedulerProvider.get().start();
        }
    }

    @Override
    protected void onSingletonNodeLost()
    {
        // A running scan is not interrupted
        this.schedulerProvider.get().stop();
    }

    private boolean isLocalIndexJobFinished(Event event)
    {
        return event instanceof JobFinishedEvent jobFinishedEvent
            && Objects.equals(jobFinishedEvent.getJobType(), JOB_TYPE)
            && !this.remoteObservationManagerContext.isRemoteState();
    }
}
