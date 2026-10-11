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
package org.xwiki.extension.index.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.extension.index.internal.job.ExtensionIndexJobScheduler;
import org.xwiki.model.namespace.WikiNamespace;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.event.Event;

/**
 * Start the indexer when XWiki starts.
 * <p>
 * When the extension index is shared by the members of the cluster (see
 * {@link ExtensionIndexClusterConfiguration#isSingletonNodeRequired()}), the indexer runs only on the cluster singleton
 * node, for all the wikis (the singleton node might never load the subwikis). It's started when the application is
 * ready. When the current instance becomes the singleton node after that (the previous one might have stopped in the
 * middle of an indexing), it's started after a delay (see
 * {@link ExtensionIndexClusterConfiguration#getIndexOnSingletonGainDelay()}) and only if the current instance is still
 * the singleton node, so that a short restart of the preferred member does not trigger an indexing on another member.
 * The periodic indexing is stopped when the current instance stops being the singleton node.
 * 
 * @version $Id$
 * @since 12.10
 */
@Component
@Named(ExtensionIndexInitializerListener.NAME)
@Singleton
public class ExtensionIndexInitializerListener extends AbstractClusterSingletonListener implements Disposable
{
    /**
     * The unique name of this event listener.
     */
    public static final String NAME = "ExtensionIndexInitializerListener";

    @Inject
    private ExtensionIndexJobScheduler scheduler;

    @Inject
    private ExtensionIndexClusterConfiguration clusterConfiguration;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private Logger logger;

    /**
     * Used to delay the indexing when the current instance becomes the cluster singleton node (the thread is only
     * created when the first task is scheduled).
     */
    private final ScheduledExecutorService delayedStarter = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XWiki extension index singleton node start");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> pendingStart;

    private volatile boolean applicationReady;

    /**
     * The default constructor.
     */
    public ExtensionIndexInitializerListener()
    {
        super(NAME, new WikiReadyEvent(), new ApplicationReadyEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent && !this.remoteObservationManagerContext.isRemoteState()) {
            // Remember it even when the current instance is not the singleton node, to know if the indexer can be
            // started when the current instance becomes the singleton node
            this.applicationReady = true;
        }

        super.onEvent(event, source, data);
    }

    @Override
    protected boolean isSingletonNodeRequired()
    {
        return this.clusterConfiguration.isSingletonNodeRequired();
    }

    @Override
    protected void processSingletonEvent(Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent) {
            this.scheduler.start(isSingletonNodeRequired());
        } else if (event instanceof WikiReadyEvent wikiReadyEvent) {
            this.scheduler.initialize(new WikiNamespace(wikiReadyEvent.getWikiId()));
        }
    }

    @Override
    protected void onSingletonNodeGained()
    {
        // Before the application is ready, the indexer is started by ApplicationReadyEvent
        if (this.applicationReady) {
            synchronized (this) {
                if (this.pendingStart == null || this.pendingStart.isDone()) {
                    int delay = Math.max(this.clusterConfiguration.getIndexOnSingletonGainDelay(), 0);
                    this.logger.info("This instance became the cluster singleton node: the extensions will be indexed"
                        + " in [{}] seconds if it is still the singleton node", delay);
                    try {
                        this.pendingStart = this.delayedStarter.schedule(
                            new ExecutionContextRunnable(this::startIfSingletonNode, this.componentManager), delay,
                            TimeUnit.SECONDS);
                    } catch (RejectedExecutionException e) {
                        // The component is disposed: the instance is stopping
                        this.logger.debug("Ignoring the extension index start of a disposed listener", e);
                    }
                }
            }
        }
    }

    @Override
    protected void onSingletonNodeLost()
    {
        synchronized (this) {
            if (this.pendingStart != null) {
                this.pendingStart.cancel(false);
            }
        }

        // A running indexing job is not interrupted
        this.scheduler.stop();
    }

    private void startIfSingletonNode()
    {
        if (this.singletonManager.isSingletonNode()) {
            this.scheduler.start(true);
        }
    }

    @Override
    public void dispose()
    {
        this.delayedStarter.shutdownNow();
    }
}
