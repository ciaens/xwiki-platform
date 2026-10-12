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
package org.xwiki.store.hibernate.internal.datasource;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.hibernate.boot.cfgxml.internal.ConfigLoader;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.store.hibernate.internal.HibernateCfgXmlLoader;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DBCPHibernateDataSourceProvider}.
 *
 * @version $Id$
 */
@ComponentTest
class DBCPHibernateDataSourceProviderTest
{
    private static final String URL_PROPERTY = "test.xwiki.db.url";

    private static final String USER_PROPERTY = "test.xwiki.db.user";

    private static final String PASSWORD_PROPERTY = "test.xwiki.db.password";

    private static final String MAX_TOTAL_PROPERTY = "test.xwiki.db.pool.maxTotal";

    private static final String URL = "jdbc:xwikitest:xwiki";

    private static final String USER = "xwikiuser";

    private static final String PASSWORD = "xwikipassword";

    /**
     * A JDBC driver that accepts the {@code jdbc:xwikitest:} URLs and records what it was asked to connect with.
     */
    public static class RecordingDriver implements Driver
    {
        private static String lastURL;

        private static Properties lastInfo;

        @Override
        public Connection connect(String url, Properties info)
        {
            if (!acceptsURL(url)) {
                return null;
            }

            lastURL = url;
            lastInfo = info;

            Connection connection = mock();
            try {
                when(connection.isValid(anyInt())).thenReturn(true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            return connection;
        }

        @Override
        public boolean acceptsURL(String url)
        {
            return url != null && url.startsWith("jdbc:xwikitest:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
        {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion()
        {
            return 1;
        }

        @Override
        public int getMinorVersion()
        {
            return 0;
        }

        @Override
        public boolean jdbcCompliant()
        {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException
        {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @InjectMockComponents
    private DBCPHibernateDataSourceProvider provider;

    @MockComponent
    private HibernateCfgXmlLoader cfgXmlLoader;

    @BeforeEach
    void beforeEach()
    {
        RecordingDriver.lastURL = null;
        RecordingDriver.lastInfo = null;

        // Load the configuration file as is, so that its placeholders reach the provider unresolved.
        when(this.cfgXmlLoader.getConfigurationURL())
            .thenReturn(getClass().getClassLoader().getResource("datasource/placeholders.cfg.xml"));
        when(this.cfgXmlLoader.loadConfig(any(), any())).then(invocation -> new ConfigLoader(
            invocation.<BootstrapServiceRegistry>getArgument(0)).loadConfigXmlUrl(invocation.getArgument(1)));
    }

    @AfterEach
    void afterEach() throws Exception
    {
        this.provider.dispose();

        System.clearProperty(URL_PROPERTY);
        System.clearProperty(USER_PROPERTY);
        System.clearProperty(PASSWORD_PROPERTY);
        System.clearProperty(MAX_TOTAL_PROPERTY);
    }

    @Test
    void getDataSourceResolvesPlaceholdersFromSystemProperties() throws Exception
    {
        System.setProperty(URL_PROPERTY, URL);
        System.setProperty(USER_PROPERTY, USER);
        System.setProperty(PASSWORD_PROPERTY, PASSWORD);
        System.setProperty(MAX_TOTAL_PROPERTY, "7");

        DataSource dataSource = this.provider.getDataSource();

        BasicDataSource basicDataSource = assertInstanceOf(BasicDataSource.class, dataSource);
        assertEquals(URL, basicDataSource.getUrl());
        assertEquals(USER, basicDataSource.getUserName());
        assertEquals(PASSWORD, basicDataSource.getPassword());
        assertEquals(7, basicDataSource.getMaxTotal());
        assertEquals(5, basicDataSource.getMaxIdle());
        assertFalse(basicDataSource.getDefaultAutoCommit());

        // The driver received the resolved values when the provider checked the pool.
        assertEquals(URL, RecordingDriver.lastURL);
        assertEquals(USER, RecordingDriver.lastInfo.getProperty("user"));
        assertEquals(PASSWORD, RecordingDriver.lastInfo.getProperty("password"));
    }

    @Test
    void getDataSourceDropsPlaceholdersWithoutSystemProperty() throws Exception
    {
        // Like Hibernate, a placeholder without a matching system property resolves to nothing and the setting is
        // then ignored.
        System.setProperty(URL_PROPERTY, URL);

        BasicDataSource basicDataSource = assertInstanceOf(BasicDataSource.class, this.provider.getDataSource());
        assertEquals(URL, basicDataSource.getUrl());
        assertNull(basicDataSource.getUserName());
        assertNull(basicDataSource.getPassword());
        assertEquals(GenericObjectPoolConfig.DEFAULT_MAX_TOTAL, basicDataSource.getMaxTotal());

        assertNull(RecordingDriver.lastInfo.getProperty("user"));
        assertNull(RecordingDriver.lastInfo.getProperty("password"));
    }
}
