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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.component.namespace.Namespace;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.context.Execution;
import org.xwiki.context.internal.DefaultExecution;
import org.xwiki.context.internal.DefaultExecutionContextManager;
import org.xwiki.extension.ExtensionManagerConfiguration;
import org.xwiki.extension.index.ExtensionIndexStatus;
import org.xwiki.job.Job;
import org.xwiki.job.JobExecutor;
import org.xwiki.model.namespace.WikiNamespace;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the scheduling part of {@link ExtensionIndexJobScheduler}.
 * <p>
 * Except in {@link #tasksRunWithAnExecutionContext()}, the tasks of the scheduler are captured and run from the test
 * thread.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({ DefaultExecution.class, DefaultExecutionContextManager.class })
class ExtensionIndexJobSchedulerTest
{
    private static final int INTERVAL = 3600;

    private static final long TIMEOUT = 10000;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @InjectMockComponents
    private ExtensionIndexJobScheduler scheduler;

    @MockComponent
    private JobExecutor jobs;

    @MockComponent
    private ExtensionManagerConfiguration configuration;

    @MockComponent
    private WikiDescriptorManager wikis;

    @Mock
    private ScheduledExecutorService executor;

    @Mock
    private ScheduledFuture<?> periodicTask;

    @Mock
    private Job job;

    @Mock
    private ExtensionIndexStatus status;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    private final List<List<Namespace>> executedNamespaces = new ArrayList<>();

    @BeforeEach
    void beforeEach() throws Exception
    {
        // Run the tasks from the test thread
        ReflectionUtils.setFieldValue(this.scheduler, "scheduler", this.executor);
        doReturn(this.periodicTask).when(this.executor).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());

        when(this.configuration.getIndexInterval()).thenReturn(INTERVAL);
        when(this.wikis.getMainWikiId()).thenReturn("xwiki");
        when(this.jobs.execute(eq(ExtensionIndexJob.JOB_TYPE), any())).then(invocation -> {
            // Remember the namespaces at the time of the execution, the scheduled request is modified afterwards
            this.executedNamespaces.add(((ExtensionIndexRequest) invocation.getArgument(1)).getNamespaces());

            return this.job;
        });
        when(this.job.getStatus()).thenReturn(this.status);
    }

    private Runnable captureExecutedTask(int count)
    {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(this.executor, times(count)).execute(captor.capture());

        return captor.getValue();
    }

    private Runnable capturePeriodicTask(int count)
    {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(this.executor, times(count)).scheduleWithFixedDelay(captor.capture(), eq((long) INTERVAL),
            eq((long) INTERVAL), eq(TimeUnit.SECONDS));

        return captor.getValue();
    }

    @Test
    void startAndStop() throws Exception
    {
        this.scheduler.start(false);

        // Starting it again while the first job is queued does nothing
        this.scheduler.start(false);
        captureExecutedTask(1).run();

        assertEquals(List.of(List.of(Namespace.ROOT, new WikiNamespace("xwiki"))), this.executedNamespaces);
        verify(this.job).join();

        // The following jobs are scheduled
        Runnable periodic = capturePeriodicTask(1);
        periodic.run();
        assertEquals(2, this.executedNamespaces.size());

        // Starting it again while the periodic indexing is scheduled does nothing
        this.scheduler.start(false);
        captureExecutedTask(1);
        capturePeriodicTask(1);

        // Stopping cancels the periodic indexing without interrupting the running job
        this.scheduler.stop();
        verify(this.periodicTask).cancel(false);

        // Starting again triggers an indexing right away (catch-up) and schedules the following ones again
        this.scheduler.start(false);
        captureExecutedTask(2).run();
        assertEquals(3, this.executedNamespaces.size());
        capturePeriodicTask(2);

        // The main wiki is added only once
        assertEquals(List.of(Namespace.ROOT, new WikiNamespace("xwiki")), this.executedNamespaces.get(2));
    }

    @Test
    void stopBeforeTheFirstJobRuns() throws Exception
    {
        this.scheduler.start(false);
        this.scheduler.stop();

        captureExecutedTask(1).run();

        // The queued job is skipped and nothing is scheduled
        assertEquals(List.of(), this.executedNamespaces);
        verify(this.executor, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());

        // Starting again queues a new first job
        this.scheduler.start(false);
        captureExecutedTask(2).run();
        assertEquals(1, this.executedNamespaces.size());
        capturePeriodicTask(1);
    }

    @Test
    void stopAndStartWhileTheFirstJobIsQueued() throws Exception
    {
        this.scheduler.start(false);
        this.scheduler.stop();
        this.scheduler.start(false);

        // A single first job is queued, and it runs since the indexing is started again
        captureExecutedTask(1).run();
        assertEquals(1, this.executedNamespaces.size());
        capturePeriodicTask(1);
    }

    @Test
    void stopWhileTheFirstJobIsRunning() throws Exception
    {
        this.scheduler.start(false);

        when(this.jobs.execute(eq(ExtensionIndexJob.JOB_TYPE), any())).then(invocation -> {
            // The current instance stops being the singleton node while the job is running
            this.scheduler.stop();

            return this.job;
        });

        captureExecutedTask(1).run();

        // The job is not interrupted but the following ones are not scheduled
        verify(this.job).join();
        verify(this.executor, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }

    @Test
    void startAllWikis() throws Exception
    {
        when(this.wikis.getAllIds()).thenReturn(List.of("xwiki", "subwiki1"));

        this.scheduler.start(true);
        captureExecutedTask(1).run();

        assertEquals(List.of(Namespace.ROOT, new WikiNamespace("xwiki"), new WikiNamespace("subwiki1")),
            this.executedNamespaces.get(0));

        // A wiki created afterwards is indexed by the next scheduled job
        when(this.wikis.getAllIds()).thenReturn(List.of("xwiki", "subwiki1", "subwiki2"));
        capturePeriodicTask(1).run();

        assertEquals(List.of(Namespace.ROOT, new WikiNamespace("xwiki"), new WikiNamespace("subwiki1"),
            new WikiNamespace("subwiki2")), this.executedNamespaces.get(1));
    }

    @Test
    void startAllWikisForgetsDeletedWikis() throws Exception
    {
        when(this.wikis.getAllIds()).thenReturn(List.of("xwiki", "subwiki1", "subwiki2"));

        this.scheduler.start(true);
        captureExecutedTask(1).run();

        when(this.wikis.getAllIds()).thenReturn(List.of("xwiki", "subwiki2"));
        capturePeriodicTask(1).run();

        assertEquals(List.of(Namespace.ROOT, new WikiNamespace("xwiki"), new WikiNamespace("subwiki2")),
            this.executedNamespaces.get(1));
    }

    @Test
    void tasksRunWithAnExecutionContext() throws Exception
    {
        // Use a real executor: the tasks run on its own thread
        ScheduledExecutorService realExecutor = Executors.newSingleThreadScheduledExecutor();
        ReflectionUtils.setFieldValue(this.scheduler, "scheduler", realExecutor);

        // Listing the wikis requires an execution context (the query needs the XWiki context)
        Execution execution = this.componentManager.getInstance(Execution.class);
        when(this.wikis.getAllIds()).then(invocation -> {
            if (execution.getContext() == null) {
                throw new WikiManagerException("No execution context");
            }

            return List.of("xwiki", "subwiki");
        });

        try {
            this.scheduler.start(true);

            verify(this.job, timeout(TIMEOUT)).join();
            assertEquals(List.of(List.of(Namespace.ROOT, new WikiNamespace("xwiki"), new WikiNamespace("subwiki"))),
                this.executedNamespaces);
            assertEquals(0, this.logCapture.size());
        } finally {
            this.scheduler.dispose();
        }
    }

    @Test
    void startAllWikisWhenTheWikisCannotBeListed() throws Exception
    {
        when(this.wikis.getAllIds()).thenThrow(new WikiManagerException("error"));

        this.scheduler.start(true);
        captureExecutedTask(1).run();

        // The main wiki is still indexed
        assertEquals(List.of(List.of(Namespace.ROOT, new WikiNamespace("xwiki"))), this.executedNamespaces);
        assertEquals("Failed to get the list of wikis, the extensions are indexed for the previous list",
            this.logCapture.getMessage(0));
    }

    @Test
    void initialize() throws Exception
    {
        when(this.status.isExtensionAdded()).thenReturn(true);

        this.scheduler.initialize(new WikiNamespace("subwiki"));
        // No extension was added yet: nothing to analyze
        verify(this.executor, never()).execute(any());

        this.scheduler.start(false);
        captureExecutedTask(1).run();

        // The namespace is added only once
        this.scheduler.initialize(new WikiNamespace("subwiki"));
        assertEquals(List.of(Namespace.ROOT, new WikiNamespace("subwiki"), new WikiNamespace("xwiki")),
            this.executedNamespaces.get(0));

        // An extension was added by the first job: the namespace is analyzed
        captureExecutedTask(2).run();
        assertEquals(List.of(new WikiNamespace("subwiki")), this.executedNamespaces.get(1));

        capturePeriodicTask(1).run();
        assertEquals(List.of(Namespace.ROOT, new WikiNamespace("subwiki"), new WikiNamespace("xwiki")),
            this.executedNamespaces.get(2));
        verify(this.wikis, never()).getAllIds();
    }

    @Test
    void disposeStopsTheScheduling() throws Exception
    {
        this.scheduler.start(false);
        this.scheduler.dispose();

        captureExecutedTask(1).run();

        assertEquals(List.of(), this.executedNamespaces);
        verify(this.executor).shutdownNow();
        verify(this.executor, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }
}
