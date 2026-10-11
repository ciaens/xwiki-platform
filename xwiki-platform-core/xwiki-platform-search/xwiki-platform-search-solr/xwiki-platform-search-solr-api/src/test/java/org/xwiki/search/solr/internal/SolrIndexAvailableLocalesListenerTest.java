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
import java.util.Locale;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.observation.remote.RemoteObservationManagerContext;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.search.solr.internal.api.SolrIndexer;
import org.xwiki.search.solr.internal.api.SolrInstance;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SolrIndexAvailableLocalesListener}.
 *
 * @version $Id$
 */
@ComponentTest
class SolrIndexAvailableLocalesListenerTest
{
    private static final String WIKI = "wiki";

    @InjectMockComponents
    private SolrIndexAvailableLocalesListener listener;

    @MockComponent
    private SolrInstance solrInstance;

    @MockComponent
    private SolrIndexer indexer;

    @MockComponent
    private DocumentReferenceResolver<SolrDocument> solrDocumentReferenceResolver;

    @MockComponent
    private RemoteObservationManagerContext remoteObservationManagerContext;

    @MockComponent
    private SolrConfiguration solrConfiguration;

    private XWikiContext xcontext;

    private XWiki xwiki;

    @BeforeEach
    void beforeEach()
    {
        this.xcontext = mock();
        this.xwiki = mock();
        when(this.xcontext.getWikiId()).thenReturn(WIKI);
        when(this.xcontext.getWiki()).thenReturn(this.xwiki);

        // Initialize the cache of the available locales with a local event.
        when(this.xwiki.getAvailableLocales(this.xcontext)).thenReturn(List.of(Locale.ENGLISH));
        this.listener.onEvent(new ApplicationReadyEvent(), null, this.xcontext);
    }

    @ParameterizedTest
    @CsvSource({ "embedded, false", "embedded, true", "remote, false" })
    void reindexNewLocalesForLocalEventsAndRemoteEventsWithEmbeddedSolr(String serverType, boolean remoteEvent)
        throws Exception
    {
        when(this.solrConfiguration.getServerType()).thenReturn(serverType);
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(remoteEvent);
        when(this.xwiki.getAvailableLocales(this.xcontext)).thenReturn(List.of(Locale.ENGLISH, Locale.FRENCH));

        this.listener.onEvent(new DocumentUpdatedEvent(), null, this.xcontext);

        ArgumentCaptor<SolrQuery> queryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
        ArgumentCaptor<StreamingResponseCallback> callbackCaptor =
            ArgumentCaptor.forClass(StreamingResponseCallback.class);
        verify(this.solrInstance).queryAndStreamResponse(queryCaptor.capture(), callbackCaptor.capture());
        assertEquals("doclocale:\"\"", queryCaptor.getValue().getQuery());

        SolrDocument solrDocument = new SolrDocument();
        DocumentReference documentReference = new DocumentReference(WIKI, "Space", "Page");
        when(this.solrDocumentReferenceResolver.resolve(solrDocument)).thenReturn(documentReference);
        callbackCaptor.getValue().streamSolrDocument(solrDocument);
        callbackCaptor.getValue().streamDocListInfo(1, 0, null);

        verify(this.indexer).index(documentReference, true);
        if (!remoteEvent) {
            // The configuration is only read for remote events.
            verifyNoInteractions(this.solrConfiguration);
        }
    }

    @Test
    void ignoreRemoteEventsWithRemoteSolr() throws Exception
    {
        when(this.solrConfiguration.getServerType()).thenReturn("remote");
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(true);
        when(this.xwiki.getAvailableLocales(this.xcontext)).thenReturn(List.of(Locale.ENGLISH, Locale.FRENCH));

        this.listener.onEvent(new DocumentUpdatedEvent(), null, this.xcontext);

        // The cache of the available locales is still updated: a later local event with the same locales does not
        // re-index anything.
        when(this.remoteObservationManagerContext.isRemoteState()).thenReturn(false);
        this.listener.onEvent(new DocumentUpdatedEvent(), null, this.xcontext);

        verify(this.solrInstance, never()).queryAndStreamResponse(any(), any());
        verifyNoInteractions(this.indexer);
    }
}
