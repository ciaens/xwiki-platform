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
package org.xwiki.job.store.internal;

import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.persistence.LockModeType;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.Session;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.job.AbstractJobStatus;
import org.xwiki.job.JobStatusStore;
import org.xwiki.job.Request;
import org.xwiki.job.event.status.CancelableJobStatus;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.job.internal.JobUtils;
import org.xwiki.job.internal.PersistentJobStatusStore;
import org.xwiki.job.store.internal.entity.JobStatusSummaryEntity;
import org.xwiki.job.store.internal.hibernate.JobStatusHibernateExecutor;
import org.xwiki.logging.LogQueue;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.logging.tail.LogTail;
import org.xwiki.logging.tail.LoggerTail;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;

import com.google.common.util.concurrent.Striped;

/**
 * Database-backed {@link JobStatusStore} that replaces the filesystem implementation.
 * <p>
 * Each cluster member stores and loads its own statuses (the summary rows are identified by the remote observation id
 * of the member). A member can also read the statuses stored by the other members, see
 * {@link #loadRemoteJobStatus(List)}.
 *
 * @version $Id$
 * @since 18.3.0RC1
 */
@Component
@Singleton
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public class DatabaseJobStatusStore implements PersistentJobStatusStore
{
    private static final String BLOB_FILENAME = "status.xml.zip";

    private static final String NODE_ID = "nodeId";

    private static final String STATUS_KEY = "statusKey";

    private static final String FROM_SUMMARY_HQL = "from org.xwiki.job.store.internal.entity.JobStatusSummaryEntity ";

    private static final String SELECT_SUMMARY_HQL =
        FROM_SUMMARY_HQL + "where nodeId = :nodeId and statusKey = :statusKey";

    private static final String SELECT_REMOTE_SUMMARIES_HQL =
        FROM_SUMMARY_HQL + "where nodeId <> :nodeId and statusKey = :statusKey";

    private static final String REMOTE_LOOKUP_PROPERTY = "job.statusRemoteLookup";

    /**
     * The first element of the ids of the jobs whose status is never read from another cluster member.
     */
    private static final Set<String> MEMBER_SCOPED_JOBS = Set.of("distribution");

    private static final String PATH_SEPARATOR = "/";

    @Inject
    @Named("filesystem")
    private PersistentJobStatusStore filesystemStore;

    @Inject
    private Logger logger;

    @Inject
    private JobStatusIdentifierSerializer identifierResolver;

    @Inject
    private JobStatusBlobStore jobStatusBlobStore;

    @Inject
    private RemoteObservationManagerConfiguration remoteObservationManagerConfiguration;

    @Inject
    private Provider<DatabaseLoggerTail> databaseLoggerTailProvider;

    @Inject
    private JobStatusHibernateExecutor hibernateExecutor;

    @Inject
    @Named("xwikiproperties")
    private ConfigurationSource configuration;

    // Use striped locks to limit the number of concurrent locks while still allowing some level of concurrency for
    // different job statuses. The lock is used to protect the on-demand migration process in loadJobStatus and avoid
    // multiple threads trying to migrate the same job status at the same time.
    private final Striped<Lock> stripedLocks = Striped.lazyWeakLock(64);

    private String getNodeId()
    {
        return this.remoteObservationManagerConfiguration.getId();
    }

    @Override
    public JobStatus loadJobStatus(List<String> id) throws IOException
    {
        // As this code should only be called when there is a cache miss, there is no need to optimize the lock
        // acquisition as much as possible - in theory, the lock is only needed for migration.
        Lock lock = getLock(id);
        lock.lock();
        try {
            JobStatus status = loadFromDatabase(id);
            if (status != null) {
                return status;
            }

            JobStatus legacyStatus = this.filesystemStore.loadJobStatus(id);
            // Migrate the job status to the database if found in the filesystem, so that next time it can be loaded
            // directly from the database.
            saveJobStatus(legacyStatus);
            // As the logs have been migrated to the database, we need to re-attach the logger tail to read the logs
            // from the database instead of the filesystem.
            attachReadOnlyLoggerTail(legacyStatus, getNodeId(), this.identifierResolver.getDatabaseKey(id));
            return legacyStatus;
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the most recent of the statuses stored for this job by the other cluster members (by end date, or start
     * date when the job has not ended, as measured by the clock of each member; on a tie, by member id), with a
     * read-only access to its log in the database. Skipped: a status whose blob cannot be read (for example because the
     * blob store is not shared between the members) and a status stored for another id with the same database key.
     * <p>
     * When the member that stored the status runs the same job again, the summary row and the blob still describe the
     * previous run until the new one ends, while the log rows already belong to the new run. The log is therefore only
     * attached when its first entry is not more recent than the end of the returned run, checked when the status is
     * loaded; otherwise the status is returned with an empty log.
     * <p>
     * Not used for the Distribution Wizard jobs (ids starting with {@code distribution}): a member compares the
     * previous status of these jobs with its current distribution and reuses its step states, while the steps check
     * the extensions installed on the member itself, so each member has to decide from its own status.
     * <p>
     * Only used when remote observation is enabled and the {@code job.statusRemoteLookup} configuration property is
     * not {@code false}. Nothing is cached here.
     *
     * @since 18.9.0RC1
     */
    @Override
    public JobStatus loadRemoteJobStatus(List<String> id) throws IOException
    {
        if (id == null || id.isEmpty() || MEMBER_SCOPED_JOBS.contains(id.get(0)) || !isRemoteLookupEnabled()) {
            return null;
        }

        String nodeId = getNodeId();
        String statusKey = this.identifierResolver.getDatabaseKey(id);
        List<JobStatusSummaryEntity> entities;

        try {
            entities = this.hibernateExecutor.executeRead(session -> session
                .createQuery(SELECT_REMOTE_SUMMARIES_HQL, JobStatusSummaryEntity.class)
                .setParameter(NODE_ID, nodeId)
                .setParameter(STATUS_KEY, statusKey)
                .list());
        } catch (Exception e) {
            throw new IOException("Failed to read the job status summaries of the other cluster members.", e);
        }

        // Most recent first, the summaries without any date last. Sorted here because the position of null values in
        // an ORDER BY depends on the database.
        entities.sort(Comparator
            .comparing(DatabaseJobStatusStore::getLastDate, Comparator.nullsFirst(Comparator.<Date>naturalOrder()))
            .reversed().thenComparing(JobStatusSummaryEntity::getNodeId));

        for (JobStatusSummaryEntity entity : entities) {
            JobStatus status = loadRemoteJobStatus(entity);
            if (status != null && status.getRequest() != null && id.equals(status.getRequest().getId())) {
                attachRemoteLoggerTail(status, entity.getNodeId(), statusKey);
                return status;
            }
        }

        return null;
    }

    private JobStatus loadRemoteJobStatus(JobStatusSummaryEntity entity)
    {
        try {
            return this.jobStatusBlobStore.load(entity.getBlobLocator());
        } catch (IOException e) {
            this.logger.debug("Failed to load the job status [{}] stored by the cluster member [{}]: [{}]",
                entity.getFullJobId(), entity.getNodeId(), ExceptionUtils.getRootCauseMessage(e));

            return null;
        }
    }

    private void attachRemoteLoggerTail(JobStatus status, String nodeId, String statusKey)
    {
        if (status instanceof AbstractJobStatus<?> abstractJobStatus) {
            LoggerTail tail = this.databaseLoggerTailProvider.get().initialize(nodeId, statusKey, true);
            LogEvent firstLogEvent = tail.getFirstLogEvent(null);
            Date endDate = status.getEndDate();
            if (firstLogEvent != null && (endDate == null || firstLogEvent.getTimeStamp() <= endDate.getTime())) {
                abstractJobStatus.setLoggerTail(tail);
            } else {
                // No log rows yet, or rows of a newer run of the same job: do not show them with this status.
                abstractJobStatus.setLoggerTail(new LogQueue());
            }
        }
    }

    private boolean isRemoteLookupEnabled()
    {
        return this.remoteObservationManagerConfiguration.isEnabled()
            && this.configuration.getProperty(REMOTE_LOOKUP_PROPERTY, true);
    }

    private static Date getLastDate(JobStatusSummaryEntity entity)
    {
        return entity.getEndDate() != null ? entity.getEndDate() : entity.getStartDate();
    }

    private @NonNull Lock getLock(List<String> id)
    {
        String rawId = this.identifierResolver.getRawId(id);
        return this.stripedLocks.get(rawId);
    }

    @Override
    public void removeJobStatus(List<String> id) throws IOException
    {
        if (id == null) {
            return;
        }

        Lock lock = getLock(id);
        lock.lock();
        try {
            this.filesystemStore.removeJobStatus(id);

            String nodeId = getNodeId();
            String statusKey = this.identifierResolver.getDatabaseKey(id);

            JobStatusSummaryEntity entity = this.hibernateExecutor.executeWrite(session -> {
                JobStatusSummaryEntity result = session
                    .createQuery(SELECT_SUMMARY_HQL, JobStatusSummaryEntity.class)
                    .setParameter(NODE_ID, nodeId)
                    .setParameter(STATUS_KEY, statusKey)
                    // Use pessimistic lock to make sure that the job status won't be modified by another thread
                    // while we are deleting it.
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setMaxResults(1)
                    .uniqueResult();

                // Initialize a DatabaseLoggerTail to delete the logs from the database.
                try (DatabaseLoggerTail tail = this.databaseLoggerTailProvider.get()) {
                    tail.initialize(nodeId, statusKey, session);
                }

                if (result != null) {
                    session.delete(result);
                }

                return result;
            });

            if (entity != null) {
                String blobLocator = entity.getBlobLocator();
                this.jobStatusBlobStore.delete(blobLocator);
            }
        } catch (Exception e) {
            throw new IOException("Failed to remove job status [%s] from the database."
                .formatted(this.identifierResolver.getRawId(id)), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public LoggerTail createLoggerTail(List<String> jobId, boolean readonly)
    {
        if (jobId == null) {
            return new LogQueue();
        }

        String nodeId = getNodeId();
        String statusKey = this.identifierResolver.getDatabaseKey(jobId);

        if (readonly) {
            // Trigger on-demand migration to make sure the logs are moved to the database if needed.
            Lock lock = getLock(jobId);
            lock.lock();
            try {
                JobStatus legacyStatus = this.filesystemStore.loadJobStatus(jobId);
                if (legacyStatus != null) {
                    saveJobStatus(legacyStatus);
                }
            } catch (Exception e) {
                this.logger.warn("Failed to migrate job status [{}] from filesystem: [{}].", jobId,
                    ExceptionUtils.getRootCauseMessage(e));
                return this.filesystemStore.createLoggerTail(jobId, true);
            } finally {
                lock.unlock();
            }
        } else {
            // Try deleting the logs from the filesystem to avoid wrongly migrating the logs to the database later
            // when the logs shall be read. We only log a warning here because in most cases, it shouldn't cause any
            // issues.
            try {
                this.filesystemStore.removeJobStatus(jobId);
            } catch (IOException e) {
                this.logger.warn("Failed to remove legacy job status [{}] from filesystem: [{}].", jobId,
                    ExceptionUtils.getRootCauseMessage(e));
            }
        }

        return this.databaseLoggerTailProvider.get().initialize(nodeId, statusKey, readonly);
    }

    @Override
    public void saveJobStatus(JobStatus status) throws IOException
    {
        if (!JobUtils.isSerializable(status)) {
            return;
        }

        String nodeId = getNodeId();
        Request request = status.getRequest();
        String statusKey = this.identifierResolver.getDatabaseKey(request.getId());
        String blobLocator = buildBlobLocator(this.identifierResolver.getBlobKey(request.getId()));

        // Lock to avoid parallel loading and migrations.
        Lock lock = getLock(request.getId());
        lock.lock();

        try {
            LogTail logTail = status.getLogTail();
            boolean rewriteLogs = !(logTail instanceof DatabaseLoggerTail);

            // Store first in the blob store, so if another cluster node finds the blob locator in the database, we're
            // sure the blob exists already.
            this.jobStatusBlobStore.store(status, blobLocator);

            this.hibernateExecutor.executeWrite(session -> {
                JobStatusSummaryEntity entity = session
                    .createQuery(SELECT_SUMMARY_HQL, JobStatusSummaryEntity.class)
                    .setParameter(NODE_ID, nodeId)
                    .setParameter(STATUS_KEY, statusKey)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setMaxResults(1)
                    .uniqueResult();

                if (entity == null) {
                    entity = new JobStatusSummaryEntity();
                    entity.setStatusKey(statusKey);
                    entity.setNodeId(nodeId);
                }

                entity.setFullJobId(this.identifierResolver.getRawId(request.getId()));
                entity.setJobType(status.getJobType());
                entity.setState(status.getState() != null ? status.getState().name() : null);
                entity.setStartDate(status.getStartDate());
                entity.setEndDate(status.getEndDate());
                entity.setSerialized(status.isSerialized());
                entity.setIsolated(status.isIsolated());
                entity.setCancelable(isCancelable(status));
                entity.setCanceled(isCanceled(status));
                entity.setBlobLocator(blobLocator);

                session.saveOrUpdate(entity);

                if (rewriteLogs) {
                    persistLogs(session, nodeId, statusKey, logTail);
                }

                return null;
            });

            this.filesystemStore.removeJobStatus(request.getId());
        } catch (JobStatusStoreException e) {
            throw new IOException(
                "Failed to persist job status metadata for [%s].".formatted(status.getRequest().getId()), e);
        } finally {
            lock.unlock();
        }
    }

    private void persistLogs(Session session, String nodeId, String statusKey, LogTail loggerTail)
    {
        // Skip if the log is already stored in the database.
        if (loggerTail == null || loggerTail instanceof DatabaseLoggerTail) {
            return;
        }

        // This initialization deletes the existing logs for the given job status, so we can be sure that only the
        // new logs will be stored in the database.
        try (DatabaseLoggerTail tail = this.databaseLoggerTailProvider.get().initialize(nodeId, statusKey, false)) {
            long lineIndex = 0;
            for (LogEvent event : loggerTail) {
                if (event == null) {
                    continue;
                }
                tail.appendLogEntry(lineIndex++, event, session);
            }
        }
    }

    private JobStatus loadFromDatabase(List<String> id) throws IOException
    {
        String nodeId = getNodeId();
        String statusKey = this.identifierResolver.getDatabaseKey(id);
        JobStatusSummaryEntity entity;

        try {
            entity = this.hibernateExecutor.executeRead(session -> session
                .createQuery(SELECT_SUMMARY_HQL, JobStatusSummaryEntity.class)
                .setParameter(NODE_ID, nodeId)
                .setParameter(STATUS_KEY, statusKey)
                .setMaxResults(1)
                .uniqueResult());
        } catch (Exception e) {
            throw new IOException("Failed to read job status summary from database.", e);
        }

        if (entity == null) {
            return null;
        }

        String blobLocator = entity.getBlobLocator();
        JobStatus status = this.jobStatusBlobStore.load(blobLocator);
        attachReadOnlyLoggerTail(status, nodeId, statusKey);
        return status;
    }

    private void attachReadOnlyLoggerTail(JobStatus status, String nodeId, String statusKey)
    {
        if (status instanceof AbstractJobStatus<?> abstractJobStatus) {
            LoggerTail tail = this.databaseLoggerTailProvider.get().initialize(nodeId, statusKey, true);
            abstractJobStatus.setLoggerTail(tail);
        }
    }

    private boolean isCancelable(JobStatus status)
    {
        return status instanceof CancelableJobStatus c && c.isCancelable();
    }

    private boolean isCanceled(JobStatus status)
    {
        return status instanceof CancelableJobStatus c && c.isCanceled();
    }

    private String buildBlobLocator(String escapedKey)
    {
        return PATH_SEPARATOR + escapedKey + PATH_SEPARATOR + BLOB_FILENAME;
    }

}
