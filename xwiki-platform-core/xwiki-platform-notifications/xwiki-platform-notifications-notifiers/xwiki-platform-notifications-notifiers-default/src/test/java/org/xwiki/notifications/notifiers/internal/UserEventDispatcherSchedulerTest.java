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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.eventstream.RecordableEventDescriptorManager;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Validate {@link UserEventDispatcherScheduler}.
 *
 * @version $Id$
 */
@ComponentTest
class UserEventDispatcherSchedulerTest
{
    private static final long TIMEOUT = 10000;

    @InjectMockComponents
    private UserEventDispatcherScheduler scheduler;

    @MockComponent
    private UserEventDispatcher dispatcher;

    @MockComponent
    private RecordableEventDescriptorManager recordableEventDescriptorManager;

    @MockComponent
    private ExecutionContextManager contextManager;

    @MockComponent
    private Execution execution;

    @AfterEach
    void afterEach() throws ComponentLifecycleException
    {
        this.scheduler.dispose();
    }

    @Test
    void flushBeforeInitialize() throws Exception
    {
        // The first pre-filtering happens when the scheduler is started
        this.scheduler.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        verify(this.dispatcher, never()).flush();
    }

    @Test
    void flushAfterInitialize() throws Exception
    {
        this.scheduler.initialize();

        verify(this.dispatcher, timeout(TIMEOUT)).flush();

        // Request a new pre-filtering until it happens
        long end = System.currentTimeMillis() + TIMEOUT;
        while (Mockito.mockingDetails(this.dispatcher).getInvocations().size() < 2
            && System.currentTimeMillis() < end) {
            this.scheduler.flush();
            TimeUnit.MILLISECONDS.sleep(10);
        }

        verify(this.dispatcher, atLeast(2)).flush();
    }

    @Test
    void flushDuringRun() throws Exception
    {
        CountDownLatch runStarted = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        doAnswer(invocation -> {
            runStarted.countDown();
            releaseRun.await(TIMEOUT, TimeUnit.MILLISECONDS);
            return null;
        }).doNothing().when(this.dispatcher).flush();

        this.scheduler.initialize();
        assertTrue(runStarted.await(TIMEOUT, TimeUnit.MILLISECONDS));

        // A request received while a run is in progress (for example when the instance becomes the cluster singleton
        // node) is not dropped: another run follows
        this.scheduler.flush();
        releaseRun.countDown();

        verify(this.dispatcher, timeout(TIMEOUT).times(2)).flush();
    }

    @Test
    void flushAfterDispose() throws Exception
    {
        this.scheduler.initialize();
        verify(this.dispatcher, timeout(TIMEOUT)).flush();
        this.scheduler.dispose();

        // Ignored
        this.scheduler.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        verify(this.dispatcher, times(1)).flush();
    }
}
