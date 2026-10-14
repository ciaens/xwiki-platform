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
package com.xpn.xwiki.internal.observation.remote.converter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.event.Event;
import org.xwiki.observation.remote.LocalEventData;
import org.xwiki.observation.remote.RemoteEventData;
import org.xwiki.observation.remote.internal.converter.DefaultEventConverterManager;
import org.xwiki.observation.remote.internal.converter.EventConverters;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDeletedDocument;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.file.TemporaryDeferredFileRepository;
import com.xpn.xwiki.internal.store.hibernate.XWikiHibernateDeletedDocumentContent;
import com.xpn.xwiki.store.XWikiRecycleBinStoreInterface;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.component.XWikiDocumentFilterUtilsComponentList;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link DocumentEventConverter}.
 * 
 * @version $Id$
 */
@OldcoreTest
@ComponentList({DocumentEventConverter.class, EventConverters.class, TemporaryDeferredFileRepository.class})
@XWikiDocumentFilterUtilsComponentList
class DocumentEventConverterTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultEventConverterManager converterManager;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    private XWikiRecycleBinStoreInterface recycleBinStore;

    @BeforeEach
    void beforeEach()
    {
        this.recycleBinStore = mock(XWikiRecycleBinStoreInterface.class);
        doReturn(this.recycleBinStore).when(this.oldcore.getSpyXWiki()).getRecycleBinStore();
        doReturn(true).when(this.oldcore.getSpyXWiki()).hasRecycleBin(any());
        doReturn(true).when(this.oldcore.getSpyXWiki()).hasVersioning(any());
    }

    @Test
    void testConvertCreatedDocument() throws Exception
    {
        DocumentReference documentReference = new DocumentReference("wiki", "space", "page");

        // Setup of document which just been created and received by a document event listener
        XWikiDocument document =
            this.oldcore.getSpyXWiki().getDocument(documentReference, this.oldcore.getXWikiContext());
        this.oldcore.getSpyXWiki().saveDocument(document, this.oldcore.getXWikiContext());
        document = document.clone();
        document.setOriginalDocument(new XWikiDocument(documentReference));

        // local -> remote

        LocalEventData localEvent = new LocalEventData();
        localEvent.setEvent(new DocumentUpdatedEvent());
        localEvent.setSource(document);
        localEvent.setData(this.oldcore.getXWikiContext());

        RemoteEventData remoteEvent = this.converterManager.createRemoteEventData(localEvent);

        assertFalse(remoteEvent.getSource() instanceof XWikiDocument);
        assertFalse(remoteEvent.getData() instanceof XWikiContext);

        // serialize/unserialize
        ByteArrayOutputStream sos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(sos);
        oos.writeObject(remoteEvent);
        ByteArrayInputStream sis = new ByteArrayInputStream(sos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(sis);
        remoteEvent = (RemoteEventData) ois.readObject();

        // remote -> local

        LocalEventData localEvent2 = this.converterManager.createLocalEventData(remoteEvent);

        assertTrue(localEvent2.getSource() instanceof XWikiDocument);
        assertTrue(localEvent2.getData() instanceof XWikiContext);
        assertEquals(documentReference, ((XWikiDocument) localEvent2.getSource()).getDocumentReference());
        assertEquals("space", ((XWikiDocument) localEvent2.getSource()).getSpaceName());
        assertEquals("page", ((XWikiDocument) localEvent2.getSource()).getPageName());
        assertTrue(((XWikiDocument) localEvent2.getSource()).getOriginalDocument().isNew());
        assertNotSame(this.oldcore.getSpyXWiki().getDocument(documentReference, this.oldcore.getXWikiContext()),
            localEvent2.getSource());
    }

    @Test
    void testConvertWithOriginalDocNull() throws Exception
    {
        DocumentReference documentReference = new DocumentReference("wiki", "space", "page");

        // local -> remote

        LocalEventData localEvent = new LocalEventData();
        localEvent.setEvent(new DocumentUpdatedEvent());
        localEvent.setSource(new XWikiDocument(documentReference));
        localEvent.setData(this.oldcore.getXWikiContext());

        RemoteEventData remoteEvent = this.converterManager.createRemoteEventData(localEvent);

        assertFalse(remoteEvent.getSource() instanceof XWikiDocument);
        assertFalse(remoteEvent.getData() instanceof XWikiContext);

        // serialize/unserialize
        ByteArrayOutputStream sos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(sos);
        oos.writeObject(remoteEvent);
        ByteArrayInputStream sis = new ByteArrayInputStream(sos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(sis);
        remoteEvent = (RemoteEventData) ois.readObject();

        // remote -> local

        LocalEventData localEvent2 = this.converterManager.createLocalEventData(remoteEvent);

        assertTrue(localEvent2.getSource() instanceof XWikiDocument);
        assertTrue(localEvent2.getData() instanceof XWikiContext);
        assertEquals(documentReference, ((XWikiDocument) localEvent2.getSource()).getDocumentReference());
        assertEquals("space", ((XWikiDocument) localEvent2.getSource()).getSpaceName());
        assertEquals("page", ((XWikiDocument) localEvent2.getSource()).getPageName());
        assertTrue(((XWikiDocument) localEvent2.getSource()).getOriginalDocument().isNew());
        assertNotSame(this.oldcore.getSpyXWiki().getDocument(documentReference, this.oldcore.getXWikiContext()),
            localEvent2.getSource());
    }

    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference("xwiki", "space", "page");

    private static final Date REPLACED_DATE = new Date(1000000000000L);

    private static final Date OTHER_DATE = new Date(1100000000000L);

    private static final String REPLACED_VERSION = "2.2";

    private static final String NOT_FOUND_MESSAGE = "Failed to load version [2.2] of document";

    private XWikiDocument createDocument(DocumentReference reference, String version, Date date, String content)
    {
        XWikiDocument document = new XWikiDocument(reference);
        document.setVersion(version);
        document.setDate(date);
        document.setContent(content);
        document.setNew(false);

        return document;
    }

    private XWikiDeletedDocument createDeletedDocument(XWikiDocument document, Date deleteDate) throws Exception
    {
        return new XWikiDeletedDocument(document.getFullName(), document.getLocale(), null, "XWiki.Deleter",
            deleteDate, new XWikiHibernateDeletedDocumentContent(document));
    }

    private XWikiDeletedDocument createDeletedDocument(DocumentReference reference, String version, Date date,
        String content, Date deleteDate) throws Exception
    {
        return createDeletedDocument(createDocument(reference, version, date, content), deleteDate);
    }

    /**
     * Compute a date relative to now.
     *
     * @param minutes a number of minutes
     * @return the date at the passed number of minutes from now
     */
    private Date inMinutes(int minutes)
    {
        return new Date(System.currentTimeMillis() + minutes * 60000L);
    }

    private void mockRecycleBin(XWikiDeletedDocument... deletedDocuments) throws Exception
    {
        when(this.recycleBinStore.getAllDeletedDocuments(any(XWikiDocument.class), any(XWikiContext.class),
            anyBoolean())).thenReturn(deletedDocuments);
    }

    private void verifyRecycleBinNotRead() throws Exception
    {
        verify(this.recycleBinStore, never()).getAllDeletedDocuments(any(XWikiDocument.class),
            any(XWikiContext.class), anyBoolean());
    }

    /**
     * Convert a local event to a remote event, serialize and unserialize it, and convert it back to a local event.
     *
     * @param event the event
     * @param document the source of the event
     * @param removedKey a key to remove from the serialized source (to simulate an older sender), or {@code null}
     * @return the local event built from the remote event
     */
    private LocalEventData convert(Event event, XWikiDocument document, String removedKey) throws Exception
    {
        LocalEventData localEvent = new LocalEventData();
        localEvent.setEvent(event);
        localEvent.setSource(document);
        localEvent.setData(this.oldcore.getXWikiContext());

        RemoteEventData remoteEvent = this.converterManager.createRemoteEventData(localEvent);
        if (removedKey != null) {
            ((Map<?, ?>) remoteEvent.getSource()).remove(removedKey);
        }

        // serialize/unserialize
        ByteArrayOutputStream sos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(sos);
        oos.writeObject(remoteEvent);
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(sos.toByteArray()));

        return this.converterManager.createLocalEventData((RemoteEventData) ois.readObject());
    }

    /**
     * Send an update event of a document whose original document has a version that is not in the history of the
     * document, as when a document is renamed onto an existing document, and convert it back to a local event.
     *
     * @param reference the reference of the updated document
     * @param version the version of the updated document as sent in the event
     * @param removedKey a key to remove from the serialized source (to simulate an older sender), or {@code null}
     * @return the local event built from the remote event
     */
    private LocalEventData convertUpdateOfReplacedDocument(DocumentReference reference, String version,
        String removedKey) throws Exception
    {
        XWikiContext xcontext = this.oldcore.getXWikiContext();

        // The document moved to the reference of the replaced document, with the history of the moved document
        XWikiDocument document = this.oldcore.getSpyXWiki().getDocument(reference, xcontext);
        document.setContent("moved");
        this.oldcore.getSpyXWiki().saveDocument(document, xcontext);
        document = this.oldcore.getSpyXWiki().getDocument(reference, xcontext).clone();
        document.setVersion(version);

        // The replaced document, deleted with its history before the event is sent
        document.setOriginalDocument(createDocument(reference, REPLACED_VERSION, REPLACED_DATE, "replaced"));

        return convert(new DocumentUpdatedEvent(), document, removedKey);
    }

    private XWikiDocument convertUpdateOfReplacedDocument() throws Exception
    {
        return (XWikiDocument) convertUpdateOfReplacedDocument(DOCUMENT_REFERENCE, "1.1", null).getSource();
    }

    private void assertEmptyOriginalDocument(XWikiDocument source)
    {
        assertEquals("moved", source.getContent());
        XWikiDocument originalDocument = source.getOriginalDocument();
        assertEquals(source.getDocumentReference(), originalDocument.getDocumentReference());
        assertTrue(originalDocument.isNew());
        assertEquals("", originalDocument.getContent());
    }

    private void assertReplacedDocument(XWikiDocument source, String content)
    {
        assertEquals("moved", source.getContent());
        assertEquals("1.1", source.getVersion());
        XWikiDocument originalDocument = source.getOriginalDocument();
        assertEquals(source.getDocumentReference(), originalDocument.getDocumentReference());
        assertEquals(REPLACED_VERSION, originalDocument.getVersion());
        assertEquals(content, originalDocument.getContent());
        assertFalse(originalDocument.isNew());
    }

    private void assertNotFoundWarning()
    {
        assertEquals(1, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0).startsWith(NOT_FOUND_MESSAGE));
        assertNull(this.logCapture.getLogEvent(0).getThrowableProxy());
    }

    @Test
    void convertUpdatedDocumentWithOriginalDocumentInRecycleBin() throws Exception
    {
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "replaced",
            inMinutes(1)));

        assertReplacedDocument(convertUpdateOfReplacedDocument(), "replaced");
    }

    @Test
    void convertUpdatedDocumentWithOriginalDocumentNotFound() throws Exception
    {
        mockRecycleBin();

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());

        assertEquals(1, this.logCapture.size());
        assertEquals("Failed to load version [2.2] of document [xwiki:space.page()], used as the original document of"
            + " a remote event, an empty original document is used instead. Root cause: [XWikiException: Error number"
            + " 3205 in 3: Version 2.2 does not exist while reading document xwiki:space.page()]",
            this.logCapture.getMessage(0));
        assertNull(this.logCapture.getLogEvent(0).getThrowableProxy());
    }

    @Test
    void convertUpdatedDocumentWithOriginalDocumentAfterOtherVersionInRecycleBin() throws Exception
    {
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, "2.1", REPLACED_DATE, "other", inMinutes(2)),
            createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "replaced", inMinutes(1)));

        assertReplacedDocument(convertUpdateOfReplacedDocument(), "replaced");
    }

    @Test
    void convertUpdatedDocumentWithOriginalDocumentDeletedBeforeDocument() throws Exception
    {
        // Deleted before the document of the event was saved: another incarnation of the reference
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "replaced",
            new Date(REPLACED_DATE.getTime() + 3600000L)));

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());
        assertNotFoundWarning();
    }

    @Test
    void convertUpdatedDocumentWhenRestoringDeletedDocumentFails() throws Exception
    {
        XWikiDeletedDocument brokenDeletedDocument = mock(XWikiDeletedDocument.class);
        when(brokenDeletedDocument.getDate()).thenReturn(inMinutes(2));
        when(brokenDeletedDocument.restoreDocument(any())).thenThrow(new XWikiException());
        mockRecycleBin(brokenDeletedDocument,
            createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "replaced", inMinutes(1)));

        assertReplacedDocument(convertUpdateOfReplacedDocument(), "replaced");
    }

    @Test
    void convertUpdatedDocumentWithOriginalDateMatch() throws Exception
    {
        // Same version, but not the same date as the original document sent by the other member
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, OTHER_DATE, "other", inMinutes(2)),
            createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "replaced", inMinutes(1)));

        assertReplacedDocument(convertUpdateOfReplacedDocument(), "replaced");
    }

    @Test
    void convertUpdatedDocumentWithoutOriginalDate() throws Exception
    {
        // An older sender does not send the date of the original document: the version decides
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, OTHER_DATE, "other", inMinutes(1)));

        LocalEventData localEvent =
            convertUpdateOfReplacedDocument(DOCUMENT_REFERENCE, "1.1", AbstractXWikiEventConverter.ORIGDOC_DATE);

        assertReplacedDocument((XWikiDocument) localEvent.getSource(), "other");
    }

    @Test
    void convertUpdatedDocumentWithOriginalDateMismatch() throws Exception
    {
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, OTHER_DATE, "other", inMinutes(1)));

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());
        assertNotFoundWarning();
    }

    @Test
    void convertUpdatedDocumentWhenRecycleBinFails() throws Exception
    {
        when(this.recycleBinStore.getAllDeletedDocuments(any(XWikiDocument.class), any(XWikiContext.class),
            anyBoolean())).thenThrow(new XWikiException());

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());
        assertEquals("xwiki", this.oldcore.getXWikiContext().getWikiId());
        assertNotFoundWarning();
    }

    @Test
    void convertUpdatedDocumentWithoutRecycleBin() throws Exception
    {
        doReturn(false).when(this.oldcore.getSpyXWiki()).hasRecycleBin(any());

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());
        verifyRecycleBinNotRead();
        assertNotFoundWarning();
    }

    @Test
    void convertUpdatedDocumentWithoutVersioning() throws Exception
    {
        doReturn(false).when(this.oldcore.getSpyXWiki()).hasVersioning(any());

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());
        verifyRecycleBinNotRead();
        // Expected without versioning: nothing above debug level
        assertEquals(0, this.logCapture.size());
    }

    @Test
    void convertUpdatedDocumentWhenOriginalVersionFailsToLoad() throws Exception
    {
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN, "broken"))
            .when(this.oldcore.getMockVersioningStore()).loadXWikiDoc(any(), eq(REPLACED_VERSION), any());

        assertEmptyOriginalDocument(convertUpdateOfReplacedDocument());
        // Only a missing version is looked for in the recycle bin
        verifyRecycleBinNotRead();
        assertNotFoundWarning();
    }

    @Test
    void convertUpdatedDocumentFromOtherWiki() throws Exception
    {
        DocumentReference documentReference = new DocumentReference("otherwiki", "space", "page");

        List<String> wikis = new ArrayList<>();
        XWikiDeletedDocument deletedDocument =
            createDeletedDocument(documentReference, REPLACED_VERSION, REPLACED_DATE, "replaced", inMinutes(1));
        when(this.recycleBinStore.getAllDeletedDocuments(any(XWikiDocument.class), any(XWikiContext.class),
            anyBoolean())).then(invocation -> {
                wikis.add(invocation.<XWikiContext>getArgument(1).getWikiId());
                return new XWikiDeletedDocument[] {deletedDocument};
            });

        LocalEventData localEvent = convertUpdateOfReplacedDocument(documentReference, "1.1", null);

        // The recycle bin of the wiki of the document is read, the stored reference is resolved in that wiki, then the
        // context wiki is restored
        assertEquals(List.of("otherwiki"), wikis);
        XWikiDocument originalDocument = ((XWikiDocument) localEvent.getSource()).getOriginalDocument();
        assertEquals(documentReference, originalDocument.getDocumentReference());
        assertEquals("replaced", originalDocument.getContent());
        assertEquals("xwiki", this.oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void convertUpdatedDocumentWhenDocumentVersionCannotBeLoaded() throws Exception
    {
        mockRecycleBin();

        // The version of the document sent in the event is not in its history
        LocalEventData localEvent = convertUpdateOfReplacedDocument(DOCUMENT_REFERENCE, "5.1", null);

        // The event is still delivered, with the current version of the document
        assertTrue(localEvent.getEvent() instanceof DocumentUpdatedEvent);
        assertTrue(localEvent.getData() instanceof XWikiContext);
        XWikiDocument source = (XWikiDocument) localEvent.getSource();
        assertEquals("1.1", source.getVersion());
        assertEmptyOriginalDocument(source);
        assertNotSame(this.oldcore.getSpyXWiki().getDocument(DOCUMENT_REFERENCE, this.oldcore.getXWikiContext()),
            source);

        assertEquals(1, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0).startsWith("Failed to convert remote event ["));
        assertNotNull(this.logCapture.getLogEvent(0).getThrowableProxy());
    }

    @Test
    void convertUpdatedDocumentWhenDocumentCannotBeLoaded() throws Exception
    {
        XWikiContext xcontext = this.oldcore.getXWikiContext();
        XWikiDocument document = createDocument(DOCUMENT_REFERENCE, "1.1", OTHER_DATE, "moved");
        document.setOriginalDocument(createDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "replaced"));
        doThrow(new XWikiException()).when(this.oldcore.getSpyXWiki()).getDocument(any(XWikiDocument.class),
            any(XWikiContext.class));

        LocalEventData localEvent = convert(new DocumentUpdatedEvent(), document, null);

        // The event is still delivered, with a document carrying only the reference
        assertTrue(localEvent.getData() instanceof XWikiContext);
        XWikiDocument source = (XWikiDocument) localEvent.getSource();
        assertEquals(DOCUMENT_REFERENCE, source.getDocumentReference());
        assertEquals(Locale.ROOT, source.getLocale());
        assertTrue(source.isNew());
        assertEquals("", source.getContent());
        assertTrue(source.getOriginalDocument().isNew());
        assertEquals(DOCUMENT_REFERENCE, source.getOriginalDocument().getDocumentReference());
        assertEquals("xwiki", xcontext.getWikiId());

        assertEquals(2, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0).startsWith("Failed to convert remote event ["));
        assertEquals("Failed to load the current version of document [xwiki:space.page()], the remote event is"
            + " delivered with a document carrying only its reference. Root cause: [XWikiException: Error number 0 in"
            + " 0]", this.logCapture.getMessage(1));
    }

    @Test
    void convertDeletedDocument() throws Exception
    {
        mockRecycleBin(createDeletedDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "deleted",
            inMinutes(1)));
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.setOriginalDocument(createDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "deleted"));

        LocalEventData localEvent = convert(new DocumentDeletedEvent(), document, null);

        XWikiDocument source = (XWikiDocument) localEvent.getSource();
        assertEquals(DOCUMENT_REFERENCE, source.getDocumentReference());
        XWikiDocument originalDocument = source.getOriginalDocument();
        assertEquals(DOCUMENT_REFERENCE, originalDocument.getDocumentReference());
        assertEquals("deleted", originalDocument.getContent());
        assertFalse(originalDocument.isNew());
    }

    @Test
    void convertDeletedDocumentWhenRecycleBinFails() throws Exception
    {
        when(this.recycleBinStore.getAllDeletedDocuments(any(XWikiDocument.class), any(XWikiContext.class),
            anyBoolean())).thenThrow(new XWikiException());
        XWikiDocument document = new XWikiDocument(DOCUMENT_REFERENCE);
        document.setOriginalDocument(createDocument(DOCUMENT_REFERENCE, REPLACED_VERSION, REPLACED_DATE, "deleted"));

        LocalEventData localEvent = convert(new DocumentDeletedEvent(), document, null);

        XWikiDocument source = (XWikiDocument) localEvent.getSource();
        assertEquals(DOCUMENT_REFERENCE, source.getDocumentReference());
        assertTrue(source.isNew());
        assertTrue(source.getOriginalDocument().isNew());
        assertEquals(1, this.logCapture.size());
        assertTrue(this.logCapture.getMessage(0).startsWith("Failed to convert remote event ["));
    }
}
