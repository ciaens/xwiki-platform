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
package org.xwiki.health.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;
import org.xwiki.container.health.ReadinessIndicator;
import org.xwiki.environment.Environment;
import org.xwiki.environment.internal.ServletEnvironment;
import org.xwiki.job.Job;
import org.xwiki.job.event.status.JobProgress;
import org.xwiki.job.event.status.JobStatus;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.internal.XWikiInitializerJob;
import com.xpn.xwiki.store.XWikiHibernateBaseStore;
import com.xpn.xwiki.store.migration.DataMigrationManager;

/**
 * Reports the state of the initialization of the main {@link XWiki} instance, performed by {@link XWikiInitializerJob}:
 * <ul>
 * <li>job not started: {@link State#STARTING},</li>
 * <li>job running: {@link State#MIGRATING} while the database migrations are running, else {@link State#STARTING},
 * with the job progress in the {@code progress} detail,</li>
 * <li>job finished: {@link State#READY} when the {@link XWiki} instance has been registered in the servlet context,
 * else {@link State#FAILED} with the error in the {@code error} detail.</li>
 * </ul>
 * The check never waits for the initialization and never triggers it.
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component
@Named(XWikiInitializationReadinessIndicator.HINT)
@Singleton
public class XWikiInitializationReadinessIndicator implements ReadinessIndicator
{
    /**
     * The hint of the indicator, and its key in the health endpoint output.
     */
    public static final String HINT = "xwiki";

    private static final String DETAIL_STARTED = "started";

    private static final String DETAIL_JOB_STATE = "jobState";

    @Inject
    @Named(XWikiInitializerJob.JOBTYPE)
    private Job initializerJob;

    @Inject
    @Named(XWikiHibernateBaseStore.HINT)
    private Provider<DataMigrationManager> dataMigrationManagerProvider;

    @Inject
    private Environment environment;

    @Override
    public Readiness check()
    {
        JobStatus status = this.initializerJob.getStatus();

        Map<String, Object> details = new LinkedHashMap<>();

        if (status == null) {
            details.put(DETAIL_STARTED, false);

            return Readiness.notReady(State.STARTING, details);
        }

        details.put(DETAIL_STARTED, true);
        details.put(DETAIL_JOB_STATE, status.getState());

        if (status.getState() == JobStatus.State.FINISHED) {
            return checkFinished(status, details);
        }

        JobProgress progress = status.getProgress();
        if (progress != null) {
            details.put("progress", progress.getOffset());
        }

        return Readiness.notReady(isMigrating() ? State.MIGRATING : State.STARTING, details);
    }

    private Readiness checkFinished(JobStatus status, Map<String, Object> details)
    {
        if (getMainXWiki() != null) {
            return Readiness.ready(details);
        }

        Throwable error = status.getError();
        details.put(Readiness.DETAIL_ERROR,
            error != null ? error : "The initialization ended without registering the main wiki");

        return Readiness.of(State.FAILED, false, details);
    }

    private boolean isMigrating()
    {
        try {
            return this.dataMigrationManagerProvider.get().isMigrating();
        } catch (Exception e) {
            // The migration manager cannot be instantiated yet (for example because the store is not configured)
            return false;
        }
    }

    /**
     * @return the main {@link XWiki} instance registered by the initialization job in the servlet context (the same
     *         attribute {@link XWiki#getMainXWiki(com.xpn.xwiki.XWikiContext)} uses), or {@code null} if it's not
     *         registered or if the environment is not a servlet one
     */
    private Object getMainXWiki()
    {
        if (this.environment instanceof ServletEnvironment servletEnvironment) {
            return servletEnvironment.getJakartaServletContext().getAttribute(XWiki.DEFAULT_MAIN_WIKI);
        }

        return null;
    }
}
