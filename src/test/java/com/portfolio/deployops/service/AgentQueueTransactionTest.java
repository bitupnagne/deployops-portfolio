package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.AgentProtocol;
import com.portfolio.deployops.repository.ServerRepository;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the shipped migration and broker SQL with actual transaction proxies and concurrent connections. */
class AgentQueueTransactionTest {
    JdbcTemplate jdbc;
    DriverManagerDataSource dataSource;
    DataSourceTransactionManager transactions;
    AgentQueueService queue;
    AgentQueueService target;
    ServerRepository servers;
    @TempDir Path files;

    @BeforeEach void prepareDatabase() throws Exception {
        dataSource=new DriverManagerDataSource("jdbc:h2:mem:agent_"+UUID.randomUUID()+";MODE=MariaDB;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE server(id BIGINT PRIMARY KEY,name VARCHAR(255),server_type VARCHAR(16))");
        try(var connection=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V53__outbound_server_agents.sql"));
        }
        jdbc.update("INSERT INTO server(id,name,server_type,control_mode) VALUES(7,'WAS7','WAS','AGENT'),(8,'WEB8','WEB','AGENT')");
        servers=mock(ServerRepository.class);
        when(servers.findById(anyLong())).thenAnswer(call-> {
            long id=call.getArgument(0);
            var rows=jdbc.query("SELECT id,name,server_type,control_mode,agent_binding_id FROM server WHERE id=?",
                (r,n)->Server.builder().id(r.getLong(1)).name(r.getString(2)).serverType(ServerType.valueOf(r.getString(3)))
                    .controlMode(ServerControlMode.valueOf(r.getString(4))).agentBindingId(r.getString(5)).build(),id);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        });
        transactions=new DataSourceTransactionManager(dataSource);
        target=spy(new AgentQueueService(jdbc,servers,new SecretCryptoService("transaction-test-key"),JsonMapper.builder().build()));
        var proxy=new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        queue=(AgentQueueService)proxy.getProxy();
    }
    @AfterEach void closeDatabase() { if(jdbc!=null) jdbc.execute("DROP ALL OBJECTS"); }
    Server server(long id) { return servers.findById(id).orElseThrow(); }
    String activeAgent(long id,String role) {
        String code=String.valueOf(queue.issueCode(id).get("enrollmentCode"));
        String credential=String.valueOf(queue.enroll(new AgentProtocol.Enrollment(id,code,role,"machine-"+id,"host-"+id,"1.0.0")).get("credential"));
        queue.approve(id);
        queue.heartbeat("Bearer "+credential,new AgentProtocol.Heartbeat("host-"+id,"1.0.0",Map.of("ready",true,"role",role)));
        return "Bearer "+credential;
    }
    AgentQueueService.Task enqueueExec(boolean sensitive) {
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("argv",List.of("/usr/local/deploy/bin/deploy-agent-probe"));
        payload.put("stdinBase64",Base64.getEncoder().encodeToString("transaction-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return queue.enqueue(server(7),"EXEC",payload,sensitive,30,null,null,null);
    }
    String claim(String auth) {
        var raw=queue.poll(auth).get("task"); assertInstanceOf(Map.class,raw);
        return String.valueOf(((Map<?,?>)raw).get("id"));
    }

    @Test void enrollmentIsOneTimePendingCannotPollAndApprovalRotatesPinnedBinding() {
        var issued=queue.issueCode(7); String before=server(7).getAgentBindingId();
        String code=String.valueOf(issued.get("enrollmentCode"));
        var enrolled=queue.enroll(new AgentProtocol.Enrollment(7L,code,"WAS","machine7","was7","1.0.0"));
        String auth="Bearer "+enrolled.get("credential");
        assertEquals("PENDING",enrolled.get("state"));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->queue.poll(auth)).getStatusCode().value());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->queue.enroll(new AgentProtocol.Enrollment(7L,code,"WAS","machine7","was7","1.0.0"))).getStatusCode().value());
        assertEquals("PENDING",queue.heartbeat(auth,new AgentProtocol.Heartbeat("was7","1.0.0",Map.of("ready",true))).get("state"));
        assertNotEquals(enrolled.get("credential"),jdbc.queryForObject("SELECT credential_hash FROM server_agent WHERE server_id=7",String.class));
        assertNull(jdbc.queryForObject("SELECT enrollment_hash FROM server_agent WHERE server_id=7",String.class));
        queue.approve(7);
        assertNotEquals(before,server(7).getAgentBindingId());
        assertNull(queue.poll(auth).get("task"));
        assertThrows(ResponseStatusException.class,()->queue.issueCode(7));
    }

    @Test void taskLifecycleEncryptsInputsRedactsSensitiveOutputsAndKeepsOnlyMetadataInHistory() {
        String auth=activeAgent(7,"WAS"); var task=enqueueExec(true);
        String encrypted=jdbc.queryForObject("SELECT payload_encrypted FROM server_agent_task WHERE id=?",String.class,task.id());
        assertTrue(encrypted.startsWith("enc:v2:")); assertFalse(encrypted.contains("transaction-secret"));
        assertFalse(encrypted.contains("dHJhbnNhY3Rpb24tc2VjcmV0"));
        assertEquals(task.id(),claim(auth)); assertEquals("CLAIMED",queue.task(task.id(),false).state());
        assertEquals(task.id(),claim(auth));
        queue.started(auth,task.id()); assertEquals("RUNNING",queue.task(task.id(),false).state());
        queue.result(auth,task.id(),new AgentProtocol.Result("SUCCEEDED",0,"secret-output","secret-error",5L));
        var completed=queue.task(task.id(),false);
        assertEquals("SUCCEEDED",completed.state()); assertEquals("",completed.stdout()); assertEquals("",completed.stderr());
        assertNull(queue.poll(auth).get("task"));
        var history=(List<?>)queue.status(7).get("recentTasks"); assertEquals(1,history.size());
        var item=(Map<?,?>)history.getFirst();
        assertEquals(Set.of("id","kind","state","createdAt","finishedAt","exitCode"),item.keySet());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM server WHERE id=7"));
    }

    @Test void taskCommitAndCompletionRemainVisibleInsideAnOuterRepeatableReadTransaction() throws Exception {
        String auth=activeAgent(7,"WAS");
        var outer=new TransactionTemplate(transactions); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var executor=Executors.newSingleThreadExecutor();
        try {
            outer.executeWithoutResult(status-> {
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM server_agent_task",Integer.class));
                var task=enqueueExec(false);
                Future<?> worker=executor.submit(()-> {
                    assertEquals(task.id(),claim(auth)); queue.started(auth,task.id());
                    queue.result(auth,task.id(),new AgentProtocol.Result("SUCCEEDED",0,"worker-visible","",9L));
                });
                try { worker.get(5,TimeUnit.SECONDS); } catch(Exception e) { throw new AssertionError(e); }
                assertEquals("SUCCEEDED",queue.task(task.id(),false).state());
                status.setRollbackOnly();
            });
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM server_agent_task WHERE state='SUCCEEDED'",Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test void conflictingConcurrentResultsHaveExactlyOneWinnerAndNeverOverwriteIt() throws Exception {
        String auth=activeAgent(7,"WAS"); var task=enqueueExec(false); claim(auth); queue.started(auth,task.id());
        var executor=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
        try {
            Callable<String> success=()-> { barrier.await(); try { queue.result(auth,task.id(),new AgentProtocol.Result("SUCCEEDED",0,"winner-success","",1L)); return "accepted"; } catch(ResponseStatusException e) { return "rejected-"+e.getStatusCode().value(); } };
            Callable<String> failed=()-> { barrier.await(); try { queue.result(auth,task.id(),new AgentProtocol.Result("FAILED",7,"","winner-failed",2L)); return "accepted"; } catch(ResponseStatusException e) { return "rejected-"+e.getStatusCode().value(); } };
            var first=executor.submit(success); var second=executor.submit(failed);
            var outcomes=List.of(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"accepted")); assertEquals(1,Collections.frequency(outcomes,"rejected-409"));
            var finalTask=queue.task(task.id(),false);
            if("SUCCEEDED".equals(finalTask.state())) { assertEquals("winner-success",finalTask.stdout()); assertEquals(0,finalTask.exitCode()); }
            else { assertEquals("FAILED",finalTask.state()); assertEquals("winner-failed",finalTask.stderr()); assertEquals(7,finalTask.exitCode()); }
        } finally { executor.shutdownNow(); }
    }

    @Test void unknownOutcomeBlocksNewTasksButLateAuthoritativeResultReconcilesDurably() {
        String auth=activeAgent(7,"WAS"); var task=enqueueExec(false); claim(auth); queue.started(auth,task.id());
        queue.markUnknown(task.id());
        assertThrows(AgentOutcomeUnknownException.class,()->enqueueExec(false)); assertNull(queue.poll(auth).get("task"));
        queue.result(auth,task.id(),new AgentProtocol.Result("SUCCEEDED",0,"late-result","",500L));
        assertEquals("SUCCEEDED",queue.task(task.id(),false).state());
        assertEquals("late-result",queue.task(task.id(),false).stdout());
        assertDoesNotThrow(()->enqueueExec(false));
    }

    @Test void artifactDownloadIsScopedToClaimedTaskAndOtherAgentCannotReadIt() throws Exception {
        String auth=activeAgent(7,"WAS"), other=activeAgent(8,"WEB");
        Path source=Files.writeString(files.resolve("image.tar"),"immutable-image");
        String hash=AgentQueueService.sha(source); long size=Files.size(source);
        var task=queue.enqueue(server(7),"COPY",Map.of("argv",List.of(),"file",Map.of("path","/var/tmp/deploy-ops/image.tar","sha256",hash,"size",size)),false,30,source,hash,size);
        assertThrows(ResponseStatusException.class,()->queue.artifact(auth,task.id()));
        String claimed=claim(auth); assertEquals(task.id(),claimed);
        assertEquals(source,queue.artifact(auth,task.id()));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->queue.artifact(other,task.id())).getStatusCode().value());
        Files.writeString(source,"changed-image");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->queue.artifact(auth,task.id())).getStatusCode().value());
    }

    @Test void revocationManualResolutionAndNewEnrollmentInvalidateTheOldCredentialAndBinding() {
        String auth=activeAgent(7,"WAS"); Server pinned=server(7); var task=enqueueExec(false); claim(auth); queue.started(auth,task.id());
        queue.revoke(7); assertEquals("UNKNOWN",queue.task(task.id(),false).state());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->queue.heartbeat(auth,new AgentProtocol.Heartbeat("was7","1",Map.of()))).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->queue.issueCode(7));
        queue.resolveUnknown(7,task.id(),"서버에서 실행 중지 및 현재 상태 확인",true);
        assertEquals("RESOLVED_UNKNOWN",queue.task(task.id(),false).state());
        String replacement=activeAgent(7,"WAS");
        assertNotEquals(auth,replacement); assertNotEquals(pinned.getAgentBindingId(),server(7).getAgentBindingId());
        assertThrows(RemoteExecutionException.class,()->queue.enqueue(pinned,"EXEC",Map.of("argv",List.of("probe")),false,30,null,null,null));
    }
    @Test void migrationCanResumeAfterNonTransactionalDdlWithoutDeletingExistingRegistration() throws Exception {
        activeAgent(7,"WAS");
        String binding=server(7).getAgentBindingId();
        jdbc.execute("DROP TABLE server_agent_task");
        try(var connection=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V53__outbound_server_agents.sql"));
        }
        assertEquals(binding,server(7).getAgentBindingId());
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM server_agent WHERE server_id=7",String.class));
        var task=enqueueExec(true);
        assertTrue(jdbc.queryForObject("SELECT is_sensitive FROM server_agent_task WHERE id=?",Boolean.class,task.id()));
        try(var connection=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V53__outbound_server_agents.sql"));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM server_agent_task",Integer.class));
    }
    @Test void ordinaryOutputIsEncryptedAtRestAndLiteralCipherLikeTextRoundTripsWithoutChangingResultHash() {
        String auth=activeAgent(7,"WAS"); var task=enqueueExec(false); claim(auth); queue.started(auth,task.id());
        String output="enc:v2:literal-application-output\n{\"configuration\":\"unchanged\"}";
        var result=new AgentProtocol.Result("SUCCEEDED",0,output,"diagnostic-secret",11L);
        queue.result(auth,task.id(),result);
        String stored=jdbc.queryForObject("SELECT stdout_text FROM server_agent_task WHERE id=?",String.class,task.id());
        assertTrue(stored.startsWith("enc:v2:")); assertFalse(stored.contains("literal-application-output"));
        assertEquals(output,queue.task(task.id(),false).stdout());
        assertEquals("diagnostic-secret",queue.task(task.id(),false).stderr());
        assertEquals("SUCCEEDED",queue.result(auth,task.id(),result).get("state"));
    }
    @Test void strictApprovalAcceptsOnlyTheReviewedPendingHostAndBinding() {
        String code=String.valueOf(queue.issueCode(7).get("enrollmentCode"));
        String binding=server(7).getAgentBindingId();
        queue.enroll(new AgentProtocol.Enrollment(7L,code,"WAS","machine7","was7","1.0.0"));
        assertThrows(ResponseStatusException.class,()->queue.approve(7,binding,"forged-machine","WAS"));
        assertThrows(ResponseStatusException.class,()->queue.approve(7,binding,"machine7","WEB"));
        assertEquals("PENDING",queue.status(7).get("registrationState"));
        queue.approve(7,binding,"machine7","WAS");
        assertEquals("ACTIVE",queue.status(7).get("registrationState"));
    }
    @Test void strictApprovalRejectsAReplacedEnrollmentEvenWhenMachineIdAndRoleAreTheSame() {
        String firstCode=String.valueOf(queue.issueCode(7).get("enrollmentCode"));
        queue.enroll(new AgentProtocol.Enrollment(7L,firstCode,"WAS","machine7","was7","1.0.0"));
        String reviewedBinding=server(7).getAgentBindingId();
        String replacementCode=String.valueOf(queue.issueCode(7).get("enrollmentCode"));
        queue.enroll(new AgentProtocol.Enrollment(7L,replacementCode,"WAS","machine7","was7","1.0.0"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->queue.approve(7,reviewedBinding,"machine7","WAS")).getStatusCode().value());
        assertEquals("PENDING",queue.status(7).get("registrationState"));
        queue.approve(7,server(7).getAgentBindingId(),"machine7","WAS");
        assertEquals("ACTIVE",queue.status(7).get("registrationState"));
    }
    @Test void expiredReadOnlyQueryNeedsExplicitRecoveryAndPreservesBindingAndOutcome() {
        jdbc.update("UPDATE server SET server_type='DEPLOY' WHERE id=7");
        String auth=activeAgent(7,"DEPLOY"), binding=server(7).getAgentBindingId();
        Map<String,Object> payload=Map.of("argv",List.of("/usr/local/deploy/bin/deploy-infrastructure.sh","nexus-broker"),
            "stdinBase64",Base64.getEncoder().encodeToString("{\"operation\":\"exists\",\"path\":\"results/1.json\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var task=queue.enqueue(server(7),"EXEC",payload,false,30,null,null,null);
        // A real expired queued task models the bounded TTL failure, without sleeping.
        jdbc.update("UPDATE server_agent_task SET expires_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),task.id());
        assertNull(queue.poll(auth).get("task"));
        assertEquals("UNKNOWN",queue.task(task.id(),false).state());
        assertNull(queue.task(task.id(),false).startedAt());
        assertThrows(AgentOutcomeUnknownException.class,()->enqueueExec(false));
        assertEquals(true,((Map<?,?>)queue.status(7).get("busyTask")).get("readOnlyQueryRecoverable"));
        queue.resolveReadOnlyQuery(7,task.id(),"Nexus 상태 조회만 재개",true);
        assertEquals(binding,server(7).getAgentBindingId());
        assertEquals("RESOLVED_UNKNOWN",queue.task(task.id(),false).state());
        assertNull(queue.task(task.id(),false).exitCode());
        assertEquals("RESOLVED_UNKNOWN",queue.result(auth,task.id(),new AgentProtocol.Result("SUCCEEDED",0,"stale-exists", "",1L)).get("state"));
        assertNull(queue.task(task.id(),false).stdout());
        var next=queue.enqueue(server(7),"EXEC",payload,false,30,null,null,null);
        assertEquals(next.id(),claim(auth));
        queue.started(auth,next.id());
        queue.result(auth,next.id(),new AgentProtocol.Result("SUCCEEDED",0,"fresh-exists","",1L));
        assertEquals("SUCCEEDED",queue.task(next.id(),false).state());
        assertEquals("RESOLVED_UNKNOWN",queue.task(task.id(),false).state());
    }
    @Test void revocationCommittedBetweenAuthenticationAndResultLockRejectsOldCredential() throws Exception {
        String auth=activeAgent(7,"WAS");var task=enqueueExec(false);claim(auth);queue.started(auth,task.id());
        CountDownLatch authenticated=new CountDownLatch(1), revoked=new CountDownLatch(1);
        doAnswer(call->{
            Object identity=call.callRealMethod();authenticated.countDown();
            assertTrue(revoked.await(5,TimeUnit.SECONDS));return identity;
        }).when(target).authenticate(auth,true);
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<?> completion=executor.submit(()->assertEquals(401,assertThrows(ResponseStatusException.class,
                ()->queue.result(auth,task.id(),new AgentProtocol.Result("SUCCEEDED",0,"stale", "",1L))).getStatusCode().value()));
            assertTrue(authenticated.await(5,TimeUnit.SECONDS));queue.revoke(7);revoked.countDown();
            completion.get(8,TimeUnit.SECONDS);
            assertEquals("UNKNOWN",queue.task(task.id(),false).state());
            assertNull(queue.task(task.id(),false).resultHash());
        } finally { revoked.countDown();executor.shutdownNow(); }
    }
}
