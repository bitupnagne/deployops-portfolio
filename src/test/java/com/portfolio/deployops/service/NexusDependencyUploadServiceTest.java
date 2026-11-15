package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NexusDependencyUploadServiceTest {
    @TempDir Path directory;
    NexusDependencyUploadRepository repository;
    NexusMetadataService nexus;
    NexusDependencyUploadService service;
    Map<String,NexusDependencyUpload> rows;

    @BeforeEach void setup() {
        rows = new ConcurrentHashMap<>();
        repository=mock(NexusDependencyUploadRepository.class);
        var guard=mock(ExecutionGuardRepository.class); when(guard.lockSingleton()).thenReturn(1);
        var projects=mock(ProjectRepository.class);when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).build()));
        nexus=mock(NexusMetadataService.class);when(nexus.dependencyBaseUrl()).thenReturn("http://nexus:8081");
        var settings=mock(SystemSettingService.class);
        when(settings.get(anyString(),anyString())).thenAnswer(i -> {
            String key=i.getArgument(0); return key.endsWith(".repository") ? "raw-hosted" : key.endsWith(".format") ? "raw" : "";
        });
        var transaction=mock(TransactionTemplate.class);
        doAnswer(i->{ java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback=i.getArgument(0);callback.accept(null);return null;})
            .when(transaction).executeWithoutResult(any());
        doAnswer(i->{NexusDependencyUpload r=i.getArgument(0);rows.put(r.getId(),copy(r));return null;}).when(repository).insert(any());
        doAnswer(i->{NexusDependencyUpload r=i.getArgument(0);rows.put(r.getId(),copy(r));return null;}).when(repository).save(any());
        when(repository.transition(anyString(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            synchronized(rows) {
                NexusDependencyUpload row=rows.get(i.getArgument(0));
                if(row==null || !row.getStatus().equals(i.getArgument(1))) return 0;
                row=copy(row); row.setStatus(i.getArgument(2)); row.setMessage(i.getArgument(3)); rows.put(row.getId(),row);
                return 1;
            }
        });
        when(repository.find(anyString())).thenAnswer(i->copy(rows.get(i.getArgument(0))));
        service=new NexusDependencyUploadService(repository,guard,projects,nexus,settings,transaction,directory.toString());
    }
    @AfterEach void close() {service.stop();}
    static NexusDependencyUpload copy(NexusDependencyUpload row) {
        if(row==null)return null;
        var out=new NexusDependencyUpload();BeanUtils.copyProperties(row,out);return out;
    }
    NexusDependencyUpload create(long size)throws Exception{return service.create(1L,2L,"a.bin",size);}
    NexusDependencyUpload chunk(String id,long offset,byte[] bytes)throws Exception {
        return service.chunk(id,1L,2L,offset,bytes.length,new ByteArrayInputStream(bytes));
    }
    @Test void resumeVerifiesExistingBytesAndRejectsAnotherOwnersSession()throws Exception {
        var row=create(6);chunk(row.getId(),0,new byte[]{1,2,3});
        assertEquals(3,chunk(row.getId(),0,new byte[]{1,2,3}).getReceivedBytes());
        assertThrows(Exception.class,()->chunk(row.getId(),0,new byte[]{9,2,3}));
        assertThrows(Exception.class,()->service.require(row.getId(),1L,99L));
        assertEquals("READY",chunk(row.getId(),3,new byte[]{4,5,6}).getStatus());
        assertArrayEquals(new byte[]{1,2,3,4,5,6},Files.readAllBytes(directory.resolve(row.getId()+".part")));
    }
    @Test void interruptedChunkTruncatesBackToConfirmedOffset()throws Exception {
        var row=create(6);chunk(row.getId(),0,new byte[]{1,2,3});
        assertThrows(Exception.class,()->service.chunk(row.getId(),1L,2L,3,3,new ByteArrayInputStream(new byte[]{4})));
        assertEquals(3,Files.size(directory.resolve(row.getId()+".part")));
        assertEquals(3,service.require(row.getId(),1L,2L).getReceivedBytes());
    }
    @Test void completedMeansRemoteDigestMatchesAndTemporaryFileIsRemoved()throws Exception {
        var row=create(3);chunk(row.getId(),0,new byte[]{1,2,3});
        String hash=NexusDependencyUploadService.hash(directory.resolve(row.getId()+".part"),()->{});
        when(nexus.dependencyDigest(anyString(),anyString(),anyString(),any())).thenReturn(hash);
        service.publish(row.getId(),1L,2L);waitFor(row.getId(),"COMPLETED");
        assertFalse(Files.exists(directory.resolve(row.getId()+".part")));
        verify(nexus).publishDependency(anyString(),eq("raw-hosted"),eq("a.bin"),any(),any());
    }
    @Test void remoteFailureIsNotSuccessAndCanOnlyRetryAfterVerification()throws Exception {
        var row=create(3);chunk(row.getId(),0,new byte[]{1,2,3});
        doThrow(new java.io.IOException("lost reply")).when(nexus).publishDependency(anyString(),anyString(),anyString(),any(),any());
        service.publish(row.getId(),1L,2L);waitFor(row.getId(),"VERIFY_REQUIRED");
        assertThrows(Exception.class,()->service.publish(row.getId(),1L,2L));
        assertThrows(Exception.class,()->service.cancel(row.getId(),1L,2L));
        // A 404 check safely returns the preserved file to READY, without another PUT.
        assertEquals("READY",service.verify(row.getId(),1L,2L).getStatus());
        verify(nexus,times(1)).publishDependency(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void existingRemoteAssetIsNeverUploadedAgain()throws Exception {
        var row=create(1);chunk(row.getId(),0,new byte[]{1});
        when(nexus.dependencyExists(anyString(),anyString(),anyString())).thenReturn(true);
        service.publish(row.getId(),1L,2L);waitFor(row.getId(),"READY");
        verify(nexus,never()).publishDependency(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void staleReadyCancelCannotDeleteFileAfterPublishHasStarted()throws Exception {
        var row=create(3); chunk(row.getId(),0,new byte[]{1,2,3});
        var staleRead=new java.util.concurrent.CountDownLatch(1);
        var releaseCancel=new java.util.concurrent.CountDownLatch(1);
        var remoteStarted=new java.util.concurrent.CountDownLatch(1);
        var intercepted=new java.util.concurrent.atomic.AtomicBoolean();
        when(repository.find(row.getId())).thenAnswer(i -> {
            var snapshot=copy(rows.get(row.getId()));
            if(Thread.currentThread().getName().equals("cancel-race") && intercepted.compareAndSet(false,true)) {
                staleRead.countDown();
                assertTrue(releaseCancel.await(5,java.util.concurrent.TimeUnit.SECONDS));
            }
            return snapshot;
        });
        doAnswer(i -> {
            remoteStarted.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch(InterruptedException e) { throw new java.io.IOException("cancelled after remote start",e); }
            return null;
        }).when(nexus).publishDependency(anyString(),anyString(),anyString(),any(),any());
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r,"cancel-race"));
        try {
            var cancelled=executor.submit(() -> service.cancel(row.getId(),1L,2L));
            assertTrue(staleRead.await(5,java.util.concurrent.TimeUnit.SECONDS));
            service.publish(row.getId(),1L,2L);
            assertTrue(remoteStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            releaseCancel.countDown(); cancelled.get(5,java.util.concurrent.TimeUnit.SECONDS);
            waitFor(row.getId(),"VERIFY_REQUIRED");
            assertTrue(Files.exists(directory.resolve(row.getId()+".part")));
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(directory.resolve(row.getId()+".part")));
        } finally {releaseCancel.countDown();executor.shutdownNow();}
    }
    @Test void chunkInProgressRejectsCancelWithoutDeletingConfirmedData()throws Exception {
        var row=create(3);
        var reading=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var input=new ByteArrayInputStream(new byte[]{1,2,3}) {
            @Override public synchronized int read(byte[] b,int offset,int length) {
                reading.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch(InterruptedException e) {throw new AssertionError(e);}
                return super.read(b,offset,length);
            }
        };
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var received=executor.submit(() -> service.chunk(row.getId(),1L,2L,0,3,input));
            assertTrue(reading.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> service.cancel(row.getId(),1L,2L));
            assertEquals("RECEIVING",service.require(row.getId(),1L,2L).getStatus());
            release.countDown(); received.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("CANCELLED",service.cancel(row.getId(),1L,2L).getStatus());
            assertFalse(Files.exists(directory.resolve(row.getId()+".part")));
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void concurrentVerificationCannotOverwriteANewerPublishClaim()throws Exception {
        var row=create(3);chunk(row.getId(),0,new byte[]{1,2,3});
        var persisted=rows.get(row.getId());persisted.setStatus("VERIFY_REQUIRED");
        var reading=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(nexus.dependencyDigest(anyString(),anyString(),anyString(),any())).thenAnswer(i -> {
            reading.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;
        });
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var verification=executor.submit(() -> service.verify(row.getId(),1L,2L));
            assertTrue(reading.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> service.verify(row.getId(),1L,2L));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> service.publish(row.getId(),1L,2L));
            assertEquals("VERIFY_REQUIRED",service.require(row.getId(),1L,2L).getStatus());
            release.countDown();
            assertEquals("READY",verification.get(5,java.util.concurrent.TimeUnit.SECONDS).getStatus());
        } finally {release.countDown();executor.shutdownNow();}
    }
    NexusDependencyUpload mavenRow(String status, boolean keepFile) throws Exception {
        var row=create(3);chunk(row.getId(),0,new byte[]{1,2,3});
        row=rows.get(row.getId());row.setRepositoryFormat("maven2");row.setTargetPath("com/acme/lib/1/lib-1.jar");
        row.setContentSha256(NexusDependencyUploadService.hash(directory.resolve(row.getId()+".part"),()->{}));
        row.setStatus(status);
        if(!keepFile)Files.delete(directory.resolve(row.getId()+".part"));
        return copy(row);
    }
    Map<String,String> hashes(NexusDependencyUpload row) {
        return Map.of("sha256",row.getContentSha256(),"sha1","1".repeat(40),"md5","2".repeat(32));
    }
    @Test void mavenPublishCompletesOnlyAfterSidecarRegistration() throws Exception {
        var row=mavenRow("READY",true);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any())).thenReturn(true);
        service.publish(row.getId(),1L,2L);waitFor(row.getId(),"COMPLETED");
        var ordered=inOrder(nexus);
        ordered.verify(nexus).publishDependency(anyString(),anyString(),eq(row.getTargetPath()),any(),any());
        ordered.verify(nexus).dependencyChecksums(anyString(),anyString(),eq(row.getTargetPath()),any());
        ordered.verify(nexus).dependencyChecksumFiles(anyString(),anyString(),eq(row.getTargetPath()),anyMap(),eq(true),any());
        assertFalse(Files.exists(directory.resolve(row.getId()+".part")));
    }
    @Test void completedLegacyMavenCanRepairWithoutTemporaryFileOrOriginalPut() throws Exception {
        var row=mavenRow("COMPLETED",false);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any())).thenReturn(true);
        assertThrows(Exception.class,()->service.repairChecksums(row.getId(),1L,99L));
        service.repairChecksums(row.getId(),1L,2L);waitFor(row.getId(),"COMPLETED");
        verify(nexus,never()).publishDependency(anyString(),anyString(),anyString(),any(),any());
        verify(nexus).assertDependencyRepository(row.getRepositoryName(),"maven2");
    }
    @Test void checksumVerificationIsReadOnlyAndMissingSidecarsRemainUncertain() throws Exception {
        var row=mavenRow("VERIFY_REQUIRED",false);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        assertEquals("VERIFY_REQUIRED",service.verify(row.getId(),1L,2L).getStatus());
        verify(nexus).dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(false),any());
        verify(nexus,never()).dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any());
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(false),any())).thenReturn(true);
        assertEquals("COMPLETED",service.verify(row.getId(),1L,2L).getStatus());
    }
    @Test void checksumRepairRejectsChangedOriginalAndNeverPublishesSidecars() throws Exception {
        var row=mavenRow("COMPLETED",false);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any()))
            .thenReturn(Map.of("sha256","f".repeat(64),"sha1","1".repeat(40),"md5","2".repeat(32)));
        service.repairChecksums(row.getId(),1L,2L);waitFor(row.getId(),"VERIFY_REQUIRED");
        verify(nexus,never()).dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),anyBoolean(),any());
    }
    @Test void checksumRepairCannotOverlapOtherUncertainWorkAndRejectedWorkerRestoresState() throws Exception {
        var row=mavenRow("COMPLETED",false);
        when(repository.active()).thenReturn(1L);
        assertThrows(Exception.class,()->service.repairChecksums(row.getId(),1L,2L));
        assertEquals("COMPLETED",service.require(row.getId(),1L,2L).getStatus());
        when(repository.active()).thenReturn(0L);
        service.stop();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,()->service.repairChecksums(row.getId(),1L,2L));
        assertEquals("COMPLETED",service.require(row.getId(),1L,2L).getStatus());
        verify(nexus,never()).dependencyChecksums(anyString(),anyString(),anyString(),any());
    }
    @Test void checksumWorkerInitialDbReadFailureCleansHandlesAndAllowsExplicitRetry() throws Exception {
        var row=mavenRow("COMPLETED",false);
        var failOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repository.find(row.getId())).thenAnswer(i -> {
            if(Thread.currentThread().getName().equals("nexus-dependency-publisher") && failOnce.compareAndSet(true,false))
                throw new IllegalStateException("DB temporarily unavailable");
            return copy(rows.get(row.getId()));
        });
        service.repairChecksums(row.getId(),1L,2L);waitFor(row.getId(),"VERIFY_REQUIRED");
        assertWorkerHandlesCleared();
        verify(nexus,never()).dependencyChecksums(anyString(),anyString(),anyString(),any());
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any())).thenReturn(true);
        service.repairChecksums(row.getId(),1L,2L);waitFor(row.getId(),"COMPLETED");
        assertWorkerHandlesCleared();
    }
    @Test void checksumWorkerDbReadAndRecoveryWriteFailureLeaveLeaseRecoverableWithoutDeadThread() throws Exception {
        var row=mavenRow("COMPLETED",false);
        var failed=new java.util.concurrent.CountDownLatch(1);
        var failRead=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repository.find(row.getId())).thenAnswer(i -> {
            if(Thread.currentThread().getName().equals("nexus-dependency-publisher") && failRead.compareAndSet(true,false))
                throw new IllegalStateException("DB temporarily unavailable");
            return copy(rows.get(row.getId()));
        });
        doAnswer(i -> {
            failed.countDown();throw new IllegalStateException("DB still unavailable");
        }).when(repository).transition(eq(row.getId()),eq("PUBLISHING"),eq("VERIFY_REQUIRED"),anyString());
        service.repairChecksums(row.getId(),1L,2L);
        assertTrue(failed.await(5,java.util.concurrent.TimeUnit.SECONDS));
        assertWorkerHandlesCleared();
        assertEquals("PUBLISHING",service.require(row.getId(),1L,2L).getStatus());
        // Model the repository's existing ten-minute PUBLISHING lease recovery after DB returns.
        doAnswer(i -> {rows.get(row.getId()).setStatus("VERIFY_REQUIRED");return null;}).when(repository).expireLeases();
        service.recent(1L,2L);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any())).thenReturn(true);
        service.repairChecksums(row.getId(),1L,2L);waitFor(row.getId(),"COMPLETED");
        assertWorkerHandlesCleared();
    }
    void assertWorkerHandlesCleared() throws Exception {
        for(int attempt=0;attempt<100;attempt++) {
            var tasks=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"tasks");
            var threads=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"runningThreads");
            var cancellations=(Set<?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"cancelRequested");
            if(tasks.isEmpty() && threads.isEmpty() && cancellations.isEmpty())return;
            Thread.sleep(10);
        }
        fail("finished worker must not retain task, thread or cancellation handles");
    }
    @Test void failedSidecarPublicationKeepsOriginalAndRequiresExplicitRepair() throws Exception {
        var row=mavenRow("READY",true);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any()))
            .thenThrow(new java.io.IOException("lost checksum response")).thenReturn(true);
        service.publish(row.getId(),1L,2L);waitFor(row.getId(),"VERIFY_REQUIRED");
        assertTrue(Files.exists(directory.resolve(row.getId()+".part")));
        assertThrows(Exception.class,()->service.publish(row.getId(),1L,2L));
        service.repairChecksums(row.getId(),1L,2L);waitFor(row.getId(),"COMPLETED");
        verify(nexus,times(1)).publishDependency(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void activeLegacyChecksumRepairCanBeCancelledWithoutPartFile() throws Exception {
        var row=mavenRow("COMPLETED",false);
        var started=new java.util.concurrent.CountDownLatch(1);
        when(nexus.dependencyChecksums(anyString(),anyString(),anyString(),any())).thenReturn(hashes(row));
        when(nexus.dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),eq(true),any())).thenAnswer(i -> {
            started.countDown();new java.util.concurrent.CountDownLatch(1).await();return true;
        });
        service.repairChecksums(row.getId(),1L,2L);
        assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
        service.cancel(row.getId(),1L,2L);waitFor(row.getId(),"VERIFY_REQUIRED");
        verify(nexus,never()).publishDependency(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void rawAndChecksumFilesNeverGenerateRecursiveSidecars() throws Exception {
        var raw=create(1);
        assertFalse(NexusDependencyUploadService.mavenPayload(raw));
        raw.setRepositoryFormat("maven2");
        for(String suffix:List.of(".sha1",".md5",".sha256",".sha512",".asc")) {
            raw.setTargetPath("com/acme/lib-1.jar"+suffix);
            assertFalse(NexusDependencyUploadService.mavenPayload(raw));
            var stored=rows.get(raw.getId());stored.setRepositoryFormat("maven2");stored.setTargetPath(raw.getTargetPath());
            assertThrows(Exception.class,()->service.repairChecksums(raw.getId(),1L,2L));
        }
        verify(nexus,never()).dependencyChecksumFiles(anyString(),anyString(),anyString(),anyMap(),anyBoolean(),any());
    }
    void waitFor(String id,String status)throws Exception {
        for(int i=0;i<300;i++){
            if(status.equals(service.require(id,1L,2L).getStatus())){Thread.sleep(20);return;}
            Thread.sleep(10);
        }
        fail("expected "+status+" but was "+service.require(id,1L,2L).getStatus());
    }
}
