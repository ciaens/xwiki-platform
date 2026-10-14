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
package org.xwiki.extension.distribution.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.InstalledExtension;
import org.xwiki.extension.distribution.internal.DistributionManager.DistributionState;
import org.xwiki.extension.distribution.internal.job.DistributionJob;
import org.xwiki.extension.distribution.internal.job.DistributionJobStatus;
import org.xwiki.extension.repository.InstalledExtensionRepository;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.store.XWikiHibernateBaseStore;
import com.xpn.xwiki.store.migration.DataMigrationException;
import com.xpn.xwiki.store.migration.DataMigrationManager;
import com.xpn.xwiki.store.migration.MigrationRequiredException;

/**
 * The steps of the upgrade done by {@link UpgradeAndExitRunner}: check the database of every wiki, then run the
 * Distribution Wizard on every wiki and check its results.
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component(roles = UpgradeAndExitSteps.class)
@Singleton
public class UpgradeAndExitSteps
{
    private static final String WIKI_NAMESPACE_PREFIX = "wiki:";

    private static final String WIKIS_FAILURE = "failed to list the wikis: ";

    private static final String DATABASE_FAILURE = "database of wiki [%s]: %s";

    @Inject
    private Provider<DistributionManager> distributionManagerProvider;

    @Inject
    @Named(XWikiHibernateBaseStore.HINT)
    private Provider<DataMigrationManager> dataMigrationManagerProvider;

    @Inject
    private Provider<WikiDescriptorManager> wikisProvider;

    @Inject
    private Provider<InstalledExtensionRepository> installedRepositoryProvider;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    private Logger logger;

    /**
     * Check that the database of every wiki can be used, initializing or migrating it if needed (and enabled). The
     * initialization already migrated the databases when migrations are enabled.
     *
     * @param failures where to add the description of each failure
     * @return true if every database can be used
     */
    public boolean checkDatabases(List<String> failures)
    {
        List<String> wikiIds;
        try {
            wikiIds = getWikiIds();
        } catch (WikiManagerException e) {
            failures.add(WIKIS_FAILURE + ExceptionUtils.getRootCauseMessage(e));

            return false;
        }

        boolean success = true;
        DataMigrationManager dataMigrationManager = this.dataMigrationManagerProvider.get();
        XWikiContext xcontext = this.xcontextProvider.get();
        String currentWiki = xcontext.getWikiId();
        try {
            for (String wikiId : wikiIds) {
                xcontext.setWikiId(wikiId);
                try {
                    dataMigrationManager.checkDatabase();
                } catch (MigrationRequiredException | DataMigrationException e) {
                    failures.add(String.format(DATABASE_FAILURE, wikiId, e.getMessage()));
                    success = false;
                } catch (RuntimeException e) {
                    // For example a failure of the store while reading the version of the database
                    failures.add(String.format(DATABASE_FAILURE, wikiId, ExceptionUtils.getRootCauseMessage(e)));
                    success = false;
                }
            }
        } finally {
            xcontext.setWikiId(currentWiki);
        }

        return success;
    }

    /**
     * Run the Distribution Wizard on the main wiki then on each sub-wiki, wait for each of them and check their
     * results.
     *
     * @param failures where to add the description of each failure
     * @return true if the Distribution Wizard succeeded on every wiki
     * @throws InterruptedException when the thread is interrupted while waiting for a Distribution Wizard
     */
    public boolean upgradeDistribution(List<String> failures) throws InterruptedException
    {
        DistributionManager distributionManager = this.distributionManagerProvider.get();

        if (distributionManager.getFarmDistributionState() == DistributionState.NONE) {
            this.logger.info("No distribution to upgrade");

            return true;
        }

        List<String> wikiIds;
        try {
            wikiIds = getWikiIds();
        } catch (WikiManagerException e) {
            failures.add(WIKIS_FAILURE + ExceptionUtils.getRootCauseMessage(e));

            return false;
        }

        int failureCount = failures.size();

        // The job of the main wiki may have been started already
        DistributionJob farmJob = distributionManager.getFarmJob();
        if (farmJob == null) {
            farmJob = distributionManager.startFarmJob();
        }
        String mainWikiId = wikiIds.get(0);
        checkDistributionJob(farmJob, mainWikiId, failures);

        // The sub-wikis after the main wiki, since their extensions often depend on extensions of the main wiki
        for (String wikiId : wikiIds) {
            if (!Objects.equals(wikiId, mainWikiId)) {
                checkDistributionJob(distributionManager.startWikiJob(wikiId, false), wikiId, failures);
            }
        }

        return failures.size() == failureCount;
    }

    private List<String> getWikiIds() throws WikiManagerException
    {
        WikiDescriptorManager wikis = this.wikisProvider.get();

        // The main wiki first
        List<String> wikiIds = new ArrayList<>();
        wikiIds.add(wikis.getMainWikiId());
        for (String wikiId : wikis.getAllIds()) {
            if (!wikis.isMainWiki(wikiId)) {
                wikiIds.add(wikiId);
            }
        }

        return wikiIds;
    }

    private void checkDistributionJob(DistributionJob job, String wikiId, List<String> failures)
        throws InterruptedException
    {
        if (job == null) {
            failures.add(String.format("the Distribution Wizard of wiki [%s] could not be started", wikiId));

            return;
        }

        job.join();

        DistributionJobStatus status = job.getStatus();
        if (status.getError() != null) {
            failures.add(String.format("the Distribution Wizard of wiki [%s] failed: %s", wikiId,
                ExceptionUtils.getRootCauseMessage(status.getError())));

            return;
        }

        ExtensionId expectedUI = status.getDistributionExtensionUI();
        if (expectedUI != null) {
            InstalledExtension installedUI = this.installedRepositoryProvider.get()
                .getInstalledExtension(expectedUI.getId(), WIKI_NAMESPACE_PREFIX + wikiId);
            if (installedUI == null || !Objects.equals(installedUI.getId().getVersion(), expectedUI.getVersion())) {
                failures.add(String.format("wiki [%s] has [%s] instead of [%s]", wikiId,
                    installedUI != null ? installedUI.getId() : "no user interface", expectedUI));
            }
        }
    }
}
