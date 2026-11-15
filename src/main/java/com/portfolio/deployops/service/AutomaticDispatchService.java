package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.DeploymentReadyEvent;
import com.portfolio.deployops.domain.ExecutionTrigger;
import com.portfolio.deployops.repository.DeployRequestRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/** 준비 완료 이벤트와 보정 스캔을 동일한 CAS 실행 관문으로 연결한다. */
@Service
@RequiredArgsConstructor
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "false", matchIfMissing = true)
public class AutomaticDispatchService {

    public static final String SYSTEM_ACTOR = "SYSTEM_AUTOMATION";
    public static final String SYSTEM_IP = "(system)";
    private static final List<DeployStatus> READY_STATUSES = List.of(
        DeployStatus.TEST_REQUESTED,
        DeployStatus.APPROVAL_COMPLETED
    );
    private static final int SCAN_BATCH_SIZE = 100;

    private final DeployRequestRepository deployRequestRepository;
    private final DeployRequestService deployRequestService;
    private final ExecutionPolicyService executionPolicyService;
    private final OperatorExecutionService operatorExecutionService;
    private final AuditLogService auditLogService;
    private final AtomicLong scanCursor = new AtomicLong(0);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeploymentReady(DeploymentReadyEvent event) {
        if (event != null && event.deployRequestId() != null
            && (executionPolicyService.autoDispatchTest() || executionPolicyService.autoDispatchProd())) {
            dispatchSafely(event.deployRequestId());
        }
    }

    /** 이벤트 유실·재기동·일시적인 동시 실행 제한을 복구한다. CAS 때문에 중복 실행되지 않는다. */
    @Scheduled(fixedDelayString = "${app.deploy.auto-dispatch-scan-ms:15000}")
    public void scanMissedReadyEvents() {
        if (!executionPolicyService.autoDispatchTest() && !executionPolicyService.autoDispatchProd()) {
            return;
        }
        long afterId = scanCursor.get();
        List<DeployRequest> candidates = deployRequestRepository.findAutoDispatchCandidatesAfterId(
            READY_STATUSES, afterId, SCAN_BATCH_SIZE);
        if (candidates.isEmpty()) {
            scanCursor.set(0);
            return;
        }
        candidates.forEach(request -> dispatchSafely(request.getId()));
        long lastId = candidates.get(candidates.size() - 1).getId();
        scanCursor.set(candidates.size() < SCAN_BATCH_SIZE ? 0 : lastId);
    }

    void dispatchIfEligible(Long requestId) {
        DeployRequest request = deployRequestService.findById(requestId);
        DeployStatus expected = readyStatus(request);
        if (expected == null || !isEnabled(request.getEnvironment())
            || request.getAutoDispatchBlockedAt() != null
            || (request.getDeployStrategy() != DeployStrategy.SAFE
                && request.getDeployStrategy() != DeployStrategy.WAVE)) {
            return;
        }

        DeployStatus start = startStatus(request);
        try {
            deployRequestService.assertProductionRollbackProvenance(request);
        } catch (ResponseStatusException ex) {
            blockPermanentPlanFailure(request, expected, ex.getReason());
            return;
        }
        try {
            if (!executionPolicyService.tryStart(request, expected, start, ExecutionTrigger.AUTO)) {
                return;
            }
        } catch (AutomaticDispatchPlanBlockedException ex) {
            blockPermanentPlanFailure(request, expected, ex.getMessage());
            return;
        }

        try {
            auditLogService.record(
                SYSTEM_ACTOR,
                request.getEnvironment() == DeployEnvironment.PROD ? "PROD 자동 배포 시작" : "TEST 자동 배포 시작",
                "DeployRequest",
                String.valueOf(requestId),
                auditLogService.describeDeployRequest(request),
                AuditResult.SUCCESS,
                SYSTEM_IP
            );
            operatorExecutionService.executeRemoteDeployment(
                requestId, SYSTEM_ACTOR, SYSTEM_IP, SYSTEM_ACTOR);
        } catch (RuntimeException ex) {
            markDispatchFailure(request, ex);
        }
    }

    private void blockPermanentPlanFailure(DeployRequest request, DeployStatus expected, String message) {
        String reason = concise(message);
        if (deployRequestRepository.blockAutomaticDispatch(request.getId(), expected, reason) != 1) {
            return;
        }
        auditLogService.record(
            SYSTEM_ACTOR,
            "자동 배포 계획 검증 차단",
            "DeployRequest",
            String.valueOf(request.getId()),
            auditLogService.describeDeployRequest(request) + " | " + reason,
            AuditResult.FAIL,
            SYSTEM_IP
        );
    }

    private void dispatchSafely(Long requestId) {
        try {
            dispatchIfEligible(requestId);
        } catch (Exception ex) {
            // 시간 제한·Freeze·동시 수 제한 같은 일시 조건은 상태를 소비하지 않고 재평가한다.
            // snapshot 누락·plan drift 같은 영구 오류는 dispatchIfEligible에서 별도 차단한다.
            log.warn("Automatic dispatch deferred requestId={}: {}", requestId, ex.getMessage());
        }
    }

    private void markDispatchFailure(DeployRequest request, RuntimeException cause) {
        DeployStatus failed = request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_FAILED : DeployStatus.PROD_FAILED;
        try {
            if (!deployRequestService.tryTransition(request.getId(), request.getStatus(), failed)) {
                log.warn("자동 실행 예약 실패 상태가 이미 변경되었습니다. requestId={} status={}",
                    request.getId(), request.getStatus());
            }
        } catch (Exception updateError) {
            log.error("자동 실행 예약 실패 상태 기록도 실패했습니다. requestId={}", request.getId(), updateError);
        }
        try {
            auditLogService.record(
                SYSTEM_ACTOR,
                request.getEnvironment() == DeployEnvironment.PROD ? "PROD 자동 배포 시작 실패" : "TEST 자동 배포 시작 실패",
                "DeployRequest",
                String.valueOf(request.getId()),
                auditLogService.describeDeployRequest(request) + " | " + concise(cause.getMessage()),
                AuditResult.FAIL,
                SYSTEM_IP
            );
        } catch (Exception auditError) {
            log.error("자동 실행 실패 감사로그 기록에 실패했습니다. requestId={}", request.getId(), auditError);
        }
    }

    private boolean isEnabled(DeployEnvironment environment) {
        return environment == DeployEnvironment.TEST
            ? executionPolicyService.autoDispatchTest()
            : environment == DeployEnvironment.PROD && executionPolicyService.autoDispatchProd();
    }

    private DeployStatus readyStatus(DeployRequest request) {
        if (request.getEnvironment() == DeployEnvironment.TEST
            && request.getStatus() == DeployStatus.TEST_REQUESTED) {
            return DeployStatus.TEST_REQUESTED;
        }
        if (request.getEnvironment() == DeployEnvironment.PROD
            && request.getStatus() == DeployStatus.APPROVAL_COMPLETED) {
            return DeployStatus.APPROVAL_COMPLETED;
        }
        return null;
    }

    private DeployStatus startStatus(DeployRequest request) {
        if (request.getEnvironment() == DeployEnvironment.PROD) {
            return DeployStatus.PROD_DEPLOYING;
        }
        return deployRequestService.isRollbackRequest(request)
            || (request.getImageTag() != null && !request.getImageTag().isBlank())
            ? DeployStatus.TEST_DEPLOYING
            : DeployStatus.TEST_BUILDING;
    }

    private String concise(String message) {
        if (message == null || message.isBlank()) {
            return "원인 미상";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "..." : compact;
    }
}
