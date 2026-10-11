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
package org.xwiki.extension.index.internal.job;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.namespace.Namespace;
import org.xwiki.component.phase.Disposable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.extension.ExtensionManagerConfiguration;
import org.xwiki.extension.index.ExtensionIndexStatus;
import org.xwiki.job.Job;
import org.xwiki.job.JobException;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.JobStatusStore;
import org.xwiki.job.event.status.JobStatus.State;
import org.xwiki.model.namespace.WikiNamespace;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

/**
 * Component in charge of scheduling and stopping indexing jobs.
 * 
 * @version $Id$
 * @since 12.10
 */
@Component(roles = ExtensionIndexJobScheduler.class)
@Singleton
public class ExtensionIndexJobScheduler implements Disposable
{
    @Inject
    private JobExecutor jobs;

    @Inject
    private JobStatusStore jobStore;

    @Inject
    private ExtensionManagerConfiguration configuration;

    @Inject
    private WikiDescriptorManager wikis;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private Logger logger;

    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ExtensionIndexRequest scheduledRequest =
        new ExtensionIndexRequest(true, true, Arrays.asList(Namespace.ROOT));

    private ExtensionIndexRequest currentRequest;

    private boolean extensionAdded;

    private volatile boolean disposed;

    /**
     * Guards {@link #started}, {@link #firstJobPending}, {@link #periodicTask} and the changes of the namespaces of
     * {@link #scheduledRequest}.
     */
    private final Object schedulingLock = new Object();

    /**
     * {@code true} between {@link #start(boolean)} and {@link #stop()}.
     */
    private boolean started;

    /**
     * {@code true} while the job which starts the periodic indexing is queued or running.
     */
    private boolean firstJobPending;

    private ScheduledFuture<?> periodicTask;

    private volatile boolean allWikis;

    /**
     * Start indexing the extensions right away and then periodically. Calling this method while the indexing is
     * already started has no effect (a running job keeps running and the periodic indexing is not scheduled twice).
     *
     * @param allWikis {@code true} to index the extensions of all the wikis, including the wikis which are not ready
     *            on this instance (the wikis of the scheduled indexing are replaced by the current list of wikis before
     *            each run, so created wikis are added and deleted wikis are forgotten), {@code false} to index the
     *            extensions of the main wiki and of the namespaces passed to {@link #initialize(Namespace)}
     * @since 18.10.0RC1
     */
    public void start(boolean allWikis)
    {
        if (allWikis) {
            this.allWikis = true;
        }

        // Add main wiki
        addScheduledNamespace(new WikiNamespace(this.wikis.getMainWikiId()));

        synchronized (this.schedulingLock) {
            if (!this.started) {
                this.started = true;

                if (this.periodicTask == null && !this.firstJobPending) {
                    this.firstJobPending = true;

                    // Trigger a first indexing
                    executeFirstJob();
                }
            }
        }
    }

    /**
     * Stop the periodic indexing, for example because another member of the cluster is now in charge of it. A running
     * indexing job is not interrupted. The indexing can be started again with {@link #start(boolean)}.
     *
     * @since 18.10.0RC1
     */
    public void stop()
    {
        synchronized (this.schedulingLock) {
            this.started = false;

            if (this.periodicTask != null) {
                // Don't interrupt the running job
                this.periodicTask.cancel(false);
                this.periodicTask = null;
            }
        }
    }

    /**
     * Indicate that a namespace start to be ready for manipulation.
     * 
     * @param namespace the namespace to initialize
     */
    public void initialize(Namespace namespace)
    {
        addScheduledNamespace(namespace);

        // Start an analysis of the namespace only if something changed
        if (this.extensionAdded) {
            executeJob(new ExtensionIndexRequest(false, false, Arrays.asList(namespace)));
        }
    }

    /**
     * Start a new indexing process or return the status of the currently running one.
     * 
     * @param namespace the namespace for which to validate the extensions
     * @return the status of the running indexing process
     * @throws JobException when failing to start indexing
     */
    public ExtensionIndexStatus index(Namespace namespace) throws JobException
    {
        // Check if the namespace's job is already running
        Job job;
        if (this.currentRequest != null && this.currentRequest.getNamespaces().contains(namespace)) {
            job = this.jobs.getJob(ExtensionIndexRequest.getId(namespace));
        } else {
            job = null;
        }

        // If not start a new one
        if (job == null || job.getStatus().getState() == State.FINISHED) {
            ExtensionIndexRequest request = new ExtensionIndexRequest(false, true, Arrays.asList(namespace));

            // Also analyze root namespace for main wiki
            if (namespace.getType().equals(WikiNamespace.TYPE) && this.wikis.isMainWiki(namespace.getValue())) {
                request.addNamespace(Namespace.ROOT);
            }

            job = this.jobs.execute(ExtensionIndexJob.JOB_TYPE, request);
        }

        return (ExtensionIndexStatus) job.getStatus();
    }

    /**
     * @param namespace the namespace for which the validation was executed
     * @return the status of the currently running or last indexing process
     */
    public ExtensionIndexStatus getStatus(Namespace namespace)
    {
        List<String> id = ExtensionIndexRequest.getId(namespace);

        // Try running jobs

        Job job = this.jobs.getJob(id);

        // If no specific job try scheduled job
        if (job == null && this.scheduledRequest.getNamespaces().contains(namespace)) {
            job = this.jobs.getJob(this.scheduledRequest.getId());
        }

        if (job != null) {
            return (ExtensionIndexStatus) job.getStatus();
        }

        // Try serialized jobs

        ExtensionIndexStatus namespaceStatus = (ExtensionIndexStatus) this.jobStore.getJobStatus(id);
        ExtensionIndexStatus scheduledStatus = !id.equals(this.scheduledRequest.getId())
            ? (ExtensionIndexStatus) this.jobStore.getJobStatus(this.scheduledRequest.getId()) : null;

        if (hasAStartDate(namespaceStatus)
            && (scheduledStatus == null || namespaceStatus.getStartDate().after(scheduledStatus.getStartDate())
                || !((ExtensionIndexRequest) scheduledStatus.getRequest()).getNamespaces().contains(namespace))) {
            return namespaceStatus;
        }

        return scheduledStatus;
    }

    private boolean hasAStartDate(ExtensionIndexStatus status)
    {
        return status != null && status.getStartDate() != null;
    }

    /**
     * @param namespace the new namespace to analyze
     */
    public void add(Namespace namespace)
    {
        // Update the scheduled request
        addScheduledNamespace(namespace);

        // Queue an analysis of the namespace
        executeJob(new ExtensionIndexRequest(false, false, Arrays.asList(namespace)));
    }

    /**
     * @param namespace the namespace to stop analysing
     */
    public void remove(Namespace namespace)
    {
        // Update the scheduled request
        synchronized (this.schedulingLock) {
            this.scheduledRequest.removeNamespace(namespace);
        }

        // TODO: remove the namespace from the index
    }

    private void executeFirstJob()
    {
        this.scheduler.execute(withContext(this::runFirstJob));
    }

    private void executeJob(ExtensionIndexRequest request)
    {
        this.scheduler.execute(withContext(() -> this.runJob(request)));
    }

    /**
     * The tasks need an execution context, for example to list the wikis.
     */
    private Runnable withContext(Runnable runnable)
    {
        return new ExecutionContextRunnable(runnable, this.componentManager);
    }

    private void runFirstJob()
    {
        try {
            // The indexing might have been stopped while this job was queued
            if (isStarted()) {
                // Run the first job
                runScheduledJob();

                if (!this.disposed) {
                    // Disable local extension loading (it will be done dynamically)
                    this.scheduledRequest.setLocalExtensionsEnabled(false);
                }
            }
        } finally {
            synchronized (this.schedulingLock) {
                this.firstJobPending = false;

                if (this.started && !this.disposed && this.periodicTask == null) {
                    // Start scheduling following jobs
                    this.periodicTask = this.scheduler.scheduleWithFixedDelay(withContext(this::runScheduledJob),
                        this.configuration.getIndexInterval(), this.configuration.getIndexInterval(), TimeUnit.SECONDS);
                }
            }
        }
    }

    private boolean isStarted()
    {
        synchronized (this.schedulingLock) {
            return this.started;
        }
    }

    private void addScheduledNamespace(Namespace namespace)
    {
        synchronized (this.schedulingLock) {
            if (!this.scheduledRequest.getNamespaces().contains(namespace)) {
                this.scheduledRequest.addNamespace(namespace);
            }
        }
    }

    private void updateWikis()
    {
        Collection<String> wikiIds;
        try {
            wikiIds = this.wikis.getAllIds();
        } catch (WikiManagerException e) {
            this.logger.error("Failed to get the list of wikis, the extensions are indexed for the previous list", e);

            return;
        }

        synchronized (this.schedulingLock) {
            Set<Namespace> currentWikis = new HashSet<>();
            for (String wikiId : wikiIds) {
                currentWikis.add(new WikiNamespace(wikiId));
            }

            // Forget the deleted wikis
            for (Namespace namespace : this.scheduledRequest.getNamespaces()) {
                if (WikiNamespace.TYPE.equals(namespace.getType()) && !currentWikis.contains(namespace)) {
                    this.scheduledRequest.removeNamespace(namespace);
                }
            }

            // Add the new wikis
            for (String wikiId : wikiIds) {
                addScheduledNamespace(new WikiNamespace(wikiId));
            }
        }
    }

    private void runScheduledJob()
    {
        if (!this.disposed) {
            if (this.allWikis) {
                updateWikis();
            }

            Job job = runJob(this.scheduledRequest);

            if (job != null) {
                ExtensionIndexStatus status = (ExtensionIndexStatus) job.getStatus();

                // Remember updates
                this.extensionAdded |= status.isExtensionAdded();
            }
        }
    }

    private Job runJob(ExtensionIndexRequest request)
    {
        this.currentRequest = request;

        try {
            Job job = this.jobs.execute(ExtensionIndexJob.JOB_TYPE, request);
            job.join();

            return job;
        } catch (Exception e) {
            // Ignore the error if the scheduler was disposed (we assume it's a consequence of the shutdown)
            if (!this.disposed) {
                this.logger.error("Failed to execute job", e);
            }
        } finally {
            this.currentRequest = null;
        }

        return null;
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        this.disposed = true;

        // Stop the scheduling
        this.scheduler.shutdownNow();
    }
}
