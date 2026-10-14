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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.WikiDeletedEvent;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.job.event.status.JobProgressManager;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.ObservationManager;
import org.xwiki.observation.event.Event;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.XWikiConfig;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.internal.store.hibernate.HibernateConfiguration;
import com.xpn.xwiki.store.migration.hibernate.HibernateDataMigration;

/**
 * Template for {@link DataMigrationManager}.
 *
 * @version $Id$
 * @since 3.4M1
 */
public abstract class AbstractDataMigrationManager implements DataMigrationManager, Initializable
{
    /**
     * How long to wait for the database lock between two log messages.
     */
    private static final Duration LOCK_WAIT_LOG_INTERVAL = Duration.ofSeconds(60);

    /**
     * How long a database found outdated by a member which does not migrate it is refused before reading its version
     * again.
     */
    private static final long OUTDATED_RECHECK_INTERVAL = TimeUnit.SECONDS.toNanos(10);

    /**
     * Component manager used to access stores and data migrations.
     */
    @Inject
    protected ComponentManager componentManager;

    /**
     * Component manager used to access stores and data migrations.
     */
    @Inject
    protected ObservationManager observationManager;

    @Inject
    protected JobProgressManager progress;

    @Inject
    protected HibernateConfiguration hibernateConfiguration;

    @Inject
    private Provider<RemoteObservationManagerConfiguration> remoteObservationConfiguration;

    /**
     * Ordered list of migrators that may be applied.
     */
    protected Collection<XWikiMigration> migrations;

    /**
     * True while {@link #startMigrations()} is running.
     */
    private volatile boolean migrating;

    /**
     * Internal class used to find out the data migration that are being forced in the XWiki configuration file.
     */
    protected class XWikiMigration
    {
        /**
         * True for a forced migration.
         */
        public boolean isForced;

        /**
         * The data migration.
         */
        public DataMigration dataMigration;

        /**
         * Build a new XWikiMigration.
         *
         * @param dataMigration the data migration
         * @param isForced true when this migration is forced
         */
        public XWikiMigration(DataMigration dataMigration, boolean isForced)
        {
            this.dataMigration = dataMigration;
            this.isForced = isForced;
        }
    }

    /**
     * Logger.
     */
    @Inject
    protected Logger logger;

    /**
     * Execution context used to access XWikiContext.
     */
    @Inject
    private Execution execution;

    /**
     * Internal class used to prevent double checking of the database during migration operation.
     */
    private static final class ThreadLock extends ThreadLocal<Integer>
    {
        @Override
        protected Integer initialValue()
        {
            return 0;
        }

        /**
         * Release the lock.
         */
        public void unlock()
        {
            int i = get();
            if (i > 0) {
                set(--i);
            }
        }

        /**
         * Acquire the lock.
         */
        public void lock()
        {
            set(get() + 1);
        }

        /**
         * Try to acquire the lock.
         *
         * @return true if the lock has been acquired
         */
        public boolean tryLock()
        {
            int i = get();
            if (i > 0) {
                return false;
            }
            set(++i);
            return true;
        }
    }

    /**
     * Semaphore to prevent re-entrance.
     */
    private final ThreadLock lock = new ThreadLock();

    /**
     * Internal class used to store the migration status of the database.
     */
    private static class MigrationStatus implements DataMigrationStatus
    {
        /**
         * Current version of the database.
         */
        private XWikiDBVersion version;

        /**
         * True if the database has been migrated during the current run.
         */
        private Exception migrationException;

        /**
         * The exception produced by a migration failure, if any.
         */
        private boolean migrationAttempted;

        /**
         * When the version was read, as a {@link System#nanoTime()} value.
         */
        private long readTime;

        /**
         * Build a simple status with no migration attempted, just storing the current database version.
         *
         * @param version the current database version
         */
        public MigrationStatus(XWikiDBVersion version)
        {
            this.version = version;
            this.migrationAttempted = false;
        }

        /**
         * Build a simple status with no migration attempted, storing the current database version and when it was
         * read.
         *
         * @param version the current database version
         * @param readTime when the version was read, as a {@link System#nanoTime()} value
         */
        MigrationStatus(XWikiDBVersion version, long readTime)
        {
            this(version);
            this.readTime = readTime;
        }

        /**
         * Build a status following a migration storing the current database version and the exception resulting from
         * the migration. A null value for the exception means a successful migration.
         *
         * @param version the current database version
         * @param migrationException the exception resulting from the migration.
         */
        public MigrationStatus(XWikiDBVersion version, Exception migrationException)
        {
            this.version = version;
            this.migrationAttempted = true;
            this.migrationException = migrationException;
        }

        @Override
        public XWikiDBVersion getDBVersion()
        {
            return this.version;
        }

        @Override
        public boolean hasDataMigrationBeenAttempted()
        {
            return this.migrationAttempted;
        }

        @Override
        public boolean hasBeenSuccessfullyMigrated()
        {
            return this.migrationAttempted && this.migrationException == null;
        }

        @Override
        public Exception getLastMigrationException()
        {
            return this.migrationException;
        }
    }

    /**
     * A cache of wiki database version.
     */
    private final Map<String, MigrationStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * True once the absence of a database lock shared by the cluster members has been reported.
     */
    private boolean missingLockReported;

    /**
     * True while this instance holds the database lock shared by the cluster members.
     */
    private boolean databaseLocked;

    /**
     * The source of the time used to decide when an outdated database should be checked again.
     */
    private LongSupplier ticker = System::nanoTime;

    /**
     * The final database version when the migration process finishes. This is use to compute the DBVersion of an empty
     * store and quickly check the outdated status of existing DB.
     */
    private XWikiDBVersion targetVersion;

    /**
     * The highest version of all the known data migrations, including the forced and ignored ones, null when there is
     * none. A database in a higher version was migrated by a more recent version of XWiki (or of an extension).
     */
    private XWikiDBVersion latestKnownVersion;

    /**
     * Internal class used to clean the database version cache on wiki deletion.
     */
    private final class WikiDeletedEventListener implements EventListener
    {
        @Override
        public String getName()
        {
            return "dbversioncache";
        }

        @Override
        public List<Event> getEvents()
        {
            return Arrays.<Event>asList(new WikiDeletedEvent());
        }

        @Override
        public void onEvent(Event event, Object source, Object data)
        {
            AbstractDataMigrationManager.this.statusCache.remove(((WikiDeletedEvent) event).getWikiId());
        }
    }

    /**
     * Unified constructor for all subclasses.
     */
    protected AbstractDataMigrationManager()
    {
    }

    /**
     * @return XWikiContext
     */
    protected XWikiContext getXWikiContext()
    {
        ExecutionContext context = this.execution.getContext();
        return (XWikiContext) context.getProperty("xwikicontext");
    }

    /**
     * @return XWikiConfig to read configuration from xwiki.cfg
     * @deprecated
     */
    @Deprecated
    protected XWikiConfig getXWikiConfig()
    {
        return getXWikiContext().getWiki().getConfig();
    }

    /**
     * @deprecated Virtual mode is on by default.
     * @return true if running in virtual mode
     */
    @Deprecated(since = "5.0M2")
    protected boolean isVirtualMode()
    {
        return true;
    }

    /**
     * @return list of virtual database names
     * @throws DataMigrationException on error
     */
    @Deprecated
    protected List<String> getVirtualWikisDatabaseNames() throws DataMigrationException
    {
        try {
            return getXWikiContext().getWiki().getVirtualWikisDatabaseNames(getXWikiContext());
        } catch (XWikiException e) {
            throw new DataMigrationException("Unable to retrieve the list of wiki names", e);
        }
    }

    /**
     * @return the main XWiki database name
     */
    protected String getMainXWiki()
    {
        return getXWikiContext().getMainXWiki();
    }

    @Override
    public void initialize() throws InitializationException
    {
        checkMigrationsVersions();
        try {
            SortedMap<XWikiDBVersion, XWikiMigration> availableMigrations = new TreeMap<>();
            Map<XWikiDBVersion, XWikiMigration> forcedMigrations = getForcedMigrations();
            if (!forcedMigrations.isEmpty()) {
                availableMigrations.putAll(forcedMigrations);
            } else {
                Set<String> ignoredMigrations = new HashSet<>(this.hibernateConfiguration.getIgnoredMigrations());
                for (DataMigration migrator : getAllMigrations()) {
                    XWikiDBVersion migratorVersion = migrator.getVersion();
                    if (isMigrationIgnored(migrator, ignoredMigrations)) {
                        continue;
                    }
                    XWikiMigration migration = new XWikiMigration(migrator, false);
                    availableMigrations.put(migratorVersion, migration);
                }
            }

            this.targetVersion = !availableMigrations.isEmpty() ? availableMigrations.lastKey() : new XWikiDBVersion(0);
            this.migrations = availableMigrations.values();

            // Forced migrations replace the list of migrations and ignored ones are removed from it: they don't
            // change the versions this XWiki knows
            this.latestKnownVersion = getAllMigrations().stream().map(DataMigration::getVersion)
                .max(XWikiDBVersion::compareTo).orElse(null);
        } catch (Exception e) {
            throw new InitializationException("Migration Manager initialization failed", e);
        }

        this.observationManager.addListener(new WikiDeletedEventListener());
    }

    public boolean isMigrationIgnored(DataMigration migration, Set<String> ignoredMigrations)
    {
        return ignoredMigrations.contains(migration.getClass().getName())
            || ignoredMigrations.contains(migration.getVersion().toString());
    }

    /**
     * Ensure we don't have two migrations with same DB version.
     * Note that it's possible to use the ignored migrations feature to bypass this check: if two migrations has same
     * version one can ignore them in which case there will be only a warning.
     *
     * @throws InitializationException in case we found same DB version or the list of migration cannot be obtained.
     */
    private void checkMigrationsVersions() throws InitializationException
    {
        try {
            List<HibernateDataMigration> migrationList =
                this.componentManager.getInstanceList(HibernateDataMigration.class);
            Set<String> ignoredMigrations = new HashSet<>(this.hibernateConfiguration.getIgnoredMigrations());
            Map<XWikiDBVersion, String> hintMap = new HashMap<>();
            for (HibernateDataMigration dataMigration : migrationList) {
                XWikiDBVersion version = dataMigration.getVersion();
                if (hintMap.containsKey(version)) {
                    if (isMigrationIgnored(dataMigration, ignoredMigrations)) {
                        this.logger.warn("Two migrations with same version [{}] were found: [{}] and [{}] but "
                            + "migration [{}] is ignored.",
                            version,
                            hintMap.get(version),
                            dataMigration.getClass().getName(),
                            dataMigration.getClass().getName());
                    } else {
                        throw new InitializationException(
                            String.format("Two migrations with same version [%s] were found: [%s] and [%s]",
                                version,
                                hintMap.get(version),
                                dataMigration.getClass().getName()));
                    }
                }
                hintMap.put(version, dataMigration.getClass().getName());
            }
        } catch (ComponentLookupException e) {
            throw new InitializationException("Unable to retrieve the list of hibernate data migrations", e);
        }
    }

    /**
     * read data version from xwiki.cfg.
     *
     * @return data version if set, or null.
     */
    protected XWikiDBVersion getDBVersionFromConfig()
    {
        String ver = this.hibernateConfiguration.getMigrationVersion();
        return ver == null ? null : new XWikiDBVersion(Integer.parseInt(ver));
    }

    /**
     * Read data version from database.
     *
     * @return data version or null if this is a new database
     * @throws DataMigrationException in case of an unexpected error
     */
    protected XWikiDBVersion getDBVersionFromDatabase() throws DataMigrationException
    {
        return getDBVersionFromConfig();
    }

    @Override
    public final XWikiDBVersion getDBVersion() throws DataMigrationException
    {
        this.lock.lock();
        try {
            String wikiName = getXWikiContext().getWikiId();
            MigrationStatus dbStatus = this.statusCache.get(wikiName);
            if (dbStatus == null) {
                synchronized (this.statusCache) {
                    XWikiDBVersion version = getDBVersionFromDatabase();
                    if (version != null) {
                        this.statusCache.put(wikiName, new MigrationStatus(version, this.ticker.getAsLong()));
                    }
                    return version;
                }
            }
            return dbStatus.getDBVersion();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public DataMigrationStatus getDataMigrationStatus() throws DataMigrationException
    {
        this.lock.lock();
        try {
            String wikiName = getXWikiContext().getWikiId();
            MigrationStatus dbStatus = this.statusCache.get(wikiName);
            if (dbStatus == null) {
                synchronized (this.statusCache) {
                    XWikiDBVersion version = getDBVersionFromDatabase();
                    if (version != null) {
                        dbStatus = new MigrationStatus(version, this.ticker.getAsLong());
                        this.statusCache.put(wikiName, dbStatus);
                    }
                }
            }
            return dbStatus;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public final XWikiDBVersion getLatestVersion()
    {
        return this.targetVersion;
    }

    @Override
    public synchronized void initNewDB() throws DataMigrationException
    {
        this.lock.lock();
        try {
            initializeEmptyDB();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * @throws DataMigrationException if any error
     */
    protected abstract void initializeEmptyDB() throws DataMigrationException;

    /**
     * @param version to set
     * @throws DataMigrationException if any error
     */
    protected abstract void setDBVersionToDatabase(XWikiDBVersion version) throws DataMigrationException;

    /**
     * Update database version and status cache (not after a migration, use updateMigrationStatus).
     *
     * @param version database version to be stored
     * @throws DataMigrationException if any error
     */
    protected final void setDBVersion(XWikiDBVersion version) throws DataMigrationException
    {
        updateMigrationStatus(version, false, null);
    }

    /**
     * Update database version and status cache after a successful migration.
     *
     * @param version new database version
     * @throws DataMigrationException if any error
     */
    private void updateMigrationStatus(XWikiDBVersion version) throws DataMigrationException
    {
        updateMigrationStatus(version, true, null);
    }

    /**
     * Update status cache based on last migration failure.
     *
     * @param version current database version (last valid version)
     * @param e exception thrown by the migration
     * @throws DataMigrationException if any error
     */
    private void updateMigrationStatus(XWikiDBVersion version, Exception e) throws DataMigrationException
    {
        updateMigrationStatus(version, true, e);
    }

    /**
     * Update database version and status cache based on the new migration status.
     *
     * @param version current or new database version
     * @param migrationAttempted true if this update is the result of a migration process
     * @param e exception thrown by the last migration or null if the migration was successful
     * @throws DataMigrationException if any error
     */
    private synchronized void updateMigrationStatus(XWikiDBVersion version, boolean migrationAttempted, Exception e)
        throws DataMigrationException
    {
        String wikiName = getXWikiContext().getWikiId();
        if (!migrationAttempted || e == null) {
            setDBVersionToDatabase(version);
        }
        if (version != null) {
            this.statusCache.put(wikiName,
                (migrationAttempted) ? new MigrationStatus(version, e) : new MigrationStatus(version));
        }
    }

    /**
     * Update database schema to the latest structure.
     *
     * @param migrations the migration that will be executed (since 4.0M1)
     * @throws DataMigrationException if any error
     */
    protected abstract void updateSchema(Collection<XWikiMigration> migrations) throws DataMigrationException;

    @Override
    public void checkDatabase() throws MigrationRequiredException, DataMigrationException
    {
        if (!this.lock.tryLock()) {
            return;
        }
        try {
            // Initialize an empty database, or proceed with migrations (only once)
            if (getDatabaseStatus() == null
                || (this.migrations != null && this.hibernateConfiguration.isMigrationEnabled())) {
                initializeOrMigrateDatabase();
            }

            preventAccessToOutdatedDb();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Initialize the current database if it's empty and run the migrations if they are enabled and did not run yet,
     * while holding the database lock shared by the cluster members, if the store supports one. The synchronization
     * makes sure that only one thread of this instance holds or waits for the database lock.
     *
     * @throws DataMigrationException when failing to acquire the database lock, to initialize the database or to
     *             migrate it
     */
    private synchronized void initializeOrMigrateDatabase() throws DataMigrationException
    {
        boolean locked = lockDatabase();
        this.databaseLocked = locked;
        try {
            if (locked) {
                // Another cluster member may have initialized or migrated the databases while this instance was
                // waiting for the lock: forget the versions read before acquiring it, but keep the result of the
                // migrations attempted by this instance (a failed migration must keep failing)
                synchronized (this.statusCache) {
                    this.statusCache.values().removeIf(status -> !status.hasDataMigrationBeenAttempted());
                }
            }

            if (getDatabaseStatus() == null) {
                initializeCurrentDatabase();
            }

            // Proceed with migration (only once)
            if (this.migrations != null) {
                tryToProcceedToMigration();
            }
        } finally {
            if (locked) {
                this.databaseLocked = false;
                releaseDatabaseLock();
            }
        }
    }

    private void releaseDatabaseLock()
    {
        try {
            unlockDatabase();
        } catch (DataMigrationException e) {
            // Don't hide the result of the migration: the store closes a connection holding a lock it failed to
            // release
            this.logger.warn("Failed to release the database migration lock: {}",
                ExceptionUtils.getRootCauseMessage(e));
        }
    }

    /**
     * Acquire the database lock shared by the cluster members, waiting for it as long as configured.
     *
     * @return true if the lock has been acquired, false if the store does not support such a lock
     * @throws DataMigrationException when the lock could not be acquired in time
     */
    private boolean lockDatabase() throws DataMigrationException
    {
        if (!isDatabaseLockSupported()) {
            if (!this.missingLockReported && isClustered()) {
                this.missingLockReported = true;
                this.logger.warn("The database does not support locks shared by the cluster members: make sure that "
                    + "only one cluster member initializes or migrates it at a time");
            }

            return false;
        }

        if (tryLockDatabase(Duration.ZERO)) {
            return true;
        }

        Duration timeout = this.hibernateConfiguration.getMigrationLockTimeout();
        this.logger.info("Waiting for the database migration lock held by another cluster member");
        Duration waited = Duration.ZERO;
        while (true) {
            // An interrupted thread can't wait anymore (the lock requests return immediately): stop instead of
            // looping, and keep the interruption flag for the caller
            if (Thread.currentThread().isInterrupted()) {
                throw new DataMigrationException("Interrupted while waiting for the database migration lock held by"
                    + " another cluster member");
            }

            Duration wait = LOCK_WAIT_LOG_INTERVAL;
            if (!timeout.isZero()) {
                Duration remaining = timeout.minus(waited);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new DataMigrationException(String.format("Gave up waiting for the database migration lock"
                        + " held by another cluster member after [%d] seconds (store.migration.lockTimeout)",
                        waited.toSeconds()));
                }
                if (remaining.compareTo(wait) < 0) {
                    wait = remaining;
                }
            }

            if (tryLockDatabase(wait)) {
                this.logger.info("Acquired the database migration lock");

                return true;
            }

            waited = waited.plus(wait);
            this.logger.info("Still waiting for the database migration lock held by another cluster member after"
                + " [{}] seconds", waited.toSeconds());
        }
    }

    private boolean isClustered()
    {
        try {
            return this.remoteObservationConfiguration.get().isEnabled();
        } catch (Exception e) {
            this.logger.debug("Failed to read the remote observation configuration", e);

            return false;
        }
    }

    /**
     * Indicate if the store supports a lock shared by all the processes (cluster members) using the database. When it
     * does, the database initialization and the migrations are done while holding it.
     *
     * @return true if the store supports a lock shared by all the processes using the database
     * @since 18.10.0RC1
     */
    @Unstable
    protected boolean isDatabaseLockSupported()
    {
        return false;
    }

    /**
     * Try to acquire the lock shared by all the processes (cluster members) using the database.
     *
     * @param timeout the maximum time to wait for the lock, {@link Duration#ZERO} to try only once
     * @return true if the lock has been acquired, false if it's held by another process after the passed duration
     * @throws DataMigrationException when failing to request the lock
     * @since 18.10.0RC1
     */
    @Unstable
    protected boolean tryLockDatabase(Duration timeout) throws DataMigrationException
    {
        return true;
    }

    /**
     * Indicate if the lock acquired with {@link #tryLockDatabase(Duration)} is still held. The database server releases
     * the lock when the connection holding it is closed, for example after an idle timeout of the database server or
     * of the network, and another cluster member can then acquire it.
     *
     * @return true if the lock is still held
     * @throws DataMigrationException when failing to check the lock
     * @since 18.10.0RC1
     */
    @Unstable
    protected boolean isDatabaseLockHeld() throws DataMigrationException
    {
        return true;
    }

    /**
     * Release the lock acquired with {@link #tryLockDatabase(Duration)}.
     *
     * @throws DataMigrationException when failing to release the lock
     * @since 18.10.0RC1
     */
    @Unstable
    protected void unlockDatabase() throws DataMigrationException
    {
        // Nothing to release by default
    }

    private void initializeCurrentDatabase() throws DataMigrationException
    {
        try {
            initNewDB();
        } catch (DataMigrationException e) {
            String message =
                String.format("The empty database %s seems to be not writable, please check your configuration!",
                    getXWikiContext().getWikiId());
            this.logger.error("The empty database [{}] seems to be not writable, please check your configuration!",
                getXWikiContext().getWikiId(), e);
            throw new DataMigrationException(message, e);
        }
    }

    private DataMigrationStatus getDatabaseStatus() throws DataMigrationException
    {
        DataMigrationStatus status;
        try {
            status = getDataMigrationStatus();
        } catch (DataMigrationException e) {
            String message = String.format("Database %s seems to be inaccessible, please check your configuration!",
                getXWikiContext().getWikiId());
            this.logger.error("Database [{}] seems to be inaccessible, please check your configuration!",
                getXWikiContext().getWikiId(), e);
            throw new DataMigrationException(message, e);
        }
        return status;
    }

    /**
     * Check database status, and throws if access should not be allowed.
     *
     * @throws DataMigrationException when a database has failed to migrate.
     * @throws MigrationRequiredException when a database has not been migrated and require migration.
     */
    private void preventAccessToOutdatedDb() throws DataMigrationException, MigrationRequiredException
    {
        DataMigrationStatus status = getDataMigrationStatus();

        checkNotNewer(status.getDBVersion());

        if (getLatestVersion().compareTo(status.getDBVersion()) > 0) {
            status = recheckOutdatedDb(status);
        }

        if (getLatestVersion().compareTo(status.getDBVersion()) > 0) {
            if (status.hasDataMigrationBeenAttempted() && !status.hasBeenSuccessfullyMigrated()) {
                String message = String.format(
                    "Migration of database [%s] has failed, it could not be safely used! Database is currently in"
                        + " version [%d] while the required version is [%d].",
                    getXWikiContext().getWikiId(), status.getDBVersion().getVersion(), getLatestVersion().getVersion());
                throw new DataMigrationException(message, status.getLastMigrationException());
            } else {
                String message = String.format(
                    "Since database [%s] needs to be migrated, it couldn't be safely used! Please check your"
                        + " configuration to enable required migration for upgrading database from version [%d]"
                        + " to version [%d].",
                    getXWikiContext().getWikiId(), status.getDBVersion().getVersion(), getLatestVersion().getVersion());
                throw new MigrationRequiredException(message);
            }
        }
    }

    /**
     * Refuse a database migrated by a more recent version of XWiki: the schema updates of this version could revert
     * the changes of the more recent one, and this version could not read or write the data in its new format. The
     * database version is compared with the highest version of all the known data migrations (including the ones of
     * the installed extensions), whatever the forced and ignored migrations.
     *
     * @param version the version of the database
     * @throws DataMigrationException when the database is newer than this version of XWiki supports
     */
    private void checkNotNewer(XWikiDBVersion version) throws DataMigrationException
    {
        if (version != null && this.latestKnownVersion != null && version.compareTo(this.latestKnownVersion) > 0) {
            throw new DataMigrationException(String.format("Database [%s] is in version [%d], newer than the version"
                + " [%d] supported by this XWiki: it was migrated by a more recent version of XWiki, or by a data"
                + " migration of an extension that is no longer installed, and could not be safely used! Downgrading"
                + " XWiki requires a new installation and the import of a XAR export (the version of the database can"
                + " be forced with xwiki.store.migration.version in xwiki.cfg, at your own risk).",
                getXWikiContext().getWikiId(), version.getVersion(), this.latestKnownVersion.getVersion()));
        }
    }

    /**
     * Read again the version of an outdated database which this instance did not try to migrate, at most every
     * {@link #OUTDATED_RECHECK_INTERVAL}, since another cluster member may have migrated it meanwhile.
     *
     * @param status the current status of the database
     * @return the status to use
     * @throws DataMigrationException when failing to read the version
     */
    private DataMigrationStatus recheckOutdatedDb(DataMigrationStatus status) throws DataMigrationException
    {
        if (!status.hasDataMigrationBeenAttempted() && status instanceof MigrationStatus migrationStatus
            && this.ticker.getAsLong() - migrationStatus.readTime >= OUTDATED_RECHECK_INTERVAL) {
            String wikiId = getXWikiContext().getWikiId();
            synchronized (this.statusCache) {
                // Only forget the status which was checked, not a newer one stored by another thread
                if (this.statusCache.get(wikiId) == status) {
                    this.statusCache.remove(wikiId);
                }
            }

            DataMigrationStatus newStatus = getDataMigrationStatus();
            if (newStatus == null) {
                return status;
            }

            if (getLatestVersion().compareTo(newStatus.getDBVersion()) <= 0) {
                this.logger.info("Database [{}] has been migrated to version [{}]", wikiId, newStatus.getDBVersion());
            }

            return newStatus;
        }

        return status;
    }

    /**
     * Start migrations if migrations are enabled.
     *
     * @throws DataMigrationException
     */
    private void tryToProcceedToMigration() throws DataMigrationException
    {
        if (this.hibernateConfiguration.isMigrationEnabled()) {
            // Run migrations
            this.logger.info("Storage schema updates and data migrations are enabled");

            startMigrationsOnlyOnce();

            // TODO: Improve or remove this which is inappropriate in a container environment
            if (this.hibernateConfiguration.isExitAfterMigration()) {
                this.logger.error("Exiting because xwiki.store.migration.exitAfterEnd is set");
                System.exit(0);
            }
        }
    }

    /**
     * Start the migration process only once by synchronization and semaphore.
     *
     * @throws DataMigrationException
     */
    private synchronized void startMigrationsOnlyOnce() throws DataMigrationException
    {
        // migrations hold available migrations and is used like a semaphore to avoid multiple run
        if (this.migrations == null) {
            return;
        }

        this.migrating = true;
        try {
            startMigrations();
        } finally {
            this.migrating = false;
            this.migrations = null;
        }
    }

    @Override
    public boolean isMigrating()
    {
        return this.migrating;
    }

    /**
     * Start the migration process. This one is not thread safe and should be synchronized. The migrations field should
     * not be null.
     *
     * @throws DataMigrationException in case of any error
     */
    protected void startMigrations() throws DataMigrationException
    {
        Set<String> databasesToMigrate = getDatabasesToMigrate();

        this.progress.pushLevelProgress(databasesToMigrate.size(), this);

        try {
            // We should migrate the main wiki first to be able to access subwiki descriptors if needed.
            checkDatabaseLock();
            if (!migrateDatabase(getMainXWiki())) {
                String message = "Main wiki database migration failed, it is not safe to continue!";
                this.logger.error(message);
                throw new DataMigrationException(message);
            }

            int errorCount = 0;
            for (String database : databasesToMigrate) {
                this.progress.startStep(this);

                checkDatabaseLock();
                if (!migrateDatabase(database)) {
                    errorCount++;
                }

                this.progress.endStep(this);
            }

            if (errorCount > 0) {
                String message = String.format("%s wiki database migration(s) failed.", errorCount);
                this.logger.error("[{}] wiki database migration(s) failed.", errorCount);
                throw new DataMigrationException(message);
            }
        } finally {
            this.progress.popLevelProgress(this);
        }
    }

    /**
     * Make sure that this instance still holds the database lock before migrating a database, so that the migration
     * never continues at the same time as the one of another cluster member which acquired the lock after it was
     * lost.
     *
     * @throws DataMigrationException when the database lock has been lost
     */
    private void checkDatabaseLock() throws DataMigrationException
    {
        if (this.databaseLocked && !isDatabaseLockHeld()) {
            String message = "Lost the database migration lock (the connection holding it was closed, for example by"
                + " an idle timeout of the database server or of the network): stopping the migration so that it does"
                + " not run at the same time as the one of another cluster member";
            this.logger.error(message);
            throw new DataMigrationException(message);
        }
    }

    /**
     * Returns the names of the databases that should be migrated. The main wiki database should have been migrated and
     * is never returned. This is controlled through the "xwiki.store.migration.databases" configuration property in
     * xwiki.cfg. A value of "all" or no value at all will add all databases. Note that the main database is
     * automatically added even if not specified.
     *
     * @return The names of all databases to migrate.
     * @throws DataMigrationException if the list of wikis cannot be obtained.
     */
    private Set<String> getDatabasesToMigrate() throws DataMigrationException
    {
        Set<String> databasesToMigrate = new HashSet<>();

        // Add the databases listed by the user (if any). If there's no database name or
        // a single database named and if it's "all" or "ALL" then automatically add all the registered databases.
        List<String> databases = this.hibernateConfiguration.getMigrationDatabases();
        if (databases.isEmpty() || (databases.size() == 1 && "all".equals(databases.getFirst()))) {
            // The main wiki will also be included, but, since we are using a Set, it should not be a problem.
            List<String> allwikis = getVirtualWikisDatabaseNames();
            databasesToMigrate.addAll(allwikis);
        } else {
            databasesToMigrate.addAll(databases);
        }

        // Remove the main wiki if listed since it should have already been migrated.
        databasesToMigrate.remove(getMainXWiki());

        return databasesToMigrate;
    }

    /**
     * Migrate a given database and log error appropriately.
     *
     * @param database name of the database to migrate.
     * @return false if there is an error updating the database.
     */
    private boolean migrateDatabase(String database)
    {
        XWikiContext context = getXWikiContext();

        // Save context values so that we can restore them as they were before the migration.
        String currentDatabase = context.getWikiId();
        String currentOriginalDatabase = context.getOriginalWikiId();

        try {
            // Set up the context so that it points to the virtual wiki corresponding to the
            // database.
            context.setWikiId(database);
            context.setOriginalWikiId(database);

            // Don't update the schema of a database migrated by a more recent version of XWiki
            checkNotNewer(getDBVersion());

            Collection<XWikiMigration> neededMigrations = getNeededMigrations();
            updateSchema(neededMigrations);
            startMigrations(neededMigrations);
        } catch (Exception e) {
            try {
                updateMigrationStatus(getDBVersion(), e);
            } catch (DataMigrationException e1) {
                // Should not happen and could be safely ignored.
            }
            this.logger.error("Failed to migrate database [{}]...", database, e);
            return false;
        } finally {
            context.setWikiId(currentDatabase);
            context.setOriginalWikiId(currentOriginalDatabase);
        }
        return true;
    }

    /**
     * @return collection of {@link DataMigration} in ascending order, which need be executed.
     * @throws DataMigrationException if any error
     */
    protected Collection<XWikiMigration> getNeededMigrations() throws DataMigrationException
    {
        XWikiDBVersion curversion = getDBVersion();
        Collection<XWikiMigration> neededMigrations = new ArrayList<>();

        for (XWikiMigration migration : this.migrations) {
            if (migration.isForced || (migration.dataMigration.getVersion().compareTo(curversion) > 0
                && migration.dataMigration.shouldExecute(curversion))) {
                neededMigrations.add(migration);
            }
        }

        if (this.logger.isInfoEnabled()) {
            logNeededMigrationReport(curversion, neededMigrations);
        }

        return neededMigrations;
    }

    private void logNeededMigrationReport(XWikiDBVersion curversion, Collection<XWikiMigration> neededMigrations)
    {
        String database = getXWikiContext().getWikiId();
        if (!neededMigrations.isEmpty()) {
            this.logger.info("The following data migration(s) will be applied for wiki [{}] currently in version [{}]:",
                database, curversion);
            for (XWikiMigration migration : neededMigrations) {
                this.logger.info("  [{}] - [{}]{}", migration.dataMigration.getName(),
                    migration.dataMigration.getDescription(), (migration.isForced ? " (forced)" : ""));
            }
        } else {
            if (curversion != null) {
                this.logger.info("No data migration to apply for wiki [{}] currently in version [{}]", database,
                    curversion);
            } else {
                this.logger.info("No data migration to apply for empty wiki [{}]", database);
            }
        }
    }

    /**
     * @return a map of forced {@link DataMigration} for this manager
     * @throws DataMigrationException id any error
     */
    protected Map<XWikiDBVersion, XWikiMigration> getForcedMigrations() throws DataMigrationException
    {
        SortedMap<XWikiDBVersion, XWikiMigration> forcedMigrations = new TreeMap<>();
        for (String hint : this.hibernateConfiguration.getForcedMigrations()) {
            try {
                DataMigration dataMigration = this.componentManager.getInstance(DataMigration.class, hint);
                forcedMigrations.put(dataMigration.getVersion(), new XWikiMigration(dataMigration, true));
            } catch (ComponentLookupException e) {
                throw new DataMigrationException("Forced dataMigration " + hint + " component could not be found", e);
            }
        }
        return forcedMigrations;
    }

    /**
     * @param migrations - run this migrations in order of collection
     * @throws DataMigrationException if any error
     */
    protected void startMigrations(Collection<XWikiMigration> migrations) throws DataMigrationException
    {
        XWikiDBVersion curversion = getDBVersion();
        String database = null;
        if (this.logger.isInfoEnabled()) {
            database = getXWikiContext().getWikiId();
        }

        this.progress.pushLevelProgress(migrations.size(), this);

        try {
            for (XWikiMigration migration : migrations) {
                this.progress.startStep(this);

                this.logger.info("Starting data migration [{}] with version [{}] on database [{}]",
                    migration.dataMigration.getName(), migration.dataMigration.getVersion(), database);

                migration.dataMigration.migrate();

                if (migration.dataMigration.getVersion().compareTo(curversion) > 0) {
                    curversion = migration.dataMigration.getVersion();
                    updateMigrationStatus(curversion);
                    if (this.logger.isInfoEnabled()) {
                        this.logger.info(
                            "Data migration [{}] applied successfully, database [{}] upgraded to version [{}]",
                            migration.dataMigration.getName(), database, getDBVersion());
                    }
                } else if (this.logger.isInfoEnabled()) {
                    this.logger.info("Data migration [{}] applied successfully, database [{}] stay in version [{}]",
                        migration.dataMigration.getName(), database, getDBVersion());
                }

                this.progress.endStep(this);
            }
        } finally {
            this.progress.popLevelProgress(this);
        }

        // If migration is launch on an empty DB or latest migration was unneeded, properly set the latest DB version
        setDatabaseToLastestVersion(curversion);
    }

    /**
     * Set the database to the latest version when migration has all been processed. If migration is launch on an empty
     * DB or latest migration was unneeded, this method ensure that the database is properly set the latest DB version.
     *
     * @param currentVersion the current database version
     * @throws DataMigrationException if the version update fails
     */
    private void setDatabaseToLastestVersion(XWikiDBVersion currentVersion) throws DataMigrationException
    {
        if (currentVersion == null) {
            setDBVersion(getLatestVersion());
        } else if (getLatestVersion().compareTo(currentVersion) > 0) {
            updateMigrationStatus(getLatestVersion());
            if (this.logger.isInfoEnabled()) {
                this.logger.info("Database [{}] upgraded to latest version [{}] without needing{} data migration",
                    getXWikiContext().getWikiId(), getDBVersion(), !this.migrations.isEmpty() ? " further" : "");
            }
        }
    }

    /**
     * @return List of all {@link DataMigration} for this manager
     * @throws DataMigrationException if any error
     */
    protected abstract List<? extends DataMigration> getAllMigrations() throws DataMigrationException;
}
