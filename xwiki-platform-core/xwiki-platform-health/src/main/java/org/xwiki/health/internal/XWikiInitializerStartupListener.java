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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.environment.Environment;
import org.xwiki.environment.internal.ServletEnvironment;
import org.xwiki.jakartabridge.servlet.JakartaServletBridge;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.ApplicationStartedEvent;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.web.Utils;
import com.xpn.xwiki.web.XWikiServletContext;
import com.xpn.xwiki.web.XWikiServletRequestStub;
import com.xpn.xwiki.web.XWikiServletResponseStub;

/**
 * Optionally start the initialization of the main {@link XWiki} instance as soon as the servlet context is started,
 * instead of waiting for the first HTTP request. Enabled with the {@code core.initialization.eager} property of
 * {@code xwiki.properties}, disabled by default.
 * <p>
 * The initialization job needs a {@link XWikiContext} with a request, because
 * {@link com.xpn.xwiki.util.XWikiStubContextProvider} is initialized from it and every background thread (including
 * the initialization itself) gets a clone of that stub context. Without a real request, a daemon
 * {@link XWikiServletRequestStub} is built from the {@code xwiki.home} URL of {@code xwiki.cfg}, which is why that
 * property is required and the listener does nothing (with a warning) when it's not set.
 * <p>
 * The stub context provider is not re-initialized when the first real request arrives: it is initialized by
 * {@link XWiki#getMainXWiki(boolean, XWikiContext)} before the job starts and again by the job and the {@link XWiki}
 * constructor, each time from a context derived from the one that triggered the initialization. Background threads
 * therefore keep using the request that triggered the initialization. Since the stub request is flagged as daemon,
 * the URL factory ignores its host and uses the configured server URL ({@code xwiki.home} or the wiki descriptor)
 * instead, which is also what background threads created after a real first request do.
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
@Component
@Named(XWikiInitializerStartupListener.NAME)
@Singleton
public class XWikiInitializerStartupListener extends AbstractEventListener
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "XWikiInitializerStartupListener";

    /**
     * The {@code xwiki.properties} property enabling the eager initialization.
     */
    public static final String PROPERTY_EAGER = "core.initialization.eager";

    private static final String CFG_HOME = "xwiki.home";

    @Inject
    @Named("xwikiproperties")
    private ConfigurationSource properties;

    @Inject
    @Named("xwikicfg")
    private ConfigurationSource xwikicfg;

    @Inject
    private Environment environment;

    @Inject
    private Logger logger;

    /**
     * Default constructor.
     */
    public XWikiInitializerStartupListener()
    {
        super(NAME, new ApplicationStartedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (!this.properties.getProperty(PROPERTY_EAGER, false)) {
            return;
        }

        String home = this.xwikicfg.getProperty(CFG_HOME, String.class);
        if (StringUtils.isBlank(home)) {
            this.logger.warn("Eager initialization is enabled ([{}]) but [{}] is not set in xwiki.cfg: it's needed "
                + "to build the request used by the initialization and the background threads. Falling back on "
                + "initializing XWiki at the first request.", PROPERTY_EAGER, CFG_HOME);

            return;
        }

        URL homeURL;
        try {
            homeURL = new URI(home).toURL();
        } catch (URISyntaxException | IllegalArgumentException | MalformedURLException e) {
            this.logger.warn("Eager initialization is enabled ([{}]) but [{}] is not a valid URL ([{}]). Falling back "
                + "on initializing XWiki at the first request.", PROPERTY_EAGER, CFG_HOME, home);

            return;
        }

        if (!(this.environment instanceof ServletEnvironment servletEnvironment)) {
            this.logger.warn("Eager initialization is enabled ([{}]) but the environment is not a servlet one. "
                + "Falling back on initializing XWiki at the first request.", PROPERTY_EAGER);

            return;
        }

        this.logger.info("Starting XWiki initialization eagerly with home URL [{}]", homeURL);

        try {
            XWikiServletContext engineContext =
                new XWikiServletContext(JakartaServletBridge.toJavax(servletEnvironment.getJakartaServletContext()));
            XWikiServletRequestStub request = new XWikiServletRequestStub.Builder().setRequestURL(homeURL)
                .setContextPath(engineContext.getServletContext().getContextPath())
                .setRequestParameters(Collections.emptyMap()).build();
            XWikiServletResponseStub response = new XWikiServletResponseStub();

            XWikiContext xcontext = Utils.prepareContext("view", request, response, engineContext);

            startInitialization(xcontext);
        } catch (Exception e) {
            this.logger.error("Failed to start XWiki initialization eagerly: [{}]",
                ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Start the initialization job without waiting for it.
     *
     * @param xcontext the context to initialize the job from
     * @throws XWikiException when failing to start the initialization
     */
    protected void startInitialization(XWikiContext xcontext) throws XWikiException
    {
        XWiki.getMainXWiki(false, xcontext);
    }
}
