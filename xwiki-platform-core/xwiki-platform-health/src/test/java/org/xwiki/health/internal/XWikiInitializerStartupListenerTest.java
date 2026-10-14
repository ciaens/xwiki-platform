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
package org.xwiki.health.internal;

import jakarta.annotation.Priority;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.xwiki.component.descriptor.ComponentDescriptor;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.environment.Environment;
import org.xwiki.environment.internal.ServletEnvironment;
import org.xwiki.jakartabridge.servlet.JakartaServletBridge;
import org.xwiki.observation.event.ApplicationStartedEvent;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.web.XWikiServletContext;
import com.xpn.xwiki.web.XWikiServletRequestStub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link XWikiInitializerStartupListener}.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiInitializerStartupListenerTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @InjectMockComponents
    private XWikiInitializerStartupListener listener;

    @MockComponent
    @Named("xwikiproperties")
    private ConfigurationSource properties;

    @MockComponent
    @Named("xwikicfg")
    private ConfigurationSource xwikicfg;

    @MockComponent(classToMock = ServletEnvironment.class)
    private Environment environment;

    private final ServletContext servletContext = mock();

    private XWikiInitializerStartupListener spy;

    @BeforeEach
    void beforeEach() throws Exception
    {
        when(((ServletEnvironment) this.environment).getJakartaServletContext()).thenReturn(this.servletContext);
        when(this.servletContext.getContextPath()).thenReturn("/xwiki");

        // The initialization itself is static code which cannot be unit tested, intercept it
        this.spy = spy(this.listener);
        doNothing().when(this.spy).startInitialization(any());
    }

    private void onEvent()
    {
        this.spy.onEvent(new ApplicationStartedEvent(), null, null);
    }

    @Test
    void listenerDefinition()
    {
        assertEquals(XWikiInitializerStartupListener.NAME, this.listener.getName());
        assertEquals(1, this.listener.getEvents().size());
        assertTrue(this.listener.getEvents().get(0) instanceof ApplicationStartedEvent);
        // After the listeners with the default priority, among which the one loading the installed extensions
        assertEquals(XWikiInitializerStartupListener.PRIORITY,
            XWikiInitializerStartupListener.class.getAnnotation(Priority.class).value());
        assertTrue(XWikiInitializerStartupListener.PRIORITY > ComponentDescriptor.DEFAULT_PRIORITY);
    }

    @Test
    void disabledByDefault() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(false);
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EXIT_AFTER_UPGRADE, false))
            .thenReturn(false);

        onEvent();

        verify(this.spy, never()).startInitialization(any());
        verify(this.xwikicfg, never()).getProperty(any(), any(Class.class));
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void enabledWithoutHome() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(true);

        onEvent();

        verify(this.spy, never()).startInitialization(any());
        assertEquals(1, this.logCapture.size());
        assertEquals("Eager initialization is enabled ([core.initialization.eager]) but [xwiki.home] is not set in "
            + "xwiki.cfg: it's needed to build the request used by the initialization and the background threads. "
            + "Falling back on initializing XWiki at the first request.", this.logCapture.getMessage(0));
    }

    @Test
    void enabledWithInvalidHome() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(true);
        when(this.xwikicfg.getProperty("xwiki.home", String.class)).thenReturn("not a url");

        onEvent();

        verify(this.spy, never()).startInitialization(any());
        assertEquals(1, this.logCapture.size());
        assertEquals("Eager initialization is enabled ([core.initialization.eager]) but [xwiki.home] is not a valid "
            + "URL ([not a url]). Falling back on initializing XWiki at the first request.",
            this.logCapture.getMessage(0));
    }

    @Test
    void enabledWithoutServletEnvironment() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(true);
        when(this.xwikicfg.getProperty("xwiki.home", String.class)).thenReturn("http://localhost:8080/xwiki/");
        // Replace the servlet environment with a plain one
        ReflectionUtils.setFieldValue(this.listener, "environment", mock(Environment.class));
        this.spy = spy(this.listener);
        doNothing().when(this.spy).startInitialization(any());

        onEvent();

        verify(this.spy, never()).startInitialization(any());
        assertEquals(1, this.logCapture.size());
        assertEquals("Eager initialization is enabled ([core.initialization.eager]) but the environment is not a "
            + "servlet one. Falling back on initializing XWiki at the first request.", this.logCapture.getMessage(0));
    }

    @Test
    void enabledWithHome() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(true);
        when(this.xwikicfg.getProperty("xwiki.home", String.class)).thenReturn("https://wiki.example.com:8443/xwiki/");

        onEvent();

        ArgumentCaptor<XWikiContext> captor = ArgumentCaptor.forClass(XWikiContext.class);
        verify(this.spy).startInitialization(captor.capture());
        XWikiContext xcontext = captor.getValue();

        assertEquals("https://wiki.example.com:8443/xwiki/", xcontext.getURL().toString());
        assertEquals("view", xcontext.getAction());
        assertEquals("xwiki", xcontext.getWikiId());
        assertEquals(XWikiContext.MODE_SERVLET, xcontext.getMode());
        assertSame(this.servletContext,
            JakartaServletBridge.toJakarta(((XWikiServletContext) xcontext.getEngineContext()).getServletContext()));

        XWikiServletRequestStub request = (XWikiServletRequestStub) xcontext.getRequest();
        assertTrue(request.isDaemon());
        assertEquals("https", request.getScheme());
        assertEquals("wiki.example.com", request.getServerName());
        assertEquals(8443, request.getServerPort());
        assertEquals("/xwiki", request.getContextPath());
        assertEquals("/xwiki/", request.getRequestURI());

        assertEquals(1, this.logCapture.size());
        assertEquals("Starting XWiki initialization eagerly with home URL [https://wiki.example.com:8443/xwiki/]",
            this.logCapture.getMessage(0));
    }

    @Test
    void initializationFailure() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(true);
        when(this.xwikicfg.getProperty("xwiki.home", String.class)).thenReturn("http://localhost:8080/xwiki/");
        when(this.servletContext.getContextPath()).thenThrow(new IllegalStateException("broken"));

        onEvent();

        verify(this.spy, never()).startInitialization(any());
        assertEquals(2, this.logCapture.size());
        assertEquals("Starting XWiki initialization eagerly with home URL [http://localhost:8080/xwiki/]",
            this.logCapture.getMessage(0));
        assertEquals("Failed to start XWiki initialization eagerly: [IllegalStateException: broken]",
            this.logCapture.getMessage(1));
    }

    @Test
    void enabledByExitAfterUpgrade() throws Exception
    {
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EAGER, false)).thenReturn(false);
        when(this.properties.getProperty(XWikiInitializerStartupListener.PROPERTY_EXIT_AFTER_UPGRADE, false))
            .thenReturn(true);
        when(this.xwikicfg.getProperty("xwiki.home", String.class)).thenReturn("http://localhost:8080/xwiki/");

        onEvent();

        verify(this.spy).startInitialization(any());
        assertEquals(1, this.logCapture.size());
        assertEquals("Starting XWiki initialization eagerly with home URL [http://localhost:8080/xwiki/]",
            this.logCapture.getMessage(0));
    }
}
