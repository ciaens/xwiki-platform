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

import org.apache.commons.codec.digest.DigestUtils;
import org.hibernate.SessionFactory;
import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.xwiki.store.hibernate.HibernateStoreException;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.internal.store.hibernate.HibernateConfiguration;
import com.xpn.xwiki.internal.store.hibernate.HibernateStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link PostgreSQLHibernateAdapter}.
 *
 * @version $Id$
 */
@ComponentTest
class PostgreSQLHibernateAdapterTest
{
    private static final String LOCK = "xwiki-migration";

    private static final String LOCK_NAME = "xwiki-migration:xwiki";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private PostgreSQLHibernateAdapter adapter;

    @MockComponent
    private WikiDescriptorManager wikis;

    @MockComponent
    private HibernateStore hibernateStore;

    @MockComponent
    private HibernateConfiguration hibernateConfiguration;

    @Mock
    private SessionFactory sessionFactory;

    @Mock
    private SessionImplementor session;

    @Mock
    private JdbcConnectionAccess connectionAccess;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    @BeforeEach
    void beforeEach() throws Exception
    {
        when(this.wikis.getMainWikiId()).thenReturn("xwiki");
        when(this.hibernateConfiguration.getDB()).thenReturn("xwiki");
        when(this.hibernateConfiguration.getDBPrefix()).thenReturn("");

        when(this.hibernateStore.getSessionFactory()).thenReturn(this.sessionFactory);
        when(this.sessionFactory.openSession()).thenReturn(this.session);
        when(this.session.getJdbcConnectionAccess()).thenReturn(this.connectionAccess);
        when(this.connectionAccess.obtainConnection()).thenReturn(this.connection);
        when(this.connection.prepareStatement(anyString())).thenReturn(this.statement);
        when(this.statement.executeQuery()).thenReturn(this.resultSet);
        when(this.resultSet.next()).thenReturn(true);
    }

    private long expectedKey()
    {
        return ByteBuffer.wrap(DigestUtils.sha256(LOCK_NAME.getBytes(StandardCharsets.UTF_8))).getLong();
    }

    @Test
    void getColumnName()
    {
        // PostgreSQL stores the unquoted column names in lower case, whatever the case used in the mapping
        assertEquals("xwd_fullname", this.adapter.getColumnName("XWD_FULLNAME"));
        assertEquals("xwd_fullname", this.adapter.getColumnName("xwd_fullname"));
        assertNull(this.adapter.getColumnName(null));
    }

    @Test
    void getLockKey()
    {
        assertTrue(this.adapter.isLockSupported());
        assertEquals(expectedKey(), this.adapter.getLockKey(LOCK_NAME));
    }

    @Test
    void tryLockAndUnlock() throws Exception
    {
        // The pool hands out connections in transaction mode: the lock is taken in auto-commit mode
        when(this.connection.getAutoCommit()).thenReturn(false);
        when(this.resultSet.getBoolean(1)).thenReturn(true);

        assertTrue(this.adapter.tryLock(LOCK, Duration.ZERO));

        verify(this.connection).setAutoCommit(true);
        verify(this.connection).prepareStatement("SELECT pg_try_advisory_lock(?)");
        verify(this.statement).setLong(1, expectedKey());
        // The connection is held until the lock is released
        verify(this.connectionAccess, never()).releaseConnection(any());
        when(this.connection.isValid(5)).thenReturn(true);
        assertTrue(this.adapter.isLockHeld(LOCK));
        // Not re-entrant
        IllegalStateException reentrance =
            assertThrows(IllegalStateException.class, () -> this.adapter.tryLock(LOCK, Duration.ZERO));
        assertEquals("The database lock [xwiki-migration] is already held", reentrance.getMessage());
        verify(this.connectionAccess).obtainConnection();

        this.adapter.unlock(LOCK);

        InOrder order = inOrder(this.connection, this.connectionAccess);
        order.verify(this.connection).prepareStatement("SELECT pg_advisory_unlock(?)");
        order.verify(this.connection).setAutoCommit(false);
        order.verify(this.connectionAccess).releaseConnection(this.connection);

        // Releasing a lock which is not held does nothing
        this.adapter.unlock(LOCK);
        verify(this.connectionAccess).releaseConnection(this.connection);
        assertFalse(this.adapter.isLockHeld(LOCK));
    }

    @Test
    void tryLockWhenHeldByAnotherConnection() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.resultSet.getBoolean(1)).thenReturn(false);

        assertFalse(this.adapter.tryLock(LOCK, Duration.ZERO));

        verify(this.connection, never()).setAutoCommit(anyBoolean());
        verify(this.statement).executeQuery();
        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void tryLockWaitsUntilAvailable() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.resultSet.getBoolean(1)).thenReturn(false, true);

        assertTrue(this.adapter.tryLock(LOCK, Duration.ofSeconds(10)));

        verify(this.statement, times(2)).executeQuery();
        verify(this.connectionAccess, never()).releaseConnection(any());
    }

    @Test
    void tryLockGivesUpAfterTimeout() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.resultSet.getBoolean(1)).thenReturn(false);

        assertFalse(this.adapter.tryLock(LOCK, Duration.ofMillis(50)));

        verify(this.statement, times(2)).executeQuery();
        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void tryLockWhenInterrupted() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.resultSet.getBoolean(1)).thenReturn(false);

        Thread.currentThread().interrupt();
        try {
            assertFalse(this.adapter.tryLock(LOCK, Duration.ofSeconds(10)));
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
        }

        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void tryLockWhenRequestFails() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(false);
        SQLException exception = new SQLException("failed");
        when(this.statement.executeQuery()).thenThrow(exception);

        HibernateStoreException thrown =
            assertThrows(HibernateStoreException.class, () -> this.adapter.tryLock(LOCK, Duration.ZERO));

        assertEquals("Failed to acquire the database lock [xwiki-migration:xwiki]", thrown.getMessage());
        assertSame(exception, thrown.getCause());
        InOrder order = inOrder(this.connection, this.connectionAccess);
        order.verify(this.connection).setAutoCommit(true);
        order.verify(this.connection).setAutoCommit(false);
        order.verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void tryLockWhenNoConnection() throws Exception
    {
        SQLException exception = new SQLException("no connection");
        when(this.connectionAccess.obtainConnection()).thenThrow(exception);

        HibernateStoreException thrown =
            assertThrows(HibernateStoreException.class, () -> this.adapter.tryLock(LOCK, Duration.ZERO));

        assertEquals("Failed to obtain a database connection", thrown.getMessage());
        assertSame(exception, thrown.getCause());
    }

    @Test
    void unlockWhenRequestFails() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(false);
        when(this.resultSet.getBoolean(1)).thenReturn(true);
        assertTrue(this.adapter.tryLock(LOCK, Duration.ZERO));

        when(this.statement.executeQuery()).thenThrow(new SQLException("failed"));
        doThrow(new SQLException("release failed")).when(this.connectionAccess).releaseConnection(this.connection);

        HibernateStoreException thrown = assertThrows(HibernateStoreException.class, () -> this.adapter.unlock(LOCK));

        assertEquals("Failed to release the database lock [xwiki-migration:xwiki]", thrown.getMessage());
        // The connection still holding the lock must not go back to the pool as is
        verify(this.connection).abort(any());
        // The auto-commit mode of an aborted connection is not restored
        verify(this.connection, never()).setAutoCommit(false);
        verify(this.connectionAccess).releaseConnection(this.connection);
        assertEquals("Failed to release a database connection: SQLException: release failed",
            this.logCapture.getMessage(0));
    }

    @Test
    void unlockWhenNotHeldAnymore() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.resultSet.getBoolean(1)).thenReturn(true, false);
        assertTrue(this.adapter.tryLock(LOCK, Duration.ZERO));

        this.adapter.unlock(LOCK);

        assertEquals("The database lock [xwiki-migration:xwiki] was not held anymore when releasing it",
            this.logCapture.getMessage(0));
        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void isLockHeldChecksTheConnection() throws Exception
    {
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.resultSet.getBoolean(1)).thenReturn(true);
        assertTrue(this.adapter.tryLock(LOCK, Duration.ZERO));

        // A connection closed by the database server (for example after an idle timeout) lost its lock
        when(this.connection.isValid(5)).thenReturn(true, false);
        assertTrue(this.adapter.isLockHeld(LOCK));
        assertFalse(this.adapter.isLockHeld(LOCK));

        when(this.connection.isValid(5)).thenThrow(new SQLException("failed"));
        assertFalse(this.adapter.isLockHeld(LOCK));
        assertEquals("Failed to check the connection holding the database lock [xwiki-migration:xwiki]:"
            + " SQLException: failed", this.logCapture.getMessage(0));

        // Only the connection holding the lock is used
        verify(this.connectionAccess).obtainConnection();
    }
}
