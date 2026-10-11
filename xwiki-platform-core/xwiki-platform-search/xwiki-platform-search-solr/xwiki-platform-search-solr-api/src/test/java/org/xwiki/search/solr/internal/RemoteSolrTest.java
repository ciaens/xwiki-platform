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
package org.xwiki.search.solr.internal;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.component.descriptor.DefaultComponentDependency;
import org.xwiki.component.descriptor.DefaultComponentDescriptor;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.phase.Disposable;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.internal.api.SolrConfiguration;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Validate {@link RemoteSolr}.
 * 
 * @version $Id$
 */
@ComponentTest
@ComponentList({RemoteSolr.class, SolrSchemaUtils.class})
class RemoteSolrTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @MockComponent
    SolrConfiguration solrConfiguration;

    @InjectComponentManager
    MockitoComponentManager componentManager;

    @Test
    void init() throws ComponentLookupException
    {
        System.setProperty("xwiki.solr.remote.requestVersion", "false");

        RemoteSolr solr = this.componentManager.getInstance(Solr.class, RemoteSolr.TYPE);

        assertEquals(RemoteSolr.DEFAULT_BASE_URL, solr.getRootClient().getBaseURL());
    }

    @Test
    void initWithBaseURL() throws ComponentLookupException
    {
        System.setProperty("xwiki.solr.remote.requestVersion", "false");

        when(this.solrConfiguration.getInstanceConfiguration(RemoteSolr.TYPE, "baseURL", null))
            .thenReturn("http://baseurl/");

        RemoteSolr solr = this.componentManager.getInstance(Solr.class, RemoteSolr.TYPE);

        assertEquals("http://baseurl", solr.getRootClient().getBaseURL());
    }

    @Test
    void initWithURL() throws ComponentLookupException
    {
        System.setProperty("xwiki.solr.remote.requestVersion", "false");

        when(this.solrConfiguration.getInstanceConfiguration(RemoteSolr.TYPE, "url", null))
            .thenReturn("http://host/xwiki");

        RemoteSolr solr = this.componentManager.getInstance(Solr.class, RemoteSolr.TYPE);

        assertEquals("http://host", solr.getRootClient().getBaseURL());
        assertEquals(
            "[solr.remote.url] property in xwiki.properties file is deprecated, use [solr.remote.baseURL] instead",
            this.logCapture.getMessage(0));
    }

    @Test
    void initWithURLandBaseURL() throws ComponentLookupException
    {
        System.setProperty("xwiki.solr.remote.requestVersion", "false");

        when(this.solrConfiguration.getInstanceConfiguration(RemoteSolr.TYPE, "url", null))
            .thenReturn("http://host/xwiki");
        when(this.solrConfiguration.getInstanceConfiguration(RemoteSolr.TYPE, "baseURL", null))
            .thenReturn("http://baseurl/");

        RemoteSolr solr = this.componentManager.getInstance(Solr.class, RemoteSolr.TYPE);

        assertEquals("http://baseurl", solr.getRootClient().getBaseURL());
    }

    @Test
    void disposeAfterDefaultPriorityComponents() throws Exception
    {
        List<String> disposed = new ArrayList<>();

        // Components with the default dispose priority, like the event store, may still need Solr while disposed
        Disposable solrUser = () -> disposed.add("user");
        this.componentManager.registerComponent(Disposable.class, "user", solrUser);

        // The DisposePriority annotation is inherited, so it applies to this subclass of RemoteSolr
        Solr solr = new RemoteSolr()
        {
            @Override
            public void dispose()
            {
                disposed.add("solr");
            }
        };
        // Declare that Solr depends on the other component: with the same dispose priority, Solr would be disposed
        // first, since a component is disposed before its dependencies
        DefaultComponentDescriptor<Solr> descriptor = new DefaultComponentDescriptor<>();
        descriptor.setRoleType(Solr.class);
        descriptor.setRoleHint("test");
        DefaultComponentDependency<Disposable> dependency = new DefaultComponentDependency<>();
        dependency.setRoleType(Disposable.class);
        dependency.setRoleHint("user");
        descriptor.addComponentDependency(dependency);
        this.componentManager.registerComponent(descriptor, solr);

        this.componentManager.dispose();

        assertEquals(List.of("user", "solr"), disposed);
    }
}
