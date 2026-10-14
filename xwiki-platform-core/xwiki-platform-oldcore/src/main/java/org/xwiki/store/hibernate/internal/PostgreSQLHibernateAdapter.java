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
package org.xwiki.store.hibernate.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.codec.digest.DigestUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.store.hibernate.AbstractHibernateAdapter;
import org.xwiki.store.hibernate.HibernateAdapter;

/**
 * The {@link HibernateAdapter} for PostgreSQL.
 * 
 * @version $Id$
 * @since 17.1.0RC1
 */
@Component
@Named(PostgreSQLHibernateAdapter.HINT)
@Singleton
public class PostgreSQLHibernateAdapter extends AbstractHibernateAdapter
{
    /**
     * The role hint of the component.
     */
    public static final String HINT = "postgresql";

    /**
     * The time to wait between two attempts to acquire a lock held by another connection.
     */
    private static final long LOCK_POLL_INTERVAL = 1000;

    @Override
    protected String getDefaultMainWikiDatabase(String wikiId)
    {
        if (isConfiguredInSchemaMode()) {
            return "public";
        }

        return super.getDefaultMainWikiDatabase(wikiId);
    }

    @Override
    public boolean isCatalog()
    {
        if (isConfiguredInSchemaMode()) {
            return false;
        }

        return super.isCatalog();
    }

    @Override
    public String getColumnName(String columnName)
    {
        // PostgreSQL stores the unquoted column names in lower case
        return columnName != null ? columnName.toLowerCase() : null;
    }

    @Override
    public boolean isLockSupported()
    {
        return true;
    }

    /**
     * PostgreSQL advisory locks are identified by a 64 bits integer and are scoped to the current database.
     *
     * @param lockName the name of the lock
     * @return the key of the advisory lock corresponding to the passed name
     */
    long getLockKey(String lockName)
    {
        return ByteBuffer.wrap(DigestUtils.sha256(lockName.getBytes(StandardCharsets.UTF_8))).getLong();
    }

    @Override
    protected boolean acquireLock(Connection connection, String lockName, Duration timeout) throws SQLException
    {
        long key = getLockKey(lockName);

        // pg_advisory_lock() has no timeout, and the lock_timeout setting would have to be restored on a pooled
        // connection: try without waiting until the timeout instead
        long deadline = System.nanoTime() + timeout.toNanos();
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            while (true) {
                if (getBoolean(statement)) {
                    return true;
                }

                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }

                try {
                    Thread.sleep(Math.min(LOCK_POLL_INTERVAL, Duration.ofNanos(remaining).toMillis() + 1));
                } catch (InterruptedException e) {
                    // Keep the interruption flag: the caller is expected to stop waiting
                    Thread.currentThread().interrupt();

                    return false;
                }
            }
        }
    }

    @Override
    protected void releaseLock(Connection connection, String lockName) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, getLockKey(lockName));
            if (!getBoolean(statement)) {
                this.logger.warn("The database lock [{}] was not held anymore when releasing it", lockName);
            }
        }
    }

    private boolean getBoolean(PreparedStatement statement) throws SQLException
    {
        try (ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }
}
