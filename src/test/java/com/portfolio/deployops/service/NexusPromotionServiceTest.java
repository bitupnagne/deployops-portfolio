package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NexusPromotionServiceTest {
    final NexusPromotionRequestRepository repo=mock(NexusPromotionRequestRepository.class);
    final NexusRestoreRequestRepository restores=mock(NexusRestoreRequestRepository.class);
    final ExecutionGuardRepository guard=mock(ExecutionGuardRepository.class,CALLS_REAL_METHODS);
    final DeployRequestRepository deployments=mock(DeployRequestRepository.class);
    final NexusPromotionJobGateway gateway=mock(NexusPromotionJobGateway.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final NexusPromotionService service=new NexusPromotionService(repo,restores,guard,deployments,gateway,audit);
    NexusPromotionRequest request;
    NexusRestoreRequest restore;
    @BeforeEach void setup(){
        request=new NexusPromotionRequest();request.setId(5L);request.setRestoreRequestId(2L);request.setRequestedById(10L);request.setStatus("PENDING");
        when(repo.find(5L)).thenReturn(Optional.of(request));
        restore=new NexusRestoreRequest();restore.setId(2L);restore.setStatus("OPERATOR_CONFIRMED");restore.setOriginalProjectionPreserved(true);restore.setCloneContainerId("a".repeat(64));restore.setConfirmedAt(LocalDateTime.now());
        when(restores.find(2L)).thenReturn(Optional.of(restore));
        when(gateway.ready()).thenReturn(true);when(guard.lockSingleton()).thenReturn(1);when(guard.nexusMaintenanceOwner()).thenReturn(null);when(guard.nexusRestoreOwner()).thenReturn(null);when(guard.nexusPromotionOwner()).thenReturn(null);
        when(repo.approve(5L,20L)).thenReturn(1);when(guard.reserveNexusPromotion(5L)).thenReturn(1);
    }
    @Test void distinctApproverReservesWithoutPublishing()throws Exception{
        service.approve(5L,true,20L);
        verify(guard).reserveNexusPromotion(5L);verify(gateway,never()).submit(anyLong(),anyLong());
    }
    @Test void selfApprovalNeverReserves(){
        assertThatThrownBy(()->service.approve(5L,true,10L)).hasMessageContaining("달라야");verify(guard,never()).reserveNexusPromotion(anyLong());
    }
    @Test void staleApprovalCannotReserveOrPublish(){
        when(repo.approve(5L,20L)).thenReturn(0);assertThatThrownBy(()->service.approve(5L,true,20L)).hasMessageContaining("이미 처리");verify(guard,never()).reserveNexusPromotion(anyLong());
    }
    @Test void healthyButNotFunctionallyConfirmedCloneCannotPromote(){
        restore.setStatus("HEALTH_READY");assertThatThrownBy(()->service.approve(5L,true,20L)).hasMessageContaining("기능과 자료");verify(repo,never()).approve(anyLong(),anyLong());
    }
    @Test void changedOrUncertainCloneCannotPromote(){
        restore.setMutationUncertain(true);assertThatThrownBy(()->service.approve(5L,true,20L)).hasMessageContaining("기능과 자료");
    }
    @Test void anotherNexusOperationBlocksPromotion(){
        when(guard.nexusMaintenanceOwner()).thenReturn(7L);assertThatThrownBy(()->service.approve(5L,true,20L)).hasMessageContaining("유지보수");verify(repo,never()).approve(anyLong(),anyLong());
    }
    @Test void activeCiAndMissingExternalStopBothBlock(){
        assertThatThrownBy(()->service.approve(5L,false,20L)).hasMessageContaining("외부 게시");
        when(deployments.countByStatusIn(any())).thenReturn(1L);assertThatThrownBy(()->service.approve(5L,true,20L)).hasMessageContaining("진행 중");verify(repo,never()).approve(anyLong(),anyLong());
    }
    @Test void duplicateRestorePromotionCannotCreateAnotherRequest(){
        when(repo.activeForRestore(2L)).thenReturn(1L);assertThatThrownBy(()->service.create(2L,"restore",true,10L)).hasMessageContaining("이미 있습니다");verify(repo,never()).insert(any());
    }
    @Test void completedResultReleasesGuardButUncertaintyDoesNot(){
        when(guard.nexusPromotionOwner()).thenReturn(5L);when(repo.result(any())).thenReturn(1);when(guard.releaseNexusPromotion(5L)).thenReturn(1);
        service.accept(5L,new NexusPromotionJobGateway.Result("RECOVERY_REQUIRED",null,"b".repeat(64),"PROMOTION_FAILED",LocalDateTime.now()));verify(guard,never()).releaseNexusPromotion(anyLong());
        service.accept(5L,new NexusPromotionJobGateway.Result("COMPLETED","c".repeat(64),"b".repeat(64),null,LocalDateTime.now()));verify(guard).releaseNexusPromotion(5L);
    }
    @Test void submittedClaimCasControlsSinglePublication(){
        when(guard.nexusPromotionOwner()).thenReturn(5L);when(repo.submit(5L)).thenReturn(1,0);assertThat(service.markSubmitted(5L)).isTrue();assertThat(service.markSubmitted(5L)).isFalse();
    }
}
