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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.XWikiSolrCore;
import org.xwiki.search.solr.internal.api.SolrConfiguration;

/**
 * Gives access to a Solr core which can be resolved the first time it's needed instead of when the component using it
 * is initialized.
 * <p>
 * With a remote Solr server, a server which cannot be reached while XWiki starts must not make the components using one
 * of its cores (and the components depending on them, like event listeners) unavailable until XWiki is restarted. After
 * a failed attempt (including the one made during the initialization of the component), the core is requested again
 * when needed, but not more than once every 10 seconds; in between, and while another thread is already requesting
 * the core, the operations fail immediately with the last failure instead of each waiting for the Solr server. A
 * warning is logged when the core becomes unavailable and an information message when it is available again.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
public class LazySolrCore
{
    /**
     * The default minimum time between two attempts to get the core after a failure.
     */
    private static final long DEFAULT_RETRY_DELAY = TimeUnit.SECONDS.toNanos(10);

    private final String name;

    private final Supplier<Solr> solrSupplier;

    private final Logger logger;

    private final long retryDelay;

    private final Lock lock = new ReentrantLock();

    private volatile XWikiSolrCore core;

    /**
     * The last failure to get the core, {@code null} if the core was never requested or if the last attempt succeeded.
     */
    private volatile SolrException failure;

    /**
     * The time ({@link System#nanoTime()}) before which the core is not requested again after a failure.
     */
    private volatile long retryTime;

    /**
     * @param name the name of the core, for example {@code search}
     * @param solrSupplier supplies the {@link Solr} component, called only when the core is requested so that a Solr
     *            component which cannot be initialized yet (because the Solr server cannot be reached for example) is
     *            requested again later
     * @param logger the logger of the component using the core, used to report when the core becomes unavailable or
     *            available again
     */
    public LazySolrCore(String name, Supplier<Solr> solrSupplier, Logger logger)
    {
        this(name, solrSupplier, logger, DEFAULT_RETRY_DELAY);
    }

    /**
     * @param name the name of the core, for example {@code search}
     * @param solrSupplier supplies the {@link Solr} component
     * @param logger the logger of the component using the core
     * @param retryDelay the minimum time in nanoseconds between two attempts to get the core after a failure
     */
    LazySolrCore(String name, Supplier<Solr> solrSupplier, Logger logger, long retryDelay)
    {
        this.name = name;
        this.solrSupplier = solrSupplier;
        this.logger = logger;
        this.retryDelay = retryDelay;
    }

    /**
     * Gets the core during the initialization of the component using it, strictly or tolerantly depending on the type
     * of Solr server. With a remote Solr server, an unavailable core can be a server which cannot be reached yet: the
     * failure is logged and the core is requested again when needed (see {@link #get()}). With any other type of Solr
     * server (the embedded Solr), a core which cannot be loaded is a configuration error, not a temporary condition:
     * the failure is thrown to the caller, without being logged.
     *
     * @param configuration the Solr configuration, to know the type of Solr server
     * @return the core, or {@code null} if a remote Solr server is used and the core is not available
     * @throws SolrException when a Solr server other than a remote one is used and the core cannot be loaded
     */
    public XWikiSolrCore initialize(SolrConfiguration configuration) throws SolrException
    {
        if (RemoteSolr.TYPE.equals(configuration.getServerType())) {
            return tryInitialize();
        }

        return initialize();
    }

    /**
     * Gets the core now, during the initialization of the component using it, and fails if it's not available. Used
     * when an unavailable core is a configuration error rather than a temporary condition (with the embedded Solr for
     * example). The failure is not logged, it's up to the caller to report it. Components use
     * {@link #initialize(SolrConfiguration)}, this method is visible to the tests only.
     *
     * @return the core, never {@code null}
     * @throws SolrException when the core cannot be found or the Solr server cannot be reached
     */
    XWikiSolrCore initialize() throws SolrException
    {
        this.lock.lock();
        try {
            if (this.core == null) {
                this.core = getCore();
            }

            return this.core;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Tries to get the core now, during the initialization of the component using it, so that it's created, migrated
     * and initialized during the startup when the Solr server is available. A failure is logged and handled like any
     * other failed attempt: the core is requested again when needed, after the retry delay. Components use
     * {@link #initialize(SolrConfiguration)}, this method is visible to the tests only.
     *
     * @return the core, or {@code null} if it is not available
     */
    XWikiSolrCore tryInitialize()
    {
        try {
            return get();
        } catch (SolrException e) {
            // Already logged
            return null;
        }
    }

    /**
     * Gets the core, requesting it from Solr if it was not resolved yet. After a failure, the core is not requested
     * again before the retry delay, and while another thread is requesting it: the last failure is reported instead.
     *
     * @return the core, never {@code null}
     * @throws SolrException when the core cannot be found or the Solr server cannot be reached
     */
    public XWikiSolrCore get() throws SolrException
    {
        XWikiSolrCore solrCore = this.core;
        if (solrCore != null) {
            return solrCore;
        }

        if (!this.lock.tryLock()) {
            // Another thread is requesting the core: don't wait for it (it can take as long as the connection timeout)
            // if a failure is already known.
            SolrException lastFailure = this.failure;
            if (lastFailure != null) {
                throw new SolrException(lastFailure.getMessage(), lastFailure);
            }

            this.lock.lock();
        }

        try {
            return resolve();
        } finally {
            this.lock.unlock();
        }
    }

    private XWikiSolrCore resolve() throws SolrException
    {
        if (this.core == null) {
            SolrException lastFailure = this.failure;
            if (lastFailure != null && System.nanoTime() - this.retryTime < 0) {
                throw new SolrException(lastFailure.getMessage(), lastFailure);
            }

            XWikiSolrCore solrCore;
            try {
                solrCore = getCore();
            } catch (SolrException e) {
                onFailure(e);

                throw e;
            }

            if (lastFailure != null) {
                this.logger.info("The Solr core [{}] is available again", this.name);

                this.failure = null;
            }

            this.core = solrCore;
        }

        return this.core;
    }

    private XWikiSolrCore getCore() throws SolrException
    {
        Solr solr;
        try {
            solr = this.solrSupplier.get();
        } catch (RuntimeException e) {
            // The component Provider reports a component which fails to initialize as a RuntimeException.
            throw new SolrException("Failed to get the Solr component", e);
        }

        XWikiSolrCore solrCore = solr.getCore(this.name);
        if (solrCore == null) {
            throw new SolrException(String.format("The Solr core [%s] could not be found: with a remote Solr server it"
                + " must be created beforehand, with the name [<corePrefix>_%s_<Solr major version>]", this.name,
                this.name));
        }

        return solrCore;
    }

    private void onFailure(SolrException e)
    {
        if (this.failure == null) {
            this.logger.warn("The Solr core [{}] is not available: [{}]. Check that the Solr server can be reached and"
                + " that the core exists on it. The core will be requested again when needed.", this.name,
                ExceptionUtils.getRootCauseMessage(e));
        } else {
            this.logger.debug("The Solr core [{}] is still not available: [{}]", this.name,
                ExceptionUtils.getRootCauseMessage(e));
        }

        // Set the retry time first: the failure is read without the lock.
        this.retryTime = System.nanoTime() + this.retryDelay;
        this.failure = e;
    }
}
