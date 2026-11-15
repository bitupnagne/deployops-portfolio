package com.portfolio.deployops.repository;
import static org.assertj.core.api.Assertions.*;
import com.portfolio.deployops.domain.*;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.nio.charset.StandardCharsets;

class NginxTwoUpstreamSqlTest {
 @Test void realInsertReadAndLegacyMigrationDefaultsPreserveTypedPair() throws Exception {
  var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:nginxPair;MODE=MySQL;DB_CLOSE_DELAY=-1");
  try(var c=ds.getConnection();var s=c.createStatement()){
   s.execute("CREATE TABLE server(id BIGINT PRIMARY KEY,name VARCHAR,ip_address VARCHAR,server_type VARCHAR,ssh_port INT,deploy_account VARCHAR,auth_type VARCHAR,control_mode VARCHAR DEFAULT 'SSH',agent_binding_id VARCHAR)");
   s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,name VARCHAR,login_id VARCHAR)");
   String ddl=new String(getClass().getResourceAsStream("/db/migration/V5__nginx_change_management.sql").readAllBytes(),StandardCharsets.UTF_8);
   ddl=ddl.replaceAll("b'([01])'","$1").replaceAll("(?m)^  KEY.*\\n","").replaceAll("(?m)^  CONSTRAINT.*\\n","").replace(",\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;","\n);");s.execute(ddl);
   // H2 cannot parse MariaDB's multi-ADD/AFTER syntax. Preserve each real column
   // declaration while adapting only statement grouping/position for this mapper fixture.
   for(String file:new String[]{"V10__nginx_certificate_metadata.sql","V19__nginx_static_policy.sql","V32__nginx_static_api_proxy.sql","V34__nginx_secondary_upstream.sql","V35__nginx_rollback_native_snapshot.sql","V40__nginx_ordered_upstream_snapshots.sql","V43__nginx_approved_configuration_text.sql","V54__nginx_agent_target_binding.sql"}){
    String sql=new String(getClass().getResourceAsStream("/db/migration/"+file).readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$","").replaceAll(" AFTER [a-z_]+","");
    for(String statement:sql.split(";")){statement=statement.trim();if(statement.isBlank())continue;
     if(statement.startsWith("ALTER TABLE nginx_change_request")){String body=statement.substring("ALTER TABLE nginx_change_request".length()).trim();for(String column:body.split(",\\s*(?=ADD COLUMN)"))s.execute("ALTER TABLE nginx_change_request "+column);}else s.execute(statement);
    }
   }
   s.execute("INSERT INTO server(id,name,ip_address,server_type,ssh_port,deploy_account,auth_type) VALUES(1,'web','10.0.0.1','WEB',2022,'deploy','PASSWORD')");s.execute("INSERT INTO users VALUES(10,'requester','qa'),(20,'approver','approval-qa')");
  }
  var cfg=new Configuration(new Environment("fixture",new JdbcTransactionFactory(),ds));cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(NginxChangeRequestRepository.class);
  try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(true)){
   var repo=session.getMapper(NginxChangeRequestRepository.class);
   var r=NginxChangeRequest.builder().server(Server.builder().id(1L).build()).requestedBy(User.builder().id(10L).build()).status(NginxChangeStatus.PENDING).policyName("pair").serverName("localhost").listenPort(443).upstreamHost("10.0.0.11").upstreamPort(18080).secondaryUpstreamHost("10.0.0.12").secondaryUpstreamPort(18080).upstreamMaxFails(2).upstreamFailTimeoutSec(20).proxyConnectTimeoutSec(4).tlsProtocols("TLSv1.2").build();
   r.setRollbackSnapshotHash("a".repeat(64));r.setExpectedCurrentSnapshotHash("b".repeat(64));r.setExpectedNativeRequestId(99L);r.setRestoreTargetId(12L);assertThat(repo.insert(r)).isEqualTo(1);var read=repo.findById(r.getId()).orElseThrow();assertThat(read.getRollbackSnapshotHash()).isEqualTo("a".repeat(64));assertThat(read.getExpectedCurrentSnapshotHash()).isEqualTo("b".repeat(64));assertThat(read.getExpectedNativeRequestId()).isEqualTo(99L);assertThat(read.getRestoreTargetId()).isEqualTo(12L);read.setStatus(NginxChangeStatus.APPLYING);try(var c=ds.getConnection();var stmt=c.createStatement()){stmt.execute("UPDATE nginx_change_request SET status='APPLYING' WHERE id="+read.getId());}assertThat(repo.storeNativeProof(read.getId(),"a".repeat(64),"b".repeat(64),"c".repeat(64),2L)).isEqualTo(1);assertThat(repo.findById(read.getId()).orElseThrow().getNativeRequestId()).isEqualTo(2L);assertThat(read.getSecondaryUpstreamHost()).isEqualTo("10.0.0.12");assertThat(read.getSecondaryUpstreamPort()).isEqualTo(18080);assertThat(read.getUpstreamMaxFails()).isEqualTo(2);assertThat(read.getProxyConnectTimeoutSec()).isEqualTo(4);
   for(int i=0;i<3;i++)repo.insertUpstream(r.getId(),new NginxUpstream("PROXY",i,20L+i,"10.0.0."+(20+i),9090+i));
   assertThat(repo.findById(r.getId()).orElseThrow().getEffectiveUpstreams()).extracting(NginxUpstream::getHost).containsExactly("10.0.0.20","10.0.0.21","10.0.0.22");
   assertThat(repo.findAll().getFirst().getUpstreamSnapshots()).hasSize(3);
   assertThat(repo.findById(r.getId()).orElseThrow().getRawConfig()).isNull();
   r.setId(null);r.setPolicyName("raw-reviewed");r.setRawConfig("# 승인한 원문\nserver { listen 8080; }\n");r.setRawConfigSha256("d".repeat(64));
   repo.insert(r);var raw=repo.findById(r.getId()).orElseThrow();assertThat(raw.getRawConfig()).isEqualTo(r.getRawConfig());assertThat(raw.getRawConfigSha256()).isEqualTo(r.getRawConfigSha256());
   r.setRawConfig(null);r.setRawConfigSha256(null);
   r.setId(null);r.setPolicyName("legacy");r.setSecondaryUpstreamHost(null);r.setSecondaryUpstreamPort(null);r.setUpstreamMaxFails(1);r.setUpstreamFailTimeoutSec(10);r.setProxyConnectTimeoutSec(3);repo.insert(r);var legacy=repo.findById(r.getId()).orElseThrow();assertThat(legacy.hasSecondaryUpstream()).isFalse();assertThat(legacy.getEffectiveUpstreams()).hasSize(1);assertThat(legacy.getUpstreamFailTimeoutSec()).isEqualTo(10);
   assertThat(repo.findUpstreams(legacy.getId())).isEmpty();
   try(var c=ds.getConnection();var stmt=c.createStatement()){
    stmt.execute("UPDATE nginx_change_request SET status='PENDING',storage_key='fixture',created_at='2000-01-01' WHERE id="+read.getId());
   }
   assertThat(repo.findStalePendingWithUploads(java.time.LocalDateTime.now()).getFirst().getEffectiveUpstreams()).hasSize(3);
   try(var c=ds.getConnection();var stmt=c.createStatement()){
    stmt.execute("UPDATE nginx_change_request SET status='APPLIED',applied_at=CURRENT_TIMESTAMP,certificate_not_after='2030-01-01' WHERE id="+read.getId());
    stmt.execute("UPDATE server SET ip_address='10.9.9.9' WHERE id=1");
   }
   assertThat(repo.findCurrentCertificates().getFirst().getEffectiveUpstreams()).extracting(NginxUpstream::getHost).containsExactly("10.0.0.20","10.0.0.21","10.0.0.22");
   r.setId(null);r.setPolicyName("api");r.setPolicyType(NginxPolicyType.STATIC_SPA);r.setWebRoot("/var/www/qa");r.setApiProxyEnabled(true);r.setApiPrefix("/api/");r.setApiUpstreamHost("10.0.0.30");r.setApiUpstreamPort(9090);repo.insert(r);
   for(int i=0;i<3;i++)repo.insertUpstream(r.getId(),new NginxUpstream("API",i,30L+i,"10.0.0."+(30+i),9090+i));
   assertThat(repo.findById(r.getId()).orElseThrow().getEffectiveUpstreams()).extracting(NginxUpstream::getPurpose).containsOnly("API");

   assertThat(repo.acquireApproval(raw.getId(),20L)).isEqualTo(1);
   r.setId(null);r.setPolicyName("agent-policy");r.setRequestedControlMode(ServerControlMode.AGENT);r.setRequestedAgentBindingId("registration-1");
   try(var c=ds.getConnection();var stmt=c.createStatement()) { stmt.execute("UPDATE server SET control_mode='AGENT',agent_binding_id='registration-1' WHERE id=1"); }
   repo.insert(r);
   var agent=repo.findById(r.getId()).orElseThrow();
   assertThat(agent.getRequestedControlMode()).isEqualTo(ServerControlMode.AGENT);
   assertThat(agent.getRequestedAgentBindingId()).isEqualTo("registration-1");
   assertThat(agent.getServer().isAgentControlled()).isTrue();
   assertThat(agent.getServer().getAgentBindingId()).isEqualTo("registration-1");
   assertThat(repo.acquireApproval(legacy.getId(),20L)).isZero();
   try(var c=ds.getConnection();var stmt=c.createStatement()) { stmt.execute("UPDATE server SET agent_binding_id='replacement' WHERE id=1"); }
   assertThat(repo.acquireApproval(agent.getId(),20L)).isZero();
   assertThat(repo.findById(agent.getId()).orElseThrow().getStatus()).isEqualTo(NginxChangeStatus.PENDING);
   try(var c=ds.getConnection();var stmt=c.createStatement()) { stmt.execute("UPDATE server SET agent_binding_id='registration-1' WHERE id=1"); }
   assertThat(repo.acquireApproval(agent.getId(),20L)).isEqualTo(1);
   try(var c=ds.getConnection();var stmt=c.createStatement()) { stmt.execute("UPDATE nginx_change_request SET status='APPLIED',applied_at=CURRENT_TIMESTAMP WHERE id="+agent.getId()); }
   r.setId(null);r.setRollbackOfId(agent.getId());repo.insert(r);
   try(var c=ds.getConnection();var stmt=c.createStatement()) { stmt.execute("UPDATE server SET agent_binding_id='replacement' WHERE id=1"); }
   assertThat(repo.acquireRollbackApproval(r.getId(),20L,agent.getId(),1L,"agent-policy")).isZero();
   try(var c=ds.getConnection();var stmt=c.createStatement()) { stmt.execute("UPDATE server SET agent_binding_id='registration-1' WHERE id=1"); }
   assertThat(repo.acquireRollbackApproval(r.getId(),20L,agent.getId(),1L,"agent-policy")).isEqualTo(1);

  }
 }
}
