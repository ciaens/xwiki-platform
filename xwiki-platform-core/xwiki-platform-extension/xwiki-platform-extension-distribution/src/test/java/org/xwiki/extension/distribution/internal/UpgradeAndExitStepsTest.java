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

import jakarta.inject.Named;
import jakarta.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.InstalledExtension;
import org.xwiki.extension.distribution.internal.DistributionManager.DistributionState;
import org.xwiki.extension.distribution.internal.job.DefaultDistributionJob;
import org.xwiki.extension.distribution.internal.job.DistributionJob;
import org.xwiki.extension.distribution.internal.job.DistributionJobStatus;
import org.xwiki.extension.repository.InstalledExtensionRepository;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.store.migration.DataMigrationException;
import com.xpn.xwiki.store.migration.DataMigrationManager;
import com.xpn.xwiki.store.migration.MigrationRequiredException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link UpgradeAndExitSteps}.
 *
 * @version $Id$
 */
@ComponentTest
class UpgradeAndExitStepsTest
{
    private static final String MAIN_WIKI = "xwiki";

    private static final String SUBWIKI = "subwiki";

    private static final ExtensionId MAIN_UI =
        new ExtensionId("org.xwiki.platform:xwiki-platform-distribution-flavor-mainwiki", "18.10.0");

    private static final ExtensionId WIKI_UI =
        new ExtensionId("org.xwiki.platform:xwiki-platform-distribution-flavor-wiki", "18.10.0");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @InjectMockComponents
    private UpgradeAndExitSteps steps;

    @MockComponent
    private DistributionManager distributionManager;

    @MockComponent
    @Named("hibernate")
    private DataMigrationManager dataMigrationManager;

    @MockComponent
    private WikiDescriptorManager wikis;

    @MockComponent
    private InstalledExtensionRepository installedRepository;

    @MockComponent
    private Provider<XWikiContext> xcontextProvider;

    @Mock
    private DefaultDistributionJob farmJob;

    @Mock
    private DistributionJobStatus farmStatus;

    @Mock
    private DistributionJob wikiJob;

    @Mock
    private DistributionJobStatus wikiStatus;

    private final XWikiContext xcontext = new XWikiContext();

    /**
     * The wiki of the context each time the database is checked.
     */
    private final List<String> checkedWikis = new ArrayList<>();

    private final List<String> failures = new ArrayList<>();

    @BeforeEach
    void beforeEach() throws Exception
    {
        this.xcontext.setWikiId(MAIN_WIKI);
        when(this.xcontextProvider.get()).thenReturn(this.xcontext);
        doAnswer(invocation -> this.checkedWikis.add(this.xcontext.getWikiId())).when(this.dataMigrationManager)
            .checkDatabase();

        when(this.wikis.getMainWikiId()).thenReturn(MAIN_WIKI);
        when(this.wikis.isMainWiki(MAIN_WIKI)).thenReturn(true);
        when(this.wikis.getAllIds()).thenReturn(List.of(SUBWIKI, MAIN_WIKI));

        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.UPGRADE);
        when(this.distributionManager.getFarmJob()).thenReturn(this.farmJob);
        when(this.farmJob.getStatus()).thenReturn(this.farmStatus);
        when(this.farmStatus.getDistributionExtensionUI()).thenReturn(MAIN_UI);
        when(this.distributionManager.startWikiJob(SUBWIKI, false)).thenReturn(this.wikiJob);
        when(this.wikiJob.getStatus()).thenReturn(this.wikiStatus);
        when(this.wikiStatus.getDistributionExtensionUI()).thenReturn(WIKI_UI);

        installUI(MAIN_UI, MAIN_WIKI);
        installUI(WIKI_UI, SUBWIKI);
    }

    private void installUI(ExtensionId extensionId, String wiki)
    {
        InstalledExtension extension = mock(InstalledExtension.class);
        when(extension.getId()).thenReturn(extensionId);
        when(this.installedRepository.getInstalledExtension(extensionId.getId(), "wiki:" + wiki))
            .thenReturn(extension);
    }

    @Test
    void checkDatabases()
    {
        assertTrue(this.steps.checkDatabases(this.failures));

        // The main wiki first, and the context is restored afterwards
        assertEquals(List.of(MAIN_WIKI, SUBWIKI), this.checkedWikis);
        assertEquals(MAIN_WIKI, this.xcontext.getWikiId());
        assertEquals(List.of(), this.failures);
    }

    @Test
    void checkDatabasesWhenOneIsNotUsable() throws Exception
    {
        doAnswer(invocation -> {
            if (SUBWIKI.equals(this.xcontext.getWikiId())) {
                throw new MigrationRequiredException("outdated");
            }
            return null;
        }).when(this.dataMigrationManager).checkDatabase();

        assertFalse(this.steps.checkDatabases(this.failures));

        assertEquals(List.of("database of wiki [subwiki]: outdated"), this.failures);
        assertEquals(MAIN_WIKI, this.xcontext.getWikiId());
    }

    @Test
    void checkDatabasesWhenAllFail() throws Exception
    {
        doThrow(new DataMigrationException("newer")).when(this.dataMigrationManager).checkDatabase();

        assertFalse(this.steps.checkDatabases(this.failures));

        assertEquals(List.of("database of wiki [xwiki]: newer", "database of wiki [subwiki]: newer"), this.failures);
    }

    @Test
    void checkDatabasesWhenTheStoreFails() throws Exception
    {
        doThrow(new IllegalStateException("connection refused")).when(this.dataMigrationManager).checkDatabase();

        assertFalse(this.steps.checkDatabases(this.failures));

        assertEquals(List.of("database of wiki [xwiki]: IllegalStateException: connection refused",
            "database of wiki [subwiki]: IllegalStateException: connection refused"), this.failures);
        assertEquals(MAIN_WIKI, this.xcontext.getWikiId());
    }

    @Test
    void wikisCannotBeListed() throws Exception
    {
        when(this.wikis.getAllIds()).thenThrow(new WikiManagerException("broken"));

        assertFalse(this.steps.checkDatabases(this.failures));
        assertFalse(this.steps.upgradeDistribution(this.failures));

        assertEquals(List.of("failed to list the wikis: WikiManagerException: broken",
            "failed to list the wikis: WikiManagerException: broken"), this.failures);
        verify(this.dataMigrationManager, never()).checkDatabase();
    }

    @Test
    void upgradeDistribution() throws Exception
    {
        assertTrue(this.steps.upgradeDistribution(this.failures));

        assertEquals(List.of(), this.failures);
        verify(this.distributionManager, never()).startFarmJob();
        verify(this.farmJob).join();
        verify(this.wikiJob).join();
        verify(this.distributionManager, never()).startWikiJob(MAIN_WIKI, false);
    }

    @Test
    void noDistribution() throws Exception
    {
        when(this.distributionManager.getFarmDistributionState()).thenReturn(DistributionState.NONE);

        assertTrue(this.steps.upgradeDistribution(this.failures));

        assertEquals("No distribution to upgrade", this.logCapture.getMessage(0));
        verify(this.distributionManager, never()).getFarmJob();
        verify(this.distributionManager, never()).startWikiJob(any(), anyBoolean());
    }

    @Test
    void farmJobStartedWhenNotStartedYet() throws Exception
    {
        when(this.distributionManager.getFarmJob()).thenReturn(null);
        when(this.distributionManager.startFarmJob()).thenReturn(this.farmJob);

        assertTrue(this.steps.upgradeDistribution(this.failures));

        verify(this.distributionManager).startFarmJob();
        verify(this.farmJob).join();
    }

    @Test
    void farmJobCannotStart() throws Exception
    {
        when(this.distributionManager.getFarmJob()).thenReturn(null);

        assertFalse(this.steps.upgradeDistribution(this.failures));

        assertEquals(List.of("the Distribution Wizard of wiki [xwiki] could not be started"), this.failures);
        // The sub-wikis are upgraded anyway
        verify(this.wikiJob).join();
    }

    @Test
    void wikiJobCannotStart() throws Exception
    {
        when(this.distributionManager.startWikiJob(SUBWIKI, false)).thenReturn(null);

        assertFalse(this.steps.upgradeDistribution(this.failures));

        assertEquals(List.of("the Distribution Wizard of wiki [subwiki] could not be started"), this.failures);
    }

    @Test
    void distributionWizardFailed() throws Exception
    {
        when(this.wikiStatus.getError()).thenReturn(new RuntimeException("failed"));

        assertFalse(this.steps.upgradeDistribution(this.failures));

        assertEquals(List.of("the Distribution Wizard of wiki [subwiki] failed: RuntimeException: failed"),
            this.failures);
    }

    @Test
    void userInterfaceNotUpgraded() throws Exception
    {
        installUI(new ExtensionId(MAIN_UI.getId(), "18.9.0"), MAIN_WIKI);
        when(this.installedRepository.getInstalledExtension(WIKI_UI.getId(), "wiki:" + SUBWIKI)).thenReturn(null);

        assertFalse(this.steps.upgradeDistribution(this.failures));

        assertEquals(List.of(
            "wiki [xwiki] has [org.xwiki.platform:xwiki-platform-distribution-flavor-mainwiki/18.9.0] instead of"
                + " [org.xwiki.platform:xwiki-platform-distribution-flavor-mainwiki/18.10.0]",
            "wiki [subwiki] has [no user interface] instead of"
                + " [org.xwiki.platform:xwiki-platform-distribution-flavor-wiki/18.10.0]"),
            this.failures);
    }

    @Test
    void noExpectedUserInterface() throws Exception
    {
        when(this.farmStatus.getDistributionExtensionUI()).thenReturn(null);
        when(this.wikiStatus.getDistributionExtensionUI()).thenReturn(null);
        when(this.installedRepository.getInstalledExtension(any(), any())).thenReturn(null);

        assertTrue(this.steps.upgradeDistribution(this.failures));
    }

    @Test
    void previousFailuresAreNotCounted() throws Exception
    {
        this.failures.add("previous");

        assertTrue(this.steps.upgradeDistribution(this.failures));
    }
}
