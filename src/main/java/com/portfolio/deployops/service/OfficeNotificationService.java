package com.portfolio.deployops.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.OfficeNotificationOutbox;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.OfficeNotificationRequest;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.OfficeNotificationOutboxRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import com.portfolio.deployops.repository.SsoRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeNotificationService {

    private final SystemSettingService settingService;
    private final SsoRepository ssoRepository;
    private final UserRepository userRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final ProjectUserPermissionRepository projectUserPermissionRepository;
    private final OfficeNotificationOutboxRepository outboxRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Value("${app.office.base-url:}")
    private String officeBaseUrl;

    @Value("${app.sso.shared-secret:${SSO_SHARED_SECRET:}}")
    private String sharedSecret;

    @Value("${app.office.notification-max-attempts:12}")
    private int maximumAttempts = 12;

    @Value("${app.office.notification-delivery-lease-seconds:120}")
    private int deliveryLeaseSeconds = 120;

    public void notifyCreated(DeployRequest request) {
        if (request == null || request.getStatus() == null) {
            return;
        }
        if (request.getStatus() == DeployStatus.APPROVAL_PENDING) {
            notifyApprovalPending(request);
            return;
        }
        notifyRequester(request, "DEPLOY_REQUESTED", titlePrefix(request) + " 배포 요청이 접수되었습니다.", summary(request));
        notifyOperators(request, "DEPLOY_OPERATOR_READY", titlePrefix(request) + " 배포 실행 대기", summary(request));
    }

    public void notifyApprovalPending(DeployRequest request) {
        String body = summary(request);
        if (request != null && request.getProject() != null && request.getProject().getApprovalPolicy() != null
            && request.getProject().getApprovalPolicy().isGroupComposed()) {
            // The same person may be assigned to later stages. Include the completed
            // stage evidence so outbox de-duplication does not swallow the next invitation.
            var records = request.getApprovalRecords() == null ? List.<com.portfolio.deployops.domain.ApprovalRecord>of() : request.getApprovalRecords();
            body += " / 합성 승인 진행 " + records.stream()
                .filter(record -> record.getAction() == com.portfolio.deployops.domain.ApprovalAction.APPROVED && record.getApprover() != null)
                .map(record -> record.getStepOrder() + ":" + record.getApprover().getId()).sorted().toList();
        }
        for (Long deployUserId : pendingApproverIds(request)) {
            sendToDeployUser(
                deployUserId,
                requesterId(request),
                "DEPLOY_APPROVAL_REQUESTED",
                titlePrefix(request) + " 배포 승인 요청",
                body,
                detailLink(request)
            );
        }
    }

    public void notifyApprovalCompleted(DeployRequest request) {
        if (request == null || (request.getStatus() != DeployStatus.APPROVAL_COMPLETED
            && !(request.getStatus() == DeployStatus.TEST_REQUESTED
                 && request.getApprovalDecisionSource() == com.portfolio.deployops.domain.ApprovalDecisionSource.MANUAL))) {
            return;
        }
        notifyRequester(request, "DEPLOY_APPROVED", titlePrefix(request) + " 배포 승인이 완료되었습니다.", summary(request));
        notifyOperators(request, "DEPLOY_OPERATOR_READY", titlePrefix(request) + " " + request.getEnvironment() + " 배포 실행 대기", summary(request));
    }

    public void notifyRejected(DeployRequest request) {
        notifyRequester(request, "DEPLOY_REJECTED", titlePrefix(request) + " 배포 요청이 반려되었습니다.", summary(request));
    }

    public void notifyStatusChanged(DeployRequest request) {
        if (request == null || request.getStatus() == null) {
            return;
        }
        String title = switch (request.getStatus()) {
            case TEST_BUILDING -> "TEST 빌드가 시작되었습니다.";
            case TEST_DEPLOYING -> "TEST 배포가 시작되었습니다.";
            case TEST_DEPLOYED -> "TEST 배포가 완료되었습니다.";
            case TEST_FAILED -> "TEST 배포가 실패했습니다.";
            case TEST_CONFIRMED -> "TEST 확인이 완료되었습니다.";
            case PROD_DEPLOYING -> "PROD 배포가 시작되었습니다.";
            case COMPLETED -> "PROD 배포가 완료되었습니다.";
            case PROD_FAILED -> "PROD 배포가 실패했습니다.";
            case ROLLED_BACK -> "배포 롤백이 완료되었습니다.";
            default -> null;
        };
        if (title == null) {
            return;
        }
        String type = eventTypeForStatus(request.getStatus());
        String body = summary(request);
        notifyRequester(request, type, titlePrefix(request) + " " + title, body);
        notifyOperators(request, type, titlePrefix(request) + " " + title, body);
        if (request.getStatus() == DeployStatus.TEST_FAILED
            || request.getStatus() == DeployStatus.PROD_FAILED
            || request.getStatus() == DeployStatus.ROLLED_BACK) {
            notifyProjectViewersAndActor(
                request.getProject(),
                requesterId(request),
                "DEPLOY_INCIDENT",
                titlePrefix(request) + " 운영 확인 필요",
                body + " / 즉시 원인과 복구 상태를 확인해 주세요.",
                detailLink(request)
            );
        }
    }

    public void notifyMergeRequestCreated(Project project, Long mergeRequestIid, User actor) {
        notifyProjectPermissionUsers(
            project,
            ProjectPermissionType.MR_REVIEW,
            ProjectPermissionEnvironment.ALL,
            actorId(actor),
            "MR_REVIEW_REQUESTED",
            projectTitle(project) + " MR 검토 요청",
            mrBody(project, mergeRequestIid),
            projectLink(project)
        );
    }

    public void notifyMergeRequestApproved(Project project, Long mergeRequestIid, User actor) {
        notifyProjectPermissionUsers(
            project,
            ProjectPermissionType.MR_MERGE,
            ProjectPermissionEnvironment.ALL,
            actorId(actor),
            "MR_APPROVED",
            projectTitle(project) + " MR 승인이 완료되었습니다.",
            mrBody(project, mergeRequestIid),
            projectLink(project)
        );
    }

    public void notifyMergeRequestMerged(Project project, Long mergeRequestIid, User actor) {
        notifyProjectViewersAndActor(
            project,
            actorId(actor),
            "MR_MERGED",
            projectTitle(project) + " MR이 merge되었습니다.",
            mrBody(project, mergeRequestIid),
            projectLink(project)
        );
    }

    private void notifyRequester(DeployRequest request, String type, String title, String body) {
        if (request == null || request.getRequestedBy() == null) {
            return;
        }
        sendToDeployUser(request.getRequestedBy().getId(), null, type, title, body, detailLink(request));
    }

    private void notifyOperators(DeployRequest request, String type, String title, String body) {
        if (request == null || request.getProject() == null || request.getEnvironment() == null) {
            return;
        }
        ProjectPermissionType permission = request.getEnvironment() == DeployEnvironment.PROD
            ? ProjectPermissionType.PROD_EXECUTE
            : ProjectPermissionType.TEST_EXECUTE;
        ProjectPermissionEnvironment environment = request.getEnvironment() == DeployEnvironment.PROD
            ? ProjectPermissionEnvironment.PROD
            : ProjectPermissionEnvironment.TEST;
        notifyProjectPermissionUsers(
            request.getProject(),
            permission,
            environment,
            requesterId(request),
            type,
            title,
            body,
            detailLink(request)
        );
    }

    private void notifyProjectPermissionUsers(Project project, ProjectPermissionType permission,
                                              ProjectPermissionEnvironment environment, Long senderDeployUserId,
                                              String type, String title, String body, String linkUrl) {
        if (project == null || project.getId() == null) {
            return;
        }
        projectUserPermissionRepository.findUserIdsByProjectAndPermission(project.getId(), permission, environment)
            .forEach(deployUserId -> sendToDeployUser(deployUserId, senderDeployUserId, type, title, body, linkUrl));
    }

    private void notifyProjectViewersAndActor(Project project, Long senderDeployUserId,
                                              String type, String title, String body, String linkUrl) {
        Set<Long> deployUserIds = new LinkedHashSet<>();
        if (senderDeployUserId != null) {
            deployUserIds.add(senderDeployUserId);
        }
        if (project != null && project.getId() != null) {
            deployUserIds.addAll(projectUserPermissionRepository.findUserIdsByProjectAndPermission(
                project.getId(),
                ProjectPermissionType.PROJECT_VIEW,
                ProjectPermissionEnvironment.ALL
            ));
        }
        deployUserIds.forEach(deployUserId -> sendToDeployUser(deployUserId, senderDeployUserId, type, title, body, linkUrl));
    }

    private void sendToDeployUser(Long recipientDeployUserId, Long senderDeployUserId, String type,
                                  String title, String body, String linkUrl) {
        if (recipientDeployUserId == null) {
            return;
        }
        ssoRepository.findOfficeUserIdByDeployUserId(recipientDeployUserId)
            .ifPresent(officeUserId -> {
                OfficeNotificationRequest request = new OfficeNotificationRequest(
                    officeUserId,
                    senderDeployUserId == null ? null : ssoRepository.findOfficeUserIdByDeployUserId(senderDeployUserId).orElse(null),
                    type,
                    title,
                    body,
                    linkUrl
                );
                OfficeNotificationOutbox outbox = OfficeNotificationOutbox.builder()
                    .recipientOfficeUserId(request.recipientOfficeUserId())
                    .senderOfficeUserId(request.senderOfficeUserId())
                    .type(request.type())
                    .title(request.title())
                    .body(request.body())
                    .linkUrl(request.linkUrl())
                    .eventKey(eventKey(request))
                    .build();
                // 커밋된 PENDING만 스케줄러가 전송한다. 호출 트랜잭션에서 외부 전송하지 않는다.
                if (outboxRepository.insertIfAbsent(outbox) != 1) {
                    log.debug("Office notification deduplicated. eventKey={}", outbox.getEventKey());
                }
            });
    }

    @Scheduled(fixedDelayString = "${app.office.notification-retry-delay-ms:30000}")
    public void retryPending() {
        int recovered = outboxRepository.recoverExpiredClaims(
            LocalDateTime.now().minusSeconds(Math.max(30, deliveryLeaseSeconds))
        );
        if (recovered > 0) {
            log.warn("Recovered {} expired Office notification delivery claim(s).", recovered);
        }
        outboxRepository.findPending(LocalDateTime.now(), 100).forEach(this::deliver);
    }

    private void deliver(OfficeNotificationOutbox outbox) {
        if (outbox == null || outbox.getId() == null || outboxRepository.claimForDelivery(outbox.getId()) != 1) {
            return;
        }
        OfficeNotificationRequest request = new OfficeNotificationRequest(
            outbox.getRecipientOfficeUserId(), outbox.getSenderOfficeUserId(), outbox.getType(),
            outbox.getTitle(), outbox.getBody(), outbox.getLinkUrl());
        DeliveryResult result = send(request);
        if (result.success()) {
            outboxRepository.markDelivered(outbox.getId());
        } else {
            outboxRepository.markAttemptFailed(outbox.getId(), Math.max(1, maximumAttempts), result.error());
        }
    }

    /**
     * 큐 변경과 필수 감사를 함께 커밋한다. 전송은 기존 스케줄러가 수행한다.
     */
    @Transactional
    public boolean retryDeadLetter(Long id) {
        if (id == null || outboxRepository.requeueDeadLetter(id) != 1) {
            return false;
        }
        auditLogService.recordCurrentRequired(
            "Office 알림 재전송", "OfficeNotificationOutbox", String.valueOf(id),
            "DEAD_LETTER 알림을 재전송 큐에 접수", AuditResult.SUCCESS
        );
        return true;
    }

    public OutboxSnapshot getOutboxSnapshot(String requestedStatus, int requestedPage, int requestedSize) {
        String status = normalizeOutboxStatus(requestedStatus);
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 100);
        long total = outboxRepository.countByStatus(status);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String candidate : OUTBOX_STATUS_ORDER) {
            counts.put(candidate, outboxRepository.countByStatus(candidate));
        }
        List<OutboxItem> items = outboxRepository.findByStatus(status, size, page * size).stream()
            .map(item -> new OutboxItem(item.getId(), item.getType(), item.getStatus(), item.getAttempts(),
                item.getCreatedAt(), item.getNextAttemptAt(), item.getDeliveredAt(), item.getDeadLetteredAt(),
                targetRequestId(item.getLinkUrl())))
            .toList();
        return new OutboxSnapshot(status, page, size, total, counts, items);
    }

    private DeliveryResult send(OfficeNotificationRequest request) {
        try {
            String baseUrl = setting("office.base-url", officeBaseUrl);
            String secret = setting("sso.shared-secret", sharedSecret);
            if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(secret)) {
                log.debug("Office notification skipped. office.base-url or sso.shared-secret is empty.");
                return DeliveryResult.failed("OFFICE_NOT_CONFIGURED");
            }
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/api/internal/deploy/notifications"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-SSO-SECRET", secret)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
            String lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                    DeliveryResult responseResult = classifyResponse(response);
                    if (responseResult.success()) {
                        return DeliveryResult.delivered();
                    }
                    lastError = responseResult.error();
                    log.warn("Office notification failed. attempt={}, category={}", attempt, lastError);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return DeliveryResult.failed("OFFICE_INTERRUPTED");
                } catch (Exception e) {
                    lastError = e instanceof HttpTimeoutException ? "OFFICE_TIMEOUT" : "OFFICE_TRANSPORT_FAILURE";
                    log.warn("Office notification attempt failed. attempt={}, category={}", attempt, lastError);
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return DeliveryResult.failed("OFFICE_INTERRUPTED");
                    }
                }
            }
            if (lastError != null) {
                log.warn("Office notification exhausted retries: {}", lastError);
            }
            return DeliveryResult.failed(lastError == null ? "OFFICE_DELIVERY_FAILED" : lastError);
        } catch (Exception e) {
            log.warn("Office notification failed. category=OFFICE_REQUEST_INVALID");
            return DeliveryResult.failed("OFFICE_REQUEST_INVALID");
        }
    }

    private DeliveryResult classifyResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return DeliveryResult.failed(status >= 100 && status <= 599 ? "OFFICE_HTTP_" + status : "OFFICE_HTTP_INVALID");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("")
            .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!(contentType.equals("application/json")
            || (contentType.startsWith("application/") && contentType.endsWith("+json")))) {
            return DeliveryResult.failed("OFFICE_RESPONSE_INVALID");
        }
        String body = response.body();
        if (!StringUtils.hasText(body) || body.length() > 8192) {
            return DeliveryResult.failed("OFFICE_RESPONSE_INVALID");
        }
        try {
            JsonNode root = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(body);
            JsonNode success = root == null || !root.isObject() ? null : root.get("success");
            return success != null && success.isBoolean() && success.booleanValue()
                ? DeliveryResult.delivered() : DeliveryResult.failed("OFFICE_RESPONSE_REJECTED");
        } catch (Exception e) {
            return DeliveryResult.failed("OFFICE_RESPONSE_INVALID");
        }
    }

    private String eventKey(OfficeNotificationRequest request) {
        StringBuilder source = new StringBuilder();
        appendEventPart(source, request.recipientOfficeUserId());
        appendEventPart(source, request.senderOfficeUserId());
        appendEventPart(source, request.type());
        appendEventPart(source, request.title());
        appendEventPart(source, request.body());
        appendEventPart(source, request.linkUrl());
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private void appendEventPart(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private String normalizeOutboxStatus(String requestedStatus) {
        String normalized = requestedStatus == null ? "DEAD_LETTER" : requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (!OUTBOX_STATUS_ORDER.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported Office notification outbox status: " + requestedStatus);
        }
        return normalized;
    }

    private Set<Long> pendingApproverIds(DeployRequest request) {
        Set<Long> ids = new LinkedHashSet<>();
        if (request != null && request.getProject() != null && request.getProject().getApprovalPolicy() != null) {
            var policy = request.getProject().getApprovalPolicy();
            List<ApprovalStep> steps = policy.getSteps();
            if ((steps == null || steps.isEmpty()) && !policy.isGroupComposed() && policy.getId() != null) {
                steps = approvalStepRepository.findByPolicyId(policy.getId());
                steps.forEach(step -> step.setApprovers(approvalStepRepository.findApproversByStepId(step.getId())));
            }
            var records = request.getApprovalRecords() == null ? List.<com.portfolio.deployops.domain.ApprovalRecord>of() : request.getApprovalRecords();
            if (steps != null) {
                for (ApprovalStep step : steps.stream().sorted(java.util.Comparator.comparingInt(ApprovalStep::getStepOrder)).toList()) {
                    Set<Long> active = new LinkedHashSet<>();
                    if (step.getApprovers() != null) step.getApprovers().stream().filter(User::isActive).map(User::getId).forEach(active::add);
                    Set<Long> approved = new LinkedHashSet<>();
                    records.stream().filter(record -> record.getAction() == com.portfolio.deployops.domain.ApprovalAction.APPROVED)
                        .filter(record -> record.getStepOrder() == step.getStepOrder() && record.getApprover() != null)
                        .map(record -> record.getApprover().getId()).filter(active::contains).forEach(approved::add);
                    if (policy.getPolicyType() == com.portfolio.deployops.domain.PolicyType.STEP
                        && approved.size() >= Math.max(step.getRequiredCount(),1)) continue;
                    active.removeAll(approved);
                    ids.addAll(active);
                    if (policy.getPolicyType() == com.portfolio.deployops.domain.PolicyType.STEP) break;
                }
            }
            // A frozen policy never falls back to unrelated global approvers.
            if (!policy.isAllowSelfApproval()) ids.remove(requesterId(request));
            return ids;
        } else {
            userRepository.findByRoleAndActiveTrue(UserRole.APPROVER).stream().map(User::getId).forEach(ids::add);
            userRepository.findByRoleAndActiveTrue(UserRole.SUPER_ADMIN).stream().map(User::getId).forEach(ids::add);
        }
        Long requesterId = requesterId(request);
        if (requesterId != null) {
            ids.remove(requesterId);
        }
        return ids;
    }

    private Long requesterId(DeployRequest request) {
        return request == null || request.getRequestedBy() == null ? null : request.getRequestedBy().getId();
    }

    private Long actorId(User actor) {
        return actor == null ? null : actor.getId();
    }

    private String detailLink(DeployRequest request) {
        String deployPath = request == null || request.getId() == null
            ? "/deploy/dashboard"
            : "/deploy/request-detail?id=" + request.getId();
        return ssoLink(deployPath);
    }

    private String projectLink(Project project) {
        String deployPath = project == null || project.getId() == null
            ? "/deploy/dashboard"
            : "/deploy/project-detail?id=" + project.getId();
        return ssoLink(deployPath);
    }

    private String ssoLink(String deployPath) {
        return "/deploy-system/sso?redirect=" + URLEncoder.encode(deployPath, StandardCharsets.UTF_8);
    }

    private String summary(DeployRequest request) {
        if (request == null) {
            return "Deploy System notification";
        }
        String projectName = request.getProject() == null ? "-" : request.getProject().getName();
        String reqNo = StringUtils.hasText(request.getReqNo()) ? request.getReqNo() : "-";
        String environment = request.getEnvironment() == null ? "-" : request.getEnvironment().name();
        String status = request.getStatus() == null ? "-" : request.getStatus().name();
        return "%s / %s / %s / %s".formatted(reqNo, projectName, environment, status);
    }

    // 알림 출처가 배포시스템임만 표시(프로젝트명은 summary 본문에 이미 포함).
    private String titlePrefix(DeployRequest request) {
        return "[배포시스템]";
    }

    private String projectTitle(Project project) {
        return "[배포시스템]";
    }

    private String mrBody(Project project, Long mergeRequestIid) {
        String iid = mergeRequestIid == null ? "-" : "!" + mergeRequestIid;
        String projectName = project == null || !StringUtils.hasText(project.getName()) ? "-" : project.getName();
        return "project=%s / mr=%s".formatted(projectName, iid);
    }

    private String eventTypeForStatus(DeployStatus status) {
        return switch (status) {
            case TEST_BUILDING, TEST_DEPLOYING, PROD_DEPLOYING -> "DEPLOY_STARTED";
            case TEST_DEPLOYED, COMPLETED -> "DEPLOY_COMPLETED";
            case TEST_FAILED, PROD_FAILED -> "DEPLOY_FAILED";
            case TEST_CONFIRMED -> "DEPLOY_TEST_CONFIRMED";
            case ROLLED_BACK -> "ROLLBACK_COMPLETED";
            default -> "DEPLOY_STATUS";
        };
    }

    private String setting(String key, String fallback) {
        String value = settingService.get(key, fallback);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static final List<String> OUTBOX_STATUS_ORDER =
        List.of("PENDING", "SENDING", "DELIVERED", "DEAD_LETTER");

    public record OutboxSnapshot(String status, int page, int size, long total, Map<String, Long> counts,
                                 List<OutboxItem> items) {
    }

    static Long targetRequestId(String link) {
        if (link == null) return null;
        String target = link;
        String prefix = "/deploy-system/sso?redirect=";
        if (link.startsWith(prefix)) {
            String encoded = link.substring(prefix.length());
            try {
                target = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                // 발급한 상대 링크 형식만 해석하고 다른 쿼리·중복 인코딩은 거부한다.
                if (!URLEncoder.encode(target, StandardCharsets.UTF_8).equals(encoded)) return null;
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
        String requestPrefix = "/deploy/request-detail?id=";
        if (!target.startsWith(requestPrefix)) return null;
        String id = target.substring(requestPrefix.length());
        if (!id.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public record OutboxItem(Long id, String type, String status, int attempts, LocalDateTime createdAt,
                             LocalDateTime nextAttemptAt, LocalDateTime deliveredAt, LocalDateTime deadLetteredAt,
                             Long targetRequestId) {
    }

    private record DeliveryResult(boolean success, String error) {
        private static DeliveryResult delivered() {
            return new DeliveryResult(true, null);
        }

        private static DeliveryResult failed(String error) {
            return new DeliveryResult(false, error == null ? "OFFICE_DELIVERY_FAILED" : error);
        }
    }
}
