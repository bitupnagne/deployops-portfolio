package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.repository.*;
import java.util.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class NginxTwoUpstreamTest {
    NginxChangeRequestRepository repo=mock(NginxChangeRequestRepository.class);
    ServerRepository servers=mock(ServerRepository.class);
    SshCommandService ssh=mock(SshCommandService.class);
    ExecutionGuardRepository guard=mock(ExecutionGuardRepository.class);
    NginxChangeService service=new NginxChangeService(repo,servers,ssh,guard);
    Server web=Server.builder().id(1L).name("web").serverType(ServerType.WEB).controlMode(ServerControlMode.AGENT).agentBindingId("web-registration-1").build();
    @BeforeEach void setup(){when(servers.findById(1L)).thenReturn(Optional.of(web));when(guard.lockSingleton()).thenReturn(1);when(repo.storeNativeProof(any(),any(),any(),any(),any())).thenReturn(1);}
    NginxPolicyForm form(){var f=new NginxPolicyForm();f.setServerId(1L);f.setPolicyName("ha");f.setServerName("localhost");f.setListenPort(80);f.setUpstreamHost("198.18.8.11");f.setUpstreamPort(18080);f.setSecondaryUpstreamEnabled(true);f.setSecondaryUpstreamHost("198.18.8.12");f.setSecondaryUpstreamPort(18080);return f;}
    @Test void persistsBothTypedEndpointsAndBoundedControls(){var f=form();f.setUpstreamMaxFails(2);f.setUpstreamFailTimeoutSec(20);f.setProxyConnectTimeoutSec(4);var r=service.create(f,null,null,10L);assertThat(r.hasSecondaryUpstream()).isTrue();assertThat(r.getSecondaryUpstreamHost()).isEqualTo("198.18.8.12");assertThat(r.getSecondaryUpstreamPort()).isEqualTo(18080);assertThat(r.getUpstreamMaxFails()).isEqualTo(2);assertThat(r.getProxyConnectTimeoutSec()).isEqualTo(4);verify(repo).insert(r);}
    @Test void rejectsMissingInjectionDuplicatesAndStaticBeforeInsert(){
        for(int c=0;c<5;c++){var f=form();switch(c){case 0->f.setSecondaryUpstreamPort(null);case 1->f.setSecondaryUpstreamHost("host;return 200");case 2->f.setSecondaryUpstreamHost("198.18.8.11");case 3->f.setPolicyType("STATIC_SPA");case 4->f.setSecondaryUpstreamEnabled(false);}assertThatThrownBy(()->service.create(f,null,null,10L)).isInstanceOf(ResponseStatusException.class);}
        verify(repo,never()).insert(any());
    }
    @Test void rejectsFailureSettingsOutsideBounds(){for(int c=0;c<3;c++){var f=form();switch(c){case 0->f.setUpstreamMaxFails(0);case 1->f.setUpstreamFailTimeoutSec(301);case 2->f.setProxyConnectTimeoutSec(31);}assertThatThrownBy(()->service.create(f,null,null,10L)).isInstanceOf(ResponseStatusException.class);}verify(repo,never()).insert(any());}
    @Test void approvedFrozenPairUsesTypedArgsAndRollbackPreservesPair(){
        var r=service.create(form(),null,null,10L);r.setId(7L);r.setRequestedBy(User.builder().id(10L).build());when(repo.findById(7L)).thenReturn(Optional.of(r));when(repo.acquireApproval(7L,20L)).thenReturn(1);var now=LocalDateTime.now();when(ssh.execute(any(),any())).thenReturn(new RemoteCommandResult(0,"BACKUP_PATH=/var/backups/deploy-system/nginx/ha\nBACKUP_SNAPSHOT_SHA=" + NginxRollbackSnapshotTest.A + "\n" + NginxRollbackSnapshotTest.proof("CURRENT_", NginxRollbackSnapshotTest.A, NginxRollbackSnapshotTest.C, "7"),"","ssh",now,now,1));
        service.approveAndApply(7L,20L);ArgumentCaptor<List<String>> args=ArgumentCaptor.forClass(List.class);verify(ssh).execute(eq(web),args.capture());assertThat(args.getValue()).containsSubsequence("--secondary-upstream-host","198.18.8.12","--secondary-upstream-port","18080");
        r.setBackupSnapshotHash(NginxRollbackSnapshotTest.A);r.setAppliedSnapshotHash(NginxRollbackSnapshotTest.A);r.setAppliedConfigHash(NginxRollbackSnapshotTest.C);r.setNativeRequestId(7L);when(ssh.execute(any(),any())).thenReturn(NginxRollbackSnapshotTest.result(NginxRollbackSnapshotTest.proof("TARGET_",NginxRollbackSnapshotTest.A,NginxRollbackSnapshotTest.C,"7")));r.setStatus(NginxChangeStatus.APPLIED);r.setBackupPath("/var/backups/deploy-system/nginx/ha");when(repo.findLatestCompletedId(1L,"ha")).thenReturn(7L);var rollback=service.requestRollback(7L,30L);assertThat(rollback.getSecondaryUpstreamHost()).isEqualTo(r.getSecondaryUpstreamHost());assertThat(rollback.getUpstreamFailTimeoutSec()).isEqualTo(r.getUpstreamFailTimeoutSec());
    }
    NginxPolicyForm staticApi(String type) {var f=form();f.setPolicyType(type);f.setWebRoot("/var/www/react");f.setApiProxyEnabled(true);f.setApiPrefix("/api/");f.setApiUpstreamHost("198.18.8.21");f.setApiUpstreamPort(18082);f.setSecondaryUpstreamPort(18082);return f;}
    @Test void staticApiPairsPersistEffectivePrimaryAndRejectItsDuplicate() {
        for(String type:List.of("STATIC_SPA","STATIC_FILES")) {
            var f=staticApi(type);f.setUpstreamHost("ignored;dormant");f.setUpstreamPort(0);
            var r=service.create(f,null,null,10L);
            assertThat(r.getApiUpstreamHost()).isEqualTo("198.18.8.21");assertThat(r.getApiUpstreamPort()).isEqualTo(18082);
            assertThat(r.getUpstreamHost()).isEqualTo("static");assertThat(r.getUpstreamPort()).isEqualTo(1);
            assertThat(r.getSecondaryUpstreamHost()).isEqualTo("198.18.8.12");assertThat(r.getWebRoot()).isEqualTo("/var/www/react");
            f.setSecondaryUpstreamHost("198.18.8.21");assertThatThrownBy(()->service.create(f,null,null,10L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("서로 다른");
        }
    }
    @Test void staticApiPrimaryInvalidAndApiOffPairFailBeforeInsert() {
        for(int c=0;c<4;c++){var f=staticApi("STATIC_SPA");switch(c){case 0->f.setApiUpstreamPort(null);case 1->f.setApiUpstreamHost("bad;host");case 2->f.setApiProxyEnabled(false);case 3->f.setWebRoot("/var/www/react/current");}assertThatThrownBy(()->service.create(f,null,null,10L)).isInstanceOf(ResponseStatusException.class);}
        verify(repo,never()).insert(any());
    }
    @Test void staticApiApprovalAndRollbackKeepApiAndSecondaryMetadata() {
        var r=service.create(staticApi("STATIC_SPA"),null,null,10L);r.setId(7L);when(repo.findById(7L)).thenReturn(Optional.of(r));when(repo.acquireApproval(7L,20L)).thenReturn(1);
        var now=LocalDateTime.now();when(ssh.execute(any(),any())).thenReturn(new RemoteCommandResult(0,"BACKUP_PATH=/var/backups/deploy-system/nginx/ha\nBACKUP_SNAPSHOT_SHA="+NginxRollbackSnapshotTest.A+"\n"+NginxRollbackSnapshotTest.proof("CURRENT_",NginxRollbackSnapshotTest.A,NginxRollbackSnapshotTest.C,"7"),"","ssh",now,now,1));
        service.approveAndApply(7L,20L);ArgumentCaptor<List<String>> args=ArgumentCaptor.forClass(List.class);verify(ssh).execute(eq(web),args.capture());
        assertThat(args.getValue()).containsSubsequence("--api-upstream-host","198.18.8.21","--api-upstream-port","18082").containsSubsequence("--secondary-upstream-host","198.18.8.12","--secondary-upstream-port","18082");
        r.setBackupSnapshotHash(NginxRollbackSnapshotTest.A);r.setAppliedSnapshotHash(NginxRollbackSnapshotTest.A);r.setAppliedConfigHash(NginxRollbackSnapshotTest.C);r.setNativeRequestId(7L);r.setStatus(NginxChangeStatus.APPLIED);r.setBackupPath("/var/backups/deploy-system/nginx/ha");when(repo.findLatestCompletedId(1L,"ha")).thenReturn(7L);
        when(ssh.execute(any(),any())).thenReturn(NginxRollbackSnapshotTest.result(NginxRollbackSnapshotTest.proof("TARGET_",NginxRollbackSnapshotTest.A,NginxRollbackSnapshotTest.C,"7")));
        var rollback=service.requestRollback(7L,30L);assertThat(rollback.isApiProxyEnabled()).isTrue();assertThat(rollback.getApiUpstreamHost()).isEqualTo(r.getApiUpstreamHost());assertThat(rollback.getSecondaryUpstreamHost()).isEqualTo(r.getSecondaryUpstreamHost());assertThat(rollback.getWebRoot()).isEqualTo(r.getWebRoot());
    }
    @Test void selfApprovalNeverExecutesPair(){var r=service.create(form(),null,null,10L);r.setId(7L);when(repo.findById(7L)).thenReturn(Optional.of(r));assertThatThrownBy(()->service.approveAndApply(7L,10L)).isInstanceOf(ResponseStatusException.class);verify(ssh,never()).execute(any(),any());}
}
