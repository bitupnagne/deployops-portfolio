package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NexusBackupService {
    private final NexusBackupRequestRepository repository;
    private final ExecutionGuardRepository guard;
    private final DeployRequestRepository deployments;
    private final NexusBackupJobGateway gateway;
    private final AuditLogService audit;

    public boolean ready(){ return gateway.ready(); }
    public String readinessCode(){return gateway.readinessCode();}
    @Transactional(readOnly=true) public List<NexusBackupRequest> recent(){ return repository.findRecent(); }
    @Transactional(readOnly=true) public NexusBackupRequest require(Long id){ return repository.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"백업 요청을 찾을 수 없습니다.")); }
    @Transactional public NexusBackupRequest create(String reason,boolean externalStopped,Long actor) {
        guard.lockSingleton();
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>1000||!externalStopped) throw error(HttpStatus.BAD_REQUEST,"백업 사유와 외부 이미지 게시 작업 중지 확인이 필요합니다.");
        if(!gateway.ready()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"Nexus 백업 실행기가 준비되지 않았습니다. 관리자가 유지보수 설정을 확인해 주세요.");
        NexusBackupRequest r=new NexusBackupRequest();r.setRequestedById(actor);r.setReason(reason.trim());r.setStatus("PENDING");r.setStage("PREFLIGHT");
        repository.insert(r);
        audit.recordCurrentRequired("Nexus 백업 요청","NexusBackup",String.valueOf(r.getId()),"외부 게시 작업 중지 확인",AuditResult.SUCCESS);
        return require(r.getId());
    }
    @Transactional public NexusBackupRequest approve(Long id,Long actor,boolean externalStopped) {
        if(!externalStopped) throw error(HttpStatus.BAD_REQUEST,"외부 이미지 게시 작업 중지를 확인해 주세요.");
        if(!gateway.ready()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"Nexus 백업 실행기가 준비되지 않았습니다.");
        if(guard.lockSingleton()==null) throw error(HttpStatus.SERVICE_UNAVAILABLE,"실행 잠금 정보를 찾을 수 없습니다.");
        guard.assertNoNexusMaintenance();
        NexusBackupRequest r=require(id);
        if(actor.equals(r.getRequestedById())) throw error(HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.");
        if(deployments.countByStatusIn(List.of(DeployStatus.TEST_BUILDING,DeployStatus.TEST_DEPLOYING,DeployStatus.PROD_DEPLOYING))>0||guard.activeNexusCleanupCount()>0) throw error(HttpStatus.CONFLICT,"진행 중인 빌드·배포·이미지 정리가 끝난 뒤 승인해 주세요.");
        if(repository.approve(id,actor)!=1||guard.reserveNexusMaintenance(id)!=1) throw error(HttpStatus.CONFLICT,"이미 처리되었거나 유지보수를 선점할 수 없는 요청입니다.");
        audit.recordCurrentRequired("Nexus 백업 승인","NexusBackup",String.valueOf(id),"유지보수 선점·백업 대기",AuditResult.SUCCESS);
        return require(id);
    }
    @Transactional public void reject(Long id,Long actor,String reason) {
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>1000) throw error(HttpStatus.BAD_REQUEST,"반려 사유를 입력해 주세요.");
        if(actor.equals(require(id).getRequestedById())) throw error(HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.");
        if(repository.reject(id,actor,reason.trim())!=1) throw error(HttpStatus.CONFLICT,"이미 처리된 요청입니다.");
        audit.recordCurrentRequired("Nexus 백업 반려","NexusBackup",String.valueOf(id),reason.trim(),AuditResult.SUCCESS);
    }
    @Transactional public boolean markSubmitted(Long id) {
        if(guard.lockSingleton()==null||!id.equals(guard.nexusMaintenanceOwner())) throw error(HttpStatus.CONFLICT,"백업 유지보수 선점 정보가 다릅니다.");
        return repository.markSubmitted(id)==1;
    }
    @Transactional public void acceptResult(Long id,NexusBackupJobGateway.SafeResult result) {
        if(guard.lockSingleton()==null||!id.equals(guard.nexusMaintenanceOwner())) throw error(HttpStatus.CONFLICT,"백업 유지보수 선점 정보가 다릅니다.");
        NexusBackupRequest current=require(id);
        if(Objects.equals(current.getStatus(),result.status())&&Objects.equals(current.getStage(),result.stage())
                &&Objects.equals(current.getArtifactId(),result.artifactId())&&Objects.equals(current.getManifestSha256(),result.manifestSha256())
                &&Objects.equals(current.getErrorCode(),result.errorCode())&&Objects.equals(current.getCompletedAt(),result.completedAt())) return;
        NexusBackupRequest r=new NexusBackupRequest();r.setId(id);r.setStatus(result.status());r.setStage(result.stage());r.setArtifactId(result.artifactId());r.setManifestSha256(result.manifestSha256());r.setErrorCode(result.errorCode());r.setCompletedAt(result.completedAt());
        if(repository.updateResult(r)!=1) return;
        boolean terminal=result.status().equals("COMPLETED")||result.status().equals("FAILED");
        if(terminal||result.status().equals("RECOVERY_REQUIRED")) {
            audit.recordCurrentRequired("Nexus 백업 결과","NexusBackup",String.valueOf(id),result.status()+" · "+result.stage(),result.status().equals("COMPLETED")?AuditResult.SUCCESS:AuditResult.FAIL);
            if(terminal&&result.originalStateRestored()) guard.releaseNexusMaintenance(id);
        }
    }
    @Transactional(readOnly=true) public List<NexusBackupRequest> unfinished(){return repository.findUnfinished();}
    @Transactional public NexusBackupRecoveryCheck requestRecoveryCheck(Long id,Long actor) {
        requireCheckRunnerReady();
        if(guard.lockSingleton()==null||!id.equals(guard.nexusMaintenanceOwner())) throw error(HttpStatus.CONFLICT,"원본 상태 확인에 필요한 유지보수 선점 정보가 다릅니다.");
        NexusBackupRequest r=require(id);
        if(!r.isSubmitted()||!List.of("RECOVERY_REQUIRED","RECONCILING").contains(r.getStatus())||r.getActiveCheckId()!=null) throw error(HttpStatus.CONFLICT,"상태 재확인이 불가능하거나 이미 확인 중인 요청입니다.");
        NexusBackupRecoveryCheck check=new NexusBackupRecoveryCheck();check.setRequestId(id);check.setRequestedById(actor);check.setStatus("QUEUED");
        repository.insertCheck(check);
        if(repository.activateCheck(id,check.getId())!=1) throw error(HttpStatus.CONFLICT,"이미 원본 상태를 확인 중입니다.");
        audit.recordCurrentRequired("Nexus 원본 상태 재확인 요청","NexusBackup",String.valueOf(id),"확인 #"+check.getId()+" · 원본 변경 없이 확인",AuditResult.SUCCESS);
        return check;
    }
    @Transactional public NexusBackupRecoveryCheck supersedeRecoveryCheck(Long id,Long oldCheckId,Long actor) {
        requireCheckRunnerReady();
        if(guard.lockSingleton()==null||!id.equals(guard.nexusMaintenanceOwner())) throw error(HttpStatus.CONFLICT,"원본 상태 확인에 필요한 유지보수 선점 정보가 다릅니다.");
        NexusBackupRequest r=require(id);
        if(!r.isSubmitted()||!List.of("RECOVERY_REQUIRED","RECONCILING").contains(r.getStatus())||!oldCheckId.equals(r.getActiveCheckId())) throw error(HttpStatus.CONFLICT,"대체하려는 상태 확인 요청이 현재 요청과 다릅니다. 새로 조회해 주세요.");
        if(repository.supersedeCheck(oldCheckId,id)!=1) throw error(HttpStatus.CONFLICT,"이미 결과가 반영되었거나 대체된 확인 요청입니다.");
        NexusBackupRecoveryCheck check=new NexusBackupRecoveryCheck();check.setRequestId(id);check.setRequestedById(actor);check.setStatus("QUEUED");
        repository.insertCheck(check);
        if(repository.replaceCheck(id,oldCheckId,check.getId())!=1) throw error(HttpStatus.CONFLICT,"다른 상태 확인 요청이 먼저 접수되었습니다.");
        audit.recordCurrentRequired("Nexus 원본 상태 재확인 대체","NexusBackup",String.valueOf(id),"확인 #"+oldCheckId+" 미확정 종료 → 새 확인 #"+check.getId()+" · 차단 유지·원본 변경 없음",AuditResult.SUCCESS);
        return check;
    }
    private void requireCheckRunnerReady() {
        if(!gateway.ready()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"원본 상태 확인 실행기가 준비되지 않았습니다. 관리자가 실행기 설정을 확인해 주세요. 유지보수 차단은 유지됩니다.");
    }
    @Transactional(readOnly=true) public List<NexusBackupRecoveryCheck> unfinishedChecks(){return repository.unfinishedChecks();}
    @Transactional public boolean markCheckSubmitted(Long checkId) {
        NexusBackupRecoveryCheck check=repository.findCheck(checkId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"상태 확인 요청을 찾을 수 없습니다."));
        if(guard.lockSingleton()==null||!check.getRequestId().equals(guard.nexusMaintenanceOwner())||!checkId.equals(require(check.getRequestId()).getActiveCheckId())) throw error(HttpStatus.CONFLICT,"상태 확인 접수 정보가 다릅니다.");
        return repository.submitCheck(checkId)==1;
    }
    @Transactional public void acceptRecoveryCheck(Long id,Long checkId,NexusBackupJobGateway.SafeResult result) {
        if(guard.lockSingleton()==null||!id.equals(guard.nexusMaintenanceOwner())) return;
        NexusBackupRequest r=require(id);
        NexusBackupRecoveryCheck check=repository.findCheck(checkId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"상태 확인 요청을 찾을 수 없습니다."));
        if(!id.equals(check.getRequestId())||!checkId.equals(r.getActiveCheckId())||!"SUBMITTED".equals(check.getStatus())) return;
        boolean restored="FAILED".equals(result.status())&&"DONE".equals(result.stage())&&result.originalStateRestored();
        check.setStatus(restored?"RESTORED":"UNCONFIRMED");check.setCompletedAt(result.completedAt());
        if(repository.finishCheck(check)!=1) return;
        audit.recordCurrentRequired("Nexus 원본 상태 재확인 결과","NexusBackup",String.valueOf(id),"확인 #"+checkId+(restored?" · 원본 상태 복구 확인":" · 원본 상태 미확정·차단 유지"),restored?AuditResult.SUCCESS:AuditResult.FAIL);
        if(restored) {
            r.setStatus("FAILED");r.setStage("DONE");r.setErrorCode("BACKUP_FAILED");r.setCompletedAt(result.completedAt());
            // 재확인은 백업 완료/새 artifact를 증명하지 않는다.
            r.setArtifactId(null);r.setManifestSha256(null);
            if(repository.updateResult(r)!=1) throw error(HttpStatus.CONFLICT,"상태 확인 결과를 반영할 수 없습니다.");
        }
        if(repository.clearCheck(id,checkId)!=1) throw error(HttpStatus.CONFLICT,"상태 확인 선점 정보가 다릅니다.");
        if(restored&&guard.releaseNexusMaintenance(id)!=1) throw error(HttpStatus.CONFLICT,"유지보수 선점 해제를 확정할 수 없습니다.");
    }
    @Transactional public void markUncertain(Long id) {
        if(guard.lockSingleton()==null||!id.equals(guard.nexusMaintenanceOwner())) return;
        NexusBackupRequest r=require(id);
        if("RECOVERY_REQUIRED".equals(r.getStatus())) return;
        r.setStatus("RECONCILING");r.setErrorCode("RESULT_UNCERTAIN");
        repository.updateResult(r);
    }
    private ResponseStatusException error(HttpStatus status,String text){return new ResponseStatusException(status,text);}
}
