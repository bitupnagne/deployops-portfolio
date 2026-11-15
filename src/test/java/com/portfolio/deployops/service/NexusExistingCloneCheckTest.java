package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NexusExistingCloneCheckTest {
    NexusRestoreRecoveryCheckTest fixture;com.portfolio.deployops.repository.NexusRestoreRequestRepository repo;
    com.portfolio.deployops.repository.ExecutionGuardRepository guard;NexusRestoreJobGateway gateway;AuditLogService audit;
    NexusRestoreService service;NexusRestoreRequest request;NexusRestoreRecoveryCheck check;
    @BeforeEach void existingAction(){fixture=new NexusRestoreRecoveryCheckTest();fixture.setup();repo=fixture.repo;guard=fixture.guard;gateway=fixture.gateway;
        audit=fixture.audit;service=fixture.service;request=fixture.request;check=fixture.check;request.setErrorCode("RESULT_UNCERTAIN");check.setReasonCode(NexusRestoreJobGateway.EXISTING_CLONE_CHECK);}
    NexusRestoreJobGateway.CheckResult result(boolean verified){return fixture.result(verified);}
    NexusRestoreJobGateway.ReadinessCheckResult ready()throws Exception{
        var fixture=new NexusExistingCloneGatewayTest();var p=fixture.primary();p.put("requestId",2);p.put("cloneName","nexus-restore-2");p.put("cloneNetwork","nexus-restore-net-2");p.put("cloneVolume","nexus-restore-data-2");
        var n=fixture.check(p);n.put("requestId",2);return NexusRestoreJobGateway.parseReadinessCheck(n,fixture.bytes(p),2,7);
    }
    @Test void readinessSetsCloneFieldsAndReleasesOnlyAfterAuditCas()throws Exception{
        service.acceptExistingCloneCheck(2L,7L,ready());assertThat(request.getStatus()).isEqualTo("HEALTH_READY");
        assertThat(request.getCloneContainerId()).isEqualTo("b".repeat(64));assertThat(request.getOperatorChecklist()).isNull();
        var order=inOrder(repo,audit,guard);order.verify(repo).finishCheck(check);order.verify(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        order.verify(repo).result(request);order.verify(repo).clearCheck(2L,7L);order.verify(guard).releaseNexusRestore(2L);
        service.acceptExistingCloneCheck(2L,7L,ready());verify(guard,times(1)).releaseNexusRestore(2L);
    }
    @Test void staleActiveCheckOrCasLoserCannotRelease()throws Exception{
        request.setActiveCheckId(8L);service.acceptExistingCloneCheck(2L,7L,ready());verifyNoInteractions(audit);verify(guard,never()).releaseNexusRestore(any());
        request.setActiveCheckId(7L);when(repo.finishCheck(any())).thenReturn(0);service.acceptExistingCloneCheck(2L,7L,ready());verifyNoInteractions(audit);verify(repo,never()).result(any());
    }
    @Test void oldActionCannotConsumeReadinessAndNewActionCannotConsumeFailure()throws Exception{
        assertThatThrownBy(()->service.acceptRecoveryCheck(2L,7L,result(true))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        check.setReasonCode(null);assertThatThrownBy(()->service.acceptExistingCloneCheck(2L,7L,ready())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(repo,never()).finishCheck(any());verify(guard,never()).releaseNexusRestore(any());
    }
    @Test void requiredAuditFailureKeepsDatabaseReservation()throws Exception{
        doThrow(new IllegalStateException("AUDIT_FAILED")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        assertThatThrownBy(()->service.acceptExistingCloneCheck(2L,7L,ready())).isInstanceOf(IllegalStateException.class);
        verify(repo,never()).result(any());verify(repo,never()).clearCheck(anyLong(),anyLong());verify(guard,never()).releaseNexusRestore(any());
    }
    @Test void unconfirmedCompletesOnlyCheckAndRetainsHold()throws Exception{
        service.acceptExistingCloneCheck(2L,7L,new NexusRestoreJobGateway.ReadinessCheckResult(result(false),null,null,null));
        verify(repo).clearCheck(2L,7L);verify(repo,never()).result(any());verify(guard,never()).releaseNexusRestore(any());
        request.setActiveCheckId(null);request.setLastCheckStatus("UNCONFIRMED");service.accept(2L,ready().primary());verify(repo,never()).result(any());
    }
    @Test void checkKindIsPersistedBeforeActivation()throws Exception{
        request.setActiveCheckId(null);when(repo.insertCheck(any())).thenAnswer(c->{var n=(NexusRestoreRecoveryCheck)c.getArgument(0);assertThat(n.getReasonCode()).isEqualTo(NexusRestoreJobGateway.EXISTING_CLONE_CHECK);n.setId(9L);return 1;});
        when(repo.activateCheck(2L,9L)).thenReturn(1);assertThat(service.requestExistingCloneCheck(2L,20L).getReasonCode()).isEqualTo(NexusRestoreJobGateway.EXISTING_CLONE_CHECK);
    }
    @Test void submittedMissingPublicationResumesSameTokenOnly()throws Exception{
        var svc=mock(NexusRestoreService.class);when(svc.unfinishedChecks()).thenReturn(List.of(check));when(svc.require(2L)).thenReturn(request);when(svc.unfinished()).thenReturn(List.of(request));
        new NexusRestoreReconciler(svc,gateway).poll();new NexusRestoreReconciler(svc,gateway).poll();
        verify(gateway,times(2)).submitExistingCloneCheck(2L,7L,2L,"a".repeat(64));verify(gateway,never()).submitRecoveryCheck(anyLong(),anyLong(),anyLong(),anyString());
        verify(gateway,never()).submit(anyLong(),anyLong(),anyString());verify(svc,never()).markCheckSubmitted(anyLong());
    }
    @Test void completedCheckResultDoesNotRepublishInbox()throws Exception{
        var svc=mock(NexusRestoreService.class);when(svc.unfinishedChecks()).thenReturn(List.of(check));when(svc.require(2L)).thenReturn(request);when(svc.unfinished()).thenReturn(List.of());when(gateway.existingCloneCheckResult(2L,7L)).thenReturn(ready());
        new NexusRestoreReconciler(svc,gateway).poll();verify(gateway,never()).submitExistingCloneCheck(anyLong(),anyLong(),anyLong(),anyString());verify(svc).acceptExistingCloneCheck(eq(2L),eq(7L),any());
    }
    @Test void newActionRejectsOtherPublicCloneFieldsAndNonUncertainResults(){
        request.setActiveCheckId(null);
        for(String kind:List.of("image","name","volume","network","access","error","status")){
            request.setCloneImageId(null);request.setCloneName(null);request.setCloneVolume(null);request.setCloneNetwork(null);request.setCloneAccess(null);request.setErrorCode("RESULT_UNCERTAIN");request.setStatus("RECOVERY_REQUIRED");
            switch(kind){case "image"->request.setCloneImageId("sha256:"+"b".repeat(64));case "name"->request.setCloneName("nexus-restore-2");case "volume"->request.setCloneVolume("nexus-restore-data-2");case "network"->request.setCloneNetwork("nexus-restore-net-2");case "access"->request.setCloneAccess("{}");case "error"->request.setErrorCode("ORIGINAL_CHANGED");default->request.setStatus("RECONCILING");}
            assertThatThrownBy(()->service.requestExistingCloneCheck(2L,20L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        verify(repo,never()).insertCheck(any());verifyNoInteractions(audit);
    }
}
