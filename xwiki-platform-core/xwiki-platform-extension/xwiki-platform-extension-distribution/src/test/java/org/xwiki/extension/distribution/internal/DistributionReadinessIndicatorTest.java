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

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;
import org.xwiki.extension.distribution.internal.DistributionManager.DistributionState;
import org.xwiki.extension.distribution.internal.job.DefaultDistributionJob;
import org.xwiki.extension.distribution.internal.job.DistributionJobStatus;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Validate {@link DistributionReadinessIndicator}.
 *
 * @version $Id$
 */
@ComponentTest
class DistributionReadinessIndicatorTest
{
    @InjectMockComponents
    private DistributionReadinessIndicator indicator;

    @MockComponent
    private DistributionManager distributionManager;

    @Mock
    private DefaultDistributionJob job;

    @Mock
    private DistributionJobStatus status;

    @BeforeEach
    void beforeEach()
    {
        when(this.job.getStatus()).thenReturn(this.status);
    }

    @Test
    void noFarmJobNothingToDo()
    {
        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.SAME);

        Readiness readiness = this.indicator.check();

        assertEquals(State.READY, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("farmState", DistributionState.SAME), readiness.details());

        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.NONE);

        assertEquals(Readiness.ready(Map.of("farmState", DistributionState.NONE)), this.indicator.check());
    }

    @Test
    void noFarmJobPendingWizard()
    {
        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.NEW);

        Readiness readiness = this.indicator.check();

        assertEquals(State.DISTRIBUTION, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("farmState", DistributionState.NEW), readiness.details());

        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.UPGRADE);

        assertEquals(Readiness.of(State.DISTRIBUTION, true, Map.of("farmState", DistributionState.UPGRADE)),
            this.indicator.check());
    }

    @Test
    void noFarmJobUnknownState()
    {
        when(this.distributionManager.getFarmDistributionState()).thenThrow(new RuntimeException("store is down"));

        Readiness readiness = this.indicator.check();

        assertEquals(State.READY, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("farmState", "UNKNOWN"), readiness.details());
    }

    @Test
    void jobWithoutStatus()
    {
        when(this.distributionManager.getFarmJob()).thenReturn(this.job);
        when(this.job.getStatus()).thenReturn(null);

        Readiness readiness = this.indicator.check();

        assertEquals(State.DISTRIBUTION, readiness.state());
        assertFalse(readiness.ready());
        assertEquals(Map.of("jobState", JobStatus.State.NONE), readiness.details());
    }

    @Test
    void jobRunning()
    {
        when(this.distributionManager.getFarmJob()).thenReturn(this.job);
        when(this.status.getState()).thenReturn(JobStatus.State.RUNNING);

        Readiness readiness = this.indicator.check();

        assertEquals(State.DISTRIBUTION, readiness.state());
        assertFalse(readiness.ready());
        assertEquals(Map.of("jobState", JobStatus.State.RUNNING), readiness.details());
    }

    @Test
    void jobWaiting()
    {
        when(this.distributionManager.getFarmJob()).thenReturn(this.job);
        when(this.status.getState()).thenReturn(JobStatus.State.WAITING);

        Readiness readiness = this.indicator.check();

        assertEquals(State.DISTRIBUTION, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("jobState", JobStatus.State.WAITING), readiness.details());
    }

    @Test
    void jobFinished()
    {
        when(this.distributionManager.getFarmJob()).thenReturn(this.job);
        when(this.status.getState()).thenReturn(JobStatus.State.FINISHED);

        Readiness readiness = this.indicator.check();

        assertEquals(State.READY, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("jobState", JobStatus.State.FINISHED), readiness.details());
    }

    @Test
    void jobFailed()
    {
        when(this.distributionManager.getFarmJob()).thenReturn(this.job);
        when(this.status.getState()).thenReturn(JobStatus.State.FINISHED);
        RuntimeException error = new RuntimeException("wrapper", new Exception("Install failed"));
        when(this.status.getError()).thenReturn(error);

        Readiness readiness = this.indicator.check();

        assertEquals(State.FAILED, readiness.state());
        assertTrue(readiness.ready());
        assertEquals(Map.of("jobState", JobStatus.State.FINISHED, "error", error), readiness.details());
    }
}
