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
package com.xpn.xwiki.store.migration.hibernate;

import java.time.Duration;

import org.hibernate.HibernateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.store.hibernate.HibernateAdapter;
import org.xwiki.store.hibernate.HibernateStoreException;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.internal.store.hibernate.HibernateConfiguration;
import com.xpn.xwiki.internal.store.hibernate.HibernateStore;
import com.xpn.xwiki.store.migration.DataMigrationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate the database lock of {@link HibernateDataMigrationManager}.
 *
 * @version $Id$
 */
@ComponentTest
class HibernateDataMigrationManagerTest
{
    private static final String LOCK = "xwiki-migration";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private HibernateDataMigrationManager manager;

    @MockComponent
    private HibernateStore store;

    @MockComponent
    private HibernateConfiguration hibernateConfiguration;

    @Mock
    private HibernateAdapter adapter;

    @BeforeEach
    void setUp()
    {
        when(this.store.getAdapter()).thenReturn(this.adapter);
    }

    @Test
    void lockDelegatesToTheAdapter() throws Exception
    {
        when(this.adapter.isLockSupported()).thenReturn(true);
        when(this.adapter.tryLock(LOCK, Duration.ofSeconds(60))).thenReturn(true);

        assertTrue(this.manager.isDatabaseLockSupported());
        assertTrue(this.manager.tryLockDatabase(Duration.ofSeconds(60)));
        assertFalse(this.manager.tryLockDatabase(Duration.ZERO));
        when(this.adapter.isLockHeld(LOCK)).thenReturn(true, false);
        assertTrue(this.manager.isDatabaseLockHeld());
        assertFalse(this.manager.isDatabaseLockHeld());

        this.manager.unlockDatabase();

        verify(this.adapter).unlock(LOCK);
    }

    @Test
    void lockFailures() throws Exception
    {
        HibernateStoreException lockException = new HibernateStoreException("lock");
        when(this.adapter.tryLock(LOCK, Duration.ZERO)).thenThrow(lockException);
        HibernateStoreException unlockException = new HibernateStoreException("unlock");
        doThrow(unlockException).when(this.adapter).unlock(LOCK);

        DataMigrationException exception =
            assertThrows(DataMigrationException.class, () -> this.manager.tryLockDatabase(Duration.ZERO));
        assertEquals("Failed to acquire the database migration lock", exception.getMessage());
        assertSame(lockException, exception.getCause());

        exception = assertThrows(DataMigrationException.class, () -> this.manager.unlockDatabase());
        assertEquals("Failed to release the database migration lock", exception.getMessage());
        assertSame(unlockException, exception.getCause());
    }

    @Test
    void lockNotSupportedWhenNoAdapter()
    {
        when(this.store.getAdapter()).thenThrow(new HibernateException("no adapter"));

        assertFalse(this.manager.isDatabaseLockSupported());

        assertEquals("Failed to check if the database supports locks: HibernateException: no adapter",
            this.logCapture.getMessage(0));
    }
}
