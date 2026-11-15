package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import org.junit.jupiter.api.Test;

class OperatorExecutionServiceTest {

    @Test
    void testImageRetrySkipsCiAndCompletesOnlyRemainingTargetPath() {
        DeployRequest request = runningRequest(DeployEnvironment.TEST, DeployStatus.TEST_DEPLOYING);
        when(deployRequestService.findById(3L)).thenReturn(request);
        when(remoteDeployService.executeRetry(eq(request), any())).thenReturn(DeployStatus.TEST_DEPLOYED);
        when(deployRequestService.tryTransition(3L, DeployStatus.TEST_DEPLOYING, DeployStatus.TEST_DEPLOYED)).thenReturn(true);
        service.retryRemoteDeployment(3L, "operator", "127.0.0.1", "OPERATOR");
        org.mockito.Mockito.verifyNoInteractions(buildOrchestrationService);
        verify(remoteDeployService, never()).execute(any(), any());
        verify(remoteDeployService).executeRetry(eq(request), any());
        verify(deployLogService).sendStatus(3L, "TEST_DEPLOYED");
    }

    private final DeployRequestService deployRequestService = mock(DeployRequestService.class);
    private final RemoteDeployService remoteDeployService = mock(RemoteDeployService.class);
    private final BuildOrchestrationService buildOrchestrationService = mock(BuildOrchestrationService.class);
    private final DeployLogService deployLogService = mock(DeployLogService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OperatorExecutionService service = new OperatorExecutionService(
        deployRequestService,
        remoteDeployService,
        buildOrchestrationService,
        deployLogService,
        auditLogService,
        mock(ManualServerExecutionService.class),
        new SecretMaskingService()
    );

    @Test
    void rollbackSuccessAlwaysResolvesToRolledBack() {
        DeployRequest request = DeployRequest.builder()
            .id(1L)
            .reason(DeployRequestService.ROLLBACK_REASON_PREFIX + "[history:7] restore")
            .build();
        when(deployRequestService.isRollbackRequest(request)).thenReturn(true);

        assertThat(service.resolveSuccessfulStatus(request, DeployStatus.TEST_DEPLOYED))
            .isEqualTo(DeployStatus.ROLLED_BACK);
        assertThat(service.resolveSuccessfulStatus(request, DeployStatus.COMPLETED))
            .isEqualTo(DeployStatus.ROLLED_BACK);
    }

    @Test
    void nonRollbackKeepsDefaultStatus() {
        DeployRequest request = DeployRequest.builder().id(2L).reason("normal deploy").build();
        when(deployRequestService.isRollbackRequest(request)).thenReturn(false);

        assertThat(service.resolveSuccessfulStatus(request, DeployStatus.COMPLETED))
            .isEqualTo(DeployStatus.COMPLETED);
    }

    @Test
    void successfulProdCompletionUsesExpectedStateCas() {
        DeployRequest request = runningRequest(DeployEnvironment.PROD, DeployStatus.PROD_DEPLOYING);
        when(deployRequestService.findById(3L)).thenReturn(request);
        when(remoteDeployService.execute(eq(request), any())).thenReturn(DeployStatus.COMPLETED);
        when(deployRequestService.tryTransition(
            3L, DeployStatus.PROD_DEPLOYING, DeployStatus.COMPLETED)).thenReturn(true);

        service.executeRemoteDeployment(3L, "operator", "127.0.0.1", "OPERATOR");

        verify(deployRequestService).tryTransition(
            3L, DeployStatus.PROD_DEPLOYING, DeployStatus.COMPLETED);
        verify(deployRequestService, never()).updateStatus(any(), any());
        verify(deployLogService).sendStatus(3L, DeployStatus.COMPLETED.name());
    }

    @Test
    void failedExecutionCannotOverwriteAConcurrentlyChangedState() {
        DeployRequest request = runningRequest(DeployEnvironment.PROD, DeployStatus.PROD_DEPLOYING);
        when(deployRequestService.findById(4L)).thenReturn(request);
        doThrow(new RemoteExecutionException("remote failed"))
            .when(remoteDeployService).execute(eq(request), any());
        when(deployRequestService.tryTransitionToFailure(
            4L, DeployStatus.PROD_DEPLOYING, DeployStatus.PROD_FAILED, "remote failed")).thenReturn(false);

        service.executeRemoteDeployment(4L, "operator", "127.0.0.1", "OPERATOR");

        verify(deployRequestService).tryTransitionToFailure(
            4L, DeployStatus.PROD_DEPLOYING, DeployStatus.PROD_FAILED, "remote failed");
        verify(deployRequestService, never()).updateStatus(any(), any());
        verify(deployLogService, never()).sendStatus(4L, DeployStatus.PROD_FAILED.name());
        verify(deployLogService).sendLog(
            4L, "[상태 보호] 실행 상태가 이미 변경되어 실패 상태로 덮어쓰지 않았습니다.");
    }

    @Test
    void failureReasonIsPersistedAndSecretsAreMasked() {
        DeployRequest request = runningRequest(DeployEnvironment.TEST, DeployStatus.TEST_BUILDING);
        when(deployRequestService.findById(5L)).thenReturn(request);
        doThrow(new RemoteExecutionException("registry login failed: password=do-not-store"))
            .when(buildOrchestrationService).ensureBuildArtifact(eq(request), eq("OPERATOR"), any());
        when(deployRequestService.tryTransitionToFailure(
            5L, DeployStatus.TEST_BUILDING, DeployStatus.TEST_FAILED,
            "registry login failed: password=***")).thenReturn(true);

        service.executeRemoteDeployment(5L, "operator", "127.0.0.1", "OPERATOR");

        verify(deployRequestService).tryTransitionToFailure(
            5L, DeployStatus.TEST_BUILDING, DeployStatus.TEST_FAILED,
            "registry login failed: password=***");
        verify(deployLogService).sendLog(5L, "[오류] registry login failed: password=***");
        verify(deployLogService).sendStatus(5L, DeployStatus.TEST_FAILED.name());
    }

    @Test
    void testBuildPersistsOnlyServerVerifiedArtifactBeforeDeploying() {
        DeployRequest request = runningRequest(DeployEnvironment.TEST, DeployStatus.TEST_BUILDING);
        GitlabBuildArtifactMetadata artifact = new GitlabBuildArtifactMetadata(
            701L, 77L, "registry/app", "release-01234567-77",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0123456789abcdef0123456789abcdef01234567");
        when(deployRequestService.findById(5L)).thenReturn(request);
        when(buildOrchestrationService.ensureBuildArtifact(eq(request), eq("OPERATOR"), any()))
            .thenReturn(artifact);
        when(deployRequestService.updateVerifiedBuildMetadata(5L, artifact)).thenReturn(request);
        when(deployRequestService.tryTransition(
            5L, DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING)).thenReturn(true);
        when(remoteDeployService.execute(eq(request), any())).thenReturn(DeployStatus.TEST_DEPLOYED);
        when(deployRequestService.tryTransition(
            5L, DeployStatus.TEST_DEPLOYING, DeployStatus.TEST_DEPLOYED)).thenReturn(true);

        service.executeRemoteDeployment(5L, "operator", "127.0.0.1", "OPERATOR");

        verify(deployRequestService).updateVerifiedBuildMetadata(5L, artifact);
        verify(deployRequestService).tryTransition(
            5L, DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING);
        verify(deployRequestService).tryTransition(
            5L, DeployStatus.TEST_DEPLOYING, DeployStatus.TEST_DEPLOYED);
    }

    private DeployRequest runningRequest(DeployEnvironment environment, DeployStatus status) {
        return DeployRequest.builder()
            .id(environment == DeployEnvironment.PROD ? 3L : 5L)
            .reqNo("REQ-" + status)
            .project(Project.builder().id(1L).name("app").build())
            .environment(environment)
            .status(status)
            .build();
    }
}
