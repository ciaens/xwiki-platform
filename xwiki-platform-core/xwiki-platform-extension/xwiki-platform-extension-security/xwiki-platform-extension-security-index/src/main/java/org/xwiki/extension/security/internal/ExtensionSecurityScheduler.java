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
package org.xwiki.extension.security.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Disposable;
import org.xwiki.extension.security.ExtensionSecurityConfiguration;
import org.xwiki.job.Job;
import org.xwiki.job.JobExecutor;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Periodically check for new security vulnerabilities associated with components that are part of the current wiki
 * instance.
 *
 * @version $Id$
 * @since 15.5RC1
 */
@Component(roles = ExtensionSecurityScheduler.class)
@Singleton
public class ExtensionSecurityScheduler implements Runnable, Disposable
{
    @Inject
    private JobExecutor jobExecutor;

    @Inject
    private ExtensionSecurityConfiguration extensionSecurityConfiguration;

    @Inject
    private Logger logger;

    private ScheduledExecutorService executor;

    private boolean started;

    private CompletableFuture<Boolean> completableFuture;

    /**
     * The executors stopped by {@link #stop()}, whose running scan is interrupted when the component is disposed.
     */
    private final List<ScheduledExecutorService> stoppedExecutors = new ArrayList<>();

    /**
     * Initialize and start the scheduler.
     *
     * @return a completable future set to {@code true} once a first job is done and the security scan is enabled, or
     *     {@code false} once a first job is done and the security scan is disabled
     */
    public synchronized CompletableFuture<Boolean> start()
    {
        if (this.executor == null) {
            this.started = true;
            // Start the scheduling.
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r);
                thread.setName("Extension Security scanner");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setDaemon(true);

                return thread;
            });

            // Create the future before scheduling so the scan can always complete it, and give it to the scan so that a
            // scan of a stopped executor does not complete the future of the next start.
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            this.completableFuture = future;
            this.executor.scheduleWithFixedDelay(() -> scan(future), 0,
                this.extensionSecurityConfiguration.getScanDelay(), HOURS);
        }
        return this.completableFuture;
    }

    /**
     * Restart the executor. This is needed when the delay is updated from the administration.
     *
     * @return a completable future set to {@code true} once a first job is done after the restart and the security scan
     *     is enabled, or {@code false} once a first job is done after the restart and the security scan is disabled.
     *     {@code null} is returned if restart is called before {@link #start()} is ever called
     */
    public synchronized CompletableFuture<Boolean> restart()
    {
        if (!this.started) {
            return null;
        }

        this.executor.shutdown();
        this.executor = null;
        this.completableFuture = null;
        return start();
    }

    /**
     * Stop the periodic scan, for example because another member of the cluster is now in charge of it. A running scan
     * is not interrupted. The scan can be started again with {@link #start()}.
     *
     * @since 18.10.0RC1
     */
    public synchronized void stop()
    {
        this.started = false;

        if (this.executor != null) {
            // Cancel the next scans without interrupting the running one
            this.executor.shutdown();
            this.stoppedExecutors.removeIf(ScheduledExecutorService::isTerminated);
            this.stoppedExecutors.add(this.executor);
            this.executor = null;
        }
    }

    @Override
    public void run()
    {
        scan(this.completableFuture);
    }

    private void scan(CompletableFuture<Boolean> future)
    {
        if (!this.extensionSecurityConfiguration.isSecurityScanEnabled()) {
            this.logger.info("Extension security scan disabled.");
            future.complete(false);
            return;
        }

        try {
            // Execute job
            Job job = this.jobExecutor.execute(ExtensionSecurityJob.JOBTYPE, new ExtensionSecurityRequest());

            // Wait for the job to finish.
            job.join();
        } catch (InterruptedException e) {
            // Restore interrupted state...
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            this.logger.error("Failed to execute job", e);
        }
        future.complete(true);
    }

    @Override
    public synchronized void dispose()
    {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        this.stoppedExecutors.forEach(ScheduledExecutorService::shutdownNow);
    }
}
