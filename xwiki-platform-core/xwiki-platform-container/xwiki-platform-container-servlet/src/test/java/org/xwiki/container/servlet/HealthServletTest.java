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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;
import org.xwiki.container.health.ReadinessIndicator;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link HealthServlet}.
 *
 * @version $Id$
 */
@ComponentTest
class HealthServletTest
{
    @InjectComponentManager
    private MockitoComponentManager componentManager;

    private final HealthServlet servlet = new HealthServlet();

    private final ServletContext servletContext = mock();

    private final HttpServletRequest request = mock();

    private final HttpServletResponse response = mock();

    private final StringWriter body = new StringWriter();

    @BeforeEach
    void beforeEach() throws Exception
    {
        ServletConfig config = mock();
        when(config.getServletContext()).thenReturn(this.servletContext);
        this.servlet.init(config);

        when(this.request.getServletPath()).thenReturn("/health");
        when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));
    }

    private void withComponentManager()
    {
        when(this.servletContext.getAttribute(ComponentManager.class.getName())).thenReturn(this.componentManager);
    }

    private void withDetails(boolean details) throws Exception
    {
        withComponentManager();
        ConfigurationSource configuration =
            this.componentManager.registerMockComponent(ConfigurationSource.class, "xwikiproperties");
        when(configuration.getProperty(HealthServlet.PROPERTY_DETAILS, false)).thenReturn(details);
    }

    private void registerIndicator(String hint, Readiness readiness) throws Exception
    {
        ReadinessIndicator indicator = this.componentManager.registerMockComponent(ReadinessIndicator.class, hint);
        when(indicator.check()).thenReturn(readiness);
    }

    private String get() throws Exception
    {
        this.servlet.doGet(this.request, this.response);

        verify(this.response).setContentType("application/json");
        verify(this.response).setHeader("Cache-Control", "no-store");

        return this.body.toString();
    }

    @Test
    void live() throws Exception
    {
        when(this.request.getServletPath()).thenReturn("/health/live");

        assertEquals("{\"status\":\"alive\"}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_OK);
        verify(this.servletContext, never()).getAttribute(ComponentManager.class.getName());
    }

    @Test
    void noComponentManagerYet() throws Exception
    {
        assertEquals("{\"status\":\"starting\",\"ready\":false,\"checks\":{}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void noIndicator() throws Exception
    {
        withComponentManager();

        assertEquals("{\"status\":\"starting\",\"ready\":false,\"checks\":{}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void allReadyWithoutDetailsByDefault() throws Exception
    {
        withComponentManager();
        registerIndicator("xwiki", Readiness.ready(Map.of("started", true)));
        registerIndicator("distribution", Readiness.ready(Map.of("farmState", "SAME")));

        assertEquals("{\"status\":\"ready\",\"ready\":true,\"checks\":{"
            + "\"distribution\":{\"state\":\"ready\",\"ready\":true},"
            + "\"xwiki\":{\"state\":\"ready\",\"ready\":true}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void allReadyWithDetails() throws Exception
    {
        withDetails(true);
        registerIndicator("xwiki", Readiness.ready(Map.of("started", true)));
        registerIndicator("distribution", Readiness.ready(Map.of("farmState", "SAME")));

        assertEquals("{\"status\":\"ready\",\"ready\":true,\"checks\":{"
            + "\"distribution\":{\"state\":\"ready\",\"ready\":true,\"details\":{\"farmState\":\"SAME\"}},"
            + "\"xwiki\":{\"state\":\"ready\",\"ready\":true,\"details\":{\"started\":true}}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void readyInDistributionState() throws Exception
    {
        withDetails(true);
        registerIndicator("xwiki", Readiness.ready(null));
        registerIndicator("distribution", Readiness.of(State.DISTRIBUTION, true, Map.of("jobState", "WAITING")));

        assertEquals("{\"status\":\"ready\",\"ready\":true,\"checks\":{"
            + "\"distribution\":{\"state\":\"distribution\",\"ready\":true,\"details\":{\"jobState\":\"WAITING\"}},"
            + "\"xwiki\":{\"state\":\"ready\",\"ready\":true,\"details\":{}}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void readyButFailed() throws Exception
    {
        withDetails(false);
        registerIndicator("xwiki", Readiness.ready(null));
        registerIndicator("distribution", Readiness.of(State.FAILED, true,
            Map.of("jobState", "FINISHED", "error", new RuntimeException("wrapper", new Exception("Install failed")))));

        assertEquals("{\"status\":\"failed\",\"ready\":true,\"checks\":{"
            + "\"distribution\":{\"state\":\"failed\",\"ready\":true,\"error\":\"java.lang.Exception\"},"
            + "\"xwiki\":{\"state\":\"ready\",\"ready\":true}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void notReadyEarliestStateWins() throws Exception
    {
        withDetails(true);
        registerIndicator("xwiki", Readiness.notReady(State.MIGRATING, Map.of("progress", 0.25)));
        registerIndicator("distribution", Readiness.notReady(State.DISTRIBUTION, null));
        registerIndicator("other", Readiness.ready(null));

        assertEquals("{\"status\":\"migrating\",\"ready\":false,\"checks\":{"
            + "\"distribution\":{\"state\":\"distribution\",\"ready\":false,\"details\":{}},"
            + "\"other\":{\"state\":\"ready\",\"ready\":true,\"details\":{}},"
            + "\"xwiki\":{\"state\":\"migrating\",\"ready\":false,\"details\":{\"progress\":0.25}}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void failedWinsWithDetails() throws Exception
    {
        withDetails(true);
        registerIndicator("xwiki", Readiness.failed("Could not initialize \"main\" wiki\nline 2 \\ \u0001"));
        registerIndicator("distribution", Readiness.notReady(State.STARTING, null));

        assertEquals("{\"status\":\"failed\",\"ready\":false,\"checks\":{"
            + "\"distribution\":{\"state\":\"starting\",\"ready\":false,\"details\":{}},"
            + "\"xwiki\":{\"state\":\"failed\",\"ready\":false,\"details\":{"
            + "\"error\":\"Could not initialize \\\"main\\\" wiki\\nline 2 \\\\ \\u0001\"}}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void failedWithStringErrorWithoutDetails() throws Exception
    {
        withDetails(false);
        registerIndicator("xwiki", Readiness.failed("secret message"));

        assertEquals("{\"status\":\"failed\",\"ready\":false,\"checks\":{"
            + "\"xwiki\":{\"state\":\"failed\",\"ready\":false}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void indicatorThrowsOrReturnsNull() throws Exception
    {
        withDetails(true);
        ReadinessIndicator failing = this.componentManager.registerMockComponent(ReadinessIndicator.class, "failing");
        when(failing.check()).thenThrow(new IllegalStateException("broken"));
        registerIndicator("nothing", null);

        assertEquals("{\"status\":\"failed\",\"ready\":false,\"checks\":{"
            + "\"failing\":{\"state\":\"failed\",\"ready\":false,\"details\":{"
            + "\"error\":\"IllegalStateException: broken\"}},"
            + "\"nothing\":{\"state\":\"failed\",\"ready\":false,\"details\":{"
            + "\"error\":\"The indicator returned no readiness\"}}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void indicatorThrowsWithoutDetails() throws Exception
    {
        withComponentManager();
        ReadinessIndicator failing = this.componentManager.registerMockComponent(ReadinessIndicator.class, "failing");
        when(failing.check()).thenThrow(new IllegalStateException("broken", new NullPointerException("npe")));

        assertEquals("{\"status\":\"failed\",\"ready\":false,\"checks\":{"
            + "\"failing\":{\"state\":\"failed\",\"ready\":false,\"error\":\"java.lang.NullPointerException\"}}}",
            get());
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void detailValueTypes() throws Exception
    {
        withDetails(true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("string", "text");
        details.put("int", 42);
        details.put("long", 42L);
        details.put("double", 0.5);
        details.put("nan", Double.NaN);
        details.put("boolean", false);
        details.put("null", null);
        details.put("object", State.READY);
        registerIndicator("xwiki", Readiness.ready(details));

        assertEquals("{\"status\":\"ready\",\"ready\":true,\"checks\":{\"xwiki\":{\"state\":\"ready\",\"ready\":true,"
            + "\"details\":{\"string\":\"text\",\"int\":42,\"long\":42,\"double\":0.5,\"nan\":null,\"boolean\":false,"
            + "\"null\":null,\"object\":\"READY\"}}}}", get());
        verify(this.response).setStatus(HttpServletResponse.SC_OK);
    }
}
