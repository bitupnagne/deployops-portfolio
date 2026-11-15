package com.portfolio.deployops.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NexusRestoreReconciler {
    private final NexusRestoreService service;
    private final NexusRestoreJobGateway gateway;

    @Scheduled(fixedDelayString = "${deployops.nexus-backup.poll-ms:5000}")
    public void poll() {
        for(var check:service.unfinishedChecks()){
            try{
                var request=service.require(check.getRequestId());
                if(NexusRestoreJobGateway.EXISTING_CLONE_CHECK.equals(check.getReasonCode())){
                    var existing=gateway.existingCloneCheckResult(check.getRequestId(),check.getId());
                    if(existing!=null){service.acceptExistingCloneCheck(check.getRequestId(),check.getId(),existing);continue;}
                    if("SUBMITTED".equals(check.getStatus())||"QUEUED".equals(check.getStatus())&&service.markCheckSubmitted(check.getId())){
                        gateway.submitExistingCloneCheck(request.getId(),check.getId(),request.getBackupRequestId(),request.getManifestSha256());
                        var ready=gateway.existingCloneCheckResult(check.getRequestId(),check.getId());
                        if(ready!=null)service.acceptExistingCloneCheck(check.getRequestId(),check.getId(),ready);
                    }
                    continue;
                }
                if(check.getReasonCode()==null&&"QUEUED".equals(check.getStatus())&&service.markCheckSubmitted(check.getId()))
                    gateway.submitRecoveryCheck(request.getId(),check.getId(),request.getBackupRequestId(),request.getManifestSha256());
                var result=gateway.recoveryCheckResult(check.getRequestId(),check.getId());
                if(result!=null)service.acceptRecoveryCheck(check.getRequestId(),check.getId(),result);
            }catch(Exception ambiguous){
                // 새 접수·복원 재실행은 하지 않는다. 기존 clone 확인만 동일 token의 정확한 inbox 게시를 재개한다.
            }
        }
        for (var request : service.unfinished()) {
            if(request.getActiveCheckId()!=null||request.getLastCheckStatus()!=null)continue;
            try {
                if (!request.isSubmitted() && service.submit(request.getId())) {
                    gateway.submit(request.getId(), request.getBackupRequestId(), request.getManifestSha256());
                }
                var result = gateway.result(request.getId());
                if (result != null) {
                    service.accept(request.getId(), result);
                }
            } catch (NexusRestoreJobGateway.PublicationNotStarted definitePrewriteRejection) {
                try {
                    service.publicationRejected(request.getId());
                } catch (Exception databaseFailure) {
                    // Transaction failure retains the durable reservation.
                }
            } catch (Exception ambiguousFailure) {
                try {
                    service.uncertain(request.getId());
                } catch (Exception databaseFailure) {
                    // Never release an uncertain publication or replay the job.
                }
            }
        }
    }
}
