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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.descriptor.ComponentDescriptor;
import org.xwiki.extension.distribution.internal.job.DefaultDistributionJob;
import org.xwiki.extension.job.internal.InstallJob;
import org.xwiki.extension.job.internal.UninstallJob;
import org.xwiki.job.event.JobFinishingEvent;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.ApplicationStartedEvent;
import org.xwiki.observation.event.ApplicationStoppedEvent;
import org.xwiki.observation.event.Event;

/**
 * Upgrade the databases and the distribution, then stop the JVM, when the {@code core.initialization.exitAfterUpgrade}
 * property of {@code xwiki.properties} is true (see {@link UpgradeAndExitRunner}).
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component
@Named(UpgradeAndExitListener.NAME)
@Singleton
// Start the upgrade thread (and its delay for the initialization to start) after the other listeners of
// ApplicationStartedEvent, which are called synchronously: the ones loading the installed extensions and starting the
// initialization eagerly (with a priority of 2000) in particular
@Priority(UpgradeAndExitListener.PRIORITY)
public class UpgradeAndExitListener extends AbstractEventListener
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "org.xwiki.extension.distribution.internal.UpgradeAndExitListener";

    /**
     * The priority of the listener: after the other listeners of {@link ApplicationStartedEvent}.
     */
    public static final int PRIORITY = ComponentDescriptor.DEFAULT_PRIORITY + 2000;

    /**
     * The types of the jobs whose failure fails the upgrade: the Distribution Wizard ignores the result of the
     * extension jobs it starts.
     */
    private static final List<String> CHECKED_JOB_TYPES =
        List.of(InstallJob.JOBTYPE, UninstallJob.JOBTYPE, DefaultDistributionJob.HINT);

    @Inject
    private DistributionConfiguration distributionConfiguration;

    @Inject
    private Provider<UpgradeAndExitRunner> runnerProvider;

    /**
     * Default constructor.
     */
    public UpgradeAndExitListener()
    {
        super(NAME, new ApplicationStartedEvent(), new ApplicationStoppedEvent(), new JobFinishingEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (event instanceof ApplicationStartedEvent) {
            if (this.distributionConfiguration.isExitAfterUpgrade()) {
                this.runnerProvider.get().start();
            }
        } else if (event instanceof ApplicationStoppedEvent) {
            if (this.distributionConfiguration.isExitAfterUpgrade()) {
                this.runnerProvider.get().stop();
            }
        } else if (event instanceof JobFinishingEvent jobEvent && data instanceof Throwable error
            && CHECKED_JOB_TYPES.contains(jobEvent.getJobType())
            && this.distributionConfiguration.isExitAfterUpgrade()) {
            this.runnerProvider.get().onJobFailed(jobEvent.getJobId(), jobEvent.getJobType(), error);
        }
    }
}
