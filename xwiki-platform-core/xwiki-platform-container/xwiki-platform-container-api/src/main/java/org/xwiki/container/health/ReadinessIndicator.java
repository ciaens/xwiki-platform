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

import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;

/**
 * Reports whether one part of the application is ready to serve requests. Several implementations can exist, each
 * registered with a distinct hint (for example the hint {@code xwiki} for the initialization of the main wiki). They
 * are all aggregated by the health endpoint: the application is ready only when every indicator is.
 * <p>
 * Implementations must return quickly and must never block waiting for the application to be initialized, since they
 * are called by readiness probes while the application is starting.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
@Role
@Unstable
public interface ReadinessIndicator
{
    /**
     * @return the current readiness of the part of the application covered by this indicator, never {@code null}
     */
    Readiness check();
}
