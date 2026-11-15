package com.portfolio.deployops.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NexusBackupReconciler {
    private final NexusBackupService service;
    private final NexusBackupJobGateway gateway;
    @Scheduled(fixedDelayString="${deployops.nexus-backup.poll-ms:5000}")
    public void poll(){
        for(var check:service.unfinishedChecks()) {
            try {
                if("QUEUED".equals(check.getStatus())&&service.markCheckSubmitted(check.getId())) gateway.submitRecoveryCheck(check.getRequestId(),check.getId());
                var result=gateway.recoveryCheckResult(check.getRequestId(),check.getId());
                if(result!=null) service.acceptRecoveryCheck(check.getRequestId(),check.getId(),result);
            } catch(Exception uncertain) {
                // SUBMITTED 접수를 보존한다. 게시·결과 오류에도 자동 재게시하거나 유지보수 선점을 해제하지 않는다.
            }
        }
        for(var request:service.unfinished()) {
            if(request.getActiveCheckId()!=null) continue; // 이전 백업 결과를 현재 check 결과로 오인하지 않는다.
            try {
                if(!request.isSubmitted()&&service.markSubmitted(request.getId())) gateway.submit(request.getId());
                var result=gateway.result(request.getId());
                if(result!=null) service.acceptResult(request.getId(),result);
            } catch(Exception ex) {
                // 접수·결과가 불명확하면 durable RECONCILING과 유지보수 선점을 보존한다. 자동 재제출하지 않는다.
                try { service.markUncertain(request.getId()); } catch(Exception unavailable) {
                    // DB 장애 동안 기존 durable reservation도 해제하지 않는다.
                }
            }
        }
    }
}
