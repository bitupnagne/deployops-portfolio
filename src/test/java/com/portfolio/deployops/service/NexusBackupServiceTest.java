package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NexusBackupServiceTest {
    final NexusBackupRequestRepository repo=mock(NexusBackupRequestRepository.class);
    final ExecutionGuardRepository guard=mock(ExecutionGuardRepository.class,CALLS_REAL_METHODS);
    final DeployRequestRepository deploy=mock(DeployRequestRepository.class);
    final NexusBackupJobGateway gateway=mock(NexusBackupJobGateway.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final NexusBackupService service=new NexusBackupService(repo,guard,deploy,gateway,audit);
    NexusBackupRequest request;
    @BeforeEach void setup(){request=new NexusBackupRequest();request.setId(1L);request.setRequestedById(10L);request.setStatus("PENDING");when(repo.findById(1L)).thenReturn(Optional.of(request));when(gateway.ready()).thenReturn(true);when(guard.lockSingleton()).thenReturn(1);when(guard.nexusMaintenanceOwner()).thenReturn(null);when(guard.nexusPromotionOwner()).thenReturn(null);when(guard.nexusRestoreOwner()).thenReturn(null);when(repo.approve(1L,20L)).thenReturn(1);when(guard.reserveNexusMaintenance(1L)).thenReturn(1);}
    @Test void readinessReportsOnlyBrokerStateWithoutRequestMutation(){assertThat(service.ready()).isTrue();when(gateway.ready()).thenReturn(false);assertThat(service.ready()).isFalse();verifyNoInteractions(repo,guard,deploy,audit);}
    @Test void distinctApprovalReservesAndAuditsWithoutPublishing()throws Exception{assertThat(service.approve(1L,20L,true)).isSameAs(request);verify(guard).reserveNexusMaintenance(1L);verify(audit).recordCurrentRequired(eq("Nexus 백업 승인"),anyString(),eq("1"),anyString(),eq(AuditResult.SUCCESS));verify(gateway,never()).submit(anyLong());}
    @Test void selfApprovalDeniedBeforeCas(){assertThatThrownBy(()->service.approve(1L,10L,true)).hasMessageContaining("달라야");verify(repo,never()).approve(anyLong(),anyLong());}
    @Test void activeBuildDenied(){when(deploy.countByStatusIn(anyList())).thenReturn(1L);assertThatThrownBy(()->service.approve(1L,20L,true)).hasMessageContaining("진행 중");verify(repo,never()).approve(anyLong(),anyLong());}
    @Test void activeCleanupDenied(){when(guard.activeNexusCleanupCount()).thenReturn(1L);assertThatThrownBy(()->service.approve(1L,20L,true)).hasMessageContaining("진행 중");}
    @Test void maintenanceAcrossInstancesDenied(){when(guard.nexusMaintenanceOwner()).thenReturn(5L);assertThatThrownBy(()->service.approve(1L,20L,true)).hasMessageContaining("유지보수");verify(repo,never()).approve(anyLong(),anyLong());}
    @Test void duplicateApprovalCasDenied(){when(repo.approve(1L,20L)).thenReturn(0);assertThatThrownBy(()->service.approve(1L,20L,true)).hasMessageContaining("이미 처리");verify(guard,never()).reserveNexusMaintenance(anyLong());}
    @Test void brokerNotReadyDeniesBeforeReservation(){when(gateway.ready()).thenReturn(false);assertThatThrownBy(()->service.approve(1L,20L,true)).hasMessageContaining("준비되지");verify(guard,never()).lockSingleton();}
    @Test void externalWriterConfirmationRequired(){assertThatThrownBy(()->service.approve(1L,20L,false)).hasMessageContaining("중지");verify(repo,never()).approve(anyLong(),anyLong());}
    @Test void rejectionNeedsDifferentActorAndReason(){assertThatThrownBy(()->service.reject(1L,10L,"반려")).hasMessageContaining("달라야");assertThatThrownBy(()->service.reject(1L,20L," ")).hasMessageContaining("반려 사유");when(repo.reject(1L,20L,"유지보수 창 없음")).thenReturn(1);service.reject(1L,20L,"유지보수 창 없음");verify(audit).recordCurrentRequired(eq("Nexus 백업 반려"),anyString(),eq("1"),eq("유지보수 창 없음"),eq(AuditResult.SUCCESS));}
    @Test void auditFailurePropagatesAndDoesNotPublish()throws Exception{doThrow(new IllegalStateException("audit unavailable")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());assertThatThrownBy(()->service.approve(1L,20L,true)).hasMessageContaining("audit unavailable");verify(gateway,never()).submit(anyLong());}
    @Test void unRestoredRecoveryRetainsMaintenance(){when(guard.nexusMaintenanceOwner()).thenReturn(1L);when(repo.updateResult(any())).thenReturn(1);service.acceptResult(1L,new NexusBackupJobGateway.SafeResult("RECOVERY_REQUIRED","RESTORING_ORIGINAL",null,null,"ORIGINAL_RECOVERY_FAILED",false,LocalDateTime.now()));verify(guard,never()).releaseNexusMaintenance(anyLong());}
    @Test void completedResultAuditsBeforeReleasingMaintenance(){when(guard.nexusMaintenanceOwner()).thenReturn(1L);when(repo.updateResult(any())).thenReturn(1);service.acceptResult(1L,new NexusBackupJobGateway.SafeResult("COMPLETED","DONE","nexus-backup-1","a".repeat(64),null,true,LocalDateTime.now()));var order=inOrder(audit,guard);order.verify(audit).recordCurrentRequired(anyString(),anyString(),eq("1"),anyString(),eq(AuditResult.SUCCESS));order.verify(guard).releaseNexusMaintenance(1L);}
    @Test void recoveryFailureIsAuditedOnceAndKeepsReservation(){
        when(guard.nexusMaintenanceOwner()).thenReturn(1L);when(repo.updateResult(any())).thenReturn(1);
        var result=new NexusBackupJobGateway.SafeResult("RECOVERY_REQUIRED","RESTORING_ORIGINAL",null,null,"ORIGINAL_RECOVERY_FAILED",false,LocalDateTime.now());
        service.acceptResult(1L,result);
        request.setStatus(result.status());request.setStage(result.stage());request.setErrorCode(result.errorCode());request.setCompletedAt(result.completedAt());
        service.acceptResult(1L,result);
        verify(repo,times(1)).updateResult(any());verify(audit,times(1)).recordCurrentRequired(eq("Nexus 백업 결과"),anyString(),eq("1"),anyString(),eq(AuditResult.FAIL));
        verify(guard,never()).releaseNexusMaintenance(anyLong());
    }
    @Test void recoveryAuditFailureRollsBackPersistedStateAndKeepsReservation(){
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:recovery_audit"+System.nanoTime()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE state(status VARCHAR(32)); INSERT INTO state VALUES('RUNNING')");
        when(guard.nexusMaintenanceOwner()).thenReturn(1L);
        when(repo.updateResult(any())).thenAnswer(call->jdbc.update("UPDATE state SET status=?",((NexusBackupRequest)call.getArgument(0)).getStatus()));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        var proxy=new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional=(NexusBackupService)proxy.getProxy();
        assertThatThrownBy(()->transactional.acceptResult(1L,new NexusBackupJobGateway.SafeResult("RECOVERY_REQUIRED","RESTORING_ORIGINAL",null,null,"ORIGINAL_RECOVERY_FAILED",false,LocalDateTime.now()))).hasMessageContaining("audit unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM state",String.class)).isEqualTo("RUNNING");
        verify(guard,never()).releaseNexusMaintenance(anyLong());
    }
    @Test void existingIsolatedRestoreReservationBlocksBackupApproval() {
        when(guard.nexusRestoreOwner()).thenReturn(7L);
        assertThatThrownBy(() -> service.approve(1L, 20L, true)).hasMessageContaining("유지보수");
        verify(repo, never()).approve(anyLong(), anyLong());
        verify(guard, never()).reserveNexusMaintenance(anyLong());
    }

}
