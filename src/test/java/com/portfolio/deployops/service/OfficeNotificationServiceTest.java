package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import tools.jackson.databind.json.JsonMapper;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.OfficeNotificationOutbox;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.OfficeNotificationOutboxRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import com.portfolio.deployops.repository.SsoRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.time.LocalDateTime;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OfficeNotificationServiceTest {
    @Test
    void frozenGroupStagesNotifyOnlyCurrentApproversAndRepeatInvitationHasDistinctKey() {
        var member=User.builder().id(11L).active(true).build();
        var first=com.portfolio.deployops.domain.ApprovalPolicy.builder().id(91L)
            .policyType(com.portfolio.deployops.domain.PolicyType.STEP).steps(List.of(
                com.portfolio.deployops.domain.ApprovalStep.builder().id(92L).stepOrder(1).requiredCount(1)
                    .approvers(List.of(member)).build())).build();
        var second=com.portfolio.deployops.domain.ApprovalPolicy.builder().id(93L)
            .policyType(com.portfolio.deployops.domain.PolicyType.STEP).steps(List.of(
                com.portfolio.deployops.domain.ApprovalStep.builder().id(94L).stepOrder(1).requiredCount(1)
                    .approvers(List.of(member)).build())).build();
        var combined=EffectiveApprovalPolicy.compose(first,List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(2L).approvalPolicyId(93L).build()),id -> second);
        var request=request(DeployStatus.APPROVAL_PENDING,DeployEnvironment.TEST);
        request.getProject().setApprovalPolicy(combined);
        when(ssoRepository.findOfficeUserIdByDeployUserId(11L)).thenReturn(Optional.of(110L));
        service.notifyApprovalPending(request);
        request.setApprovalRecords(List.of(com.portfolio.deployops.domain.ApprovalRecord.builder()
            .stepOrder(1).action(com.portfolio.deployops.domain.ApprovalAction.APPROVED).approver(member).build()));
        service.notifyApprovalPending(request);
        var rows=ArgumentCaptor.forClass(OfficeNotificationOutbox.class);
        verify(outboxRepository,times(2)).insertIfAbsent(rows.capture());
        assertThat(rows.getAllValues().get(0).getEventKey()).isNotEqualTo(rows.getAllValues().get(1).getEventKey());
        verifyNoInteractions(approvalStepRepository,userRepository);
    }

    @Test
    void manuallyApprovedTestGroupNotifiesTestOperators() {
        var request=request(DeployStatus.TEST_REQUESTED,DeployEnvironment.TEST);
        request.setApprovalDecisionSource(com.portfolio.deployops.domain.ApprovalDecisionSource.MANUAL);
        service.notifyApprovalCompleted(request);
        verify(projectUserPermissionRepository).findUserIdsByProjectAndPermission(
            50L,ProjectPermissionType.TEST_EXECUTE,ProjectPermissionEnvironment.TEST);
        verify(projectUserPermissionRepository,never()).findUserIdsByProjectAndPermission(
            50L,ProjectPermissionType.PROD_EXECUTE,ProjectPermissionEnvironment.PROD);
    }

    private final SystemSettingService settingService = mock(SystemSettingService.class);
    private final SsoRepository ssoRepository = mock(SsoRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApprovalStepRepository approvalStepRepository = mock(ApprovalStepRepository.class);
    private final ProjectUserPermissionRepository projectUserPermissionRepository = mock(ProjectUserPermissionRepository.class);
    private final OfficeNotificationOutboxRepository outboxRepository = mock(OfficeNotificationOutboxRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final HttpClient httpClient = mock(HttpClient.class);
    private final OfficeNotificationService service = new OfficeNotificationService(
        settingService,
        ssoRepository,
        userRepository,
        approvalStepRepository,
        projectUserPermissionRepository,
        outboxRepository,
        auditLogService,
        JsonMapper.builder().build()
    );

    @Test
    void approvalCompletedNotifiesProdOperatorsOnlyAfterFinalCompletion() {
        DeployRequest pending = request(DeployStatus.APPROVAL_PENDING, DeployEnvironment.PROD);

        service.notifyApprovalCompleted(pending);

        verify(projectUserPermissionRepository, never()).findUserIdsByProjectAndPermission(
            50L,
            ProjectPermissionType.PROD_EXECUTE,
            ProjectPermissionEnvironment.PROD
        );

        DeployRequest completed = request(DeployStatus.APPROVAL_COMPLETED, DeployEnvironment.PROD);
        when(projectUserPermissionRepository.findUserIdsByProjectAndPermission(
            50L,
            ProjectPermissionType.PROD_EXECUTE,
            ProjectPermissionEnvironment.PROD
        )).thenReturn(List.of(30L));
        when(ssoRepository.findOfficeUserIdByDeployUserId(20L)).thenReturn(Optional.empty());
        when(ssoRepository.findOfficeUserIdByDeployUserId(30L)).thenReturn(Optional.empty());

        service.notifyApprovalCompleted(completed);

        verify(projectUserPermissionRepository).findUserIdsByProjectAndPermission(
            50L,
            ProjectPermissionType.PROD_EXECUTE,
            ProjectPermissionEnvironment.PROD
        );
    }

    @Test
    void mergeRequestCreatedTargetsProjectMrReviewUsers() {
        Project project = project();
        User actor = User.builder().id(10L).name("developer").active(true).build();
        when(projectUserPermissionRepository.findUserIdsByProjectAndPermission(
            50L,
            ProjectPermissionType.MR_REVIEW,
            ProjectPermissionEnvironment.ALL
        )).thenReturn(List.of(11L, 12L));
        when(ssoRepository.findOfficeUserIdByDeployUserId(11L)).thenReturn(Optional.empty());
        when(ssoRepository.findOfficeUserIdByDeployUserId(12L)).thenReturn(Optional.empty());

        service.notifyMergeRequestCreated(project, 7L, actor);

        verify(projectUserPermissionRepository).findUserIdsByProjectAndPermission(
            50L,
            ProjectPermissionType.MR_REVIEW,
            ProjectPermissionEnvironment.ALL
        );
    }

    @Test
    void duplicateLogicalEventIsNotScheduledForAnotherDelivery() {
        DeployRequest request = request(DeployStatus.TEST_REQUESTED, DeployEnvironment.TEST);
        when(ssoRepository.findOfficeUserIdByDeployUserId(20L)).thenReturn(Optional.of(200L));
        when(projectUserPermissionRepository.findUserIdsByProjectAndPermission(
            50L, ProjectPermissionType.TEST_EXECUTE, ProjectPermissionEnvironment.TEST
        )).thenReturn(List.of());
        when(outboxRepository.insertIfAbsent(any())).thenReturn(1, 0);

        service.notifyCreated(request);
        service.notifyCreated(request);

        ArgumentCaptor<com.portfolio.deployops.domain.OfficeNotificationOutbox> inserted =
            ArgumentCaptor.forClass(com.portfolio.deployops.domain.OfficeNotificationOutbox.class);
        verify(outboxRepository, org.mockito.Mockito.times(2)).insertIfAbsent(inserted.capture());
        assertThat(inserted.getAllValues().get(0).getEventKey())
            .hasSize(64)
            .isEqualTo(inserted.getAllValues().get(1).getEventKey());
    }

    @Test
    void outboxSnapshotClampsPageSizeAndExposesAllOperationalCounts() {
        when(outboxRepository.countByStatus("DEAD_LETTER")).thenReturn(3L);
        when(outboxRepository.countByStatus("PENDING")).thenReturn(2L);
        when(outboxRepository.countByStatus("SENDING")).thenReturn(1L);
        when(outboxRepository.countByStatus("DELIVERED")).thenReturn(8L);
        when(outboxRepository.findByStatus("DEAD_LETTER", 100, 0)).thenReturn(List.of());

        OfficeNotificationService.OutboxSnapshot snapshot =
            service.getOutboxSnapshot("dead_letter", -2, 500);

        assertThat(snapshot.status()).isEqualTo("DEAD_LETTER");
        assertThat(snapshot.page()).isZero();
        assertThat(snapshot.size()).isEqualTo(100);
        assertThat(snapshot.total()).isEqualTo(3L);
        assertThat(snapshot.counts()).isEqualTo(Map.of(
            "PENDING", 2L, "SENDING", 1L, "DELIVERED", 8L, "DEAD_LETTER", 3L
        ));
    }

    @Test
    void onlyDeadLetterEntryCanBeManuallyRequeued() {
        when(outboxRepository.requeueDeadLetter(55L)).thenReturn(0);

        assertThat(service.retryDeadLetter(55L)).isFalse();

        verify(outboxRepository, never()).findById(55L);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void retryAcceptsOnlyAnAuditedQueueChangeAndDoesNotSendBeforeCommit() {
        when(outboxRepository.requeueDeadLetter(55L)).thenReturn(1);
        assertThat(service.retryDeadLetter(55L)).isTrue();
        verify(auditLogService).recordCurrentRequired("Office 알림 재전송", "OfficeNotificationOutbox", "55",
            "DEAD_LETTER 알림을 재전송 큐에 접수", AuditResult.SUCCESS);
        verify(outboxRepository, never()).findById(any());
        verify(outboxRepository, never()).claimForDelivery(any());
        verifyNoInteractions(httpClient);
    }

    @Test
    void ordinaryEnqueueWithoutAnOuterTransactionStoresOnlyPendingAndDoesNotSend() {
        prepareEnqueue();
        service.notifyCreated(request(DeployStatus.TEST_REQUESTED, DeployEnvironment.TEST));
        verify(outboxRepository).insertIfAbsent(any());
        verify(outboxRepository, never()).claimForDelivery(any());
        verifyNoInteractions(httpClient);
    }

    @Test
    void ordinaryEnqueueDoesNotRegisterAfterCommitDeliveryOrSendDuringATransaction() {
        prepareEnqueue();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyCreated(request(DeployStatus.TEST_REQUESTED, DeployEnvironment.TEST));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(outboxRepository).insertIfAbsent(any());
            verify(outboxRepository, never()).claimForDelivery(any());
            verifyNoInteractions(httpClient);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    private void prepareEnqueue() {
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        when(ssoRepository.findOfficeUserIdByDeployUserId(20L)).thenReturn(Optional.of(200L));
        when(projectUserPermissionRepository.findUserIdsByProjectAndPermission(
            50L, ProjectPermissionType.TEST_EXECUTE, ProjectPermissionEnvironment.TEST)).thenReturn(List.of());
        when(outboxRepository.insertIfAbsent(any())).thenAnswer(invocation -> {
            OfficeNotificationOutbox inserted = invocation.getArgument(0);
            inserted.setId(55L); inserted.setStatus("PENDING");
            return 1;
        });
        when(outboxRepository.claimForDelivery(55L)).thenReturn(1);
    }

    @Test
    void snapshotSerializesExactlySafeMetadataEvenWithSensitivePayloadAndLegacyError() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 9, 0);
        OfficeNotificationOutbox item = OfficeNotificationOutbox.builder().id(55L).type("DEPLOY_FAILED")
            .status("DEAD_LETTER").attempts(12).createdAt(now).nextAttemptAt(now.plusMinutes(5))
            .deliveredAt(now).deadLetteredAt(now).title("private-title").body("private-body")
            .linkUrl("https://private.invalid/?token=sensitive").recipientOfficeUserId(200L)
            .senderOfficeUserId(300L).eventKey("private-event").lastError("X-SSO-SECRET: sensitive")
            .deliveryStartedAt(now).updatedAt(now).build();
        when(outboxRepository.findByStatus("DEAD_LETTER", 30, 0)).thenReturn(List.of(item));
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var serialized = mapper.readTree(mapper.writeValueAsString(service.getOutboxSnapshot(null, 0, 30)));
        var row = serialized.path("items").path(0);
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "type", "status", "attempts",
            "createdAt", "nextAttemptAt", "deliveredAt", "deadLetteredAt", "targetRequestId");
        assertThat(row.path("id").asLong()).isEqualTo(55L);
        assertThat(row.path("attempts").asInt()).isEqualTo(12);
        assertThat(serialized.toString()).doesNotContain("private-", "sensitive", "recipientOfficeUserId", "lastError");
    }

    @Test
    void actualStoredRequestLinkExposesOnlyIdWithoutPayloadOrDatabaseLookup() {
        var item = OfficeNotificationOutbox.builder().id(1L).type("DEPLOY_REQUESTED").status("DELIVERED")
            .body("REQ-20261009-021 private-body").recipientOfficeUserId(200L)
            .linkUrl("/deploy-system/sso?redirect=%2Fdeploy%2Frequest-detail%3Fid%3D31").build();
        when(outboxRepository.findByStatus("DELIVERED", 30, 0)).thenReturn(List.of(item));
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var json = mapper.writeValueAsString(service.getOutboxSnapshot("DELIVERED", 0, 30));
        assertThat(mapper.readTree(json).path("items").path(0).path("targetRequestId").asLong()).isEqualTo(31L);
        assertThat(json).doesNotContain("private-body", "REQ-", "redirect", "recipientOfficeUserId", "linkUrl");
        verifyNoInteractions(userRepository, projectUserPermissionRepository);
    }

    @Test
    void directSafeRequestLinkAndDeletedRequestIdHaveIdFallbackWithoutLookup() {
        assertThat(OfficeNotificationService.targetRequestId("/deploy/request-detail?id=31")).isEqualTo(31L);
        assertThat(OfficeNotificationService.targetRequestId("/deploy/request-detail?id=999999")).isEqualTo(999999L);
        assertThat(OfficeNotificationService.targetRequestId(null)).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "", "https://external.invalid/deploy/request-detail?id=31", "//external.invalid/deploy/request-detail?id=31",
        "/deploy/request-detail?id=0", "/deploy/request-detail?id=-1", "/deploy/request-detail?id=031",
        "/deploy/request-detail?id=9223372036854775808", "/deploy/request-detail?id=31&id=32",
        "/deploy/request-detail?id=31&token=private", "/deploy/request-detail?id=31#fragment",
        "/deploy/request-detail?other=31", "/deploy/project-detail?id=31", "/deploy/mr-approvals?projectId=31",
        "/deploy-system/sso?redirect=%2Fdeploy%2Frequest-detail%3Fid%3D31&redirect=x",
        "/deploy-system/sso?redirect=https%3A%2F%2Fexternal.invalid%2Fdeploy%2Frequest-detail%3Fid%3D31",
        "/deploy-system/sso?redirect=%252Fdeploy%252Frequest-detail%253Fid%253D31",
        "/deploy-system/sso?redirect=%ZZ", "/deploy-system/sso?redirect=%2Fdeploy%2Frequest-detail%3Fid%3D31%26id%3D32",
        "/deploy-system/sso?redirect=%2Fdeploy%2Frequest-detail%3Fid%3D31%23fragment",
        "/deploy-system/sso?redirect=%2Fdeploy%2Fproject-detail%3Fid%3D31"
    })
    void ambiguousExternalNonRequestAndMalformedTargetsAreNotExposed(String link) {
        assertThat(OfficeNotificationService.targetRequestId(link)).isNull();
    }

    @Test
    void onlyActualOfficeJsonSuccessMarksDelivered() throws Exception {
        prepareDelivery();
        HttpResponse<String> officeResponse = response(200, "application/json;charset=UTF-8", "{\"success\":true}");
        when(httpClient.<String>send(any(), any())).thenReturn(officeResponse);
        service.retryPending();
        verify(httpClient).send(any(), any());
        verify(outboxRepository).markDelivered(55L);
        verify(outboxRepository, never()).markAttemptFailed(any(), anyInt(), anyString());
    }

    static Stream<Arguments> rejectedOfficeResponses() {
        return Stream.of(
            Arguments.of(302, "application/json", "{\"success\":true}", "OFFICE_HTTP_302"),
            Arguments.of(401, "application/json", "{\"success\":true}", "OFFICE_HTTP_401"),
            Arguments.of(500, "application/json", "{\"success\":true}", "OFFICE_HTTP_500"),
            Arguments.of(200, "text/html", "<html>authentication error SECRET</html>", "OFFICE_RESPONSE_INVALID"),
            Arguments.of(200, "application/json", "{\"success\":false,\"message\":\"SECRET\"}", "OFFICE_RESPONSE_REJECTED"),
            Arguments.of(200, "application/json", "{}", "OFFICE_RESPONSE_REJECTED"),
            Arguments.of(200, "application/json", "{\"success\":\"true\"}", "OFFICE_RESPONSE_REJECTED"),
            Arguments.of(200, "application/json", "{\"success\":1}", "OFFICE_RESPONSE_REJECTED"),
            Arguments.of(200, "application/json", "[{\"success\":true}]", "OFFICE_RESPONSE_REJECTED"),
            Arguments.of(204, "application/json", "", "OFFICE_RESPONSE_INVALID"),
            Arguments.of(200, "application/json", "SECRET invalid json", "OFFICE_RESPONSE_INVALID"),
            Arguments.of(200, "application/json", "{\"success\":true} trailing SECRET", "OFFICE_RESPONSE_INVALID"),
            Arguments.of(200, "application/json", "{\"success\":false,\"success\":true}", "OFFICE_RESPONSE_INVALID")
        );
    }

    @ParameterizedTest
    @MethodSource("rejectedOfficeResponses")
    void rejectedResponsesStayInRetryOrDeadLetterLifecycle(int status, String contentType, String body, String category) throws Exception {
        prepareDelivery();
        ReflectionTestUtils.setField(service, "maximumAttempts", 4);
        HttpResponse<String> officeResponse = response(status, contentType, body);
        when(httpClient.<String>send(any(), any())).thenReturn(officeResponse);
        service.retryPending();
        verify(httpClient, times(3)).send(any(), any());
        verify(outboxRepository, never()).markDelivered(any());
        verify(outboxRepository).markAttemptFailed(55L, 4, category);
    }

    @Test
    void failedClaimDoesNotSendOrUpdateAnotherDelivery() throws Exception {
        prepareDelivery();
        when(outboxRepository.claimForDelivery(55L)).thenReturn(0);
        service.retryPending();
        verifyNoInteractions(httpClient);
        verify(outboxRepository, never()).markDelivered(any());
        verify(outboxRepository, never()).markAttemptFailed(any(), anyInt(), anyString());
    }

    @Test
    void exceptionMessagesAndThrowablesNeverEnterStoredOrLoggedDeliveryErrors() throws Exception {
        prepareDelivery();
        Logger logger = (Logger) LoggerFactory.getLogger(OfficeNotificationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            when(httpClient.<String>send(any(), any())).thenThrow(new IOException("X-SSO-SECRET: SECRET private payload"));
            service.retryPending();
            verify(outboxRepository).markAttemptFailed(55L, 12, "OFFICE_TRANSPORT_FAILURE");
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("SECRET", "private payload");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void invalidSecretHeaderIsCategorizedWithoutLoggingItsValueOrSending() {
        prepareDelivery();
        when(settingService.get(eq("sso.shared-secret"), any())).thenReturn("SECRET\r\nprivate");
        Logger logger = (Logger) LoggerFactory.getLogger(OfficeNotificationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            service.retryPending();
            verifyNoInteractions(httpClient);
            verify(outboxRepository).markAttemptFailed(55L, 12, "OFFICE_REQUEST_INVALID");
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("SECRET", "private");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void timeoutHasAFixedSafeCategory() throws Exception {
        prepareDelivery();
        when(httpClient.<String>send(any(), any())).thenThrow(new HttpTimeoutException("SECRET"));
        service.retryPending();
        verify(outboxRepository).markAttemptFailed(55L, 12, "OFFICE_TIMEOUT");
    }

    @Test
    void interruptedSendRestoresInterruptAndDoesNotContinueRetries() throws Exception {
        prepareDelivery();
        when(httpClient.<String>send(any(), any())).thenThrow(new InterruptedException("SECRET"));
        try {
            service.retryPending();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(httpClient).send(any(), any());
            verify(outboxRepository).markAttemptFailed(55L, 12, "OFFICE_INTERRUPTED");
        } finally { Thread.interrupted(); }
    }

    private void prepareDelivery() {
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        when(settingService.get(eq("office.base-url"), any())).thenReturn("http://office.invalid");
        when(settingService.get(eq("sso.shared-secret"), any())).thenReturn("test-only-secret");
        when(outboxRepository.findPending(any(), eq(100))).thenReturn(List.of(
            OfficeNotificationOutbox.builder().id(55L).recipientOfficeUserId(200L).type("DEPLOY_FAILED")
                .title("title").body("body").status("PENDING").build()));
        when(outboxRepository.claimForDelivery(55L)).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String contentType, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }

    private DeployRequest request(DeployStatus status, DeployEnvironment environment) {
        return DeployRequest.builder()
            .id(1L)
            .reqNo("REQ-001")
            .project(project())
            .environment(environment)
            .status(status)
            .requestedBy(User.builder().id(20L).name("requester").active(true).build())
            .build();
    }

    private Project project() {
        return Project.builder()
            .id(50L)
            .name("worksystem-server")
            .build();
    }
}
