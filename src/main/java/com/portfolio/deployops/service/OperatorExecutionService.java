package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 운영자 배포의 비동기 실행부.
 * 컨트롤러에 두면 @Async 자기호출(self-invocation)로 프록시를 우회해 동기 실행되므로
 * 반드시 별도 빈으로 분리해 호출한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorExecutionService {

    private final DeployRequestService deployRequestService;
    private final RemoteDeployService remoteDeployService;
    private final BuildOrchestrationService buildOrchestrationService;
    private final DeployLogService deployLogService;
    private final AuditLogService auditLogService;
    private final ManualServerExecutionService manualServerExecutionService;
    private final SecretMaskingService secretMaskingService;

    @Async
    public void executeRemoteDeployment(Long requestId, String actorName, String remoteIp, String actorRole) {
        executeDeployment(requestId, actorName, remoteIp, actorRole, false);
    }

    @Async
    public void retryRemoteDeployment(Long requestId, String actorName, String remoteIp, String actorRole) {
        executeDeployment(requestId, actorName, remoteIp, actorRole, true);
    }

    private void executeDeployment(Long requestId, String actorName, String remoteIp, String actorRole, boolean retry) {
        DeployRequest request = null;
        try {
            request = deployRequestService.findById(requestId);
            if (!retry && request.getEnvironment() == DeployEnvironment.TEST && !deployRequestService.isRollbackRequest(request)) {
                GitlabBuildArtifactMetadata verifiedArtifact = buildOrchestrationService.ensureBuildArtifact(
                    request,
                    actorRole,
                    message -> deployLogService.sendLog(requestId, message)
                );
                if (verifiedArtifact != null) {
                    request = deployRequestService.updateVerifiedBuildMetadata(requestId, verifiedArtifact);
                }
                if (request.getStatus() == DeployStatus.TEST_BUILDING) {
                    if (!deployRequestService.tryTransition(
                        requestId, DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING)) {
                        throw new RemoteExecutionException(
                            "빌드 완료 후 배포 상태를 선점하지 못했습니다. 현재 상태를 확인해 주세요."
                        );
                    }
                    request.setStatus(DeployStatus.TEST_DEPLOYING);
                }
            }
            java.util.function.Consumer<String> sink = message -> deployLogService.sendLog(requestId, message);
            DeployStatus finalStatus = resolveSuccessfulStatus(request, retry
                ? remoteDeployService.executeRetry(request, sink)
                : remoteDeployService.execute(request, sink));
            if (!deployRequestService.tryTransition(requestId, request.getStatus(), finalStatus)) {
                throw new RemoteExecutionException(
                    "배포 완료 상태를 저장하지 못했습니다. 다른 작업이 상태를 변경했는지 확인해 주세요."
                );
            }
            request.setStatus(finalStatus);
            auditLogService.record(
                actorName,
                request.getEnvironment() == DeployEnvironment.PROD ? "PROD 배포 완료" : "TEST 배포 완료",
                "DeployRequest",
                String.valueOf(requestId),
                request.getReqNo() + " / " + request.getProject().getName(),
                AuditResult.SUCCESS,
                remoteIp
            );
            deployLogService.sendStatus(requestId, finalStatus.name());
        } catch (Exception e) {
            log.error("Operator execution failed for request {}", requestId, e);
            handleExecutionFailure(requestId, actorName, remoteIp, request, e.getMessage());
        } finally {
            deployLogService.complete(requestId);
        }
    }

    @Async
    public void executeSingleServerDeployment(
        Long requestId,
        Long serverId,
        String actorName,
        String remoteIp
    ) {
        DeployRequest request = null;
        try {
            request = deployRequestService.findById(requestId);
            remoteDeployService.executeServer(
                request,
                serverId,
                message -> deployLogService.sendLog(requestId, message)
            );
            manualServerExecutionService.succeed(requestId, serverId);
            deployLogService.sendStatus(requestId, "SERVER_DONE");
            auditLogService.record(
                actorName,
                request.getEnvironment() == DeployEnvironment.PROD ? "PROD 단일 서버 배포" : "TEST 단일 서버 배포",
                "DeployRequest",
                String.valueOf(requestId),
                request.getReqNo() + " / server=" + serverId,
                AuditResult.SUCCESS,
                remoteIp
            );
        } catch (Exception e) {
            log.error("Single-server deploy failed for request {} server {}", requestId, serverId, e);
            deployLogService.sendLog(requestId, "[오류] " + e.getMessage());
            deployLogService.sendStatus(requestId, "SERVER_FAILED");
            manualServerExecutionService.fail(requestId, serverId, e.getMessage());
            String reqNo = request != null ? request.getReqNo() : String.valueOf(requestId);
            auditLogService.record(
                actorName, "단일 서버 배포 실패", "DeployRequest",
                String.valueOf(requestId), reqNo + " / server=" + serverId, AuditResult.FAIL, remoteIp
            );
        } finally {
            deployLogService.complete(requestId);
        }
    }

    /** 롤백 요청의 성공 종결 상태는 COMPLETED/TEST_DEPLOYED 대신 ROLLED_BACK. */
    public DeployStatus resolveSuccessfulStatus(DeployRequest request, DeployStatus defaultStatus) {
        if (deployRequestService.isRollbackRequest(request)
            && (defaultStatus == DeployStatus.COMPLETED || defaultStatus == DeployStatus.TEST_DEPLOYED)) {
            return DeployStatus.ROLLED_BACK;
        }
        return defaultStatus;
    }

    private void handleExecutionFailure(
        Long requestId,
        String actorName,
        String remoteIp,
        DeployRequest request,
        String message
    ) {
        DeployRequest resolvedRequest = request != null ? request : deployRequestService.findById(requestId);
        DeployStatus failedStatus = resolvedRequest.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_FAILED
            : DeployStatus.PROD_FAILED;
        DeployStatus expected = resolvedRequest.getStatus();
        String safeMessage = limitFailureReason(secretMaskingService.mask(message));
        boolean transitioned = expected != null
            && (expected == DeployStatus.TEST_BUILDING
                || expected == DeployStatus.TEST_DEPLOYING
                || expected == DeployStatus.PROD_DEPLOYING)
            && deployRequestService.tryTransitionToFailure(requestId, expected, failedStatus, safeMessage);
        deployLogService.sendLog(requestId, "[오류] " + safeMessage);
        if (transitioned) {
            deployLogService.sendStatus(requestId, failedStatus.name());
        } else {
            deployLogService.sendLog(
                requestId,
                "[상태 보호] 실행 상태가 이미 변경되어 실패 상태로 덮어쓰지 않았습니다."
            );
        }
        auditLogService.record(
            actorName,
            resolvedRequest.getEnvironment() == DeployEnvironment.PROD ? "PROD 배포 실패" : "TEST 배포 실패",
            "DeployRequest",
            String.valueOf(requestId),
            resolvedRequest.getReqNo() + " / " + resolvedRequest.getProject().getName(),
            AuditResult.FAIL,
            remoteIp
        );
    }

    private String limitFailureReason(String message) {
        if (message == null || message.isBlank()) {
            return "실행 중 오류가 발생했습니다. 서버 로그를 확인하세요.";
        }
        return message.length() <= 1800 ? message : message.substring(0, 1800);
    }
}
