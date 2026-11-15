package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.*;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.nio.charset.StandardCharsets;

class DeployComponentEnvironmentHostSqlTest {
    @Test
    void realMigrationAndMapperKeepCommonAndEnvironmentOverridesIndependent() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:componentEnvironmentHost;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = ds.getConnection(); var stmt = c.createStatement()) {
            stmt.execute("""
                CREATE TABLE deploy_component (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT, code VARCHAR(50),
                    display_name VARCHAR(100), role VARCHAR(20), artifact_type VARCHAR(30),
                    image_repository VARCHAR(255), web_root VARCHAR(500), build_tool VARCHAR(30),
                    dockerfile_path VARCHAR(500), docker_context_path VARCHAR(500), build_command VARCHAR(500),
                    artifact_path VARCHAR(500), health_check_mode VARCHAR(30), health_check_path VARCHAR(255),
                    health_check_port INT, container_user VARCHAR(50), container_privileged BOOLEAN,
                    container_cap_add VARCHAR(255), container_read_only_root_fs BOOLEAN, deploy_order INT, active BOOLEAN)
                """);
            String common = new String(getClass().getResourceAsStream("/db/migration/V30__proxy_health_host.sql").readAllBytes(), StandardCharsets.UTF_8);
            stmt.execute(common);
            stmt.execute("INSERT INTO deploy_component(project_id,code,proxy_health_host) VALUES(1,'legacy','common.internal')");
            String migration = new String(getClass().getResourceAsStream("/db/migration/V36__proxy_health_host_environment.sql").readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").trim();
            // H2 groups ADD columns differently; retain the shipped definitions and only split the grouping.
            String columns = migration.substring("ALTER TABLE deploy_component".length()).replace(";", "").trim();
            for (String column : columns.split(",\\s*(?=ADD COLUMN)")) {
                stmt.execute("ALTER TABLE deploy_component " + column);
            }
        }
        var cfg = new Configuration(new Environment("fixture", new JdbcTransactionFactory(), ds));
        cfg.addMapper(DeployComponentRepository.class);
        try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession(true)) {
            var repo = session.getMapper(DeployComponentRepository.class);
            DeployComponent legacy = repo.findById(1L).orElseThrow();
            assertThat(legacy.getProxyHealthHostTest()).isNull();
            assertThat(legacy.getProxyHealthHostProd()).isNull();
            assertThat(legacy.resolveProxyHealthHost(DeployEnvironment.TEST)).isEqualTo("common.internal");
            DeployComponent component = DeployComponent.builder().projectId(1L).code("web")
                .role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD).active(true)
                .proxyHealthHost("common.internal").proxyHealthHostTest("test.internal")
                .proxyHealthHostProd("prod.internal").build();
            assertThat(repo.insert(component)).isEqualTo(1);
            DeployComponent read = repo.findById(component.getId()).orElseThrow();
            assertThat(read.resolveProxyHealthHost(DeployEnvironment.TEST)).isEqualTo("test.internal");
            assertThat(read.resolveProxyHealthHost(DeployEnvironment.PROD)).isEqualTo("prod.internal");
            read.setProxyHealthHostTest(null);
            assertThat(repo.update(read)).isEqualTo(1);
            DeployComponent changed = repo.findById(component.getId()).orElseThrow();
            assertThat(changed.resolveProxyHealthHost(DeployEnvironment.TEST)).isEqualTo("common.internal");
            assertThat(changed.resolveProxyHealthHost(DeployEnvironment.PROD)).isEqualTo("prod.internal");
            assertThat(repo.findByProjectId(1L)).hasSize(2);
        }
    }
}
