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

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.localization.LocaleUtils;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.remote.converter.AbstractEventConverter;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDeletedDocument;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.store.XWikiCacheStore;
import com.xpn.xwiki.util.XWikiStubContextProvider;

/**
 * Provide some serialization tools for old apis like {@link XWikiDocument} and {@link XWikiContext}.
 *
 * @version $Id$
 * @since 2.0M4
 */
public abstract class AbstractXWikiEventConverter extends AbstractEventConverter
{
    protected static final String CONTEXT_WIKI = "contextwiki";

    protected static final String CONTEXT_USER = "contextuser";

    protected static final String DOC_NAME = "docname";

    protected static final String DOC_VERSION = "docversion";

    protected static final String DOC_LANGUAGE = "doclanguage";

    protected static final String ORIGDOC_VERSION = "origdocversion";

    protected static final String ORIGDOC_LANGUAGE = "origdoclanguage";

    /**
     * The date of the original document, in milliseconds. It identifies the original document in the recycle bin when
     * its version cannot be loaded from the history of the document.
     *
     * @since 18.10.0RC1
     */
    protected static final String ORIGDOC_DATE = "origdocdate";

    /**
     * Some databases store dates with a precision of one second.
     */
    private static final long DATE_PRECISION = 1000L;

    /**
     * The logger to log.
     */
    @Inject
    protected Logger logger;

    /**
     * Used to set some proper context information.
     */
    @Inject
    private Execution execution;

    /**
     * Generate stub XWikiContext.
     */
    @Inject
    private XWikiStubContextProvider stubContextProvider;

    /**
     * @param context the XWiki context to serialize
     * @return the serialized version of the context
     */
    protected Serializable serializeXWikiContext(XWikiContext context)
    {
        HashMap<String, Serializable> remoteDataMap = new HashMap<>();

        remoteDataMap.put(CONTEXT_WIKI, context.getWikiId());
        remoteDataMap.put(CONTEXT_USER, context.getUser());

        return remoteDataMap;
    }

    /**
     * @return a stub XWikiContext, null if none can be generated (XWiki has never been accessed yet)
     */
    private XWikiContext getXWikiStubContext()
    {
        ExecutionContext context = this.execution.getContext();
        XWikiContext xcontext = (XWikiContext) context.getProperty(XWikiContext.EXECUTIONCONTEXT_KEY);

        if (xcontext == null) {
            xcontext = this.stubContextProvider.createStubContext();

            if (xcontext != null) {
                xcontext.declareInExecutionContext(context);
            }
        }

        return xcontext;
    }

    /**
     * @param remoteData the serialized version of the context
     * @return the XWiki context
     */
    protected XWikiContext unserializeXWikiContext(Serializable remoteData)
    {
        XWikiContext xcontext = getXWikiStubContext();

        Map<String, Serializable> remoteDataMap = (Map<String, Serializable>) remoteData;

        if (xcontext != null) {
            xcontext.setWikiId((String) remoteDataMap.get(CONTEXT_WIKI));
            xcontext.setUser((String) remoteDataMap.get(CONTEXT_USER));
        } else {
            this.logger.warn("Can't get a proper XWikiContext."
                + " It generally mean that the wiki has never been fully initialized,"
                + " i.e. has never been accesses at least once");
        }

        return xcontext;
    }

    /**
     * @param document the document to serialize
     * @return the serialized version of the document
     */
    protected Serializable serializeXWikiDocument(XWikiDocument document)
    {
        HashMap<String, Serializable> remoteDataMap = new HashMap<>();

        remoteDataMap.put(DOC_NAME, document.getDocumentReference());

        if (!document.isNew()) {
            remoteDataMap.put(DOC_VERSION, document.getVersion());
            remoteDataMap.put(DOC_LANGUAGE, document.getLanguage());
        }

        XWikiDocument originalDocument = document.getOriginalDocument();

        if (originalDocument != null && !originalDocument.isNew()) {
            remoteDataMap.put(ORIGDOC_VERSION, originalDocument.getVersion());
            remoteDataMap.put(ORIGDOC_LANGUAGE, originalDocument.getLanguage());
            if (originalDocument.getDate() != null) {
                remoteDataMap.put(ORIGDOC_DATE, originalDocument.getDate().getTime());
            }
        }

        return remoteDataMap;
    }

    protected XWikiDocument getDocument(XWikiDocument document, String version, XWikiContext xcontext)
        throws XWikiException
    {
        // Force bypassing the cache to make extra sure we get the last version of the document.
        XWikiDocument targetDocument = xcontext.getWiki().getDocument(document, xcontext);

        if (!targetDocument.getVersion().equals(version)) {
            // It's not the last version of the document, ask versioning store.
            targetDocument = xcontext.getWiki().getVersioningStore().loadXWikiDoc(document, version, xcontext);
        }

        return targetDocument;
    }

    /**
     * @param remoteData the serialized version of the document
     * @return the document
     * @throws XWikiException when failing to unserialize document
     */
    protected XWikiDocument unserializeDocument(Serializable remoteData) throws XWikiException
    {
        Map<String, Serializable> remoteDataMap = (Map<String, Serializable>) remoteData;

        DocumentReference docReference = (DocumentReference) remoteDataMap.get(DOC_NAME);
        Locale locale = LocaleUtils.toLocale((String) remoteDataMap.get(DOC_LANGUAGE));
        Locale origLocale = LocaleUtils.toLocale((String) remoteDataMap.get(ORIGDOC_LANGUAGE));

        XWikiContext xcontext = getXWikiStubContext();

        XWikiDocument document = new XWikiDocument(docReference, locale);
        XWikiDocument origDoc = new XWikiDocument(docReference, origLocale);

        // Force invalidating the cache to be sure it returns (and keep) the right document
        if (xcontext.getWiki().getStore() instanceof XWikiCacheStore cacheStore) {
            cacheStore.invalidate(document);
            cacheStore.invalidate(origDoc);
        }

        String version = (String) remoteDataMap.get(DOC_VERSION);
        if (version != null) {
            document = getDocument(document, version, xcontext);
        }

        String origVersion = (String) remoteDataMap.get(ORIGDOC_VERSION);
        if (origVersion != null) {
            origDoc = getOriginalDocument(docReference, origLocale, origVersion, (Long) remoteDataMap.get(ORIGDOC_DATE),
                document.getDate(), xcontext);
        }

        // Clone the document to be sure we don't leave an inconsistent document state in the cache
        // TODO: optimize when https://jira.xwiki.org/browse/XWIKI-22510 is fixed
        document = document.clone();

        // Set the original document expected by listeners (so that they can know what changed)
        document.setOriginalDocument(origDoc);

        return document;
    }

    /**
     * Load the original document of a remote document event.
     *
     * @param reference the reference of the original document
     * @param locale the locale of the original document
     * @param version the version of the original document
     * @param date the date of the original document in milliseconds, {@code null} when the sender did not provide it
     * @param documentDate the date of the document of the event
     * @param xcontext the XWiki context
     * @return the original document, an empty document carrying only the reference and locale when it cannot be found
     */
    private XWikiDocument getOriginalDocument(DocumentReference reference, Locale locale, String version, Long date,
        Date documentDate, XWikiContext xcontext)
    {
        try {
            return getDocument(new XWikiDocument(reference, locale), version, xcontext);
        } catch (XWikiException e) {
            DocumentReference referenceWithLocale = new DocumentReference(reference, locale);

            if (!xcontext.getWiki().hasVersioning(xcontext)) {
                // Without versioning, only the current version of a document is stored
                this.logger.debug("Failed to load version [{}] of document [{}] (versioning is disabled), an empty"
                    + " original document is used instead", version, referenceWithLocale, e);

                return new XWikiDocument(reference, locale);
            }

            if (e.getCode() == XWikiException.ERROR_XWIKI_STORE_HIBERNATE_UNEXISTANT_VERSION) {
                // When a document is renamed or moved onto an existing document, the replaced document is the original
                // document of the event, but it was deleted along with its history and the history found at this
                // reference is now the one of the moved document. The replaced document can still be in the recycle
                // bin.
                XWikiDocument deletedDocument =
                    getDeletedOriginalDocument(reference, locale, version, date, documentDate, xcontext);
                if (deletedDocument != null) {
                    return deletedDocument;
                }
            } else {
                this.logger.debug("Failed to load version [{}] of document [{}]", version, referenceWithLocale, e);
            }

            // Listeners only need the reference of the original document to invalidate what they know about it
            this.logger.warn("Failed to load version [{}] of document [{}], used as the original document of a remote"
                + " event, an empty original document is used instead. Root cause: [{}]", version, referenceWithLocale,
                ExceptionUtils.getRootCauseMessage(e));

            return new XWikiDocument(reference, locale);
        }
    }

    /**
     * Find the original document of a remote document event in the recycle bin.
     *
     * @param reference the reference of the original document
     * @param locale the locale of the original document
     * @param version the version of the original document
     * @param date the date of the original document in milliseconds, {@code null} when the sender did not provide it
     * @param documentDate the date of the document of the event
     * @param xcontext the XWiki context
     * @return the most recently deleted document with the passed reference, locale and version (and date when it is
     *         known), deleted after the document of the event was saved, {@code null} if there is none
     */
    private XWikiDocument getDeletedOriginalDocument(DocumentReference reference, Locale locale, String version,
        Long date, Date documentDate, XWikiContext xcontext)
    {
        if (!xcontext.getWiki().hasRecycleBin(xcontext)) {
            return null;
        }

        XWikiDeletedDocument[] deletedDocuments;
        try {
            deletedDocuments = getDeletedDocuments(reference, locale, xcontext);
        } catch (XWikiException e) {
            this.logger.debug("Failed to get the deleted documents [{}] from the recycle bin",
                new DocumentReference(reference, locale), e);

            return null;
        }

        // The original document was deleted when the document of the event replaced it, so after the document of the
        // event was saved: older entries belong to other incarnations of the reference (the most recent come first)
        long minimumDeletionDate = documentDate != null ? documentDate.getTime() - DATE_PRECISION : Long.MIN_VALUE;
        for (XWikiDeletedDocument deletedDocument : deletedDocuments) {
            Date deletionDate = deletedDocument.getDate();
            if (deletionDate != null && deletionDate.getTime() < minimumDeletionDate) {
                break;
            }

            XWikiDocument document;
            try {
                document = restoreDeletedDocument(deletedDocument, reference, xcontext);
            } catch (Exception e) {
                this.logger.debug("Failed to restore the deleted document with id [{}]", deletedDocument.getId(), e);

                continue;
            }

            // The date of the original document sent by the other member is the one stored in the recycle bin
            if (version.equals(document.getVersion())
                && (date == null || (document.getDate() != null && date == document.getDate().getTime()))) {
                return document;
            }
        }

        return null;
    }

    /**
     * Get the deleted documents with the passed reference and locale from the recycle bin of the wiki of the document.
     *
     * @param reference the reference of the deleted document
     * @param locale the locale of the deleted document
     * @param xcontext the XWiki context
     * @return the deleted documents, the most recently deleted first
     * @throws XWikiException when failing to read the recycle bin
     * @since 18.10.0RC1
     */
    protected XWikiDeletedDocument[] getDeletedDocuments(DocumentReference reference, Locale locale,
        XWikiContext xcontext) throws XWikiException
    {
        // The recycle bin store reads the database of the context wiki
        String currentWiki = xcontext.getWikiId();
        xcontext.setWikiId(reference.getWikiReference().getName());
        try {
            XWikiDeletedDocument[] deletedDocuments = xcontext.getWiki().getRecycleBinStore()
                .getAllDeletedDocuments(new XWikiDocument(reference, locale), xcontext, true);

            return deletedDocuments != null ? deletedDocuments : new XWikiDeletedDocument[0];
        } finally {
            xcontext.setWikiId(currentWiki);
        }
    }

    /**
     * Restore a document from the recycle bin, without saving it.
     *
     * @param deletedDocument the deleted document to restore
     * @param reference the reference of the deleted document
     * @param xcontext the XWiki context
     * @return the restored document
     * @throws XWikiException when failing to restore the document
     * @since 18.10.0RC1
     */
    protected XWikiDocument restoreDeletedDocument(XWikiDeletedDocument deletedDocument, DocumentReference reference,
        XWikiContext xcontext) throws XWikiException
    {
        // The reference stored in the recycle bin is resolved against the context wiki
        String currentWiki = xcontext.getWikiId();
        xcontext.setWikiId(reference.getWikiReference().getName());
        try {
            XWikiDocument document = deletedDocument.restoreDocument(xcontext);

            // The document existed before it was deleted
            document.setNew(false);

            return document;
        } finally {
            xcontext.setWikiId(currentWiki);
        }
    }
}
