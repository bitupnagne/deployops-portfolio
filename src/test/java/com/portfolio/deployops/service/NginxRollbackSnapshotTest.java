package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;

class NginxRollbackSnapshotTest {
    static final String A="a".repeat(64), B="b".repeat(64), C="c".repeat(64);
    NginxChangeRequestRepository repo=mock(NginxChangeRequestRepository.class);
    SshCommandService ssh=mock(SshCommandService.class);
    ExecutionGuardRepository guard=mock(ExecutionGuardRepository.class);
    NginxChangeService service=new NginxChangeService(repo,mock(ServerRepository.class),ssh,guard);
    Server web=Server.builder().id(1L).serverType(ServerType.WEB).controlMode(ServerControlMode.AGENT).agentBindingId("web-registration-1").build();
    NginxChangeRequest current, prior;
    static RemoteCommandResult result(String stdout){var now=LocalDateTime.now();return new RemoteCommandResult(0,stdout,"","fixture",now,now,1);}
    static String proof(String prefix,String hash,String config,String id){return prefix+"SNAPSHOT_SHA="+hash+"\n"+prefix+"CONFIG_SHA="+config+"\n"+prefix+"REQUEST_ID="+id+"\n"+prefix+"CONFIG_PRESENT="+(!id.equals("absent"))+"\n";}
    @BeforeEach void setup(){
        prior=NginxChangeRequest.builder().id(2L).server(web).requestedControlMode(web.getControlMode()).requestedAgentBindingId(web.getAgentBindingId()).policyName("ha").requestedBy(User.builder().id(9L).build()).upstreamHost("old").upstreamPort(8080).appliedSnapshotHash(A).appliedConfigHash(C).nativeRequestId(2L).status(NginxChangeStatus.APPLIED).build();
        current=NginxChangeRequest.builder().id(7L).server(web).requestedControlMode(web.getControlMode()).requestedAgentBindingId(web.getAgentBindingId()).policyName("ha").requestedBy(User.builder().id(10L).build()).upstreamHost("new").upstreamPort(9090).secondaryUpstreamHost("new2").secondaryUpstreamPort(9090).backupPath("/var/backups/deploy-system/nginx/original").backupSnapshotHash(A).appliedSnapshotHash(B).appliedConfigHash(B).nativeRequestId(7L).status(NginxChangeStatus.APPLIED).build();
        when(repo.findById(7L)).thenReturn(Optional.of(current));when(repo.findById(2L)).thenReturn(Optional.of(prior));when(repo.findLatestCompletedId(1L,"ha")).thenReturn(7L);when(guard.lockSingleton()).thenReturn(1);
    }
    @Test void previewUsesActualPreviousScalarAndFreezesBothCurrentAndBackup(){
        prior.setTlsEnabled(true);prior.setHstsEnabled(true);prior.setApiProxyEnabled(true);prior.setSecurityHeaders(true);prior.setWebsocketEnabled(true);
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,C,"2")));
        var rollback=service.requestRollback(7L,20L);
        assertThat(rollback.getUpstreamHost()).isEqualTo("old");assertThat(rollback.hasSecondaryUpstream()).isFalse();assertThat(rollback.getRestoreTargetId()).isEqualTo(2L);assertThat(rollback.getRollbackSnapshotHash()).isEqualTo(A);assertThat(rollback.getExpectedCurrentSnapshotHash()).isEqualTo(B);assertThat(rollback.getExpectedNativeRequestId()).isEqualTo(7L);
        assertThat(rollback.isTlsEnabled()).isTrue();assertThat(rollback.isHstsEnabled()).isTrue();assertThat(rollback.isApiProxyEnabled()).isTrue();assertThat(rollback.isSecurityHeaders()).isTrue();assertThat(rollback.isWebsocketEnabled()).isTrue();
    }
    @Test void absentTargetIsExplicitRemovalAndUnknownLegacyRefusesWithoutSsh(){
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,"absent","absent")));
        assertThat(service.requestRollback(7L,20L).isRestoreAbsent()).isTrue();
        reset(ssh);current.setBackupSnapshotHash(null);assertThatThrownBy(()->service.requestRollback(7L,20L)).hasMessageContaining("검증된 복원 정보");verifyNoInteractions(ssh);
    }
    @Test void changedBackupWrongHistoryAndDuplicateProofRefuseBeforeInsert(){
        for(String stdout:List.of(proof("TARGET_",B,C,"2"),proof("TARGET_",A,B,"2"),proof("TARGET_",A,C,"2")+"TARGET_REQUEST_ID=3\n")){
            when(ssh.execute(eq(web),any())).thenReturn(result(stdout));assertThatThrownBy(()->service.requestRollback(7L,20L)).isInstanceOf(RuntimeException.class);
        }verify(repo,never()).insert(any());
    }
    @Test void rollbackExitZeroWithWrongActualDigestIsFailedNotCompleted(){
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,C,"2")));var rollback=service.requestRollback(7L,20L);rollback.setId(8L);when(repo.findById(8L)).thenReturn(Optional.of(rollback));when(repo.acquireRollbackApproval(8L,30L,7L,1L,"ha")).thenReturn(1);
        when(ssh.execute(eq(web),any())).thenReturn(result("BACKUP_PATH=/var/backups/deploy-system/nginx/safety\nBACKUP_SNAPSHOT_SHA="+B+"\n"+proof("CURRENT_",B,C,"2")));
        assertThatThrownBy(()->service.approveAndApply(8L,30L)).hasMessageContaining("실제 복원 결과");verify(repo).finish(eq(8L),eq(NginxChangeStatus.FAILED),any(),any());verify(repo,never()).storeNativeProof(any(),any(),any(),any(),any());
    }
    @Test void rolledBackDbGenerationCanRequestNextRollbackUsingActualNativeHeader(){
        current.setStatus(NginxChangeStatus.ROLLED_BACK);current.setNativeRequestId(2L);
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,C,"2")));
        var rollback=service.requestRollback(7L,20L);assertThat(rollback.getRollbackOfId()).isEqualTo(7L);assertThat(rollback.getExpectedNativeRequestId()).isEqualTo(2L);
        verify(ssh).execute(eq(web),argThat(args->args.contains("2")&&args.contains("describe-backup")));
    }
    @Test void successfulRestorePersistsActualNativeHeaderAndNextRollbackTargetsSafetyBackup(){
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,C,"2")));
        var rollback=service.requestRollback(7L,20L);rollback.setId(8L);when(repo.findById(8L)).thenReturn(Optional.of(rollback));when(repo.acquireRollbackApproval(8L,30L,7L,1L,"ha")).thenReturn(1);when(repo.storeNativeProof(any(),any(),any(),any(),any())).thenReturn(1);
        when(ssh.execute(eq(web),any())).thenReturn(result("BACKUP_PATH=/var/backups/deploy-system/nginx/safety\nBACKUP_SNAPSHOT_SHA="+B+"\n"+proof("CURRENT_",A,C,"2")));
        service.approveAndApply(8L,30L);
        verify(repo).storeNativeProof(8L,B,A,C,2L);verify(repo).finish(8L,NginxChangeStatus.ROLLED_BACK,"/var/backups/deploy-system/nginx/safety",null);
        verify(ssh).execute(eq(web),argThat(args->args.contains("rollback")&&args.contains("--expected-current-snapshot")&&args.contains(B)&&args.contains(A)));
        rollback.setStatus(NginxChangeStatus.ROLLED_BACK);rollback.setNativeRequestId(2L);rollback.setBackupPath("/var/backups/deploy-system/nginx/safety");rollback.setBackupSnapshotHash(B);rollback.setAppliedSnapshotHash(A);rollback.setAppliedConfigHash(C);when(repo.findLatestCompletedId(1L,"ha")).thenReturn(8L);
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",B,B,"7")));
        var reverse=service.requestRollback(8L,40L);assertThat(reverse.getRestoreTargetId()).isEqualTo(7L);assertThat(reverse.getExpectedNativeRequestId()).isEqualTo(2L);assertThat(reverse.getRollbackOfId()).isEqualTo(8L);assertThat(reverse.getUpstreamHost()).isEqualTo("new");
    }

    @Test void orderedThreeRestoreTargetCopiedWithoutUsingCurrentNewListOrChangingNativeProof(){
        prior.setUpstreamSnapshots(List.of(new NginxUpstream("PROXY",0,2L,"old1",8080),new NginxUpstream("PROXY",1,3L,"old2",8081),new NginxUpstream("PROXY",2,4L,"old3",8082)));
        current.setUpstreamSnapshots(List.of(new NginxUpstream("PROXY",0,5L,"new1",9090)));
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,C,"2")));
        var r=service.requestRollback(7L,20L);assertThat(r.getEffectiveUpstreams()).extracting(NginxUpstream::getHost).containsExactly("old1","old2","old3");assertThat(r.getRollbackSnapshotHash()).isEqualTo(A);verify(repo,times(3)).insertUpstream(any(),any());
    }
    @Test void absentNativeTargetHasNoGhostCurrentEndpoints(){
        current.setUpstreamSnapshots(List.of(new NginxUpstream("PROXY",0,5L,"new1",9090)));
        when(ssh.execute(eq(web),any())).thenReturn(result(proof("TARGET_",A,"absent","absent")));
        var r=service.requestRollback(7L,20L);assertThat(r.isRestoreAbsent()).isTrue();assertThat(r.getEffectiveUpstreams()).isEmpty();verify(repo,never()).insertUpstream(any(),any());
    }
}
