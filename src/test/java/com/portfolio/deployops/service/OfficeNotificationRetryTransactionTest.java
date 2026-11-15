package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.OfficeNotificationOutbox;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.OfficeNotificationOutboxRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import com.portfolio.deployops.repository.SsoRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Actual Spring transaction interceptor and bound JDBC connection, with no database/network server. */
class OfficeNotificationRetryTransactionTest {

    @Test
    void auditFailureRollsBackBoundQueueTransactionAndCannotSend() throws Exception {
        verifyRetryTransaction(true);
    }

    @Test
    void successfulRequiredAuditCommitsBoundQueueTransactionWithoutSending() throws Exception {
        verifyRetryTransaction(false);
    }

    @Test
    void ordinaryEnqueueRollbackCannotRegisterOrPerformNetworkDelivery() throws Exception {
        verifyOrdinaryEnqueueTransaction(true);
    }

    @Test
    void ordinaryEnqueueCommitIsDeliveredOnlyByTheLaterSchedulerClaim() throws Exception {
        verifyOrdinaryEnqueueTransaction(false);
    }

    @SuppressWarnings("unchecked")
    private void verifyOrdinaryEnqueueTransaction(boolean rollback) throws Exception {
        DataSource dataSource = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        OfficeNotificationOutboxRepository outbox = mock(OfficeNotificationOutboxRepository.class);
        SsoRepository sso = mock(SsoRepository.class);
        ProjectUserPermissionRepository permissions = mock(ProjectUserPermissionRepository.class);
        SystemSettingService settings = mock(SystemSettingService.class);
        HttpClient http = mock(HttpClient.class);
        OfficeNotificationService service = new OfficeNotificationService(settings, sso, mock(UserRepository.class),
            mock(ApprovalStepRepository.class), permissions, outbox, mock(AuditLogService.class), JsonMapper.builder().build());
        ReflectionTestUtils.setField(service, "httpClient", http);
        when(sso.findOfficeUserIdByDeployUserId(20L)).thenReturn(Optional.of(200L));
        when(permissions.findUserIdsByProjectAndPermission(50L, ProjectPermissionType.TEST_EXECUTE,
            ProjectPermissionEnvironment.TEST)).thenReturn(List.of());
        AtomicReference<OfficeNotificationOutbox> queued = new AtomicReference<>();
        when(outbox.insertIfAbsent(any())).thenAnswer(invocation -> {
            assertBoundConnection(dataSource, connection);
            OfficeNotificationOutbox item = invocation.getArgument(0); item.setId(55L); item.setStatus("PENDING");
            queued.set(item); return 1;
        });
        DeployRequest request = DeployRequest.builder().id(1L).reqNo("REQ-001")
            .project(Project.builder().id(50L).name("project").build()).status(DeployStatus.TEST_REQUESTED)
            .environment(DeployEnvironment.TEST).requestedBy(User.builder().id(20L).name("user").active(true).build()).build();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Runnable operation = () -> transaction.executeWithoutResult(status -> {
            service.notifyCreated(request);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(outbox, never()).claimForDelivery(any()); verifyNoInteractions(http);
            if (rollback) throw new IllegalStateException("outer request rollback");
        });
        if (rollback) {
            assertThatThrownBy(operation::run).isInstanceOf(IllegalStateException.class);
            verify(connection).rollback(); verify(connection, never()).commit(); verifyNoInteractions(http);
        } else {
            operation.run(); verify(connection).commit(); verify(connection, never()).rollback();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            when(settings.get(org.mockito.ArgumentMatchers.eq("office.base-url"), any())).thenReturn("http://office.invalid");
            when(settings.get(org.mockito.ArgumentMatchers.eq("sso.shared-secret"), any())).thenReturn("test-only-secret");
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (n, v) -> true));
            when(response.body()).thenReturn("{\"success\":true}");
            when(http.<String>send(any(), any())).thenReturn(response);
            when(outbox.findPending(any(), org.mockito.ArgumentMatchers.eq(100))).thenReturn(List.of(queued.get()));
            when(outbox.claimForDelivery(55L)).thenReturn(1);
            service.retryPending();
            verify(http).send(any(), any()); verify(outbox).claimForDelivery(55L); verify(outbox).markDelivered(55L);
            verify(outbox, never()).markAttemptFailed(any(), anyInt(), anyString());
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void assertBoundConnection(DataSource dataSource, Connection expected) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        Connection actual = DataSourceUtils.getConnection(dataSource);
        try { assertThat(actual).isSameAs(expected); }
        finally { DataSourceUtils.releaseConnection(actual, dataSource); }
    }

    private void verifyRetryTransaction(boolean auditFails) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        OfficeNotificationOutboxRepository outbox = mock(OfficeNotificationOutboxRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        HttpClient http = mock(HttpClient.class);
        OfficeNotificationService target = new OfficeNotificationService(
            mock(SystemSettingService.class), mock(SsoRepository.class), mock(UserRepository.class),
            mock(ApprovalStepRepository.class), mock(ProjectUserPermissionRepository.class), outbox, audit,
            JsonMapper.builder().build());
        ReflectionTestUtils.setField(target, "httpClient", http);
        List<String> order = new ArrayList<>();
        when(outbox.requeueDeadLetter(55L)).thenAnswer(invocation -> {
            assertBoundConnection(dataSource, connection);
            order.add("requeue");
            return 1;
        });
        doAnswer(invocation -> {
            assertBoundConnection(dataSource, connection);
            order.add("required-audit");
            if (auditFails) throw new IllegalStateException("audit unavailable");
            return null;
        }).when(audit).recordCurrentRequired("Office 알림 재전송", "OfficeNotificationOutbox", "55",
            "DEAD_LETTER 알림을 재전송 큐에 접수", AuditResult.SUCCESS);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
            new AnnotationTransactionAttributeSource()));
        OfficeNotificationService transactional = (OfficeNotificationService) factory.getProxy();
        if (auditFails) {
            assertThatThrownBy(() -> transactional.retryDeadLetter(55L))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
            verify(connection).rollback();
            verify(connection, never()).commit();
        } else {
            assertThat(transactional.retryDeadLetter(55L)).isTrue();
            verify(connection).commit();
            verify(connection, never()).rollback();
        }
        assertThat(order).containsExactly("requeue", "required-audit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(outbox, never()).findById(any());
        verify(outbox, never()).claimForDelivery(any());
        verify(outbox, never()).markDelivered(any());
        verify(outbox, never()).markAttemptFailed(any(), anyInt(), anyString());
        verifyNoInteractions(http);
    }
}
