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

import java.util.Map;

import jakarta.inject.Named;
import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;
import org.xwiki.environment.Environment;
import org.xwiki.environment.internal.ServletEnvironment;
import org.xwiki.job.Job;
import org.xwiki.job.event.status.JobProgress;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.store.migration.DataMigrationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link XWikiInitializationReadinessIndicator}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiInitializationReadinessIndicatorTest
{
    @InjectMockComponents
    private XWikiInitializationReadinessIndicator indicator;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @MockComponent
    @Named("xwiki.init")
    private Job initializerJob;

    @MockComponent
    @Named("hibernate")
    private DataMigrationManager dataMigrationManager;

    @MockComponent(classToMock = ServletEnvironment.class)
    private Environment environment;

    @Mock
    private JobStatus status;

    @Mock
    private JobProgress progress;

    private final ServletContext servletContext = mock();

    @BeforeEach
    void beforeEach()
    {
        when(((ServletEnvironment) this.environment).getJakartaServletContext()).thenReturn(this.servletContext);
        when(this.status.getProgress()).thenReturn(this.progress);
        when(this.progress.getOffset()).thenReturn(0.5);
    }

    @Test
    void notStarted()
    {
        Readiness readiness = this.indicator.check();

        assertEquals(State.STARTING, readiness.state());
        assertFalse(readiness.ready());
        assertEquals(Map.of("started", false), readiness.details());
        verify(this.dataMigrationManager, never()).isMigrating();
    }

    @Test
    void running()
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.RUNNING);

        Readiness readiness = this.indicator.check();

        assertEquals(State.STARTING, readiness.state());
        assertFalse(readiness.ready());
        assertEquals(Map.of("started", true, "jobState", JobStatus.State.RUNNING, "progress", 0.5),
            readiness.details());
    }

    @Test
    void runningWithoutProgress()
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.RUNNING);
        when(this.status.getProgress()).thenReturn(null);

        Readiness readiness = this.indicator.check();

        assertEquals(State.STARTING, readiness.state());
        assertEquals(Map.of("started", true, "jobState", JobStatus.State.RUNNING), readiness.details());
    }

    @Test
    void migrating()
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.RUNNING);
        when(this.dataMigrationManager.isMigrating()).thenReturn(true);

        Readiness readiness = this.indicator.check();

        assertEquals(State.MIGRATING, readiness.state());
        assertFalse(readiness.ready());
        assertEquals(0.5, readiness.details().get("progress"));
    }

    @Test
    void finishedAndRegistered()
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.FINISHED);
        when(this.servletContext.getAttribute(XWiki.DEFAULT_MAIN_WIKI)).thenReturn(mock(XWiki.class));

        Readiness readiness = this.indicator.check();

        assertEquals(State.READY, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("started", true, "jobState", JobStatus.State.FINISHED), readiness.details());
        verify(this.dataMigrationManager, never()).isMigrating();
    }

    @Test
    void finishedWithError()
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.FINISHED);
        RuntimeException error = new RuntimeException("wrapper", new IllegalStateException("Database is down"));
        when(this.status.getError()).thenReturn(error);

        Readiness readiness = this.indicator.check();

        assertEquals(State.FAILED, readiness.state());
        assertFalse(readiness.ready());
        assertSame(error, readiness.details().get("error"));
    }

    @Test
    void migrationManagerNotAvailable() throws Exception
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.RUNNING);
        this.componentManager.unregisterComponent(DataMigrationManager.class, "hibernate");

        Readiness readiness = this.indicator.check();

        assertEquals(State.STARTING, readiness.state());
        assertFalse(readiness.ready());
    }

    @Test
    void finishedWithoutErrorAndNotRegistered()
    {
        when(this.initializerJob.getStatus()).thenReturn(this.status);
        when(this.status.getState()).thenReturn(JobStatus.State.FINISHED);

        Readiness readiness = this.indicator.check();

        assertEquals(State.FAILED, readiness.state());
        assertFalse(readiness.ready());
        assertEquals("The initialization ended without registering the main wiki", readiness.details().get("error"));
    }
}
