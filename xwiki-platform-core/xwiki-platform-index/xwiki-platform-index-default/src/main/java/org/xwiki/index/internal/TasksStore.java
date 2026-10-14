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
package org.xwiki.index.internal;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.doc.tasks.XWikiDocumentIndexingTask;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.store.XWikiHibernateBaseStore;

/**
 * Provide the operations to interact with the tasks store.
 *
 * @version $Id$
 * @since 14.1RC1
 */
@Component(roles = TasksStore.class)
@Singleton
public class TasksStore extends XWikiHibernateBaseStore
{
    private static final String QUERY_TASK_DELETION =
        "delete from XWikiDocumentIndexingTask t where t.docId = :docId and t.type = :type ";

    private static final String DOC_ID = "docId";

    private static final String TYPE = "type";

    private static final String INSTANCE_ID = "instanceId";

    private static final String OLDEST = "oldest";

    private static final String OLDEST_CONDITION = " and t.timestamp >= :oldest";

    @Inject
    private ExecutionContextManager contextManager;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    /**
     * Retrieve the list of all the tasks queued for a given wiki for the current instance.
     *
     * @param wikiId the wiki in which to execute the query
     * @param instanceId the identifier of the cluster instance in which to execute the task. Each cluster member is
     *     in charge of consuming its own tasks
     * @return the list of all the task
     * @throws XWikiException in case of error when creating or executing the query
     */
    public List<XWikiDocumentIndexingTask> getAllTasks(String wikiId, String instanceId) throws XWikiException
    {
        return initWikiContext(xWikiContext -> executeRead(xWikiContext,
            session -> session.createQuery("SELECT t FROM XWikiDocumentIndexingTask t "
                    + "WHERE t.instanceId = :instanceId")
                .setParameter(INSTANCE_ID, instanceId)
                .getResultList()), wikiId);
    }

    /**
     * Gives the identifiers of the cluster members which queued tasks in a given wiki, for example to find the tasks
     * left by a cluster member which is gone for good.
     *
     * @param wikiId the wiki in which to execute the query
     * @param excludedInstanceId the identifier of a cluster member to leave out of the result, generally the
     *     identifier of the current instance
     * @param oldest only the members with a task queued at or after this date are returned, {@code null} for no limit
     * @return the distinct identifiers of the cluster members (other than the excluded one) with at least one task in
     *     the wiki, the tasks without identifier are ignored
     * @throws XWikiException in case of error when creating or executing the query
     * @since 18.9.0RC1
     */
    public List<String> getInstanceIds(String wikiId, String excludedInstanceId, Date oldest) throws XWikiException
    {
        return initWikiContext(xWikiContext -> executeRead(xWikiContext, session -> {
            StringBuilder statement = new StringBuilder("select distinct t.instanceId from XWikiDocumentIndexingTask t"
                + " where t.instanceId is not null and t.instanceId <> :instanceId");
            if (oldest != null) {
                statement.append(OLDEST_CONDITION);
            }
            Query<String> query = session.createQuery(statement.toString(), String.class)
                .setParameter(INSTANCE_ID, excludedInstanceId);
            if (oldest != null) {
                query.setParameter(OLDEST, oldest);
            }
            return query.getResultList();
        }), wikiId);
    }

    /**
     * Assigns to another cluster member the tasks queued by a cluster member in a given wiki during a period, for
     * example to take over the tasks left by a cluster member which is gone for good. The change is done with a single
     * update query, so each task is assigned to the new member once, and the member which queued the tasks does not
     * load them anymore if it comes back.
     *
     * @param wikiId the wiki in which to execute the query
     * @param instanceId the identifier of the cluster member which queued the tasks
     * @param newInstanceId the identifier of the cluster member which takes over the tasks, generally the current
     *     instance
     * @param oldest only the tasks queued at or after this date are assigned, {@code null} for no limit
     * @param newest only the tasks queued at or before this date are assigned
     * @return the number of tasks assigned to the new cluster member
     * @throws XWikiException in case of error when creating or executing the query
     * @since 18.9.0RC1
     */
    public int reassignTasks(String wikiId, String instanceId, String newInstanceId, Date oldest, Date newest)
        throws XWikiException
    {
        return initWikiContext(xWikiContext -> executeWrite(xWikiContext, session -> {
            StringBuilder statement = new StringBuilder("update XWikiDocumentIndexingTask t"
                + " set t.instanceId = :newInstanceId where t.instanceId = :instanceId and t.timestamp <= :newest");
            if (oldest != null) {
                statement.append(OLDEST_CONDITION);
            }
            Query<?> query = session.createQuery(statement.toString())
                .setParameter("newInstanceId", newInstanceId)
                .setParameter(INSTANCE_ID, instanceId)
                .setParameter("newest", newest);
            if (oldest != null) {
                query.setParameter(OLDEST, oldest);
            }
            return query.executeUpdate();
        }), wikiId);
    }

    /**
     * Counts the tasks queued by a cluster member in a given wiki.
     *
     * @param wikiId the wiki in which to execute the query
     * @param instanceId the identifier of the cluster member which queued the tasks
     * @return the number of tasks of the cluster member in the wiki
     * @throws XWikiException in case of error when creating or executing the query
     * @since 18.9.0RC1
     */
    public long countTasks(String wikiId, String instanceId) throws XWikiException
    {
        return initWikiContext(xWikiContext -> executeRead(xWikiContext,
            session -> session.createQuery(
                    "select count(t) from XWikiDocumentIndexingTask t where t.instanceId = :instanceId", Long.class)
                .setParameter(INSTANCE_ID, instanceId)
                .getSingleResult()), wikiId);
    }

    /**
     * Persist a task to the queue.
     *
     * @param wikiId the wiki in which to execute the query
     * @param task the task to persist
     * @throws XWikiException in case of error when saving the task
     */
    public void addTask(String wikiId, XWikiDocumentIndexingTask task) throws XWikiException
    {
        initWikiContext(xWikiContext -> {
            executeWrite(xWikiContext, session -> {
                innerAddTask(task, session);
                return null;
            });
            return null;
        }, wikiId);
    }

    /**
     * Remove a task from the queue.
     *
     * @param wikiId the wiki in which to execute the query
     * @param docId the docId to remove
     * @param version the version to remove
     * @param type the type of the task to remove
     * @throws XWikiException in case of error when removing the task
     */
    public void deleteTask(String wikiId, long docId, String version, String type) throws XWikiException
    {
        initWikiContext(xWikiContext -> {
            executeWrite(xWikiContext, session -> {
                String query = QUERY_TASK_DELETION;
                if (StringUtils.isEmpty(version)) {
                    // The is null part is required for Oracle.
                    query += "and (t.version = :version or t.version is null)";
                } else {
                    query += "and t.version = :version";
                }
                session.createQuery(query)
                    .setParameter(DOC_ID, docId)
                    .setParameter("version", version)
                    .setParameter(TYPE, type)
                    .executeUpdate();
                return null;
            });
            return null;
        }, wikiId);
    }

    /**
     * Remove all tasks of the same type and document, regardless of tehe version, then add the new task to the queue.
     *
     * @param wikiId the wiki in which to execute the query
     * @param task the task replacing the previously queued tasks for the same document and the same type
     * @throws XWikiException in case of error when removing or adding the tasks
     */
    public void replaceTask(String wikiId, XWikiDocumentIndexingTask task) throws XWikiException
    {
        initWikiContext(xWikiContext -> {
            executeWrite(xWikiContext, session -> {
                session.createQuery(QUERY_TASK_DELETION)
                    .setParameter(DOC_ID, task.getDocId())
                    .setParameter(TYPE, task.getType())
                    .executeUpdate();
                innerAddTask(task, session);
                return null;
            });
            return null;
        }, wikiId);
    }

    /**
     * Return  an {@link XWikiDocument} by its id.
     *
     * @param wikiId the wiki in which to execute the query
     * @param docId the id of the document to retrieve
     * @return the document
     * @throws XWikiException in case of error when executing the query
     */
    public XWikiDocument getDocument(String wikiId, long docId) throws XWikiException
    {
        return initWikiContext(context -> executeRead(context, session -> (XWikiDocument)
            session.createQuery("select doc from XWikiDocument doc where doc.id = :docId")
                .setParameter(DOC_ID, docId)
                .getSingleResult()
        ), wikiId);
    }

    private <T> T initWikiContext(Lambda<T> r, String wikiId) throws XWikiException
    {
        ExecutionContext executionContext = new ExecutionContext();
        try {
            this.contextManager.pushContext(executionContext, false);
            this.contextManager.initialize(executionContext);

            XWikiContext xWikiContext = this.xcontextProvider.get();
            xWikiContext.setWikiId(wikiId);
            return r.call(xWikiContext);
        } catch (Exception e) {
            throw new XWikiException("Failed to executed the task in the context", e);
        } finally {
            this.contextManager.popContext();
        }
    }

    private void innerAddTask(XWikiDocumentIndexingTask task, Session session)
    {
        // In case of inconsistent data. But the timestamp is expected to be initialized by the caller.
        if (task.getTimestamp() == null) {
            task.setTimestamp(new Date());
        }

        // Update allowed in case the same document is queued again with the same version and the same task on 
        // restart.
        session.saveOrUpdate(task);
    }

    /**
     * Internal functional interface with no return value and the possibility to throw exceptions.
     */
    @FunctionalInterface
    private interface Lambda<T>
    {
        T call(XWikiContext context) throws Exception;
    }
}
