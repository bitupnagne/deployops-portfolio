package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.NexusRestoreRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class NexusRestoreReconcilerTest {
    @Test void publicationFailureRetainsDurableSubmittedStateAndNeverReplays() throws Exception {
        var service = mock(NexusRestoreService.class);
        var gateway = mock(NexusRestoreJobGateway.class);
        var request = new NexusRestoreRequest();
        request.setId(1L);
        request.setBackupRequestId(2L);
        request.setManifestSha256("a".repeat(64));
        when(service.unfinished()).thenReturn(List.of(request));
        when(service.submit(1L)).thenAnswer(invocation -> { request.setSubmitted(true); return true; });
        doThrow(new java.io.IOException("SAFE_FAKE_FAILURE")).when(gateway).submit(1L, 2L, "a".repeat(64));
        var reconciler = new NexusRestoreReconciler(service, gateway);
        reconciler.poll();
        reconciler.poll();
        verify(gateway, times(1)).submit(1L, 2L, "a".repeat(64));
        verify(service).uncertain(1L);
    }
    @Test void readinessFlipAfterSubmissionUsesDefiniteNoPublicationPath() throws Exception {
        var service = mock(NexusRestoreService.class);
        var files = mock(NexusBackupJobGateway.class);
        var base = java.nio.file.Files.createTempDirectory("restore-ready-flip");
        try {
            var gateway = new NexusRestoreJobGateway(files, base.toString());
            var ready = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            ready.put("schema", 1).put("restoreReady", true);
            when(files.readRootFile(any())).thenReturn(ready);
            org.assertj.core.api.Assertions.assertThat(gateway.ready()).isTrue();
            var request = new NexusRestoreRequest();
            request.setId(1L);
            request.setBackupRequestId(2L);
            request.setManifestSha256("a".repeat(64));
            when(service.unfinished()).thenReturn(List.of(request));
            when(service.submit(1L)).thenAnswer(call -> {
                request.setSubmitted(true);
                ready.put("restoreReady", false);
                return true;
            });
            new NexusRestoreReconciler(service, gateway).poll();
            verify(service).publicationRejected(1L);
            verify(service, never()).uncertain(anyLong());
            verify(files, never()).publish(anyString(), any());
        } finally {
            java.nio.file.Files.delete(base);
        }
    }

}
