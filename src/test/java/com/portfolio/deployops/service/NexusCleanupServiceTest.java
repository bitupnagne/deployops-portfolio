package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.NexusCleanupRequest;
import com.portfolio.deployops.domain.NexusCleanupStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.NexusCleanupRequestRepository;
import com.portfolio.deployops.repository.ProjectImageHistoryRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class NexusCleanupServiceTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private final NexusCleanupRequestRepository repository = mock(NexusCleanupRequestRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectImageHistoryRepository historyRepository = mock(ProjectImageHistoryRepository.class);
    private final DeployRequestRepository deployRequestRepository = mock(DeployRequestRepository.class);
    private final com.portfolio.deployops.repository.ExecutionGuardRepository executionGuardRepository = mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class);
    private final NexusMetadataService nexus = mock(NexusMetadataService.class);
    private final PlatformTransactionManager transactionManager = transactionManager();
    private final NexusCleanupService service = new NexusCleanupService(
        repository, projectRepository, historyRepository, deployRequestRepository, executionGuardRepository, nexus,
        transactionManager);

    @Test
    void requestPinsResolvedDigestWithoutDeleting() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any())).thenReturn(Map.of(
            "repositoryPath", "team/app", "tag", "old", "digest", DIGEST));
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(List.of());

        service.create(1L, "old", "보존기간 경과", 10L);

        ArgumentCaptor<NexusCleanupRequest> saved = ArgumentCaptor.forClass(NexusCleanupRequest.class);
        verify(repository).insert(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getImageDigest()).isEqualTo(DIGEST);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getStatus()).isEqualTo(NexusCleanupStatus.PENDING);
        verify(nexus, never()).deleteManifest(any(), any());
    }

    @Test
    void protectedDeploymentDigestCannotBeRequested() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any())).thenReturn(Map.of(
            "repositoryPath", "team/app", "tag", "current", "digest", DIGEST));
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(List.of(DIGEST));

        assertThatThrownBy(() -> service.create(1L, "current", "정리", 10L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("정리할 수 없습니다");
        verify(repository, never()).insert(any());
    }

    @Test
    void digestAtEndOfRollbackHistoryWindowCannotBeRequestedForCleanup() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any())).thenReturn(Map.of(
            "repositoryPath", "team/app", "tag", "oldest-visible", "digest", DIGEST));
        List<String> protectedDigests = new ArrayList<>(
            Collections.nCopies(ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT - 1,
                "sha256:" + "b".repeat(64)));
        protectedDigests.add(DIGEST);
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(protectedDigests);

        assertThatThrownBy(() -> service.create(1L, "oldest-visible", "정리", 10L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("정리할 수 없습니다");

        verify(historyRepository).findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT);
        verify(repository, never()).insert(any());
    }

    @Test
    void approvalStopsWhenTagMovedToAnotherDigest() throws Exception {
        NexusCleanupRequest request = request();
        when(repository.findById(7L)).thenReturn(Optional.of(request));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(nexus.resolveManifest(any(), any())).thenReturn(Map.of("repositoryPath", "team/app", "digest", "sha256:" + "b".repeat(64)));

        assertThatThrownBy(() -> service.approveAndDelete(7L, 20L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("태그가 다른 이미지");
        verify(repository, never()).acquireApproval(any(), any());
        verify(nexus, never()).deleteManifest(any(), any());
    }

    @Test
    void differentApproverDeletesExactReviewedDigest() throws Exception {
        NexusCleanupRequest pending = request();
        NexusCleanupRequest deleting = request();
        deleting.setStatus(NexusCleanupStatus.DELETING);
        NexusCleanupRequest complete = request();
        complete.setStatus(NexusCleanupStatus.COMPLETED);
        when(repository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(deleting), Optional.of(complete));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(nexus.resolveManifest(any(), any()))
            .thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST))
            .thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST))
            .thenThrow(new IllegalArgumentException("선택한 이미지 태그를 Nexus에서 찾을 수 없습니다."));
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(List.of());
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        when(nexus.deleteManifestTag(any(), org.mockito.ArgumentMatchers.eq("old"),
            org.mockito.ArgumentMatchers.eq(DIGEST)))
            .thenReturn(NexusMetadataService.TagDeleteResult.DELETED);

        service.approveAndDelete(7L, 20L);

        verify(nexus).deleteManifest(any(), org.mockito.ArgumentMatchers.eq(DIGEST));
        verify(nexus).deleteManifestTag(any(), org.mockito.ArgumentMatchers.eq("old"),
            org.mockito.ArgumentMatchers.eq(DIGEST));
        verify(repository).finish(7L, "COMPLETED", "Nexus digest와 승인 태그를 삭제했습니다.");
    }

    @Test
    void approvalRejectsRepositoryIdentityDrift() throws Exception {
        NexusCleanupRequest request = request();
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(request));
        Project changed = project();
        changed.setImageRepository("registry:5000/team/other");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(changed));

        assertThatThrownBy(() -> service.approveAndDelete(7L, 20L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("저장소가 변경");
        verify(nexus, never()).deleteManifest(any(), any());
    }

    @Test
    void ambiguousDeleteResultIsCommittedAsReconciliationInsteadOfThrown() throws Exception {
        NexusCleanupRequest pending = request();
        NexusCleanupRequest deleting = request();
        deleting.setStatus(NexusCleanupStatus.DELETING);
        NexusCleanupRequest reconciling = request();
        reconciling.setStatus(NexusCleanupStatus.RECONCILING);
        reconciling.setResultMessage("삭제 결과를 확정하지 못했습니다.");
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(deleting), Optional.of(reconciling));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any())).thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST));
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(List.of());
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new java.io.IOException("connection reset"))
            .when(nexus).deleteManifest(any(), org.mockito.ArgumentMatchers.eq(DIGEST));

        org.assertj.core.api.Assertions.assertThat(service.approveAndDelete(7L, 20L).getStatus())
            .isEqualTo(NexusCleanupStatus.RECONCILING);
        verify(repository).finish(org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("RECONCILING"), org.mockito.ArgumentMatchers.contains("확정하지 못했습니다"));
    }

    @Test
    void sameDigestTagAfterDigestDeleteUsesVerifiedTagDeletion() throws Exception {
        NexusCleanupRequest pending = request();
        NexusCleanupRequest deleting = request();
        deleting.setStatus(NexusCleanupStatus.DELETING);
        NexusCleanupRequest complete = request();
        complete.setStatus(NexusCleanupStatus.COMPLETED);
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(deleting), Optional.of(complete));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any()))
            .thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST))
            .thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST));
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(List.of());
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        when(nexus.deleteManifestTag(any(), org.mockito.ArgumentMatchers.eq("old"),
            org.mockito.ArgumentMatchers.eq(DIGEST)))
            .thenReturn(NexusMetadataService.TagDeleteResult.DELETED);

        org.assertj.core.api.Assertions.assertThat(service.approveAndDelete(7L, 20L).getStatus())
            .isEqualTo(NexusCleanupStatus.COMPLETED);
        verify(nexus).deleteManifest(any(), org.mockito.ArgumentMatchers.eq(DIGEST));
        verify(nexus).deleteManifestTag(any(), org.mockito.ArgumentMatchers.eq("old"),
            org.mockito.ArgumentMatchers.eq(DIGEST));
    }

    @Test
    void movedTagAfterDigestDeleteIsPreservedAndReportedComplete() throws Exception {
        NexusCleanupRequest pending = request();
        NexusCleanupRequest deleting = request();
        deleting.setStatus(NexusCleanupStatus.DELETING);
        NexusCleanupRequest complete = request();
        complete.setStatus(NexusCleanupStatus.COMPLETED);
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(deleting), Optional.of(complete));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any()))
            .thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST));
        when(historyRepository.findProtectedDigests("registry:5000/team/app",
            ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).thenReturn(List.of());
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        when(nexus.deleteManifestTag(any(), org.mockito.ArgumentMatchers.eq("old"),
            org.mockito.ArgumentMatchers.eq(DIGEST)))
            .thenReturn(NexusMetadataService.TagDeleteResult.MOVED);

        org.assertj.core.api.Assertions.assertThat(service.approveAndDelete(7L, 20L).getStatus())
            .isEqualTo(NexusCleanupStatus.COMPLETED);
        verify(repository).finish(7L, "COMPLETED",
            "Nexus digest를 삭제했습니다. 태그는 새 digest로 이동해 보존했습니다.");
    }

    @Test
    void approvalBlocksWhileAnyProjectDeploymentIsInFlight() throws Exception {
        NexusCleanupRequest pending = request();
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project()));
        when(nexus.resolveManifest(any(), any())).thenReturn(Map.of("repositoryPath", "team/app", "digest", DIGEST));
        when(deployRequestRepository.countByStatusIn(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.approveAndDelete(7L, 20L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("진행 중인 배포");
        verify(repository, never()).acquireApproval(any(), any());
        verify(nexus, never()).deleteManifest(any(), any());
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return manager;
    }

    private Project project() {
        return Project.builder().id(1L).name("결제").imageRepository("registry:5000/team/app").build();
    }

    private NexusCleanupRequest request() {
        return NexusCleanupRequest.builder().id(7L).project(project())
            .requestedBy(User.builder().id(10L).name("요청자").build())
            .status(NexusCleanupStatus.PENDING).repositoryPath("team/app")
            .imageRepository("registry:5000/team/app")
            .imageTag("old").imageDigest(DIGEST).reason("정리").build();
    }
}
