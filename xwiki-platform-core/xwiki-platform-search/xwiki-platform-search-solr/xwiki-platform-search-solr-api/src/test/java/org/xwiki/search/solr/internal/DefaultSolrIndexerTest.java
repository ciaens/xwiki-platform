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
package org.xwiki.search.solr.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.inject.Provider;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.xwiki.bridge.internal.DocumentContextExecutor;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.model.internal.reference.EntityReferenceFactory;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.search.solr.internal.api.SolrInstance;
import org.xwiki.search.solr.internal.metadata.SolrMetadataExtractor;
import org.xwiki.search.solr.internal.metadata.XWikiSolrInputDocument;
import org.xwiki.store.ReadyIndicator;
import org.xwiki.store.StoreStoppedException;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.web.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultSolrIndexer}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultSolrIndexerTest
{
    private static final long TEST_TIMEOUT = 10;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @MockComponent
    private SolrConfiguration configuration;

    // Needed by the resolve and index threads to initialize their execution context.
    @MockComponent
    private Execution execution;

    @MockComponent
    private ExecutionContextManager executionContextManager;

    @MockComponent
    private EntityReferenceFactory entityReferenceFactory;

    @MockComponent
    private Provider<XWikiContext> xcontextProvider;

    @MockComponent
    private DocumentContextExecutor documentContextExecutor;

    @MockComponent
    private SolrInstance solrInstance;

    @InjectMockComponents
    private DefaultSolrIndexer indexer;

    /**
     * Replaces the logger of the indexer once it's initialized (a log capture would also catch the debug messages
     * logged by the indexer threads when they start).
     */
    private final Logger logger = mock();

    private SolrMetadataExtractor documentExtractor;

    /**
     * The documents sent to the Solr client, in order.
     */
    private final List<Object> sentDocuments = new ArrayList<>();

    /**
     * Released to let the blocked metadata extraction or Solr client call continue.
     */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeComponent
    void beforeComponent() throws Exception
    {
        when(this.configuration.getIndexerQueueCapacity()).thenReturn(10);
        when(this.configuration.getIndexerBatchSize()).thenReturn(100);
        when(this.configuration.getIndexerBatchMaxLengh()).thenReturn(1000000);

        // The resolve and index threads get their execution context components through the static component manager.
        Utils.setComponentManager(this.componentManager);

        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(any(EntityReference.class), any(XWikiContext.class)))
            .thenReturn(mock(XWikiDocument.class));
        when(this.xcontextProvider.get()).thenReturn(xcontext);
        doAnswer(invocation -> {
            invocation.<ExecutionContext>getArgument(0).setProperty(XWikiContext.EXECUTIONCONTEXT_KEY, xcontext);
            return null;
        }).when(this.executionContextManager).initialize(any(ExecutionContext.class));

        when(this.entityReferenceFactory.getReference(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.documentContextExecutor.call(any(), any()))
            .thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());

        this.documentExtractor = this.componentManager.registerMockComponent(SolrMetadataExtractor.class, "document");
        when(this.documentExtractor.getSolrDocument(any())).thenAnswer(invocation -> {
            XWikiSolrInputDocument document = new XWikiSolrInputDocument();
            document.setField("id", invocation.getArgument(0).toString());
            return document;
        });

        doAnswer(invocation -> {
            List<SolrInputDocument> documents = invocation.getArgument(0);
            synchronized (this.sentDocuments) {
                documents.forEach(document -> this.sentDocuments.add(document.getFieldValue("id")));
            }
            return null;
        }).when(this.solrInstance).add(anyList());
    }

    @BeforeEach
    void beforeEach() throws Exception
    {
        FieldUtils.writeField(this.indexer, "logger", this.logger, true);
        when(this.configuration.getIndexerShutdownTimeout()).thenReturn(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
    }

    @AfterEach
    void afterEach()
    {
        // Never leave a thread blocked.
        this.release.countDown();
    }

    /**
     * @return the warnings and errors logged by the indexer, formatted
     */
    private List<String> loggedMessages()
    {
        return mockingDetails(this.logger).getInvocations().stream()
            .filter(invocation -> List.of("warn", "error").contains(invocation.getMethod().getName()))
            .map(invocation -> {
                Object[] arguments = invocation.getArguments();
                return MessageFormatter
                    .arrayFormat((String) arguments[0], Arrays.copyOfRange(arguments, 1, arguments.length))
                    .getMessage();
            }).toList();
    }

    private static DocumentReference documentReference(String name)
    {
        return new DocumentReference("wiki", "Space", name);
    }

    private CountDownLatch blockDocumentExtraction(DocumentReference reference) throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            this.release.await();
            XWikiSolrInputDocument document = new XWikiSolrInputDocument();
            document.setField("id", reference.toString());
            return document;
        }).when(this.documentExtractor).getSolrDocument(reference);
        return entered;
    }

    private static void awaitState(Thread thread, Thread.State state) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_TIMEOUT);
        while (thread.getState() != state) {
            if (System.nanoTime() > deadline) {
                fail("Thread [" + thread.getName() + "] is not waiting but [" + thread.getState() + "]");
            }
            Thread.sleep(1);
        }
    }

    /**
     * Wait for the resolve thread to move everything to the index queue.
     */
    private void awaitResolverIdle() throws Exception
    {
        Thread resolveThread = (Thread) FieldUtils.readField(this.indexer, "resolveThread", true);
        BlockingQueue<?> resolveQueue = (BlockingQueue<?>) FieldUtils.readField(this.indexer, "resolveQueue", true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_TIMEOUT);
        while (!resolveQueue.isEmpty() || resolveThread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                fail("The resolve thread is still busy");
            }
            Thread.sleep(1);
        }
    }

    @Test
    void disposeProcessesQueuedEntries() throws Exception
    {
        DocumentReference first = documentReference("First");
        CountDownLatch entered = blockDocumentExtraction(first);

        this.indexer.index(first, false);
        // Wait for the index thread to be busy with the first entry so that the next ones stay in the queues.
        assertTrue(entered.await(TEST_TIMEOUT, TimeUnit.SECONDS));
        this.indexer.index(documentReference("Second"), false);
        this.indexer.index(documentReference("Third"), false);
        ReadyIndicator readyIndicator = this.indexer.waitReady();

        // Dispose in another thread and let the indexing continue only when the indexer is waiting for its queues to
        // be processed.
        Thread disposeThread = new Thread(() -> {
            try {
                this.indexer.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        disposeThread.start();
        awaitState(disposeThread, Thread.State.TIMED_WAITING);

        // New entries are not accepted any more.
        this.indexer.index(documentReference("Late"), false);

        this.release.countDown();
        disposeThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
        assertEquals(Thread.State.TERMINATED, disposeThread.getState());

        // Everything queued before the dispose has been sent and committed, in order, before the end of the dispose.
        assertEquals(List.of(first.toString(), documentReference("Second").toString(),
            documentReference("Third").toString()), this.sentDocuments);
        verify(this.solrInstance, atLeastOnce()).commit();
        readyIndicator.get(TEST_TIMEOUT, TimeUnit.SECONDS);
        assertEquals(0, this.indexer.getQueueSize());

        // Nothing was dropped.
        assertEquals(0, loggedMessages().size());
    }

    @Test
    void disposeWithEmptyQueues() throws Exception
    {
        long start = System.nanoTime();
        this.indexer.dispose();

        // The stop does not wait for the timeout and has nothing to send.
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(TEST_TIMEOUT / 2));
        verify(this.solrInstance, never()).add(anyList());
        verify(this.solrInstance, never()).commit();
        assertEquals(0, loggedMessages().size());
    }

    @Test
    void disposeWhenSolrIsBlocked() throws Exception
    {
        when(this.configuration.getIndexerShutdownTimeout()).thenReturn(200L);

        // The Solr client never answers.
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            this.release.await();
            return null;
        }).when(this.solrInstance).add(anyList());

        this.indexer.index(documentReference("First"), false);
        assertTrue(entered.await(TEST_TIMEOUT, TimeUnit.SECONDS));
        this.indexer.index(documentReference("Second"), false);
        this.indexer.index(documentReference("Third"), false);

        long start = System.nanoTime();
        this.indexer.dispose();
        long duration = System.nanoTime() - start;

        // The dispose waited for the timeout, not more.
        assertTrue(duration >= TimeUnit.MILLISECONDS.toNanos(200), "Disposed in [" + duration + "] ns");
        assertTrue(duration < TimeUnit.SECONDS.toNanos(TEST_TIMEOUT / 2), "Disposed in [" + duration + "] ns");

        assertEquals(1, loggedMessages().size());
        assertTrue(loggedMessages().get(0).startsWith(
            "The Solr indexer could not finish processing its queues within [200] ms while stopping:"),
            loggedMessages().get(0));

        // The threads end once the Solr client answers again.
        this.release.countDown();
        awaitIndexerThreadsEnd();
    }

    @Test
    void disposeWithoutTimeoutDropsQueuedEntries() throws Exception
    {
        when(this.configuration.getIndexerShutdownTimeout()).thenReturn(0L);

        DocumentReference first = documentReference("First");
        CountDownLatch entered = blockDocumentExtraction(first);

        this.indexer.index(first, false);
        assertTrue(entered.await(TEST_TIMEOUT, TimeUnit.SECONDS));
        this.indexer.index(documentReference("Second"), false);
        this.indexer.index(documentReference("Third"), false);
        ReadyIndicator readyIndicator = this.indexer.waitReady();
        awaitResolverIdle();

        this.indexer.dispose();

        assertEquals(1, loggedMessages().size());
        assertEquals(expectedAbandonMessage(0, 0, 2, 0), loggedMessages().get(0));

        // The ready indicator queued before the stop will never be reached.
        ExecutionException exception =
            assertThrows(ExecutionException.class, () -> readyIndicator.get(TEST_TIMEOUT, TimeUnit.SECONDS));
        StoreStoppedException cause = assertInstanceOf(StoreStoppedException.class, exception.getCause());
        assertEquals("Indexing stopped.", cause.getMessage());

        // The entries which were still queued are not indexed.
        this.release.countDown();
        awaitIndexerThreadsEnd();
        verify(this.documentExtractor, never()).getSolrDocument(documentReference("Second"));
        verify(this.documentExtractor, never()).getSolrDocument(documentReference("Third"));
        verify(this.solrInstance, never()).commit();
    }

    private static String expectedAbandonMessage(long timeout, int resolve, int dropped, int sending)
    {
        return String.format("The Solr indexer could not finish processing its queues within [%d] ms while stopping:"
            + " [%d] references to resolve and [%d] index operations were dropped (not counting the entity being"
            + " indexed, if any) and [%d] index operations were still being sent to the Solr server. The Solr index"
            + " may miss the corresponding changes.", timeout, resolve, dropped, sending);
    }

    private Thread startDispose()
    {
        Thread disposeThread = new Thread(() -> {
            try {
                this.indexer.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        disposeThread.start();
        return disposeThread;
    }

    private void awaitIndexerThreadsEnd() throws Exception
    {
        for (String field : List.of("resolveThread", "indexThread")) {
            Thread thread = (Thread) FieldUtils.readField(this.indexer, field, true);
            thread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
            assertEquals(Thread.State.TERMINATED, thread.getState());
        }
        ExecutorService executor = (ExecutorService) FieldUtils.readField(this.indexer, "solrClientExecutor", true);
        assertTrue(executor.awaitTermination(TEST_TIMEOUT, TimeUnit.SECONDS));
    }

    /**
     * A queue which pauses the first producer enabled right after its entry is inserted.
     */
    private static final class PausingQueue<E> extends LinkedBlockingQueue<E>
    {
        private volatile boolean enabled;

        private final CountDownLatch inserted = new CountDownLatch(1);

        private final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        public void put(E e) throws InterruptedException
        {
            super.put(e);
            if (this.enabled) {
                this.enabled = false;
                this.inserted.countDown();
                this.proceed.await();
            }
        }
    }

    @Test
    void disposeCommitsTheLastBatch() throws Exception
    {
        DocumentReference first = documentReference("First");
        CountDownLatch entered = blockDocumentExtraction(first);

        this.indexer.index(first, false);
        assertTrue(entered.await(TEST_TIMEOUT, TimeUnit.SECONDS));
        this.indexer.index(documentReference("Second"), false);
        this.indexer.index(documentReference("Third"), false);
        awaitResolverIdle();

        Thread disposeThread = startDispose();
        awaitState(disposeThread, Thread.State.TIMED_WAITING);
        // The index stop signal is queued right behind Second and Third, in the same batch.
        Thread resolveThread = (Thread) FieldUtils.readField(this.indexer, "resolveThread", true);
        resolveThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
        assertEquals(Thread.State.TERMINATED, resolveThread.getState());

        this.release.countDown();
        disposeThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
        assertEquals(Thread.State.TERMINATED, disposeThread.getState());

        // No ready marker: only the commit at the stop signal sends this batch.
        assertEquals(List.of(first.toString(), documentReference("Second").toString(),
            documentReference("Third").toString()), this.sentDocuments);
        verify(this.solrInstance).commit();
        assertEquals(0, loggedMessages().size());
    }

    @Test
    void entryQueuedWhileDisposingIsNotDropped() throws Exception
    {
        // Keep the resolve thread busy.
        Thread resolveThread = (Thread) FieldUtils.readField(this.indexer, "resolveThread", true);
        DocumentReference blocker = documentReference("Blocker");
        CountDownLatch resolverBusy = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread() == resolveThread && blocker.equals(invocation.getArgument(0))) {
                resolverBusy.countDown();
                this.release.await();
            }
            return invocation.getArgument(0);
        }).when(this.entityReferenceFactory).getReference(any());
        this.indexer.index(blocker, false);
        assertTrue(resolverBusy.await(TEST_TIMEOUT, TimeUnit.SECONDS));

        PausingQueue<Object> pausingQueue = new PausingQueue<>();
        FieldUtils.writeField(this.indexer, "resolveQueue", pausingQueue, true);

        // A producer is queuing Second when the dispose starts.
        pausingQueue.enabled = true;
        DocumentReference second = documentReference("Second");
        Thread producer = new Thread(() -> this.indexer.index(second, false));
        producer.start();
        assertTrue(pausingQueue.inserted.await(TEST_TIMEOUT, TimeUnit.SECONDS));

        // The dispose waits for the producer before queuing the stop signal.
        Thread disposeThread = startDispose();
        awaitState(disposeThread, Thread.State.WAITING);
        assertEquals(1, pausingQueue.size());

        pausingQueue.proceed.countDown();
        producer.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
        awaitState(disposeThread, Thread.State.TIMED_WAITING);
        assertEquals(2, pausingQueue.size());

        this.release.countDown();
        disposeThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
        assertEquals(Thread.State.TERMINATED, disposeThread.getState());

        // Second, queued before the stop signal, is indexed.
        assertEquals(List.of(blocker.toString(), second.toString()), this.sentDocuments);
        assertEquals(0, loggedMessages().size());
    }

    @Test
    void abandonedDrainSendsNothingMore() throws Exception
    {
        when(this.configuration.getIndexerShutdownTimeout()).thenReturn(200L);
        when(this.configuration.getIndexerBatchSize()).thenReturn(1);

        DocumentReference first = documentReference("First");
        CountDownLatch entered = blockDocumentExtraction(first);
        this.indexer.index(first, false);
        assertTrue(entered.await(TEST_TIMEOUT, TimeUnit.SECONDS));
        this.indexer.index(documentReference("Second"), false);
        awaitResolverIdle();

        this.indexer.dispose();

        assertEquals(1, loggedMessages().size());
        assertEquals(expectedAbandonMessage(200, 0, 1, 0), loggedMessages().get(0));

        // The index thread finishes the extraction in progress but sends nothing to the Solr server.
        this.release.countDown();
        awaitIndexerThreadsEnd();
        assertEquals(List.of(), this.sentDocuments);
        verify(this.solrInstance, never()).commit();
    }

    @Test
    void disposeWhenSolrRefusesTheRequests() throws Exception
    {
        doThrow(new SolrServerException("Connection refused")).when(this.solrInstance).add(anyList());

        DocumentReference first = documentReference("First");
        CountDownLatch entered = blockDocumentExtraction(first);
        this.indexer.index(first, false);
        assertTrue(entered.await(TEST_TIMEOUT, TimeUnit.SECONDS));
        this.indexer.index(documentReference("Second"), false);
        awaitResolverIdle();

        Thread disposeThread = startDispose();
        awaitState(disposeThread, Thread.State.TIMED_WAITING);
        this.release.countDown();
        disposeThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT));
        assertEquals(Thread.State.TERMINATED, disposeThread.getState());

        // The drain finished in time, but the changes could not be sent.
        assertEquals(2, loggedMessages().size());
        assertEquals("Failed to add documents to the Solr server", loggedMessages().get(0));
        assertEquals("[1] requests to the Solr server failed while the Solr indexer was processing its queues before"
            + " stopping. The Solr index may miss the corresponding changes.", loggedMessages().get(1));
    }

    @Test
    void waitReadyWhenDisposed() throws Exception
    {
        this.indexer.dispose();

        ReadyIndicator readyIndicator = this.indexer.waitReady();

        // The indexer won't become ready again, which is reported as a stop and not as a failure.
        ExecutionException exception =
            assertThrows(ExecutionException.class, () -> readyIndicator.get(TEST_TIMEOUT, TimeUnit.SECONDS));
        StoreStoppedException cause = assertInstanceOf(StoreStoppedException.class, exception.getCause());
        assertEquals("The indexer has been disposed", cause.getMessage());
    }
}
