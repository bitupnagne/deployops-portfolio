package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RolloutTrafficRecoveryApiControllerTest {
    private final DeployRequestService requests = mock(DeployRequestService.class);
    private final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    private final DeploymentDutySeparationService duties = mock(DeploymentDutySeparationService.class);
    private final ExecutionPolicyService executions = mock(ExecutionPolicyService.class);
    private final RolloutTrafficRecoveryService recovery = mock(RolloutTrafficRecoveryService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SecurityUserDetails user = mock(SecurityUserDetails.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final RolloutTrafficRecoveryApiController controller =
        new RolloutTrafficRecoveryApiController(requests, permissions, duties, executions, recovery, audit);
    private final DeployRequest request = DeployRequest.builder().id(17L).reqNo("DEP-17")
        .project(Project.builder().id(3L).build()).environment(DeployEnvironment.PROD)
        .status(DeployStatus.PROD_FAILED).build();

    @BeforeEach
    void setup() {
        when(requests.findById(17L)).thenReturn(request);
        when(user.getName()).thenReturn("운영 담당");
        when(http.getRemoteAddr()).thenReturn("192.0.2.10");
    }

    @Test
    void deniedProjectPermissionCannotAcquireExecutionOrScheduleRecovery() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트 실행 권한 없음"))
            .when(permissions).assertPermission(user, 3L, ProjectPermissionType.PROD_EXECUTE, DeployEnvironment.PROD);
        assertThatThrownBy(() -> controller.recover(17L, user, http))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(duties, executions, recovery, audit);
        verify(requests, never()).tryTransitionToFailure(any(), any(), any(), any());
    }

    @Test
    void prodDutySeparationFailureCannotAcquireExecutionOrScheduleRecovery() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "요청자는 실행 불가"))
            .when(duties).assertCanOperate(request, user);
        assertThatThrownBy(() -> controller.recover(17L, user, http))
            .hasMessageContaining("요청자는 실행 불가");
        verify(permissions).assertPermission(user, 3L, ProjectPermissionType.PROD_EXECUTE, DeployEnvironment.PROD);
        verifyNoInteractions(executions, recovery, audit);
    }

    @Test
    void alreadyActiveRequestCannotScheduleAnotherRecovery() {
        request.setStatus(DeployStatus.PROD_DEPLOYING);
        assertThatThrownBy(() -> controller.recover(17L, user, http))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("실패한 배포");
        verifyNoInteractions(executions, recovery, audit);
        verify(requests, never()).tryTransitionToFailure(any(), any(), any(), any());
    }

    @Test
    void losingExecutionCasCannotEnqueueOrResetOtherExecution() {
        when(executions.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING)).thenReturn(false);
        assertThatThrownBy(() -> controller.recover(17L, user, http))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("다른 작업");
        verifyNoInteractions(recovery, audit);
        verify(requests, never()).tryTransitionToFailure(any(), any(), any(), any());
    }

    @Test
    void executionPolicyConflictCannotEnqueueOrResetOtherExecution() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "같은 대상 실행 중"))
            .when(executions).tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING);
        assertThatThrownBy(() -> controller.recover(17L, user, http)).hasMessageContaining("같은 대상 실행 중");
        verifyNoInteractions(recovery, audit);
        verify(requests, never()).tryTransitionToFailure(any(), any(), any(), any());
    }

    @Test
    void rejectedAsyncSubmissionRestoresOnlyTheClaimedProdState() {
        when(executions.tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING)).thenReturn(true);
        var rejected = new TaskRejectedException("worker queue full");
        doThrow(rejected).when(recovery).recover(17L, DeployStatus.PROD_FAILED, "운영 담당", "192.0.2.10");
        assertThatThrownBy(() -> controller.recover(17L, user, http)).isSameAs(rejected);
        var order = inOrder(executions, recovery, requests);
        order.verify(executions).tryStart(request, DeployStatus.PROD_FAILED, DeployStatus.PROD_DEPLOYING);
        order.verify(recovery).recover(17L, DeployStatus.PROD_FAILED, "운영 담당", "192.0.2.10");
        order.verify(requests).tryTransitionToFailure(eq(17L), eq(DeployStatus.PROD_DEPLOYING),
            eq(DeployStatus.PROD_FAILED), contains("시작하지 못했습니다"));
        verify(requests, never()).updateStatus(any(), any());
    }

    @Test
    void testRecoveryUsesTestPermissionAndSchedulesOnlyAfterClaim() {
        request.setEnvironment(DeployEnvironment.TEST);
        request.setStatus(DeployStatus.TEST_FAILED);
        when(executions.tryStart(request, DeployStatus.TEST_FAILED, DeployStatus.TEST_DEPLOYING)).thenReturn(true);
        assertThat(controller.recover(17L, user, http)).isNotNull();
        var order = inOrder(permissions, duties, executions, recovery);
        order.verify(permissions).assertPermission(user, 3L, ProjectPermissionType.TEST_EXECUTE, DeployEnvironment.TEST);
        order.verify(duties).assertCanOperate(request, user);
        order.verify(executions).tryStart(request, DeployStatus.TEST_FAILED, DeployStatus.TEST_DEPLOYING);
        order.verify(recovery).recover(17L, DeployStatus.TEST_FAILED, "운영 담당", "192.0.2.10");
        verify(requests, never()).tryTransitionToFailure(any(), any(), any(), any());
    }
}
