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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link MySQLHibernateAdapter}.
 *
 * @version $Id$
 */
@ComponentTest
class MySQLHibernateAdapterTest
{
    private static final String LOCK = "xwiki-migration";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MySQLHibernateAdapter adapter;

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
        when(this.hibernateStore.getDialect()).thenReturn(new MySQLDialect());
        when(this.hibernateConfiguration.getDBPrefix()).thenReturn("");
        when(this.wikis.getMainWikiId()).thenReturn("xwiki");

        when(this.hibernateStore.getSessionFactory()).thenReturn(this.sessionFactory);
        when(this.sessionFactory.openSession()).thenReturn(this.session);
        when(this.session.getJdbcConnectionAccess()).thenReturn(this.connectionAccess);
        when(this.connectionAccess.obtainConnection()).thenReturn(this.connection);
        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.connection.prepareStatement(anyString())).thenReturn(this.statement);
        when(this.statement.executeQuery()).thenReturn(this.resultSet);
        when(this.resultSet.next()).thenReturn(true);
    }

    @Test
    void getAlterRowFormatStringIsQualifiedWithTheCurrentWikiDatabase() throws Exception
    {
        when(this.wikis.getCurrentWikiId()).thenReturn("subwiki");

        // The statement must name the database explicitly, otherwise it is executed against whichever database the
        // pooled JDBC connection happens to be on.
        assertEquals("ALTER TABLE `subwiki`.xwikircs ROW_FORMAT=Dynamic",
            this.adapter.getAlterRowFormatString("xwikircs", false, Map.of("xwikircs", "Compact"), null));
        assertEquals("ALTER TABLE `subwiki`.xwikircs ROW_FORMAT=Compressed",
            this.adapter.getAlterRowFormatString("xwikircs", true, Map.of(), null));
    }

    @Test
    void getAlterRowFormatStringWhenNoCurrentWiki() throws Exception
    {
        when(this.wikis.getCurrentWikiId()).thenReturn(null);

        assertEquals("ALTER TABLE xwikircs ROW_FORMAT=Dynamic",
            this.adapter.getAlterRowFormatString("xwikircs", false, Map.of(), null));
    }

    @Test
    void getLockName()
    {
        assertTrue(this.adapter.isLockSupported());
        // The locks are shared by all the databases of the server: the name includes the main wiki database
        assertEquals("xwiki-migration:xwiki", this.adapter.getLockName(LOCK));

        // GET_LOCK() names are limited to 64 characters
        String database = "d".repeat(60);
        when(this.hibernateConfiguration.getDB()).thenReturn(database);
        assertEquals(DigestUtils.sha256Hex("xwiki-migration:" + database), this.adapter.getLockName(LOCK));
        assertEquals(64, this.adapter.getLockName(LOCK).length());
    }

    @Test
    void tryLockAndUnlock() throws Exception
    {
        when(this.resultSet.getInt(1)).thenReturn(1);

        assertTrue(this.adapter.tryLock(LOCK, Duration.ofMillis(1500)));

        verify(this.connection).prepareStatement("SELECT GET_LOCK(?, ?)");
        verify(this.statement).setString(1, "xwiki-migration:xwiki");
        // GET_LOCK() takes whole seconds
        verify(this.statement).setLong(2, 2);
        verify(this.connectionAccess, never()).releaseConnection(any());
        when(this.connection.isValid(5)).thenReturn(true);
        assertTrue(this.adapter.isLockHeld(LOCK));

        this.adapter.unlock(LOCK);

        verify(this.connection).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(this.statement, times(2)).setString(1, "xwiki-migration:xwiki");
        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void tryLockWhenHeldByAnotherConnection() throws Exception
    {
        when(this.resultSet.getInt(1)).thenReturn(0);

        assertFalse(this.adapter.tryLock(LOCK, Duration.ZERO));

        verify(this.statement).setLong(2, 0);
        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void unlockWhenNotHeldAnymore() throws Exception
    {
        when(this.resultSet.getInt(1)).thenReturn(1, 0);
        assertTrue(this.adapter.tryLock(LOCK, Duration.ZERO));

        this.adapter.unlock(LOCK);

        assertEquals("The database lock [xwiki-migration:xwiki] was not held anymore when releasing it",
            this.logCapture.getMessage(0));
        verify(this.connectionAccess).releaseConnection(this.connection);
    }

    @Test
    void tryLockWhenSessionCannotBeOpened()
    {
        when(this.sessionFactory.openSession()).thenThrow(new HibernateException("closed"));

        HibernateStoreException thrown =
            assertThrows(HibernateStoreException.class, () -> this.adapter.tryLock(LOCK, Duration.ZERO));

        assertEquals("Failed to access the database connections", thrown.getMessage());
    }

    @Test
    void tryLockWhenAutoCommitCannotBeRead() throws Exception
    {
        when(this.connection.getAutoCommit()).thenThrow(new SQLException("closed"));

        assertThrows(HibernateStoreException.class, () -> this.adapter.tryLock(LOCK, Duration.ZERO));

        verify(this.connectionAccess).releaseConnection(this.connection);
    }
}
