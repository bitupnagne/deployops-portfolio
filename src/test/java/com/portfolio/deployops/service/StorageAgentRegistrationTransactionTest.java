package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.AgentProtocol;
import com.portfolio.deployops.dto.StorageLocationForm;
import com.portfolio.deployops.repository.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction proxies and the shipped FK queue, without an OS or Docker process. */
class StorageAgentRegistrationTransactionTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    AgentQueueService queue;
    StorageMountService storage;
    AuditLogService audit;
    String authorization;
    ExecutorService worker;
    final AtomicReference<Runnable> afterRemote=new AtomicReference<>(()->{});
    final SecurityUserDetails admin=new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).build());
    final StorageLocationForm location=new StorageLocationForm("qa-data","LOCAL","/srv/offline-qa/service-data","/","overlay","overlay",true,"로컬 저장소 등록");

    @BeforeEach void prepareDatabase() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:storage_"+UUID.randomUUID()+";MODE=MariaDB;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000","sa","");
        jdbc=new JdbcTemplate(source);
        transactions=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE server(id BIGINT PRIMARY KEY,name VARCHAR(255),server_type VARCHAR(16),environment VARCHAR(16),ip_address VARCHAR(255))");
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V53__outbound_server_agents.sql"));
        }
        jdbc.execute("CREATE TABLE server_storage_location(server_id BIGINT,storage_key VARCHAR(100),kind VARCHAR(16),root_path VARCHAR(500),mount_target VARCHAR(500),mount_source VARCHAR(512),fs_type VARCHAR(32),enabled BOOLEAN,PRIMARY KEY(server_id,storage_key),FOREIGN KEY(server_id) REFERENCES server(id))");
        jdbc.update("INSERT INTO server(id,name,server_type,environment,ip_address,control_mode) VALUES(7,'WAS7','WAS','TEST','192.0.2.7','AGENT')");
        var servers=mock(ServerRepository.class);
        when(servers.findById(anyLong())).thenAnswer(call->{
            long id=call.getArgument(0);
            var rows=jdbc.query("SELECT id,name,server_type,environment,ip_address,control_mode,agent_binding_id FROM server WHERE id=?",
                (r,n)->Server.builder().id(r.getLong(1)).name(r.getString(2)).serverType(ServerType.valueOf(r.getString(3)))
                    .environment(ServerEnvironment.valueOf(r.getString(4))).ipAddress(r.getString(5))
                    .controlMode(ServerControlMode.valueOf(r.getString(6))).agentBindingId(r.getString(7)).build(),id);
            return rows.isEmpty()?Optional.empty():Optional.of(rows.getFirst());
        });
        var mapper=JsonMapper.builder().build();
        queue=proxied(new AgentQueueService(jdbc,servers,new SecretCryptoService("storage-transaction-test-key"),mapper),AgentQueueService.class);
        String code=String.valueOf(queue.issueCode(7).get("enrollmentCode"));
        authorization="Bearer "+queue.enroll(new AgentProtocol.Enrollment(7L,code,"WAS","storage-machine7","was7","1.0.0")).get("credential");
        queue.approve(7);
        queue.heartbeat(authorization,new AgentProtocol.Heartbeat("was7","1.0.0",Map.of("ready",true)));
        audit=mock(AuditLogService.class);
        var ssh=mock(SshCommandService.class);
        var dispatcher=new AgentDispatchService(queue,mapper);
        when(ssh.executeSensitive(any(Server.class),anyList(),any(byte[].class))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"A remote wait must not retain a caller DB transaction or server row lock");
            RemoteCommandResult result=dispatcher.execute(call.getArgument(0),call.getArgument(1),call.getArgument(2),true,5);
            afterRemote.get().run();
            return result;
        });
        storage=proxied(new StorageMountService(jdbc,servers,mock(ProjectRepository.class),mock(DeployComponentRepository.class),
            mock(ProjectPermissionService.class),ssh,mapper,audit,transactions),StorageMountService.class);
        worker=Executors.newSingleThreadExecutor();
    }

    <T> T proxied(T target,Class<T> type) {
        var proxy=new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    Future<String> completeOneAgentTask(boolean succeed) {
        return worker.submit(()->{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<deadline) {
                var polled=queue.poll(authorization).get("task");
                if(polled instanceof Map<?,?> task) {
                    String id=String.valueOf(task.get("id"));
                    assertEquals(List.of("sudo",StorageMountService.HELPER,"register"),task.get("argv"));
                    String input=new String(Base64.getDecoder().decode(String.valueOf(task.get("stdinBase64"))),StandardCharsets.UTF_8);
                    var body=JsonMapper.builder().build().readTree(input);
                    assertEquals("qa-data",body.path("locations").get(0).path("storageKey").asText());
                    assertEquals("/srv/offline-qa/service-data",body.path("locations").get(0).path("rootPath").asText());
                    queue.heartbeat(authorization,new AgentProtocol.Heartbeat("was7","1.0.0",Map.of("ready",true)));
                    queue.started(authorization,id);
                    queue.result(authorization,id,new AgentProtocol.Result(succeed?"SUCCEEDED":"FAILED",succeed?0:1,"","",1L));
                    return id;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Agent task did not commit for a separate consumer");
        });
    }

    @AfterEach void closeDatabase() throws Exception {
        if(worker!=null) {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));
        }
        if(jdbc!=null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test void enqueueHeartbeatAndCompletionWorkWhileCallerTransactionIsSuspended() throws Exception {
        var completed=completeOneAgentTask(true);
        new TransactionTemplate(transactions).execute(status->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            storage.saveLocation(7L,location,admin);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),"The caller transaction resumes after registration");
            return null;
        });
        String taskId=completed.get(10,TimeUnit.SECONDS);
        assertEquals("SUCCEEDED",queue.task(taskId,false).state());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM server_storage_location WHERE server_id=7 AND storage_key='qa-data'",Integer.class));
        verify(audit).recordCurrentRequired(eq("STORAGE_LOCATION_UPDATE"),eq("SERVER"),eq("7"),anyString(),eq(AuditResult.SUCCESS));
    }

    @Test void failedNativeRegistrationLeavesNoLocalInventoryOrSuccessAudit() throws Exception {
        var completed=completeOneAgentTask(false);
        assertThrows(ResponseStatusException.class,()->storage.saveLocation(7L,location,admin));
        assertEquals("FAILED",queue.task(completed.get(10,TimeUnit.SECONDS),false).state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM server_storage_location",Integer.class));
        verifyNoInteractions(audit);
    }

    @Test void concurrentInventoryMutationCannotBeOverwrittenByAStaleRemoteCompletion() throws Exception {
        var completed=completeOneAgentTask(true);
        afterRemote.set(()->jdbc.update("INSERT INTO server_storage_location VALUES(7,'other','LOCAL','/srv/other','/','overlay','overlay',true)"));
        assertTrue(assertThrows(ResponseStatusException.class,()->storage.saveLocation(7L,location,admin)).getReason().contains("변경"));
        assertEquals("SUCCEEDED",queue.task(completed.get(10,TimeUnit.SECONDS),false).state());
        assertEquals(List.of("other"),jdbc.queryForList("SELECT storage_key FROM server_storage_location",String.class));
        verifyNoInteractions(audit);
    }

    @Test void registrationGenerationChangeCannotAcceptACompletionForTheOldBinding() throws Exception {
        var completed=completeOneAgentTask(true);
        afterRemote.set(()->jdbc.update("UPDATE server SET agent_binding_id='replacement-binding' WHERE id=7"));
        assertThrows(ResponseStatusException.class,()->storage.saveLocation(7L,location,admin));
        assertEquals("SUCCEEDED",queue.task(completed.get(10,TimeUnit.SECONDS),false).state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM server_storage_location",Integer.class));
        verifyNoInteractions(audit);
    }

    @Test void auditFailureRollsBackLocalInventoryButPreservesCompletedNativeEvidence() throws Exception {
        var completed=completeOneAgentTask(true);
        doThrow(new IllegalStateException("required audit unavailable")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        assertThrows(IllegalStateException.class,()->storage.saveLocation(7L,location,admin));
        assertEquals("SUCCEEDED",queue.task(completed.get(10,TimeUnit.SECONDS),false).state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM server_storage_location",Integer.class));
    }
}
