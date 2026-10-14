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
package org.xwiki.notifications.notifiers.internal.email.live;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventStore;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.internal.DefaultEvent;
import org.xwiki.eventstream.internal.StreamEventSearchResult;
import org.xwiki.eventstream.query.SimpleEventQuery;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.notifications.notifiers.internal.email.IntervalUsersManager;
import org.xwiki.notifications.preferences.NotificationEmailInterval;
import org.xwiki.query.QueryException;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link MissingLiveNotificationMailsJob}.
 * 
 * @version $Id$
 */
@ComponentTest
class MissingLiveNotificationMailsJobTest
{
    @InjectMockComponents
    private MissingLiveNotificationMailsJob job;

    @MockComponent
    private PrefilteringLiveNotificationEmailDispatcher dispatcher;

    @MockComponent
    private EventStore eventStore;

    @MockComponent
    private EntityReferenceSerializer<String> referenceSerializer;

    @MockComponent
    private IntervalUsersManager intervals;

    @MockComponent
    private WikiDescriptorManager wikis;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    private void runJob(MissingLiveNotificationMailsRequest request)
    {
        this.job.initialize(request);
        this.job.run();
    }

    private Event event(String id)
    {
        Event event = new DefaultEvent();
        event.setId(id);

        return event;
    }

    @Test
    void run() throws QueryException, EventStreamException
    {
        DocumentReference user1 = new DocumentReference("wiki", "space", "user1");
        DocumentReference user2 = new DocumentReference("wiki", "space", "user2");

        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki")).thenReturn(Arrays.asList(user1, user2));

        when(this.referenceSerializer.serialize(user1)).thenReturn("wiki:space:user1");
        when(this.referenceSerializer.serialize(user2)).thenReturn("wiki:space:user2");

        Event eventa = new DefaultEvent();
        eventa.setId("a");
        Event eventb = new DefaultEvent();
        eventb.setId("b");
        Event eventc = new DefaultEvent();
        eventc.setId("c");

        SimpleEventQuery query1 = new SimpleEventQuery();
        query1.withMail("wiki:space:user1");
        when(this.eventStore.search(query1))
            .thenReturn(new StreamEventSearchResult(2, 0, 2, Arrays.asList(eventa, eventb).stream()));

        SimpleEventQuery query2 = new SimpleEventQuery();
        query2.withMail("wiki:space:user2");
        when(this.eventStore.search(query2))
            .thenReturn(new StreamEventSearchResult(2, 0, 2, Arrays.asList(eventa, eventc).stream()));

        MissingLiveNotificationMailsRequest request = new MissingLiveNotificationMailsRequest("wiki");
        request.setVerbose(false);

        this.job.initialize(request);
        this.job.run();

        verify(this.dispatcher, times(4)).addEvent(any(), any());
        verify(this.dispatcher).addEvent(eventa, user1);
        verify(this.dispatcher).addEvent(eventb, user1);
        verify(this.dispatcher).addEvent(eventa, user2);
        verify(this.dispatcher).addEvent(eventc, user2);
    }

    @Test
    void runWithCurrentInstance() throws QueryException, EventStreamException
    {
        DocumentReference user = new DocumentReference("wiki", "space", "user");
        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki")).thenReturn(List.of(user));
        when(this.referenceSerializer.serialize(user)).thenReturn("wiki:space:user");

        Event event = event("a");
        SimpleEventQuery query = new SimpleEventQuery();
        query.withMail("wiki:space:user");
        query.open();
        query.eq(Event.FIELD_REMOTE_OBSERVATION_ID, "instance1");
        query.close();
        when(this.eventStore.search(query)).thenReturn(new StreamEventSearchResult(1, 0, 1, Stream.of(event)));

        runJob(new MissingLiveNotificationMailsRequest("wiki", List.of("instance1")));

        verify(this.dispatcher).addEvent(event, user);
        verify(this.dispatcher, times(1)).addEvent(any(), any());
    }

    @Test
    void runWithSeveralInstancesInAllWikis() throws QueryException, EventStreamException, WikiManagerException
    {
        when(this.wikis.getAllIds()).thenReturn(List.of("wiki1", "wiki2", "wiki3"));

        DocumentReference user1 = new DocumentReference("wiki1", "space", "user1");
        DocumentReference user3 = new DocumentReference("wiki3", "space", "user3");
        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki1")).thenReturn(List.of(user1));
        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki2"))
            .thenThrow(new QueryException("failed", null, null));
        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki3")).thenReturn(List.of(user3));
        when(this.referenceSerializer.serialize(user1)).thenReturn("wiki1:space:user1");
        when(this.referenceSerializer.serialize(user3)).thenReturn("wiki3:space:user3");

        Event eventa = event("a");
        Event eventb = event("b");
        SimpleEventQuery query1 = new SimpleEventQuery();
        query1.withMail("wiki1:space:user1");
        query1.open();
        query1.eq(Event.FIELD_REMOTE_OBSERVATION_ID, "instance1");
        query1.or();
        query1.eq(Event.FIELD_REMOTE_OBSERVATION_ID, null);
        query1.close();
        when(this.eventStore.search(query1)).thenReturn(new StreamEventSearchResult(1, 0, 1, Stream.of(eventa)));
        SimpleEventQuery query3 = new SimpleEventQuery();
        query3.withMail("wiki3:space:user3");
        query3.open();
        query3.eq(Event.FIELD_REMOTE_OBSERVATION_ID, "instance1");
        query3.or();
        query3.eq(Event.FIELD_REMOTE_OBSERVATION_ID, null);
        query3.close();
        when(this.eventStore.search(query3)).thenReturn(new StreamEventSearchResult(1, 0, 1, Stream.of(eventb)));

        runJob(new MissingLiveNotificationMailsRequest(null, Arrays.asList("instance1", null)));

        verify(this.dispatcher).addEvent(eventa, user1);
        verify(this.dispatcher).addEvent(eventb, user3);
        verify(this.dispatcher, times(2)).addEvent(any(), any());
        assertEquals("Failed to resume the live notification mails of the users of wiki [wiki2]",
            this.logCapture.getMessage(0));
    }

    @Test
    void runWithoutWikis() throws WikiManagerException
    {
        when(this.wikis.getAllIds()).thenThrow(new WikiManagerException("failed"));

        runJob(new MissingLiveNotificationMailsRequest(null, Collections.singletonList(null)));

        verify(this.dispatcher, never()).addEvent(any(), any());
        assertEquals("Failed to get the wikis in which to resume the live notification mails",
            this.logCapture.getMessage(0));
    }

    @Test
    void runWithEmptyInstances() throws QueryException
    {
        runJob(new MissingLiveNotificationMailsRequest("wiki", List.of()));

        verify(this.intervals, never()).getUsers(any(), any());
    }

    @Test
    void runWithSearchFailure() throws QueryException, EventStreamException
    {
        DocumentReference user = new DocumentReference("wiki", "space", "user");
        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki")).thenReturn(List.of(user));
        when(this.referenceSerializer.serialize(user)).thenReturn("wiki:space:user");
        when(this.eventStore.search(any())).thenThrow(new EventStreamException("failed"));

        runJob(new MissingLiveNotificationMailsRequest("wiki"));

        verify(this.dispatcher, never()).addEvent(any(), any());
        assertEquals("Failed to get events to send by mail to the user [wiki:space.user]",
            this.logCapture.getMessage(0));
    }

    @Test
    void request()
    {
        MissingLiveNotificationMailsRequest request = new MissingLiveNotificationMailsRequest("wiki", List.of("id"));
        assertEquals("wiki", request.getWiki());
        assertEquals(List.of("id"), request.getRemoteObservationIds());

        request.setRemoteObservationIds(null);
        assertNull(request.getRemoteObservationIds());

        MissingLiveNotificationMailsRequest copy =
            new MissingLiveNotificationMailsRequest(new MissingLiveNotificationMailsRequest(null, List.of("id")));
        assertNull(copy.getWiki());
        assertEquals(List.of("id"), copy.getRemoteObservationIds());
    }

    @Test
    void runWithRuntimeExceptionInOneWiki() throws QueryException, WikiManagerException
    {
        when(this.wikis.getAllIds()).thenReturn(List.of("wiki1", "wiki2", "wiki3"));
        when(this.intervals.getUsers(NotificationEmailInterval.LIVE, "wiki2")).thenThrow(new ClassCastException());

        runJob(new MissingLiveNotificationMailsRequest(null, List.of("instance1")));

        // The failure of one wiki does not prevent resuming the next ones
        verify(this.intervals).getUsers(NotificationEmailInterval.LIVE, "wiki1");
        verify(this.intervals).getUsers(NotificationEmailInterval.LIVE, "wiki3");
        assertEquals("Failed to resume the live notification mails of the users of wiki [wiki2]",
            this.logCapture.getMessage(0));
    }
}
