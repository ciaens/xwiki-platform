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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.xwiki.bridge.internal.DocumentContextExecutor;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.DisposePriority;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.job.JobException;
import org.xwiki.job.JobExecutor;
import org.xwiki.model.EntityType;
import org.xwiki.model.internal.reference.EntityReferenceFactory;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.search.solr.internal.api.IndexingUserConfig;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.search.solr.internal.api.SolrIndexer;
import org.xwiki.search.solr.internal.api.SolrIndexerException;
import org.xwiki.search.solr.internal.api.SolrInstance;
import org.xwiki.search.solr.internal.job.IndexerJob;
import org.xwiki.search.solr.internal.job.IndexerRequest;
import org.xwiki.search.solr.internal.metadata.SolrMetadataExtractor;
import org.xwiki.search.solr.internal.metadata.XWikiSolrInputDocument;
import org.xwiki.search.solr.internal.reference.SolrReferenceResolver;
import org.xwiki.store.ReadyIndicator;
import org.xwiki.store.StoreStoppedException;

import com.google.common.util.concurrent.Uninterruptibles;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.util.AbstractXWikiRunnable;

/**
 * Default implementation of {@link SolrIndexer}.
 * <p>
 * This implementation does not directly process the given leaf-references, but adds them to a processing queue, in the
 * order they were received. The {@link Runnable} part of this implementation is the one that sequentially reads and
 * processes the queue.
 * 
 * @version $Id$
 * @since 5.1M2
 */
@Component
@Singleton
// We start the disposal earlier than the components with the default priority because the resolve & index threads
// process the entries which are still queued when the indexer is disposed, and they need the components used to resolve
// and extract the entities (document store, caches, metadata extractors) and the Solr client to still be available.
@DisposePriority(500)
public class DefaultSolrIndexer implements SolrIndexer, Initializable, Disposable, Runnable
{
    /**
     * Index queue entry.
     * 
     * @version $Id$
     */
    private static class IndexQueueEntry
    {
        /**
         * The reference of the entity to index.
         */
        public EntityReference reference;

        /**
         * The query used to filter entries to delete.
         */
        public String deleteQuery;
        /**
         * The indexing operation to perform.
         */
        public IndexOperation operation;

        /**
         * The ready indicator to indicate that indexing finished until that point.
         */
        private SolrIndexerReadyIndicator readyIndicator;

        /**
         * @param indexReference the reference of the entity to index.
         * @param operation the indexing operation to perform.
         */
        public IndexQueueEntry(EntityReference indexReference, IndexOperation operation)
        {
            this.reference = indexReference;
            this.operation = operation;
        }

        /**
         * @param deleteQuery the query used to filter entries to delete.
         * @param operation the indexing operation to perform.
         */
        public IndexQueueEntry(String deleteQuery, IndexOperation operation)
        {
            this.deleteQuery = deleteQuery;
            this.operation = operation;
        }

        IndexQueueEntry(SolrIndexerReadyIndicator readyIndicator)
        {
            this.readyIndicator = readyIndicator;
            this.operation = IndexOperation.READY_MARKER;
        }

        @Override
        public String toString()
        {
            String str;

            switch (operation) {
                case INDEX:
                    str = "INDEX " + this.reference;
                    break;
                case DELETE:
                    str = "DELETE " + this.deleteQuery;
                    break;
                case STOP:
                    str = "STOP";
                    break;
                case READY_MARKER:
                    str = "READY_MARKER";
                    break;
                default:
                    str = "";
                    break;
            }

            return str;
        }
    }

    /**
     * Resolve queue entry.
     * 
     * @version $Id$
     */
    private static class ResolveQueueEntry
    {
        /**
         * The reference of the entity to index.
         */
        public EntityReference reference;

        /**
         * Also apply operation to reference children.
         */
        public boolean recurse;

        /**
         * The indexing operation to perform.
         */
        public IndexOperation operation;

        /**
         * The ready indicator to indicate that indexing finished until that point.
         */
        private final SolrIndexerReadyIndicator readyIndicator;

        /**
         * @param reference the reference of the entity to index.
         * @param recurse also apply operation to reference children.
         * @param operation the indexing operation to perform.
         */
        public ResolveQueueEntry(EntityReference reference, boolean recurse, IndexOperation operation)
        {
            this.reference = reference;
            this.recurse = recurse;
            this.operation = operation;
            this.readyIndicator = null;
        }

        ResolveQueueEntry(SolrIndexerReadyIndicator readyIndicator)
        {
            this.readyIndicator = readyIndicator;
            this.operation = IndexOperation.READY_MARKER;
        }
    }

    /**
     * Extract children references from passed references and dispatch them to the index queue.
     * 
     * @version $Id$
     */
    private final class Resolver extends AbstractXWikiRunnable
    {
        @Override
        public void runInternal()
        {
            logger.debug("Start SOLR resolver thread");

            while (!Thread.interrupted()) {
                ResolveQueueEntry queueEntry = getQueueEntry();

                // Stop at the stop signal, or as soon as the indexer stopped waiting for the queued entries.
                if (queueEntry == RESOLVE_QUEUE_ENTRY_STOP || DefaultSolrIndexer.this.drainAbandoned) {
                    if (queueEntry != RESOLVE_QUEUE_ENTRY_STOP) {
                        dropResolveQueueEntry(queueEntry);
                    }
                    // Stop the index thread once it has processed the index operations queued before.
                    sendIndexerStopSignal();
                    break;
                }

                try {
                    dispatchQueueEntry(queueEntry);
                } catch (Throwable e) {
                    logger.warn("Failed to apply operation [{}] on root reference [{}]",
                        queueEntry.operation, queueEntry.reference, e);
                } finally {
                    // Decrement only for entries submitted via addToQueue(); READY_MARKER entries come from
                    // waitReady() which does not increment pendingResolveItems.
                    if (queueEntry.operation != IndexOperation.READY_MARKER) {
                        DefaultSolrIndexer.this.pendingResolveItems.decrementAndGet();
                    }
                }
            }

            logger.debug("Stop SOLR resolver thread");
        }

        private void dispatchQueueEntry(ResolveQueueEntry queueEntry)
            throws SolrIndexerException, InterruptedException
        {
            switch (queueEntry.operation) {
                case READY_MARKER:
                    queueEntry.readyIndicator.switchToIndexQueue();
                    DefaultSolrIndexer.this.indexQueue.put(new IndexQueueEntry(queueEntry.readyIndicator));
                    break;
                case INDEX:
                    Iterable<EntityReference> references = retrieveReferences(queueEntry);

                    for (EntityReference reference : references) {
                        if (DefaultSolrIndexer.this.drainAbandoned) {
                            // The indexer stopped waiting for the queued entries.
                            break;
                        }
                        indexQueue.put(new IndexQueueEntry(
                            DefaultSolrIndexer.this.entityReferenceFactory.getReference(reference),
                            queueEntry.operation));
                    }
                    break;
                default:
                    if (queueEntry.recurse) {
                        indexQueue.put(new IndexQueueEntry(solrRefereceResolver.getQuery(queueEntry.reference),
                            queueEntry.operation));
                    } else if (queueEntry.reference != null) {
                        indexQueue.put(new IndexQueueEntry(
                            DefaultSolrIndexer.this.entityReferenceFactory.getReference(queueEntry.reference),
                            queueEntry.operation));
                    }
            }
        }

        private Iterable<EntityReference> retrieveReferences(ResolveQueueEntry queueEntry) throws SolrIndexerException
        {
            Iterable<EntityReference> references;
            if (queueEntry.recurse) {
                references = solrRefereceResolver.getReferences(queueEntry.reference);
            } else {
                references = Arrays.asList(queueEntry.reference);
            }
            return references;
        }

        private ResolveQueueEntry getQueueEntry()
        {
            ResolveQueueEntry queueEntry;
            try {
                queueEntry = resolveQueue.take();
                DefaultSolrIndexer.this.resolveQueueRemovalCounter.incrementAndGet();
            } catch (InterruptedException e) {
                logger.warn("The SOLR resolve thread has been interrupted", e);
                queueEntry = RESOLVE_QUEUE_ENTRY_STOP;
            }
            return queueEntry;
        }
    }

    /**
     * Stop resolver thread.
     */
    private static final ResolveQueueEntry RESOLVE_QUEUE_ENTRY_STOP =
        new ResolveQueueEntry(null, false, IndexOperation.STOP);

    /**
     * Stop indexer thread.
     */
    private static final IndexQueueEntry INDEX_QUEUE_ENTRY_STOP =
        new IndexQueueEntry((String) null, IndexOperation.STOP);

    private static final String INDEXING_STOPPED = "Indexing stopped.";

    private static final String INDEXER_DISPOSED = "The indexer has been disposed";

    private static final String STOPPED_INDEXER_LOG =
        "The Solr indexer is stopped, ignoring operation [{}] on reference [{}]";

    /**
     * Logging framework.
     */
    @Inject
    private Logger logger;

    /**
     * Component manager used to get metadata extractors.
     */
    @Inject
    private ComponentManager componentManager;

    /**
     * The Solr configuration source.
     */
    @Inject
    private SolrConfiguration configuration;

    /**
     * Communication with the Solr instance.
     */
    @Inject
    private SolrInstance solrInstance;

    /**
     * Extract contained indexable references.
     */
    @Inject
    private SolrReferenceResolver solrRefereceResolver;

    /**
     * Provide a context user for indexing.
     */
    @Inject
    private IndexingUserConfig indexingUserConfig;

    @Inject
    private Execution execution;

    @Inject
    private ExecutionContextManager ecim;

    @Inject
    private JobExecutor jobs;

    @Inject
    private DocumentContextExecutor documentContextExecutor;

    @Inject
    private Provider<XWikiContext> xWikiContextProvider;

    @Inject
    private EntityReferenceFactory entityReferenceFactory;

    /**
     * The queue of index operation to perform.
     */
    private BlockingQueue<IndexQueueEntry> indexQueue;

    /**
     * The queue of resolve references and add them to the index queue.
     */
    private BlockingQueue<ResolveQueueEntry> resolveQueue;

    /**
     * The executor for Solr client operations.
     */
    private ExecutorService solrClientExecutor;

    /**
     * Future to wait on the last commit operation.
     */
    private Future<Void> commitFuture;

    /**
     * Thread in which the indexUpdater will be executed.
     */
    private Thread indexThread;

    /**
     * Thread in which the provided references children will be resolved.
     */
    private Thread resolveThread;

    /**
     * A counter that tracks how many items have been removed from the index queue since the start.
     * Used to track progress in the index queue.
     */
    private final AtomicLong indexQueueRemovalCounter = new AtomicLong();

    /**
     * A counter that tracks how many items have been removed from the resolve queue since the start.
     * Used to track progress in the resolve queue.
     */
    private final AtomicLong resolveQueueRemovalCounter = new AtomicLong();

    /**
     * Tracks the number of items currently waiting in or actively being processed by the resolver — i.e. items that
     * have been submitted via {@link #addToQueue} but whose resolved references have not yet been fully placed into
     * the index queue. Incrementing before the {@link #resolveQueue} put and decrementing only after all resolved
     * references have been put into {@link #indexQueue} prevents {@link #getQueueSize()} from transiently returning
     * zero while the resolver is in the middle of a database look-up.
     */
    private final AtomicInteger pendingResolveItems = new AtomicInteger();

    /**
     * Indicate if the component has been disposed, in which case no new entry is accepted.
     */
    private volatile boolean disposed;

    /**
     * Indicate if the processing of the queued entries has been abandoned because it did not finish in time when the
     * component was disposed.
     */
    private volatile boolean drainAbandoned;

    /**
     * Makes "check that the indexer is not disposed and queue an entry" atomic with respect to "mark the indexer as
     * disposed and queue the stop signal", so that no entry can be queued behind the stop signal. The resolve queue is
     * not bounded, so a producer never waits while holding the read lock.
     */
    private final ReadWriteLock disposeLock = new ReentrantReadWriteLock();

    /**
     * The number of requests to the Solr server which failed after the indexer was disposed.
     */
    private final AtomicInteger failedRequestsAfterDispose = new AtomicInteger();

    /**
     * The size of the not yet sent batch.
     */
    private volatile int batchSize;

    /**
     * The length of the not yet sent batch in characters.
     */
    private int batchLength;

    /**
     * The size of the batch that is currently being sent and committed.
     */
    private volatile int committingBatchSize;

    @Override
    public void initialize() throws InitializationException
    {
        // Initialize the queues before starting the threads.
        this.resolveQueue = new LinkedBlockingQueue<>();
        this.indexQueue = new LinkedBlockingQueue<>(this.configuration.getIndexerQueueCapacity());

        // Launch the Solr client executor.
        this.solrClientExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("XWiki Solr client thread");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });

        // Launch the resolve thread
        this.resolveThread = new Thread(new Resolver());
        this.resolveThread.setName("XWiki Solr resolve thread");
        this.resolveThread.setDaemon(true);
        this.resolveThread.start();
        this.resolveThread.setPriority(Thread.NORM_PRIORITY - 1);

        // Launch the index thread
        this.indexThread = new Thread(this);
        this.indexThread.setName("XWiki Solr index thread");
        this.indexThread.setDaemon(true);
        this.indexThread.start();
        this.indexThread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        // Stop accepting new entries, and ask the resolve thread to stop once it has processed the entries queued
        // before, which then asks the index thread to stop once it has processed (and committed) the index operations
        // queued before. The resolve queue is not bounded so the stop signal is always accepted.
        this.disposeLock.writeLock().lock();
        try {
            if (this.disposed) {
                // Already disposed.
                return;
            }
            this.disposed = true;
            this.resolveQueue.offer(RESOLVE_QUEUE_ENTRY_STOP);
        } finally {
            this.disposeLock.writeLock().unlock();
        }

        // Wait for the queued entries to be processed while the document store and the Solr client are still available
        // (the indexer is disposed before them), but not more than the configured timeout in case Solr cannot be
        // reached or the processing is stuck for some other reason.
        long timeout = Math.max(this.configuration.getIndexerShutdownTimeout(), 0);
        boolean stopped;
        try {
            stopped = waitForThreads(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout));
        } catch (InterruptedException e) {
            this.logger.warn("Interrupted while waiting for the Solr indexer to process its queues: [{}]",
                ExceptionUtils.getRootCauseMessage(e));
            Thread.currentThread().interrupt();
            stopped = false;
        }

        if (!stopped) {
            abandonDrain(timeout);
        } else if (this.failedRequestsAfterDispose.get() > 0) {
            // The queues were processed in time, but some changes could not be sent (the Solr server refused the
            // connection for example): each failure was logged as an error.
            this.logger.warn("[{}] requests to the Solr server failed while the Solr indexer was processing its queues"
                + " before stopping. The Solr index may miss the corresponding changes.",
                this.failedRequestsAfterDispose.get());
        }
    }

    /**
     * Wait for the resolve and index threads to stop and for the Solr client operations they submitted to finish.
     *
     * @param deadline the {@link System#nanoTime()} value after which to stop waiting
     * @return {@code true} if everything stopped before the deadline
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    private boolean waitForThreads(long deadline) throws InterruptedException
    {
        for (Thread thread : List.of(this.resolveThread, this.indexThread)) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            }
            if (thread.isAlive()) {
                return false;
            }
        }

        // The index thread shuts down the Solr client executor when it stops, after submitting its last commit.
        return this.solrClientExecutor.awaitTermination(Math.max(deadline - System.nanoTime(), 0),
            TimeUnit.NANOSECONDS);
    }

    /**
     * Stop processing the queued entries: drop what is left in the queues and log how much was lost.
     *
     * @param timeout the time in milliseconds which was given to the processing of the queued entries
     */
    private void abandonDrain(long timeout)
    {
        // Make the resolve and index threads stop as soon as possible: they don't take new entries and don't send
        // anything more to the Solr server. They are not interrupted, so the operation in progress (a database query,
        // the extraction of a document or a request to the Solr server) is finished first.
        this.drainAbandoned = true;
        // The operations already submitted to the Solr client are still executed.
        this.solrClientExecutor.shutdown();

        // Clear the resolve queue and send the stop signal again in case the resolve thread did not reach it yet
        // (if it did, the second stop signal is never read).
        int resolveEntries = 0;
        for (ResolveQueueEntry entry = this.resolveQueue.poll(); entry != null; entry = this.resolveQueue.poll()) {
            if (entry != RESOLVE_QUEUE_ENTRY_STOP) {
                dropResolveQueueEntry(entry);
                if (entry.operation != IndexOperation.READY_MARKER) {
                    resolveEntries++;
                }
            }
        }
        this.resolveQueue.offer(RESOLVE_QUEUE_ENTRY_STOP);

        // Same for the index queue. Clearing it also unblocks the resolve thread if it was waiting for some space in
        // the index queue.
        int indexEntries = clearIndexQueue() + this.batchSize;
        this.indexQueue.offer(INDEX_QUEUE_ENTRY_STOP);

        int sendingEntries = this.committingBatchSize;
        if (timeout > 0 || resolveEntries > 0 || indexEntries > 0 || sendingEntries > 0) {
            this.logger.warn("The Solr indexer could not finish processing its queues within [{}] ms while stopping:"
                + " [{}] references to resolve and [{}] index operations were dropped (not counting the entity being"
                + " indexed, if any) and [{}] index operations were still being sent to the Solr server. The Solr index"
                + " may miss the corresponding changes.", timeout, resolveEntries, indexEntries, sendingEntries);
        }
    }

    private void dropResolveQueueEntry(ResolveQueueEntry entry)
    {
        if (entry.operation == IndexOperation.READY_MARKER) {
            entry.readyIndicator.completeExceptionally(new StoreStoppedException(INDEXING_STOPPED));
        } else {
            this.pendingResolveItems.decrementAndGet();
        }
    }

    private int clearIndexQueue()
    {
        int count = 0;
        for (IndexQueueEntry entry = this.indexQueue.poll(); entry != null; entry = this.indexQueue.poll()) {
            if (entry.operation == IndexOperation.READY_MARKER) {
                entry.readyIndicator.completeExceptionally(new StoreStoppedException(INDEXING_STOPPED));
            } else if (entry != INDEX_QUEUE_ENTRY_STOP) {
                count++;
            }
        }
        return count;
    }

    private void sendIndexerStopSignal()
    {
        if (this.drainAbandoned) {
            // Don't wait for some space in the index queue: the index thread is stopping anyway.
            this.indexQueue.offer(INDEX_QUEUE_ENTRY_STOP);
            return;
        }

        try {
            // Wait for some space in the index queue if it's full: the index thread is processing it, or the queue is
            // cleared when the processing takes too long.
            this.indexQueue.put(INDEX_QUEUE_ENTRY_STOP);
        } catch (InterruptedException e) {
            // Don't wait: drop the index operations which are still queued to make space for the stop signal.
            clearIndexQueue();
            this.indexQueue.offer(INDEX_QUEUE_ENTRY_STOP);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run()
    {
        this.logger.debug("Start SOLR indexer thread");

        while (!Thread.interrupted()) {
            // Block until there is at least one entry in the queue
            IndexQueueEntry queueEntry = null;
            try {
                queueEntry = this.indexQueue.take();
            } catch (InterruptedException e) {
                this.logger.warn("The SOLR index thread has been interrupted", e);

                queueEntry = INDEX_QUEUE_ENTRY_STOP;
            }

            // Add to the batch until either the batch size is achieved, the queue gets emptied or the
            // INDEX_QUEUE_ENTRY_STOP is retrieved from the queue.
            if (!processBatch(queueEntry)) {
                break;
            }
        }

        this.logger.debug("Stop SOLR indexer thread");
    }

    /**
     * Process a batch of operations that were just read from the index operations queue. This method also commits the
     * batch when it finishes to process it.
     * 
     * @param queueEntry the batch to process
     * @return {@code true} to wait for another batch, {@code false} to stop the indexing thread
     */
    private boolean processBatch(IndexQueueEntry queueEntry)
    {
        List<SolrInputDocument> solrDocuments = new ArrayList<>();

        for (IndexQueueEntry batchEntry = queueEntry; batchEntry != null; batchEntry = this.indexQueue.poll()) {
            this.indexQueueRemovalCounter.incrementAndGet();

            // Stop at the stop signal, or as soon as the indexer stopped waiting for the queued entries.
            if (batchEntry == INDEX_QUEUE_ENTRY_STOP || this.drainAbandoned) {
                if (batchEntry.operation == IndexOperation.READY_MARKER) {
                    batchEntry.readyIndicator.completeExceptionally(new StoreStoppedException(INDEXING_STOPPED));
                }
                // Send and commit what's left of the batch, unless the indexer stopped waiting for it.
                if (this.batchSize > 0) {
                    commit(solrDocuments);
                }
                // Handle the shutdown of the executor here to avoid that the executor is shut down before the batch
                // processing finished. The operations already submitted to the executor are still executed.
                this.solrClientExecutor.shutdown();
                // Stop the indexing thread.
                return false;
            }

            // For the current contiguous operations queue, group the changes
            try {
                ExecutionContext executionContext = new ExecutionContext();
                this.ecim.initialize(executionContext);
                XWikiContext xcontext = (XWikiContext) executionContext.getProperty(XWikiContext.EXECUTIONCONTEXT_KEY);
                xcontext.setUserReference(indexingUserConfig.getIndexingUserReference());

                switch (batchEntry.operation) {
                    case INDEX:
                        XWikiSolrInputDocument solrDocument = getSolrDocument(batchEntry.reference);
                        if (solrDocument != null) {
                            solrDocuments.add(solrDocument);
                            this.batchLength += solrDocument.getLength();
                            ++this.batchSize;
                        }
                        break;
                    case DELETE:
                        // Ensure that all queued additions are processed before any deletions are processed.
                        flushDocuments(solrDocuments);
                        applyDeletion(batchEntry);

                        ++this.batchSize;
                        break;
                    case READY_MARKER:
                        commit(solrDocuments);
                        // Add the completion of the ready indicator to the same queue to ensure that it is processed
                        // after the commit.
                        IndexQueueEntry finalBatchEntry = batchEntry;
                        if (!execute(() -> finalBatchEntry.readyIndicator.complete(null))) {
                            finalBatchEntry.readyIndicator
                                .completeExceptionally(new StoreStoppedException(INDEXING_STOPPED));
                        }
                        break;
                    default:
                        // Do nothing.
                }
            } catch (Throwable e) {
                this.logger.error("Failed to process entry [{}]", batchEntry, e);
            } finally {
                this.execution.removeContext();
            }

            // Commit the index changes so that they become available to queries. This is a costly operation and that is
            // the reason why we perform it at the end of the batch.
            if (shouldCommit(this.batchLength, this.batchSize)) {
                commit(solrDocuments);
            }
        }

        // Commit what's left
        if (this.batchSize > 0) {
            commit(solrDocuments);
        }

        return true;
    }

    private void applyDeletion(IndexQueueEntry queueEntry) throws SolrIndexerException
    {
        String id = queueEntry.reference == null ? null : this.solrRefereceResolver.getId(queueEntry.reference);
        execute(() -> {
            try {
                if (id == null) {
                    this.solrInstance.deleteByQuery(queueEntry.deleteQuery);
                } else {
                    this.solrInstance.delete(id);
                }
            } catch (Exception e) {
                onRequestFailure();
                this.logger.error("Failed to delete document [{}] from the Solr server", queueEntry, e);
            }
        });
    }

    private void flushDocuments(List<SolrInputDocument> documents)
    {
        // An update request without any document has no body, which a remote Solr rejects with a
        // "missing content stream" error. There is also nothing to send, so skip the request entirely.
        if (documents.isEmpty()) {
            return;
        }

        try {
            // Copy the documents to flush to a new list so that we can clear the original list without affecting the
            // documents that are being added to the Solr server.
            List<SolrInputDocument> documentsToFlush = new ArrayList<>(documents);
            execute(() -> {
                try {
                    this.solrInstance.add(documentsToFlush);
                } catch (Exception e) {
                    onRequestFailure();
                    this.logger.error("Failed to add documents to the Solr server", e);
                }
            });
        } finally {
            // Clear the list of documents even when adding them failed to avoid leaking memory and to avoid
            // re-submitting the same documents to the Solr server multiple times.
            documents.clear();
        }
    }

    /**
     * Commit.
     */
    private void commit(List<SolrInputDocument> documents)
    {
        if (this.drainAbandoned) {
            // The indexer stopped waiting for the queued entries: don't send anything more to the Solr server.
            documents.clear();
            this.batchSize = 0;
            this.batchLength = 0;

            return;
        }

        flushDocuments(documents);
        if (this.commitFuture != null) {
            try {
                Uninterruptibles.getUninterruptibly(this.commitFuture);
            } catch (ExecutionException e) {
                this.logger.error("Failed to commit index changes to the Solr server", e);
            }
        }

        // At this point, we can be sure that there is no more commit in the queue - so we can safely store the size
        // of the next commit.
        this.committingBatchSize = this.batchSize;
        try {
            this.commitFuture = this.solrClientExecutor.submit(this::commitTask);
        } catch (RejectedExecutionException e) {
            // The indexer stopped waiting for the queued entries in the meantime.
            this.commitFuture = null;
            this.committingBatchSize = 0;
        }
        this.batchSize = 0;
        this.batchLength = 0;
    }

    private Void commitTask()
    {
        try {
            this.solrInstance.commit();
        } catch (Exception e) {
            onRequestFailure();
            this.logger.error("Failed to commit index changes to the Solr server. Rolling back.", e);

            try {
                this.solrInstance.rollback();
            } catch (Exception ex) {
                // Just log the failure.
                this.logger.error("Failed to rollback index changes.", ex);
            }
        }

        this.committingBatchSize = 0;

        return null;
    }

    /**
     * Submit an operation to the Solr client thread.
     *
     * @param task the operation to execute
     * @return {@code false} if the operation was not accepted because the indexer stopped waiting for the queued
     *         entries
     */
    private boolean execute(Runnable task)
    {
        try {
            this.solrClientExecutor.execute(task);

            return true;
        } catch (RejectedExecutionException e) {
            this.logger.debug("The Solr indexer is stopped, ignoring a request to the Solr server");

            return false;
        }
    }

    private void onRequestFailure()
    {
        if (this.disposed) {
            this.failedRequestsAfterDispose.incrementAndGet();
        }
    }

    /**
     * Check various constraints to know if the batch should be committed.
     * 
     * @param length the current length
     * @param size the current size
     * @return true if the batch should be sent
     */
    private boolean shouldCommit(int length, int size)
    {
        // If the length is above the configured maximum
        if (length >= this.configuration.getIndexerBatchMaxLengh()) {
            return true;
        }

        // If the size is above the configured maximum
        return size >= this.configuration.getIndexerBatchSize();
    }

    /**
     * @param reference the reference to extract metadata from.
     * @return the {@link SolrInputDocument} containing extracted metadata from the passed reference; {@code null} if
     *         the reference type is not supported.
     * @throws SolrIndexerException if problems occur.
     * @throws IllegalArgumentException if there is an incompatibility between a reference and the assigned extractor.
     */
    private XWikiSolrInputDocument getSolrDocument(EntityReference reference)
        throws SolrIndexerException, IllegalArgumentException
    {
        SolrMetadataExtractor metadataExtractor = getMetadataExtractor(reference.getType());

        // If the entity type is supported, use the extractor to get the SolrInputDocuent.
        if (metadataExtractor != null) {
            // Set the document that belongs to the entity reference as context document to ensure that the correct
            // settings are loaded for the current document/wiki.
            XWikiContext context = this.xWikiContextProvider.get();
            try {
                XWikiDocument document = context.getWiki().getDocument(reference, context);

                return this.documentContextExecutor.call(() -> metadataExtractor.getSolrDocument(reference), document);
            } catch (SolrIndexerException | IllegalArgumentException e) {
                // Re-throw to avoid wrapping exceptions that are declared in the method signature.
                throw e;
            } catch (Exception e) {
                throw new SolrIndexerException("Error executing the indexer in the context of the document to index",
                    e);
            }
        }

        return null;
    }

    /**
     * @param entityType the entity type
     * @return the metadata extractor that is registered for the specified type or {@code null} if none exists.
     */
    private SolrMetadataExtractor getMetadataExtractor(EntityType entityType)
    {
        SolrMetadataExtractor result = null;
        try {
            result = this.componentManager.getInstance(SolrMetadataExtractor.class, entityType.name().toLowerCase());
        } catch (ComponentLookupException e) {
            this.logger.warn("Unsupported entity type: [{}]", entityType, e);
        }

        return result;
    }

    @Override
    public void index(EntityReference reference, boolean recurse)
    {
        addToQueue(reference, recurse, IndexOperation.INDEX);
    }

    @Override
    public void delete(EntityReference reference, boolean recurse)
    {
        addToQueue(reference, recurse, IndexOperation.DELETE);
    }

    /**
     * Add a list of references to the index queue, all having the same operation.
     * 
     * @param reference the references to add
     * @param recurse also apply operation to children
     * @param operation the operation to assign to the given references
     */
    private void addToQueue(EntityReference reference, boolean recurse, IndexOperation operation)
    {
        ResolveQueueEntry entry =
            new ResolveQueueEntry(this.entityReferenceFactory.getReference(reference), recurse, operation);

        // Hold the read lock so that the entry cannot be queued behind the stop signal.
        this.disposeLock.readLock().lock();
        try {
            if (!this.disposed) {
                // Increment before the put so that getQueueSize() never transiently returns zero while the resolver is
                // consuming this entry from resolveQueue but has not yet placed resolved references into indexQueue.
                this.pendingResolveItems.incrementAndGet();
                // Don't block because the capacity of the resolver queue is not limited.
                this.resolveQueue.put(entry);
            } else {
                this.logger.debug(STOPPED_INDEXER_LOG, operation, reference);
            }
        } catch (InterruptedException e) {
            this.pendingResolveItems.decrementAndGet();
            this.logger.error("Failed to add reference [{}] to Solr indexing queue", reference, e);
        } finally {
            this.disposeLock.readLock().unlock();
        }
    }

    @Override
    public int getQueueSize()
    {
        return this.indexQueue.size() + this.pendingResolveItems.get() + this.batchSize + this.committingBatchSize;
    }

    @Override
    public IndexerJob startIndex(IndexerRequest request) throws SolrIndexerException
    {
        try {
            return (IndexerJob) this.jobs.execute(IndexerJob.JOBTYPE, request);
        } catch (JobException e) {
            throw new SolrIndexerException("Failed to start index job", e);
        }
    }

    @Override
    public ReadyIndicator waitReady()
    {
        SolrIndexerReadyIndicator readyIndicator = new SolrIndexerReadyIndicator(
            this.resolveQueueRemovalCounter::getAcquire, this.resolveQueue::size,
            this.indexQueueRemovalCounter::getAcquire, this.indexQueue::size,
            this.configuration::getIndexerQueueCapacity
        );

        // Hold the read lock so that the marker cannot be queued behind the stop signal.
        this.disposeLock.readLock().lock();
        try {
            if (!this.disposed) {
                this.resolveQueue.put(new ResolveQueueEntry(readyIndicator));
            } else {
                // The indexer has been stopped and won't become ready again.
                readyIndicator.completeExceptionally(new StoreStoppedException(INDEXER_DISPOSED));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            readyIndicator.completeExceptionally(e);
        } finally {
            this.disposeLock.readLock().unlock();
        }

        return readyIndicator;
    }
}
