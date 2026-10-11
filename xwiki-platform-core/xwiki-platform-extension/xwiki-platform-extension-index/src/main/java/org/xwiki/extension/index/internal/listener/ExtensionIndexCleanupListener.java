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
package org.xwiki.extension.index.internal.listener;

import java.io.IOException;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.index.internal.ExtensionIndexClusterConfiguration;
import org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer;
import org.xwiki.extension.index.internal.ExtensionIndexSolrUtil;
import org.xwiki.extension.repository.CoreExtensionRepository;
import org.xwiki.extension.repository.InstalledExtensionRepository;
import org.xwiki.observation.event.AbstractClusterSingletonListener;
import org.xwiki.observation.event.ApplicationStartedEvent;
import org.xwiki.observation.event.Event;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.SolrUtils;

import static org.xwiki.extension.InstalledExtension.FIELD_INSTALLED_NAMESPACES;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.IS_CORE_EXTENSION;
import static org.xwiki.search.solr.AbstractSolrCoreInitializer.SOLR_FIELD_ID;

/**
 * Cleanup the extension index of entries related to artifacts that are not available anymore (e.g., upgrade or manual
 * manipulation of the directories storing jar files).
 * <p>
 * When the extension index is shared by the members of the cluster (see
 * {@link ExtensionIndexClusterConfiguration#isSingletonNodeRequired()}), the cleanup runs only when the cluster
 * singleton node starts: the other members might know different extensions (for example during a rolling upgrade). It
 * does not run when another member becomes the singleton node later, since that member's extensions did not change and
 * it might not know all the extensions installed through the previous singleton node.
 *
 * @version $Id$
 * @since 15.9RC1
 */
@Component
@Singleton
@Named(ExtensionIndexCleanupListener.ID)
public class ExtensionIndexCleanupListener extends AbstractClusterSingletonListener
{
    /**
     * The hint of this component.
     */
    public static final String ID = "ExtensionIndexCleanupListener";

    @Inject
    private CoreExtensionRepository coreExtensionRepository;

    @Inject
    private InstalledExtensionRepository installedExtensionRepository;

    @Inject
    private Solr solr;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private ExtensionIndexSolrUtil extensionIndexSolrUtil;

    @Inject
    private ExtensionIndexClusterConfiguration clusterConfiguration;

    @Inject
    private Logger logger;

    /**
     * Default constructor.
     */
    public ExtensionIndexCleanupListener()
    {
        super(ID, new ApplicationStartedEvent());
    }

    @Override
    protected boolean isSingletonNodeRequired()
    {
        return this.clusterConfiguration.isSingletonNodeRequired();
    }

    @Override
    protected void processSingletonEvent(Event event, Object source, Object data)
    {
        startCleanup();
    }

    private void startCleanup()
    {
        Thread thread = new Thread(this::proceed);
        thread.setName(ID);
        thread.setPriority(thread.getPriority() - 2);
        thread.start();
    }

    /**
     * This method is package-protected for testing purpose. It is not advised to use it outside of the current class.
     */
    void proceed()
    {
        try {
            SolrClient client = this.solr.getClient(ExtensionIndexSolrCoreInitializer.NAME);
            SolrQuery solrQuery = new SolrQuery()
                .setFields(SOLR_FIELD_ID, IS_CORE_EXTENSION, FIELD_INSTALLED_NAMESPACES);
            int batchSize = 1000;
            QueryResponse search = client.query(solrQuery.setRows(batchSize).setStart(0));

            while (!search.getResults().isEmpty()) {
                for (SolrDocument doc : search.getResults()) {
                    String id = this.solrUtils.getId(doc);
                    ExtensionId extensionId = this.extensionIndexSolrUtil.fromSolrId(id);
                    // Search for extensions that can be found in the index, but that are not found in the extension 
                    // stores. 
                    if (cleanCoreExtension(doc, extensionId)
                        || cleanInstalledExtension(doc, extensionId))
                    {
                        client.deleteById(id);
                        this.logger.info("Remove outdated extension [{}] from the solr index", id);
                    }
                }
                search = client.query(solrQuery.setStart(solrQuery.getStart() + batchSize));
            }

            client.commit();
        } catch (SolrException e) {
            this.logger.error("Failed to get a solr client for the [{}] index.",
                ExtensionIndexSolrCoreInitializer.NAME, e);
        } catch (SolrServerException | IOException e) {
            this.logger.error("Failed to perform a solr query.", e);
        }
    }

    private boolean cleanCoreExtension(SolrDocument doc, ExtensionId extensionId)
    {
        // Return true if the extension is indexed as a core extension, but cannot be found in the core extensions 
        // store 
        boolean isCoreExtension = Objects.equals(doc.getFieldValue(IS_CORE_EXTENSION), true);
        return isCoreExtension && !this.coreExtensionRepository.exists(extensionId);
    }

    private boolean cleanInstalledExtension(SolrDocument doc, ExtensionId extensionId)
    {
        // Return true if the extension is installed (i.e., it's indexed as present in at least one namespace), but 
        // cannot be found in the installed extension repository.
        boolean isInstalled = CollectionUtils.isNotEmpty(doc.getFieldValues(FIELD_INSTALLED_NAMESPACES));
        return isInstalled && !this.installedExtensionRepository.exists(extensionId);
    }
}
