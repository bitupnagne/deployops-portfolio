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
public class NexusPromotionService {
    private final NexusPromotionRequestRepository repository;
    private final NexusRestoreRequestRepository restores;
    private final ExecutionGuardRepository guard;
    private final DeployRequestRepository deployments;
    private final NexusPromotionJobGateway gateway;
    private final AuditLogService audit;
    public boolean ready(){return gateway.ready();}
    @Transactional(readOnly=true) public List<NexusPromotionRequest> recent(){return repository.recent();}
    @Transactional(readOnly=true) public List<NexusPromotionRequest> unfinished(){return repository.unfinished();}
    @Transactional(readOnly=true) public NexusPromotionRequest require(Long id){return repository.find(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Nexus 운영 전환 요청을 찾을 수 없습니다."));}
    private NexusRestoreRequest confirmed(Long id){
        var restore=restores.find(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"복원 요청을 찾을 수 없습니다."));
        if(!"OPERATOR_CONFIRMED".equals(restore.getStatus())||restore.isMutationUncertain()||!restore.isOriginalProjectionPreserved()||restore.getConfirmedAt()==null||restore.getCloneContainerId()==null)
            throw error(HttpStatus.CONFLICT,"정상 Nexus 화면에서 기능과 자료를 확인한 복원본만 운영 전환할 수 있습니다.");
        return restore;
    }
    @Transactional public NexusPromotionRequest create(Long restoreId,String reason,boolean externalStopped,Long actor){
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>1000||!externalStopped)throw error(HttpStatus.BAD_REQUEST,"운영 전환 사유와 외부 게시 작업 중지 확인이 필요합니다.");
        if(!gateway.ready())throw error(HttpStatus.SERVICE_UNAVAILABLE,"Nexus 운영 전환 실행기가 준비되지 않았습니다.");
        confirmed(restoreId);
        if(guard.lockSingleton()==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"실행 잠금 정보를 찾을 수 없습니다.");
        if(repository.activeForRestore(restoreId)>0)throw error(HttpStatus.CONFLICT,"이 복원본의 운영 전환 요청이 이미 있습니다. 기존 요청을 확인하세요.");
        NexusPromotionRequest request=new NexusPromotionRequest();request.setRestoreRequestId(restoreId);request.setRequestedById(actor);request.setReason(reason.trim());
        if(repository.insert(request)!=1)throw error(HttpStatus.CONFLICT,"운영 전환 요청을 저장할 수 없습니다.");
        audit.recordCurrentRequired("Nexus 운영 전환 요청","NexusPromotion",request.getId().toString(),"복원 #"+restoreId,AuditResult.SUCCESS);
        return require(request.getId());
    }
    @Transactional public NexusPromotionRequest approve(Long id,boolean externalStopped,Long actor){
        var request=require(id);
        if(actor.equals(request.getRequestedById()))throw error(HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.");
        if(!externalStopped)throw error(HttpStatus.BAD_REQUEST,"외부 게시 작업 중지를 확인해 주세요.");
        confirmed(request.getRestoreRequestId());
        if(!gateway.ready()||guard.lockSingleton()==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"운영 전환 실행기를 확인해 주세요.");
        guard.assertNoNexusMaintenance();
        if(deployments.countByStatusIn(List.of(DeployStatus.TEST_BUILDING,DeployStatus.TEST_DEPLOYING,DeployStatus.PROD_DEPLOYING))>0||guard.activeNexusCleanupCount()>0)throw error(HttpStatus.CONFLICT,"진행 중인 빌드·배포·이미지 정리가 끝난 뒤 승인해 주세요.");
        if(repository.approve(id,actor)!=1||guard.reserveNexusPromotion(id)!=1)throw error(HttpStatus.CONFLICT,"이미 처리되었거나 다른 작업이 실행 중입니다.");
        audit.recordCurrentRequired("Nexus 운영 전환 승인","NexusPromotion",id.toString(),"기존 컨테이너·볼륨·키를 보존하고 복원본을 운영 접속점으로 전환",AuditResult.SUCCESS);
        return require(id);
    }
    @Transactional public void reject(Long id,String reason,Long actor){
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>1000)throw error(HttpStatus.BAD_REQUEST,"반려 사유를 입력해 주세요.");
        if(actor.equals(require(id).getRequestedById()))throw error(HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.");
        if(repository.reject(id,actor,reason.trim())!=1)throw error(HttpStatus.CONFLICT,"이미 처리된 운영 전환 요청입니다.");
        audit.recordCurrentRequired("Nexus 운영 전환 반려","NexusPromotion",id.toString(),reason.trim(),AuditResult.SUCCESS);
    }
    @Transactional public boolean markSubmitted(Long id){
        if(guard.lockSingleton()==null||!id.equals(guard.nexusPromotionOwner()))throw error(HttpStatus.CONFLICT,"운영 전환 선점 정보가 다릅니다.");
        return repository.submit(id)==1;
    }
    @Transactional public void accept(Long id,NexusPromotionJobGateway.Result result){
        if(guard.lockSingleton()==null||!id.equals(guard.nexusPromotionOwner()))return;
        var request=require(id);
        if(Objects.equals(request.getStatus(),result.status())&&Objects.equals(request.getCanonicalContainerId(),result.canonicalContainerId())&&Objects.equals(request.getRollbackContainerId(),result.rollbackContainerId())&&Objects.equals(request.getErrorCode(),result.errorCode())&&Objects.equals(request.getCompletedAt(),result.completedAt()))return;
        request.setStatus(result.status());request.setCanonicalContainerId(result.canonicalContainerId());request.setRollbackContainerId(result.rollbackContainerId());request.setErrorCode(result.errorCode());request.setCompletedAt(result.completedAt());
        if(repository.result(request)!=1)return;
        if(!"RUNNING".equals(result.status()))audit.recordCurrentRequired("Nexus 운영 전환 결과","NexusPromotion",id.toString(),result.status()+" · 기존 복구용 컨테이너 보존", "COMPLETED".equals(result.status())?AuditResult.SUCCESS:AuditResult.FAIL);
        if(List.of("COMPLETED","FAILED").contains(result.status())&&guard.releaseNexusPromotion(id)!=1)throw error(HttpStatus.CONFLICT,"운영 전환 선점 해제를 확정할 수 없습니다.");
    }
    @Transactional public void uncertain(Long id){if(guard.lockSingleton()!=null&&id.equals(guard.nexusPromotionOwner()))repository.uncertain(id);}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
