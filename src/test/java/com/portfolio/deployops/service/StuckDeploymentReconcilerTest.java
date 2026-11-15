package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.repository.DeployRequestRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StuckDeploymentReconcilerTest {
    private final DeployRequestRepository repository = mock(DeployRequestRepository.class);
    private final DeployRequestService requestService = mock(DeployRequestService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final StuckDeploymentReconciler reconciler =
        new StuckDeploymentReconciler(repository, requestService, auditLogService);

    @Test
    void persistsActionableRestartReasonForAllInterruptedStatesBeforeAudit() {
        var states = List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING,
            DeployStatus.PROD_DEPLOYING);
        when(repository.findByStatusIn(anyList())).thenReturn(List.of(
            request(1L, states.get(0)), request(2L, states.get(1)), request(3L, states.get(2))));
        when(requestService.tryTransitionToFailure(anyLong(), any(), any(), anyString())).thenReturn(true);

        reconciler.reconcileOnStartup();

        var reasons = ArgumentCaptor.forClass(String.class);
        var order = inOrder(requestService, auditLogService);
        for (int index = 0; index < states.size(); index++) {
            long id = index + 1;
            DeployStatus failed = index == 2 ? DeployStatus.PROD_FAILED : DeployStatus.TEST_FAILED;
            order.verify(requestService).tryTransitionToFailure(eq(id), eq(states.get(index)),
                eq(failed), reasons.capture());
            order.verify(auditLogService).record(eq("SYSTEM"), eq("중단된 배포 자동 정리"),
                eq("DeployRequest"), eq(String.valueOf(id)), anyString(), eq(AuditResult.FAIL),
                eq("127.0.0.1"));
        }
        assertThat(reasons.getAllValues()).allSatisfy(reason -> {
            assertThat(reason).contains("재시작", "중단", "대상 서버 상태", "실패 대상 재시도", "새 TEST 요청");
            assertThat(reason).hasSizeLessThanOrEqualTo(1800);
        });
        verify(requestService, never()).tryTransition(anyLong(), any(), any());
    }

    @Test
    void lostCompareAndSetDoesNotAuditOrOverwriteTheOtherExecution() {
        when(repository.findByStatusIn(anyList())).thenReturn(List.of(request(1L, DeployStatus.TEST_DEPLOYING)));
        when(requestService.tryTransitionToFailure(eq(1L), eq(DeployStatus.TEST_DEPLOYING),
            eq(DeployStatus.TEST_FAILED), anyString())).thenReturn(false);

        reconciler.reconcileOnStartup();

        verifyNoInteractions(auditLogService);
        verify(requestService).tryTransitionToFailure(eq(1L), eq(DeployStatus.TEST_DEPLOYING),
            eq(DeployStatus.TEST_FAILED), anyString());
        verifyNoMoreInteractions(requestService);
    }

    @Test
    void recoveryQueryExcludesWaitingSuccessfulAndAlreadyFailedRequests() {
        when(repository.findByStatusIn(anyList())).thenReturn(List.of());

        reconciler.reconcileOnStartup();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeployStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(repository).findByStatusIn(statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(DeployStatus.TEST_BUILDING,
            DeployStatus.TEST_DEPLOYING, DeployStatus.PROD_DEPLOYING);
        verifyNoInteractions(requestService, auditLogService);
    }

    @Test
    void oneCorruptRequestDoesNotStopRecoveryOfOtherRequests() {
        when(repository.findByStatusIn(anyList())).thenReturn(List.of(
            request(1L, DeployStatus.TEST_BUILDING), request(2L, DeployStatus.PROD_DEPLOYING)));
        when(requestService.tryTransitionToFailure(eq(1L), eq(DeployStatus.TEST_BUILDING),
            eq(DeployStatus.TEST_FAILED), anyString())).thenThrow(new IllegalStateException("corrupt snapshot"));
        when(requestService.tryTransitionToFailure(eq(2L), eq(DeployStatus.PROD_DEPLOYING),
            eq(DeployStatus.PROD_FAILED), anyString())).thenReturn(true);

        reconciler.reconcileOnStartup();

        verify(requestService).tryTransitionToFailure(eq(2L), eq(DeployStatus.PROD_DEPLOYING),
            eq(DeployStatus.PROD_FAILED), anyString());
        verify(auditLogService).record(eq("SYSTEM"), eq("중단된 배포 자동 정리"),
            eq("DeployRequest"), eq("2"), anyString(), eq(AuditResult.FAIL), eq("127.0.0.1"));
    }

    private DeployRequest request(long id, DeployStatus status) {
        return DeployRequest.builder().id(id).reqNo("REQ-" + id).status(status).build();
    }
}
