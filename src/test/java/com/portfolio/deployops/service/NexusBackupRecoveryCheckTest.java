package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NexusBackupRecoveryCheckTest {
    final NexusBackupRequestRepository repo=mock(NexusBackupRequestRepository.class);
    final ExecutionGuardRepository guard=mock(ExecutionGuardRepository.class);
    final NexusBackupJobGateway gateway=mock(NexusBackupJobGateway.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final NexusBackupService service=new NexusBackupService(repo,guard,mock(DeployRequestRepository.class),gateway,audit);
    final NexusBackupRequest request=new NexusBackupRequest();
    final NexusBackupRecoveryCheck check=new NexusBackupRecoveryCheck();
    @BeforeEach void setup(){
        request.setId(1L);request.setStatus("RECOVERY_REQUIRED");request.setSubmitted(true);
        check.setId(7L);check.setRequestId(1L);check.setStatus("SUBMITTED");
        when(repo.findById(1L)).thenReturn(Optional.of(request));when(repo.findCheck(7L)).thenReturn(Optional.of(check));
        when(guard.lockSingleton()).thenReturn(1);when(guard.nexusMaintenanceOwner()).thenReturn(1L);
        when(gateway.ready()).thenReturn(true);
        when(repo.activateCheck(1L,7L)).thenReturn(1);when(repo.finishCheck(any())).thenReturn(1);when(repo.updateResult(any())).thenReturn(1);when(repo.clearCheck(1L,7L)).thenReturn(1);when(guard.releaseNexusMaintenance(1L)).thenReturn(1);
        doAnswer(c->{((NexusBackupRecoveryCheck)c.getArgument(0)).setId(7L);return 1;}).when(repo).insertCheck(any());
    }
    NexusBackupJobGateway.SafeResult recovered(){return new NexusBackupJobGateway.SafeResult("FAILED","DONE",null,null,"BACKUP_FAILED",true,LocalDateTime.now());}
    @Test void createsDistinctAuditedCheckWithoutPublishingOrChangingApproval()throws Exception{service.requestRecoveryCheck(1L,30L);verify(audit).recordCurrentRequired(eq("Nexus 원본 상태 재확인 요청"),anyString(),eq("1"),anyString(),eq(AuditResult.SUCCESS));verify(repo).activateCheck(1L,7L);verify(repo,never()).approve(anyLong(),anyLong());verify(gateway,never()).submitRecoveryCheck(anyLong(),anyLong());}
    @Test void activeCheckAndWrongOwnerRejectBeforeInsert(){request.setActiveCheckId(7L);assertThatThrownBy(()->service.requestRecoveryCheck(1L,30L)).hasMessageContaining("이미 확인");request.setActiveCheckId(null);when(guard.nexusMaintenanceOwner()).thenReturn(2L);assertThatThrownBy(()->service.requestRecoveryCheck(1L,30L)).hasMessageContaining("선점");verify(repo,never()).insertCheck(any());}
    @Test void staleCheckCannotReleaseOrAudit(){request.setActiveCheckId(8L);service.acceptRecoveryCheck(1L,7L,recovered());verify(repo,never()).finishCheck(any());verifyNoInteractions(audit);verify(guard,never()).releaseNexusMaintenance(anyLong());}
    @Test void recoveredCheckAuditsBeforeReleaseAndDoesNotProveBackupSuccess(){request.setActiveCheckId(7L);service.acceptRecoveryCheck(1L,7L,recovered());var order=inOrder(audit,guard);order.verify(audit).recordCurrentRequired(eq("Nexus 원본 상태 재확인 결과"),anyString(),eq("1"),anyString(),eq(AuditResult.SUCCESS));order.verify(guard).releaseNexusMaintenance(1L);assertThat(request.getStatus()).isEqualTo("FAILED");assertThat(request.getArtifactId()).isNull();assertThat(request.getManifestSha256()).isNull();when(guard.nexusMaintenanceOwner()).thenReturn(null);service.acceptRecoveryCheck(1L,7L,recovered());verify(repo,times(1)).finishCheck(any());verify(guard,times(1)).releaseNexusMaintenance(1L);}
    @Test void unconfirmedOutcomeAuditsFailureButNeverClearsMaintenance(){request.setActiveCheckId(7L);service.acceptRecoveryCheck(1L,7L,new NexusBackupJobGateway.SafeResult("RECONCILING","PREFLIGHT",null,null,"RESULT_UNCERTAIN",false,LocalDateTime.now()));verify(audit).recordCurrentRequired(anyString(),anyString(),eq("1"),anyString(),eq(AuditResult.FAIL));verify(guard,never()).releaseNexusMaintenance(anyLong());assertThat(request.getStatus()).isEqualTo("RECOVERY_REQUIRED");}
    @Test void missingPublicationOrResultNeverRepublishesAndOldResultIsNotRead()throws Exception{request.setActiveCheckId(7L);when(repo.unfinishedChecks()).thenReturn(List.of(check));when(repo.findUnfinished()).thenReturn(List.of(request));new NexusBackupReconciler(service,gateway).poll();new NexusBackupReconciler(service,gateway).poll();verify(gateway,never()).submitRecoveryCheck(anyLong(),anyLong());verify(gateway,never()).result(anyLong());verify(gateway,times(2)).recoveryCheckResult(1L,7L);verify(guard,never()).releaseNexusMaintenance(anyLong());}
    @Test void checkParserRejectsStaleIdsAndBackupSuccess()throws Exception{var m=new ObjectMapper();String json="{\"schema\":1,\"requestId\":1,\"checkId\":7,\"status\":\"FAILED\",\"stage\":\"DONE\",\"artifactId\":null,\"manifestSha256\":null,\"errorCode\":\"BACKUP_FAILED\",\"originalStateRestored\":true,\"completedAt\":\"2026-10-11T01:00:00Z\"}";assertThat(NexusBackupJobGateway.parseCheckResult(m.readTree(json),1,7).originalStateRestored()).isTrue();assertThatThrownBy(()->NexusBackupJobGateway.parseCheckResult(m.readTree(json),1,8)).hasMessage("CHECK_RESULT_ID");assertThatThrownBy(()->NexusBackupJobGateway.parseCheckResult(m.readTree(json.replace("\"checkId\":7","\"checkId\":\"7\"")),1,7)).hasMessage("CHECK_RESULT_ID");assertThatThrownBy(()->NexusBackupJobGateway.parseCheckResult(m.readTree(json.replace("\"artifactId\":null","\"artifactId\":\"nexus-backup-1\"")),1,7)).hasMessage("CHECK_RESULT_CONTRACT");}
    @Test void requiredCheckAuditFailureRollsBackCheckCasAndKeepsReservation(){
        request.setActiveCheckId(7L);
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:check_audit"+System.nanoTime()+";DB_CLOSE_DELAY=-1","sa","");var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);jdbc.execute("CREATE TABLE state(status VARCHAR(32)); INSERT INTO state VALUES('SUBMITTED')");
        when(repo.finishCheck(any())).thenAnswer(c->jdbc.update("UPDATE state SET status=?",((NexusBackupRecoveryCheck)c.getArgument(0)).getStatus()));
        doThrow(new IllegalStateException("required audit unavailable")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        var proxy=new org.springframework.aop.framework.ProxyFactory(service);proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        assertThatThrownBy(()->((NexusBackupService)proxy.getProxy()).acceptRecoveryCheck(1L,7L,recovered())).hasMessageContaining("required audit unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM state",String.class)).isEqualTo("SUBMITTED");verify(repo,never()).clearCheck(anyLong(),anyLong());verify(guard,never()).releaseNexusMaintenance(anyLong());
    }
    @Test void explicitSupersedeGetsFreshIdAndIgnoresOldSuccessfulResult()throws Exception{
        request.setActiveCheckId(7L);when(repo.supersedeCheck(7L,1L)).thenReturn(1);when(repo.replaceCheck(1L,7L,8L)).thenReturn(1);
        doAnswer(c->{((NexusBackupRecoveryCheck)c.getArgument(0)).setId(8L);return 1;}).when(repo).insertCheck(any());
        assertThat(service.supersedeRecoveryCheck(1L,7L,30L).getId()).isEqualTo(8L);request.setActiveCheckId(8L);
        service.acceptRecoveryCheck(1L,7L,recovered());verify(repo,never()).finishCheck(any());verify(guard,never()).releaseNexusMaintenance(anyLong());verify(audit).recordCurrentRequired(eq("Nexus 원본 상태 재확인 대체"),anyString(),eq("1"),anyString(),eq(AuditResult.SUCCESS));verify(gateway,never()).submitRecoveryCheck(anyLong(),anyLong());
        // root가 이전 check 성공을 먼저 게시해도 Java는 새 active ID의 새 증거만 수용한다.
        var next=new NexusBackupRecoveryCheck();next.setId(8L);next.setRequestId(1L);next.setStatus("SUBMITTED");when(repo.findCheck(8L)).thenReturn(Optional.of(next));when(repo.clearCheck(1L,8L)).thenReturn(1);
        service.acceptRecoveryCheck(1L,8L,recovered());verify(repo,times(1)).finishCheck(any());verify(guard,times(1)).releaseNexusMaintenance(1L);verify(audit).recordCurrentRequired(eq("Nexus 원본 상태 재확인 결과"),anyString(),eq("1"),contains("#8"),eq(AuditResult.SUCCESS));
    }
    @Test void unavailableRunnerRejectsInitialAndSupersedeWithoutChangingPendingState(){
        when(gateway.ready()).thenReturn(false);assertThatThrownBy(()->service.requestRecoveryCheck(1L,30L)).hasMessageContaining("준비되지");request.setActiveCheckId(7L);assertThatThrownBy(()->service.supersedeRecoveryCheck(1L,7L,30L)).hasMessageContaining("차단은 유지");verify(repo,never()).supersedeCheck(anyLong(),anyLong());verify(repo,never()).insertCheck(any());verify(guard,never()).releaseNexusMaintenance(anyLong());
    }
    @Test void supersedeAuditFailureRollsBackOldStateAndNewActiveId(){
        request.setActiveCheckId(7L);
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:supersede_audit"+System.nanoTime()+";DB_CLOSE_DELAY=-1","sa","");var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);jdbc.execute("CREATE TABLE state(status VARCHAR(32),active_id BIGINT); INSERT INTO state VALUES('SUBMITTED',7)");
        when(repo.supersedeCheck(7L,1L)).thenAnswer(c->jdbc.update("UPDATE state SET status='UNCONFIRMED'"));
        doAnswer(c->{((NexusBackupRecoveryCheck)c.getArgument(0)).setId(8L);return 1;}).when(repo).insertCheck(any());
        when(repo.replaceCheck(1L,7L,8L)).thenAnswer(c->jdbc.update("UPDATE state SET active_id=8 WHERE active_id=7"));
        doThrow(new IllegalStateException("required audit unavailable")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        var proxy=new org.springframework.aop.framework.ProxyFactory(service);proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        assertThatThrownBy(()->((NexusBackupService)proxy.getProxy()).supersedeRecoveryCheck(1L,7L,30L)).hasMessageContaining("required audit unavailable");assertThat(jdbc.queryForObject("SELECT status FROM state",String.class)).isEqualTo("SUBMITTED");assertThat(jdbc.queryForObject("SELECT active_id FROM state",Long.class)).isEqualTo(7L);verify(guard,never()).releaseNexusMaintenance(anyLong());
    }
}
