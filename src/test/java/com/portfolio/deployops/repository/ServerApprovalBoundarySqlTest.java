package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

class ServerApprovalBoundarySqlTest {
    @Test void ordinaryAndLockingMapperReadsBothPreserveGroupApprovalIdentity() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:serverApprovalBoundary;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = ds.getConnection(); var stmt = c.createStatement()) {
            stmt.execute("""
                CREATE TABLE server_group (
                    id BIGINT PRIMARY KEY, name VARCHAR(100), environment VARCHAR(20),
                    rolling_interval_sec INT, min_available INT, max_unavailable INT,
                    observation_seconds INT, failure_policy VARCHAR(20),
                    approval_policy_id BIGINT, description VARCHAR(255),
                    was_vip_enabled BOOLEAN, was_vip_address VARCHAR(255))
                """);
            stmt.execute("""
                CREATE TABLE server (
                    id BIGINT PRIMARY KEY, name VARCHAR(100), ip_address VARCHAR(255),
                    environment VARCHAR(20), server_type VARCHAR(20), ssh_port INT,
                    deploy_account VARCHAR(100), auth_type VARCHAR(20), deploy_path VARCHAR(255),
                    memo VARCHAR(255), last_checked_at TIMESTAMP, status VARCHAR(20),
                    order_index INT, created_at TIMESTAMP, auto_start BOOLEAN,
                    startup_service VARCHAR(255), server_group_id BIGINT,
                    control_mode VARCHAR(16) DEFAULT 'SSH', agent_binding_id VARCHAR(64))
                """);
            stmt.execute("INSERT INTO server_group(id,name,environment,approval_policy_id,was_vip_enabled,was_vip_address) VALUES(1,'protected','TEST',4,true,'198.18.21.100')");
            stmt.execute("INSERT INTO server(id,name,ip_address,environment,server_type,ssh_port,server_group_id) VALUES(7,'was','198.18.21.3','TEST','WAS',2022,1)");
        }
        var cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.addMapper(ServerRepository.class);
        try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession()) {
            var repo = session.getMapper(ServerRepository.class);
            assertThat(repo.findAll().getFirst().getServerGroup().getApprovalPolicyId()).isEqualTo(4L);
            assertThat(repo.findById(7L).orElseThrow().getServerGroup().getApprovalPolicyId()).isEqualTo(4L);
            assertThat(repo.findByServerGroupId(1L).getFirst().getServerGroup().getApprovalPolicyId()).isEqualTo(4L);
            assertThat(repo.lockGroupsForServerMutation()).containsExactly(1L);
            assertThat(repo.findByIdForMutation(7L).orElseThrow().getServerGroup().getApprovalPolicyId()).isEqualTo(4L);
            assertThat(repo.findAllForMutation().getFirst().getServerGroup().getApprovalPolicyId()).isEqualTo(4L);
            assertThat(repo.findById(7L).orElseThrow().getServerGroup().getWasVipEnabled()).isTrue();
            assertThat(repo.findByIdForMutation(7L).orElseThrow().getServerGroup().getWasVipAddress()).isEqualTo("198.18.21.100");
            var stale = repo.findById(7L).orElseThrow();
            // A connectivity probe must never write its stale group/endpoint back to inventory.
            session.getConnection().createStatement().execute("UPDATE server SET ip_address='198.18.21.4', server_group_id=NULL WHERE id=7");
            stale.setStatus(com.portfolio.deployops.domain.ServerStatus.NORMAL);
            stale.setLastCheckedAt(java.time.LocalDateTime.now());
            repo.updateConnectivityStatus(stale);
            session.clearCache();
            var current = repo.findById(7L).orElseThrow();
            assertThat(current.getIpAddress()).isEqualTo("198.18.21.4");
            assertThat(current.getServerGroup() == null || current.getServerGroup().getId() == null).isTrue();
            assertThat(current.getStatus()).isEqualTo(com.portfolio.deployops.domain.ServerStatus.NORMAL);
            session.rollback();
        }
    }
}
