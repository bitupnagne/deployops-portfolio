package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RolloutTrafficRecoveryServiceTest {
    private final DeployRequestService requests = mock(DeployRequestService.class);
    private final RemoteDeployService remote = mock(RemoteDeployService.class);
    private final DeployLogService logs = mock(DeployLogService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final RolloutTrafficRecoveryService service = new RolloutTrafficRecoveryService(requests, remote, logs, audit);
    private final DeployRequest request = DeployRequest.builder().id(17L).reqNo("DEP-17").build();

    @BeforeEach
    void setup() {
        when(requests.findById(17L)).thenReturn(request);
        when(requests.tryTransitionToFailure(eq(17L), any(), any(), anyString())).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(value = DeployStatus.class, names = {"TEST_FAILED", "PROD_FAILED"})
    void remoteFailureRestoresEnvironmentFailureStateAndClosesStream(DeployStatus failed) {
        doThrow(new RemoteExecutionException("WEB unavailable")).when(remote).recoverTraffic(eq(request), any());
        assertThatCode(() -> service.recover(17L, failed, "operator", "192.0.2.10")).doesNotThrowAnyException();
        DeployStatus executing = failed == DeployStatus.TEST_FAILED ? DeployStatus.TEST_DEPLOYING : DeployStatus.PROD_DEPLOYING;
        var order = inOrder(requests, remote, audit, logs);
        order.verify(requests).findById(17L);
        order.verify(remote).recoverTraffic(eq(request), any());
        order.verify(logs).sendLog(17L, "트래픽 복구 실패: WEB unavailable");
        order.verify(audit).record(eq("operator"), eq("배포 트래픽 복구 실패"), eq("DeployRequest"),
            eq("17"), contains("WEB unavailable"), eq(AuditResult.FAIL), eq("192.0.2.10"));
        order.verify(requests).tryTransitionToFailure(17L, executing, failed, "트래픽 복구 실패: WEB unavailable");
        order.verify(logs).sendStatus(17L, failed.name());
        order.verify(logs).complete(17L);
        verify(requests, never()).updateStatus(any(), any());
    }

    @Test
    void loadFailureStillReleasesClaimAndClosesStreamWithoutRemoteMutation() {
        when(requests.findById(17L)).thenThrow(new IllegalStateException("request unavailable"));
        service.recover(17L, DeployStatus.PROD_FAILED, "operator", "192.0.2.10");
        verifyNoInteractions(remote);
        verify(requests).tryTransitionToFailure(eq(17L), eq(DeployStatus.PROD_DEPLOYING),
            eq(DeployStatus.PROD_FAILED), contains("request unavailable"));
        verify(logs).sendStatus(17L, "PROD_FAILED");
        verify(logs).complete(17L);
    }

    @Test
    void changedRequestStateDoesNotEmitAnInventedFailureStatus() {
        when(requests.tryTransitionToFailure(eq(17L), any(), any(), anyString())).thenReturn(false);
        service.recover(17L, DeployStatus.PROD_FAILED, "operator", "192.0.2.10");
        verify(logs, never()).sendStatus(anyLong(), anyString());
        verify(logs).complete(17L);
    }

    @Test
    void statePersistenceFailureStillClosesStream() {
        when(requests.tryTransitionToFailure(eq(17L), any(), any(), anyString())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> service.recover(17L, DeployStatus.PROD_FAILED, "operator", "192.0.2.10"))
            .hasMessageContaining("DB unavailable");
        verify(logs).complete(17L);
    }

    @Test
    void successfulTrafficRecoveryDoesNotClaimDeploymentCompleted() {
        doAnswer(call -> {
            Consumer<String> sink = call.getArgument(1);
            sink.accept("WEB1 traffic restored");
            return null;
        }).when(remote).recoverTraffic(eq(request), any());
        service.recover(17L, DeployStatus.PROD_FAILED, "operator", "192.0.2.10");
        verify(logs).sendLog(17L, "WEB1 traffic restored");
        verify(audit).record("operator", "배포 트래픽 복구 완료", "DeployRequest", "17", "DEP-17",
            AuditResult.SUCCESS, "192.0.2.10");
        verify(requests).tryTransitionToFailure(eq(17L), eq(DeployStatus.PROD_DEPLOYING),
            eq(DeployStatus.PROD_FAILED), contains("실패 대상 재시도"));
        verify(logs).sendStatus(17L, "PROD_FAILED");
        verify(logs).complete(17L);
        verify(logs, never()).sendStatus(17L, "COMPLETED");
        verify(requests, never()).updateStatus(any(), any());
    }
}
