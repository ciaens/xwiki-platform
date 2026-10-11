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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.Logger;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.job.DefaultJobStatus;
import org.xwiki.job.DefaultRequest;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.job.internal.JobStatusFolderResolver;
import org.xwiki.job.internal.JobStatusSerializer;
import org.xwiki.job.internal.PersistentJobStatusStore;
import org.xwiki.logging.LogLevel;
import org.xwiki.logging.LogQueue;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.logging.tail.LoggerTail;
import org.xwiki.observation.remote.RemoteObservationManagerConfiguration;
import org.xwiki.store.blob.BlobStoreManager;
import org.xwiki.store.blob.FileSystemBlobStoreProperties;
import org.xwiki.store.blob.internal.FileSystemBlobStore;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component integration tests for {@link DatabaseJobStatusStore}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({
    DatabaseJobStatusStore.class,
    JobStatusBlobStore.class,
    JobStatusIdentifierSerializer.class,
    JobStatusSerializer.class
})
class DatabaseJobStatusStoreTest extends AbstractJobStatusHibernateTest
{
    private static final String NODE_ID = "node-job-store";

    private static final String OTHER_NODE_ID = "node-other";

    private static final String THIRD_NODE_ID = "node-third";

    private static final String REMOTE_LOOKUP_PROPERTY = "job.statusRemoteLookup";

    private static final List<String> REMOTE_ID = List.of("jobs", "remote", "status");

    @InjectMockComponents
    private DatabaseJobStatusStore store;

    @MockComponent
    private BlobStoreManager blobStoreManager;

    @MockComponent
    @Named("filesystem")
    private PersistentJobStatusStore filesystemStore;

    @MockComponent
    private RemoteObservationManagerConfiguration remoteObservationManagerConfiguration;

    @MockComponent
    @Named("version3")
    private JobStatusFolderResolver folderResolver;

    @MockComponent
    @Named("xwikiproperties")
    private ConfigurationSource configuration;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Override
    protected void configureAdditionalComponents() throws Exception
    {
        when(this.remoteObservationManagerConfiguration.getId()).thenReturn(NODE_ID);
        when(this.remoteObservationManagerConfiguration.isEnabled()).thenReturn(true);
        when(this.configuration.getProperty(REMOTE_LOOKUP_PROPERTY, true)).thenReturn(true);
        when(this.filesystemStore.loadJobStatus(ArgumentMatchers.anyList())).thenReturn(null);
        when(this.folderResolver.getFolderSegments(ArgumentMatchers.anyList()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, FileSystemBlobStore> blobStores = new HashMap<>();
        when(this.blobStoreManager.getBlobStore(ArgumentMatchers.anyString())).thenAnswer(invocation -> blobStores
            .computeIfAbsent(invocation.getArgument(0), key -> {
                try {
                    FileSystemBlobStoreProperties properties = new FileSystemBlobStoreProperties();
                    properties.setRootDirectory(this.tmpDir.toPath().resolve("blob").resolve(key));
                    return new FileSystemBlobStore(key, properties);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
    }

    @Test
    void saveLoadAndRemoveStatusWithPaginatedLogs() throws Exception
    {
        DefaultRequest request = new DefaultRequest();
        request.setId(List.of("jobs", "integration", "status"));

        DefaultJobStatus<DefaultRequest> status = new DefaultJobStatus<>("test-job-type", request, null,
            mock(), mock());
        status.setState(JobStatus.State.FINISHED);
        status.setStartDate(new Date(1000L));
        status.setEndDate(new Date(2000L));

        LogQueue logs = new LogQueue();
        for (int i = 0; i < 260; ++i) {
            logs.log(new LogEvent(LogLevel.INFO, "status-message-" + i, null, null));
        }
        status.setLoggerTail(logs);

        this.store.saveJobStatus(status);

        JobStatus loaded = this.store.loadJobStatus(request.getId());
        assertNotSame(status, loaded);
        assertInstanceOf(DefaultJobStatus.class, loaded);
        @SuppressWarnings("unchecked")
        DefaultJobStatus<DefaultRequest> defaultStatus = (DefaultJobStatus<DefaultRequest>) loaded;
        assertEquals("test-job-type", defaultStatus.getJobType());
        assertEquals(JobStatus.State.FINISHED, defaultStatus.getState());
        assertNotNull(defaultStatus.getLoggerTail());
        assertEquals(260, defaultStatus.getLoggerTail().size());

        List<String> messages = new ArrayList<>();
        LoggerTail loggerTail = defaultStatus.getLoggerTail();
        loggerTail.iterator().forEachRemaining(event -> messages.add(event.getFormattedMessage()));
        assertEquals(260, messages.size());
        assertEquals("status-message-0", messages.getFirst());
        assertEquals("status-message-259", messages.getLast());

        LogEvent logEvent = new LogEvent(LogLevel.INFO, "cannot-write", null, null);
        assertThrows(UnsupportedOperationException.class, () -> loggerTail.log(logEvent));

        // We should also be able to access the logs from the database.
        LoggerTail databaseLoggerTail = this.store.createLoggerTail(request.getId(), true);
        assertEquals(260, databaseLoggerTail.size());

        this.store.removeJobStatus(request.getId());
        assertNull(this.store.loadJobStatus(request.getId()));

        // Now the logs should be empty.
        assertEquals(0, databaseLoggerTail.size());

        // The filesystem job status should be removed twice, once when loading and once when removing.
        verify(this.filesystemStore, times(2)).removeJobStatus(request.getId());
    }

    @Test
    void loadJobStatusMigratesFromFilesystem() throws Exception
    {
        List<String> id = List.of("jobs", "migration", "load");
        DefaultRequest request = new DefaultRequest();
        request.setId(id);

        DefaultJobStatus<DefaultRequest> filesystemStatus = new DefaultJobStatus<>("migrate-job", request, null,
            mock(), mock());
        filesystemStatus.setState(JobStatus.State.FINISHED);
        filesystemStatus.setStartDate(new Date(3000L));
        filesystemStatus.setEndDate(new Date(4000L));

        LogQueue logs = new LogQueue();
        for (int i = 0; i < 5; ++i) {
            logs.log(new LogEvent(LogLevel.INFO, "migrated-message-" + i, null, null));
        }
        filesystemStatus.setLoggerTail(logs);

        when(this.filesystemStore.loadJobStatus(id)).thenReturn(filesystemStatus).thenReturn(null);

        // First load: status should be migrated from filesystem to database. The same instance is returned
        // with the logger tail replaced with a DatabaseLoggerTail.
        JobStatus loaded = this.store.loadJobStatus(id);
        assertNotNull(loaded);
        assertInstanceOf(DefaultJobStatus.class, loaded);
        @SuppressWarnings("unchecked")
        DefaultJobStatus<DefaultRequest> defaultStatus = (DefaultJobStatus<DefaultRequest>) loaded;
        assertEquals("migrate-job", defaultStatus.getJobType());
        assertEquals(JobStatus.State.FINISHED, defaultStatus.getState());

        // The migrated status should have a DatabaseLoggerTail attached with all migrated log entries.
        assertNotNull(defaultStatus.getLoggerTail());
        assertInstanceOf(DatabaseLoggerTail.class, defaultStatus.getLoggerTail());
        assertEquals(5, defaultStatus.getLoggerTail().size());

        List<String> messages = new ArrayList<>();
        defaultStatus.getLoggerTail().iterator().forEachRemaining(event -> messages.add(event.getFormattedMessage()));
        assertEquals("migrated-message-0", messages.getFirst());
        assertEquals("migrated-message-4", messages.getLast());

        // The filesystem store should have been asked to remove the status (via saveJobStatus).
        verify(this.filesystemStore).removeJobStatus(id);

        // Second load: status should now come from the database without consulting the filesystem again.
        JobStatus reloaded = this.store.loadJobStatus(id);
        assertNotNull(reloaded);
        assertInstanceOf(DefaultJobStatus.class, reloaded);
        // filesystemStore.loadJobStatus should have been called once only (already returned null on second call).
        verify(this.filesystemStore, times(1)).loadJobStatus(id);
    }

    @Test
    void loadJobStatusReturnsNullWhenNotFoundInDatabaseOrFilesystem() throws Exception
    {
        List<String> id = List.of("jobs", "missing", "status");

        // filesystemStore already returns null by default from configureComponents.
        JobStatus result = this.store.loadJobStatus(id);

        assertNull(result);
        // saveJobStatus on a null status shouldn't write anything - the filesystem store's removeJobStatus
        // should never have been called.
        verify(this.filesystemStore, never()).removeJobStatus(id);
    }

    @Test
    void createReadOnlyLoggerTailMigratesFromFilesystem() throws Exception
    {
        List<String> id = List.of("jobs", "migration", "logtail");
        DefaultRequest request = new DefaultRequest();
        request.setId(id);

        DefaultJobStatus<DefaultRequest> filesystemStatus = new DefaultJobStatus<>("migrate-log-job", request, null,
            mock(), mock());
        filesystemStatus.setState(JobStatus.State.FINISHED);

        LogQueue logs = new LogQueue();
        for (int i = 0; i < 3; ++i) {
            logs.log(new LogEvent(LogLevel.WARN, "warn-message-" + i, null, null));
        }
        filesystemStatus.setLoggerTail(logs);

        when(this.filesystemStore.loadJobStatus(id)).thenReturn(filesystemStatus).thenReturn(null);

        // Creating a read-only logger tail should trigger migration of the filesystem status to the database.
        LoggerTail loggerTail = this.store.createLoggerTail(id, true);

        assertNotNull(loggerTail);
        assertInstanceOf(DatabaseLoggerTail.class, loggerTail);
        assertEquals(3, loggerTail.size());

        List<String> messages = new ArrayList<>();
        loggerTail.iterator().forEachRemaining(event -> messages.add(event.getFormattedMessage()));
        assertEquals("warn-message-0", messages.getFirst());
        assertEquals("warn-message-2", messages.getLast());

        // The filesystem status should have been removed as part of the migration (via saveJobStatus).
        verify(this.filesystemStore).removeJobStatus(id);
    }

    @Test
    void createWritableLoggerTailRemovesFilesystemStatus() throws Exception
    {
        List<String> id = List.of("jobs", "migration", "writable");

        // Creating a writable logger tail should remove the filesystem status to prevent future migration.
        LoggerTail loggerTail = this.store.createLoggerTail(id, false);

        assertNotNull(loggerTail);
        assertInstanceOf(DatabaseLoggerTail.class, loggerTail);

        verify(this.filesystemStore).removeJobStatus(id);
    }

    @Test
    void loadRemoteJobStatusReturnsTheMostRecentStatusOfTheOtherMembers() throws Exception
    {
        saveAs(OTHER_NODE_ID, createStatus("other-type", 1000L, 2000L, 3));
        saveAs(THIRD_NODE_ID, createStatus("third-type", 3000L, 4000L, 2));

        // The current member never executed the job.
        setNodeId(NODE_ID);
        assertNull(this.store.loadJobStatus(REMOTE_ID));

        JobStatus remoteStatus = this.store.loadRemoteJobStatus(REMOTE_ID);
        assertEquals("third-type", remoteStatus.getJobType());
        assertEquals(JobStatus.State.FINISHED, remoteStatus.getState());
        // The log is the one stored by the member that executed the job, read from the database.
        LoggerTail loggerTail = ((DefaultJobStatus<?>) remoteStatus).getLoggerTail();
        assertInstanceOf(DatabaseLoggerTail.class, loggerTail);
        assertEquals(2, loggerTail.size());
        assertEquals("third-type-message-1", loggerTail.getLastLogEvent(LogLevel.INFO).getFormattedMessage());

        // Another member runs the job again: the next read returns the new status, nothing was cached.
        saveAs(OTHER_NODE_ID, createStatus("other-type-again", 5000L, 6000L, 1));
        setNodeId(NODE_ID);
        assertEquals("other-type-again", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());

        // A member never reads its own status as a remote one.
        setNodeId(OTHER_NODE_ID);
        assertEquals("third-type", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());

        // A status removed by its member is not visible to the others anymore.
        this.store.removeJobStatus(REMOTE_ID);
        setNodeId(NODE_ID);
        assertEquals("third-type", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());

        // The statuses of the other members are never returned as the status of the current member.
        assertNull(this.store.loadJobStatus(REMOTE_ID));
    }

    @Test
    void loadRemoteJobStatusPrefersAnEndedStatusOverAnOlderOrUndatedOne() throws Exception
    {
        saveAs(OTHER_NODE_ID, createStatus("undated-type", null, null, 0));
        saveAs(THIRD_NODE_ID, createStatus("started-type", 3000L, null, 0));
        setNodeId(NODE_ID);

        assertEquals("started-type", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());

        saveAs(OTHER_NODE_ID, createStatus("ended-type", 1000L, 4000L, 0));
        setNodeId(NODE_ID);

        assertEquals("ended-type", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());
    }

    @Test
    void loadRemoteJobStatusSkipsUnreadableBlobs() throws Exception
    {
        saveAs(OTHER_NODE_ID, createStatus("other-type", 1000L, 2000L, 0));
        saveAs(THIRD_NODE_ID, createStatus("third-type", 3000L, 4000L, 0));
        setNodeId(NODE_ID);

        // The blob of the most recent status is not readable by the current member (for example a blob store that is
        // not shared between the members).
        JobStatusBlobStore blobStore = this.componentManager.getInstance(JobStatusBlobStore.class);
        blobStore.delete("/" + THIRD_NODE_ID + "/jobs/remote/status/status.xml.zip");
        Logger logger = mock();
        ReflectionUtils.setFieldValue(this.store, "logger", logger);

        assertEquals("other-type", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());
        verify(logger).debug(eq("Failed to load the job status [{}] stored by the cluster member [{}]: [{}]"),
            eq("jobs/remote/status"), eq(THIRD_NODE_ID), anyString());

        blobStore.delete("/" + OTHER_NODE_ID + "/jobs/remote/status/status.xml.zip");

        assertNull(this.store.loadRemoteJobStatus(REMOTE_ID));
    }

    @Test
    void loadRemoteJobStatusWhenDisabled() throws Exception
    {
        saveAs(OTHER_NODE_ID, createStatus("other-type", 1000L, 2000L, 0));
        setNodeId(NODE_ID);
        assertNotNull(this.store.loadRemoteJobStatus(REMOTE_ID));

        when(this.configuration.getProperty(REMOTE_LOOKUP_PROPERTY, true)).thenReturn(false);
        assertNull(this.store.loadRemoteJobStatus(REMOTE_ID));

        // A member without remote observation is not part of a cluster.
        when(this.configuration.getProperty(REMOTE_LOOKUP_PROPERTY, true)).thenReturn(true);
        when(this.remoteObservationManagerConfiguration.isEnabled()).thenReturn(false);
        assertNull(this.store.loadRemoteJobStatus(REMOTE_ID));

        assertNull(this.store.loadRemoteJobStatus(null));
    }

    @Test
    void loadRemoteJobStatusWhileTheOwnerRunsTheJobAgain() throws Exception
    {
        saveAs(OTHER_NODE_ID, createStatus("previous-run", 1000L, 2000L, 3));
        setNodeId(NODE_ID);
        assertEquals(3, ((DefaultJobStatus<?>) this.store.loadRemoteJobStatus(REMOTE_ID)).getLoggerTail().size());

        // The owner starts the same job again: its log rows are replaced while the summary and the blob still
        // describe the previous run.
        setNodeId(OTHER_NODE_ID);
        LoggerTail newRunTail = this.store.createLoggerTail(REMOTE_ID, false);

        setNodeId(NODE_ID);
        DefaultJobStatus<?> remoteStatus = (DefaultJobStatus<?>) this.store.loadRemoteJobStatus(REMOTE_ID);
        assertEquals("previous-run", remoteStatus.getJobType());
        assertEquals(0, remoteStatus.getLoggerTail().size());

        setNodeId(OTHER_NODE_ID);
        newRunTail.log(new LogEvent(LogLevel.INFO, "new-run-line", null, null));

        // The log of the new run is never shown with the status of the previous run.
        setNodeId(NODE_ID);
        remoteStatus = (DefaultJobStatus<?>) this.store.loadRemoteJobStatus(REMOTE_ID);
        assertEquals(JobStatus.State.FINISHED, remoteStatus.getState());
        assertEquals("previous-run", remoteStatus.getJobType());
        assertInstanceOf(LogQueue.class, remoteStatus.getLoggerTail());
        assertEquals(0, remoteStatus.getLoggerTail().size());
    }

    @Test
    void loadRemoteJobStatusWithTheSameDatesOnTwoMembers() throws Exception
    {
        saveAs(THIRD_NODE_ID, createStatus("third-type", 1000L, 2000L, 0));
        saveAs(OTHER_NODE_ID, createStatus("other-type", 1000L, 2000L, 0));
        setNodeId(NODE_ID);

        // On a tie, the member ids decide.
        assertEquals("other-type", this.store.loadRemoteJobStatus(REMOTE_ID).getJobType());
    }

    @Test
    void loadRemoteJobStatusOfDistributionJobs() throws Exception
    {
        List<String> farmId = List.of("distribution");
        List<String> wikiId = List.of("distribution", "wiki", "subwiki");
        saveAs(OTHER_NODE_ID, createStatus(farmId, "distribution", 1000L, 2000L, 0));
        saveAs(OTHER_NODE_ID, createStatus(wikiId, "distribution", 1000L, 2000L, 0));
        setNodeId(NODE_ID);

        assertNull(this.store.loadRemoteJobStatus(farmId));
        assertNull(this.store.loadRemoteJobStatus(wikiId));
        assertNull(this.store.loadRemoteJobStatus(List.of()));
    }

    @Test
    void loadRemoteJobStatusOfAnotherIdWithTheSameKey() throws Exception
    {
        // ["jobs", "remote/status"] and ["jobs", "remote", "status"] have the same database key.
        saveAs(OTHER_NODE_ID, createStatus(List.of("jobs", "remote/status"), "other-id", 1000L, 2000L, 0));
        setNodeId(NODE_ID);

        assertNull(this.store.loadRemoteJobStatus(REMOTE_ID));
    }

    private void setNodeId(String nodeId)
    {
        when(this.remoteObservationManagerConfiguration.getId()).thenReturn(nodeId);
    }

    private void saveAs(String nodeId, JobStatus status) throws Exception
    {
        setNodeId(nodeId);
        this.store.saveJobStatus(status);
    }

    private DefaultJobStatus<DefaultRequest> createStatus(String type, Long startDate, Long endDate, int logCount)
    {
        return createStatus(REMOTE_ID, type, startDate, endDate, logCount);
    }

    private DefaultJobStatus<DefaultRequest> createStatus(List<String> id, String type, Long startDate, Long endDate,
        int logCount)
    {
        DefaultRequest request = new DefaultRequest();
        request.setId(id);

        DefaultJobStatus<DefaultRequest> status = new DefaultJobStatus<>(type, request, null, mock(), mock());
        status.setState(JobStatus.State.FINISHED);
        status.setStartDate(startDate != null ? new Date(startDate) : null);
        status.setEndDate(endDate != null ? new Date(endDate) : null);

        LogQueue logs = new LogQueue();
        for (int i = 0; i < logCount; ++i) {
            // The log of a run is written between its start and its end.
            logs.log(new LogEvent(null, LogLevel.INFO, type + "-message-" + i, null, null,
                startDate != null ? startDate : 0L));
        }
        status.setLoggerTail(logs);

        return status;
    }
}
