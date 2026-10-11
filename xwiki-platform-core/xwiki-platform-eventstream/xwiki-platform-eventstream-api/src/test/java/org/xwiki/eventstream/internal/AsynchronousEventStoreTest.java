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
package org.xwiki.eventstream.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.context.concurrent.ContextStoreManager;
import org.xwiki.eventstream.EntityEvent;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventQuery;
import org.xwiki.eventstream.EventSearchResult;
import org.xwiki.eventstream.EventStatus;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.events.AbstractEventStreamEvent;
import org.xwiki.eventstream.events.EventStreamAddedEvent;
import org.xwiki.eventstream.events.MailEntityAddedEvent;
import org.xwiki.eventstream.events.MailEntityDeleteEvent;
import org.xwiki.observation.ObservationManager;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

/**
 * Validate {@link AbstractAsynchronousEventStore}.
 *
 * @version $Id$
 */
@ComponentTest
class AsynchronousEventStoreTest
{
    public static class TestAbstractAsynchronousEventStore extends AbstractAsynchronousEventStore
    {
        class EventEntry
        {
            DefaultEvent event;

            Map<String, EventStatus> statuses = new ConcurrentHashMap<>();

            Map<String, EntityEvent> mailstatuses = new ConcurrentHashMap<>();
        }

        Map<String, EventEntry> events = new ConcurrentHashMap<>();

        EventEntry getEventEntry(String eventId, boolean create)
        {
            EventEntry entry = this.events.get(eventId);

            if (entry == null && create) {
                entry = new EventEntry();
                this.events.put(eventId, entry);
            }

            return entry;
        }

        ReentrantLock lock = new ReentrantLock();

        @Override
        public Optional<Event> getEvent(String eventId) throws EventStreamException
        {
            EventEntry entry = getEventEntry(eventId, false);

            return entry != null ? Optional.ofNullable(entry.event) : Optional.empty();
        }

        @Override
        public EventSearchResult search(EventQuery query) throws EventStreamException
        {
            // Not needed for the test
            return null;
        }

        @Override
        public EventSearchResult search(EventQuery query, Set<String> fields) throws EventStreamException
        {
            // Not needed for the test
            return null;
        }

        @Override
        public List<EventStatus> getEventStatuses(Collection<Event> events, Collection<String> entityIds)
        {
            return List.of();
        }

        @Override
        public void initialize() throws InitializationException
        {
            initialize(10, true, false);
        }

        @Override
        protected EventStatus syncSaveEventStatus(EventStatus status)
        {
            this.lock.lock();

            try {
                getEventEntry(status.getEvent().getId(), true).statuses.put(status.getEntityId(), status);

                return status;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected EntityEvent syncSaveMailEntityEvent(EntityEvent event)
        {
            this.lock.lock();

            try {
                getEventEntry(event.getEvent().getId(), true).mailstatuses.put(event.getEntityId(), event);

                return event;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Event syncSaveEvent(Event event)
        {
            this.lock.lock();

            try {
                getEventEntry(event.getId(), true).event = (DefaultEvent) event;

                return event;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Event syncPrefilterEvent(Event event)
        {
            this.lock.lock();

            try {
                DefaultEvent storeEvent = getEventEntry(event.getId(), true).event;

                storeEvent.setPrefiltered(true);

                return storeEvent;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Optional<EventStatus> syncDeleteEventStatus(EventStatus status)
        {
            this.lock.lock();

            try {
                EventEntry entry = getEventEntry(status.getEvent().getId(), false);

                if (entry != null) {
                    return Optional.ofNullable(entry.statuses.remove(status.getEntityId()));
                }

                return Optional.empty();
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Void syncDeleteEventStatuses(String entityId, Date date)
        {
            this.lock.lock();

            try {
                this.events.forEach((eventId, entry) -> {
                    if (entry.event.getDate() != null && !entry.event.getDate().after(date)) {
                        entry.statuses.remove(entityId);
                    }
                });

                return null;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Optional<EntityEvent> syncDeleteMailEntityEvent(EntityEvent event)
        {
            this.lock.lock();

            try {
                EventEntry entry = getEventEntry(event.getEvent().getId(), false);

                if (entry != null) {
                    return Optional.ofNullable(entry.mailstatuses.remove(event.getEntityId()));
                }

                return Optional.empty();
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Optional<Event> syncDeleteEvent(String eventId) throws EventStreamException
        {
            this.lock.lock();

            try {
                Optional<Event> event = getEvent(eventId);

                this.events.remove(eventId);

                return event;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        protected Optional<Event> syncDeleteEvent(Event event) throws EventStreamException
        {
            this.lock.lock();

            try {
                return syncDeleteEvent(event.getId());
            } finally {
                this.lock.unlock();
            }
        }
    }

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @InjectMockComponents
    private TestAbstractAsynchronousEventStore store;

    @MockComponent
    private ObservationManager observation;

    @MockComponent
    private ContextStoreManager contextStore;

    private DefaultEvent event(String id)
    {
        DefaultEvent event = new DefaultEvent();

        event.setId(id);

        return event;
    }

    private DefaultEventStatus eventStatus(DefaultEvent event, String entityId, boolean read)
    {
        return new DefaultEventStatus(event, entityId, read);
    }

    private DefaultEntityEvent entityEvent(DefaultEvent event, String entityId)
    {
        return new DefaultEntityEvent(event, entityId);
    }

    // Tests

    @Test
    void getQueueSize() throws InterruptedException
    {
        assertEquals(0, this.store.getQueueSize());

        // Lock, add an event and wait for the lock to be in place in the store thread
        this.store.lock.lock();

        try {
            this.store.saveEvent(event(""));
            Thread.sleep(10);
            assertEquals(0, this.store.getQueueSize());

            this.store.saveEvent(event("id1"));

            assertEquals(1, this.store.getQueueSize());

            this.store.saveEvent(event("id2"));

            assertEquals(2, this.store.getQueueSize());

            this.store.deleteEvent(event("id3"));

            assertEquals(1, this.store.getQueueSize());

            this.store.deleteEvent("id4");

            assertEquals(0, this.store.getQueueSize());
        } finally {
            this.store.lock.unlock();
        }
    }

    @Test
    void event() throws InterruptedException, ExecutionException, EventStreamException
    {
        DefaultEvent event1 = event("id1");
        DefaultEvent event2 = event("id2");

        this.store.saveEvent(event1);
        // Only wait for the completion of the last order since all the other are supposed to be completed before
        this.store.saveEvent(event2).get();

        assertSame(event1, this.store.getEvent(event1.getId()).get());
        assertSame(event2, this.store.getEvent(event2.getId()).get());

        this.store.deleteEvent(event1).get();

        assertFalse(this.store.getEvent(event1.getId()).isPresent());
        assertSame(event2, this.store.getEvent(event2.getId()).get());

        this.store.deleteEvent(event2.getId()).get();

        assertFalse(this.store.getEvent(event1.getId()).isPresent());
        assertFalse(this.store.getEvent(event2.getId()).isPresent());
    }

    @Test
    void eventstatus() throws InterruptedException, ExecutionException
    {
        DefaultEvent event1 = event("id1");
        DefaultEvent event2 = event("id2");
        DefaultEventStatus status11 = eventStatus(event1, "entity1", true);
        DefaultEventStatus status12 = eventStatus(event1, "entity2", true);
        DefaultEventStatus status13 = eventStatus(event1, "entity3", true);
        DefaultEventStatus status21 = eventStatus(event2, "entity1", true);
        DefaultEventStatus status22 = eventStatus(event2, "entity2", true);
        DefaultEventStatus status23 = eventStatus(event2, "entity3", true);

        this.store.saveEvent(event1);
        this.store.saveEvent(event2);
        this.store.saveEventStatus(status11);
        this.store.saveEventStatus(status12);
        this.store.saveEventStatus(status13);
        this.store.saveEventStatus(status21);
        this.store.saveEventStatus(status22);
        // Only wait for the completion of the last order since all the other are supposed to be completed before
        this.store.saveEventStatus(status23).get();

        assertSame(status11, this.store.events.get(event1.getId()).statuses.get(status11.getEntityId()));
        assertSame(status12, this.store.events.get(event1.getId()).statuses.get(status12.getEntityId()));
        assertSame(status13, this.store.events.get(event1.getId()).statuses.get(status13.getEntityId()));
        assertSame(status21, this.store.events.get(event2.getId()).statuses.get(status21.getEntityId()));
        assertSame(status22, this.store.events.get(event2.getId()).statuses.get(status22.getEntityId()));
        assertSame(status23, this.store.events.get(event2.getId()).statuses.get(status23.getEntityId()));

        this.store.deleteEventStatus(status11).get();

        assertNull(this.store.events.get(event1.getId()).statuses.get(status11.getEntityId()));
        assertSame(status12, this.store.events.get(event1.getId()).statuses.get(status12.getEntityId()));
        assertSame(status13, this.store.events.get(event1.getId()).statuses.get(status13.getEntityId()));
        assertSame(status21, this.store.events.get(event2.getId()).statuses.get(status21.getEntityId()));
        assertSame(status22, this.store.events.get(event2.getId()).statuses.get(status22.getEntityId()));
        assertSame(status23, this.store.events.get(event2.getId()).statuses.get(status23.getEntityId()));
    }

    @Test
    void mailentity() throws InterruptedException, ExecutionException
    {
        DefaultEvent event1 = event("id1");
        DefaultEvent event2 = event("id2");
        DefaultEntityEvent status11 = entityEvent(event1, "entity1");
        DefaultEntityEvent status13 = entityEvent(event1, "entity3");
        DefaultEntityEvent status21 = entityEvent(event2, "entity1");
        DefaultEntityEvent status24 = entityEvent(event2, "entity4");

        CompletableFuture<Object> waitForObservationOnStatus24 =
            createCompletableFutureForStatus(status24, MailEntityAddedEvent.class);
        CompletableFuture<Object> waitForObservationOnStatus11 =
            createCompletableFutureForStatus(status11, MailEntityDeleteEvent.class);

        this.store.saveEvent(event1);
        this.store.saveEvent(event2);
        this.store.saveMailEntityEvent(status11);
        this.store.saveMailEntityEvent(status13);
        this.store.saveMailEntityEvent(status21);
        this.store.saveMailEntityEvent(status24).get();

        // Wait for the observation to be called on the last store call. The observation component is intentionally 
        // called after the completable future returned by saveMailEntityEvent and saveEvent. Therefore, we need to 
        // mock and wait explicitly for the test. 
        waitForObservationOnStatus24.get();

        assertSame(status11, this.store.events.get(event1.getId()).mailstatuses.get(status11.getEntityId()));
        assertSame(status13, this.store.events.get(event1.getId()).mailstatuses.get(status13.getEntityId()));
        assertSame(status21, this.store.events.get(event2.getId()).mailstatuses.get(status21.getEntityId()));
        assertSame(status24, this.store.events.get(event2.getId()).mailstatuses.get(status24.getEntityId()));

        verify(this.observation).notify(any(EventStreamAddedEvent.class), eq(event1));
        verify(this.observation).notify(any(EventStreamAddedEvent.class), eq(event2));
        verify(this.observation).notify(any(MailEntityAddedEvent.class), eq(status11));
        verify(this.observation).notify(any(MailEntityAddedEvent.class), eq(status13));
        verify(this.observation).notify(any(MailEntityAddedEvent.class), eq(status21));
        verify(this.observation).notify(any(MailEntityAddedEvent.class), eq(status24));

        this.store.deleteMailEntityEvent(status11).get();

        // Wait for the observation to be called after deleteMailEntityEvent. The observation component is intentionally 
        // called after the completable future returned by deleteMailEntityEventt. Therefore, we need to mock and wait
        // explicitly for the test.
        waitForObservationOnStatus11.get();

        assertNull(this.store.events.get(event1.getId()).mailstatuses.get(status11.getEntityId()));
        assertSame(status13, this.store.events.get(event1.getId()).mailstatuses.get(status13.getEntityId()));
        assertSame(status21, this.store.events.get(event2.getId()).mailstatuses.get(status21.getEntityId()));
        assertSame(status24, this.store.events.get(event2.getId()).mailstatuses.get(status24.getEntityId()));

        verify(this.observation).notify(any(MailEntityDeleteEvent.class), eq(status11));
    }

    @Test
    void prefilter() throws InterruptedException, ExecutionException, EventStreamException
    {
        DefaultEvent event1 = event("id1");
        DefaultEvent event2 = event("id2");

        this.store.saveEvent(event1);
        this.store.saveEvent(event2).get();

        assertFalse(this.store.getEvent(event1.getId()).get().isPrefiltered());
        assertFalse(this.store.getEvent(event2.getId()).get().isPrefiltered());

        this.store.prefilterEvent(event1).get();

        assertTrue(this.store.getEvent(event1.getId()).get().isPrefiltered());
        assertFalse(this.store.getEvent(event2.getId()).get().isPrefiltered());
    }

    @Test
    void disposeProcessesQueuedTasks() throws Exception
    {
        List<CompletableFuture<Event>> futures = new ArrayList<>();

        // Block the store thread on the first task and fill the queue (capacity 10) behind it, so that the queued tasks
        // don't fit in a single batch
        this.store.lock.lock();
        try {
            futures.add(this.store.saveEvent(event("id0")));
            await(this.store.lock::hasQueuedThreads);
            for (int i = 1; i <= 10; ++i) {
                futures.add(this.store.saveEvent(event("id" + i)));
            }

            // Dispose the store while the queue is full
            Thread disposeThread = new Thread(() -> {
                try {
                    this.store.dispose();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            disposeThread.start();

            // Wait for the dispose to wait for room in the queue for the stop order
            await(() -> disposeThread.getState() == Thread.State.TIMED_WAITING);

            // New tasks are refused as soon as the store is being disposed
            assertTrue(this.store.saveEvent(event("refused")).isCompletedExceptionally());

            this.store.lock.unlock();

            disposeThread.join(10000);
            assertFalse(disposeThread.isAlive());
        } finally {
            if (this.store.lock.isHeldByCurrentThread()) {
                this.store.lock.unlock();
            }
        }

        // All the tasks accepted before the dispose have been processed
        for (int i = 0; i <= 10; ++i) {
            assertEquals("id" + i, futures.get(i).get(0, TimeUnit.SECONDS).getId());
            assertTrue(this.store.getEvent("id" + i).isPresent());
        }
        assertFalse(this.store.getEvent("refused").isPresent());
    }

    @Test
    void disposeInterruptedWhileTheStoreIsStuck() throws Exception
    {
        List<CompletableFuture<Event>> futures = new ArrayList<>();

        // Block the store thread on the first task and fill the queue behind it
        this.store.lock.lock();
        try {
            futures.add(this.store.saveEvent(event("id0")));
            await(this.store.lock::hasQueuedThreads);
            for (int i = 1; i <= 10; ++i) {
                futures.add(this.store.saveEvent(event("id" + i)));
            }

            Thread disposeThread = new Thread(() -> {
                try {
                    this.store.dispose();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            disposeThread.start();
            await(() -> disposeThread.getState() == Thread.State.TIMED_WAITING);

            // Stop waiting for the stuck store thread
            disposeThread.interrupt();
            disposeThread.join(10000);
            assertFalse(disposeThread.isAlive());

            // The tasks still in the queue have been failed
            for (int i = 1; i <= 10; ++i) {
                ExecutionException exception = assertThrows(ExecutionException.class, futures.get(i)::get);
                assertInstanceOf(EventStreamException.class, exception.getCause());
            }
        } finally {
            this.store.lock.unlock();
        }

        // The task being processed when the store was disposed is finished
        assertEquals("id0", futures.get(0).get().getId());

        String storeThread = "The thread handling asynchronous storage for event store [";
        assertTrue(this.logCapture.getMessage(0).startsWith("Interrupted while waiting for the event store ["));
        assertTrue(this.logCapture.getMessage(0).endsWith("] to process its queued tasks: [InterruptedException: ]"));
        assertTrue(this.logCapture.getMessage(1).startsWith(storeThread));
        assertTrue(
            this.logCapture.getMessage(1).endsWith("] did not finish processing the queued tasks in [10] seconds"));
        assertTrue(this.logCapture.getMessage(2).startsWith("[10] tasks were not processed before the event store ["));
    }

    @Test
    void saveEventWhileDisposing() throws Exception
    {
        // Block a producer after it checked that the store is not disposed, but before its task is queued
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(this.contextStore.save(any())).thenAnswer(invocation -> {
            saving.countDown();
            release.await();
            return null;
        });

        CompletableFuture<CompletableFuture<Event>> producer =
            CompletableFuture.supplyAsync(() -> this.store.saveEvent(event("late")));
        assertTrue(saving.await(10, TimeUnit.SECONDS));

        // The store is disposed (and its thread stopped) before the task is queued
        this.store.dispose();
        release.countDown();

        // The task is not left pending
        CompletableFuture<Event> future = producer.get(10, TimeUnit.SECONDS);
        ExecutionException exception =
            assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(EventStreamException.class, exception.getCause());
        assertFalse(this.store.getEvent("late").isPresent());
    }

    @Test
    void saveEventAfterDispose() throws Exception
    {
        this.store.saveEvent(event("id1")).get();

        this.store.dispose();

        CompletableFuture<Event> future = this.store.saveEvent(event("id2"));

        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(EventStreamException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().endsWith("is disposed"));
        assertTrue(this.store.getEvent("id1").isPresent());
        assertFalse(this.store.getEvent("id2").isPresent());
        assertEquals(0, this.store.getQueueSize());
    }

    /**
     * Wait for a condition, failing the test if it's not met in 10 seconds.
     *
     * @param condition the condition to wait for
     */
    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("The condition was not met in 10 seconds");
            }
            Thread.sleep(1);
        }
    }

    /**
     * Observe for a call to notify on {@link #observation} for a given event and type. Complete the returned
     * {@link CompletableFuture} as soon as notify is called. This allows for tests to wait for notify to be called even
     * if not anticipated in the tested code.
     *
     * @param event the event to wait for
     * @param type the type of the event
     * @return a completable future to wait for before calling assertions
     */
    private CompletableFuture<Object> createCompletableFutureForStatus(DefaultEntityEvent event,
        Class<? extends AbstractEventStreamEvent> type)
    {
        CompletableFuture<Object> completable = new CompletableFuture<>();
        doAnswer(invocationOnMock -> {
            completable.complete(true);
            return null;
        }).when(this.observation).notify(any(type), eq(event));
        return completable;
    }
}
