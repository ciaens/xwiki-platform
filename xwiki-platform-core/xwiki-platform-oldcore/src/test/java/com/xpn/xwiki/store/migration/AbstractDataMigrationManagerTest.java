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
package com.xpn.xwiki.store.migration;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import javax.inject.Named;
import javax.inject.Singleton;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.internal.store.hibernate.HibernateConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Validate the database initialization and migration paths of {@link AbstractDataMigrationManager}: the database
 * lock shared by the cluster members, the version read again once the lock is held, the outdated database read again
 * by the members which don't migrate it, and the refusal of a database newer than the code.
 *
 * @version $Id$
 */
@ComponentTest
class AbstractDataMigrationManagerTest
{
    private static final String MAIN_WIKI = "xwiki";

    private static final String SUBWIKI = "subwiki";

    private static final String NEWER_DATABASE_MESSAGE = "Database [xwiki] is in version [300], newer than the"
        + " version [200] supported by this XWiki: it was migrated by a more recent version of XWiki, or by a data"
        + " migration of an extension that is no longer installed, and could not be safely used! Downgrading XWiki"
        + " requires a new installation and the import of a XAR export (the version of the database can be forced with"
        + " xwiki.store.migration.version in xwiki.cfg, at your own risk).";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private TestDataMigrationManager manager;

    @MockComponent
    private HibernateConfiguration hibernateConfiguration;

    @MockComponent
    private Execution execution;

    @MockComponent
    private RemoteObservationManagerConfiguration remoteObservationConfiguration;

    private final AtomicLong time = new AtomicLong();

    private XWikiContext xcontext;

    /**
     * A migration manager working on a simulated database, with a simulated database lock.
     */
    @Component
    @Named("test")
    @Singleton
    public static class TestDataMigrationManager extends AbstractDataMigrationManager
    {
        /**
         * The version of each wiki database, absent for an empty database.
         */
        final Map<String, Integer> database = new HashMap<>();

        /**
         * What has been done on the database and the lock, in order.
         */
        final List<String> calls = new ArrayList<>();

        boolean lockSupported = true;

        boolean failUnlock;

        /**
         * The wikis whose schema update fails.
         */
        final Set<String> failingSchemaUpdates = new HashSet<>();

        /**
         * The result of the next checks of the lock (still held when empty).
         */
        final Deque<Boolean> heldAnswers = new ArrayDeque<>();

        int lockChecks;

        /**
         * The versions of the forced migrations.
         */
        final List<Integer> forcedMigrations = new ArrayList<>();

        /**
         * The result of the next lock requests (acquired when empty).
         */
        final Deque<Boolean> lockAnswers = new ArrayDeque<>();

        /**
         * Called when a lock request fails, to simulate the work of the cluster member holding the lock.
         */
        Runnable lockHolder;

        private String wikiId()
        {
            return getXWikiContext().getWikiId();
        }

        private DataMigration createMigration(int version)
        {
            return new DataMigration()
            {
                @Override
                public String getName()
                {
                    return "R" + version;
                }

                @Override
                public String getDescription()
                {
                    return "Test migration";
                }

                @Override
                public XWikiDBVersion getVersion()
                {
                    return new XWikiDBVersion(version);
                }

                @Override
                public boolean shouldExecute(XWikiDBVersion startupVersion)
                {
                    return true;
                }

                @Override
                public void migrate()
                {
                    TestDataMigrationManager.this.calls.add("migrate " + version + " " + wikiId());
                }
            };
        }

        @Override
        protected List<DataMigration> getAllMigrations()
        {
            return List.of(createMigration(100), createMigration(200));
        }

        @Override
        protected Map<XWikiDBVersion, XWikiMigration> getForcedMigrations()
        {
            Map<XWikiDBVersion, XWikiMigration> migrations = new HashMap<>();
            for (int version : this.forcedMigrations) {
                migrations.put(new XWikiDBVersion(version), new XWikiMigration(createMigration(version), true));
            }

            return migrations;
        }

        @Override
        protected XWikiDBVersion getDBVersionFromDatabase()
        {
            XWikiDBVersion configured = getDBVersionFromConfig();
            if (configured != null) {
                return configured;
            }

            Integer version = this.database.get(wikiId());

            return version != null ? new XWikiDBVersion(version) : null;
        }

        @Override
        protected void initializeEmptyDB() throws DataMigrationException
        {
            this.calls.add("init " + wikiId());
            setDBVersion(getLatestVersion());
        }

        @Override
        protected void setDBVersionToDatabase(XWikiDBVersion version)
        {
            this.database.put(wikiId(), version.getVersion());
        }

        @Override
        protected void updateSchema(Collection<XWikiMigration> migrations) throws DataMigrationException
        {
            if (this.failingSchemaUpdates.contains(wikiId())) {
                throw new DataMigrationException("Failed to update the schema");
            }

            this.calls.add("schema " + wikiId());
        }

        @Override
        protected boolean isDatabaseLockSupported()
        {
            return this.lockSupported;
        }

        @Override
        protected boolean tryLockDatabase(Duration timeout)
        {
            this.calls.add("lock " + timeout.toSeconds());

            boolean acquired = this.lockAnswers.isEmpty() || this.lockAnswers.poll();
            if (!acquired && this.lockHolder != null) {
                this.lockHolder.run();
            }

            return acquired;
        }

        @Override
        protected boolean isDatabaseLockHeld()
        {
            this.lockChecks++;

            return this.heldAnswers.isEmpty() || this.heldAnswers.poll();
        }

        @Override
        protected void unlockDatabase() throws DataMigrationException
        {
            this.calls.add("unlock");

            if (this.failUnlock) {
                throw new DataMigrationException("unlock failed");
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.xcontext = new XWikiContext();
        this.xcontext.setMainXWiki(MAIN_WIKI);
        this.xcontext.setWikiId(MAIN_WIKI);
        ExecutionContext executionContext = new ExecutionContext();
        executionContext.setProperty(XWikiContext.EXECUTIONCONTEXT_KEY, this.xcontext);
        when(this.execution.getContext()).thenReturn(executionContext);

        when(this.hibernateConfiguration.getMigrationDatabases()).thenReturn(List.of(MAIN_WIKI));
        when(this.hibernateConfiguration.getMigrationLockTimeout()).thenReturn(Duration.ZERO);

        ReflectionUtils.setFieldValue(this.manager, "ticker", (LongSupplier) this.time::get);
    }

    private void enableMigrations()
    {
        when(this.hibernateConfiguration.isMigrationEnabled()).thenReturn(true);
    }

    @Test
    void migrateWhileHoldingTheLock() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 100);

        this.manager.checkDatabase();

        assertEquals(List.of("lock 0", "schema xwiki", "migrate 200 xwiki", "unlock"), this.manager.calls);
        assertEquals(200, this.manager.database.get(MAIN_WIKI));
        assertTrue(this.manager.getDataMigrationStatus().hasBeenSuccessfullyMigrated());

        // Migrations run only once
        this.manager.checkDatabase();

        assertEquals(4, this.manager.calls.size());
    }

    @Test
    void skipWhatAnotherMemberMigratedWhileWaitingForTheLock() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 100);
        this.manager.lockAnswers.add(false);
        this.manager.lockAnswers.add(false);
        // The member holding the lock migrates the database meanwhile
        this.manager.lockHolder = () -> this.manager.database.put(MAIN_WIKI, 200);

        this.manager.checkDatabase();

        // The version is read again once the lock is held: no data migration runs a second time
        assertEquals(List.of("lock 0", "lock 60", "lock 60", "schema xwiki", "unlock"), this.manager.calls);
        assertEquals(200, this.manager.getDBVersion().getVersion());
    }

    @Test
    void giveUpWaitingForTheLockAfterTheTimeout() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 100);
        when(this.hibernateConfiguration.getMigrationLockTimeout()).thenReturn(Duration.ofSeconds(90));
        for (int i = 0; i < 3; i++) {
            this.manager.lockAnswers.add(false);
        }

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

        assertEquals("Gave up waiting for the database migration lock held by another cluster member after [90]"
            + " seconds (store.migration.lockTimeout)", exception.getMessage());
        assertEquals(List.of("lock 0", "lock 60", "lock 30"), this.manager.calls);

        // The next access tries again
        this.manager.checkDatabase();

        assertEquals(List.of("lock 0", "lock 60", "lock 30", "lock 0", "schema xwiki", "migrate 200 xwiki", "unlock"),
            this.manager.calls);
    }

    @Test
    void stopWaitingForTheLockWhenInterrupted() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 100);
        this.manager.lockAnswers.add(false);
        // The thread is interrupted while waiting (for example because XWiki is stopping)
        this.manager.lockHolder = () -> Thread.currentThread().interrupt();

        try {
            DataMigrationException exception =
                assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

            assertEquals("Interrupted while waiting for the database migration lock held by another cluster member",
                exception.getMessage());
            // No other lock request, and the interruption is kept for the caller
            assertEquals(List.of("lock 0"), this.manager.calls);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopMigratingWhenTheLockIsLost() throws Exception
    {
        enableMigrations();
        when(this.hibernateConfiguration.getMigrationDatabases()).thenReturn(List.of(MAIN_WIKI, SUBWIKI));
        this.manager.database.put(MAIN_WIKI, 100);
        this.manager.database.put(SUBWIKI, 100);
        // The connection holding the lock is closed while the main wiki is migrated
        this.manager.heldAnswers.add(true);
        this.manager.heldAnswers.add(false);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

        String message = "Lost the database migration lock (the connection holding it was closed, for example by an"
            + " idle timeout of the database server or of the network): stopping the migration so that it does not"
            + " run at the same time as the one of another cluster member";
        assertEquals(message, exception.getMessage());
        assertEquals(message, this.logCapture.getMessage(0));
        // The sub-wiki is not migrated
        assertEquals(List.of("lock 0", "schema xwiki", "migrate 200 xwiki", "unlock"), this.manager.calls);
        assertEquals(2, this.manager.lockChecks);
        assertEquals(100, this.manager.database.get(SUBWIKI));

        // It's left to the cluster member which acquired the lock
        this.xcontext.setWikiId(SUBWIKI);
        assertThrows(MigrationRequiredException.class, () -> this.manager.checkDatabase());
    }

    @Test
    void lockNotCheckedWithoutLockSupport() throws Exception
    {
        enableMigrations();
        this.manager.lockSupported = false;
        this.manager.database.put(MAIN_WIKI, 100);

        this.manager.checkDatabase();

        assertEquals(0, this.manager.lockChecks);
        assertEquals(200, this.manager.database.get(MAIN_WIKI));
    }

    @Test
    void unlockFailureIsOnlyLogged() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 100);
        this.manager.failUnlock = true;

        this.manager.checkDatabase();

        assertEquals(List.of("lock 0", "schema xwiki", "migrate 200 xwiki", "unlock"), this.manager.calls);
        assertEquals("Failed to release the database migration lock: DataMigrationException: unlock failed",
            this.logCapture.getMessage(0));
        assertTrue(this.manager.getDataMigrationStatus().hasBeenSuccessfullyMigrated());
    }

    @Test
    void failedMigrationIsKeptWhenTheLockIsAcquiredAgain() throws Exception
    {
        enableMigrations();
        when(this.hibernateConfiguration.getMigrationDatabases()).thenReturn(List.of(MAIN_WIKI, SUBWIKI));
        this.manager.database.put(MAIN_WIKI, 100);
        this.manager.database.put(SUBWIKI, 100);
        this.manager.failingSchemaUpdates.add(SUBWIKI);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());
        assertEquals("1 wiki database migration(s) failed.", exception.getMessage());
        assertEquals("Failed to migrate database [subwiki]...", this.logCapture.getMessage(0));
        assertEquals("[1] wiki database migration(s) failed.", this.logCapture.getMessage(1));

        // A new empty wiki is initialized while holding the lock
        this.xcontext.setWikiId("newwiki");
        this.manager.checkDatabase();
        assertEquals(List.of("lock 0", "schema xwiki", "migrate 200 xwiki", "unlock", "lock 0", "init newwiki",
            "unlock"), this.manager.calls);

        // The failed migration of the sub-wiki is still reported as such
        this.xcontext.setWikiId(SUBWIKI);
        exception = assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());
        assertEquals("Migration of database [subwiki] has failed, it could not be safely used! Database is currently"
            + " in version [100] while the required version is [200].", exception.getMessage());
    }

    @Test
    void initializeAnEmptyDatabaseWhileHoldingTheLock() throws Exception
    {
        this.manager.checkDatabase();

        assertEquals(List.of("lock 0", "init xwiki", "unlock"), this.manager.calls);
        assertEquals(200, this.manager.database.get(MAIN_WIKI));
    }

    @Test
    void skipTheInitializationDoneByAnotherMember() throws Exception
    {
        this.manager.lockAnswers.add(false);
        this.manager.lockHolder = () -> this.manager.database.put(MAIN_WIKI, 200);

        this.manager.checkDatabase();

        assertEquals(List.of("lock 0", "lock 60", "unlock"), this.manager.calls);
    }

    @Test
    void noLockWhenNothingToDo() throws Exception
    {
        this.manager.database.put(MAIN_WIKI, 200);

        this.manager.checkDatabase();

        assertEquals(List.of(), this.manager.calls);
    }

    @Test
    void lockNotSupportedInACluster() throws Exception
    {
        enableMigrations();
        when(this.remoteObservationConfiguration.isEnabled()).thenReturn(true);
        this.manager.lockSupported = false;
        this.manager.database.put(MAIN_WIKI, 100);

        this.manager.checkDatabase();

        assertEquals(List.of("schema xwiki", "migrate 200 xwiki"), this.manager.calls);
        assertEquals("The database does not support locks shared by the cluster members: make sure that only one"
            + " cluster member initializes or migrates it at a time", this.logCapture.getMessage(0));

        // Reported only once
        this.xcontext.setWikiId(SUBWIKI);
        this.manager.checkDatabase();
        assertEquals(List.of("schema xwiki", "migrate 200 xwiki", "init subwiki"), this.manager.calls);
    }

    @Test
    void lockNotSupportedWithoutCluster() throws Exception
    {
        this.manager.lockSupported = false;

        this.manager.checkDatabase();

        assertEquals(List.of("init xwiki"), this.manager.calls);
    }

    @Test
    void outdatedDatabaseIsReadAgainWhenMigrationsAreDisabled() throws Exception
    {
        this.manager.database.put(MAIN_WIKI, 100);

        MigrationRequiredException exception =
            assertThrows(MigrationRequiredException.class, () -> this.manager.checkDatabase());
        assertEquals("Since database [xwiki] needs to be migrated, it couldn't be safely used! Please check your"
            + " configuration to enable required migration for upgrading database from version [100] to version"
            + " [200].", exception.getMessage());

        // Another member migrates the database
        this.manager.database.put(MAIN_WIKI, 200);

        // The version is not read again before 10 seconds
        this.time.addAndGet(TimeUnit.SECONDS.toNanos(9));
        assertThrows(MigrationRequiredException.class, () -> this.manager.checkDatabase());

        this.time.addAndGet(TimeUnit.SECONDS.toNanos(1));
        this.manager.checkDatabase();

        assertEquals(200, this.manager.getDBVersion().getVersion());
        assertEquals(List.of(), this.manager.calls);
    }

    @Test
    void outdatedDatabaseStillOutdatedWhenReadAgain() throws Exception
    {
        this.manager.database.put(MAIN_WIKI, 100);
        assertThrows(MigrationRequiredException.class, () -> this.manager.checkDatabase());

        this.time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        assertThrows(MigrationRequiredException.class, () -> this.manager.checkDatabase());

        // Read again only 10 seconds after the previous read
        this.manager.database.put(MAIN_WIKI, 200);
        this.time.addAndGet(TimeUnit.SECONDS.toNanos(5));
        assertThrows(MigrationRequiredException.class, () -> this.manager.checkDatabase());
        this.time.addAndGet(TimeUnit.SECONDS.toNanos(5));
        this.manager.checkDatabase();
    }

    @Test
    void failedMigrationIsNotReadAgain() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 100);
        this.manager.failingSchemaUpdates.add(MAIN_WIKI);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

        assertEquals("Main wiki database migration failed, it is not safe to continue!", exception.getMessage());
        assertEquals(List.of("lock 0", "unlock"), this.manager.calls);
        assertEquals("Failed to migrate database [xwiki]...", this.logCapture.getMessage(0));
        assertEquals("Main wiki database migration failed, it is not safe to continue!",
            this.logCapture.getMessage(1));

        // The failure of this member is kept: the database is not read again
        this.time.addAndGet(TimeUnit.SECONDS.toNanos(60));
        exception = assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());
        assertEquals("Migration of database [xwiki] has failed, it could not be safely used! Database is currently in"
            + " version [100] while the required version is [200].", exception.getMessage());
        assertFalse(this.manager.getDataMigrationStatus().hasBeenSuccessfullyMigrated());
        assertEquals(List.of("lock 0", "unlock"), this.manager.calls);
    }

    @Test
    void newerDatabaseIsRefused() throws Exception
    {
        this.manager.database.put(MAIN_WIKI, 300);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

        assertEquals(NEWER_DATABASE_MESSAGE, exception.getMessage());
        assertEquals(List.of(), this.manager.calls);
    }

    @Test
    void newerDatabaseIsNotMigrated() throws Exception
    {
        enableMigrations();
        this.manager.database.put(MAIN_WIKI, 300);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

        // The schema of a newer database is not updated, and its version is left as is
        assertEquals("Main wiki database migration failed, it is not safe to continue!", exception.getMessage());
        assertEquals(List.of("lock 0", "unlock"), this.manager.calls);
        assertEquals(300, this.manager.database.get(MAIN_WIKI));
        assertEquals("Failed to migrate database [xwiki]...", this.logCapture.getMessage(0));
        assertEquals("Main wiki database migration failed, it is not safe to continue!",
            this.logCapture.getMessage(1));

        exception = assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());
        assertEquals(NEWER_DATABASE_MESSAGE, exception.getMessage());
    }

    @Test
    void newerDatabaseAllowedWithForcedVersion() throws Exception
    {
        when(this.hibernateConfiguration.getMigrationVersion()).thenReturn("200");
        this.manager.database.put(MAIN_WIKI, 300);

        this.manager.checkDatabase();

        assertEquals(200, this.manager.getDBVersion().getVersion());
    }

    @Test
    void forcedMigrationOlderThanTheDatabase() throws Exception
    {
        enableMigrations();
        this.manager.forcedMigrations.add(100);
        this.manager.initialize();
        this.manager.database.put(MAIN_WIKI, 200);

        this.manager.checkDatabase();

        // The forced migration runs again and the database stays in its version
        assertEquals(List.of("lock 0", "schema xwiki", "migrate 100 xwiki", "unlock"), this.manager.calls);
        assertEquals(200, this.manager.database.get(MAIN_WIKI));
        this.manager.checkDatabase();
        assertEquals(100, this.manager.getLatestVersion().getVersion());
    }

    @Test
    void newerDatabaseIsRefusedWithForcedMigrations() throws Exception
    {
        this.manager.forcedMigrations.add(100);
        this.manager.initialize();
        this.manager.database.put(MAIN_WIKI, 300);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.checkDatabase());

        // Compared with the latest version of all the migrations, not only the forced ones
        assertEquals(NEWER_DATABASE_MESSAGE, exception.getMessage());
    }

    @Test
    void latestMigrationIgnoredOnADatabaseAlreadyInItsVersion() throws Exception
    {
        enableMigrations();
        when(this.hibernateConfiguration.getIgnoredMigrations()).thenReturn(List.of("200"));
        this.manager.initialize();
        this.manager.database.put(MAIN_WIKI, 200);

        this.manager.checkDatabase();

        assertEquals(List.of("lock 0", "schema xwiki", "unlock"), this.manager.calls);
        assertEquals(200, this.manager.database.get(MAIN_WIKI));
        this.manager.checkDatabase();

        // Also when the migrations are disabled
        when(this.hibernateConfiguration.isMigrationEnabled()).thenReturn(false);
        this.xcontext.setWikiId(SUBWIKI);
        this.manager.database.put(SUBWIKI, 200);
        this.manager.checkDatabase();
    }
}
