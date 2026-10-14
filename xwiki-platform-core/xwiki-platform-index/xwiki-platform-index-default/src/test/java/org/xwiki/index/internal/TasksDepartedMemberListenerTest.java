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
package org.xwiki.index.internal;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Provider;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.context.Execution;
import org.xwiki.context.internal.DefaultExecution;
import org.xwiki.context.internal.DefaultExecutionContextManager;
import org.xwiki.index.TaskManager;
import org.xwiki.observation.remote.ClusterMemberDepartedEvent;
import org.xwiki.observation.remote.ClusterMemberDepartureManager;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
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

import com.xpn.xwiki.XWikiException;

import ch.qos.logback.classic.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TasksDepartedMemberListener}. The work submitted to the executor of the listener is run
 * synchronously, and the delayed retries are run explicitly.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({ DefaultExecution.class, DefaultExecutionContextManager.class })
class TasksDepartedMemberListenerTest
{
    private static final String CURRENT_ID = "xwiki-worker-0";

    private static final String DEPARTED_ID = "xwiki-web-1";

    private static final String OTHER_ID = "xwiki-web-2";

    private static final String WIKI1 = "xwiki";

    private static final String WIKI2 = "subwiki";

    private static final long MAX_AGE = 86400;

    private static final long DELAY = 900;

    private static final Date DEPARTURE = new Date(1760000000000L);

    private static final Date OLDEST = new Date(DEPARTURE.getTime() - MAX_AGE * 1000);

    private static final String TOOK_OVER_MESSAGE =
        "Took over [%s] tasks queued by cluster member [xwiki-web-1], which is gone for good (absent since [%s])";

    private static final String STOPPED_MESSAGE = "Stopped taking over the tasks of cluster member [xwiki-web-1]: it"
        + " came back or this instance is not the cluster singleton node anymore";

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @InjectMockComponents
    private TasksDepartedMemberListener listener;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @MockComponent
    private ClusterMemberDepartureManager departureManager;

    @MockComponent
    private RemoteObservationManagerConfiguration configuration;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private Provider<TasksStore> tasksStoreProvider;

    @MockComponent
    private Provider<TaskManager> taskManagerProvider;

    @Mock
    private TasksStore tasksStore;

    @Mock
    private DefaultTasksManager tasksManager;

    @Mock
    private ScheduledExecutorService executor;

    /**
     * The tasks scheduled with a delay on the executor.
     */
    private final List<Runnable> scheduled = new ArrayList<>();

    private ScheduledExecutorService originalExecutor;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @BeforeEach
    void beforeEach() throws Exception
    {
        when(this.configuration.isEnabled()).thenReturn(true);
        when(this.configuration.getReclaimDelay()).thenReturn(DELAY);
        when(this.configuration.getReclaimMaxAge()).thenReturn(MAX_AGE);
        when(this.configuration.getId()).thenReturn(CURRENT_ID);
        when(this.tasksStoreProvider.get()).thenReturn(this.tasksStore);
        when(this.taskManagerProvider.get()).thenReturn(this.tasksManager);
        when(this.wikiDescriptorManager.getAllIds()).thenReturn(List.of(WIKI1, WIKI2));
        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        when(this.departureManager.isDeparted(DEPARTED_ID)).thenReturn(true);
        when(this.departureManager.getDepartureDate(DEPARTED_ID)).thenReturn(DEPARTURE);

        // Run the work synchronously
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(this.executor).execute(any());
        doAnswer(invocation -> {
            assertEquals(TasksDepartedMemberListener.LOAD_RETRY_DELAY, invocation.<Long>getArgument(1));
            assertEquals(TimeUnit.SECONDS, invocation.getArgument(2));
            this.scheduled.add(invocation.getArgument(0));
            return null;
        }).when(this.executor).schedule(any(Runnable.class), anyLong(), any());
        this.originalExecutor = (ScheduledExecutorService) FieldUtils.readField(this.listener, "executor", true);
        ReflectionUtils.setFieldValue(this.listener, "executor", this.executor);
    }

    @AfterEach
    void afterEach()
    {
        this.originalExecutor.shutdownNow();
    }

    private void notifySingletonNodeChanged(boolean singletonNode)
    {
        // The listener reads the current state from the manager
        when(this.singletonManager.isSingletonNode()).thenReturn(singletonNode);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, singletonNode);
    }

    private void notifyReady()
    {
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
    }

    private void notifyDeparted(String memberId)
    {
        this.listener.onEvent(new ClusterMemberDepartedEvent(), this.departureManager, memberId);
    }

    private void runScheduled()
    {
        List<Runnable> runnables = List.copyOf(this.scheduled);
        this.scheduled.clear();
        runnables.forEach(Runnable::run);
    }

    private void assertTookOver(int index, int count)
    {
        assertEquals(String.format(TOOK_OVER_MESSAGE, count, DEPARTURE), this.logCapture.getMessage(index));
        assertEquals(Level.INFO, this.logCapture.getLogEvent(index).getLevel());
    }

    @Test
    void getEvents()
    {
        assertEquals(3, this.listener.getEvents().size());
        assertEquals(TasksDepartedMemberListener.NAME, this.listener.getName());
    }

    @Test
    void scanWhenReady() throws Exception
    {
        when(this.tasksStore.getInstanceIds(eq(WIKI1), eq(CURRENT_ID), any())).thenReturn(List.of(DEPARTED_ID));
        when(this.tasksStore.getInstanceIds(eq(WIKI2), eq(CURRENT_ID), any()))
            .thenReturn(List.of(OTHER_ID, DEPARTED_ID));

        long before = System.currentTimeMillis();
        notifyReady();
        long after = System.currentTimeMillis();

        verify(this.departureManager).watch(Set.of(DEPARTED_ID, OTHER_ID));

        // The members with tasks queued during the maximum age plus the delay are watched (the delay is the minimum
        // time between a departure and its notification)
        ArgumentCaptor<Date> oldestCaptor = ArgumentCaptor.forClass(Date.class);
        verify(this.tasksStore).getInstanceIds(eq(WIKI1), eq(CURRENT_ID), oldestCaptor.capture());
        long oldest = oldestCaptor.getValue().getTime();
        long window = (MAX_AGE + DELAY) * 1000;
        assertTrue(oldest >= before - window && oldest <= after - window);

        // Nothing is taken over by the scan itself
        verify(this.tasksStore, never()).reassignTasks(any(), any(), any(), any(), any());
    }

    @Test
    void scanWithoutMaxAge() throws Exception
    {
        when(this.configuration.getReclaimMaxAge()).thenReturn(-1L);
        when(this.tasksStore.getInstanceIds(WIKI1, CURRENT_ID, null)).thenReturn(List.of(DEPARTED_ID));
        when(this.tasksStore.getInstanceIds(WIKI2, CURRENT_ID, null)).thenReturn(List.of());

        notifyReady();

        verify(this.departureManager).watch(Set.of(DEPARTED_ID));
    }

    @Test
    void scanWithAbsurdMaxAge() throws Exception
    {
        when(this.configuration.getReclaimMaxAge()).thenReturn(Long.MAX_VALUE);

        notifyReady();

        // No overflow
        verify(this.tasksStore).getInstanceIds(WIKI1, CURRENT_ID, new Date(0));
    }

    @Test
    void scanWithoutForeignTasks() throws Exception
    {
        when(this.tasksStore.getInstanceIds(any(), any(), any())).thenReturn(List.of());

        notifyReady();

        verify(this.tasksStore).getInstanceIds(eq(WIKI2), eq(CURRENT_ID), any());
        verify(this.departureManager, never()).watch(any());
    }

    @Test
    void scanFailures() throws Exception
    {
        when(this.tasksStore.getInstanceIds(eq(WIKI1), eq(CURRENT_ID), any()))
            .thenThrow(new XWikiException(0, 0, "Query failed"));
        when(this.tasksStore.getInstanceIds(eq(WIKI2), eq(CURRENT_ID), any())).thenReturn(List.of(OTHER_ID));

        notifyReady();

        // The other wikis are still scanned
        verify(this.departureManager).watch(Set.of(OTHER_ID));
        assertEquals("Failed to list the cluster members with tasks in wiki [xwiki]. Cause: [XWikiException: Error"
            + " number 0 in 0: Query failed].", this.logCapture.getMessage(0));
        assertEquals(Level.WARN, this.logCapture.getLogEvent(0).getLevel());
    }

    @Test
    void scanWhenTheWikisCannotBeListed() throws Exception
    {
        when(this.wikiDescriptorManager.getAllIds()).thenThrow(new WikiManagerException("Failed"));

        notifyReady();

        assertEquals("Failed to list the wikis to take over the tasks of departed cluster members. Cause:"
            + " [WikiManagerException: Failed].", this.logCapture.getMessage(0));
        verifyNoInteractions(this.tasksStore);
        verify(this.departureManager, never()).watch(any());
    }

    @Test
    void scanStopsWhenTheSingletonNodeIsLost() throws Exception
    {
        when(this.tasksStore.getInstanceIds(eq(WIKI1), eq(CURRENT_ID), any())).thenAnswer(invocation -> {
            // Lost while scanning the first wiki
            when(this.singletonManager.isSingletonNode()).thenReturn(false);
            return List.of(DEPARTED_ID);
        });

        notifyReady();

        verify(this.tasksStore).getInstanceIds(eq(WIKI1), eq(CURRENT_ID), any());
        verify(this.tasksStore, never()).getInstanceIds(eq(WIKI2), any(), any());
        verify(this.departureManager, never()).watch(any());
    }

    @Test
    void scanWhenTheSingletonNodeIsGained() throws Exception
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);
        when(this.tasksStore.getInstanceIds(any(), any(), any())).thenReturn(List.of(DEPARTED_ID));

        notifyReady();
        verifyNoInteractions(this.tasksStore);

        notifySingletonNodeChanged(true);
        verify(this.departureManager).watch(Set.of(DEPARTED_ID));

        // Nothing to stop when the role is lost
        notifySingletonNodeChanged(false);
        verify(this.departureManager).watch(any());
    }

    @Test
    void singletonNodeGainedBeforeApplicationReady() throws Exception
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        // Makes the listener ready, but the application is not ready yet, and the current instance is not the
        // singleton node (the departure is ignored, the scan watches the member again)
        notifyDeparted(DEPARTED_ID);
        notifySingletonNodeChanged(true);

        verifyNoInteractions(this.tasksStore);

        // The scan is done when the application is ready
        notifyReady();
        verify(this.tasksStore).getInstanceIds(eq(WIKI2), eq(CURRENT_ID), any());
        verify(this.tasksStore, never()).reassignTasks(any(), any(), any(), any(), any());
    }

    @Test
    void departureBeforeApplicationReady() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(2);

        // The store can't be used yet
        notifyDeparted(DEPARTED_ID);
        verifyNoInteractions(this.tasksStore);

        // Taken over when the application is ready, after the scan
        notifyReady();
        verify(this.tasksStore).getInstanceIds(eq(WIKI2), eq(CURRENT_ID), any());
        verify(this.tasksStore).reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE);
        verify(this.tasksManager).loadTasks(WIKI1);
        assertTookOver(0, 2);

        // Only once
        notifySingletonNodeChanged(false);
        notifySingletonNodeChanged(true);
        verify(this.tasksStore).reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE);
    }

    @Test
    void takeOver() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(3);
        when(this.tasksStore.reassignTasks(WIKI2, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(0);
        when(this.tasksStore.countTasks(WIKI1, DEPARTED_ID)).thenReturn(0L);
        when(this.tasksStore.countTasks(WIKI2, DEPARTED_ID)).thenReturn(2L);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verify(this.tasksManager).loadTasks(WIKI1);
        verify(this.tasksManager, never()).loadTasks(WIKI2);
        assertTookOver(0, 3);
        assertEquals("[2] tasks queued by cluster member [xwiki-web-1] were not taken over because they are dated more"
            + " than [86400] seconds before its departure, or after it", this.logCapture.getMessage(1));
    }

    @Test
    void takeOverTwice() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(3, 0);

        notifyReady();
        notifyDeparted(DEPARTED_ID);
        assertTookOver(0, 3);

        // The same departure notified again (for example when another listener watches the member): nothing left
        notifyDeparted(DEPARTED_ID);

        verify(this.tasksStore, times(2)).reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE);
        verify(this.tasksManager).loadTasks(WIKI1);
        assertEquals(1, this.logCapture.size());
    }

    @Test
    void takeOverWithoutMaxAge() throws Exception
    {
        when(this.configuration.getReclaimMaxAge()).thenReturn(-1L);
        when(this.tasksStore.reassignTasks(WIKI2, DEPARTED_ID, CURRENT_ID, null, DEPARTURE)).thenReturn(1);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verify(this.tasksStore).reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, null, DEPARTURE);
        verify(this.tasksManager).loadTasks(WIKI2);
        assertTookOver(0, 1);
    }

    @Test
    void takeOverWithoutTasks() throws Exception
    {
        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verify(this.tasksStore).reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE);
        verify(this.tasksStore).reassignTasks(WIKI2, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE);
        verify(this.tasksStore).countTasks(WIKI2, DEPARTED_ID);
        verify(this.tasksManager, never()).loadTasks(any());
        // Nothing is logged above the debug level
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void onlyOlderTasksLeft() throws Exception
    {
        when(this.tasksStore.countTasks(WIKI1, DEPARTED_ID)).thenReturn(4L);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        assertEquals(1, this.logCapture.size());
        assertEquals("[4] tasks queued by cluster member [xwiki-web-1] were not taken over because they are dated more"
            + " than [86400] seconds before its departure, or after it", this.logCapture.getMessage(0));
    }

    @Test
    void takeOverFailure() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE))
            .thenThrow(new XWikiException(0, 0, "Update failed"));
        when(this.tasksStore.reassignTasks(WIKI2, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(1);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        assertEquals("Failed to take over the tasks of cluster member [xwiki-web-1] in wiki [xwiki]. Cause:"
            + " [XWikiException: Error number 0 in 0: Update failed].", this.logCapture.getMessage(0));
        assertEquals(Level.WARN, this.logCapture.getLogEvent(0).getLevel());
        assertTookOver(1, 1);
        verify(this.tasksManager).loadTasks(WIKI2);
    }

    @Test
    void loadFailureRetried() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(3);
        when(this.tasksStore.reassignTasks(WIKI2, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(1);
        when(this.tasksManager.loadTasks(WIKI1)).thenThrow(new XWikiException(0, 0, "Read failed"))
            .thenThrow(new XWikiException(0, 0, "Read failed again")).thenReturn(3);
        when(this.tasksManager.loadTasks(WIKI2)).thenThrow(new XWikiException(0, 0, "Read failed")).thenReturn(1);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        // The update is done: the tasks are counted as taken over, and the load is retried
        assertEquals("[3] tasks of cluster member [xwiki-web-1] were assigned to this instance in wiki [xwiki] but"
            + " could not be added to its queue: retrying in [60] seconds (they are also loaded when this instance"
            + " restarts). Cause: [XWikiException: Error number 0 in 0: Read failed].",
            this.logCapture.getMessage(0));
        assertEquals(Level.WARN, this.logCapture.getLogEvent(0).getLevel());
        assertEquals("[1] tasks of cluster member [xwiki-web-1] were assigned to this instance in wiki [subwiki] but"
            + " could not be added to its queue: retrying in [60] seconds (they are also loaded when this instance"
            + " restarts). Cause: [XWikiException: Error number 0 in 0: Read failed].", this.logCapture.getMessage(1));
        assertTookOver(2, 4);
        // One retry for the two wikis
        assertEquals(1, this.scheduled.size());

        // First retry: the second wiki is loaded, the first one fails again
        runScheduled();
        assertEquals("Added [1] tasks taken over from departed cluster members in wiki [subwiki] to the queue",
            this.logCapture.getMessage(3));
        assertEquals(1, this.scheduled.size());

        // Second retry: loaded
        runScheduled();
        assertEquals("Added [3] tasks taken over from departed cluster members in wiki [xwiki] to the queue",
            this.logCapture.getMessage(4));
        assertEquals(0, this.scheduled.size());
        verify(this.tasksManager, times(3)).loadTasks(WIKI1);
        verify(this.tasksManager, times(2)).loadTasks(WIKI2);
    }

    @Test
    void loadRetryOfADisposedListener() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenReturn(3);
        when(this.tasksManager.loadTasks(WIKI1)).thenThrow(new XWikiException(0, 0, "Read failed"));
        doThrow(new RejectedExecutionException()).when(this.executor).schedule(any(Runnable.class), anyLong(),
            any());

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        // The tasks are loaded when the instance restarts
        assertEquals(2, this.logCapture.size());
        assertTrue(
            this.logCapture.getMessage(0).startsWith("[3] tasks of cluster member [xwiki-web-1] were assigned"));
        assertTookOver(1, 3);
    }

    @Test
    void takeOverStopsWhenTheMemberComesBack() throws Exception
    {
        when(this.tasksStore.reassignTasks(WIKI1, DEPARTED_ID, CURRENT_ID, OLDEST, DEPARTURE)).thenAnswer(i -> {
            // Comes back while the tasks of the first wiki are taken over
            when(this.departureManager.isDeparted(DEPARTED_ID)).thenReturn(false);
            return 2;
        });

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        assertEquals(STOPPED_MESSAGE, this.logCapture.getMessage(0));
        assertTookOver(1, 2);
        verify(this.tasksManager).loadTasks(WIKI1);
        verify(this.tasksStore, never()).reassignTasks(eq(WIKI2), any(), any(), any(), any());
    }

    @Test
    void memberCameBack() throws Exception
    {
        when(this.departureManager.isDeparted(DEPARTED_ID)).thenReturn(false);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verify(this.departureManager).isDeparted(DEPARTED_ID);
        verify(this.tasksStore, never()).reassignTasks(any(), any(), any(), any(), any());
    }

    @Test
    void memberCameBackBeforeTheDepartureDateIsRead() throws Exception
    {
        when(this.departureManager.getDepartureDate(DEPARTED_ID)).thenReturn(null);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verify(this.departureManager).getDepartureDate(DEPARTED_ID);
        verify(this.tasksStore, never()).reassignTasks(any(), any(), any(), any(), any());
    }

    @Test
    void notSingletonNode() throws Exception
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verify(this.departureManager, never()).isDeparted(any());
        verifyNoInteractions(this.tasksStore);
        verifyNoInteractions(this.tasksManager);
    }

    @Test
    void singletonNodeLostBeforeTheTakeOver() throws Exception
    {
        notifyReady();
        // The event is received as singleton node, then the role is lost before the work starts
        when(this.departureManager.isDeparted(DEPARTED_ID)).thenAnswer(invocation -> {
            when(this.singletonManager.isSingletonNode()).thenReturn(false);
            return true;
        });
        notifyDeparted(DEPARTED_ID);

        assertEquals(STOPPED_MESSAGE, this.logCapture.getMessage(0));
        verify(this.tasksStore, never()).reassignTasks(any(), any(), any(), any(), any());
    }

    @Test
    void remoteObservationDisabled() throws Exception
    {
        when(this.configuration.isEnabled()).thenReturn(false);

        notifyReady();
        notifyDeparted(DEPARTED_ID);
        notifySingletonNodeChanged(false);
        notifySingletonNodeChanged(true);

        verifyNoInteractions(this.executor);
        verifyNoInteractions(this.tasksStore);
        verifyNoInteractions(this.departureManager);
        verifyNoInteractions(this.wikiDescriptorManager);
    }

    @Test
    void negativeReclaimDelay() throws Exception
    {
        when(this.configuration.getReclaimDelay()).thenReturn(-1L);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verifyNoInteractions(this.executor);
        verifyNoInteractions(this.tasksStore);
        verifyNoInteractions(this.departureManager);
    }

    @Test
    void noCurrentIdentifier() throws Exception
    {
        // The tasks would be assigned to nobody
        when(this.configuration.getId()).thenReturn(null);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verifyNoInteractions(this.executor);
        verifyNoInteractions(this.tasksStore);
    }

    @Test
    void otherTaskManager() throws Exception
    {
        TaskManager otherTaskManager = mock(TaskManager.class);
        when(this.taskManagerProvider.get()).thenReturn(otherTaskManager);

        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verifyNoInteractions(this.executor);
        verifyNoInteractions(this.tasksStore);
        verifyNoInteractions(this.departureManager);
        verifyNoInteractions(otherTaskManager);
    }

    @Test
    void remoteApplicationReadyEventIgnored() throws Exception
    {
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);
        notifyReady();
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(false);

        // A remote ApplicationReadyEvent does not mean that this instance is ready
        notifyDeparted(DEPARTED_ID);

        verifyNoInteractions(this.tasksStore);
    }

    @Test
    void invalidEventData() throws Exception
    {
        notifyReady();
        verify(this.tasksStore).getInstanceIds(eq(WIKI2), eq(CURRENT_ID), any());

        this.listener.onEvent(new ClusterMemberDepartedEvent(), this.departureManager, null);

        verify(this.departureManager, never()).isDeparted(any());
    }

    @Test
    void disposed() throws Exception
    {
        when(this.executor.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new RejectedExecutionException()).when(this.executor).execute(any());

        this.listener.dispose();
        verify(this.executor).shutdown();
        verify(this.executor, never()).shutdownNow();

        // Nothing is done, nothing fails
        notifyReady();
        notifyDeparted(DEPARTED_ID);

        verifyNoInteractions(this.tasksStore);
        verifyNoInteractions(this.departureManager);
    }

    @Test
    void disposedWhileWorking() throws Exception
    {
        this.listener.dispose();
        verify(this.executor).shutdownNow();

        when(this.executor.awaitTermination(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        this.listener.dispose();
        verify(this.executor, times(2)).shutdownNow();
        // Clear the interrupted flag
        assertTrue(Thread.interrupted());
    }

    @Test
    void workRunsOnTheExecutorThreadInAnExecutionContext() throws Exception
    {
        ReflectionUtils.setFieldValue(this.listener, "executor", this.originalExecutor);
        List<String> threads = new ArrayList<>();
        Execution execution = this.componentManager.getInstance(Execution.class);
        when(this.tasksStore.getInstanceIds(eq(WIKI1), eq(CURRENT_ID), any())).thenAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            threads.add(String.valueOf(execution.getContext() != null));
            return List.of(DEPARTED_ID);
        });

        notifyReady();

        verify(this.departureManager, timeout(10000)).watch(Set.of(DEPARTED_ID));
        assertEquals(List.of("XWiki index tasks of departed cluster members", "true"), threads);

        this.listener.dispose();
        assertTrue(this.originalExecutor.isShutdown());
    }
}
