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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.xwiki.stability.Unstable;

/**
 * The result of a {@link ReadinessIndicator} check: a coarse {@link State}, whether requests can be served, and free
 * form details meant for humans or monitoring systems (for example a progress value or an error).
 * <p>
 * The state and the ready flag are not redundant: an application can be in the {@link State#DISTRIBUTION} or
 * {@link State#FAILED} state and still be ready, when the Distribution Wizard is waiting for an administrator (or
 * failed and must be retried by one) and must stay reachable through the load balancer.
 * <p>
 * Detail values are serialized by the health endpoint as JSON: {@link Boolean}, {@link Number} and {@code null} are
 * kept as such, a {@link Throwable} stored under the {@code error} key is reduced to its root cause (class name, or
 * message when the details exposure is enabled), any other value is serialized with {@link Object#toString()}.
 *
 * @param state the state, never {@code null}
 * @param ready true if requests can be served
 * @param details the free form details, never {@code null} and not modifiable
 * @version $Id$
 * @since 18.9.0RC1
 */
@Unstable
public record Readiness(State state, boolean ready, Map<String, Object> details)
{
    /**
     * The key of the detail holding the error, as a {@link Throwable} or a {@link String}.
     */
    public static final String DETAIL_ERROR = "error";

    /**
     * The coarse initialization state reported by a {@link ReadinessIndicator}. The order of the constants is the
     * order of the startup phases: when several indicators are not ready, the aggregated state is the earliest one.
     *
     * @version $Id$
     * @since 18.9.0RC1
     */
    @Unstable
    public enum State
    {
        /**
         * The initialization has not started yet or is running.
         */
        STARTING,

        /**
         * The initialization is running the database schema updates and data migrations.
         */
        MIGRATING,

        /**
         * The initialization is done but the Distribution Wizard (extension upgrades) is pending, running or waiting
         * for an administrator.
         */
        DISTRIBUTION,

        /**
         * Everything is initialized.
         */
        READY,

        /**
         * The initialization ended with an error. Unless {@link Readiness#ready()} is true, the application will not
         * become ready without a restart.
         */
        FAILED
    }

    /**
     * Compact constructor: validates the state and copies the details in a non modifiable map.
     */
    public Readiness
    {
        Objects.requireNonNull(state, "The state cannot be null");
        details = details == null || details.isEmpty() ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * @param state the state
     * @param ready true if requests can be served
     * @param details free form details, may be {@code null}
     * @return the readiness
     */
    public static Readiness of(State state, boolean ready, Map<String, Object> details)
    {
        return new Readiness(state, ready, details);
    }

    /**
     * @param details free form details, may be {@code null}
     * @return a ready {@link State#READY} readiness with the passed details
     */
    public static Readiness ready(Map<String, Object> details)
    {
        return new Readiness(State.READY, true, details);
    }

    /**
     * @param state the state, usually {@link State#STARTING}, {@link State#MIGRATING} or {@link State#DISTRIBUTION}
     * @param details free form details, may be {@code null}
     * @return a not ready readiness in the passed state
     */
    public static Readiness notReady(State state, Map<String, Object> details)
    {
        return new Readiness(state, false, details);
    }

    /**
     * @param error the error message, stored in the {@link #DETAIL_ERROR} detail
     * @return a not ready {@link State#FAILED} readiness
     */
    public static Readiness failed(String error)
    {
        return new Readiness(State.FAILED, false, Collections.singletonMap(DETAIL_ERROR, error));
    }

    /**
     * @param error the error, stored in the {@link #DETAIL_ERROR} detail
     * @return a not ready {@link State#FAILED} readiness
     */
    public static Readiness failed(Throwable error)
    {
        return new Readiness(State.FAILED, false, Collections.singletonMap(DETAIL_ERROR, error));
    }
}
