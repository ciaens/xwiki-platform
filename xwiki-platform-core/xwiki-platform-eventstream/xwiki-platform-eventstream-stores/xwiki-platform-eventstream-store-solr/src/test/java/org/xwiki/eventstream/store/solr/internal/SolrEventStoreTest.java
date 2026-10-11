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
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.eventstream.Event;
import org.xwiki.eventstream.EventStreamException;
import org.xwiki.eventstream.internal.DefaultEvent;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.XWikiSolrCore;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SolrEventStore}, with a mocked Solr.
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

    private SolrClient client = mock(SolrClient.class);

    @BeforeComponent
    void beforeComponent() throws Exception
    {
        XWikiSolrCore core = mock(XWikiSolrCore.class);
        when(core.getClient()).thenReturn(this.client);

        // Solr cannot be reached when the store is initialized, then the events core does not exist yet, then it's
        // available
        when(this.solr.getCore(EventsSolrCoreInitializer.NAME)).thenThrow(new SolrException("Solr is not reachable"))
            .thenReturn(null).thenReturn(core);
    }

    private void allowRetry()
    {
        ReflectionUtils.setFieldValue(this.store, "retryTime", System.nanoTime());
    }

    @Test
    void eventsCoreRequestedAgainAfterAFailure() throws Exception
    {
        // The initialization does not fail when Solr cannot be reached
        verify(this.solr).getCore(EventsSolrCoreInitializer.NAME);
        assertEquals("The events Solr core is not available, events cannot be stored or searched:"
            + " [SolrException: Solr is not reachable]. Check that the Solr server can be reached and that the events"
            + " core exists on it. The events core will be requested again when needed.",
            logCapture.getMessage(0));

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
            .startsWith("SolrServerException: The events Solr core could not be found"));
        verify(this.solr, times(2)).getCore(EventsSolrCoreInitializer.NAME);

        // The events core is available
        allowRetry();
        Event savedEvent = this.store.saveEvent(event).get();
        assertSame(event, savedEvent);
        verify(this.client).add(any(SolrInputDocument.class));
        verify(this.client).commit();
        assertEquals("The events Solr core is available again", logCapture.getMessage(1));

        // The events core is not requested again once it was found
        this.store.saveEvent(event).get();
        verify(this.solr, times(3)).getCore(EventsSolrCoreInitializer.NAME);
        verify(this.client, times(2)).add(any(SolrInputDocument.class));
        verify(this.client, times(2)).commit();
    }
}
