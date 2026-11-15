package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import com.portfolio.deployops.repository.ProjectImageHistoryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class Phase5OperationsContractTest {
    @Test
    void nginxCurrentGenerationUsesActualAppliedOrder() {
        Configuration config = new Configuration();
        config.addMapper(NginxChangeRequestRepository.class);
        String latest = config.getMappedStatement(NginxChangeRequestRepository.class.getName() + ".findLatestCompletedId")
            .getBoundSql(java.util.Map.of("serverId", 1L, "policyName", "portal")).getSql();
        String rollbackCas = config.getMappedStatement(NginxChangeRequestRepository.class.getName() + ".acquireRollbackApproval")
            .getBoundSql(java.util.Map.of("id", 3L, "approverId", 2L, "sourceId", 1L, "serverId", 1L, "policyName", "portal")).getSql();
        assertThat(latest).contains("ORDER BY applied_at DESC, id DESC").doesNotContain("MAX(id)");
        assertThat(rollbackCas).contains("ORDER BY applied_at DESC, id DESC");
    }

    @Test
    void protectedImagesIncludeCurrentPerEnvironmentAndSharedRepository() {
        Configuration config = new Configuration();
        config.addMapper(ProjectImageHistoryRepository.class);
        String sql = config.getMappedStatement(ProjectImageHistoryRepository.class.getName() + ".findProtectedDigests")
            .getBoundSql(java.util.Map.of("imageRepository", "registry/team/app",
                "limit", ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).getSql();
        assertThat(sql).contains("h2.project_id = h.project_id", "h2.environment = h.environment",
            "h.image_repository = ?", "UNION", "PARTITION BY project_id, environment",
            "ORDER BY created_at DESC, id DESC", "history_rank <= ?");
    }

    @Test
    void visibleImageHistoryQueryUsesTheSharedRollbackWindow() {
        Configuration config = new Configuration();
        config.addMapper(ProjectImageHistoryRepository.class);
        String sql = config.getMappedStatement(ProjectImageHistoryRepository.class.getName() + ".findRecentByProjectId")
            .getBoundSql(java.util.Map.of("projectId", 42L,
                "limit", ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)).getSql();

        assertThat(sql).contains("WHERE project_id = ?", "ORDER BY created_at DESC, id DESC", "LIMIT ?");
    }

    @Test
    void rollbackHistoryLookupLoadsTheOriginalDeployScopeAndStrategy() {
        Configuration config = new Configuration();
        config.addMapper(ProjectImageHistoryRepository.class);
        String sql = config.getMappedStatement(ProjectImageHistoryRepository.class.getName() + ".findById")
            .getBoundSql(java.util.Map.of("id", 19L)).getSql();
        var mappings = config.getResultMap(ProjectImageHistoryRepository.class.getName() + ".projectImageHistoryResult")
            .getResultMappings();

        assertThat(sql).contains("LEFT JOIN deploy_request source", "source.deploy_scope AS source_deploy_scope",
            "source.deploy_strategy AS source_deploy_strategy");
        assertThat(mappings).extracting("property")
            .contains("deployRequest.deployScope", "deployRequest.deployStrategy");
    }

    @Test
    void ciEditResponseAndPipelineGovernanceAreWiredToTheUiContract() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/portfolio/deployops/controller/GitlabApiController.java"));
        assertThat(controller).contains("result.put(\"baseCommitSha\", baseCommitSha)")
            .contains("gitlabId, \".gitlab-ci.yml\", baseCommitSha")
            .contains("PIPELINE_RETRY_GOVERNED")
            .contains("countByStatusInForProjectIds");
        String build = Files.readString(Path.of("src/main/java/com/portfolio/deployops/service/BuildOrchestrationService.java"));
        assertThat(build).contains("bindCreatedPipeline(request.getId(), pipelineId)");
    }

    @Test
    void nginxAgentChecksExpectedGenerationInsideItsLockAndRollbackHasErrorRestore() throws Exception {
        String script = Files.readString(Path.of("installer/deploy-agent/web/deploy-nginx-policy.sh"));
        assertThat(script).contains("--expected-current-request-id")
            .contains("current generation differs from approved source")
            .contains("current snapshot drift")
            .contains("RESTORE_ON_ERROR=\"$safety\"")
            .contains("emit_certificate_metadata \"$tls_dir/current/fullchain.pem\"");
        int lock = script.indexOf("flock -n 9");
        int dispatch = script.indexOf("case \"$ACTION\" in", lock);
        assertThat(lock).isGreaterThanOrEqualTo(0);
        assertThat(dispatch).isGreaterThan(lock);
        String rollback = script.substring(script.indexOf("rollback_policy(){"), script.indexOf("\ninspect(){"));
        assertThat(rollback.indexOf("verify_current")).isLessThan(rollback.indexOf("restore_backup \"$stage\""));
        assertThat(rollback).contains("safety recovery digest mismatch", "CURRENT_SNAPSHOT_SHA=$EXPECTED_SNAPSHOT");
    }
}
