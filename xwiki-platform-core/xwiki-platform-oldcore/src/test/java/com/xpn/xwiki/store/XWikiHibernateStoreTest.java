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
package com.xpn.xwiki.store;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Named;
import javax.inject.Provider;
import javax.persistence.PersistenceException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.dialect.Dialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.xwiki.bridge.event.ActionExecutingEvent;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.ObservationManager;
import org.xwiki.query.QueryManager;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.store.hibernate.HibernateAdapter;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.AfterComponent;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.CoreConfiguration;
import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.XWikiLock;
import com.xpn.xwiki.doc.XWikiSpace;
import com.xpn.xwiki.internal.store.hibernate.HibernateConfiguration;
import com.xpn.xwiki.internal.store.hibernate.HibernateStore;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.LargeStringProperty;
import com.xpn.xwiki.objects.StringProperty;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link XWikiHibernateStore} class.
 * 
 * @version $Id$
 */
@ComponentTest
@ReferenceComponentList
class XWikiHibernateStoreTest
{
    private static final String WIKI_NAME = "wiki";

    private static final WikiReference WIKI_REFERENCE = new WikiReference(WIKI_NAME);

    private static final String DELETE_LOCK_QUERY = "delete from XWikiLock as lock where lock.docId = :docId";

    private static final String DOCUMENT_ID_QUERY =
        "select xwikidoc.id from XWikiDocument as xwikidoc where xwikidoc.id = :id";

    private static final String MAKE_SPACE_VISIBLE_QUERY =
        "update XWikiSpace as space set space.hidden = false where space.id = :id and space.hidden = true";

    private static final SpaceReference PARENT_SPACE = new SpaceReference(WIKI_NAME, "Parent");

    private static final SpaceReference CHILD_SPACE = new SpaceReference("Child", PARENT_SPACE);

    private static final DocumentReference CHILD_DOCUMENT = new DocumentReference("Page", CHILD_SPACE);

    /**
     * A special component manager that mocks automatically all the dependencies of the component under test.
     */
    @InjectMockComponents
    private XWikiHibernateStore store;

    @Mock
    private XWikiContext xcontext;

    /**
     * The Hibernate session.
     */
    @Mock
    private Session session;

    @Mock
    private HibernateAdapter adapter;

    /**
     * The session factory used for the transactions on the space table.
     */
    @Mock
    private SessionFactory sessionFactory;

    /**
     * The session of the transactions on the space table.
     */
    @Mock
    private Session spaceSession;

    /**
     * The Hibernate transaction used on the space table.
     */
    @Mock
    private Transaction spaceTransaction;

    /**
     * The Hibernate transaction.
     */
    @Mock
    private Transaction transaction;

    @MockComponent
    private HibernateStore hibernateStore;

    @MockComponent
    private Execution execution;

    @MockComponent
    private ObservationManager observationManager;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private HibernateConfiguration hibernateConfiguration;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    @Named("readonly")
    private Provider<XWikiContext> readOnlyContextProvider;

    @MockComponent
    private WikiDescriptorManager wikiDescriptorManager;

    @MockComponent
    private CoreConfiguration coreConfiguration;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    /**
     * The session that {@link HibernateStore} keeps in the execution context.
     */
    private Session currentSession;

    /**
     * The transaction that {@link HibernateStore} keeps in the execution context.
     */
    private Transaction currentTransaction;

    /**
     * Whether the session of the document transaction is open.
     */
    private boolean documentSessionOpen;

    /**
     * The number of transactions on the space table that started while the session of the document transaction was
     * open.
     */
    private int nestedSpaceTransactions;

    @AfterComponent
    void afterComponent()
    {
        when(this.hibernateConfiguration.getOptimizedXObjectClasses()).thenReturn(Optional.empty());
    }

    @BeforeEach
    void setUp(MockitoComponentManager componentManager) throws Exception
    {
        Utils.setComponentManager(componentManager);
        when(this.coreConfiguration.getDefaultDocumentSyntax()).thenReturn(Syntax.XWIKI_2_1);

        when(this.xcontext.getWikiId()).thenReturn(WIKI_NAME);
        when(this.xcontext.getWikiReference()).thenReturn(WIKI_REFERENCE);

        // For XWikiHibernateBaseStore#initialize()

        ExecutionContext executionContext = mock(ExecutionContext.class);
        when(executionContext.getProperty("xwikicontext")).thenReturn(xcontext);

        when(execution.getContext()).thenReturn(executionContext);

        when(contextProvider.get()).thenReturn(this.xcontext);
        when(readOnlyContextProvider.get()).thenReturn(this.xcontext);

        XWiki wiki = mock(XWiki.class);
        when(this.xcontext.getWiki()).thenReturn(wiki);

        // For XWikiHibernateBaseStore#beginTransaction()

        SessionFactory wrappedSessionFactory = mock(SessionFactory.class);
        when(wrappedSessionFactory.openSession()).thenReturn(session);
        when(session.beginTransaction()).thenReturn(transaction);

        // Simulate the session and the transaction that HibernateStore keeps in the execution context: by default a
        // transaction is in progress
        this.currentSession = this.session;
        this.currentTransaction = this.transaction;
        this.documentSessionOpen = true;
        when(this.hibernateStore.getCurrentSession()).thenAnswer(invocation -> this.currentSession);
        when(this.hibernateStore.getCurrentTransaction()).thenAnswer(invocation -> this.currentTransaction);
        doAnswer(invocation -> {
            this.currentSession = invocation.getArgument(0);
            return null;
        }).when(this.hibernateStore).setCurrentSession(any());
        doAnswer(invocation -> {
            this.currentTransaction = invocation.getArgument(0);
            return null;
        }).when(this.hibernateStore).setCurrentTransaction(any());
        // The transaction of a document
        when(this.hibernateStore.beginTransaction(any(SessionFactory.class)))
            .thenAnswer(invocation -> beginTransaction(this.session));
        // The transactions on the space table
        when(this.hibernateStore.beginTransaction())
            .thenAnswer(invocation -> beginTransaction(this.sessionFactory.openSession()));
        doAnswer(invocation -> {
            endTransaction(invocation.getArgument(0));
            return null;
        }).when(this.hibernateStore).endTransaction(anyBoolean());

        when(this.hibernateStore.getAdapter()).thenReturn(this.adapter);
        // Default is schema mode
        when(this.adapter.isConfiguredInSchemaMode()).thenReturn(true);

        // For the transactions on the space table
        when(this.hibernateStore.getSessionFactory()).thenReturn(this.sessionFactory);
        when(this.sessionFactory.openSession()).thenReturn(this.spaceSession);
        when(this.spaceSession.beginTransaction()).thenReturn(this.spaceTransaction);
    }

    private XWikiDocument saveNewDocument(DocumentReference documentReference) throws XWikiException
    {
        Query queryDocument = mock();
        when(this.session.createQuery(DOCUMENT_ID_QUERY)).thenReturn(queryDocument);

        XWikiDocument document = new XWikiDocument(documentReference);
        this.store.saveXWikiDoc(document, this.xcontext, true);

        return document;
    }

    private void noTransactionInProgress()
    {
        this.currentSession = null;
        this.currentTransaction = null;
        this.documentSessionOpen = false;
    }

    private Query mockMakeSpaceVisibleQuery()
    {
        Query query = mock();
        when(this.session.createQuery(MAKE_SPACE_VISIBLE_QUERY)).thenReturn(query);

        return query;
    }

    @Test
    void getColumnsForSelectStatement()
    {
        assertEquals(", doc.date", store.getColumnsForSelectStatement("where 1=1 order by doc.date desc"));
        assertEquals(", doc.date", store.getColumnsForSelectStatement("where 1=1 order by doc.date asc"));
        assertEquals(", doc.date", store.getColumnsForSelectStatement("where 1=1 order by doc.date"));
        assertEquals(", description", store.getColumnsForSelectStatement("where 1=1 order by description desc"));
        assertEquals(", ascendent", store.getColumnsForSelectStatement("where 1=1 order by ascendent asc"));
        assertEquals(", doc.date, doc.name",
            store.getColumnsForSelectStatement("where 1=1 order by doc.date, doc.name"));
        assertEquals(", doc.date, doc.name",
            store.getColumnsForSelectStatement("where 1=1 order by doc.date ASC, doc.name DESC"));
        assertEquals("", store.getColumnsForSelectStatement(", BaseObject as obj where obj.name=doc.fullName"));
    }

    @Test
    void createSQLQuery()
    {
        assertEquals("select distinct doc.space, doc.name from XWikiDocument as doc",
            store.createSQLQuery("select distinct doc.space, doc.name", ""));
        assertEquals(
            "select distinct doc.space, doc.name, doc.date from XWikiDocument as doc "
                + "where 1=1 order by doc.date desc",
            store.createSQLQuery("select distinct doc.space, doc.name", "where 1=1 order by doc.date desc"));
    }

    @Test
    void executeDeleteWikiStatementForPostgreSQLWhenInSchemaMode() throws Exception
    {
        Statement statement = mock(Statement.class);
        DatabaseProduct databaseProduct = DatabaseProduct.POSTGRESQL;

        store.executeDeleteWikiStatement(statement, databaseProduct, "schema");

        verify(statement).execute("DROP SCHEMA schema CASCADE");
    }

    @Test
    void executeDeleteWikiStatementForPostgreSQLWhenInDatabaseMode() throws Exception
    {
        when(this.adapter.isConfiguredInSchemaMode()).thenReturn(false);

        Statement statement = mock(Statement.class);
        DatabaseProduct databaseProduct = DatabaseProduct.POSTGRESQL;

        store.executeDeleteWikiStatement(statement, databaseProduct, "schema");

        assertEquals("Subwiki deletion not yet supported in Database mode for PostgreSQL", logCapture.getMessage(0));
        verify(statement, never()).execute(any(String.class));
    }

    @Test
    void locksAreReleasedOnLogout() throws Exception
    {
        // Capture the event listener.
        ArgumentCaptor<EventListener> eventListenerCaptor = ArgumentCaptor.forClass(EventListener.class);
        verify(observationManager).addListener(eventListenerCaptor.capture());
        assertEquals("deleteLocksOnLogoutListener", eventListenerCaptor.getValue().getName());

        Query query = mock(Query.class);
        when(session.createQuery("delete from XWikiLock as lock where lock.userName=:userName")).thenReturn(query);
        when(xcontext.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "LoggerOutter"));
        when(xcontext.getUser()).thenReturn("XWiki.LoggerOutter");
        when(this.hibernateStore.beginTransaction()).thenReturn(true);

        // Fire the logout event.
        eventListenerCaptor.getValue().onEvent(new ActionExecutingEvent("logout"), null, xcontext);

        verify(query).setParameter("userName", "XWiki.LoggerOutter");
        verify(query).executeUpdate();
        verify(this.hibernateStore).beginTransaction();
        verify(this.hibernateStore).endTransaction(true);
    }

    @Test
    void deleteLock() throws Exception
    {
        Query query = mock(Query.class);
        when(this.session.createQuery(DELETE_LOCK_QUERY)).thenReturn(query);
        when(this.hibernateStore.beginTransaction()).thenReturn(true);

        this.store.deleteLock(new XWikiLock(13L, "XWiki.Alice"), this.xcontext, true);

        // A bulk delete is used, rather than deleting the entity, so that removing a lock that has been removed in the
        // mean time is a no-op.
        verify(this.session, never()).delete(any());
        verify(query).setParameter("docId", 13L);
        verify(query).executeUpdate();
        verify(this.hibernateStore).endTransaction(true);
    }

    @Test
    void saveLock() throws Exception
    {
        Query query = mock(Query.class);
        when(this.session.createQuery(DELETE_LOCK_QUERY)).thenReturn(query);
        when(this.hibernateStore.beginTransaction()).thenReturn(true);

        XWikiLock lock = new XWikiLock(13L, "XWiki.Alice");
        this.store.saveLock(lock, this.xcontext, true);

        // The previous lock, if any, is removed before the new one is saved, because updating it in place would fail if
        // it has been removed in the mean time.
        InOrder inOrder = inOrder(query, this.session);
        inOrder.verify(query).executeUpdate();
        inOrder.verify(this.session).save(lock);

        verify(query).setParameter("docId", 13L);
        verify(this.session, never()).update(any());
        verify(this.hibernateStore).endTransaction(true);
    }

    @Test
    void createHibernateSequenceIfRequiredWhenNotInUpdateCommands()
    {
        Session sessionMock = mock(Session.class);
        Dialect dialect = mock(Dialect.class);
        when(this.hibernateStore.getDialect()).thenReturn(dialect);
        when(dialect.getNativeIdentifierGeneratorStrategy()).thenReturn("sequence");
        NativeQuery sqlQuery = mock(NativeQuery.class);
        when(sessionMock.createSQLQuery("create sequence schema.hibernate_sequence")).thenReturn(sqlQuery);
        when(sqlQuery.executeUpdate()).thenReturn(0);

        this.store.createHibernateSequenceIfRequired(new String[] {}, "schema", sessionMock);

        verify(sessionMock).createSQLQuery("create sequence schema.hibernate_sequence");
        verify(sqlQuery).executeUpdate();
    }

    /**
     * We verify that the sequence is not created if it's already in the update script.
     */
    @Test
    void createHibernateSequenceIfRequiredWhenInUpdateCommands()
    {
        Session sessionMock = mock(Session.class);
        Dialect dialect = mock(Dialect.class);
        when(this.hibernateStore.getDialect()).thenReturn(dialect);
        when(dialect.getNativeIdentifierGeneratorStrategy()).thenReturn("sequence");
        NativeQuery sqlQuery = mock(NativeQuery.class);
        when(sessionMock.createSQLQuery("create sequence schema.hibernate_sequence")).thenReturn(sqlQuery);
        when(sqlQuery.executeUpdate()).thenReturn(0);

        this.store.createHibernateSequenceIfRequired(
            new String[] {"whatever", "create sequence schema.hibernate_sequence"}, "schema", sessionMock);

        verify(sessionMock, never()).createSQLQuery("create sequence schema.hibernate_sequence");
        verify(sqlQuery, never()).executeUpdate();
    }

    /**
     * Save an object that has a property whose type has changed.
     * 
     * @see "XWIKI-9716: Error while migrating SearchSuggestConfig page from 4.1.4 to 5.2.1 with DW"
     */
    @Test
    void saveObjectWithPropertyTypeChange() throws Exception
    {
        // The class must be local.
        DocumentReference classReference = new DocumentReference("myWiki", "mySpace", "myClass");
        when(xcontext.getWikiId()).thenReturn(classReference.getWikiReference().getName());
        BaseObject object = mock(BaseObject.class);
        when(object.getXClassReference()).thenReturn(classReference);

        // Query to check if the object exists already (save versus update).
        when(xcontext.get("hibsession")).thenReturn(session);
        Query queryMock = mock(Query.class);
        when(session.createQuery("select obj.id from BaseObject as obj where obj.id = :id", Long.class))
            .thenReturn(queryMock);

        // Save each object property.
        String propertyName = "query";
        long propertyId = 1234567890L;
        when(object.getPropertyList()).thenReturn(Collections.singleton(propertyName));

        // The property name must match the key in the property list.
        BaseProperty property = mock(BaseProperty.class);
        when(object.getField(propertyName)).thenReturn(property);
        when(property.getId()).thenReturn(propertyId);
        when(property.getName()).thenReturn(propertyName);
        when(property.getClassType()).thenReturn(LargeStringProperty.class.getName());

        Query<String> oldClassTypeQuery = mock(Query.class);
        when(session.createQuery(
            "select prop.classType from BaseProperty as prop " + "where prop.id.id = :id and prop.id.name= :name",
            String.class)).thenReturn(oldClassTypeQuery);
        // The old value has a different type (String -> TextArea).
        when(oldClassTypeQuery.uniqueResult()).thenReturn(StringProperty.class.getName());

        // The old property must be loaded from the corresponding table.
        Query oldPropertyQuery = mock(Query.class);
        when(session.createQuery("select prop from " + StringProperty.class.getName()
            + " as prop where prop.id.id = :id and prop.id.name= :name")).thenReturn(oldPropertyQuery);
        BaseProperty oldProperty = mock(BaseProperty.class);
        when(oldPropertyQuery.uniqueResult()).thenReturn(oldProperty);

        store.saveXWikiCollection(object, xcontext, false);

        verify(oldClassTypeQuery).setParameter("id", propertyId);
        verify(oldClassTypeQuery).setParameter("name", propertyName);

        verify(oldPropertyQuery).setParameter("id", propertyId);
        verify(oldPropertyQuery).setParameter("name", propertyName);

        // Delete the old property value and then save the new one.
        verify(session).delete(oldProperty);
        verify(session).save(property);
    }

    @Test
    void existsWithRootLocale() throws Exception
    {
        String fullName = "space.page";
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getLocale()).thenReturn(Locale.ROOT);
        when(doc.getFullName()).thenReturn(fullName);

        Query query = mock(Query.class);
        when(session.createQuery("select doc.fullName from XWikiDocument as doc where doc.fullName=:fullName"))
            .thenReturn(query);
        when(query.list()).thenReturn(Collections.singletonList(fullName));

        when(this.wikiDescriptorManager.getCurrentWikiId()).thenReturn("wiki");
        when(this.wikiDescriptorManager.exists("wiki")).thenReturn(true);

        assertTrue(store.exists(doc, xcontext));

        verify(query).setParameter("fullName", fullName);
    }

    @Test
    void existsWithNonRootLocale() throws Exception
    {
        String fullName = "space.page";
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getLocale()).thenReturn(Locale.ENGLISH);
        when(doc.getFullName()).thenReturn(fullName);

        Query query = mock(Query.class);
        String statement = "select doc.fullName from XWikiDocument as doc where doc.fullName=:fullName"
            + " and doc.language=:language";
        when(session.createQuery(statement)).thenReturn(query);
        when(query.list()).thenReturn(Collections.singletonList(fullName));

        when(this.wikiDescriptorManager.getCurrentWikiId()).thenReturn("wiki");
        when(this.wikiDescriptorManager.exists("wiki")).thenReturn(true);

        assertTrue(store.exists(doc, xcontext));

        verify(query).setParameter("fullName", fullName);
        verify(query).setParameter("language", Locale.ENGLISH.toString());
    }

    @Test
    void existsWhenDocumentBelongsToNonExistingWiki() throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);

        when(this.wikiDescriptorManager.getCurrentWikiId()).thenReturn("notexisting");
        when(this.wikiDescriptorManager.exists("notexisting")).thenReturn(false);

        assertFalse(store.exists(doc, xcontext));
    }

    @Test
    void existsWhenFailureToGetDescriptors() throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);

        when(this.wikiDescriptorManager.getCurrentWikiId()).thenReturn("wiki");
        when(this.wikiDescriptorManager.exists("wiki")).thenThrow(new WikiManagerException("error"));

        Throwable exception = assertThrows(XWikiException.class, () -> store.exists(doc, xcontext));
        assertEquals("Error number 3236 in 3: Error while checking for existence of the [wiki] wiki",
            exception.getMessage());
        assertEquals("WikiManagerException: error", ExceptionUtils.getRootCauseMessage(exception));
    }

    @Test
    void getTranslationList() throws Exception
    {
        DocumentReference documentReference = new DocumentReference("chess", Arrays.asList("Path", "To"), "Success");
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(documentReference);

        org.xwiki.query.Query query = mock(org.xwiki.query.Query.class);
        List<Object> translationList = Arrays.<Object>asList("fr", "ro");
        when(query.execute()).thenReturn(translationList);

        when(queryManager.createQuery(any(String.class), eq(org.xwiki.query.Query.HQL))).thenReturn(query);

        assertEquals(translationList, store.getTranslationList(doc, xcontext));

        verify(query).setWiki(documentReference.getWikiReference().getName());
        verify(query).bindValue("space", "Path.To");
        verify(query).bindValue("name", documentReference.getName());
    }

    @Test
    void loadBacklinksFromSameWiki() throws Exception
    {
        // in this test we simulate load of backlinks from the same wiki context.
        DocumentReference documentReference = new DocumentReference("xwiki", Arrays.asList("B"), "WebHome");
        // We are currently in the context of "xwiki"
        WikiReference currentWikiReference = new WikiReference("xwiki");
        when(this.xcontext.getWikiReference()).thenReturn(currentWikiReference);
        List<String> resultList = new ArrayList<>();
        resultList.add("A.WebHome");

        when(this.hibernateStore.beginTransaction()).thenReturn(true);
        Query<String> query = mock(Query.class);
        when(this.session.createQuery(anyString(), same(String.class))).thenReturn(query);
        when(query.list()).thenReturn(resultList);
        DocumentReference expectedBacklink = new DocumentReference("xwiki", Arrays.asList("A"), "WebHome");

        List<DocumentReference> obtainedReferences = this.store.loadBacklinks(documentReference, true, this.xcontext);
        assertEquals(1, obtainedReferences.size());
        assertEquals(expectedBacklink, obtainedReferences.get(0));
        verify(query).setParameter("backlink", "B.WebHome");
        verify(this.hibernateStore).beginTransaction();
        verify(this.hibernateStore).endTransaction(false);
    }

    @Test
    void loadBacklinksFromSubwiki() throws Exception
    {
        // in this test we simulate load of backlinks from a different wiki context.
        DocumentReference documentReference = new DocumentReference("subwiki", Arrays.asList("B"), "WebHome");
        // We are currently in the context of "xwiki"
        WikiReference currentWikiReference = new WikiReference("xwiki");
        when(this.xcontext.getWikiReference()).thenReturn(currentWikiReference);
        List<String> resultList = new ArrayList<>();
        resultList.add("Foo.WebHome");

        when(this.hibernateStore.beginTransaction()).thenReturn(true);
        Query<String> query = mock(Query.class);
        when(this.session.createQuery(anyString(), same(String.class))).thenReturn(query);
        when(query.list()).thenReturn(resultList);
        DocumentReference expectedBacklink = new DocumentReference("xwiki", Arrays.asList("Foo"), "WebHome");

        List<DocumentReference> obtainedReferences = this.store.loadBacklinks(documentReference, true, this.xcontext);
        assertEquals(1, obtainedReferences.size());
        assertEquals(expectedBacklink, obtainedReferences.get(0));
        verify(query).setParameter("backlink", "subwiki:B.WebHome");
        verify(this.hibernateStore).beginTransaction();
        verify(this.hibernateStore).endTransaction(false);
    }

    @Test
    void saveModifiedXObjects() throws XWikiException
    {
        XWikiDocument document = new XWikiDocument(new DocumentReference(WIKI_NAME, "space", "document"));
        // The space of the document exists
        when(this.session.get(eq(XWikiSpace.class), anyLong()))
            .thenReturn(new XWikiSpace(document.getDocumentReference().getLastSpaceReference(), false));

        Query queryDocument = mock();
        when(this.session.createQuery("select xwikidoc.id from XWikiDocument as xwikidoc where xwikidoc.id = :id"))
            .thenReturn(queryDocument);
        when(queryDocument.uniqueResult()).thenReturn(null);

        LocalDocumentReference classReference = new LocalDocumentReference("space", "class");

        BaseObject object1 = new BaseObject();
        object1.setXClassReference(classReference);
        document.addXObject(object1);

        Query queryXObject = mock();
        when(this.session.createQuery("select obj.id from BaseObject as obj where obj.id = :id", Long.class))
            .thenReturn(queryXObject);
        when(queryXObject.uniqueResult()).thenReturn(null);

        // First save a new document

        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(this.session).save("com.xpn.xwiki.objects.BaseObject", object1);
        verify(this.session, never()).update("com.xpn.xwiki.objects.BaseObject", object1);

        // Save again of the same document

        when(queryDocument.uniqueResult()).thenReturn(document.getId());
        when(queryXObject.uniqueResult()).thenReturn(object1.getId());

        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(this.session).save("com.xpn.xwiki.objects.BaseObject", object1);
        verify(this.session, never()).update("com.xpn.xwiki.objects.BaseObject", object1);

        // Indicate that the xobject was modified

        object1.setDirty(true);

        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(this.session).save("com.xpn.xwiki.objects.BaseObject", object1);
        verify(this.session).update("com.xpn.xwiki.objects.BaseObject", object1);

        // Save again with no change

        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(this.session).save("com.xpn.xwiki.objects.BaseObject", object1);
        verify(this.session).update("com.xpn.xwiki.objects.BaseObject", object1);

        // Save again but with disabled tracking

        document.setChangeTracked(false);

        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(this.session).save("com.xpn.xwiki.objects.BaseObject", object1);
        verify(this.session, times(2)).update("com.xpn.xwiki.objects.BaseObject", object1);
    }

    @Test
    void saveNewDocumentInMissingSpacesInsertsThemBeforeTheDocumentTransaction() throws Exception
    {
        noTransactionInProgress();
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        when(mockMakeSpaceVisibleQuery().executeUpdate()).thenReturn(1);

        XWikiDocument document = saveNewDocument(CHILD_DOCUMENT);

        // The missing spaces are found in a first short transaction, then inserted, parent first, each in its own short
        // transaction, and all these sessions are closed before the transaction of the document starts
        ArgumentCaptor<Object> spaceCaptor = ArgumentCaptor.forClass(Object.class);
        InOrder inOrder = inOrder(this.hibernateStore, this.spaceSession, this.spaceTransaction, this.session);
        inOrder.verify(this.hibernateStore).beginTransaction();
        inOrder.verify(this.spaceTransaction).rollback();
        inOrder.verify(this.spaceSession).close();
        inOrder.verify(this.hibernateStore).beginTransaction();
        inOrder.verify(this.spaceSession).save(spaceCaptor.capture());
        inOrder.verify(this.spaceTransaction).commit();
        inOrder.verify(this.spaceSession).close();
        inOrder.verify(this.hibernateStore).beginTransaction();
        inOrder.verify(this.spaceSession).save(spaceCaptor.capture());
        inOrder.verify(this.spaceTransaction).commit();
        inOrder.verify(this.spaceSession).close();
        inOrder.verify(this.hibernateStore).beginTransaction(any(SessionFactory.class));
        inOrder.verify(this.session).save(document);
        inOrder.verify(this.hibernateStore).endTransaction(true);
        inOrder.verify(this.session).close();

        assertEquals(0, this.nestedSpaceTransactions);
        verify(this.hibernateStore, times(3)).setWiki(this.spaceSession);

        // The spaces are inserted hidden, the transaction of the document makes them visible
        List<Object> spaces = spaceCaptor.getAllValues();
        assertEquals(PARENT_SPACE, ((XWikiSpace) spaces.get(0)).getSpaceReference());
        assertEquals(CHILD_SPACE, ((XWikiSpace) spaces.get(1)).getSpaceReference());
        assertTrue(((XWikiSpace) spaces.get(0)).isHidden());
        assertTrue(((XWikiSpace) spaces.get(1)).isHidden());

        // The transaction of the document never inserts a space
        verify(this.session, never()).save(any(XWikiSpace.class));
        verify(this.spaceTransaction, times(1)).rollback();
    }

    @Test
    void saveNewVisibleDocumentInMissingSpacesMakesThemVisible() throws Exception
    {
        noTransactionInProgress();
        XWikiSpace space = new XWikiSpace(CHILD_SPACE, true);
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE))).thenReturn(space);
        Query query = mockMakeSpaceVisibleQuery();
        when(query.executeUpdate()).thenReturn(1);

        saveNewDocument(CHILD_DOCUMENT);

        // The transaction of the document makes the spaces it finds hidden visible with a statement, up to the top
        // level space, and does not keep the hidden copy it loaded
        InOrder inOrder = inOrder(this.hibernateStore, this.session, query);
        inOrder.verify(this.hibernateStore).beginTransaction(any(SessionFactory.class));
        inOrder.verify(this.session).detach(space);
        inOrder.verify(query).setParameter("id", XWikiSpace.getId(CHILD_SPACE));
        inOrder.verify(query).executeUpdate();
        inOrder.verify(query).setParameter("id", XWikiSpace.getId(PARENT_SPACE));
        inOrder.verify(query).executeUpdate();
        inOrder.verify(this.hibernateStore).endTransaction(true);
        verify(query, times(2)).executeUpdate();
        verify(this.session, never()).update(any(XWikiSpace.class));
    }

    @Test
    void saveDocumentWhenSpaceIsInsertedByAnotherTransaction() throws Exception
    {
        noTransactionInProgress();
        // Another transaction (another cluster member) inserts both spaces first: the commits fail, and a new
        // transaction finds the spaces
        doThrow(
            new ConstraintViolationException("could not execute statement",
                new SQLException("duplicate key value violates unique constraint", "23505"), "xwikispace_pkey"),
            new HibernateException("Failed to commit",
                new SQLException("Duplicate entry for key 'PRIMARY'", "23000")))
            .when(this.spaceTransaction).commit();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE))).thenReturn(null, null,
            new XWikiSpace(PARENT_SPACE, true));
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE))).thenReturn(null, null,
            new XWikiSpace(CHILD_SPACE, true));
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        Query query = mockMakeSpaceVisibleQuery();
        when(query.executeUpdate()).thenReturn(1);

        XWikiDocument document = saveNewDocument(CHILD_DOCUMENT);

        // The conflicts are not errors: the document is saved
        verify(this.spaceTransaction, times(2)).commit();
        // One session to find the missing spaces, one to insert each of them and one to check each conflict
        verify(this.spaceSession, times(5)).close();
        verify(this.session).save(document);
        verify(this.session, never()).save(any(XWikiSpace.class));
        assertFalse(document.isNew());
        assertEquals(0, this.nestedSpaceTransactions);

        // The other transaction may have inserted the spaces hidden too, so they are made visible
        verify(query).setParameter("id", XWikiSpace.getId(CHILD_SPACE));
        verify(query).setParameter("id", XWikiSpace.getId(PARENT_SPACE));
        verify(query, times(2)).executeUpdate();
    }

    @Test
    void saveDocumentWhenSpaceIsInsertedByAnotherTransactionAndTheCommitFailsWithAPersistenceException()
        throws Exception
    {
        noTransactionInProgress();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE)))
            .thenReturn(new XWikiSpace(PARENT_SPACE, false));
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE))).thenReturn(null, null,
            new XWikiSpace(CHILD_SPACE, true));
        // The exception thrown by Hibernate 5.6 when the flush of a commit violates a constraint
        doThrow(new PersistenceException(new ConstraintViolationException("could not execute statement",
            new SQLException("duplicate key value violates unique constraint", "23505"), "xwikispace_pkey")))
            .when(this.spaceTransaction).commit();
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        Query query = mockMakeSpaceVisibleQuery();
        when(query.executeUpdate()).thenReturn(1, 0);

        XWikiDocument document = saveNewDocument(CHILD_DOCUMENT);

        verify(this.spaceSession).save(any(XWikiSpace.class));
        verify(this.session).save(document);
        verify(this.transaction).commit();
        verify(query, times(2)).executeUpdate();
    }

    @Test
    void saveDocumentWhenSpaceInsertionViolatesAnotherConstraint() throws Exception
    {
        noTransactionInProgress();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE)))
            .thenReturn(new XWikiSpace(PARENT_SPACE, false));
        SQLException error = new SQLException("null value in column violates not-null constraint", "23502");
        doThrow(new ConstraintViolationException("could not execute statement", error, "name"))
            .when(this.spaceTransaction).commit();

        XWikiException exception = assertThrows(XWikiException.class, () -> saveNewDocument(CHILD_DOCUMENT));

        // The space was read again and is not there, so the failure is not caused by another transaction
        assertSame(error, ExceptionUtils.getRootCause(exception));
        verify(this.spaceSession, times(3)).get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE));
        verify(this.hibernateStore, never()).beginTransaction(any(SessionFactory.class));
        verify(this.session, never()).save(any());
    }

    @Test
    void saveDocumentWhenSpaceIsInsertedByAnotherThread() throws Exception
    {
        noTransactionInProgress();
        // Another thread of this instance inserts the space while this one is waiting for the lock
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE)))
            .thenReturn(new XWikiSpace(PARENT_SPACE, false));
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE))).thenReturn(null,
            new XWikiSpace(CHILD_SPACE, true));
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        mockMakeSpaceVisibleQuery();

        XWikiDocument document = saveNewDocument(CHILD_DOCUMENT);

        verify(this.spaceSession, never()).save(any(XWikiSpace.class));
        verify(this.spaceSession, times(2)).close();
        verify(this.session).save(document);
        verify(this.session, never()).save(any(XWikiSpace.class));
    }

    @Test
    void saveDocumentWhenSpaceInsertionFails() throws Exception
    {
        noTransactionInProgress();
        HibernateException error = new HibernateException("Connection lost");
        doThrow(error).when(this.spaceTransaction).commit();

        XWikiException exception = assertThrows(XWikiException.class, () -> saveNewDocument(CHILD_DOCUMENT));

        // Any other failure to insert a space makes the document save fail, before its transaction starts
        assertSame(error, exception.getCause().getCause());
        verify(this.spaceSession, times(2)).close();
        verify(this.hibernateStore, never()).beginTransaction(any(SessionFactory.class));
        assertNull(this.currentSession);
    }

    @Test
    void saveDocumentWhenSpaceInsertionFailsWithoutSQLState() throws Exception
    {
        noTransactionInProgress();
        // The pool reports a closed connection with an SQLException that has no SQL state
        HibernateException error =
            new HibernateException("Unable to commit", new SQLException("Connection is closed."));
        doThrow(error).when(this.spaceTransaction).commit();

        XWikiException exception = assertThrows(XWikiException.class, () -> saveNewDocument(CHILD_DOCUMENT));

        // The original failure is reported, and the space is not read again
        assertSame(error, exception.getCause().getCause());
        verify(this.spaceSession, times(2)).close();
        verify(this.hibernateStore, never()).beginTransaction(any(SessionFactory.class));
    }

    @Test
    void saveDocumentWhenSwitchingTheSpaceSessionToTheWikiFails() throws Exception
    {
        // The document is saved in a transaction in progress and its space is missing there
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE)))
            .thenReturn(new XWikiSpace(PARENT_SPACE, false));
        XWikiException error = new XWikiException();
        doThrow(error).when(this.hibernateStore).setWiki(this.spaceSession);

        XWikiDocument document = new XWikiDocument(CHILD_DOCUMENT);
        Query queryDocument = mock();
        when(this.session.createQuery(DOCUMENT_ID_QUERY)).thenReturn(queryDocument);
        XWikiException exception =
            assertThrows(XWikiException.class, () -> this.store.saveXWikiDoc(document, this.xcontext, false));

        // Only the transaction on the space table is ended, the transaction in progress is left to its owner
        assertSame(error, exception.getCause());
        assertEquals(1, this.nestedSpaceTransactions);
        verify(this.spaceTransaction).rollback();
        verify(this.spaceSession).close();
        verify(this.transaction, never()).rollback();
        verify(this.transaction, never()).commit();
        verify(this.session, never()).close();
        assertSame(this.session, this.currentSession);
        assertSame(this.transaction, this.currentTransaction);

        // The space lock is released
        @SuppressWarnings("unchecked")
        Map<Long, ReentrantLock> locks =
            (Map<Long, ReentrantLock>) FieldUtils.readField(this.store, "spaceSavingLockMap", true);
        ReentrantLock lock = locks.get(XWikiSpace.getId(CHILD_SPACE));
        assertTrue(lock == null || !lock.isLocked());
    }

    @Test
    void saveDocumentWhenBeginningTheSpaceTransactionFails() throws Exception
    {
        noTransactionInProgress();
        HibernateException error = new HibernateException("Unable to acquire JDBC Connection");
        when(this.spaceSession.beginTransaction()).thenThrow(error);

        XWikiException exception = assertThrows(XWikiException.class, () -> saveNewDocument(CHILD_DOCUMENT));

        assertSame(error, exception.getCause());
        verify(this.spaceSession).close();
        verify(this.hibernateStore, never()).beginTransaction(any(SessionFactory.class));
        assertNull(this.currentSession);
    }

    @Test
    void saveExistingDocumentWhoseSpaceIsMissing() throws Exception
    {
        noTransactionInProgress();
        // The document is not new, so its space is only checked in its transaction, where it is missing
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE)))
            .thenReturn(new XWikiSpace(PARENT_SPACE, false));
        Query query = mockMakeSpaceVisibleQuery();
        when(query.executeUpdate()).thenReturn(1, 0);

        XWikiDocument document = new XWikiDocument(CHILD_DOCUMENT);
        document.setNew(false);
        Query queryDocument = mock();
        when(this.session.createQuery(DOCUMENT_ID_QUERY)).thenReturn(queryDocument);
        when(queryDocument.uniqueResult()).thenReturn(document.getId());
        this.store.saveXWikiDoc(document, this.xcontext, true);

        // The missing space is inserted hidden in a short transaction while the transaction of the document is in
        // progress, then made visible in the transaction of the document
        assertEquals(1, this.nestedSpaceTransactions);
        ArgumentCaptor<Object> spaceCaptor = ArgumentCaptor.forClass(Object.class);
        InOrder inOrder = inOrder(this.spaceSession, this.spaceTransaction, query, this.transaction);
        inOrder.verify(this.spaceSession).save(spaceCaptor.capture());
        inOrder.verify(this.spaceTransaction).commit();
        inOrder.verify(this.spaceSession).close();
        inOrder.verify(query).executeUpdate();
        inOrder.verify(this.transaction).commit();
        assertEquals(CHILD_SPACE, ((XWikiSpace) spaceCaptor.getValue()).getSpaceReference());
        assertTrue(((XWikiSpace) spaceCaptor.getValue()).isHidden());
        verify(query).setParameter("id", XWikiSpace.getId(CHILD_SPACE));
        verify(query).setParameter("id", XWikiSpace.getId(PARENT_SPACE));
        verify(this.session).update(document);
    }

    @Test
    void saveVisibleDocumentInExistingHiddenSpace() throws Exception
    {
        noTransactionInProgress();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        XWikiSpace space = new XWikiSpace(CHILD_SPACE, true);
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE))).thenReturn(space);
        Query query = mockMakeSpaceVisibleQuery();
        // The space is hidden, its parent is already visible
        when(query.executeUpdate()).thenReturn(1, 0);

        saveNewDocument(CHILD_DOCUMENT);

        // The hidden flag is changed with a statement and the loaded copy of the space is not kept in the session
        verify(query).setParameter("id", XWikiSpace.getId(CHILD_SPACE));
        verify(query).setParameter("id", XWikiSpace.getId(PARENT_SPACE));
        verify(query, times(2)).executeUpdate();
        verify(this.session).detach(space);
        verify(this.session, never()).update(any(XWikiSpace.class));
        // Only one short transaction, to check the space
        verify(this.sessionFactory).openSession();
        verify(this.spaceSession, never()).save(any());
    }

    @Test
    void saveVisibleDocumentInExistingVisibleSpace() throws Exception
    {
        noTransactionInProgress();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, false));
        XWikiSpace space = new XWikiSpace(CHILD_SPACE, false);
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE))).thenReturn(space);

        saveNewDocument(CHILD_DOCUMENT);

        verify(this.session, never()).createQuery(MAKE_SPACE_VISIBLE_QUERY);
        verify(this.session, never()).detach(any());
        verify(this.session, never()).update(any(XWikiSpace.class));
        verify(this.session, never()).save(any(XWikiSpace.class));
        verify(this.sessionFactory).openSession();
        verify(this.spaceSession, never()).save(any());
    }

    @Test
    void saveHiddenDocumentInExistingHiddenParentSpace() throws Exception
    {
        noTransactionInProgress();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(PARENT_SPACE)))
            .thenReturn(new XWikiSpace(PARENT_SPACE, true));
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));

        XWikiDocument document = new XWikiDocument(CHILD_DOCUMENT);
        document.setHidden(true);
        Query queryDocument = mock();
        when(this.session.createQuery(DOCUMENT_ID_QUERY)).thenReturn(queryDocument);

        this.store.saveXWikiDoc(document, this.xcontext, true);

        // Only the missing child space is inserted, hidden, and nothing is made visible
        ArgumentCaptor<Object> spaceCaptor = ArgumentCaptor.forClass(Object.class);
        verify(this.spaceSession).save(spaceCaptor.capture());
        assertEquals(CHILD_SPACE, ((XWikiSpace) spaceCaptor.getValue()).getSpaceReference());
        assertTrue(((XWikiSpace) spaceCaptor.getValue()).isHidden());
        verify(this.session, never()).createQuery(MAKE_SPACE_VISIBLE_QUERY);
        verify(this.session, never()).detach(any());
    }

    @Test
    void saveDocumentThatBecomesVisible() throws Exception
    {
        noTransactionInProgress();
        when(this.spaceSession.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        when(this.session.get(XWikiSpace.class, XWikiSpace.getId(CHILD_SPACE)))
            .thenReturn(new XWikiSpace(CHILD_SPACE, true));
        Query query = mockMakeSpaceVisibleQuery();
        when(query.executeUpdate()).thenReturn(1, 0);

        XWikiDocument document = new XWikiDocument(CHILD_DOCUMENT);
        document.setHidden(true);
        Query queryDocument = mock();
        when(this.session.createQuery(DOCUMENT_ID_QUERY)).thenReturn(queryDocument);
        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(this.session, never()).createQuery(MAKE_SPACE_VISIBLE_QUERY);

        // Save the existing document as visible: its hidden space is made visible, up to its visible parent, with one
        // statement for each space
        when(queryDocument.uniqueResult()).thenReturn(document.getId());
        document.setHidden(false);
        this.store.saveXWikiDoc(document, this.xcontext, true);

        verify(query).setParameter("id", XWikiSpace.getId(CHILD_SPACE));
        verify(query).setParameter("id", XWikiSpace.getId(PARENT_SPACE));
        verify(query, times(2)).executeUpdate();
        verify(this.session, never()).update(any(XWikiSpace.class));
        // The existing document does not need a short transaction to check its space
        verify(this.sessionFactory).openSession();
    }

    /**
     * Simulate {@link HibernateStore#beginTransaction(SessionFactory)}: open a session and a transaction, and switch
     * the session to the current wiki, unless a session is already in progress. This stand-in mirrors the contract of
     * {@link HibernateStore#beginTransaction(SessionFactory)}, {@link HibernateStore#setWiki(Session)} (which ends the
     * current transaction when the switch fails) and {@link HibernateStore#endTransaction(boolean)} (which clears the
     * context and closes the session); it has to be revisited if those methods change.
     */
    private boolean beginTransaction(Session newSession) throws XWikiException
    {
        if (this.currentSession != null) {
            return false;
        }

        if (newSession == this.session) {
            this.documentSessionOpen = true;
        } else if (this.documentSessionOpen) {
            this.nestedSpaceTransactions++;
        }

        this.currentSession = newSession;
        this.currentTransaction = newSession.beginTransaction();

        try {
            this.hibernateStore.setWiki(newSession);
        } catch (XWikiException e) {
            // HibernateStore#setWiki ends the current transaction when it fails to switch to the wiki
            endTransaction(false);

            throw e;
        }

        return true;
    }

    /**
     * Simulate {@link HibernateStore#endTransaction(boolean)}.
     */
    private void endTransaction(boolean commit)
    {
        Session endedSession = this.currentSession;
        Transaction endedTransaction = this.currentTransaction;
        this.currentSession = null;
        this.currentTransaction = null;

        try {
            if (endedTransaction != null) {
                if (commit) {
                    endedTransaction.commit();
                } else {
                    endedTransaction.rollback();
                }
            }
        } catch (HibernateException e) {
            throw new HibernateException("Failed to commit or rollback transaction", e);
        } finally {
            if (endedSession != null) {
                endedSession.close();

                if (endedSession == this.session) {
                    this.documentSessionOpen = false;
                }
            }
        }
    }
}
