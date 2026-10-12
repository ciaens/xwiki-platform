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
package org.xwiki.eventstream.store.solr.internal;

import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.internal.DefaultEvent;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.search.solr.internal.RemoteSolr;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SolrEventStore}, with a mocked Solr (the retry rules are tested in {@code LazySolrCoreTest}).
 *
 * @version $Id$
 */
@ComponentTest
class SolrEventStoreTest
{
    // Static to also capture the logs of the initialization of the store, which happens before each test
    @RegisterExtension
    private static LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @InjectMockComponents
    private SolrEventStore store;

    @MockComponent
    private Solr solr;

    @MockComponent
    private SolrConfiguration solrConfiguration;

    private SolrClient client = mock(SolrClient.class);

    private XWikiSolrCore core = mock(XWikiSolrCore.class);

    @BeforeComponent("eventsCoreRequestedAgainAfterAFailure")
    void beforeEventsCoreRequestedAgainAfterAFailure() throws Exception
    {
        when(this.core.getClient()).thenReturn(this.client);
        when(this.solrConfiguration.getServerType()).thenReturn(RemoteSolr.TYPE);

        // The remote Solr server cannot be reached when the store is initialized, then the events core does not exist
        // yet, then it's available
        when(this.solr.getCore(EventsSolrCoreInitializer.NAME)).thenThrow(new SolrException("Solr is not reachable"))
            .thenReturn(null).thenReturn(this.core);
    }

    @BeforeComponent("embeddedEventsCoreFailure")
    void beforeEmbeddedEventsCoreFailure() throws Exception
    {
        when(this.solrConfiguration.getServerType()).thenReturn("embedded");
        // The events core is available for the store injected in the test
        when(this.solr.getCore(EventsSolrCoreInitializer.NAME)).thenReturn(this.core);
    }

    private void allowRetry() throws IllegalAccessException
    {
        // Skip the 10 seconds retry delay of the store's LazySolrCore (xwiki-platform-search-solr-api) by resetting its
        // private retryTime field: this test breaks if that field is renamed. The delay itself is tested in
        // LazySolrCoreTest.
        ReflectionUtils.setFieldValue(FieldUtils.readField(this.store, "eventsCore", true), "retryTime",
            System.nanoTime());
    }

    @Test
    void eventsCoreRequestedAgainAfterAFailure() throws Exception
    {
        // The initialization does not fail when Solr cannot be reached
        verify(this.solr).getCore(EventsSolrCoreInitializer.NAME);
        assertEquals("The Solr core [events] is not available: [SolrException: Solr is not reachable]. Check that the"
            + " Solr server can be reached and that the core exists on it. The core will be requested again when"
            + " needed.", logCapture.getMessage(0));

        DefaultEvent event = new DefaultEvent();
        event.setId("id");

        // Solr is not contacted again right after a failure
        ExecutionException exception = assertThrows(ExecutionException.class, () -> this.store.saveEvent(event).get());
        assertInstanceOf(EventStreamException.class, exception.getCause());
        assertEquals("SolrException: Solr is not reachable", ExceptionUtils.getRootCauseMessage(exception));
        verify(this.solr).getCore(EventsSolrCoreInitializer.NAME);

        // Solr can be reached but the events core does not exist yet
        allowRetry();
        exception = assertThrows(ExecutionException.class, () -> this.store.saveEvent(event).get());
        assertTrue(ExceptionUtils.getRootCauseMessage(exception)
            .startsWith("SolrException: The Solr core [events] could not be found"));
        verify(this.solr, times(2)).getCore(EventsSolrCoreInitializer.NAME);

        // The events core is available
        allowRetry();
        Event savedEvent = this.store.saveEvent(event).get();
        assertSame(event, savedEvent);
        verify(this.client).add(any(SolrInputDocument.class));
        verify(this.client).commit();
        assertEquals("The Solr core [events] is available again", logCapture.getMessage(1));

        // The events core is not requested again once it was found
        this.store.saveEvent(event).get();
        verify(this.solr, times(3)).getCore(EventsSolrCoreInitializer.NAME);
        verify(this.client, times(2)).add(any(SolrInputDocument.class));
        verify(this.client, times(2)).commit();
    }

    @Test
    void embeddedEventsCoreFailure() throws Exception
    {
        int logCount = logCapture.size();

        // With the embedded Solr, an events core which cannot be loaded is a configuration error: the initialization
        // fails, as for the search core.
        SolrException failure = new SolrException("Failed to load the core");
        doThrow(failure).when(this.solr).getCore(EventsSolrCoreInitializer.NAME);
        // Built by hand because the component manager would fail to inject it. Only the fields used before the store
        // thread is started are set: any later step of the initialization would fail on the missing ones, so this also
        // checks that no store thread is started when the events core cannot be loaded.
        SolrEventStore embeddedStore = new SolrEventStore();
        Logger logger = mock(Logger.class);
        ReflectionUtils.setFieldValue(embeddedStore, "solr", this.solr);
        ReflectionUtils.setFieldValue(embeddedStore, "solrConfiguration", this.solrConfiguration);
        ReflectionUtils.setFieldValue(embeddedStore, "logger", logger);

        InitializationException exception = assertThrows(InitializationException.class, embeddedStore::initialize);
        assertEquals("Failed to get the events Solr core", exception.getMessage());
        assertSame(failure, exception.getCause());

        // Nothing is logged, the failure is reported to the component manager.
        verifyNoInteractions(logger);
        assertEquals(logCount, logCapture.size());
    }
}
