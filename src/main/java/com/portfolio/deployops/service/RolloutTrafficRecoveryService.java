package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RolloutTrafficRecoveryService {
    private final DeployRequestService requests;
    private final RemoteDeployService remote;
    private final DeployLogService logs;
    private final AuditLogService audit;

    @Async
    public void recover(Long id, DeployStatus failureState, String actor, String ip) {
        DeployRequest request = null;
        String result = "트래픽 복구 후 실패 대상 재시도로 배포 상태를 확인하세요.";
        try {
            request = requests.findById(id);
            remote.recoverTraffic(request, message -> logs.sendLog(id, message));
            audit.record(actor, "배포 트래픽 복구 완료", "DeployRequest", id.toString(), request.getReqNo(), AuditResult.SUCCESS, ip);
        } catch (RuntimeException error) {
            result = "트래픽 복구 실패: " + error.getMessage();
            logs.sendLog(id, result);
            audit.record(actor, "배포 트래픽 복구 실패", "DeployRequest", id.toString(), result, AuditResult.FAIL, ip);
        } finally {
            DeployStatus executing = failureState == DeployStatus.TEST_FAILED ? DeployStatus.TEST_DEPLOYING : DeployStatus.PROD_DEPLOYING;
            try {
                if (requests.tryTransitionToFailure(id, executing, failureState, result)) logs.sendStatus(id, failureState.name());
                else logs.sendLog(id, "요청 상태가 변경되어 복구 종료 상태를 덮어쓰지 않았습니다. 현재 요청 상태를 다시 확인하세요.");
            } finally { logs.complete(id); }
        }
    }
}
