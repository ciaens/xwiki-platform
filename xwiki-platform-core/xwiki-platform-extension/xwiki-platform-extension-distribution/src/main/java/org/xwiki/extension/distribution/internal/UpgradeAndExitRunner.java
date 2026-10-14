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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextException;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.job.Job;
import org.xwiki.job.event.status.JobStatus;

import com.xpn.xwiki.internal.XWikiInitializerJob;

/**
 * Upgrade the databases and the distribution, then stop the JVM (when the {@code core.initialization.exitAfterUpgrade}
 * property of {@code xwiki.properties} is true, see {@link UpgradeAndExitListener}).
 * <p>
 * A dedicated (non daemon) thread waits for the XWiki initialization (started eagerly in this mode), checks the
 * database of every wiki and runs the Distribution Wizard on every wiki (see {@link UpgradeAndExitSteps}), logs a
 * summary line and stops the JVM with {@link System#exit(int)}: {@value #EXIT_SUCCESS} when everything is upgraded,
 * {@value #EXIT_DATABASE} when the initialization failed or a database is not usable (failed, outdated or newer than
 * this version of XWiki), {@value #EXIT_DISTRIBUTION} when the Distribution Wizard or one of the extension jobs it
 * started failed, or when a wiki does not have the expected user interface version afterwards.
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component(roles = UpgradeAndExitRunner.class)
@Singleton
public class UpgradeAndExitRunner
{
    /**
     * The exit code when the databases and the distribution are upgraded.
     */
    public static final int EXIT_SUCCESS = 0;

    /**
     * The exit code when XWiki failed to initialize or a database is not usable.
     */
    public static final int EXIT_DATABASE = 1;

    /**
     * The exit code when the Distribution Wizard did not complete.
     */
    public static final int EXIT_DISTRIBUTION = 2;

    @Inject
    @Named(XWikiInitializerJob.JOBTYPE)
    private Provider<Job> initializerJobProvider;

    @Inject
    private Provider<UpgradeAndExitSteps> stepsProvider;

    @Inject
    private Provider<ExecutionContextManager> executionContextManagerProvider;

    @Inject
    private Provider<Execution> executionProvider;

    @Inject
    private Logger logger;

    /**
     * The extension and distribution jobs which failed since the upgrade started.
     */
    private final List<String> failedJobs = new CopyOnWriteArrayList<>();

    /**
     * Stops the JVM (replaced in tests).
     */
    private IntConsumer exitHandler = System::exit;

    /**
     * How long to wait between two checks of the initialization progress.
     */
    private Duration pollInterval = Duration.ofSeconds(1);

    /**
     * How long to wait for the initialization to start. The thread is started by {@link UpgradeAndExitListener} after
     * the other listeners of {@link org.xwiki.observation.event.ApplicationStartedEvent} (including the one starting
     * the initialization and the one loading the installed extensions), so this only covers the start of the
     * initialization job.
     */
    private Duration startTimeout = Duration.ofSeconds(60);

    private volatile boolean upgrading;

    private volatile boolean stopping;

    private Thread thread;

    /**
     * Start the upgrade in a dedicated thread, which stops the JVM at its end (only once).
     */
    public synchronized void start()
    {
        if (this.thread == null) {
            this.logger.info("XWiki will stop once the databases and the distribution are upgraded"
                + " (core.initialization.exitAfterUpgrade)");

            this.upgrading = true;
            this.thread = new Thread(this::run);
            // Not a daemon thread: it calls System.exit() once the upgrade is done
            this.thread.setDaemon(false);
            this.thread.setName("XWiki upgrade and exit");
            this.thread.start();
        }
    }

    /**
     * Stop waiting when the application is stopped before the end of the upgrade (but not the thread which stops the
     * application itself).
     */
    public synchronized void stop()
    {
        if (this.thread != null && this.upgrading) {
            this.stopping = true;
            this.thread.interrupt();
        }
    }

    /**
     * Record the failure of a job started during the upgrade.
     *
     * @param jobId the identifier of the job
     * @param jobType the type of the job
     * @param error the error of the job
     */
    public void onJobFailed(List<String> jobId, String jobType, Throwable error)
    {
        if (this.upgrading) {
            this.failedJobs.add(String.format("job %s of type [%s] failed: %s", jobId, jobType,
                ExceptionUtils.getRootCauseMessage(error)));
        }
    }

    private void run()
    {
        int code;
        try {
            code = upgrade();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        } catch (Throwable e) {
            // Whatever happens, the process must end with an exit code: a thread ending without calling exit would
            // leave the JVM running (and the upgrade process waiting forever)
            code = EXIT_DISTRIBUTION;
            if (!this.stopping) {
                this.logger.error("Unexpected failure of the upgrade, stopping XWiki with exit code [{}]", code, e);
            }
        } finally {
            this.upgrading = false;
        }

        // Never stop the JVM while the application is being stopped (it could be undeployed only)
        if (code < 0 || this.stopping) {
            this.logger.warn("The upgrade was interrupted before its end (XWiki is stopping): not exiting");
        } else {
            this.exitHandler.accept(code);
        }
    }

    /**
     * Wait for the initialization, check the databases and run the Distribution Wizard.
     *
     * @return the exit code
     * @throws InterruptedException when the thread is interrupted while waiting
     */
    int upgrade() throws InterruptedException
    {
        List<String> failures = new ArrayList<>();

        int code = waitForInitialization(failures);

        if (code == EXIT_SUCCESS) {
            Execution execution = this.executionProvider.get();
            try {
                this.executionContextManagerProvider.get().initialize(new ExecutionContext());

                UpgradeAndExitSteps steps = this.stepsProvider.get();
                if (!steps.checkDatabases(failures)) {
                    code = EXIT_DATABASE;
                } else {
                    steps.upgradeDistribution(failures);
                    failures.addAll(this.failedJobs);
                    code = failures.isEmpty() ? EXIT_SUCCESS : EXIT_DISTRIBUTION;
                }
            } catch (ExecutionContextException e) {
                failures.add("failed to initialize the execution context: " + ExceptionUtils.getRootCauseMessage(e));
                code = EXIT_DATABASE;
            } finally {
                execution.removeContext();
            }
        }

        // A step interrupted because XWiki is stopping (for example the wait for the database migration lock) fails
        // without being the result of the upgrade
        if (this.stopping) {
            throw new InterruptedException("XWiki is stopping");
        }

        if (code == EXIT_SUCCESS) {
            this.logger.info("The databases and the distribution are upgraded, stopping XWiki with exit code [{}]",
                code);
        } else {
            this.logger.error("The upgrade failed ({}), stopping XWiki with exit code [{}]",
                String.join("; ", failures), code);
        }

        return code;
    }

    private int waitForInitialization(List<String> failures) throws InterruptedException
    {
        Job initializerJob = this.initializerJobProvider.get();

        long startDeadline = System.nanoTime() + this.startTimeout.toNanos();
        JobStatus status = initializerJob.getStatus();
        while (status == null || status.getState() != JobStatus.State.FINISHED) {
            if (status == null && System.nanoTime() - startDeadline > 0) {
                failures.add(String.format("the XWiki initialization did not start within [%d] seconds (check the"
                    + " warnings about core.initialization.eager)", this.startTimeout.toSeconds()));

                return EXIT_DATABASE;
            }

            Thread.sleep(this.pollInterval.toMillis());

            status = initializerJob.getStatus();
        }

        if (status.getError() != null) {
            failures.add("the XWiki initialization failed: " + ExceptionUtils.getRootCauseMessage(status.getError()));

            return EXIT_DATABASE;
        }

        return EXIT_SUCCESS;
    }
}
