package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.RemoteCommandLog;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DeployLogService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.DeploymentDutySeparationService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.ManualServerExecutionService;
import com.portfolio.deployops.service.OperatorExecutionService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.service.ArchitectureService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.RemoteDeployService;
import com.portfolio.deployops.service.RemoteCommandResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OperatorApiControllerTest {

    @Test
    void retryStartsFailedProdUsingSameRequestGuardAndNeverNormalExecution() {
        DeployRequest request = approvedProdRequest();
        request.setStatus(DeployStatus.PROD_FAILED);
        request.setDeployStrategy(DeployStrategy.SAFE);
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "a".repeat(64));
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(remoteDeployService.resolveRetryTargets(request)).thenReturn(List.of(Server.builder().id(20L).build()));
        when(executionPolicyService.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING)).thenReturn(true);
        controller.retry(1L, mock(SecurityUserDetails.class), mock(HttpServletRequest.class));
        var order = org.mockito.Mockito.inOrder(deployRequestService, executionPolicyService, operatorExecutionService);
        order.verify(deployRequestService).assertProductionRollbackProvenance(request);
        order.verify(deployRequestService).assertPlanUnchanged(request);
        order.verify(executionPolicyService).tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING);
        order.verify(deployRequestService).markExecuted(eq(1L), any());
        order.verify(operatorExecutionService).retryRemoteDeployment(eq(1L), any(), any(), any());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void retryLosingCasCannotScheduleSecondExecution() {
        DeployRequest request = approvedProdRequest();
        request.setStatus(DeployStatus.PROD_FAILED);
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "a".repeat(64));
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(remoteDeployService.resolveRetryTargets(request)).thenReturn(List.of(Server.builder().id(20L).build()));
        assertThatThrownBy(() -> controller.retry(1L, mock(SecurityUserDetails.class), mock(HttpServletRequest.class)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("다른 작업");
        verify(operatorExecutionService, never()).retryRemoteDeployment(any(), any(), any(), any());
        verify(deployRequestService, never()).markExecuted(any(), any());
    }

    @Test
    void retryPlanDriftCannotStartOrSchedule() {
        DeployRequest request = approvedProdRequest();
        request.setStatus(DeployStatus.PROD_FAILED);
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "a".repeat(64));
        when(deployRequestService.findById(1L)).thenReturn(request);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "계획 변경"))
            .when(deployRequestService).assertPlanUnchanged(request);
        assertThatThrownBy(() -> controller.retry(1L, mock(SecurityUserDetails.class), mock(HttpServletRequest.class)))
            .hasMessageContaining("계획 변경");
        verify(executionPolicyService, never()).tryStart(any(), any(), any());
        verify(operatorExecutionService, never()).retryRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void retryWithoutFixedImageOrFromApprovedStateIsRejected() {
        DeployRequest request = approvedProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        for (DeployStatus status : List.of(DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING, DeployStatus.PROD_FAILED)) {
            request.setStatus(status);
            assertThatThrownBy(() -> controller.retry(1L, mock(SecurityUserDetails.class), mock(HttpServletRequest.class)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("고정된 이미지");
        }
        verify(executionPolicyService, never()).tryStart(any(), any(), any());
    }

    private final DeployRequestService deployRequestService = mock(DeployRequestService.class);
    private final OperatorExecutionService operatorExecutionService = mock(OperatorExecutionService.class);
    private final RemoteDeployService remoteDeployService = mock(RemoteDeployService.class);
    private final ExecutionPolicyService executionPolicyService = mock(ExecutionPolicyService.class);
    private final ManualServerExecutionService manualServerExecutionService = mock(ManualServerExecutionService.class);
    private final DeployRequestRepository deployRequestRepository = mock(DeployRequestRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final RemoteCommandLogRepository remoteCommandLogRepository = mock(RemoteCommandLogRepository.class);
    private final ProjectPermissionService permissionService = mock(ProjectPermissionService.class);
    private final DeploymentDutySeparationService dutySeparationService = mock(DeploymentDutySeparationService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OperatorApiController controller = new OperatorApiController(
        deployRequestService,
        auditLogService,
        mock(DeployLogService.class),
        operatorExecutionService,
        remoteDeployService,
        permissionService,
        projectService,
        remoteCommandLogRepository,
        deployRequestRepository,
        mock(ArchitectureService.class),
        dutySeparationService,
        executionPolicyService,
        manualServerExecutionService
    );

    @Test
    void retryRetainsEnvironmentPermissionAndActorSeparationBeforeCas() {
        DeployRequest request = approvedProdRequest();
        request.setStatus(DeployStatus.PROD_FAILED);
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "a".repeat(64));
        when(deployRequestService.findById(1L)).thenReturn(request);
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "요청자 실행 불가"))
            .when(dutySeparationService).assertCanOperate(request, user);
        assertThatThrownBy(() -> controller.retry(1L, user, mock(HttpServletRequest.class)))
            .hasMessageContaining("요청자 실행 불가");
        verify(permissionService).assertPermission(user, 10L,
            com.portfolio.deployops.domain.ProjectPermissionType.PROD_EXECUTE, DeployEnvironment.PROD);
        verify(executionPolicyService, never()).tryStart(any(), any(), any());
        verify(operatorExecutionService, never()).retryRemoteDeployment(any(), any(), any(), any());
    }

    private DeployRequest deployingProdRequest() {
        return DeployRequest.builder()
            .id(1L)
            .reqNo("REQ-1")
            .project(Project.builder().id(10L).name("app").imageRepository("registry/app").build())
            .environment(DeployEnvironment.PROD)
            .deployStrategy(DeployStrategy.MANUAL)
            .status(DeployStatus.PROD_DEPLOYING)
            .build();
    }

    @Test
    void acceptedManualStartReportsExactEnvironmentRunningStatus() {
        for (DeployEnvironment environment : DeployEnvironment.values()) {
            DeployRequest request = deployingProdRequest();
            request.setEnvironment(environment);
            request.setStatus(environment == DeployEnvironment.TEST ? DeployStatus.TEST_REQUESTED : DeployStatus.APPROVAL_COMPLETED);
            DeployStatus expected = environment == DeployEnvironment.TEST ? DeployStatus.TEST_DEPLOYING : DeployStatus.PROD_DEPLOYING;
            when(deployRequestService.findById(1L)).thenReturn(request);
            when(remoteDeployService.resolveTargets(request)).thenReturn(List.of(Server.builder().id(20L).build()));
            when(executionPolicyService.tryStart(request, request.getStatus(), expected)).thenReturn(true);
            SecurityUserDetails user = mock(SecurityUserDetails.class);
            when(user.getId()).thenReturn(7L);
            var response = controller.executeServer(1L, 20L, user, mock(HttpServletRequest.class));
            assertThat(((Map<?, ?>) response.getData()).get("status")).isEqualTo(expected);
        }
    }

    @Test
    void outcomesKeepAttemptsAndFrozenTargetsForReadOnlyRequestViewer() {
        DeployRequest request = deployingProdRequest();
        SecurityUserDetails viewer = mock(SecurityUserDetails.class);
        when(deployRequestService.findById(1L, viewer)).thenReturn(request);
        when(remoteDeployService.resolveTargets(request)).thenReturn(List.of(
            Server.builder().id(20L).name("WEB1").build(), Server.builder().id(21L).name("WEB2").build()));
        when(remoteCommandLogRepository.findByDeployRequestId(1L)).thenReturn(List.of(
            RemoteCommandLog.builder().id(10L).serverId(20L).success(false).build(),
            RemoteCommandLog.builder().id(11L).serverId(20L).success(true).build(),
            RemoteCommandLog.builder().id(12L).serverId(21L).success(true).build()));
        Map<?, ?> body = (Map<?, ?>) controller.serverOutcomes(1L, viewer).getData();
        assertThat((List<?>) body.get("targets")).hasSize(2);
        assertThat((List<?>) body.get("servers")).hasSize(3);
        assertThat(((Map<?, ?>) ((List<?>) body.get("servers")).get(1)).get("attemptId")).isEqualTo(11L);
        assertThat(body.get("status")).isEqualTo(DeployStatus.PROD_DEPLOYING);
        verify(deployRequestService).findById(1L, viewer);
    }

    @Test
    void completeWithoutReasonIsRejected() {
        when(deployRequestService.findById(1L)).thenReturn(deployingProdRequest());

        assertThatThrownBy(() -> controller.complete(
            1L,
            Map.of("success", true),
            mock(SecurityUserDetails.class),
            mock(HttpServletRequest.class)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(deployRequestService, never()).tryTransition(any(), any(), any());
    }

    @Test
    void completeWithReasonUpdatesStatus() {
        DeployRequest request = deployingProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(operatorExecutionService.resolveSuccessfulStatus(request, DeployStatus.COMPLETED))
            .thenReturn(DeployStatus.COMPLETED);
        when(remoteDeployService.resolveTargets(request)).thenReturn(List.of(Server.builder().id(20L).build()));
        when(manualServerExecutionService.allSucceeded(1L, List.of(20L))).thenReturn(true);
        when(deployRequestService.tryTransition(1L, DeployStatus.PROD_DEPLOYING, DeployStatus.COMPLETED)).thenReturn(true);

        controller.complete(
            1L,
            Map.of("success", true, "reason", "전 서버 헬스체크 정상 확인"),
            mock(SecurityUserDetails.class),
            mock(HttpServletRequest.class)
        );

        verify(deployRequestService).tryTransition(1L, DeployStatus.PROD_DEPLOYING, DeployStatus.COMPLETED);
    }

    @Test
    void completeCannotMarkUnverifiedProdRollbackAsSuccessful() {
        DeployRequest request = deployingProdRequest();
        request.setReason("[ROLLBACK][history:77] restore");
        when(deployRequestService.findById(1L)).thenReturn(request);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CI 출처 미검증"))
            .when(deployRequestService).assertProductionRollbackProvenance(request);

        assertThatThrownBy(() -> controller.complete(1L,
            Map.of("success", true, "reason", "done"), mock(SecurityUserDetails.class),
            mock(HttpServletRequest.class)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처 미검증");
        verify(deployRequestService, never()).tryTransition(any(), any(), any());
    }

    private DeployRequest approvedProdRequest() {
        var group = ServerGroup.builder().environment(ServerEnvironment.PROD)
            .servers(List.of(Server.builder().id(20L).serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build();
        return DeployRequest.builder()
            .id(1L)
            .reqNo("REQ-1")
            .project(Project.builder().id(10L).name("app").imageRepository("registry/app").serverGroups(List.of(group)).build())
            .environment(DeployEnvironment.PROD)
            .status(DeployStatus.APPROVAL_COMPLETED)
            .build();
    }

    @Test
    void executeStartsOnlyWhenItWinsTheStatusTransition() {
        DeployRequest request = approvedProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING)).thenReturn(true);
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        when(user.getName()).thenReturn("operator");

        controller.execute(1L, user, mock(HttpServletRequest.class));

        verify(operatorExecutionService).executeRemoteDeployment(eq(1L), any(), any(), any());
    }

    @Test
    void executeRejectsUnverifiedProdRollbackBeforeStartingDeployment() {
        DeployRequest request = approvedProdRequest();
        request.setReason("[ROLLBACK][history:77] restore");
        when(deployRequestService.findById(1L)).thenReturn(request);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CI 출처 미검증"))
            .when(deployRequestService).assertProductionRollbackProvenance(request);

        assertThatThrownBy(() -> controller.execute(1L, mock(SecurityUserDetails.class),
            mock(HttpServletRequest.class)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처 미검증");
        verify(executionPolicyService, never()).tryStart(any(), any(), any());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void executeChecksVerifiedProdRollbackBeforeStartingDeployment() {
        DeployRequest request = approvedProdRequest();
        request.setReason("[ROLLBACK][history:77] restore");
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING)).thenReturn(true);

        controller.execute(1L, mock(SecurityUserDetails.class), mock(HttpServletRequest.class));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(deployRequestService, executionPolicyService);
        order.verify(deployRequestService).assertProductionRollbackProvenance(request);
        order.verify(executionPolicyService).tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING);
        verify(operatorExecutionService).executeRemoteDeployment(eq(1L), any(), any(), any());
        verify(auditLogService).record(any(), eq("PROD 배포 시작"), eq("DeployRequest"), eq("1"),
            any(), eq(com.portfolio.deployops.domain.AuditResult.SUCCESS), any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(),
            eq(com.portfolio.deployops.domain.AuditResult.FAIL), any());
    }

    @Test
    void executeIsRejectedWhenAnotherRequestAlreadyTookTheTransition() {
        // 같은 배포에 실행이 두 번 들어오면(더블클릭·두 사람 동시) 진 쪽은 409 로 떨어지고
        // 배포 스레드를 띄우지 않아야 한다 — 안 그러면 같은 서버에 배포가 두 번 뜬다.
        DeployRequest request = approvedProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING)).thenReturn(false);

        assertThatThrownBy(() -> controller.execute(
            1L, mock(SecurityUserDetails.class), mock(HttpServletRequest.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void serviceControlRejectsDirectApiCallWhileMatchingTargetIsDeploying() {
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        Server server = Server.builder().id(20L).environment(ServerEnvironment.TEST).build();
        Project project = Project.builder().id(10L).name("app")
            .serverGroups(List.of(ServerGroup.builder().environment(ServerEnvironment.TEST)
                .servers(List.of(server)).build()))
            .build();
        // Mirrors mapper-loaded requests: project scalar fields are present, but groups are not hydrated.
        Project unloadedProject = Project.builder().id(10L).name("app").build();
        DeployRequest active = DeployRequest.builder().id(88L).project(unloadedProject)
            .environment(DeployEnvironment.TEST).deployScope(com.portfolio.deployops.domain.DeployScope.ALL)
            .status(DeployStatus.TEST_DEPLOYING).build();
        when(projectService.findByIdWithDetails(10L, user)).thenReturn(project);
        when(remoteDeployService.artifactTypeOf(project, server)).thenReturn(ArtifactType.CONTAINER);
        when(deployRequestRepository.findByProjectIdAndEnvironmentAndStatusInWithProject(
            10L, DeployEnvironment.TEST, List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING)))
            .thenReturn(List.of(active));
        when(remoteDeployService.resolveTargets(active)).thenAnswer(invocation -> {
            DeployRequest resolved = invocation.getArgument(0);
            return resolved.getProject().getServerGroups().isEmpty() ? List.of() : List.of(server);
        });
        org.mockito.Mockito.doAnswer(invocation -> invocation
            .<java.util.function.Supplier<com.portfolio.deployops.dto.ApiResponse<?>>>getArgument(0).get())
            .when(executionPolicyService).withExecutionGuard(any());

        assertThatThrownBy(() -> controller.controlService(
            10L, 20L, "stop", user, mock(HttpServletRequest.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("배포가 진행 중입니다");

        verify(remoteDeployService, never()).controlService(project, server, "stop");
        assertThat(active.getProject()).isSameAs(project);
    }

    @Test
    void serviceControlRunsWhenNoMatchingDeploymentIsActive() {
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        Server server = Server.builder().id(20L).environment(ServerEnvironment.TEST).build();
        Project project = Project.builder().id(10L).name("app")
            .serverGroups(List.of(ServerGroup.builder().environment(ServerEnvironment.TEST)
                .servers(List.of(server)).build()))
            .build();
        when(projectService.findByIdWithDetails(10L, user)).thenReturn(project);
        when(remoteDeployService.artifactTypeOf(project, server)).thenReturn(ArtifactType.CONTAINER);
        when(deployRequestRepository.findByProjectIdAndEnvironmentAndStatusInWithProject(
            10L, DeployEnvironment.TEST, List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING)))
            .thenReturn(List.of());
        when(remoteDeployService.controlService(project, server, "restart"))
            .thenReturn(new RemoteCommandResult(0, "ok", "", "docker restart", LocalDateTime.now(),
                LocalDateTime.now(), 1));
        org.mockito.Mockito.doAnswer(invocation -> invocation
            .<java.util.function.Supplier<com.portfolio.deployops.dto.ApiResponse<?>>>getArgument(0).get())
            .when(executionPolicyService).withExecutionGuard(any());

        controller.controlService(10L, 20L, "restart", user, mock(HttpServletRequest.class));

        verify(remoteDeployService).controlService(project, server, "restart");
    }

    @Test
    void forcedStopWarningIsNotAServiceSuccessOrSuccessfulAudit() {
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        Server server = Server.builder().id(20L).environment(ServerEnvironment.TEST).build();
        Project project = Project.builder().id(10L).name("app")
            .serverGroups(List.of(ServerGroup.builder().environment(ServerEnvironment.TEST)
                .servers(List.of(server)).build()))
            .build();
        when(projectService.findByIdWithDetails(10L, user)).thenReturn(project);
        when(remoteDeployService.artifactTypeOf(project, server)).thenReturn(ArtifactType.CONTAINER);
        when(deployRequestRepository.findByProjectIdAndEnvironmentAndStatusInWithProject(
            10L, DeployEnvironment.TEST, List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING)))
            .thenReturn(List.of());
        when(remoteDeployService.controlService(project, server, "stop"))
            .thenReturn(new RemoteCommandResult(42, "STOP_GRACE_EXCEEDED: 종료 유예 30초 안에 종료되지 않아 강제 종료되었습니다.", "", "docker stop", LocalDateTime.now(),
                LocalDateTime.now(), 1));
        org.mockito.Mockito.doAnswer(invocation -> invocation
            .<java.util.function.Supplier<com.portfolio.deployops.dto.ApiResponse<?>>>getArgument(0).get())
            .when(executionPolicyService).withExecutionGuard(any());

        var response=controller.controlService(10L, 20L, "stop", user, mock(HttpServletRequest.class));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("강제 종료");
        var auditResult=org.mockito.ArgumentCaptor.forClass(com.portfolio.deployops.domain.AuditResult.class);
        verify(auditLogService).record(any(),any(),any(),any(),any(),auditResult.capture(),any());
        assertThat(auditResult.getValue()).isEqualTo(com.portfolio.deployops.domain.AuditResult.FAIL);

        verify(remoteDeployService).controlService(project, server, "stop");
    }

    @Test
    void asyncEnqueueRejectionUsesStartedStateAsCasExpectation() {
        DeployRequest request = approvedProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING)).thenReturn(true);
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        when(user.getName()).thenReturn("operator");
        doThrow(new IllegalStateException("executor rejected"))
            .when(operatorExecutionService).executeRemoteDeployment(any(), any(), any(), any());

        assertThatThrownBy(() -> controller.execute(1L, user, mock(HttpServletRequest.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("executor rejected");

        verify(deployRequestService).tryTransition(
            1L, DeployStatus.PROD_DEPLOYING, DeployStatus.PROD_FAILED);
    }

    @Test
    void singleServerEnqueueRejectionFailsAcquiredServerAndUsesStartCas() {
        DeployRequest request = approvedProdRequest();
        request.setDeployStrategy(DeployStrategy.MANUAL);
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(remoteDeployService.resolveTargets(request)).thenReturn(List.of(Server.builder().id(20L).build()));
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING)).thenReturn(true);
        SecurityUserDetails user = mock(SecurityUserDetails.class);
        when(user.getId()).thenReturn(7L);
        doThrow(new IllegalStateException("executor rejected"))
            .when(operatorExecutionService).executeSingleServerDeployment(any(), any(), any(), any());

        assertThatThrownBy(() -> controller.executeServer(
            1L, 20L, user, mock(HttpServletRequest.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("executor rejected");

        verify(manualServerExecutionService).fail(1L, 20L, "executor rejected");
        verify(deployRequestService).tryTransition(
            1L, DeployStatus.PROD_DEPLOYING, DeployStatus.PROD_FAILED);
    }

    @Test
    void executeStopsBeforeStatusTransitionWhenApprovedPlanHasChanged() {
        DeployRequest request = approvedProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        var rejected = new ResponseStatusException(HttpStatus.CONFLICT, "민감한 설정 값은 감사에 넣지 않는다");
        doThrow(rejected).when(deployRequestService).assertPlanUnchanged(request);
        var user = mock(SecurityUserDetails.class);
        when(user.getName()).thenReturn("실행 담당자");
        var http = mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn("198.18.19.1");

        assertThatThrownBy(() -> controller.execute(1L, user, http)).isSameAs(rejected);

        verify(auditLogService).record("실행 담당자", "배포 실행 거부", "DeployRequest", "1",
            "code=DEPLOYMENT_PLAN_REJECTED", com.portfolio.deployops.domain.AuditResult.FAIL, "198.18.19.1");
        org.mockito.Mockito.verifyNoMoreInteractions(auditLogService);
        verify(http, never()).getHeader(any());
        verify(executionPolicyService, never()).tryStart(any(), any(), any());
        verify(deployRequestService, never()).markExecuted(any(), any());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void planRejectionAuditFailurePropagatesWithoutStartingDeployment() {
        DeployRequest request = approvedProdRequest();
        when(deployRequestService.findById(1L)).thenReturn(request);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "계획 변경"))
            .when(deployRequestService).assertPlanUnchanged(request);
        var auditFailure = new IllegalStateException("audit unavailable");
        doThrow(auditFailure).when(auditLogService).record(any(), eq("배포 실행 거부"), eq("DeployRequest"),
            eq("1"), eq("code=DEPLOYMENT_PLAN_REJECTED"),
            eq(com.portfolio.deployops.domain.AuditResult.FAIL), any());

        assertThatThrownBy(() -> controller.execute(1L, mock(SecurityUserDetails.class),
            mock(HttpServletRequest.class))).isSameAs(auditFailure);

        verify(executionPolicyService, never()).tryStart(any(), any(), any());
        verify(deployRequestService, never()).markExecuted(any(), any());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }
}
