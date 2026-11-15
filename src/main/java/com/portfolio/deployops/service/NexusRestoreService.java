package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NexusRestoreService {
    private final NexusRestoreRequestRepository repository;
    private final NexusBackupRequestRepository backups;
    private final ExecutionGuardRepository guard;
    private final DeployRequestRepository deployments;
    private final NexusRestoreJobGateway gateway;
    private final AuditLogService audit;
    private static final Set<String> CHECKLIST = Set.of("LOGIN","H2_LOCAL","FILE_BLOB_LOCAL","TASKS","USERS_ROLES_REALMS","REPOSITORIES");
    public boolean ready() {
        return gateway.ready();
    }
    @Transactional(readOnly = true)
    public List<NexusRestoreRequest> recent() {
        return repository.recent();
    }
    @Transactional(readOnly = true)
    public NexusRestoreRequest require(Long id) {
        return repository.find(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"격리복원 요청을 찾을 수 없습니다."));
    }
    private NexusBackupRequest completed(Long id) {
        var b = backups.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"백업을 찾을 수 없습니다."));
        if (!"COMPLETED".equals(b.getStatus())
                  ||  !Objects.equals(b.getArtifactId(),"nexus-backup-"+id)
                 || b.getManifestSha256() == null
                  ||  !b.getManifestSha256().matches("[0-9a-f]{64}")) throw error(HttpStatus.CONFLICT,"완료된 불변 백업만 선택할 수 있습니다.");
        return b;
    }
    @Transactional
    public NexusRestoreRequest create(NexusRestoreForm form, Long actor) {
        if (!gateway.ready()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"격리복원 실행기가 준비되지 않았습니다.");
        var backup = completed(form.backupRequestId());
        var r = new NexusRestoreRequest();
        r.setBackupRequestId(backup.getId());
        r.setArtifactId(backup.getArtifactId());
        r.setManifestSha256(backup.getManifestSha256());
        r.setReason(form.reason().trim());
        r.setRequestedById(actor);
        if (repository.insert(r) != 1) throw error(HttpStatus.CONFLICT,"복원 요청 저장을 확정할 수 없습니다.");
        audit.recordCurrentRequired("Nexus 격리복원 요청","NexusRestore",r.getId().toString(),"완료 백업 #"+backup.getId(),AuditResult.SUCCESS);
        return require(r.getId());
    }
    @Transactional
    public NexusRestoreRequest approve(Long id, Long actor) {
        var r = require(id);
        var b = completed(r.getBackupRequestId());
        if (!r.getManifestSha256().equals(b.getManifestSha256())
                  ||  !r.getArtifactId().equals(b.getArtifactId())) throw error(HttpStatus.CONFLICT,"불변 백업 정보가 다릅니다.");
        if (actor.equals(r.getRequestedById())) throw error(HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.");
        if (!gateway.ready()  ||  guard.lockSingleton() == null) throw error(HttpStatus.SERVICE_UNAVAILABLE,"격리복원 실행기를 확인해 주세요.");
        guard.assertNoNexusMaintenance();
        if (deployments.countByStatusIn(List.of(DeployStatus.TEST_BUILDING,DeployStatus.TEST_DEPLOYING,DeployStatus.PROD_DEPLOYING))>0
                  ||  guard.activeNexusCleanupCount()>0) throw error(HttpStatus.CONFLICT,"진행 중인 작업이 끝난 뒤 승인해 주세요.");
        if (repository.approve(id,actor) != 1  ||  guard.reserveNexusRestore(id) != 1) throw error(HttpStatus.CONFLICT,"이미 처리되었거나 다른 작업이 진행 중입니다.");
        audit.recordCurrentRequired("Nexus 격리복원 승인","NexusRestore",id.toString(),"원본 비접촉·격리 실행 대기",AuditResult.SUCCESS);
        return require(id);
    }
    @Transactional
    public void reject(Long id, Long actor, String reason) {
        if (reason == null || reason.trim().isEmpty() || reason.trim().length()>1000) throw error(HttpStatus.BAD_REQUEST,"반려 사유를 입력해 주세요.");
        if (actor.equals(require(id).getRequestedById())) throw error(HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.");
        if (repository.reject(id,actor,reason.trim()) != 1) throw error(HttpStatus.CONFLICT,"이미 처리된 요청입니다.");
        audit.recordCurrentRequired("Nexus 격리복원 반려","NexusRestore",id.toString(),reason.trim(),AuditResult.SUCCESS);
    }
    @Transactional(readOnly = true)
    public List<NexusRestoreRequest> unfinished() {
        return repository.unfinished();
    }
    @Transactional
    public NexusRestoreRecoveryCheck requestRecoveryCheck(Long id,Long actor){
        return requestCheck(id,actor,false);
    }
    @Transactional
    public NexusRestoreRecoveryCheck requestExistingCloneCheck(Long id,Long actor){
        return requestCheck(id,actor,true);
    }
    private NexusRestoreRecoveryCheck requestCheck(Long id,Long actor,boolean existingClone){
        if(!gateway.ready())throw error(HttpStatus.SERVICE_UNAVAILABLE,"상태 재확인 실행기가 준비되지 않았습니다. 차단은 유지됩니다.");
        if(guard.lockSingleton()==null||!id.equals(guard.nexusRestoreOwner()))throw error(HttpStatus.CONFLICT,"격리복원 예약 정보가 다릅니다.");
        var request=require(id);
        if(!request.isSubmitted()||!Set.of("RECOVERY_REQUIRED","RECONCILING").contains(request.getStatus())
                ||request.getActiveCheckId()!=null||request.getCloneContainerId()!=null)
            throw error(HttpStatus.CONFLICT,existingClone?"기존 복원본의 준비 상태를 확인할 수 없거나 이미 확인 중인 요청입니다.":"실행 전 실패 상태를 확인할 수 없거나 이미 확인 중인 요청입니다.");
        if(existingClone&&!existingCloneEligible(request))throw error(HttpStatus.CONFLICT,"미확정 결과이며 복원본 정보가 미제공인 요청만 확인할 수 있습니다.");
        var check=new NexusRestoreRecoveryCheck();check.setRequestId(id);check.setRequestedById(actor);check.setStatus("QUEUED");
        if(existingClone)check.setReasonCode(NexusRestoreJobGateway.EXISTING_CLONE_CHECK);
        if(repository.insertCheck(check)!=1||repository.activateCheck(id,check.getId())!=1)throw error(HttpStatus.CONFLICT,"이미 상태를 확인 중입니다.");
        audit.recordCurrentRequired(existingClone?"Nexus 기존 복원본 준비 상태 확인 요청":"Nexus 격리복원 실패 상태 재확인 요청","NexusRestore",id.toString(),"확인 #"+check.getId()+" · 복원 재실행·자원 삭제 없음",AuditResult.SUCCESS);
        return check;
    }
    @Transactional(readOnly=true)
    public List<NexusRestoreRecoveryCheck> unfinishedChecks(){return repository.unfinishedChecks();}
    @Transactional
    public boolean markCheckSubmitted(Long checkId){
        var check=repository.findCheck(checkId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"상태 확인 요청을 찾을 수 없습니다."));
        if(guard.lockSingleton()==null||!check.getRequestId().equals(guard.nexusRestoreOwner())
                ||!checkId.equals(require(check.getRequestId()).getActiveCheckId()))throw error(HttpStatus.CONFLICT,"상태 확인 접수 정보가 다릅니다.");
        return repository.submitCheck(checkId)==1;
    }
    @Transactional
    public void acceptRecoveryCheck(Long id,Long checkId,NexusRestoreJobGateway.CheckResult result){
        if(guard.lockSingleton()==null||!id.equals(guard.nexusRestoreOwner()))return;
        var request=require(id);
        var check=repository.findCheck(checkId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"상태 확인 요청을 찾을 수 없습니다."));
        if(!id.equals(check.getRequestId())||!checkId.equals(request.getActiveCheckId())||!"SUBMITTED".equals(check.getStatus()))return;
        if(check.getReasonCode()!=null)throw error(HttpStatus.CONFLICT,"기존 clone 확인 요청은 실패 상태 결과로 처리할 수 없습니다.");
        if(!request.isSubmitted()||!Set.of("RECOVERY_REQUIRED","RECONCILING").contains(request.getStatus())
                ||request.getBackupRequestId()!=result.backupRequestId()||!request.getManifestSha256().equals(result.manifestSha256())
                ||request.getCloneContainerId()!=null||result.completedAt()==null)throw error(HttpStatus.CONFLICT,"현재 요청과 상태 확인 결과의 식별이 다릅니다.");
        boolean verified="VERIFIED_FAILED".equals(result.status())&&"PRECONTAINER_FAILURE_CONFIRMED".equals(result.reasonCode())
                &&result.originalProjectionPreserved()&&!result.mutationUncertain();
        if(!verified&&!("UNCONFIRMED".equals(result.status())&&"STATE_UNCONFIRMED".equals(result.reasonCode())&&result.mutationUncertain()))
            throw error(HttpStatus.CONFLICT,"실행 전 실패 확인 증거가 올바르지 않습니다.");
        check.setStatus(result.status());check.setReasonCode(result.reasonCode());check.setCompletedAt(result.completedAt());
        if(repository.finishCheck(check)!=1)return;
        audit.recordCurrentRequired("Nexus 격리복원 실패 상태 재확인 결과","NexusRestore",id.toString(),
                "확인 #"+checkId+(verified?" · 실행 전 실패·원본 보존 확인":" · 상태 미확정·차단 유지"),verified?AuditResult.SUCCESS:AuditResult.FAIL);
        if(verified){
            // 실패 종료이며 clone 기능검증이나 복원 성공을 증명하지 않는다.
            request.setStatus("FAILED");request.setStage("DONE");request.setErrorCode("RESTORE_FAILED");
            request.setOriginalProjectionPreserved(true);request.setMutationUncertain(false);request.setCompletedAt(result.completedAt());
            if(repository.result(request)!=1)throw error(HttpStatus.CONFLICT,"실패 종료 상태를 확정할 수 없습니다.");
        }
        if(repository.clearCheck(id,checkId)!=1)throw error(HttpStatus.CONFLICT,"상태 확인 접수 종료를 확정할 수 없습니다.");
        if(verified&&guard.releaseNexusRestore(id)!=1)throw error(HttpStatus.CONFLICT,"격리복원 예약 해제를 확정할 수 없습니다.");
    }
    @Transactional
    public void acceptExistingCloneCheck(Long id,Long checkId,NexusRestoreJobGateway.ReadinessCheckResult result){
        if(guard.lockSingleton()==null||!id.equals(guard.nexusRestoreOwner()))return;
        var request=require(id);
        var check=repository.findCheck(checkId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"상태 확인 요청을 찾을 수 없습니다."));
        if(!id.equals(check.getRequestId())||!checkId.equals(request.getActiveCheckId())||!"SUBMITTED".equals(check.getStatus()))return;
        if(!NexusRestoreJobGateway.EXISTING_CLONE_CHECK.equals(check.getReasonCode()))throw error(HttpStatus.CONFLICT,"기존 clone 준비 확인 요청이 아닙니다.");
        var proof=result.check();var primary=result.primary();
        if(!request.isSubmitted()||!existingCloneEligible(request)
                ||request.getCloneContainerId()!=null||request.getBackupRequestId()!=proof.backupRequestId()
                ||!request.getManifestSha256().equals(proof.manifestSha256())||proof.completedAt()==null)
            throw error(HttpStatus.CONFLICT,"현재 요청과 clone 확인 결과의 식별이 다릅니다.");
        boolean verified="VERIFIED_HEALTH_READY".equals(proof.status())&&"EXISTING_CLONE_ACCESS_CONFIRMED".equals(proof.reasonCode())
                &&proof.originalProjectionPreserved()&&!proof.mutationUncertain();
        if(verified){
            if(primary==null||!"HEALTH_READY".equals(primary.status())||!"DONE".equals(primary.stage())
                    ||!primary.originalProjectionPreserved()||primary.mutationUncertain()||primary.errorCode()!=null
                    ||primary.backupRequestId()!=proof.backupRequestId()||!primary.manifestSha256().equals(proof.manifestSha256())
                    ||primary.cloneContainerId()==null||primary.cloneAccess()==null||primary.completedAt()==null
                    ||result.evidenceSha256()==null||!result.evidenceSha256().matches("[0-9a-f]{64}")
                    ||result.primarySha256()==null||!result.primarySha256().matches("[0-9a-f]{64}"))
                throw error(HttpStatus.CONFLICT,"일치하는 원본 보존·clone 준비 증거가 필요합니다.");
        }else if(!"UNCONFIRMED".equals(proof.status())||!"STATE_UNCONFIRMED".equals(proof.reasonCode())
                ||!proof.mutationUncertain()||primary!=null)throw error(HttpStatus.CONFLICT,"clone 준비 확인 결과가 올바르지 않습니다.");
        check.setStatus(proof.status());check.setReasonCode(proof.reasonCode());check.setCompletedAt(proof.completedAt());
        if(repository.finishCheck(check)!=1)return;
        audit.recordCurrentRequired("Nexus 기존 복원본 준비 상태 확인 결과","NexusRestore",id.toString(),
                "확인 #"+checkId+(verified?" · 기동·접속 확인 · 기능 검증 별도":" · 상태 미확정·차단 유지"),verified?AuditResult.SUCCESS:AuditResult.FAIL);
        if(verified){
            request.setStatus(primary.status());request.setStage(primary.stage());request.setErrorCode(primary.errorCode());
            request.setCloneContainerId(primary.cloneContainerId());request.setCloneImageId(primary.cloneImageId());request.setCloneName(primary.cloneName());
            request.setCloneVolume(primary.cloneVolume());request.setCloneNetwork(primary.cloneNetwork());request.setCloneAccess(primary.cloneAccess());
            request.setExpectedArtifacts(primary.expectedArtifacts());request.setOriginalProjectionPreserved(true);request.setMutationUncertain(false);
            request.setCompletedAt(primary.completedAt());
            if(repository.result(request)!=1)throw error(HttpStatus.CONFLICT,"clone 준비 상태를 확정할 수 없습니다.");
        }
        if(repository.clearCheck(id,checkId)!=1)throw error(HttpStatus.CONFLICT,"clone 확인 접수 종료를 확정할 수 없습니다.");
        if(verified&&guard.releaseNexusRestore(id)!=1)throw error(HttpStatus.CONFLICT,"격리복원 예약 해제를 확정할 수 없습니다.");
    }
    private boolean existingCloneEligible(NexusRestoreRequest r){
        return "RECOVERY_REQUIRED".equals(r.getStatus())&&"RESULT_UNCERTAIN".equals(r.getErrorCode())
                &&r.getCloneContainerId()==null&&r.getCloneImageId()==null&&r.getCloneName()==null
                &&r.getCloneVolume()==null&&r.getCloneNetwork()==null&&r.getCloneAccess()==null;
    }
    @Transactional
    public boolean submit(Long id) {
        if (guard.lockSingleton() == null  ||  !id.equals(guard.nexusRestoreOwner())) throw error(HttpStatus.CONFLICT,"격리복원 선점 정보가 다릅니다.");
        return repository.submit(id) == 1;
    }
    @Transactional
    public void publicationRejected(Long id) {
        if (guard.lockSingleton() == null  ||  !id.equals(guard.nexusRestoreOwner())) {
            throw error(HttpStatus.CONFLICT, "격리복원 선점 정보가 다릅니다.");
        }
        if (repository.publicationRejected(id) != 1) {
            throw error(HttpStatus.CONFLICT, "게시 전 거부 상태를 확정할 수 없습니다.");
        }
        audit.recordCurrentRequired("Nexus 격리복원 게시 전 거부", "NexusRestore", id.toString(),
                "준비 상태 변경 · 작업 게시와 실행 없음", AuditResult.FAIL);
        if (guard.releaseNexusRestore(id) != 1) {
            throw error(HttpStatus.CONFLICT, "격리복원 예약 해제를 확정할 수 없습니다.");
        }
    }

    @Transactional
    public void uncertain(Long id) {
        if (guard.lockSingleton() != null && id.equals(guard.nexusRestoreOwner())) {
            repository.uncertain(id);
        }
    }

    @Transactional
    public void accept(Long id, NexusRestoreJobGateway.SafeResult result) {
        if (guard.lockSingleton() == null || !id.equals(guard.nexusRestoreOwner())) {
            return;
        }
        var request = require(id);
        if (request.getActiveCheckId() != null || request.getLastCheckStatus() != null) return;
        if (request.getBackupRequestId() != result.backupRequestId()
                || !request.getManifestSha256().equals(result.manifestSha256())) {
            throw error(HttpStatus.CONFLICT, "복원 결과의 백업 식별이 다릅니다.");
        }
        if (sameResult(request, result)) {
            return;
        }
        request.setStatus(result.status());
        request.setStage(result.stage());
        request.setErrorCode(result.errorCode());
        request.setCloneContainerId(result.cloneContainerId());
        request.setCloneImageId(result.cloneImageId());
        request.setCloneName(result.cloneName());
        request.setCloneVolume(result.cloneVolume());
        request.setCloneNetwork(result.cloneNetwork());
        request.setCloneAccess(result.cloneAccess());
        request.setExpectedArtifacts(result.expectedArtifacts());
        request.setOriginalProjectionPreserved(result.originalProjectionPreserved());
        request.setMutationUncertain(result.mutationUncertain());
        request.setCompletedAt(result.completedAt());
        if (repository.result(request) != 1) {
            return;
        }
        if (Set.of("HEALTH_READY", "FAILED", "RECOVERY_REQUIRED").contains(result.status())) {
            audit.recordCurrentRequired("Nexus 격리복원 실행 결과", "NexusRestore", id.toString(),
                    result.status() + " · 기능검증과 별개",
                    result.status().equals("HEALTH_READY") ? AuditResult.SUCCESS : AuditResult.FAIL);
        }
        if (Set.of("HEALTH_READY", "FAILED").contains(result.status())
                && !result.mutationUncertain() && result.originalProjectionPreserved()
                && guard.releaseNexusRestore(id) != 1) {
            throw error(HttpStatus.CONFLICT, "격리복원 예약 해제를 확정할 수 없습니다.");
        }
    }

    private boolean sameResult(NexusRestoreRequest r, NexusRestoreJobGateway.SafeResult v) {
        return Objects.equals(r.getStatus(), v.status())
                && Objects.equals(r.getStage(), v.stage())
                && Objects.equals(r.getErrorCode(), v.errorCode())
                && Objects.equals(r.getCloneContainerId(), v.cloneContainerId())
                && Objects.equals(r.getCloneImageId(), v.cloneImageId())
                && Objects.equals(r.getCloneName(), v.cloneName())
                && Objects.equals(r.getCloneVolume(), v.cloneVolume())
                && Objects.equals(r.getCloneNetwork(), v.cloneNetwork())
                && Objects.equals(r.getCloneAccess(), v.cloneAccess())
                && Objects.equals(r.getExpectedArtifacts(), v.expectedArtifacts())
                && r.isOriginalProjectionPreserved() == v.originalProjectionPreserved()
                && r.isMutationUncertain() == v.mutationUncertain()
                && Objects.equals(r.getCompletedAt(), v.completedAt());
    }

    @Transactional
    public NexusRestoreRequest confirm(Long id, NexusRestoreConfirmation form, Long actor) {
        var request = require(id);
        if (!"HEALTH_READY".equals(request.getStatus()) || request.isMutationUncertain()
                || !request.isOriginalProjectionPreserved()
                || !Objects.equals(request.getCloneContainerId(), form.cloneContainerId())
                || !request.getManifestSha256().equals(form.manifestSha256())
                || !CHECKLIST.equals(form.checklist())) {
            throw error(HttpStatus.CONFLICT, "정확한 준비 clone과 모든 정상 GUI 확인 항목이 필요합니다.");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode expected = NexusRestoreJobGateway.validateExpectations(mapper.readTree(
                    request.getExpectedArtifacts() == null ? "null" : request.getExpectedArtifacts()));
            if (form.artifacts() == null || expected.size() != form.artifacts().size()) {
                throw error(HttpStatus.BAD_REQUEST,
                        "사전 지정된 자료만 확인할 수 있습니다. legacy 백업은 개별 자료 기준이 미제공입니다.");
            }
            Map<String, String> submitted = new HashMap<>();
            for (var artifact : form.artifacts()) {
                if (artifact == null || artifact.ref() == null || artifact.downloadedSha256() == null
                        || !artifact.ref().matches("[A-Za-z0-9_-]{1,64}")
                        || !artifact.downloadedSha256().matches("[0-9a-f]{64}")
                        || submitted.put(artifact.ref(), artifact.downloadedSha256()) != null) {
                    throw error(HttpStatus.BAD_REQUEST, "중복된 자료 확인입니다.");
                }
            }
            for (JsonNode artifact : expected) {
                if (!Objects.equals(submitted.get(artifact.get("ref").asText()), artifact.get("sha256").asText())) {
                    throw error(HttpStatus.BAD_REQUEST, "다운로드 SHA가 검증 예정 자료와 다릅니다.");
                }
            }
            request.setOperatorChecklist(mapper.writeValueAsString(form.checklist()));
            request.setArtifactConfirmations(mapper.writeValueAsString(form.artifacts()));
        } catch (java.io.IOException malformedEvidence) {
            throw error(HttpStatus.CONFLICT, "안전한 자료 확인 기준을 읽을 수 없습니다.");
        }
        request.setConfirmedById(actor);
        if (repository.confirm(request) != 1) {
            throw error(HttpStatus.CONFLICT, "이미 확인되었거나 clone 상태가 바뀌었습니다.");
        }
        audit.recordCurrentRequired("Nexus 격리복원 운영자 확인", "NexusRestore", id.toString(),
                "정상 GUI 수동 확인 · 자동 기능검증 성공 아님", AuditResult.SUCCESS);
        return require(id);
    }

    private ResponseStatusException error(HttpStatus status, String text) {
        return new ResponseStatusException(status, text);
    }
}
