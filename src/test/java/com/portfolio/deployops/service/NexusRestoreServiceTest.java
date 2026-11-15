package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.NexusRestoreConfirmation;
import com.portfolio.deployops.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NexusRestoreServiceTest {
    final NexusRestoreRequestRepository repo = mock(NexusRestoreRequestRepository.class);
    final NexusBackupRequestRepository backups = mock(NexusBackupRequestRepository.class);
    final ExecutionGuardRepository guard = mock(ExecutionGuardRepository.class, CALLS_REAL_METHODS);
    final DeployRequestRepository deploy = mock(DeployRequestRepository.class);
    final NexusRestoreJobGateway gateway = mock(NexusRestoreJobGateway.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final NexusRestoreService service = new NexusRestoreService(repo, backups, guard, deploy, gateway, audit);
    NexusRestoreRequest request;

    @BeforeEach void setup() {
        request = new NexusRestoreRequest();
        request.setId(1L);
        request.setBackupRequestId(2L);
        request.setArtifactId("nexus-backup-2");
        request.setManifestSha256("a".repeat(64));
        request.setRequestedById(10L);
        request.setStatus("PENDING");
        when(repo.find(1L)).thenReturn(Optional.of(request));
        var backup = new NexusBackupRequest();
        backup.setId(2L);
        backup.setStatus("COMPLETED");
        backup.setArtifactId("nexus-backup-2");
        backup.setManifestSha256("a".repeat(64));
        when(backups.findById(2L)).thenReturn(Optional.of(backup));
        when(gateway.ready()).thenReturn(true);
        when(guard.lockSingleton()).thenReturn(1);
        when(guard.nexusMaintenanceOwner()).thenReturn(null);when(guard.nexusPromotionOwner()).thenReturn(null);
        when(guard.nexusRestoreOwner()).thenReturn(null);
        when(repo.approve(1L, 20L)).thenReturn(1);
        when(guard.reserveNexusRestore(1L)).thenReturn(1);
    }

    @Test void distinctApprovalCasReservesWithoutPublishing() {
        service.approve(1L, 20L);
        verify(guard).reserveNexusRestore(1L);
        verifyNoInteractionsDuringApproval();
    }

    private void verifyNoInteractionsDuringApproval() {
        try { verify(gateway, never()).submit(anyLong(), anyLong(), anyString()); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    @Test void selfApprovalAndCasLoserNeverReserve() {
        assertThatThrownBy(() -> service.approve(1L, 10L)).hasMessageContaining("달라야");
        when(repo.approve(1L, 20L)).thenReturn(0);
        assertThatThrownBy(() -> service.approve(1L, 20L)).hasMessageContaining("이미 처리");
        verify(guard, never()).reserveNexusRestore(anyLong());
    }

    @Test void maintenanceOrAnotherRestoreBlocksApproval() {
        when(guard.nexusMaintenanceOwner()).thenReturn(3L);
        assertThatThrownBy(() -> service.approve(1L, 20L)).hasMessageContaining("유지보수");
        when(guard.nexusMaintenanceOwner()).thenReturn(null);
        when(guard.nexusRestoreOwner()).thenReturn(4L);
        assertThatThrownBy(() -> service.approve(1L, 20L)).hasMessageContaining("유지보수");
        verify(repo, never()).approve(anyLong(), anyLong());
    }

    @Test void recoveryRequiredNeverReleasesReservation() {
        when(guard.nexusRestoreOwner()).thenReturn(1L);
        when(repo.result(any())).thenReturn(1);
        service.accept(1L, new NexusRestoreJobGateway.SafeResult(2, "a".repeat(64),
            "RECOVERY_REQUIRED", "RESTORING", "RESULT_UNCERTAIN", null, null, null, null, null,
            true, true, "[]", null, LocalDateTime.now()));
        verify(guard, never()).releaseNexusRestore(anyLong());
    }

    NexusRestoreConfirmation manual(List<NexusRestoreConfirmation.Artifact> artifacts) {
        request.setStatus("HEALTH_READY");
        request.setMutationUncertain(false);
        request.setOriginalProjectionPreserved(true);
        request.setCloneContainerId("b".repeat(64));
        when(repo.confirm(any())).thenReturn(1);
        return new NexusRestoreConfirmation("b".repeat(64), "a".repeat(64),
            Set.of("LOGIN", "H2_LOCAL", "FILE_BLOB_LOCAL", "TASKS", "USERS_ROLES_REALMS", "REPOSITORIES"), artifacts);
    }

    @Test void legacyManualConfirmationHasNoAutomaticArtifactProofOrRuntimeCall() {
        request.setExpectedArtifacts("[]");
        service.confirm(1L, manual(List.of()), 20L);
        verify(repo).confirm(request);
        verify(audit).recordCurrentRequired(eq("Nexus 격리복원 운영자 확인"), anyString(), eq("1"),
            contains("자동 기능검증 성공 아님"), eq(AuditResult.SUCCESS));
        verify(guard, never()).releaseNexusRestore(anyLong());
        verifyNoInteractions(gateway);
    }

    @Test void legacyCannotAcceptInventedArtifactAndCorruptExpectedCannotConfirm() {
        request.setExpectedArtifacts("[]");
        var f = manual(List.of(new NexusRestoreConfirmation.Artifact("invented", "c".repeat(64))));
        assertThatThrownBy(() -> service.confirm(1L, f, 20L)).hasMessageContaining("미제공");
        request.setExpectedArtifacts("[{}]");
        assertThatThrownBy(() -> service.confirm(1L, manual(List.of()), 20L)).hasMessageContaining("기준");
        verify(repo, never()).confirm(any());
    }

    @Test void duplicateOrWrongDownloadAndCasLoserNeverClaimConfirmation() {
        request.setExpectedArtifacts("[{\"ref\":\"maven\",\"sha256\":\"" + "c".repeat(64)
            + "\",\"baselineReceiptSha256\":\"" + "d".repeat(64) + "\"}]");
        var wrong = manual(List.of(new NexusRestoreConfirmation.Artifact("maven", "e".repeat(64))));
        assertThatThrownBy(() -> service.confirm(1L, wrong, 20L)).hasMessageContaining("다릅니다");
        var correct = manual(List.of(new NexusRestoreConfirmation.Artifact("maven", "c".repeat(64))));
        when(repo.confirm(any())).thenReturn(0);
        assertThatThrownBy(() -> service.confirm(1L, correct, 20L)).hasMessageContaining("이미 확인");
        verifyNoInteractions(audit);
    }
    @Test void identicalHeldResultHasOneWriteAndOneRequiredAuditAcrossPolls() {
        when(guard.nexusRestoreOwner()).thenReturn(1L);
        when(repo.result(any())).thenReturn(1);
        var result = new NexusRestoreJobGateway.SafeResult(2, "a".repeat(64),
                "RECOVERY_REQUIRED", "RESTORING", "RESULT_UNCERTAIN", null, null, null, null, null,
                true, true, "[]", null, LocalDateTime.of(2026, 10, 11, 1, 2, 3));
        service.accept(1L, result);
        service.accept(1L, result);
        service.accept(1L, result);
        verify(repo, times(1)).result(request);
        verify(audit, times(1)).recordCurrentRequired(anyString(), anyString(), eq("1"), anyString(), eq(AuditResult.FAIL));
        verify(guard, never()).releaseNexusRestore(anyLong());
    }

    @Test void definiteNoPublicationFailsAndReleasesOnlyAfterCasAndRequiredAudit() {
        when(guard.nexusRestoreOwner()).thenReturn(1L);
        when(repo.publicationRejected(1L)).thenReturn(1);
        when(guard.releaseNexusRestore(1L)).thenReturn(1);
        service.publicationRejected(1L);
        var order = inOrder(repo, audit, guard);
        order.verify(repo).publicationRejected(1L);
        order.verify(audit).recordCurrentRequired(eq("Nexus 격리복원 게시 전 거부"), anyString(), eq("1"), anyString(), eq(AuditResult.FAIL));
        order.verify(guard).releaseNexusRestore(1L);
        verify(repo, never()).result(any());
    }

    @Test void noPublicationCasLoserOrAuditFailureCannotRelease() {
        when(guard.nexusRestoreOwner()).thenReturn(1L);
        when(repo.publicationRejected(1L)).thenReturn(0);
        assertThatThrownBy(() -> service.publicationRejected(1L)).hasMessageContaining("확정");
        when(repo.publicationRejected(1L)).thenReturn(1);
        doThrow(new IllegalStateException("SAFE_AUDIT_FAILURE")).when(audit)
                .recordCurrentRequired(anyString(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.publicationRejected(1L)).isInstanceOf(IllegalStateException.class);
        verify(guard, never()).releaseNexusRestore(anyLong());
    }

}
