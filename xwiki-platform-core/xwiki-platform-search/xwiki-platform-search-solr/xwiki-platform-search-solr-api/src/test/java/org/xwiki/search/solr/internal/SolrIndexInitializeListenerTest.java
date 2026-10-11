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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.remote.ClusterSingletonManager;
import org.xwiki.observation.remote.ClusterSingletonNodeChangedEvent;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.search.solr.internal.api.SolrIndexer;
import org.xwiki.search.solr.internal.api.SolrIndexerException;
import org.xwiki.search.solr.internal.job.IndexerRequest;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptor;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Check the behaviour of {@link SolrIndexInitializeListener}.
 *
 * @version $Id$
 * @since 12.5RC1
 */
@ComponentTest
class SolrIndexInitializeListenerTest
{
    @InjectMockComponents
    private SolrIndexInitializeListener solrIndexInitializeListener;

    @MockComponent
    private Provider<SolrIndexer> solrIndexerProvider;

    @MockComponent
    private SolrIndexer solrIndexer;

    @MockComponent
    private SolrConfiguration configuration;

    @MockComponent
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    private static final long TIMEOUT = 10000;

    @MockComponent
    private ClusterSingletonManager singletonManager;

    @MockComponent
    private ExecutionContextManager executionContextManager;

    @MockComponent
    private Execution execution;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @BeforeEach
    void setup()
    {
        when(solrIndexerProvider.get()).thenReturn(solrIndexer);
    }

    private IndexerRequest farmRequest()
    {
        IndexerRequest indexerRequest = new IndexerRequest();
        indexerRequest.setId(List.of("solr", "indexer"));
        indexerRequest.setCleanInvalid(true);

        return indexerRequest;
    }

    private IndexerRequest wikiRequest(String wiki)
    {
        IndexerRequest indexerRequest = new IndexerRequest();
        indexerRequest.setRootReference(new WikiReference(wiki));
        indexerRequest.setId(List.of("solr", "indexer", "wiki:" + wiki));
        indexerRequest.setCleanInvalid(true);

        return indexerRequest;
    }

    private void setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode mode)
    {
        when(this.configuration.getServerType()).thenReturn(RemoteSolr.TYPE);
        when(this.configuration.synchronizeAtStartup()).thenReturn(true);
        when(this.configuration.synchronizeAtStartupMode()).thenReturn(mode);
    }

    private void notifySingletonNodeChanged(boolean singletonNode)
    {
        // The listener reads the current state from the manager
        when(this.singletonManager.isSingletonNode()).thenReturn(singletonNode);
        this.solrIndexInitializeListener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager,
            singletonNode);
    }

    @Test
    void getEvents()
    {
        assertEquals(3, this.solrIndexInitializeListener.getEvents().size());
        assertEquals(SolrIndexInitializeListener.class.getName(), this.solrIndexInitializeListener.getName());
    }

    @Test
    void onEventApplicationReadyForFarm() throws SolrIndexerException
    {
        when(this.configuration.synchronizeAtStartupMode()).thenReturn(SolrConfiguration.SynchronizeAtStartupMode.FARM);

        when(this.configuration.synchronizeAtStartup()).thenReturn(false);
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        verify(this.solrIndexer, never()).startIndex(any());

        when(this.configuration.synchronizeAtStartup()).thenReturn(true);
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        IndexerRequest indexerRequest = new IndexerRequest();
        indexerRequest.setId(List.of("solr", "indexer"));
        verify(this.solrIndexer).startIndex(indexerRequest);
    }

    @Test
    void onEventWikiReadyForWiki() throws SolrIndexerException
    {
        when(this.configuration.synchronizeAtStartupMode()).thenReturn(SolrConfiguration.SynchronizeAtStartupMode.WIKI);
        when(this.entityReferenceSerializer.serialize(new WikiReference("mywiki"))).thenReturn("wiki:mywiki");

        when(this.configuration.synchronizeAtStartup()).thenReturn(false);
        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("mywiki"), null, null);
        verify(this.solrIndexer, never()).startIndex(any());

        when(this.configuration.synchronizeAtStartup()).thenReturn(true);
        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("mywiki"), null, null);
        IndexerRequest indexerRequest = new IndexerRequest();
        indexerRequest.setRootReference(new WikiReference("mywiki"));
        indexerRequest.setId(List.of("solr", "indexer", "wiki:mywiki"));
        verify(this.solrIndexer).startIndex(indexerRequest);
    }

    @Test
    void onEventWikiReadyForFarm() throws SolrIndexerException
    {
        when(this.configuration.synchronizeAtStartupMode()).thenReturn(SolrConfiguration.SynchronizeAtStartupMode.FARM);

        when(this.configuration.synchronizeAtStartup()).thenReturn(false);
        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("foo"), null, null);
        verify(this.solrIndexer, never()).startIndex(any());

        when(this.configuration.synchronizeAtStartup()).thenReturn(true);
        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("foo"), null, null);
        verify(this.solrIndexer, never()).startIndex(any());
    }

    @Test
    void onEventApplicationReadyForWiki() throws SolrIndexerException, WikiManagerException
    {
        when(this.configuration.synchronizeAtStartupMode()).thenReturn(SolrConfiguration.SynchronizeAtStartupMode.WIKI);
        WikiReference wikiReference = new WikiReference("foo");
        WikiDescriptor wikiDescriptor = mock(WikiDescriptor.class);
        when(this.wikiDescriptorManager.getMainWikiDescriptor()).thenReturn(wikiDescriptor);
        when(wikiDescriptor.getReference()).thenReturn(wikiReference);
        when(this.entityReferenceSerializer.serialize(wikiReference)).thenReturn("wiki:foo");

        when(this.configuration.synchronizeAtStartup()).thenReturn(false);
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        verify(this.solrIndexer, never()).startIndex(any());

        when(this.configuration.synchronizeAtStartup()).thenReturn(true);
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);

        IndexerRequest indexerRequest = new IndexerRequest();
        indexerRequest.setRootReference(wikiReference);
        indexerRequest.setId(List.of("solr", "indexer", "wiki:foo"));
        verify(this.solrIndexer).startIndex(indexerRequest);
    }

    @Test
    void onEventRemoteWikiReadyWithEmbeddedSolr() throws SolrIndexerException
    {
        // Each instance has its own index: the remote events are handled as before
        when(this.configuration.synchronizeAtStartupMode()).thenReturn(SolrConfiguration.SynchronizeAtStartupMode.WIKI);
        when(this.configuration.synchronizeAtStartup()).thenReturn(true);
        when(this.entityReferenceSerializer.serialize(new WikiReference("mywiki"))).thenReturn("wiki:mywiki");
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);

        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("mywiki"), null, null);

        verify(this.solrIndexer).startIndex(wikiRequest("mywiki"));
        verify(this.singletonManager, never()).isSingletonNode();
    }

    @Test
    void onEventApplicationReadyForFarmWithRemoteSolrOnSingletonNode() throws SolrIndexerException
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("xwiki"), null, null);
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("foo"), null, null);

        verify(this.solrIndexer).startIndex(farmRequest());

        // Losing the singleton node role does not stop the synchronization
        notifySingletonNodeChanged(false);

        verify(this.solrIndexer).startIndex(any());
    }

    @Test
    void onEventApplicationReadyForWikiWithRemoteSolrOnSingletonNode() throws Exception
    {
        // One job for the whole farm whatever the mode
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.WIKI);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        this.solrIndexInitializeListener.onEvent(new WikiReadyEvent("foo"), null, null);

        verify(this.solrIndexer).startIndex(farmRequest());
        verify(this.solrIndexer, times(1)).startIndex(any());
        verify(this.wikiDescriptorManager, never()).getAllIds();
    }

    @Test
    void staleGainAfterStartup() throws SolrIndexerException
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        // The change was already taken into account by the application ready event
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        notifySingletonNodeChanged(true);

        verify(this.solrIndexer, timeout(TIMEOUT).times(1)).startIndex(farmRequest());
        verify(this.configuration, never()).getSynchronizeOnSingletonGainDelay();
    }

    @Test
    void onEventWithRemoteSolrOnOtherNode() throws SolrIndexerException
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.WIKI);

        // A change before the application is ready is ignored: the application ready event checks the current state
        this.solrIndexInitializeListener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, true);
        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);

        verify(this.solrIndexer, never()).startIndex(any());

        // Becoming the singleton node: catch up after the delay
        notifySingletonNodeChanged(true);

        verify(this.solrIndexer, timeout(TIMEOUT)).startIndex(farmRequest());
        assertEquals("This instance became the cluster singleton node: the Solr index will be synchronized in [0]"
            + " seconds if it is still the singleton node", this.logCapture.getMessage(0));
    }

    @Test
    void onSingletonNodeGainedThenLost() throws Exception
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        when(this.configuration.getSynchronizeOnSingletonGainDelay()).thenReturn(3600);

        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        notifySingletonNodeChanged(true);
        // Already scheduled
        notifySingletonNodeChanged(false);
        notifySingletonNodeChanged(true);
        // The previous singleton node came back before the delay: the synchronization is canceled
        notifySingletonNodeChanged(false);

        this.solrIndexInitializeListener.dispose();

        verify(this.solrIndexer, never()).startIndex(any());
        assertEquals(2, this.logCapture.size());
        this.logCapture.ignoreAllMessages();
    }

    @Test
    void onSingletonNodeGainedAndLostBeforeTheDelay() throws Exception
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        // Not the singleton node at startup, gained, lost again when the delay expires
        CountDownLatch checked = new CountDownLatch(3);
        when(this.singletonManager.isSingletonNode()).then(invocation -> {
            checked.countDown();
            return checked.getCount() == 1;
        });

        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        this.solrIndexInitializeListener.onEvent(new ClusterSingletonNodeChangedEvent(), this.singletonManager, true);

        assertTrue(checked.await(TIMEOUT, TimeUnit.MILLISECONDS));
        this.solrIndexInitializeListener.dispose();

        verify(this.solrIndexer, never()).startIndex(any());
        this.logCapture.ignoreAllMessages();
    }

    @Test
    void onEventWithRemoteSolrWithoutSynchronization() throws SolrIndexerException
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        when(this.configuration.synchronizeAtStartup()).thenReturn(false);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);

        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        notifySingletonNodeChanged(false);
        notifySingletonNodeChanged(true);

        verify(this.solrIndexer, never()).startIndex(any());
    }

    @Test
    void onStartupFailing() throws Exception
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        when(this.singletonManager.isSingletonNode()).thenReturn(true);
        doThrow(new SolrIndexerException("error")).when(this.solrIndexer).startIndex(any());

        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);

        assertEquals("Failed to start initial Solr index synchronization", this.logCapture.getMessage(0));
    }

    @Test
    void onSingletonNodeGainedFailing() throws Exception
    {
        setRemoteSolr(SolrConfiguration.SynchronizeAtStartupMode.FARM);
        doThrow(new SolrIndexerException("error")).when(this.solrIndexer).startIndex(any());

        this.solrIndexInitializeListener.onEvent(new ApplicationReadyEvent(), null, null);
        notifySingletonNodeChanged(true);

        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (this.logCapture.size() < 2 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertEquals("This instance became the cluster singleton node: the Solr index will be synchronized in [0]"
            + " seconds if it is still the singleton node", this.logCapture.getMessage(0));
        assertEquals("Failed to start the Solr index synchronization of the new cluster singleton node",
            this.logCapture.getMessage(1));
    }
}
