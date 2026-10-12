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

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.inject.Named;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventSearchResult;
import org.xwiki.eventstream.EventStore;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.internal.DefaultEvent;
import org.xwiki.eventstream.internal.StreamEventSearchResult;
import org.xwiki.eventstream.query.CompareQueryCondition;
import org.xwiki.eventstream.query.SimpleEventQuery;
import org.xwiki.eventstream.query.SortableEventQuery.SortClause.Order;
import org.xwiki.job.Job;
import org.xwiki.notifications.NotificationConfiguration;
import org.xwiki.notifications.notifiers.internal.email.live.MissingLiveNotificationMailsRequest;
import org.xwiki.notifications.notifiers.internal.email.live.PrefilteringLiveNotificationEmailManager;
import org.xwiki.observation.remote.ClusterMemberDepartedEvent;
import org.xwiki.observation.remote.ClusterMemberDepartureManager;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Validate {@link NotificationsDepartedMemberListener}.
 *
 * @version $Id$
 */
@ComponentTest
class NotificationsDepartedMemberListenerTest
{
    private static final long TIMEOUT = 10000;

    private static final Set<String> FIELDS = Set.of(Event.FIELD_REMOTE_OBSERVATION_ID);

    private static final Date DEPARTURE = new Date(100000000L);

    private static final long DELAY = 900;

    @InjectMockComponents
    private NotificationsDepartedMemberListener listener;

    @MockComponent
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    @MockComponent
    private NotificationConfiguration notificationConfiguration;

    @MockComponent
    private ClusterMemberDepartureManager departureManager;

    @MockComponent
    private UserEventDispatcherScheduler scheduler;

    @MockComponent
    private UserEventDispatcher dispatcher;

    @MockComponent
    private PrefilteringLiveNotificationEmailManager mailManager;

    @MockComponent
    private EventStore events;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @MockComponent
    @Named("context")
    private ComponentManager componentManager;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @BeforeComponent
    void beforeComponent() throws ComponentLookupException
    {
        when(this.componentManager.getInstance(ExecutionContextManager.class))
            .thenReturn(mock(ExecutionContextManager.class));
        when(this.componentManager.getInstance(Execution.class)).thenReturn(mock(Execution.class));
    }

    @BeforeEach
    void beforeEach()
    {
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
        when(this.remoteObservationConfiguration.getReclaimMaxAge()).thenReturn(-1L);
        when(this.remoteObservationConfiguration.getReclaimDelay()).thenReturn(DELAY);
        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(true);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        when(this.departureManager.getMemberIds()).thenReturn(Set.of("web-0", "own"));

        // Take over the departed members right away
        ReflectionUtils.setFieldValue(this.listener, "takeOverDelay", 0L);
    }

    @AfterEach
    void afterEach() throws ComponentLifecycleException
    {
        this.listener.dispose();
    }

    private SimpleEventQuery scanQuery(boolean prefiltered, String previousMember)
    {
        SimpleEventQuery query = new SimpleEventQuery(0, 1);
        if (prefiltered) {
            query.eq(Event.FIELD_PREFILTERED, false);
        }
        query.not().eq(Event.FIELD_REMOTE_OBSERVATION_ID, null);
        query.not().in(Event.FIELD_REMOTE_OBSERVATION_ID, List.of("own", "web-0"));
        if (previousMember != null) {
            query.greater(Event.FIELD_REMOTE_OBSERVATION_ID, previousMember);
        }
        query.addSort(Event.FIELD_REMOTE_OBSERVATION_ID, Order.ASC);

        return query;
    }

    private EventSearchResult result(String... memberIds)
    {
        return new StreamEventSearchResult(memberIds.length, 0, memberIds.length, Stream.of(memberIds).map(id -> {
            DefaultEvent event = new DefaultEvent();
            event.setRemoteObservationId(id);
            return event;
        }));
    }

    private void departed(String memberId, Date departure)
    {
        when(this.departureManager.isDeparted(memberId)).thenReturn(true);
        when(this.departureManager.getDepartureDate(memberId)).thenReturn(departure);
    }

    private void ready() throws EventStreamException
    {
        when(this.events.search(any(), any())).thenAnswer(invocation -> result());
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        verify(this.events, timeout(TIMEOUT)).search(any(), any());
    }

    private void departure(String memberId)
    {
        this.listener.onEvent(new ClusterMemberDepartedEvent(), this.departureManager, memberId);
    }

    private Job job() throws InterruptedException
    {
        Job job = mock(Job.class);
        when(job.join(anyLong(), any())).thenReturn(true);

        return job;
    }

    private String takingOver(String memberId, Date departure)
    {
        return "Taking over the pending notifications of the cluster member [" + memberId + "] gone for good (it left"
            + " at [" + departure + "])";
    }

    @Test
    void scanWhenReady() throws EventStreamException
    {
        EventSearchResult result1 = result("gone1");
        when(this.events.search(scanQuery(false, null), FIELDS)).thenReturn(result1);
        EventSearchResult result2 = result("gone2");
        when(this.events.search(scanQuery(false, "gone1"), FIELDS)).thenReturn(result2);
        EventSearchResult result3 = result();
        when(this.events.search(scanQuery(false, "gone2"), FIELDS)).thenReturn(result3);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        // One query of constant size per identifier, in the order of the identifiers
        verify(this.departureManager, timeout(TIMEOUT)).watch(List.of("gone1", "gone2"));
        assertEquals("Found events produced by cluster members which are not part of the cluster [gone1, gone2],"
            + " their pending notifications will be taken over if they don't join", this.logCapture.getMessage(0));
    }

    @Test
    void scanWithMaxAgeAndWithoutMails() throws EventStreamException
    {
        when(this.remoteObservationConfiguration.getReclaimMaxAge()).thenReturn(3600L);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(false);
        EventSearchResult emptyResult = result();
        when(this.events.search(any(), any())).thenReturn(emptyResult);

        long before = System.currentTimeMillis();
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        ArgumentCaptor<SimpleEventQuery> queryCaptor = ArgumentCaptor.forClass(SimpleEventQuery.class);
        verify(this.events, timeout(TIMEOUT)).search(queryCaptor.capture(), any());
        long after = System.currentTimeMillis();
        // Only the events waiting to be pre-filtered, produced during the last hour
        CompareQueryCondition prefilteredCondition =
            (CompareQueryCondition) queryCaptor.getValue().getConditions().get(0);
        assertEquals(Event.FIELD_PREFILTERED, prefilteredCondition.getProperty());
        assertEquals(false, prefilteredCondition.getValue());
        CompareQueryCondition dateCondition = (CompareQueryCondition) queryCaptor.getValue().getConditions().get(1);
        assertEquals(Event.FIELD_DATE, dateCondition.getProperty());
        assertEquals(CompareQueryCondition.CompareType.GREATER_OR_EQUALS, dateCondition.getType());
        long oldest = ((Date) dateCondition.getValue()).getTime();
        assertTrue(oldest >= before - TimeUnit.HOURS.toMillis(1) && oldest <= after - TimeUnit.HOURS.toMillis(1));
        verify(this.departureManager, never()).watch(any());
    }

    @Test
    void scanFailure() throws EventStreamException
    {
        EventSearchResult result1 = result("gone1");
        when(this.events.search(scanQuery(false, null), FIELDS)).thenReturn(result1);
        when(this.events.search(scanQuery(false, "gone1"), FIELDS)).thenThrow(new EventStreamException("failed"));

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        // What was found before the failure is watched
        verify(this.departureManager, timeout(TIMEOUT)).watch(List.of("gone1"));
        assertEquals("Failed to search the events produced by the cluster members which are not part of the cluster",
            this.logCapture.getMessage(0));
        assertEquals("Found events produced by cluster members which are not part of the cluster [gone1], their"
            + " pending notifications will be taken over if they don't join", this.logCapture.getMessage(1));
    }

    @Test
    void scanStopsOnKnownMember() throws Exception
    {
        // A store which does not support the query returns the same member again: the scan stops
        EventSearchResult result1 = result("gone1");
        when(this.events.search(scanQuery(false, null), FIELDS)).thenReturn(result1);
        EventSearchResult result2 = result("gone1");
        when(this.events.search(scanQuery(false, "gone1"), FIELDS)).thenReturn(result2);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.departureManager, timeout(TIMEOUT)).watch(List.of("gone1"));
        verify(this.events, times(2)).search(any(), any());
        assertEquals("Found events produced by cluster members which are not part of the cluster [gone1], their"
            + " pending notifications will be taken over if they don't join", this.logCapture.getMessage(0));
    }

    @Test
    void scanOnGain() throws EventStreamException
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        EventSearchResult emptyResult = result();
        when(this.events.search(any(), any())).thenReturn(emptyResult);

        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), null, true);

        verify(this.events, timeout(TIMEOUT)).search(scanQuery(false, null), FIELDS);
    }

    @Test
    void disabled() throws Exception
    {
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(false);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        departure("gone");

        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
        when(this.notificationConfiguration.isEnabled()).thenReturn(false);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        departure("gone");

        when(this.notificationConfiguration.isEnabled()).thenReturn(true);
        when(this.remoteObservationConfiguration.getReclaimDelay()).thenReturn(-1L);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        departure("gone");

        TimeUnit.MILLISECONDS.sleep(200);
        verifyNoInteractions(this.events, this.mailManager, this.dispatcher, this.departureManager);
    }

    @Test
    void departedBeforeReady() throws Exception
    {
        departed("gone", DEPARTURE);

        departure("gone");

        TimeUnit.MILLISECONDS.sleep(200);
        verifyNoInteractions(this.mailManager, this.dispatcher);
    }

    @Test
    void departed() throws Exception
    {
        ready();

        departed("gone", DEPARTURE);
        when(this.remoteObservationConfiguration.getReclaimMaxAge()).thenReturn(3600L);
        Date oldest = new Date(DEPARTURE.getTime() - TimeUnit.HOURS.toMillis(1));
        SimpleEventQuery countQuery = new SimpleEventQuery(0, 0).eq(Event.FIELD_PREFILTERED, false)
            .eq(Event.FIELD_REMOTE_OBSERVATION_ID, "gone").less(Event.FIELD_DATE, oldest);
        EventSearchResult countResult = new StreamEventSearchResult(2, 0, 0, Stream.of());
        when(this.events.search(countQuery)).thenReturn(countResult);
        Job job = job();
        when(this.mailManager.resumeMails(any())).thenReturn(job);

        departure("gone");

        verify(this.scheduler, timeout(TIMEOUT)).flush();
        ArgumentCaptor<MissingLiveNotificationMailsRequest> requestCaptor =
            ArgumentCaptor.forClass(MissingLiveNotificationMailsRequest.class);
        // The mails are resumed before the waiting events are pre-filtered
        InOrder inOrder = inOrder(this.mailManager, job, this.dispatcher, this.scheduler);
        inOrder.verify(this.mailManager).resumeMails(requestCaptor.capture());
        inOrder.verify(job).join(anyLong(), any());
        inOrder.verify(this.dispatcher).addDepartedMember("gone");
        inOrder.verify(this.scheduler).flush();

        MissingLiveNotificationMailsRequest request = requestCaptor.getValue();
        assertNull(request.getWiki());
        assertEquals(List.of("gone"), request.getRemoteObservationIds());
        assertEquals(oldest, request.getEventDateFrom());
        assertEquals(new Date(DEPARTURE.getTime() + TimeUnit.SECONDS.toMillis(DELAY)), request.getEventDateTo());
        assertTrue(request.isReclaim());

        assertEquals("Taking over the pending notifications of the cluster member [gone] gone for good, for the events"
            + " it produced from [" + oldest + "] (it left at [" + DEPARTURE + "])", this.logCapture.getMessage(0));
        assertEquals("[2] waiting events produced by the cluster member [gone] before [" + oldest + "] are not"
            + " pre-filtered: they are older than observation.remote.reclaimMaxAge", this.logCapture.getMessage(1));
    }

    @Test
    void departedTogether() throws Exception
    {
        ready();

        // Wait for the departures notified together
        ReflectionUtils.setFieldValue(this.listener, "takeOverDelay", 500L);
        Date departure2 = new Date(DEPARTURE.getTime() + 1000);
        Date departure3 = new Date(DEPARTURE.getTime() + TimeUnit.SECONDS.toMillis(DELAY) + 1000);
        departed("gone1", DEPARTURE);
        departed("gone2", departure2);
        departed("gone3", departure3);
        Job job = job();
        when(this.mailManager.resumeMails(any())).thenReturn(job);

        departure("gone3");
        departure("gone2");
        departure("gone1");

        verify(this.scheduler, timeout(TIMEOUT)).flush();
        ArgumentCaptor<MissingLiveNotificationMailsRequest> requestCaptor =
            ArgumentCaptor.forClass(MissingLiveNotificationMailsRequest.class);
        verify(this.mailManager, times(2)).resumeMails(requestCaptor.capture());
        // One job for the members which left at about the same time, another one for the other member
        assertEquals(List.of("gone1", "gone2"), requestCaptor.getAllValues().get(0).getRemoteObservationIds());
        assertNull(requestCaptor.getAllValues().get(0).getEventDateFrom());
        assertEquals(new Date(departure2.getTime() + TimeUnit.SECONDS.toMillis(DELAY)),
            requestCaptor.getAllValues().get(0).getEventDateTo());
        assertEquals(List.of("gone3"), requestCaptor.getAllValues().get(1).getRemoteObservationIds());
        verify(this.dispatcher).addDepartedMember("gone1");
        verify(this.dispatcher).addDepartedMember("gone2");
        verify(this.dispatcher).addDepartedMember("gone3");
        assertEquals(takingOver("gone3", departure3), this.logCapture.getMessage(0));
        assertEquals(takingOver("gone2", departure2), this.logCapture.getMessage(1));
        assertEquals(takingOver("gone1", DEPARTURE), this.logCapture.getMessage(2));
    }

    @Test
    void departedTwice() throws Exception
    {
        ready();

        // The same departed member notified twice (for example by two scans which watch it): its mails are resumed
        // once, so that the second job does not queue again the mails queued by the first one or created by the
        // pre-filtering
        departed("gone", DEPARTURE);
        Job job = job();
        when(this.mailManager.resumeMails(any())).thenReturn(job);

        departure("gone");
        verify(this.scheduler, timeout(TIMEOUT)).flush();
        departure("gone");
        TimeUnit.MILLISECONDS.sleep(200);

        verify(this.mailManager).resumeMails(any());
        verify(this.dispatcher).addDepartedMember("gone");
        assertEquals(takingOver("gone", DEPARTURE), this.logCapture.getMessage(0));

        // Taken over again after it came back and left again
        Date departure2 = new Date(DEPARTURE.getTime() + 1000000);
        departed("gone", departure2);
        departure("gone");
        verify(this.scheduler, timeout(TIMEOUT).times(2)).flush();
        verify(this.mailManager, times(2)).resumeMails(any());
        assertEquals(takingOver("gone", departure2), this.logCapture.getMessage(1));

        // Taken over again by a new tenure of the singleton node role, the pre-filtering of the previous one being
        // forgotten
        when(this.singletonManager.isSingletonNode()).thenReturn(false);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), null, false);
        verify(this.dispatcher).clearDepartedMembers();
        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), null, true);
        departure("gone");
        verify(this.scheduler, timeout(TIMEOUT).times(3)).flush();
        verify(this.mailManager, times(3)).resumeMails(any());
        assertEquals(takingOver("gone", departure2), this.logCapture.getMessage(2));
    }

    @Test
    void departedWithoutMails() throws Exception
    {
        ready();

        departed("gone", DEPARTURE);
        when(this.notificationConfiguration.areEmailsEnabled()).thenReturn(false);

        departure("gone");

        verify(this.scheduler, timeout(TIMEOUT)).flush();
        verify(this.mailManager, never()).resumeMails(any());
        verify(this.dispatcher).addDepartedMember("gone");
        // No maximum age: no old events
        verify(this.events, never()).search(any());
        assertEquals(takingOver("gone", DEPARTURE), this.logCapture.getMessage(0));
    }

    @Test
    void departedWhenMailJobFailsToStartAndCountFails() throws Exception
    {
        ready();

        departed("gone", DEPARTURE);
        when(this.remoteObservationConfiguration.getReclaimMaxAge()).thenReturn(3600L);
        when(this.events.search(any())).thenThrow(new EventStreamException("failed"));

        departure("gone");

        verify(this.scheduler, timeout(TIMEOUT)).flush();
        verify(this.mailManager).resumeMails(any());
        verify(this.dispatcher).addDepartedMember("gone");
        Date oldest = new Date(DEPARTURE.getTime() - TimeUnit.HOURS.toMillis(1));
        assertEquals("Taking over the pending notifications of the cluster member [gone] gone for good, for the events"
            + " it produced from [" + oldest + "] (it left at [" + DEPARTURE + "])", this.logCapture.getMessage(0));
        assertEquals("Failed to count the old events produced by the cluster member [gone]: [EventStreamException:"
            + " failed]", this.logCapture.getMessage(1));
    }

    @Test
    void departedWhenMailJobIsLong() throws Exception
    {
        ready();

        departed("gone", DEPARTURE);
        Job job = mock(Job.class);
        when(job.join(anyLong(), any())).thenReturn(false);
        when(this.mailManager.resumeMails(any())).thenReturn(job);

        departure("gone");

        verify(this.scheduler, timeout(TIMEOUT)).flush();
        // The pre-filtering waits for the end of the job
        InOrder inOrder = inOrder(job, this.dispatcher);
        inOrder.verify(job).join();
        inOrder.verify(this.dispatcher).addDepartedMember("gone");
        assertEquals(takingOver("gone", DEPARTURE), this.logCapture.getMessage(0));
        assertEquals("The resume of the pending live notification mails of the cluster members [gone] gone for good is"
            + " still running after [600] seconds, their waiting events will be pre-filtered once it's finished",
            this.logCapture.getMessage(1));
    }

    @Test
    void departedAndStopping() throws Exception
    {
        ready();

        departed("gone", DEPARTURE);
        Job job = mock(Job.class);
        CountDownLatch joined = new CountDownLatch(1);
        doAnswer(invocation -> {
            joined.countDown();
            throw new InterruptedException();
        }).when(job).join(anyLong(), any());
        when(this.mailManager.resumeMails(any())).thenReturn(job);

        departure("gone");

        assertTrue(joined.await(TIMEOUT, TimeUnit.MILLISECONDS));
        TimeUnit.MILLISECONDS.sleep(200);
        verify(this.dispatcher, never()).addDepartedMember(any());
        verify(this.scheduler, never()).flush();
        assertEquals(takingOver("gone", DEPARTURE), this.logCapture.getMessage(0));
    }

    @Test
    void singletonNodeLostDuringTakeOver() throws Exception
    {
        ready();

        departed("gone", DEPARTURE);
        Job job = mock(Job.class);
        doAnswer(invocation -> {
            // The current instance stops being the singleton node while the job runs
            when(this.singletonManager.isSingletonNode()).thenReturn(false);
            this.listener.onEvent(new ClusterSingletonNodeChangedEvent(), null, false);
            return true;
        }).when(job).join(anyLong(), any());
        when(this.mailManager.resumeMails(any())).thenReturn(job);

        departure("gone");

        verify(this.scheduler, timeout(TIMEOUT)).flush();
        verify(this.dispatcher).clearDepartedMembers();
        verify(this.dispatcher, never()).addDepartedMember(any());
        assertEquals(takingOver("gone", DEPARTURE), this.logCapture.getMessage(0));
    }

    @Test
    void departedMemberCameBack() throws Exception
    {
        ready();

        // The member came back before the take-over
        when(this.departureManager.isDeparted("gone")).thenReturn(false);
        when(this.departureManager.getDepartureDate("gone")).thenReturn(null);

        departure("gone");

        verify(this.departureManager, timeout(TIMEOUT)).getDepartureDate("gone");
        TimeUnit.MILLISECONDS.sleep(200);
        verifyNoInteractions(this.mailManager, this.dispatcher);
        verify(this.scheduler, never()).flush();
    }

    @Test
    void departedWhenNotSingletonNode() throws Exception
    {
        when(this.singletonManager.isSingletonNode()).thenReturn(false);
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);
        departed("gone", DEPARTURE);

        departure("gone");

        TimeUnit.MILLISECONDS.sleep(200);
        verifyNoInteractions(this.mailManager, this.dispatcher, this.events);
    }

    @Test
    void notSingletonNodeAnymoreWhenTakingOver() throws Exception
    {
        ready();
        departed("gone", DEPARTURE);

        // The departure is received while the instance is the singleton node, the take-over runs after the loss
        ReflectionUtils.setFieldValue(this.listener, "takeOverDelay", 200L);
        departure("gone");
        when(this.singletonManager.isSingletonNode()).thenReturn(false);

        TimeUnit.MILLISECONDS.sleep(500);
        verifyNoInteractions(this.mailManager);
        verify(this.dispatcher, never()).addDepartedMember(any());
    }

    @Test
    void stopping() throws Exception
    {
        this.listener.dispose();

        // Ignored after dispose
        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        TimeUnit.MILLISECONDS.sleep(200);
        verifyNoInteractions(this.events);
    }
}
