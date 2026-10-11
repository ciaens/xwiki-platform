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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.XWikiSolrCore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LazySolrCore}.
 *
 * @version $Id$
 */
class LazySolrCoreTest
{
    private static final String CORE_NAME = "search";

    private static final String UNAVAILABLE_MESSAGE = "The Solr core [{}] is not available: [{}]. Check that the Solr"
        + " server can be reached and that the core exists on it. The core will be requested again when needed.";

    private static final String AVAILABLE_MESSAGE = "The Solr core [{}] is available again";

    private final Solr solr = mock();

    private final XWikiSolrCore core = mock();

    private final Logger logger = mock();

    private LazySolrCore newLazySolrCore(long retryDelay)
    {
        return new LazySolrCore(CORE_NAME, () -> this.solr, this.logger, retryDelay);
    }

    @Test
    void getResolvesTheCoreOnce() throws Exception
    {
        when(this.solr.getCore(CORE_NAME)).thenReturn(this.core);

        LazySolrCore lazyCore = new LazySolrCore(CORE_NAME, () -> this.solr, this.logger);

        assertSame(this.core, lazyCore.get());
        assertSame(this.core, lazyCore.get());

        verify(this.solr).getCore(CORE_NAME);
        verifyNoInteractions(this.logger);
    }

    @Test
    void constructorDoesNotResolveTheSolrComponent()
    {
        Supplier<Solr> solrSupplier = mock();

        new LazySolrCore(CORE_NAME, solrSupplier, this.logger);

        verify(solrSupplier, never()).get();
    }

    @Test
    void tryInitializeFailureDelaysTheNextAttempt() throws Exception
    {
        SolrException failure = new SolrException("Connection refused");
        when(this.solr.getCore(CORE_NAME)).thenThrow(failure).thenReturn(this.core);

        LazySolrCore lazyCore = newLazySolrCore(TimeUnit.HOURS.toNanos(1));

        // The failure during the initialization is logged but not thrown.
        assertNull(lazyCore.tryInitialize());
        verify(this.logger).warn(UNAVAILABLE_MESSAGE, CORE_NAME, "SolrException: Connection refused");

        // Like any other failure, it delays the next attempt.
        SolrException exception = assertThrows(SolrException.class, lazyCore::get);
        assertSame(failure, exception.getCause());
        verify(this.solr).getCore(CORE_NAME);
    }

    @Test
    void tryInitializeFailureAndRetry() throws Exception
    {
        when(this.solr.getCore(CORE_NAME)).thenThrow(new SolrException("Connection refused")).thenReturn(this.core);

        LazySolrCore lazyCore = newLazySolrCore(0);

        assertNull(lazyCore.tryInitialize());
        assertSame(this.core, lazyCore.get());
        verify(this.logger).info(AVAILABLE_MESSAGE, CORE_NAME);
        verify(this.solr, times(2)).getCore(CORE_NAME);
    }

    @Test
    void initializeWhenAvailable() throws Exception
    {
        when(this.solr.getCore(CORE_NAME)).thenReturn(this.core);

        LazySolrCore lazyCore = newLazySolrCore(TimeUnit.HOURS.toNanos(1));

        assertSame(this.core, lazyCore.tryInitialize());
        assertSame(this.core, lazyCore.initialize());
        assertSame(this.core, lazyCore.get());
        verify(this.solr).getCore(CORE_NAME);
        verifyNoInteractions(this.logger);
    }

    @Test
    void initializeFailure() throws Exception
    {
        SolrException failure = new SolrException("Failed to load the core");
        when(this.solr.getCore(CORE_NAME)).thenThrow(failure).thenReturn(null);

        LazySolrCore lazyCore = newLazySolrCore(TimeUnit.HOURS.toNanos(1));

        // The strict initialization reports the failure to the caller, without logging it.
        assertSame(failure, assertThrows(SolrException.class, lazyCore::initialize));
        SolrException exception = assertThrows(SolrException.class, lazyCore::initialize);
        assertEquals("The Solr core [search] could not be found: with a remote Solr server it must be created"
            + " beforehand, with the name [<corePrefix>_search_<Solr major version>]", exception.getMessage());
        verifyNoInteractions(this.logger);
    }

    @Test
    void concurrentCallerFailsFast() throws Exception
    {
        SolrException failure = new SolrException("Connection refused");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(this.solr.getCore(CORE_NAME)).thenThrow(failure).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return this.core;
        });

        LazySolrCore lazyCore = newLazySolrCore(0);
        assertThrows(SolrException.class, lazyCore::get);

        // A thread is requesting the core again (and waits for the Solr server).
        CompletableFuture<XWikiSolrCore> attempt = CompletableFuture.supplyAsync(() -> {
            try {
                return lazyCore.get();
            } catch (SolrException e) {
                throw new CompletionException(e);
            }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        // Another caller does not wait for it: it gets the last failure.
        SolrException exception = assertThrows(SolrException.class, lazyCore::get);
        assertSame(failure, exception.getCause());

        release.countDown();
        assertSame(this.core, attempt.get(10, TimeUnit.SECONDS));
        assertSame(this.core, lazyCore.get());
        verify(this.solr, times(2)).getCore(CORE_NAME);
    }

    @Test
    void failureDelaysTheNextAttempt() throws Exception
    {
        SolrException failure = new SolrException("Connection refused");
        when(this.solr.getCore(CORE_NAME)).thenThrow(failure);

        LazySolrCore lazyCore = newLazySolrCore(TimeUnit.HOURS.toNanos(1));

        assertSame(failure, assertThrows(SolrException.class, lazyCore::get));

        // The Solr server is not contacted again before the retry delay, the same failure is reported.
        SolrException exception = assertThrows(SolrException.class, lazyCore::get);
        assertEquals("Connection refused", exception.getMessage());
        assertSame(failure, exception.getCause());

        verify(this.solr).getCore(CORE_NAME);
        verify(this.logger).warn(UNAVAILABLE_MESSAGE, CORE_NAME, "SolrException: Connection refused");
    }

    @Test
    void retryAfterTheDelay() throws Exception
    {
        when(this.solr.getCore(CORE_NAME)).thenThrow(new SolrException("first"), new SolrException("second"))
            .thenReturn(this.core);

        LazySolrCore lazyCore = newLazySolrCore(0);

        assertThrows(SolrException.class, lazyCore::get);
        assertThrows(SolrException.class, lazyCore::get);
        assertSame(this.core, lazyCore.get());
        assertSame(this.core, lazyCore.get());

        verify(this.solr, times(3)).getCore(CORE_NAME);
        // Only one warning while the core is not available, then one information message when it's back.
        verify(this.logger).warn(eq(UNAVAILABLE_MESSAGE), any(), any());
        verify(this.logger).warn(UNAVAILABLE_MESSAGE, CORE_NAME, "SolrException: first");
        verify(this.logger).debug("The Solr core [{}] is still not available: [{}]", CORE_NAME,
            "SolrException: second");
        verify(this.logger).info(AVAILABLE_MESSAGE, CORE_NAME);
    }

    @Test
    void missingCore() throws Exception
    {
        LazySolrCore lazyCore = newLazySolrCore(0);

        SolrException exception = assertThrows(SolrException.class, lazyCore::get);
        assertEquals("The Solr core [search] could not be found: with a remote Solr server it must be created"
            + " beforehand, with the name [<corePrefix>_search_<Solr major version>]", exception.getMessage());
    }

    @Test
    void solrComponentFailure() throws Exception
    {
        RuntimeException failure = new RuntimeException("Failed to get [role = [interface org.xwiki.search.solr.Solr]]",
            new Exception("Failed to access the Solr server information"));
        LazySolrCore lazyCore = new LazySolrCore(CORE_NAME, () -> {
            throw failure;
        }, this.logger, 0);

        SolrException exception = assertThrows(SolrException.class, lazyCore::get);
        assertEquals("Failed to get the Solr component", exception.getMessage());
        assertSame(failure, exception.getCause());
        verify(this.logger).warn(UNAVAILABLE_MESSAGE, CORE_NAME,
            "Exception: Failed to access the Solr server information");
    }
}
