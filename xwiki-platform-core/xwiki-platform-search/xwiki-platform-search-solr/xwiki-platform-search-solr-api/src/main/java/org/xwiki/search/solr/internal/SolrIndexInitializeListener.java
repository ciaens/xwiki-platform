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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.event.Event;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.search.solr.internal.api.SolrIndexer;
import org.xwiki.search.solr.internal.api.SolrIndexerException;
import org.xwiki.search.solr.internal.job.IndexerRequest;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

/**
 * Automatically start synchronization at startup.
 * <p>
 * When the Solr server is shared by all the members of the cluster ({@code solr.type=remote}), the synchronization runs
 * only on the cluster singleton node, for the whole farm (whatever {@code solr.synchronizeAtStartupMode} says, the
 * singleton node might never load the subwikis): at startup, and when the current instance becomes the singleton node
 * (because the previous one stopped, possibly in the middle of a synchronization), after a delay and only if it's
 * still the singleton node. Otherwise each instance synchronizes its own index at startup.
 * 
 * @version $Id$
 * @since 5.1RC1
 */
@Component
@Named("solr.initializer")
@Singleton
public class SolrIndexInitializeListener extends AbstractClusterSingletonListener implements Disposable
{
    private static final List<String> REQUEST_DEFAULT_ID = Arrays.asList("solr", "indexer");

    /**
     * Logging framework.
     */
    @Inject
    protected Logger logger;

    /**
     * The solr index.
     * <p>
     * Lazily initialize the {@link SolrIndexer} to not initialize it too early.
     */
    @Inject
    private Provider<SolrIndexer> solrIndexer;

    @Inject
    private SolrConfiguration configuration;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private ComponentManager componentManager;

    /**
     * Used to delay the synchronization when the current instance becomes the cluster singleton node (the thread is
     * only created when the first task is scheduled).
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XWiki Solr singleton node synchronization");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> pendingSynchronization;

    /**
     * Listen to the events that trigger the index synchronization.
     */
    public SolrIndexInitializeListener()
    {
        super(SolrIndexInitializeListener.class.getName(), new ApplicationReadyEvent(), new WikiReadyEvent());
    }

    @Override
    protected boolean isSingletonNodeRequired()
    {
        // Each instance has its own embedded index, but a remote index is shared by all the members of the cluster
        return RemoteSolr.TYPE.equals(this.configuration.getServerType());
    }

    @Override
    protected void processSingletonEvent(Event event, Object source, Object data)
    {
        if (this.configuration.synchronizeAtStartup()) {
            try {
                if (isSingletonNodeRequired()) {
                    // A single job for the whole farm: the subwikis are loaded on the first request reaching them,
                    // which might never happen on the singleton node (for example an instance dedicated to
                    // background tasks)
                    if (event instanceof ApplicationReadyEvent) {
                        startIndex(null);
                    }
                } else {
                    synchronize(event);
                }
            } catch (SolrIndexerException | WikiManagerException e) {
                this.logger.error("Failed to start initial Solr index synchronization", e);
            }
        }
    }

    @Override
    protected void onSingletonNodeGained()
    {
        if (this.configuration.synchronizeAtStartup()) {
            // The previous singleton node might have stopped before (or while) synchronizing the index, but it might
            // also come back soon (restart of the preferred member): wait a bit before synchronizing
            synchronized (this) {
                if (this.pendingSynchronization == null || this.pendingSynchronization.isDone()) {
                    int delay = this.configuration.getSynchronizeOnSingletonGainDelay();
                    this.logger.info("This instance became the cluster singleton node: the Solr index will be"
                        + " synchronized in [{}] seconds if it is still the singleton node", Math.max(delay, 0));
                    try {
                        this.pendingSynchronization = this.scheduler.schedule(
                            new ExecutionContextRunnable(this::synchronizeIfSingletonNode, this.componentManager),
                            delay, TimeUnit.SECONDS);
                    } catch (RejectedExecutionException e) {
                        // The component is disposed: the instance is stopping
                        this.logger.debug("Ignoring the singleton node synchronization of a disposed listener", e);
                    }
                }
            }
        }
    }

    @Override
    protected void onSingletonNodeLost()
    {
        synchronized (this) {
            if (this.pendingSynchronization != null) {
                this.pendingSynchronization.cancel(false);
            }
        }
    }

    private void synchronizeIfSingletonNode()
    {
        if (this.singletonManager.isSingletonNode() && this.configuration.synchronizeAtStartup()) {
            try {
                startIndex(null);
            } catch (SolrIndexerException e) {
                this.logger.error("Failed to start the Solr index synchronization of the new cluster singleton node",
                    e);
            }
        }
    }

    private void synchronize(Event event) throws SolrIndexerException, WikiManagerException
    {
        SolrConfiguration.SynchronizeAtStartupMode startupMode = this.configuration.synchronizeAtStartupMode();

        if (event instanceof ApplicationReadyEvent) {
            if (startupMode == SolrConfiguration.SynchronizeAtStartupMode.FARM) {
                startIndex(null);
            } else if (startupMode == SolrConfiguration.SynchronizeAtStartupMode.WIKI) {
                // Startup of the application: start indexing the main wiki.
                startIndex(this.wikiDescriptorManager.getMainWikiDescriptor().getReference());
            }
        } else if (startupMode == SolrConfiguration.SynchronizeAtStartupMode.WIKI
            && event instanceof WikiReadyEvent wikiReadyEvent) {
            startIndex(new WikiReference(wikiReadyEvent.getWikiId()));
        }
    }

    /**
     * @param wikiReference the wiki to synchronize, {@code null} for the whole farm
     */
    private void startIndex(WikiReference wikiReference) throws SolrIndexerException
    {
        IndexerRequest request = new IndexerRequest();
        List<String> requestId = new ArrayList<>(REQUEST_DEFAULT_ID);

        if (wikiReference != null) {
            request.setRootReference(wikiReference);
            requestId.add(this.entityReferenceSerializer.serialize(wikiReference));
        }

        request.setId(requestId);

        // Remove invalid entries after the synchronization
        request.setCleanInvalid(true);

        this.solrIndexer.get().startIndex(request);
    }

    @Override
    public void dispose()
    {
        this.scheduler.shutdownNow();
    }
}
