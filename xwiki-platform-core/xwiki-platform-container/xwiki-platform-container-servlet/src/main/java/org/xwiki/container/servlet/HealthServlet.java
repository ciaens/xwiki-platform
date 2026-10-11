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
package org.xwiki.container.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;
import org.xwiki.container.health.ReadinessIndicator;
import org.xwiki.container.servlet.internal.HealthJSONWriter;
import org.xwiki.stability.Unstable;

/**
 * Health endpoint meant for load balancers and orchestrators (for example Kubernetes probes). It's mapped on two
 * paths in {@code web.xml}:
 * <ul>
 * <li>{@code /health} (readiness): aggregates all the {@link ReadinessIndicator} components and answers {@code 200}
 * with {@code "ready": true} when every indicator is ready, {@code 503} otherwise (including while the component
 * manager is not available yet). The body is a JSON document of the form
 * {@code {"status":"ready","ready":true,"checks":{"<hint>":{"state":"ready","ready":true,"details":{...}}}}} where
 * the status is {@code failed} if any indicator failed, else the state of the earliest not ready indicator, else
 * {@code ready}. A check can be both failed and ready (for example a failed Distribution Wizard an administrator has
 * to retry): the HTTP status follows the ready flag.</li>
 * <li>{@code /health/live} (liveness): answers {@code 200} with {@code {"status":"alive"}} as soon as the servlet
 * runs, without consulting any indicator.</li>
 * </ul>
 * The {@code details} of the checks and the error messages are only exposed when the {@code xwiki.properties}
 * property {@code container.health.details} is enabled, since the endpoint is not authenticated; otherwise an error is
 * reduced to the class name of its root cause.
 * <p>
 * This servlet never waits for the application to be initialized: it must be reachable during the whole startup,
 * which is why it does not depend on anything else than the component manager stored in the servlet context by
 * {@link XWikiServletContextListener}.
 *
 * @version $Id$
 * @since 18.9.0RC1
 */
@Unstable
public class HealthServlet extends HttpServlet
{
    /**
     * The {@code xwiki.properties} property controlling the exposure of the details.
     */
    public static final String PROPERTY_DETAILS = "container.health.details";

    /**
     * The JSON status used when the component manager is not available yet.
     */
    private static final String STATUS_STARTING = "starting";

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthServlet.class);

    private static final String CONTENT_TYPE = "application/json";

    private static final String LIVE_SUFFIX = "/live";

    private transient ConfigurationSource configuration;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String servletPath = request.getServletPath();
        if (servletPath != null && servletPath.endsWith(LIVE_SUFFIX)) {
            write(response, HttpServletResponse.SC_OK, HealthJSONWriter.toLivenessJSON());
        } else {
            Map<String, Readiness> checks = check();

            // Not ready without any indicator (the component manager is not available yet or nothing is registered)
            boolean ready = !checks.isEmpty() && checks.values().stream().allMatch(Readiness::ready);
            String status = checks.isEmpty() ? STATUS_STARTING : HealthJSONWriter.toJSONName(aggregate(checks));

            write(response, ready ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                HealthJSONWriter.toReadinessJSON(status, ready, checks, isDetailsEnabled()));
        }
    }

    private void write(HttpServletResponse response, int httpStatus, String json) throws IOException
    {
        response.setStatus(httpStatus);
        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        PrintWriter writer = response.getWriter();
        writer.write(json);
        writer.flush();
    }

    /**
     * @param checks the readiness of each indicator
     * @return {@link State#FAILED} if any indicator failed, else the earliest state among the not ready indicators,
     *         else {@link State#READY} (also when there is no indicator at all, the caller handles that case)
     */
    private static State aggregate(Map<String, Readiness> checks)
    {
        State aggregated = State.READY;
        for (Readiness readiness : checks.values()) {
            if (readiness.state() == State.FAILED) {
                return State.FAILED;
            } else if (!readiness.ready() && readiness.state().compareTo(aggregated) < 0) {
                aggregated = readiness.state();
            }
        }

        return aggregated;
    }

    /**
     * @return the readiness of each indicator, by indicator hint, or an empty map when the component manager is not
     *         available yet
     */
    private Map<String, Readiness> check()
    {
        ComponentManager componentManager = getComponentManager();
        if (componentManager == null) {
            return Collections.emptyMap();
        }

        Map<String, ReadinessIndicator> indicators;
        try {
            indicators = componentManager.getInstanceMap(ReadinessIndicator.class);
        } catch (ComponentLookupException e) {
            LOGGER.debug("Failed to lookup the readiness indicators", e);

            return Collections.singletonMap("indicators", Readiness.failed(e));
        }

        // Sorted by hint so that the output is stable
        Map<String, Readiness> checks = new TreeMap<>();
        for (Map.Entry<String, ReadinessIndicator> entry : indicators.entrySet()) {
            Readiness readiness;
            try {
                readiness = entry.getValue().check();
                if (readiness == null) {
                    readiness = Readiness.failed("The indicator returned no readiness");
                }
            } catch (Exception e) {
                // Logged at debug level only since probes are frequent and the error is reported in the response
                LOGGER.debug("The readiness indicator [{}] failed", entry.getKey(), e);

                readiness = Readiness.failed(e);
            }
            checks.put(entry.getKey(), readiness);
        }

        return checks;
    }

    /**
     * @return the component manager stored in the servlet context by the servlet context listener, or {@code null}
     *         when the listener did not run yet
     */
    private ComponentManager getComponentManager()
    {
        return (ComponentManager) getServletContext().getAttribute(ComponentManager.class.getName());
    }

    /**
     * @return true if the details should be exposed, false by default and when the configuration is not available yet
     */
    private boolean isDetailsEnabled()
    {
        if (this.configuration == null) {
            ComponentManager componentManager = getComponentManager();
            if (componentManager == null) {
                return false;
            }

            try {
                this.configuration = componentManager.getInstance(ConfigurationSource.class, "xwikiproperties");
            } catch (ComponentLookupException e) {
                LOGGER.debug("Failed to lookup the configuration", e);

                return false;
            }
        }

        return this.configuration.getProperty(PROPERTY_DETAILS, false);
    }
}
