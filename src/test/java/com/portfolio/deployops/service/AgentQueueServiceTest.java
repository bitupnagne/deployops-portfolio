package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.AgentProtocol;
import com.portfolio.deployops.repository.ServerRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentQueueServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ServerRepository servers=mock(ServerRepository.class);
    final AgentQueueService service=spy(new AgentQueueService(jdbc,servers,new SecretCryptoService("agent-test-key"),JsonMapper.builder().build()));
    final Server was=Server.builder().id(7L).name("WAS7").serverType(ServerType.WAS).controlMode(ServerControlMode.AGENT).agentBindingId("binding7").build();
    final String taskId="0e88be50-2c94-4361-a7ba-e7bfe9fda0fb";
    final String credential="B".repeat(64);
    AgentQueueService.Registration identity(String state) {
        return new AgentQueueService.Registration(7L,2L,state,null,null,AgentQueueService.sha(credential),"machine7","was7","1.0.0","WAS",LocalDateTime.now(),"{}");
    }
    AgentQueueService.Task task(String state,boolean sensitive,String hash) {
        return new AgentQueueService.Task(taskId,7L,2L,"EXEC",state,"enc",sensitive,30,
            LocalDateTime.now().minusSeconds(2),LocalDateTime.now().plusSeconds(28),null,null,null,null,null,null,hash,null,null,null);
    }
    void authenticated() {
        doReturn(identity("ACTIVE")).when(service).authenticate("Bearer "+credential,true);
        doReturn(List.of(identity("ACTIVE"))).when(jdbc).query(eq("SELECT * FROM server_agent WHERE server_id=? FOR UPDATE"),any(RowMapper.class),eq(7L));
    }

    @Test void untrustedWorkerCannotUseAUserSessionOrGuessCredential() {
        for(String header:Arrays.asList(null,"session","Bearer short","Bearer \n"+credential))
            assertEquals(401,assertThrows(ResponseStatusException.class,()->service.authenticate(header,true)).getStatusCode().value());
        verifyNoInteractions(jdbc,servers);
    }
    @Test void workerCannotRegisterAgainstAnotherRoleOrSshManagedServer() {
        when(servers.findById(7L)).thenReturn(Optional.of(was));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.enroll(new AgentProtocol.Enrollment(7L,"x","WEB","m","h","1"))).getStatusCode().value());
        was.setControlMode(ServerControlMode.SSH);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.enroll(new AgentProtocol.Enrollment(7L,"x","WAS","m","h","1"))).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
    @Test void pollAuthenticationRejectsPendingRegistration() {
        doReturn(List.of(identity("PENDING"))).when(jdbc).query(eq("SELECT * FROM server_agent WHERE credential_hash=?"),any(RowMapper.class),eq(AgentQueueService.sha(credential)));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.authenticate("Bearer "+credential,true)).getStatusCode().value());
        verifyNoInteractions(servers);
    }
    @Test void revokedCredentialHasNoWorkerAccess() {
        doReturn(List.of(identity("REVOKED"))).when(jdbc).query(eq("SELECT * FROM server_agent WHERE credential_hash=?"),any(RowMapper.class),eq(AgentQueueService.sha(credential)));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.authenticate("Bearer "+credential,false)).getStatusCode().value());
    }
    @Test void crossServerResultIsRefusedBeforeAnyMutation() {
        authenticated();
        var other=new AgentQueueService.Task(taskId,8L,2L,"EXEC","RUNNING","",false,30,LocalDateTime.now(),LocalDateTime.now(),null,null,null,null,null,null,null,null,null,null);
        doReturn(other).when(service).task(taskId,true);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",0,"","",1L))).getStatusCode().value());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void oldGenerationResultCannotCompleteNewRegistrationTask() {
        authenticated();
        var old=new AgentQueueService.Task(taskId,7L,1L,"EXEC","RUNNING","",false,30,LocalDateTime.now(),LocalDateTime.now(),null,null,null,null,null,null,null,null,null,null);
        doReturn(old).when(service).task(taskId,true);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.started("Bearer "+credential,taskId)).getStatusCode().value());
    }
    @Test void successfulResultRequiresZeroExitAndFailedResultRequiresNonzero() {
        authenticated(); doReturn(task("RUNNING",false,null)).when(service).task(taskId,true);
        assertThrows(ResponseStatusException.class,()->service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",9,"","",1L)));
        assertThrows(ResponseStatusException.class,()->service.result("Bearer "+credential,taskId,new AgentProtocol.Result("FAILED",0,"","",1L)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void sensitiveResultNeverPersistsWorkerOutput() {
        authenticated(); doReturn(task("RUNNING",true,null)).when(service).task(taskId,true);
        var result=service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",0,"api-secret","db-password",10L));
        assertEquals("SUCCEEDED",result.get("state"));
        verify(jdbc).update(startsWith("UPDATE server_agent_task SET state=?"),eq("SUCCEEDED"),any(LocalDateTime.class),eq(0),eq(""),eq(""),eq(10L),anyString(),eq(taskId));
    }
    @Test void uncertainExecutionRemainsUnknownRatherThanOrdinaryFailure() {
        authenticated(); doReturn(task("RUNNING",false,null)).when(service).task(taskId,true);
        var result=service.result("Bearer "+credential,taskId,new AgentProtocol.Result("UNCERTAIN",-1,"","interrupted",1L));
        assertEquals("UNKNOWN",result.get("state"));
        verify(jdbc).update(startsWith("UPDATE server_agent_task SET state=?"),eq("UNKNOWN"),any(LocalDateTime.class),eq(-1),eq(""),argThat(value->value instanceof String s && s.startsWith("enc:v2:")
            && new SecretCryptoService("agent-test-key").decryptIfEncrypted(s).equals("agent-output:v1:interrupted")),eq(1L),anyString(),eq(taskId));
    }
    @Test void repeatedFinishedResultIsIdempotentButConflictingResultIsRefused() {
        authenticated();
        String hash=AgentQueueService.sha("{\"state\":\"SUCCEEDED\",\"exitCode\":0,\"stdout\":\"done\",\"stderr\":\"\",\"durationMs\":12}");
        doReturn(task("SUCCEEDED",false,hash)).when(service).task(taskId,true);
        assertEquals("SUCCEEDED",service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",0,"done","",12L)).get("state"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.result("Bearer "+credential,taskId,new AgentProtocol.Result("FAILED",1,"different","",12L))).getStatusCode().value());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void lateAuthoritativeResultCanReconcileServerTimeout() {
        authenticated(); doReturn(task("UNKNOWN",false,null)).when(service).task(taskId,true);
        assertEquals("SUCCEEDED",service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",0,"late-completed","",500L)).get("state"));
    }
    @Test void queuedTaskCannotBeStartedWithoutClaimAndUnknownCannotBeRestarted() {
        authenticated();
        for(String state:List.of("QUEUED","UNKNOWN","CANCELLED")) {
            doReturn(task(state,false,null)).when(service).task(taskId,true);
            assertEquals(409,assertThrows(ResponseStatusException.class,()->service.started("Bearer "+credential,taskId)).getStatusCode().value());
        }
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void enqueueAndPollingUseIndependentTransactionsDespiteOuterRolloutLocks() throws Exception {
        var enqueue=AgentQueueService.class.getMethod("enqueue",Server.class,String.class,Map.class,boolean.class,int.class,java.nio.file.Path.class,String.class,Long.class).getAnnotation(Transactional.class);
        var polling=AgentQueueService.class.getMethod("task",String.class,boolean.class).getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW,enqueue.propagation());
        assertEquals(Propagation.REQUIRES_NEW,polling.propagation()); assertTrue(polling.readOnly());
    }
    @Test void resolvedUnknownNeedsRevocationAndExplicitExecutionStopConfirmation() {
        when(servers.findById(7L)).thenReturn(Optional.of(was));
        assertThrows(ResponseStatusException.class,()->service.resolveUnknown(7L,taskId,"확인",false));
        doReturn(List.of(identity("ACTIVE"))).when(jdbc).query(eq("SELECT * FROM server_agent WHERE server_id=? FOR UPDATE"),any(RowMapper.class),eq(7L));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.resolveUnknown(7L,taskId,"확인",true)).getStatusCode().value());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void snapshotBindingDriftPreventsTaskCreation() {
        Server stale=Server.builder().id(7L).serverType(ServerType.WAS).controlMode(ServerControlMode.AGENT).agentBindingId("old-binding").build();
        when(servers.findById(7L)).thenReturn(Optional.of(was));
        assertThrows(RemoteExecutionException.class,()->service.enqueue(stale,"EXEC",Map.of("argv",List.of("probe")),false,30,null,null,null));
        verifyNoInteractions(jdbc);
    }
    @Test void unresolvedPreviousTaskStopsAutomaticCompensationEvenWhenHeartbeatIsStale() {
        when(servers.findById(7L)).thenReturn(Optional.of(was));
        var stale=new AgentQueueService.Registration(7L,2L,"ACTIVE",null,null,"hash","machine7","was7","1.0.0","WAS",LocalDateTime.now().minusMinutes(5),"{}");
        doReturn(List.of(stale)).when(jdbc).query(eq("SELECT * FROM server_agent WHERE server_id=? FOR UPDATE"),any(RowMapper.class),eq(7L));
        doReturn(List.of(task("UNKNOWN",false,null))).when(jdbc).query(startsWith("SELECT * FROM server_agent_task WHERE server_id=?"),any(RowMapper.class),eq(7L),eq(2L));
        assertThrows(AgentOutcomeUnknownException.class,()->service.enqueue(was,"EXEC",Map.of("argv",List.of("probe")),false,30,null,null,null));
        verify(jdbc,never()).update(startsWith("INSERT INTO server_agent_task"),any(Object[].class));
    }
    AgentQueueService.Task queryTask(String state, String kind, boolean sensitive, long server, long generation, String request) {
        return new AgentQueueService.Task(taskId,server,generation,kind,state,
            new SecretCryptoService("agent-test-key").encryptIfPlain(NexusReadOnlyAgentTaskTest.payload(request)),sensitive,30,
            LocalDateTime.now().minusMinutes(3),LocalDateTime.now().minusMinutes(2),null,null,null,null,null,null,null,null,null,null);
    }
    void queryRegistration() {
        was.setServerType(ServerType.DEPLOY);
        when(servers.findById(7L)).thenReturn(Optional.of(was));
        doReturn(List.of(identity("ACTIVE"))).when(jdbc).query(eq("SELECT * FROM server_agent WHERE server_id=? FOR UPDATE"),any(RowMapper.class),eq(7L));
    }
    @Test void explicitFileQueryRecoveryRetainsBindingAndNeverClaimsSuccess() {
        queryRegistration();
        doReturn(queryTask("UNKNOWN","EXEC",false,7,2,"{\"operation\":\"exists\",\"path\":\"results/1.json\"}")).when(service).task(taskId,true);
        when(jdbc.update(startsWith("UPDATE server_agent_task SET state='RESOLVED_UNKNOWN'"),any(),eq(taskId))).thenReturn(1);
        service.resolveReadOnlyQuery(7,taskId,"조회 재개 확인",true);
        verify(jdbc).update(startsWith("UPDATE server_agent_task SET state='RESOLVED_UNKNOWN'"),any(),eq(taskId));
        verify(jdbc,never()).update(contains("server_agent SET"),any(Object[].class));
        assertEquals("binding7",was.getAgentBindingId());
    }
    @Test void queryRecoveryRejectsMutationWrongRoleGenerationAndAbsentAcknowledgement() {
        queryRegistration();
        assertThrows(ResponseStatusException.class,()->service.resolveReadOnlyQuery(7,taskId,"확인",false));
        for(var task:List.of(
            queryTask("UNKNOWN","EXEC",false,7,2,"{\"operation\":\"publish\",\"path\":\"inbox/1.json\"}"),
            queryTask("RUNNING","EXEC",false,7,2,"{\"operation\":\"read\",\"path\":\"results/1.json\"}"),
            queryTask("UNKNOWN","COPY",false,7,2,"{\"operation\":\"read\",\"path\":\"results/1.json\"}"),
            queryTask("UNKNOWN","EXEC",true,7,2,"{\"operation\":\"read\",\"path\":\"results/1.json\"}"),
            queryTask("UNKNOWN","EXEC",false,8,2,"{\"operation\":\"read\",\"path\":\"results/1.json\"}"),
            queryTask("UNKNOWN","EXEC",false,7,1,"{\"operation\":\"read\",\"path\":\"results/1.json\"}"))) {
            doReturn(task).when(service).task(taskId,true);
            assertThrows(ResponseStatusException.class,()->service.resolveReadOnlyQuery(7,taskId,"확인",true));
        }
        was.setServerType(ServerType.WAS);
        doReturn(queryTask("UNKNOWN","EXEC",false,7,2,"{\"operation\":\"read\",\"path\":\"results/1.json\"}")).when(service).task(taskId,true);
        assertThrows(ResponseStatusException.class,()->service.resolveReadOnlyQuery(7,taskId,"확인",true));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void lateRetiredReadOnlyResultIsAcknowledgedWithoutOverwritingOrBlockingWorkerReplay() {
        queryRegistration(); authenticated();
        doReturn(queryTask("RESOLVED_UNKNOWN","EXEC",false,7,2,"{\"operation\":\"read\",\"path\":\"results/1.json\"}")).when(service).task(taskId,true);
        assertEquals("RESOLVED_UNKNOWN",service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",0,"stale", "",1L)).get("state"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void resultRechecksRevocationGenerationAndCredentialAfterObtainingLock() {
        authenticated();
        var valid=identity("ACTIVE");
        for(var locked:List.of(identity("REVOKED"),
            new AgentQueueService.Registration(7,3,"ACTIVE",null,null,valid.credentialHash(),"machine7","was7","1","WAS",LocalDateTime.now(),"{}"),
            new AgentQueueService.Registration(7,2,"ACTIVE",null,null,"different-hash","machine7","was7","1","WAS",LocalDateTime.now(),"{}"))) {
            doReturn(List.of(locked)).when(jdbc).query(eq("SELECT * FROM server_agent WHERE server_id=? FOR UPDATE"),any(RowMapper.class),eq(7L));
            assertEquals(401,assertThrows(ResponseStatusException.class,()->service.result("Bearer "+credential,taskId,new AgentProtocol.Result("SUCCEEDED",0,"", "",1L))).getStatusCode().value());
        }
        verify(service,never()).task(eq(taskId),anyBoolean());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
