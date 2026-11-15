package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.ExecutionTrigger;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ReleaseVerificationEvidence;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.ExecutionGuardRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExecutionPolicyServiceTest {

    private DeployRequest retryRequest(long id, long target, DeployStatus status) {
        var server = com.portfolio.deployops.domain.Server.builder().id(target)
            .serverType(com.portfolio.deployops.domain.ServerType.WEB).build();
        var group = com.portfolio.deployops.domain.ServerGroup.builder()
            .environment(com.portfolio.deployops.domain.ServerEnvironment.PROD).servers(List.of(server)).build();
        return DeployRequest.builder().id(id).environment(DeployEnvironment.PROD).status(status)
            .project(Project.builder().id(3L).serverGroups(List.of(group)).build()).build();
    }

    private void prepareRetry(DeployRequest request) {
        request.setTargetAttemptTrackingStartedAt(LocalDateTime.of(2026, 10, 8, 2, 29));
        stubLockedRequest(request);
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(deploymentPlanSnapshotService.applySnapshot(request)).thenReturn(true);
        when(statusHistoryRepository.findFirstDeploymentStartedAt(request.getId()))
            .thenReturn(LocalDateTime.of(2026, 10, 8, 2, 30));
    }

    @Test
    void supersededRetryIsRejectedUnderSingletonLockBeforeCasAndHistory() {
        DeployRequest request = retryRequest(20L, 11L, DeployStatus.PROD_FAILED);
        prepareRetry(request);
        when(repository.countSupersedingTargetAttempts(20L, 3L, "PROD", List.of(11L),
            LocalDateTime.of(2026, 10, 8, 2, 30))).thenReturn(1L);
        assertThatThrownBy(() -> service.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING))
            .hasMessageContaining("이후 다른 배포가 실행된 대상");
        var order = org.mockito.Mockito.inOrder(executionGuardRepository, repository);
        order.verify(executionGuardRepository).lockSingleton();
        order.verify(repository).lockById(20L);
        order.verify(repository).findById(20L);
        order.verify(repository).countSupersedingTargetAttempts(20L, 3L, "PROD", List.of(11L),
            LocalDateTime.of(2026, 10, 8, 2, 30));
        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
        verify(statusHistoryRepository, never()).save(any());
    }

    @Test
    void nonsupersededPartialRetryCanStartWithOriginalPlan() {
        DeployRequest request = retryRequest(20L, 11L, DeployStatus.PROD_FAILED);
        prepareRetry(request);
        when(repository.compareAndSetExecutionStart(20L, DeployStatus.PROD_FAILED,
            DeployStatus.PROD_DEPLOYING, ExecutionTrigger.MANUAL)).thenReturn(1);
        assertThat(service.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING)).isTrue();
        verify(statusHistoryRepository).save(any());
    }

    @Test
    void retryWithoutOriginalExecutionTimestampFailsClosed() {
        DeployRequest request = retryRequest(20L, 11L, DeployStatus.PROD_FAILED);
        prepareRetry(request);
        when(statusHistoryRepository.findFirstDeploymentStartedAt(20L)).thenReturn(null);
        assertThatThrownBy(() -> service.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING))
            .hasMessageContaining("기존 배포 실행 시각");
        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
    }

    @Test
    void subsequentNormalExecutionCannotOverlapActiveRetryEvenWithConcurrencyAboveOne() {
        DeployRequest request = retryRequest(30L, 11L, DeployStatus.APPROVAL_COMPLETED);
        DeployRequest active = retryRequest(20L, 11L, DeployStatus.PROD_DEPLOYING);
        stubLockedRequest(request);
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(settings.get("max-concurrent", "1")).thenReturn("10");
        when(repository.findByProjectIdAndEnvironmentAndStatusInWithProject(org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq(DeployEnvironment.PROD), any())).thenReturn(List.of(active));
        when(deploymentPlanSnapshotService.applySnapshot(request)).thenReturn(true);
        when(deploymentPlanSnapshotService.applySnapshot(active)).thenReturn(true);
        assertThatThrownBy(() -> service.tryStart(request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING))
            .hasMessageContaining("같은 대상 서버");
        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
    }

    @Test
    void activeRequestOnUnrelatedTargetDoesNotBlockCurrentRetry() {
        DeployRequest request = retryRequest(20L, 11L, DeployStatus.PROD_FAILED);
        DeployRequest active = retryRequest(30L, 99L, DeployStatus.PROD_DEPLOYING);
        prepareRetry(request);
        when(repository.findByProjectIdAndEnvironmentAndStatusInWithProject(org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq(DeployEnvironment.PROD), any())).thenReturn(List.of(active));
        when(deploymentPlanSnapshotService.applySnapshot(active)).thenReturn(true);
        when(repository.compareAndSetExecutionStart(20L, DeployStatus.PROD_FAILED,
            DeployStatus.PROD_DEPLOYING, ExecutionTrigger.MANUAL)).thenReturn(1);
        assertThat(service.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING)).isTrue();
    }

    @Test
    void legacyOriginalOrRetrospectiveTrackingCannotRetry() {
        for (LocalDateTime tracking : new LocalDateTime[]{null, LocalDateTime.of(2026,10,8,2,31)}) {
            DeployRequest request = retryRequest(20L,11L,DeployStatus.PROD_FAILED);
            prepareRetry(request); request.setTargetAttemptTrackingStartedAt(tracking);
            assertThatThrownBy(() -> service.tryStart(request,DeployStatus.PROD_FAILED,DeployStatus.PROD_DEPLOYING))
                .hasMessageContaining("추적 이력을 보장할 수 없어");
        }
        verify(repository,never()).compareAndSetExecutionStart(any(),any(),any(),any());
    }

    @Test
    void laterUntrackedExecutionBlocksOverlapButNotUnrelatedTarget() {
        DeployRequest request = retryRequest(20L,11L,DeployStatus.PROD_FAILED);
        prepareRetry(request);
        DeployRequest oldCode = retryRequest(10L,11L,DeployStatus.PROD_FAILED);
        when(repository.findUntrackedExecutionsSince(20L,3L,DeployEnvironment.PROD,
            LocalDateTime.of(2026,10,8,2,30))).thenReturn(List.of(oldCode));
        when(deploymentPlanSnapshotService.applySnapshot(oldCode)).thenReturn(true);
        assertThatThrownBy(() -> service.tryStart(request,DeployStatus.PROD_FAILED,DeployStatus.PROD_DEPLOYING))
            .hasMessageContaining("추적되지 않은 다른 배포");
        verify(repository,never()).compareAndSetExecutionStart(any(),any(),any(),any());
        oldCode.getProject().getServerGroups().getFirst().getServers().getFirst().setId(99L);
        when(repository.compareAndSetExecutionStart(20L,DeployStatus.PROD_FAILED,
            DeployStatus.PROD_DEPLOYING,ExecutionTrigger.MANUAL)).thenReturn(1);
        assertThat(service.tryStart(request,DeployStatus.PROD_FAILED,DeployStatus.PROD_DEPLOYING)).isTrue();
    }

    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final DeployRequestRepository repository = mock(DeployRequestRepository.class);
    private final ExecutionGuardRepository executionGuardRepository = mock(ExecutionGuardRepository.class);
    private final StatusHistoryRepository statusHistoryRepository = mock(StatusHistoryRepository.class);
    private final DeploymentPlanSnapshotService deploymentPlanSnapshotService = mock(DeploymentPlanSnapshotService.class);
    private final ExecutionPolicyService service = new ExecutionPolicyService(
        settings, repository, executionGuardRepository, statusHistoryRepository,
        deploymentPlanSnapshotService);

    @Test
    void prodExecutionIsRejectedOutsideConfiguredWindow() {
        when(settings.get("restrict-time", "false")).thenReturn("true");
        when(settings.get("allowed-weekdays", "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY"))
            .thenReturn("MONDAY");
        when(settings.get("allow-start", "09:00")).thenReturn("09:00");
        when(settings.get("allow-end", "18:00")).thenReturn("18:00");
        DeployRequest request = DeployRequest.builder().environment(DeployEnvironment.PROD).build();

        assertThatThrownBy(() -> service.assertExecutionWindow(
            request,
            LocalDateTime.of(2026, 8, 18, 10, 0)
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void tryStartRejectsWhenConcurrentLimitIsReached() {
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(settings.get("restrict-time", "false")).thenReturn("false");
        when(settings.get("max-concurrent", "1")).thenReturn("1");
        when(repository.countByStatusIn(List.of(
            DeployStatus.TEST_BUILDING,
            DeployStatus.TEST_DEPLOYING,
            DeployStatus.PROD_DEPLOYING
        ))).thenReturn(1L);
        DeployRequest request = DeployRequest.builder().id(1L).environment(DeployEnvironment.TEST).build();
        stubLockedRequest(request);

        assertThatThrownBy(() -> service.tryStart(request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409");
    }

    @Test
    void tryStartAtomicallyTransitionsWhenPolicyAllowsIt() {
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(settings.get("max-concurrent", "1")).thenReturn("1");
        when(repository.countByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(0L);
        when(repository.compareAndSetExecutionStart(
            1L, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.MANUAL)).thenReturn(1);
        DeployRequest request = DeployRequest.builder().id(1L).environment(DeployEnvironment.TEST).build();
        stubLockedRequest(request);

        assertThat(service.tryStart(request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING)).isTrue();
        assertThat(request.getStatus()).isEqualTo(DeployStatus.TEST_BUILDING);
        assertThat(request.getExecutionTrigger()).isEqualTo(ExecutionTrigger.MANUAL);
        verify(statusHistoryRepository).save(any());
    }

    @Test
    void automaticStartPersistsAutomaticTriggerAndHistoryTogether() {
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        when(settings.get("max-concurrent", "1")).thenReturn("1");
        when(repository.countByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(0L);
        when(repository.compareAndSetExecutionStart(
            1L, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING, ExecutionTrigger.AUTO)).thenReturn(1);
        DeployRequest request = DeployRequest.builder()
            .id(1L)
            .environment(DeployEnvironment.PROD)
            .status(DeployStatus.APPROVAL_COMPLETED)
            .build();
        stubLockedRequest(request);

        assertThat(service.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING, ExecutionTrigger.AUTO)).isTrue();
        assertThat(request.getExecutionTrigger()).isEqualTo(ExecutionTrigger.AUTO);
        verify(repository).compareAndSetExecutionStart(
            1L, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING, ExecutionTrigger.AUTO);
        verify(statusHistoryRepository).save(any());
    }

    @Test
    void automaticStartConvertsPermanentPlanFailureBeforeCas() {
        when(executionGuardRepository.lockSingleton()).thenReturn(1);
        DeployRequest request = DeployRequest.builder()
            .id(2L)
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_REQUESTED)
            .build();
        stubLockedRequest(request);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "계획 변경"))
            .when(deploymentPlanSnapshotService).assertCurrentPlanMatches(request);

        assertThatThrownBy(() -> service.tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.AUTO))
            .isInstanceOf(AutomaticDispatchPlanBlockedException.class)
            .hasMessageContaining("계획 변경");

        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
    }

    @Test
    void tryStartFailsClosedWhenSingletonGuardIsMissing() {
        when(executionGuardRepository.lockSingleton()).thenReturn(null);
        DeployRequest request = DeployRequest.builder().id(1L).environment(DeployEnvironment.TEST).build();

        assertThatThrownBy(() -> service.tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.AUTO))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503");
        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
    }

    @Test
    void automationOptionsDefaultToOff() {
        when(settings.get("auto-approve-verified-promotion", "false")).thenReturn("false");
        when(settings.get("auto-dispatch-test", "false")).thenReturn("false");
        when(settings.get("auto-dispatch-prod", "false")).thenReturn("false");

        assertThat(service.autoApproveVerifiedPromotion()).isFalse();
        assertThat(service.autoDispatchTest()).isFalse();
        assertThat(service.autoDispatchProd()).isFalse();
    }

    @Test
    void settingsRejectAutoApprovalWithoutRequiredSafetyGates() {
        Map<String, String> values = validSettings();
        values.put("auto-approve-verified-promotion", "true");
        values.put("require-test-first", "false");

        assertThatThrownBy(() -> service.validateSettings(values))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("TEST 확인 필수");

        values.put("require-test-first", "true");
        values.put("require-approval", "false");
        assertThatThrownBy(() -> service.validateSettings(values))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("승인 필수");

        values.put("require-approval", "true");
        values.put("auto-approval-allowed-branches", "");
        assertThatThrownBy(() -> service.validateSettings(values))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("브랜치를 한 개 이상");
    }

    @Test
    void settingsRejectInvalidOrDuplicateAutoApprovalBranches() {
        Map<String, String> values = validSettings();
        values.put("auto-approval-allowed-branches", "main,feature/../escape");

        assertThatThrownBy(() -> service.validateSettings(values))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("브랜치 값이 올바르지 않습니다");

        values.put("auto-approval-allowed-branches", "main,main");
        assertThatThrownBy(() -> service.validateSettings(values))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("중복");
    }

    @Test
    void verifiedImmutableTestPromotionCanBeAutoApproved() {
        configureAutoApproval("main");
        DeployRequest source = verifiedTestSource();
        DeployRequest prod = promotedRequest(source);

        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isTrue();
    }

    @Test
    void apiShapedArtifactWithoutServerVerificationCannotBeAutoApproved() {
        configureAutoApproval("main");
        DeployRequest source = verifiedTestSource();
        DeployRequest prod = promotedRequest(source);
        ReleaseVerificationEvidence.clear(source);
        ReleaseVerificationEvidence.clear(prod);

        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isFalse();
    }

    @Test
    void autoApprovalFailsClosedWhenEvidenceOrArtifactDoesNotMatch() {
        configureAutoApproval("main");
        DeployRequest source = verifiedTestSource();
        DeployRequest prod = promotedRequest(source);

        source.setTestConfirmedBy(null);
        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isFalse();

        source = verifiedTestSource();
        prod = promotedRequest(source);
        prod.setImageDigest("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isFalse();

        prod = promotedRequest(source);
        prod.setBranch("release");
        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isFalse();

        prod = promotedRequest(source);
        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, true)).isFalse();
    }

    @Test
    void mutableOrUntraceableTagCannotBeAutoApproved() {
        configureAutoApproval("main");
        DeployRequest source = verifiedTestSource();
        DeployRequest prod = promotedRequest(source);
        source.setImageTag("latest");
        prod.setImageTag("latest");
        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isFalse();

        source.setImageTag("release-candidate");
        prod.setImageTag("release-candidate");
        assertThat(service.shouldAutoApproveVerifiedPromotion(prod, source, false)).isFalse();
    }

    @Test
    void prodExecutionIsRejectedInsideFreezeWindow() {
        when(settings.get("freeze-enabled", "false")).thenReturn("true");
        when(settings.get("freeze-weekdays", "SATURDAY,SUNDAY")).thenReturn("TUESDAY");
        when(settings.get("freeze-start", "00:00")).thenReturn("09:00");
        when(settings.get("freeze-end", "23:59")).thenReturn("18:00");
        when(settings.get("freeze-reason", "운영 금지시간")).thenReturn("정기 점검");
        DeployRequest request = DeployRequest.builder().environment(DeployEnvironment.PROD).build();

        assertThatThrownBy(() -> service.assertExecutionWindow(request, LocalDateTime.of(2026, 8, 18, 10, 0)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("정기 점검");
    }

    private Map<String, String> validSettings() {
        return new java.util.HashMap<>(Map.ofEntries(
            Map.entry("allow-start", "09:00"),
            Map.entry("allow-end", "18:00"),
            Map.entry("max-concurrent", "1"),
            Map.entry("allowed-weekdays", "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY"),
            Map.entry("freeze-start", "00:00"),
            Map.entry("freeze-end", "23:59"),
            Map.entry("freeze-weekdays", "SATURDAY,SUNDAY"),
            Map.entry("require-test-first", "true"),
            Map.entry("require-approval", "true"),
            Map.entry("auto-approve-verified-promotion", "true"),
            Map.entry("auto-dispatch-test", "false"),
            Map.entry("auto-dispatch-prod", "false"),
            Map.entry("restrict-time", "false"),
            Map.entry("freeze-enabled", "false"),
            Map.entry("auto-approval-allowed-branches", "main")
        ));
    }

    private void configureAutoApproval(String branches) {
        when(settings.get("auto-approve-verified-promotion", "false")).thenReturn("true");
        when(settings.get("require-approval", "true")).thenReturn("true");
        when(settings.get("require-test-first", "true")).thenReturn("true");
        when(settings.get("auto-approval-allowed-branches", "")).thenReturn(branches);
    }

    private DeployRequest verifiedTestSource() {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        DeployRequest request = DeployRequest.builder()
            .id(50L)
            .project(Project.builder().id(1L).build())
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_CONFIRMED)
            .branch("main")
            .imageTag("release-01234567-77")
            .imageDigest(digest)
            .commitSha(commit)
            .gitlabPipelineId(77L)
            .testConfirmedBy(User.builder().id(9L).active(true).build())
            .testConfirmedAt(LocalDateTime.of(2026, 9, 14, 10, 0))
            .build();
        ReleaseVerificationEvidence.markVerifiedCiArtifact(
            request, 701L, "registry/app", LocalDateTime.of(2026, 9, 14, 9, 0));
        return request;
    }

    private DeployRequest promotedRequest(DeployRequest source) {
        DeployRequest request = DeployRequest.builder()
            .id(51L)
            .project(Project.builder().id(1L).build())
            .environment(DeployEnvironment.PROD)
            .status(DeployStatus.APPROVAL_PENDING)
            .branch(source.getBranch())
            .imageTag(source.getImageTag())
            .imageDigest(source.getImageDigest())
            .commitSha(source.getCommitSha())
            .gitlabPipelineId(source.getGitlabPipelineId())
            .promotedFromRequest(source)
            .build();
        ReleaseVerificationEvidence.copyVerifiedFrom(source, request);
        return request;
    }

    @Test
    void verifiedZeroTargetSnapshotBlocksManualAndAutoBeforeCasOrHistory() {
        for (ExecutionTrigger trigger : List.of(ExecutionTrigger.MANUAL, ExecutionTrigger.AUTO)) {
            DeployRequest request = retryRequest(700L, 701L, DeployStatus.APPROVAL_COMPLETED);
            stubLockedRequest(request);
            request.getProject().setServerGroups(List.of());
            when(executionGuardRepository.lockSingleton()).thenReturn(1);
            assertThatThrownBy(() -> service.tryStart(request, DeployStatus.APPROVAL_COMPLETED,
                    DeployStatus.PROD_DEPLOYING, trigger))
                .hasMessageContaining("대상 서버가 없습니다");
        }
        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
        verify(statusHistoryRepository, never()).save(any());
    }

    private void stubLockedRequest(DeployRequest request) {
        // Existing policy happy paths now carry actual verified snapshot target membership.
        if (request.getProject() == null) {
            var group = com.portfolio.deployops.domain.ServerGroup.builder()
                .environment(request.getEnvironment() == DeployEnvironment.PROD
                    ? com.portfolio.deployops.domain.ServerEnvironment.PROD
                    : com.portfolio.deployops.domain.ServerEnvironment.TEST)
                .servers(List.of(com.portfolio.deployops.domain.Server.builder().id(901L)
                    .serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build();
            request.setProject(Project.builder().id(3L).serverGroups(List.of(group)).build());
        }
        when(deploymentPlanSnapshotService.applySnapshot(request)).thenReturn(true);
        when(repository.lockById(request.getId())).thenReturn(request.getId());
        when(repository.findById(request.getId())).thenReturn(java.util.Optional.of(request));
    }
}
