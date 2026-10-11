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
package com.xpn.xwiki.plugin.scheduler;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.ObservationManager;
import org.xwiki.observation.event.ApplicationStoppedEvent;
import org.xwiki.script.service.ScriptServiceManager;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.user.UserReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.plugin.scheduler.internal.SchedulerJobsInitializedEvent;
import com.xpn.xwiki.plugin.scheduler.internal.SchedulersClassLoaderManager;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.XWikiServletRequestStub;
import com.xpn.xwiki.web.XWikiURLFactoryService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs {@link SchedulerPlugin} with a real Quartz scheduler (RAM job store).
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class SchedulerPluginQuartzTest
{
    private static final long WAIT = 10000;

    private static final AtomicInteger EXECUTIONS = new AtomicInteger();

    /**
     * Counts its executions.
     *
     * @version $Id$
     */
    public static class CountingJob implements Job
    {
        @Override
        public void execute(JobExecutionContext context)
        {
            EXECUTIONS.incrementAndGet();
        }
    }

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    private final DocumentReference jobDocumentReference = new DocumentReference("xwiki", "Scheduler", "Job");

    private final JobKey jobKey = new JobKey("xwiki:Scheduler.Job_0");

    private SchedulerPlugin plugin;

    private Scheduler scheduler;

    private ObservationManager observation;

    @BeforeEach
    void beforeEach() throws Exception
    {
        EXECUTIONS.set(0);

        Properties properties = new Properties();
        properties.setProperty(StdSchedulerFactory.PROP_SCHED_INSTANCE_NAME, "SchedulerPluginQuartzTest");
        properties.setProperty("org.quartz.threadPool.threadCount", "2");
        this.scheduler = new StdSchedulerFactory(properties).getScheduler();

        this.plugin =
            new SchedulerPlugin("scheduler", SchedulerPlugin.class.getName(), this.oldcore.getXWikiContext());
        this.plugin.setScheduler(this.scheduler);
        SchedulersClassLoaderManager classLoaderManager = mock(SchedulersClassLoaderManager.class);
        doReturn(CountingJob.class).when(classLoaderManager).loadClassAndRegister(any(), any());
        ReflectionUtils.setFieldValue(this.plugin, "schedulersClassLoaderManager", classLoaderManager);
        ReflectionUtils.setFieldValue(this.plugin, "enabled", true);

        this.observation = this.oldcore.getMocker().registerMockComponent(ObservationManager.class);
        this.oldcore.getMocker().registerMockComponent(ExecutionContextManager.class);
        this.oldcore.getMocker().registerMockComponent(ScriptServiceManager.class);
        this.oldcore.getXWikiContext().setRequest(new XWikiServletRequestStub());
        doReturn(mock(XWikiURLFactoryService.class)).when(this.oldcore.getSpyXWiki()).getURLFactoryService();
        doReturn(List.of("xwiki")).when(this.oldcore.getSpyXWiki()).getVirtualWikisDatabaseNames(any());
        when(this.oldcore.getMockStore().searchDocumentReferences(anyString(), any(XWikiContext.class)))
            .thenReturn(List.of(this.jobDocumentReference));
    }

    @AfterEach
    void afterEach() throws Exception
    {
        this.scheduler.shutdown(false);
    }

    private BaseObject saveJob(String status, String cron, UserReference author) throws Exception
    {
        XWikiContext xcontext = this.oldcore.getXWikiContext();
        XWikiDocument document = this.oldcore.getSpyXWiki().getDocument(this.jobDocumentReference, xcontext).clone();
        BaseObject job = document.getXObject(SchedulerPlugin.XWIKI_JOB_CLASSREFERENCE);
        if (job == null) {
            job = document.newXObject(SchedulerPlugin.XWIKI_JOB_CLASSREFERENCE, xcontext);
            job.setStringValue("jobName", "Job");
            job.setStringValue("jobClass", CountingJob.class.getName());
            job.setStringValue("contextUser", "XWiki.Admin");
            job.setStringValue("contextLang", "en");
            job.setStringValue("contextDatabase", "xwiki");
        }
        job.setStringValue("status", status);
        job.setStringValue("cron", cron);
        document.getAuthors().setEffectiveMetadataAuthor(author);
        this.oldcore.getSpyXWiki().saveDocument(document, xcontext);

        return job;
    }

    private XWikiDocument getJobDocument() throws Exception
    {
        return this.oldcore.getSpyXWiki().getDocument(this.jobDocumentReference, this.oldcore.getXWikiContext());
    }

    /**
     * Save the job page and send the event the plugin receives (locally or from another cluster member).
     */
    private void editJob(String status, String cron, UserReference author) throws Exception
    {
        XWikiDocument previous = getJobDocument().clone();
        saveJob(status, cron, author);
        XWikiDocument document = getJobDocument().clone();
        document.setOriginalDocument(previous);
        this.plugin.onEvent(new DocumentUpdatedEvent(this.jobDocumentReference), document,
            this.oldcore.getXWikiContext());
    }

    private void waitForExecutions(int count) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + WAIT;
        while (EXECUTIONS.get() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(EXECUTIONS.get() >= count, "Expected at least " + count + " executions");
    }

    private BaseObject getScheduledJobObject() throws Exception
    {
        JobDetail jobDetail = this.scheduler.getJobDetail(this.jobKey);

        return (BaseObject) jobDetail.getJobDataMap().get("xjob");
    }

    @Test
    void startStopAndEdit() throws Exception
    {
        UserReference author = mock(UserReference.class, "author");
        UserReference editor = mock(UserReference.class, "editor");
        saveJob("Normal", "* * * * * ?", author);
        XWikiContext xcontext = this.oldcore.getXWikiContext();

        // Start: the job is restored from its page and fires
        this.plugin.startScheduler();
        verify(this.observation, timeout(WAIT)).notify(any(SchedulerJobsInitializedEvent.class), isNull());
        waitForExecutions(1);

        // Stop (the instance is no longer the cluster singleton node): no job anymore
        this.plugin.stopScheduler();
        assertTrue(this.scheduler.isInStandbyMode());
        assertTrue(this.scheduler.getJobKeys(GroupMatcher.anyJobGroup()).isEmpty());
        int executions = EXECUTIONS.get();
        Thread.sleep(1500);
        assertEquals(executions, EXECUTIONS.get());
        // The page answers
        assertEquals(JobState.STATE_NORMAL, this.plugin.getJobStatus(getJobDocument().clone()
            .getXObject(SchedulerPlugin.XWIKI_JOB_CLASSREFERENCE), xcontext).getValue());

        // Start again: restored and fired again, without saving the page
        this.plugin.startScheduler();
        verify(this.observation, timeout(WAIT).times(2)).notify(any(SchedulerJobsInitializedEvent.class), isNull());
        waitForExecutions(executions + 1);
        assertEquals("1.1", getJobDocument().getVersion());
        BaseObject scheduledJob = getScheduledJobObject();
        assertSame(author, scheduledJob.getOwnerDocument().getAuthors().getEffectiveMetadataAuthor());

        // Paused by editing the page: the job is rebuilt from the page, with its new author
        editJob("Paused", "* * * * * ?", editor);
        assertEquals(TriggerState.PAUSED, this.scheduler.getTriggerState(new TriggerKey(this.jobKey.getName())));
        assertSame(editor, getScheduledJobObject().getOwnerDocument().getAuthors().getEffectiveMetadataAuthor());

        // Resumed by editing the page
        editJob("Normal", "* * * * * ?", author);
        assertEquals(TriggerState.NORMAL, this.scheduler.getTriggerState(new TriggerKey(this.jobKey.getName())));
        assertSame(author, getScheduledJobObject().getOwnerDocument().getAuthors().getEffectiveMetadataAuthor());

        // New schedule
        editJob("Normal", "0 0 0 1 1 ? 2099", editor);
        assertEquals(2099, 1900 + this.scheduler.getTrigger(new TriggerKey(this.jobKey.getName())).getNextFireTime()
            .getYear());
        assertSame(editor, getScheduledJobObject().getOwnerDocument().getAuthors().getEffectiveMetadataAuthor());

        // Unscheduled
        editJob("None", "0 0 0 1 1 ? 2099", author);
        assertEquals(TriggerState.NONE, this.scheduler.getTriggerState(new TriggerKey(this.jobKey.getName())));

        // XWiki stops: the scheduler is shut down and can't be started anymore
        this.plugin.onEvent(new ApplicationStoppedEvent(), null, null);
        assertTrue(this.scheduler.isShutdown());
        this.plugin.startScheduler();
        Thread.sleep(500);
        verify(this.observation, times(2)).notify(any(SchedulerJobsInitializedEvent.class), isNull());
    }

    @Test
    void stopBeforeTheStartIsDone() throws Exception
    {
        saveJob("Normal", "* * * * * ?", mock(UserReference.class));

        this.plugin.startScheduler();
        this.plugin.stopScheduler();
        int executions = EXECUTIONS.get();

        // The start gives up (or its jobs are cleared): nothing fires anymore
        verify(this.observation, timeout(WAIT)).notify(any(SchedulerJobsInitializedEvent.class), isNull());
        Thread.sleep(1500);
        assertTrue(this.scheduler.isInStandbyMode());
        assertTrue(this.scheduler.getJobKeys(GroupMatcher.anyJobGroup()).isEmpty());
        assertEquals(executions, EXECUTIONS.get());
    }
}
