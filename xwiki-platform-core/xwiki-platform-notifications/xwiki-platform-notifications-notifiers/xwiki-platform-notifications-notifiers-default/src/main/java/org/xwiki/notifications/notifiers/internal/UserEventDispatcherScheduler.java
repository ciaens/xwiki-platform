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
package org.xwiki.notifications.notifiers.internal;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.phase.Disposable;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.RecordableEventDescriptor;
import org.xwiki.eventstream.RecordableEventDescriptorManager;

/**
 * Schedule dispatching of generated event to each user.
 * 
 * @version $Id$
 * @since 15.8RC1
 * @since 15.5.3
 * @since 14.10.17
 */
@Component(roles = UserEventDispatcherScheduler.class)
@Singleton
public class UserEventDispatcherScheduler implements Disposable
{
    @Inject
    private RecordableEventDescriptorManager recordableEventDescriptorManager;

    @Inject
    private ExecutionContextManager contextManager;

    @Inject
    private Execution execution;

    @Inject
    private Logger logger;

    @Inject
    private UserEventDispatcher dispatcher;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * {@code true} when a run was requested and did not start yet: the following requests are merged into it.
     */
    private final AtomicBoolean pending = new AtomicBoolean();

    private volatile boolean initialized;

    /**
     * Start the scheduler.
     */
    public void initialize()
    {
        this.initialized = true;

        // Schedule a job to regularly check if any event prefiltering was missed or a previous run failed
        this.scheduler.scheduleWithFixedDelay(this::run, 0, 1, TimeUnit.HOURS);
    }

    /**
     * Indicate an event just been created.
     *
     * @param event the event that has been created
     */
    public void onEvent(Event event)
    {
        try {
            if (getSupportedEventTypes().contains(event.getType())) {
                // Make sure to wakeup the dispatcher
                requestRun();
            }
        } catch (EventStreamException e) {
            this.logger.error("Failed to get supported event types", e);
        }
    }

    /**
     * Pre-filter the waiting events as soon as possible, unless the scheduler is not started yet (the first run happens
     * when it's started). The request is never dropped: when a run is already in progress, a new run follows it, so
     * that the events which became eligible during the current run (for example when the current instance just became
     * the cluster singleton node) are not left waiting for the next scheduled run. The requests received before that
     * run starts are merged into it.
     *
     * @since 18.10.0RC1
     */
    public void flush()
    {
        if (this.initialized) {
            requestRun();
        }
    }

    /**
     * Submits a run unless one is already waiting to start: the events which became eligible since the start of the
     * current run (if any) are pre-filtered by the next run, and the requests received meanwhile are merged into it.
     */
    private void requestRun()
    {
        if (this.pending.compareAndSet(false, true)) {
            try {
                this.scheduler.execute(this::run);
            } catch (RejectedExecutionException e) {
                // The component is disposed: the instance is stopping
                this.logger.debug("Ignoring the pre-filtering request received after the scheduler was stopped", e);
            }
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        // Stop the scheduling
        this.scheduler.shutdownNow();
    }

    private void run()
    {
        // The requests received from now on need a new run
        this.pending.set(false);

        Thread currenthread = Thread.currentThread();

        // Reduce the priority of this thread since it's not a critical task and it might be expensive
        currenthread.setPriority(Thread.NORM_PRIORITY - 1);
        // Set a more explicit thread name
        currenthread.setName("User event dispatcher thread");

        try {
            // Initialize a new context for the run
            this.contextManager.initialize(new ExecutionContext());

            this.dispatcher.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            // Catching Throwable to make sure we don't kill the scheduler which triggered this run
            this.logger.error("Failed to pre-filter events", e);
        } finally {
            // Remove any remaining context
            this.execution.removeContext();
        }
    }

    private Set<String> getSupportedEventTypes() throws EventStreamException
    {
        List<RecordableEventDescriptor> descriptorList =
            this.recordableEventDescriptorManager.getRecordableEventDescriptors(true);

        return descriptorList.stream().map(RecordableEventDescriptor::getEventType).collect(Collectors.toSet());
    }
}
