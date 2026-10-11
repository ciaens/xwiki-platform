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
package org.xwiki.container.health;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.xwiki.container.health.Readiness.State;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validate {@link Readiness}.
 *
 * @version $Id$
 */
class ReadinessTest
{
    @Test
    void factories()
    {
        Readiness ready = Readiness.ready(null);
        assertEquals(State.READY, ready.state());
        assertTrue(ready.ready());
        assertTrue(ready.details().isEmpty());

        Readiness readyWithDetails = Readiness.ready(Map.of("farmState", "SAME"));
        assertEquals(State.READY, readyWithDetails.state());
        assertTrue(readyWithDetails.ready());
        assertEquals("SAME", readyWithDetails.details().get("farmState"));

        Readiness notReady = Readiness.notReady(State.MIGRATING, Map.of("progress", 0.5));
        assertEquals(State.MIGRATING, notReady.state());
        assertFalse(notReady.ready());
        assertEquals(0.5, notReady.details().get("progress"));

        Readiness failed = Readiness.failed("boom");
        assertEquals(State.FAILED, failed.state());
        assertFalse(failed.ready());
        assertEquals("boom", failed.details().get(Readiness.DETAIL_ERROR));

        Exception exception = new Exception("boom");
        Readiness failedWithException = Readiness.failed(exception);
        assertEquals(State.FAILED, failedWithException.state());
        assertFalse(failedWithException.ready());
        assertSame(exception, failedWithException.details().get(Readiness.DETAIL_ERROR));

        Readiness waiting = Readiness.of(State.DISTRIBUTION, true, null);
        assertEquals(State.DISTRIBUTION, waiting.state());
        assertTrue(waiting.ready());
        assertTrue(waiting.details().isEmpty());
    }

    @Test
    void detailsAreCopiedAndNotModifiable()
    {
        Map<String, Object> details = new HashMap<>();
        details.put("a", 1);
        Readiness readiness = Readiness.ready(details);
        details.put("b", 2);

        assertEquals(1, readiness.details().size());
        assertThrows(UnsupportedOperationException.class, () -> readiness.details().put("c", 3));
    }

    @Test
    void nullState()
    {
        assertThrows(NullPointerException.class, () -> Readiness.of(null, true, null));
    }

    @Test
    void equalsHashCodeToString()
    {
        Readiness readiness1 = Readiness.notReady(State.STARTING, Map.of("started", false));
        Readiness readiness2 = Readiness.notReady(State.STARTING, Map.of("started", false));

        assertEquals(readiness1, readiness2);
        assertEquals(readiness1.hashCode(), readiness2.hashCode());
        assertNotEquals(readiness1, Readiness.ready(null));
        assertNotEquals(readiness1, Readiness.of(State.STARTING, true, Map.of("started", false)));
        assertEquals("Readiness[state=STARTING, ready=false, details={started=false}]", readiness1.toString());
    }
}
