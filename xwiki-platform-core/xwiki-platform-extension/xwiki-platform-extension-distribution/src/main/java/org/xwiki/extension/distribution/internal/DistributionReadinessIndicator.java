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

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;
import org.xwiki.container.health.ReadinessIndicator;
import org.xwiki.extension.distribution.internal.DistributionManager.DistributionState;
import org.xwiki.extension.distribution.internal.job.DistributionJob;
import org.xwiki.job.event.status.JobStatus;

/**
 * Reports the state of the farm Distribution Job (the Distribution Wizard, in charge of installing or upgrading the
 * extensions after a platform upgrade):
 * <ul>
 * <li>no farm job and nothing to do ({@link DistributionState#SAME} or {@link DistributionState#NONE}):
 * {@link State#READY}, with the distribution state in the {@code farmState} detail,</li>
 * <li>no farm job but a pending distribution (fresh instance or upgrade, the wizard was never started):
 * {@link State#DISTRIBUTION} but ready, so that operators see the pending wizard,</li>
 * <li>job running: {@link State#DISTRIBUTION} and not ready,</li>
 * <li>job waiting for an administrator (interactive wizard): {@link State#DISTRIBUTION} but ready,</li>
 * <li>job finished with an error: {@link State#FAILED} but ready, with the error in the {@code error} detail,</li>
 * <li>job finished: {@link State#READY}.</li>
 * </ul>
 * The node stays ready whenever the wizard needs an administrator (waiting, failed or never started): otherwise a load
 * balancer relying on the readiness would never route an administrator to the node and the wizard could never be
 * completed or retried. Sub-wiki distribution jobs are ignored.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
@Component
@Named(DistributionReadinessIndicator.HINT)
@Singleton
public class DistributionReadinessIndicator implements ReadinessIndicator
{
    /**
     * The hint of the indicator, and its key in the health endpoint output.
     */
    public static final String HINT = "distribution";

    private static final String DETAIL_FARM_STATE = "farmState";

    private static final String DETAIL_JOB_STATE = "jobState";

    @Inject
    private DistributionManager distributionManager;

    @Inject
    private Logger logger;

    @Override
    public Readiness check()
    {
        DistributionJob job = this.distributionManager.getFarmJob();

        Map<String, Object> details = new LinkedHashMap<>();

        if (job == null) {
            return checkWithoutJob(details);
        }

        JobStatus status = job.getStatus();
        JobStatus.State jobState = status != null ? status.getState() : JobStatus.State.NONE;
        details.put(DETAIL_JOB_STATE, jobState);

        switch (jobState) {
            case WAITING:
                // The interactive Distribution Wizard is waiting for an administrator to answer a question: the node
                // must stay ready, see the class documentation.
                return Readiness.of(State.DISTRIBUTION, true, details);
            case FINISHED:
                Throwable error = status.getError();
                if (error != null) {
                    // A failed Distribution Wizard must be retried by an administrator: the node must stay ready, see
                    // the class documentation.
                    details.put(Readiness.DETAIL_ERROR, error);

                    return Readiness.of(State.FAILED, true, details);
                }

                return Readiness.ready(details);
            default:
                // NONE (created but not started yet) or RUNNING
                return Readiness.notReady(State.DISTRIBUTION, details);
        }
    }

    private Readiness checkWithoutJob(Map<String, Object> details)
    {
        DistributionState farmState;
        try {
            farmState = this.distributionManager.getFarmDistributionState();
        } catch (Exception e) {
            // Don't fail the check (and don't log an error at each probe) when the previous job status cannot be
            // loaded, just report that the state is unknown
            this.logger.debug("Failed to get the farm distribution state", e);

            details.put(DETAIL_FARM_STATE, "UNKNOWN");

            return Readiness.ready(details);
        }

        details.put(DETAIL_FARM_STATE, farmState);

        if (farmState != null && farmState != DistributionState.SAME && farmState != DistributionState.NONE) {
            // The Distribution Wizard is pending but was never started (for example an interactive wizard on a fresh
            // instance or after an upgrade, waiting for an administrator to visit the wiki): the node must stay ready,
            // see the class documentation.
            return Readiness.of(State.DISTRIBUTION, true, details);
        }

        return Readiness.ready(details);
    }
}
