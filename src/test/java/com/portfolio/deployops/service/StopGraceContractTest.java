package com.portfolio.deployops.service;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.RuntimeBindingForm;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import java.util.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import tools.jackson.databind.json.JsonMapper;
class StopGraceContractTest {
 @Test void numericBoundsAndOldJsonDefaultAreExplicit() {
  assertEquals(30,StopGrace.seconds(null)); assertEquals(1,StopGrace.seconds(1)); assertEquals(300,StopGrace.seconds(300));
  for(int bad:new int[]{0,-1,301,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->StopGrace.seconds(bad));
  var mapper=JsonMapper.builder().build();
  var old=mapper.readValue("{\"componentId\":0,\"mode\":\"NONE\",\"persistentLogs\":false,\"tmpMiB\":0}",ManagedRuntimeSettings.class);
  assertEquals(30,old.stopGraceSeconds());
  for(String bad:List.of("30.5","\"30\"","true","0","301"))
   assertThrows(Exception.class,()->mapper.readValue("{\"componentId\":0,\"mode\":\"NONE\",\"stopGraceSeconds\":"+bad+"}",RuntimeBindingForm.class));
  assertThrows(Exception.class,()->mapper.readValue("{\"componentId\":0,\"mode\":\"NONE\",\"stopGraceSeconds\":0}",ManagedRuntimeSettings.class));
 }
 @Test void serverRejectsOutOfRangeBeforeBindingWrite() {
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var projects=mock(ProjectRepository.class);var comps=mock(DeployComponentRepository.class);
  when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).build()));when(comps.findActiveByProjectId(1L)).thenReturn(List.of());
  var svc=new RuntimeConfigurationService(jdbc,projects,comps,mock(ProjectPermissionService.class),mock(SecretCryptoService.class),mock(AuditLogService.class));
  var actor=new SecurityUserDetails(User.builder().id(1L).role(UserRole.OPERATOR).build());
  for(int bad:new int[]{0,301,-1}) assertThrows(org.springframework.web.server.ResponseStatusException.class,()->svc.bind(1L,DeployEnvironment.TEST,new RuntimeBindingForm(0L,"NONE",null,false,0,"reason",bad),actor));
  verifyNoInteractions(jdbc);
 }
 @Test void migrationPersistsDefaultAndConstraintAndIndependentEnvironmentValues() throws Exception {
  try(var c=DriverManager.getConnection("jdbc:h2:mem:stopgrace;MODE=MySQL;DB_CLOSE_DELAY=-1")) {var s=c.createStatement();s.execute("CREATE TABLE runtime_bindings(project_id BIGINT,environment VARCHAR(8),component_id BIGINT)");s.execute("INSERT INTO runtime_bindings VALUES(1,'TEST',0),(1,'PROD',0)");
   for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V41__runtime_stop_grace.sql")).split(";")) if(!sql.isBlank()) s.execute(sql);
   try(var rs=s.executeQuery("SELECT stop_grace_seconds FROM runtime_bindings")){while(rs.next())assertEquals(30,rs.getInt(1));}
   s.execute("UPDATE runtime_bindings SET stop_grace_seconds=60 WHERE environment='PROD'");
   try(var rs=s.executeQuery("SELECT stop_grace_seconds FROM runtime_bindings WHERE environment='TEST'")){assertTrue(rs.next());assertEquals(30,rs.getInt(1));}
   assertThrows(SQLException.class,()->s.execute("UPDATE runtime_bindings SET stop_grace_seconds=301"));assertThrows(SQLException.class,()->s.execute("UPDATE runtime_bindings SET stop_grace_seconds=0"));
  }
 }
 @Test void actualJdbcBindingWriteAndReadKeepEnvironmentAndComponentGraceDistinct() throws Exception {
  var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:gracebinding;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE runtime_bindings(project_id BIGINT,environment VARCHAR(8),component_id BIGINT,mode VARCHAR(32),revision_id BIGINT,persistent_logs BOOLEAN,tmp_mib INT,encrypted_reason VARCHAR(500),PRIMARY KEY(project_id,environment,component_id))");
  jdbc.execute("CREATE TABLE runtime_env_revisions(id BIGINT,record_fingerprint VARCHAR(100))");
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V41__runtime_stop_grace.sql")).split(";")) if(!sql.isBlank()) jdbc.execute(sql);
  var projects=mock(ProjectRepository.class);var comps=mock(DeployComponentRepository.class);var crypto=mock(SecretCryptoService.class);
  when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).build()));when(comps.findActiveByProjectId(1L)).thenReturn(List.of(DeployComponent.builder().id(4L).artifactType(ArtifactType.CONTAINER).build()));when(crypto.encryptIfPlain("reason")).thenReturn("enc:fixture");
  var svc=new RuntimeConfigurationService(jdbc,projects,comps,mock(ProjectPermissionService.class),crypto,mock(AuditLogService.class));var actor=new SecurityUserDetails(User.builder().id(1L).role(UserRole.OPERATOR).build());
  svc.bind(1L,DeployEnvironment.TEST,new RuntimeBindingForm(4L,"NONE",null,false,0,"reason",60),actor);
  svc.bind(1L,DeployEnvironment.PROD,new RuntimeBindingForm(4L,"NONE",null,false,0,"reason",90),actor);
  assertEquals(60,svc.bindings(1L,DeployEnvironment.TEST).getFirst().stopGraceSeconds());assertEquals(90,svc.bindings(1L,DeployEnvironment.PROD).getFirst().stopGraceSeconds());
  svc.bind(1L,DeployEnvironment.TEST,new RuntimeBindingForm(4L,"NONE",null,false,0,"reason",45),actor);
  assertEquals(45,svc.bindings(1L,DeployEnvironment.TEST).getFirst().stopGraceSeconds());assertEquals(90,svc.bindings(1L,DeployEnvironment.PROD).getFirst().stopGraceSeconds());
 }
 @Test void sshBudgetCoversBothStopAttemptsAndHealthWithoutReducingConfiguredBudget() {
  assertEquals(780,SshCommandService.minimumStopGraceTimeout(List.of("--stop-grace-seconds","300"),20));
  assertEquals(1000,SshCommandService.minimumStopGraceTimeout(List.of("--stop-grace-seconds","30"),1000));
  assertEquals(20,SshCommandService.minimumStopGraceTimeout(List.of("status"),20));
  for(String bad:List.of("0","301","1.5","+1","1;echo x")) assertThrows(RuntimeException.class,()->SshCommandService.minimumStopGraceTimeout(List.of("--stop-grace-seconds",bad),20));
 }
 @Test void unsupportedAgentStopsBeforeAnyLifecycleOrUploadAndSupportedLegacyUsesDefault30() {
  var ssh=mock(SshCommandService.class);var svc=new RemoteDeployService(ssh,mock(RemoteCommandLogService.class),mock(SystemSettingService.class),mock(ImageArtifactService.class));
  var p=Project.builder().id(1L).name("fixture").build();var server=Server.builder().id(2L).serverType(ServerType.WAS).environment(ServerEnvironment.TEST).deployAccount("root").build();
  when(ssh.execute(eq(server),anyList())).thenReturn(new RemoteCommandResult(1,"","Unsupported action","fixture",null,null,0));
  assertThrows(RemoteExecutionException.class,()->svc.controlService(p,server,"stop"));verify(ssh,times(1)).execute(eq(server),argThat(a->a.contains("capabilities")));verify(ssh,never()).copyFile(any(),any(),any());
  reset(ssh);when(ssh.execute(eq(server),anyList())).thenReturn(new RemoteCommandResult(0,"STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1","","fixture",null,null,0));svc.controlService(p,server,"restart");
  verify(ssh).execute(eq(server),argThat(a->a.contains("restart") && a.contains("--stop-grace-seconds") && a.get(a.indexOf("--stop-grace-seconds")+1).equals("30")));
 }
 @Test void controlResolvesCurrentServiceAndEnvironmentAndStatusDoesNotRequireNewAgent() {
  var ssh=mock(SshCommandService.class);var runtime=mock(RuntimeConfigurationService.class);var svc=new RemoteDeployService(ssh,mock(RemoteCommandLogService.class),mock(SystemSettingService.class),mock(ImageArtifactService.class),runtime);
  var p=Project.builder().id(1L).name("fixture").components(List.of(DeployComponent.builder().id(4L).active(true).role(ServerType.WAS).artifactType(ArtifactType.CONTAINER).build())).build();
  var server=Server.builder().id(2L).serverType(ServerType.WAS).environment(ServerEnvironment.PROD).deployAccount("root").build();
  when(runtime.bindings(1L,DeployEnvironment.PROD)).thenReturn(List.of(new ManagedRuntimeSettings(4L,"NONE",null,null,false,0,90)));
  when(ssh.execute(eq(server),anyList())).thenReturn(new RemoteCommandResult(0,"STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1","","fixture",null,null,0));svc.controlService(p,server,"stop");
  verify(ssh).execute(eq(server),argThat(a->a.contains("stop") && a.contains("90")));verify(runtime).bindings(1L,DeployEnvironment.PROD);
  reset(ssh);svc.controlService(p,server,"status");verify(ssh,never()).execute(eq(server),argThat(a->a.contains("capabilities") || a.contains("--stop-grace-seconds")));
 }
 @Test void stopGraceOnlyAgentCannotMutateProjectWithoutRecoveryAndOwnership() {
  var ssh=mock(SshCommandService.class);var svc=new RemoteDeployService(ssh,mock(RemoteCommandLogService.class),mock(SystemSettingService.class),mock(ImageArtifactService.class));
  var project=Project.builder().id(1L).name("fixture").build();
  var server=Server.builder().id(2L).serverType(ServerType.WAS).environment(ServerEnvironment.TEST).deployAccount("root").build();
  for(String capabilities:List.of("STOP_GRACE_VERSION=1","STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1")) {
   reset(ssh);when(ssh.execute(eq(server),anyList())).thenReturn(new RemoteCommandResult(0,capabilities,"","fixture",null,null,0));
   assertThrows(RemoteExecutionException.class,()->svc.controlService(project,server,"stop"));
   verify(ssh,never()).execute(eq(server),argThat(a->a.contains("stop")));
  }
 }
}
