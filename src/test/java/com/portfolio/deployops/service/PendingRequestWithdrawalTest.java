package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.sql.Connection;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PendingRequestWithdrawalTest {
    @Mock DeployRequestRepository requests;
    @Mock ProjectPermissionService permissions;
    @Mock StatusHistoryRepository histories;
    @Mock AuditLogService audit;
    @Mock ProjectImageHistoryService images;
    @Mock OfficeNotificationService notifications;
    @InjectMocks DeployRequestService service;
    private final User owner = User.builder().id(7L).loginId("owner").name("요청자")
        .role(UserRole.DEVELOPER).build();
    private final SecurityUserDetails user = new SecurityUserDetails(owner);
    private DeployRequest request;

    @BeforeEach void setup() {
        request = DeployRequest.builder().id(46L).reqNo("REQ-46")
            .project(Project.builder().id(6L).name("qa").build()).requestedBy(owner)
            .environment(DeployEnvironment.TEST).status(DeployStatus.TEST_REQUESTED)
            .expectedCommitSha("immutable-original").planSnapshotHash("stale-plan")
            .releaseVerificationEvidenceHash("protected-evidence").build();
    }

    private void loaded() {
        when(requests.lockById(46L)).thenReturn(46L);
        when(requests.findById(46L)).thenReturn(Optional.of(request));
    }

    private void ownerPermission() {
        when(permissions.hasPermission(user, 6L, ProjectPermissionType.DEPLOY_REQUEST,
            request.getEnvironment())).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(value = DeployStatus.class, names = {"TEST_REQUESTED", "APPROVAL_PENDING", "APPROVAL_COMPLETED"})
    void pendingRequestCanBeWithdrawnWithoutChangingItsPlanOrReleaseEvidence(DeployStatus status) {
        loaded(); ownerPermission(); request.setStatus(status);
        when(requests.withdrawPending(46L, status, "대상 설정 변경")).thenReturn(1);
        var result = service.withdraw(46L, "  대상 설정 변경  ", user, "127.0.0.1");
        assertThat(result.getStatus()).isEqualTo(DeployStatus.CANCELLED);
        assertThat(result.getWithdrawalReason()).isEqualTo("대상 설정 변경");
        assertThat(result.getExpectedCommitSha()).isEqualTo("immutable-original");
        assertThat(result.getPlanSnapshotHash()).isEqualTo("stale-plan");
        assertThat(result.getReleaseVerificationEvidenceHash()).isEqualTo("protected-evidence");
        var order = inOrder(requests, histories, audit);
        order.verify(requests).lockById(46L);
        order.verify(requests).findById(46L);
        order.verify(requests).withdrawPending(46L, status, "대상 설정 변경");
        order.verify(histories).save(argThat(h -> h.getStatus() == DeployStatus.CANCELLED));
        order.verify(audit).record(eq("요청자"), eq("배포 요청 철회"), eq("DeployRequest"), eq("46"),
            contains("철회 사유: 대상 설정 변경"), eq(AuditResult.SUCCESS), eq("127.0.0.1"));
        verify(requests, never()).update(any());
        verifyNoInteractions(images, notifications);
        assertThat(result.getStatus().isInFlight()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DeployStatus.class, names = {"TEST_REQUESTED", "APPROVAL_PENDING", "APPROVAL_COMPLETED"},
        mode = EnumSource.Mode.EXCLUDE)
    void runningAndTerminalRequestsIncludingRepeatedWithdrawalAreRejected(DeployStatus status) {
        loaded(); ownerPermission(); request.setStatus(status);
        assertHttp(409, () -> service.withdraw(46L, "철회", user, "ip"));
        verify(requests, never()).withdrawPending(anyLong(), any(), anyString());
        verifyNoInteractions(histories, audit, images, notifications);
        assertThat(service.canWithdraw(user, request)).isFalse();
    }

    @Test void executionWinningBeforeCompareAndSetCannotBeCancelled() {
        loaded(); ownerPermission();
        when(requests.withdrawPending(46L, DeployStatus.TEST_REQUESTED, "철회")).thenReturn(0);
        assertHttp(409, () -> service.withdraw(46L, "철회", user, "ip"));
        assertThat(request.getStatus()).isEqualTo(DeployStatus.TEST_REQUESTED);
        verifyNoInteractions(histories, audit, images, notifications);
    }

    @Test void unrelatedOperatorCannotWithdrawEvenWithExecutePermission() {
        loaded();
        var operator = new SecurityUserDetails(User.builder().id(8L).role(UserRole.OPERATOR).build());
        assertHttp(403, () -> service.withdraw(46L, "철회", operator, "ip"));
        assertThat(service.canWithdraw(operator, request)).isFalse();
        verify(requests, never()).withdrawPending(anyLong(), any(), anyString());
        verifyNoInteractions(histories, audit);
    }

    @Test void requesterWithRevokedRequestPermissionCannotWithdraw() {
        loaded();
        assertHttp(403, () -> service.withdraw(46L, "철회", user, "ip"));
        assertThat(service.canWithdraw(user, request)).isFalse();
        verify(requests, never()).withdrawPending(anyLong(), any(), anyString());
    }

    @Test void administratorCanRecoverAnotherUsersPendingRequest() {
        loaded();
        var admin = new SecurityUserDetails(User.builder().id(1L).name("관리자").role(UserRole.SUPER_ADMIN).build());
        when(permissions.isSuperAdmin(admin)).thenReturn(true);
        when(requests.withdrawPending(46L, DeployStatus.TEST_REQUESTED, "관리 복구")).thenReturn(1);
        assertThat(service.canWithdraw(admin, request)).isTrue();
        assertThat(service.withdraw(46L, "관리 복구", admin, "ip").getStatus()).isEqualTo(DeployStatus.CANCELLED);
    }

    @Test void prodRequesterRequiresProdRequestPermission() {
        request.setEnvironment(DeployEnvironment.PROD); loaded();
        assertHttp(403, () -> service.withdraw(46L, "철회", user, "ip"));
        verify(permissions).hasPermission(user, 6L, ProjectPermissionType.DEPLOY_REQUEST, DeployEnvironment.PROD);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"   "})
    void blankReasonsAreRejectedBeforeDatabaseAccess(String reason) {
        assertHttp(400, () -> service.withdraw(46L, reason, user, "ip"));
        verifyNoInteractions(requests, histories, audit);
    }

    @Test void oversizedReasonRejectedBeforeDatabaseAccess() {
        assertHttp(400, () -> service.withdraw(46L, "x".repeat(401), user, "ip"));
        verifyNoInteractions(requests, histories, audit);
    }

    @Test void missingRequestReturns404() {
        assertHttp(404, () -> service.withdraw(46L, "철회", user, "ip"));
        verifyNoInteractions(histories, audit);
    }

    @Test void mandatoryAuditFailureRollsBackSharedTransaction() throws Exception {
        loaded(); ownerPermission();
        when(requests.withdrawPending(46L, DeployStatus.TEST_REQUESTED, "철회")).thenReturn(1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
            .record(any(), any(), any(), any(), any(), any(), any());
        Connection connection = mock(Connection.class);
        DeployRequestService transactional = transactional(connection);
        assertThatThrownBy(() -> transactional.withdraw(46L, "철회", user, "ip"))
            .isInstanceOf(IllegalStateException.class);
        verify(connection).rollback(); verify(connection, never()).commit();
    }

    @Test void statusHistoryFailureRollsBackAndDoesNotReportSuccess() throws Exception {
        loaded(); ownerPermission();
        when(requests.withdrawPending(46L, DeployStatus.TEST_REQUESTED, "철회")).thenReturn(1);
        doThrow(new IllegalStateException("history unavailable")).when(histories).save(any());
        Connection connection = mock(Connection.class);
        DeployRequestService transactional = transactional(connection);
        assertThatThrownBy(() -> transactional.withdraw(46L, "철회", user, "ip"))
            .isInstanceOf(IllegalStateException.class);
        verify(connection).rollback(); verify(connection, never()).commit(); verifyNoInteractions(audit);
    }

    private DeployRequestService transactional(Connection connection) throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var factory = new ProxyFactory(service); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        return (DeployRequestService) factory.getProxy();
    }

    private void assertHttp(int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(status));
    }
}
