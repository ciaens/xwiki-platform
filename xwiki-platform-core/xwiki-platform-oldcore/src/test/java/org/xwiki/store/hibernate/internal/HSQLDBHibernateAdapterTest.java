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

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validate {@link HSQLDBHibernateAdapter}.
 *
 * @version $Id$
 */
@ComponentTest
class HSQLDBHibernateAdapterTest
{
    @InjectMockComponents
    private HSQLDBHibernateAdapter adapter;

    @Test
    void getColumnName()
    {
        // HSQLDB stores the unquoted column names in upper case, whatever the case used in the mapping
        assertEquals("ASE_PAGE", this.adapter.getColumnName("ase_page"));
        assertEquals("ASE_PAGE", this.adapter.getColumnName("ASE_PAGE"));
        assertNull(this.adapter.getColumnName(null));
    }

    @Test
    void locksAreNotSupported() throws Exception
    {
        // Without locks shared by the processes, acquiring always succeeds and there is no lock to lose
        assertFalse(this.adapter.isLockSupported());
        assertTrue(this.adapter.tryLock("lock", Duration.ZERO));
        assertTrue(this.adapter.tryLock("lock", Duration.ZERO));
        assertTrue(this.adapter.isLockHeld("lock"));
        this.adapter.unlock("lock");
        assertTrue(this.adapter.isLockHeld("lock"));
    }
}
