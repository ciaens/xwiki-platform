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

import java.sql.Driver;
import java.time.Duration;

import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.dialect.Dialect;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Table;
import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;

/**
 * Implement XWiki Hibernate features specific to a RDBMS. The goal is mainly to extend the Hibernate {@link Dialect}
 * and JDBC {@link Driver} concepts with features they don't support (table compression, wiki -> database name, etc.).
 * <p>
 * It's recommended to extend {@link AbstractHibernateAdapter}.
 * <p>
 * A {@link HibernateAdapter} is supposed to be provided by a {@link HibernateAdapterFactory}, but if you need to
 * associate the {@link HibernateAdapter} to a specific database engine (and optionally to a specific minimum version)
 * you can use the following helper format:
 * <ul>
 * <li>"jdbc scheme/minimum version" (for example the "mysql/8" adapter will be selected for a MySQL 9.0.1 server, if no
 * adapter is registered for version 9)</li>
 * <li>"jdbc scheme" (for example the "mysql" adapter will be selected for a MySQL 5.7 server, if no adapter is
 * registered for version 5.7 or lower)</li>
 * </ul>
 * 
 * @version $Id$
 * @since 17.1.0RC1
 */
@Unstable
@Role
public interface HibernateAdapter
{
    /**
     * The name of the meta attribute indicating if a table should be compressed.
     */
    String META_ATTRIBUTE_COMPRESSED = "xwiki-compressed";

    // Configuration

    /**
     * Get the native database/schema name for the current wiki.
     *
     * @return the native database/schema name
     */
    String getDatabaseFromWikiName();

    /**
     * Get the native database/schema name for the passed wiki identifier.
     *
     * @param wikiId the identifier of the wiki
     * @return the native database/schema name
     */
    String getDatabaseFromWikiName(String wikiId);

    /**
     * @return true if the user has configured Hibernate to use XWiki in schema mode (vs database mode)
     */
    boolean isConfiguredInSchemaMode();

    /**
     * @param persistentClass the Hibernate entity
     * @return the table name
     */
    String getTableName(PersistentClass persistentClass);

    /**
     * @param table the Hibernate table representation
     * @return the table name
     */
    String getTableName(Table table);

    /**
     * @param tableName the name of the table
     * @return the name of the table in the right case/format
     */
    String getTableName(String tableName);

    /**
     * Return the name of a column in the case the database uses to store it, for example to look it up in the
     * {@link java.sql.DatabaseMetaData}. Unquoted identifiers are stored in upper case by some databases (e.g. HSQLDB,
     * Oracle) and in lower case by others (e.g. PostgreSQL), whatever the case used in the Hibernate mapping.
     *
     * @param columnName the name of the column, as declared in the Hibernate mapping
     * @return the name of the column in the right case/format
     * @since 18.9.0RC1
     */
    @Unstable
    default String getColumnName(String columnName)
    {
        return columnName;
    }

    /**
     * @param entity the Hibernate entity for which to extract the configuration
     * @return true if the table should be compressed
     */
    boolean isCompressed(PersistentClass entity);

    /**
     * Escape database name to be used in a query.
     *
     * @param databaseName the schema name to escape
     * @return the escaped version
     */
    String escapeDatabaseName(String databaseName);

    /**
     * @return true if compression is enabled for this database. Its possible to force it using hibernate.cfg.xml
     *         {@code xwiki.compression} property, but the default may vary depending on the database.
     */
    boolean isCompressionAllowed();

    // Global

    /**
     * @return the Hibernate {@link SessionFactory}
     */
    SessionFactory getSessionFactory();

    /**
     * @return the Hibernate {@link Dialect}
     */
    Dialect getDialect();

    // Database operations

    /**
     * Automatically update the current database schema to contains what's defined in provided metadata.
     * 
     * @param metadata the metadata we want the current database to follow
     * @throws HibernateStoreException when failing to update the database
     */
    void updateDatabase(Metadata metadata) throws HibernateStoreException;

    /**
     * @return true if the current database product is catalog based, false for a schema based databases
     */
    boolean isCatalog();

    // Locks

    /**
     * Indicate if {@link #tryLock(String, Duration)} provides a lock shared by every process (for example every
     * member of a cluster) accessing the database. When it does not, {@link #tryLock(String, Duration)} always
     * succeeds immediately and only the synchronization of the current process applies.
     *
     * @return true if the database supports locks shared by all the processes using it
     * @since 18.10.0RC1
     */
    @Unstable
    default boolean isLockSupported()
    {
        return false;
    }

    /**
     * Try to acquire a lock shared by every process accessing the database (an advisory lock of the database server,
     * not a lock on a table or a row), waiting at most the passed duration. The lock is held on a dedicated
     * connection until {@link #unlock(String)} is called, and it is released by the database server if that
     * connection is lost (for example when the process is killed, or after an idle timeout of the database server or
     * of the network, see {@link #isLockHeld(String)}). The lock is not re-entrant: trying to acquire a lock already
     * held by this adapter fails with an {@link IllegalStateException}. The callers are expected to serialize the
     * requests for a same lock.
     *
     * @param name the name of the lock, qualified by the adapter with the main wiki database so that several XWiki
     *            instances sharing a database server don't share their locks
     * @param timeout the maximum time to wait for the lock, {@link Duration#ZERO} to try only once
     * @return true if the lock has been acquired, or if the database does not support locks (see
     *         {@link #isLockSupported()}), false if it's held by another process after the passed duration
     * @throws HibernateStoreException when failing to execute the lock request
     * @throws IllegalStateException when the lock is already held by this adapter
     * @since 18.10.0RC1
     */
    @Unstable
    default boolean tryLock(String name, Duration timeout) throws HibernateStoreException
    {
        return true;
    }

    /**
     * Release a lock acquired with {@link #tryLock(String, Duration)}, and the connection it was held on. Does
     * nothing if the lock is not held by this adapter.
     *
     * @param name the name of the lock
     * @throws HibernateStoreException when failing to execute the unlock request (the connection is released anyway)
     * @since 18.10.0RC1
     */
    @Unstable
    default void unlock(String name) throws HibernateStoreException
    {
        // Nothing to release when the database does not support locks
    }

    /**
     * Indicate if a lock acquired with {@link #tryLock(String, Duration)} is still held, that is if the connection
     * holding it is still valid. The database server releases the lock when that connection is closed, which can also
     * happen while it's idle (idle timeout of the database server, of a proxy or of the network), and another process
     * can then acquire it.
     *
     * @param name the name of the lock
     * @return true if the lock is held by this adapter on a valid connection, or if the database does not support
     *         locks (see {@link #isLockSupported()}), false otherwise
     * @since 18.10.0RC1
     */
    @Unstable
    default boolean isLockHeld(String name)
    {
        return true;
    }
}
