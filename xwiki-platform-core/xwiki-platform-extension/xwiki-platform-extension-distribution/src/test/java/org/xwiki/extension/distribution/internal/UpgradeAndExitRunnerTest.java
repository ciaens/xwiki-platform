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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import jakarta.inject.Named;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextException;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.job.Job;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.internal.XWikiInitializerJob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link UpgradeAndExitRunner}.
 *
 * @version $Id$
 */
@ComponentTest
class UpgradeAndExitRunnerTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private UpgradeAndExitRunner runner;

    @MockComponent
    @Named(XWikiInitializerJob.JOBTYPE)
    private Job initializerJob;

    @MockComponent
    private UpgradeAndExitSteps steps;

    @MockComponent
    private ExecutionContextManager executionContextManager;

    @MockComponent
    private Execution execution;

    @Mock
    private JobStatus initializerStatus;

    @BeforeEach
    void beforeEach() throws Exception
    {
        ReflectionUtils.setFieldValue(this.runner, "pollInterval", Duration.ofMillis(1));

        when(this.initializerJob.getStatus()).thenReturn(this.initializerStatus);
        when(this.initializerStatus.getState()).thenReturn(JobStatus.State.FINISHED);

        when(this.steps.checkDatabases(anyList())).thenReturn(true);
        when(this.steps.upgradeDistribution(anyList())).thenReturn(true);
    }

    private String failureMessage(String failures, int code)
    {
        return String.format("The upgrade failed (%s), stopping XWiki with exit code [%d]", failures, code);
    }

    @Test
    void upgradeSucceeds() throws Exception
    {
        assertEquals(UpgradeAndExitRunner.EXIT_SUCCESS, this.runner.upgrade());

        verify(this.executionContextManager).initialize(any());
        verify(this.steps).checkDatabases(anyList());
        verify(this.steps).upgradeDistribution(anyList());
        verify(this.execution).removeContext();
    }

    @Test
    void waitForTheInitialization() throws Exception
    {
        when(this.initializerJob.getStatus()).thenReturn(null, this.initializerStatus);
        when(this.initializerStatus.getState()).thenReturn(JobStatus.State.RUNNING, JobStatus.State.FINISHED);

        assertEquals(UpgradeAndExitRunner.EXIT_SUCCESS, this.runner.upgrade());
    }

    @Test
    void initializationNotStarted() throws Exception
    {
        when(this.initializerJob.getStatus()).thenReturn(null);
        ReflectionUtils.setFieldValue(this.runner, "startTimeout", Duration.ZERO);

        assertEquals(UpgradeAndExitRunner.EXIT_DATABASE, this.runner.upgrade());

        assertEquals(failureMessage("the XWiki initialization did not start within [0] seconds (check the warnings"
            + " about core.initialization.eager)", 1), this.logCapture.getMessage(0));
        verify(this.steps, never()).checkDatabases(anyList());
    }

    @Test
    void initializationFailed() throws Exception
    {
        when(this.initializerStatus.getError()).thenReturn(new IllegalStateException("no database"));

        assertEquals(UpgradeAndExitRunner.EXIT_DATABASE, this.runner.upgrade());

        assertEquals(failureMessage("the XWiki initialization failed: IllegalStateException: no database", 1),
            this.logCapture.getMessage(0));
        verify(this.steps, never()).checkDatabases(anyList());
    }

    @Test
    void databaseNotUsable() throws Exception
    {
        doAnswer(invocation -> {
            invocation.<List<String>>getArgument(0).add("database of wiki [subwiki]: outdated");
            return false;
        }).when(this.steps).checkDatabases(anyList());

        assertEquals(UpgradeAndExitRunner.EXIT_DATABASE, this.runner.upgrade());

        assertEquals(failureMessage("database of wiki [subwiki]: outdated", 1), this.logCapture.getMessage(0));
        verify(this.steps, never()).upgradeDistribution(anyList());
        verify(this.execution).removeContext();
    }

    @Test
    void distributionNotUpgraded() throws Exception
    {
        doAnswer(invocation -> {
            invocation.<List<String>>getArgument(0).add("the Distribution Wizard of wiki [xwiki] failed: oops");
            return false;
        }).when(this.steps).upgradeDistribution(anyList());

        assertEquals(UpgradeAndExitRunner.EXIT_DISTRIBUTION, this.runner.upgrade());

        assertEquals(failureMessage("the Distribution Wizard of wiki [xwiki] failed: oops", 2),
            this.logCapture.getMessage(0));
    }

    @Test
    void executionContextFailure() throws Exception
    {
        doThrow(new ExecutionContextException("broken")).when(this.executionContextManager).initialize(any());

        assertEquals(UpgradeAndExitRunner.EXIT_DATABASE, this.runner.upgrade());

        assertEquals(failureMessage("failed to initialize the execution context: ExecutionContextException: broken",
            1), this.logCapture.getMessage(0));
        verify(this.execution).removeContext();
    }

    @Test
    void failedJobsDuringTheUpgrade() throws Exception
    {
        // Ignored when no upgrade is running
        this.runner.onJobFailed(List.of("before"), "install", new RuntimeException("ignored"));

        ReflectionUtils.setFieldValue(this.runner, "upgrading", true);
        this.runner.onJobFailed(List.of("extension", "action", "ui"), "install", new RuntimeException("conflict"));

        assertEquals(UpgradeAndExitRunner.EXIT_DISTRIBUTION, this.runner.upgrade());

        assertEquals(failureMessage("job [extension, action, ui] of type [install] failed: RuntimeException:"
            + " conflict", 2), this.logCapture.getMessage(0));
    }

    private CompletableFuture<Integer> setExitHandler()
    {
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        ReflectionUtils.setFieldValue(this.runner, "exitHandler", (IntConsumer) exitCode::complete);

        return exitCode;
    }

    private Thread getThread() throws Exception
    {
        return (Thread) FieldUtils.readField(this.runner, "thread", true);
    }

    @Test
    void exitsAfterTheUpgrade() throws Exception
    {
        CompletableFuture<Integer> exitCode = setExitHandler();

        this.runner.start();
        // Started only once
        this.runner.start();

        assertEquals(UpgradeAndExitRunner.EXIT_SUCCESS, exitCode.get(10, TimeUnit.SECONDS));
        Thread thread = getThread();
        assertFalse(thread.isDaemon());
        thread.join();
        verify(this.steps).checkDatabases(anyList());

        // Stopping the application while exiting does not interrupt the thread
        this.runner.stop();
    }

    @Test
    void exitsWithTwoOnUnexpectedFailure() throws Exception
    {
        CompletableFuture<Integer> exitCode = setExitHandler();
        when(this.steps.upgradeDistribution(anyList())).thenThrow(new IllegalStateException("bug"));

        this.runner.start();

        assertEquals(UpgradeAndExitRunner.EXIT_DISTRIBUTION, exitCode.get(10, TimeUnit.SECONDS));
        getThread().join();
        assertEquals("Unexpected failure of the upgrade, stopping XWiki with exit code [2]",
            this.logCapture.getMessage(0));
    }

    @Test
    void exitsWithTwoOnError() throws Exception
    {
        CompletableFuture<Integer> exitCode = setExitHandler();
        when(this.steps.upgradeDistribution(anyList())).thenThrow(new NoClassDefFoundError("Missing"));

        this.runner.start();

        assertEquals(UpgradeAndExitRunner.EXIT_DISTRIBUTION, exitCode.get(10, TimeUnit.SECONDS));
        getThread().join();
        assertEquals("Unexpected failure of the upgrade, stopping XWiki with exit code [2]",
            this.logCapture.getMessage(0));
    }

    @Test
    void stopInterruptsTheWait() throws Exception
    {
        CompletableFuture<Integer> exitCode = setExitHandler();
        // The initialization never ends
        when(this.initializerStatus.getState()).thenReturn(JobStatus.State.RUNNING);

        this.runner.start();
        this.runner.stop();

        getThread().join(10000);
        assertFalse(exitCode.isDone());
        assertEquals("The upgrade was interrupted before its end (XWiki is stopping): not exiting",
            this.logCapture.getMessage(0));
    }

    @Test
    void stopDuringADatabaseCheck() throws Exception
    {
        CompletableFuture<Integer> exitCode = setExitHandler();
        // XWiki is stopped while waiting for the database migration lock, which fails the check
        doAnswer(invocation -> {
            this.runner.stop();
            invocation.<List<String>>getArgument(0).add("database of wiki [xwiki]: interrupted");
            return false;
        }).when(this.steps).checkDatabases(anyList());

        this.runner.start();

        getThread().join(10000);
        assertFalse(exitCode.isDone());
        assertEquals("The upgrade was interrupted before its end (XWiki is stopping): not exiting",
            this.logCapture.getMessage(0));
    }

    @Test
    void stopBeforeStart()
    {
        // Nothing to interrupt
        this.runner.stop();
    }
}
