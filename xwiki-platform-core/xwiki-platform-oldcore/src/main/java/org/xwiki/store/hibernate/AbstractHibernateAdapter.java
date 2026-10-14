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
package org.xwiki.store.hibernate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Table;
import org.hibernate.tool.hbm2ddl.SchemaUpdate;
import org.hibernate.tool.schema.TargetType;
import org.slf4j.Logger;
import org.xwiki.stability.Unstable;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.internal.store.hibernate.HibernateConfiguration;
import com.xpn.xwiki.internal.store.hibernate.HibernateStore;

/**
 * A base implementation of {@link HibernateAdapter} with a default implementation for each API.
 * 
 * @version $Id$
 * @since 17.1.0RC1
 */
@Unstable
public abstract class AbstractHibernateAdapter implements HibernateAdapter
{
    /**
     * The maximum time in seconds to wait for the database when checking that the connection holding a lock is valid.
     */
    private static final int LOCK_CONNECTION_CHECK_TIMEOUT = 5;

    private static final String VIRTUAL_MODE_SCHEMA = "schema";

    @Inject
    protected WikiDescriptorManager wikis;

    @Inject
    protected Logger logger;

    @Inject
    private HibernateStore hibernateStore;

    @Inject
    private HibernateConfiguration hibernateConfiguration;

    /**
     * The locks held by this adapter, indexed by the name passed to {@link #tryLock(String, Duration)}.
     */
    private final Map<String, HeldLock> heldLocks = new ConcurrentHashMap<>();

    /**
     * A lock held on a dedicated connection.
     *
     * @param lockName the name of the lock in the database
     * @param access the access used to obtain the connection, and to release it
     * @param connection the connection holding the lock
     * @param autoCommit the auto-commit mode of the connection before it was used for the lock
     */
    private record HeldLock(String lockName, JdbcConnectionAccess access, Connection connection, boolean autoCommit)
    {
    }

    // Configuration

    @Override
    public boolean isConfiguredInSchemaMode()
    {
        String virtualModePropertyValue = this.hibernateStore.getConfiguration().getProperty("xwiki.virtual_mode");
        if (virtualModePropertyValue == null) {
            virtualModePropertyValue = VIRTUAL_MODE_SCHEMA;
        }
        return Strings.CS.equals(virtualModePropertyValue, VIRTUAL_MODE_SCHEMA);
    }

    @Override
    public String getDatabaseFromWikiName()
    {
        return getDatabaseFromWikiName(this.wikis.getCurrentWikiId());
    }

    @Override
    public String getDatabaseFromWikiName(String wikiId)
    {
        if (wikiId == null) {
            return null;
        }

        String database = wikiId;

        // Apply the main wiki database configuration
        String mainWikiId = this.wikis.getMainWikiId();
        if (StringUtils.equalsIgnoreCase(wikiId, mainWikiId)) {
            database = this.hibernateConfiguration.getDB();
            if (database == null) {
                // Some databases have special database for main wiki
                database = getDefaultMainWikiDatabase(mainWikiId);
            }
        }

        // Apply prefix
        String prefix = this.hibernateConfiguration.getDBPrefix();
        database = prefix + database;

        // Make sure the database/schema format is correct
        database = cleanDatabaseName(database);

        return database;
    }

    protected String getDefaultMainWikiDatabase(String wikiId)
    {
        return wikiId;
    }

    protected String cleanDatabaseName(String name)
    {
        // Minus (-) is not supported by many databases
        // TODO: move it to adapters which really needs it ?
        return name.replace('-', '_');
    }

    @Override
    public String getTableName(PersistentClass persistentClass)
    {
        return getTableName(persistentClass.getTable());
    }

    @Override
    public String getTableName(Table table)
    {
        return getTableName(table.getName());
    }

    @Override
    public String getTableName(String tableName)
    {
        return tableName;
    }

    @Override
    public boolean isCompressed(PersistentClass entity)
    {
        return entity.getMetaAttribute(META_ATTRIBUTE_COMPRESSED) != null;
    }

    @Override
    public String escapeDatabaseName(String databaseName)
    {
        String closeQuote = String.valueOf(getDialect().closeQuote());
        return getDialect().openQuote() + databaseName.replace(closeQuote, closeQuote + closeQuote) + closeQuote;
    }

    /**
     * @return the value of the compression configuration, or empty if not configured
     */
    protected Optional<Boolean> getCompressionAllowedConfiguration()
    {
        String configuration = this.hibernateStore.getConfiguration().getProperty("xwiki.compressionAllowed");
        if (configuration == null) {
            return Optional.empty();
        }

        return Optional.of(Boolean.valueOf(configuration));
    }

    @Override
    public boolean isCompressionAllowed()
    {
        return getCompressionAllowedConfiguration().orElse(false);
    }

    // Global

    @Override
    public SessionFactory getSessionFactory()
    {
        return this.hibernateStore.getSessionFactory();
    }

    @Override
    public Dialect getDialect()
    {
        return this.hibernateStore.getDialect();
    }

    // Database operations

    @Override
    public void updateDatabase(Metadata metadata) throws HibernateStoreException
    {
        try {
            SessionFactory sessionFactory = getSessionFactory();

            // Custom update before the Hibernate standard DDL
            try (Session session = sessionFactory.openSession()) {
                updateDatabaseBegin(metadata, session);
            }

            // Hibernate standard DDL
            updateDatabaseStandard(metadata);

            // Custom update after the Hibernate standard DDL
            try (Session session = sessionFactory.openSession()) {
                updateDatabaseAfter(metadata, session);
            }
        } catch (Exception e) {
            throw new HibernateException("Failed to update the database", e);
        }
    }

    private void updateDatabaseStandard(Metadata metadata) throws HibernateStoreException
    {
        SchemaUpdate updater = new SchemaUpdate();
        updater.execute(EnumSet.of(TargetType.DATABASE), metadata);
        List<Exception> exceptions = updater.getExceptions();

        if (!exceptions.isEmpty()) {
            // Print the errors
            for (Exception exception : exceptions) {
                this.logger.error("Error raised while updating the database schema", exception);
            }

            throw new HibernateStoreException("Failed to update the database. See the previous log for all errors",
                exceptions.get(0));
        }
    }

    /**
     * Execute everything not handled by the Hibernate DDL which needs to be done before.
     * 
     * @param metadata the configuration of the database to apply
     * @param session the session in which to execute the update
     * @throws HibernateStoreException when failing to update the database
     */
    protected void updateDatabaseBegin(Metadata metadata, Session session) throws HibernateStoreException
    {

    }

    /**
     * Execute everything not handled by the Hibernate DDL which needs to be done after.
     * 
     * @param metadata the configuration of the database to apply
     * @param session the session in which to execute the update
     * @throws HibernateStoreException when failing to update the database
     */
    protected void updateDatabaseAfter(Metadata metadata, Session session) throws HibernateStoreException
    {

    }

    /**
     * @return true if the current database product is catalog based, false for a schema based databases
     */
    public boolean isCatalog()
    {
        return getDialect().canCreateCatalog();
    }

    // Locks

    @Override
    public boolean tryLock(String name, Duration timeout) throws HibernateStoreException
    {
        if (!isLockSupported()) {
            return true;
        }

        if (this.heldLocks.containsKey(name)) {
            throw new IllegalStateException(String.format("The database lock [%s] is already held", name));
        }

        String lockName = getLockName(name);

        JdbcConnectionAccess access = getJdbcConnectionAccess();
        Connection connection = obtainConnection(access);
        boolean acquired = false;
        try {
            boolean autoCommit = connection.getAutoCommit();
            // A session level lock does not need a transaction, and an idle transaction kept open for the duration
            // of the lock would prevent the database from cleaning up
            if (!autoCommit) {
                connection.setAutoCommit(true);
            }
            try {
                acquired = acquireLock(connection, lockName, timeout);
            } finally {
                if (acquired) {
                    this.heldLocks.put(name, new HeldLock(lockName, access, connection, autoCommit));
                } else {
                    restoreAutoCommit(connection, autoCommit);
                }
            }
        } catch (SQLException e) {
            throw new HibernateStoreException(String.format("Failed to acquire the database lock [%s]", lockName), e);
        } finally {
            if (!acquired) {
                releaseConnection(access, connection);
            }
        }

        return acquired;
    }

    @Override
    public void unlock(String name) throws HibernateStoreException
    {
        HeldLock lock = this.heldLocks.remove(name);

        if (lock != null) {
            boolean aborted = false;
            try {
                releaseLock(lock.connection(), lock.lockName());
            } catch (SQLException e) {
                // A connection returned to the pool would keep holding the lock: close the physical connection
                abortConnection(lock.connection());
                aborted = true;

                throw new HibernateStoreException(
                    String.format("Failed to release the database lock [%s]", lock.lockName()), e);
            } finally {
                if (!aborted) {
                    restoreAutoCommit(lock.connection(), lock.autoCommit());
                }
                releaseConnection(lock.access(), lock.connection());
            }
        }
    }

    @Override
    public boolean isLockHeld(String name)
    {
        if (!isLockSupported()) {
            return true;
        }

        HeldLock lock = this.heldLocks.get(name);
        if (lock == null) {
            return false;
        }

        // The database server releases the lock of a closed connection: checking the connection is enough, and the
        // request also resets the idle time of the connection
        try {
            return lock.connection().isValid(LOCK_CONNECTION_CHECK_TIMEOUT);
        } catch (SQLException e) {
            this.logger.warn("Failed to check the connection holding the database lock [{}]: {}", lock.lockName(),
                ExceptionUtils.getRootCauseMessage(e));

            return false;
        }
    }

    /**
     * @param name the name of the lock passed to {@link #tryLock(String, Duration)}
     * @return the name of the lock in the database, qualified with the main wiki database so that several XWiki
     *         instances sharing a database server don't share their locks
     * @since 18.10.0RC1
     */
    @Unstable
    protected String getLockName(String name)
    {
        return name + ':' + getDatabaseFromWikiName(this.wikis.getMainWikiId());
    }

    /**
     * Acquire a session level lock on the passed connection. Called only when {@link #isLockSupported()} is true,
     * with a connection in auto-commit mode.
     *
     * @param connection the connection which will hold the lock
     * @param lockName the name of the lock in the database
     * @param timeout the maximum time to wait for the lock, {@link Duration#ZERO} to try only once
     * @return true if the lock has been acquired, false if it's held by another connection after the passed duration
     * @throws SQLException when failing to execute the lock request
     * @since 18.10.0RC1
     */
    @Unstable
    protected boolean acquireLock(Connection connection, String lockName, Duration timeout) throws SQLException
    {
        return true;
    }

    /**
     * Release a lock acquired with {@link #acquireLock(Connection, String, Duration)} on the same connection.
     *
     * @param connection the connection holding the lock
     * @param lockName the name of the lock in the database
     * @throws SQLException when failing to execute the unlock request
     * @since 18.10.0RC1
     */
    @Unstable
    protected void releaseLock(Connection connection, String lockName) throws SQLException
    {
        // Nothing to release by default
    }

    private JdbcConnectionAccess getJdbcConnectionAccess() throws HibernateStoreException
    {
        // The session is only used to reach the connection provider: the connection obtained from it is not bound to
        // the session and stays open after the session is closed
        try (SessionImplementor session = (SessionImplementor) getSessionFactory().openSession()) {
            return session.getJdbcConnectionAccess();
        } catch (HibernateException e) {
            throw new HibernateStoreException("Failed to access the database connections", e);
        }
    }

    private Connection obtainConnection(JdbcConnectionAccess access) throws HibernateStoreException
    {
        try {
            return access.obtainConnection();
        } catch (SQLException e) {
            throw new HibernateStoreException("Failed to obtain a database connection", e);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean autoCommit)
    {
        if (!autoCommit) {
            try {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                this.logger.warn("Failed to restore the auto-commit mode of a database connection: {}",
                    ExceptionUtils.getRootCauseMessage(e));
            }
        }
    }

    private void abortConnection(Connection connection)
    {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException e) {
            this.logger.warn("Failed to close a database connection holding a lock: {}",
                ExceptionUtils.getRootCauseMessage(e));
        }
    }

    private void releaseConnection(JdbcConnectionAccess access, Connection connection)
    {
        try {
            access.releaseConnection(connection);
        } catch (SQLException e) {
            this.logger.warn("Failed to release a database connection: {}", ExceptionUtils.getRootCauseMessage(e));
        }
    }
}
