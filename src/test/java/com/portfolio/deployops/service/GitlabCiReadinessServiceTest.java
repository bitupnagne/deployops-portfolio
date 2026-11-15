package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.GitlabCiReadiness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitlabCiReadinessServiceTest {

    @Test
    void mixedComponentToolsBlockMissingSecondaryBuilderAndRequireComponentReleaseRuntime() throws Exception {
        readyFixture();
        project.setBuildTool(com.portfolio.deployops.domain.BuildToolType.MAVEN);
        project.setComponents(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().code("api")
                .artifactType(com.portfolio.deployops.domain.ArtifactType.CONTAINER)
                .dockerfilePath("api/Dockerfile.release").build(),
            com.portfolio.deployops.domain.DeployComponent.builder().code("web")
                .artifactType(com.portfolio.deployops.domain.ArtifactType.STATIC_BUNDLE)
                .buildTool(com.portfolio.deployops.domain.BuildToolType.NPM).dockerfilePath("web/Dockerfile").build()
        ));
        var variables = new java.util.ArrayList<Map<String, Object>>();
        for (String key : GitlabCiReadinessService.requiredVariables(project)) {
            if (!key.equals("CI_BUILD_IMAGE")) variables.add(variable(key, key.endsWith("_PASSWORD"), true));
        }
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(variables);
        var blocked = service.evaluate("token", null, project, "main");
        assertThat(blocked.isReady()).isFalse();
        assertThat(blocked.getMissingVariables()).containsExactly("CI_BUILD_IMAGE");
        assertThat(blocked.getRequiredVariables()).contains("MAVEN_SETTINGS_FILE", "MAVEN_BUILD_IMAGE", "RUNTIME_BASE_IMAGE");
        variables.add(variable("CI_BUILD_IMAGE", false, true));
        assertThat(service.evaluate("token", null, project, "main").isReady()).isTrue();
    }

    @Test
    void inactiveAndProxyComponentsDoNotChangeExistingSingleProjectBuilderContract() {
        project.setBuildTool(com.portfolio.deployops.domain.BuildToolType.MAVEN);
        var api = com.portfolio.deployops.domain.DeployComponent.builder().code("api")
            .artifactType(com.portfolio.deployops.domain.ArtifactType.CONTAINER).build();
        var proxy = com.portfolio.deployops.domain.DeployComponent.builder().code("proxy")
            .artifactType(com.portfolio.deployops.domain.ArtifactType.PROXY_RELOAD)
            .buildTool(com.portfolio.deployops.domain.BuildToolType.NPM).dockerfilePath("Dockerfile.release").build();
        project.setComponents(List.of(api, proxy));
        assertThat(GitlabCiReadinessService.requiredVariables(project)).contains("MAVEN_BUILD_IMAGE")
            .doesNotContain("CI_BUILD_IMAGE", "RUNTIME_BASE_IMAGE");
        proxy.setArtifactType(com.portfolio.deployops.domain.ArtifactType.STATIC_BUNDLE);
        proxy.setActive(false);
        assertThat(GitlabCiReadinessService.requiredVariables(project)).doesNotContain("CI_BUILD_IMAGE", "RUNTIME_BASE_IMAGE");
    }

    @Test
    void protectedMainAndProtectedVariablesCannotHideMissingMrTagPolicy() throws Exception {
        readyFixture();
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenReturn(new GitlabService.ImmutableTagPolicy(false, false));
        var result = service.evaluateForMergeRequestBuild("token", null, project, "main");
        assertThat(result.isProtectedRef()).isTrue();
        assertThat(result.getExecutableRunnerCount()).isEqualTo(1);
        assertThat(result.isReady()).isFalse();
        assertThat(result.getBlockers()).singleElement().asString()
            .contains("deploy/mr-*", "Settings > Repository > Protected tags", "Allowed to create");
    }

    @Test
    void existingMrPolicyPreservesRunnerAndVariableBlockers() throws Exception {
        readyFixture();
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenReturn(new GitlabService.ImmutableTagPolicy(true, true));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(List.of());
        var result = service.evaluateForMergeRequestBuild("token", null, project, "main");
        assertThat(result.isReady()).isFalse();
        assertThat(result.getMissingVariables()).contains("NEXUS_REGISTRY_PASSWORD");
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("승인·병합된 MR 증거") && w.contains("배포 권한"))
            .noneMatch(w -> w.contains("요청자의 GitLab PAT"));
    }

    @Test
    void blockedCreationAndUnreadableMrPolicyFailClosedWithoutSensitiveExceptionText() throws Exception {
        readyFixture();
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenReturn(new GitlabService.ImmutableTagPolicy(true, false));
        assertThat(service.evaluateForMergeRequestBuild("token", null, project, "main").getBlockers())
            .anyMatch(b -> b.contains("No access"));
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenThrow(new IllegalArgumentException("private-sentinel"));
        var result = service.evaluateForMergeRequestBuild("token", null, project, "main");
        assertThat(result.isReady()).isFalse();
        assertThat(result.getBlockers()).anyMatch(b -> b.contains("조회에 실패"))
            .noneMatch(b -> b.contains("private-sentinel"));
    }

    @Test
    void validMrPolicyIsReadOnlyAndGenericPipelineReadinessDoesNotRequireIt() throws Exception {
        readyFixture();
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenReturn(new GitlabService.ImmutableTagPolicy(true, true));
        assertThat(service.evaluateForMergeRequestBuild("token", null, project, "main").isReady()).isTrue();
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenReturn(new GitlabService.ImmutableTagPolicy(false, false));
        assertThat(service.evaluate("token", null, project, "main").isReady()).isTrue();
    }

    @Test
    void coveringPolicyDisplaysActualMatchedNameAndPreservesOtherReadinessGuards() throws Exception {
        readyFixture();
        when(gitlabService.getImmutableMrTagPolicy("token", null, 101L))
            .thenReturn(new GitlabService.ImmutableTagPolicy(true, true, "deploy/*"));
        var result = service.evaluateForMergeRequestBuild("token", null, project, "main");
        assertThat(result.isReady()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("규칙 'deploy/*'"))
            .noneMatch(w -> w.contains("규칙 'deploy/mr-*'를 확인"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(List.of());
        assertThat(service.evaluateForMergeRequestBuild("token", null, project, "main").isReady()).isFalse();
    }

    private void readyFixture() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L))
            .thenReturn(List.of(runnerSummary(77L, "online", false)));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("offline"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", false, true), variable("NEXUS_REGISTRY_PASSWORD", true, true)));
    }

    private final GitlabService gitlabService = mock(GitlabService.class);
    private final GitlabCiReadinessService service = new GitlabCiReadinessService(gitlabService);
    private final Project project = Project.builder()
        .id(1L)
        .name("업무 웹")
        .gitlabProjectId(101L)
        .build();

    @Test
    void readyWhenOfflineTaggedRunnerAndRequiredVariablesExist() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("docker", "offline"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", false, true),
            variable("NEXUS_REGISTRY_PASSWORD", true, true)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "main");

        assertThat(result.isReady()).isTrue();
        assertThat(result.isProtectedRef()).isTrue();
        assertThat(result.getRefType()).isEqualTo("BRANCH");
        assertThat(result.getRunnerTag()).isEqualTo("offline");
        assertThat(result.getMatchingOnlineRunnerCount()).isEqualTo(1);
        assertThat(result.getExecutableRunnerCount()).isEqualTo(1);
        assertThat(result.getMissingVariables()).isEmpty();
        assertThat(result.getBlockers()).isEmpty();
    }

    @Test
    void blocksOfflinePausedOrWrongTagRunnerAndMissingVariable() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(71L, "offline", false),
            runnerSummary(72L, "online", true),
            runnerSummary(73L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 73L)).thenReturn(
            runnerDetails(73L, "online", false, List.of("docker"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", false, true)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "main");

        assertThat(result.isReady()).isFalse();
        assertThat(result.getMissingVariables()).containsExactly("NEXUS_REGISTRY_PASSWORD");
        assertThat(result.getBlockers())
            .anyMatch(message -> message.contains("offline") && message.contains("Runner"))
            .anyMatch(message -> message.contains("NEXUS_REGISTRY_PASSWORD"));
        assertThatThrownBy(() -> service.assertReady("token", null, project, "main"))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("사전점검 실패");
    }

    @Test
    void warnsWhenPasswordIsNotMaskedOrVariablesAreNotProtected() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("offline"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", false, false),
            variable("NEXUS_REGISTRY_PASSWORD", false, false)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "main");

        assertThat(result.isReady()).isTrue();
        assertThat(result.getWarnings())
            .anyMatch(message -> message.contains("Masked"))
            .anyMatch(message -> message.contains("Protected"));
    }

    @Test
    void environmentScopedVariableDoesNotSatisfyGlobalImageJob() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("offline"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", false, true),
            Map.of(
                "key", "NEXUS_REGISTRY_PASSWORD",
                "masked", true,
                "protected", true,
                "environmentScope", "production"
            )
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "main");

        assertThat(result.isReady()).isFalse();
        assertThat(result.getMissingVariables()).containsExactly("NEXUS_REGISTRY_PASSWORD");
    }

    @Test
    void blocksUnprotectedRefWithProtectedRunnerAndProtectedVariables() throws Exception {
        when(gitlabService.getRefProtection("token", null, 101L, "feature/demo"))
            .thenReturn(new GitlabService.RefProtection("feature/demo", "BRANCH", false));
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("docker", "offline"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", true, true),
            variable("NEXUS_REGISTRY_PASSWORD", true, true)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "feature/demo");

        assertThat(result.isReady()).isFalse();
        assertThat(result.isProtectedRef()).isFalse();
        assertThat(result.getMatchingOnlineRunnerCount()).isEqualTo(1);
        assertThat(result.getExecutableRunnerCount()).isZero();
        assertThat(result.getUnavailableProtectedVariables())
            .containsExactly("NEXUS_REGISTRY_USER", "NEXUS_REGISTRY_PASSWORD");
        assertThat(result.getBlockers())
            .anyMatch(message -> message.contains("ref_protected"))
            .anyMatch(message -> message.contains("Protected 변수가 전달되지 않습니다"));
    }

    @Test
    void explicitlyAllowsUnprotectedRefOnlyWithGeneralRunnerAndUnprotectedVariables() throws Exception {
        when(gitlabService.getRefProtection("token", null, 101L, "feature/demo"))
            .thenReturn(new GitlabService.RefProtection("feature/demo", "BRANCH", false));
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("docker", "offline"), "not_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", true, false),
            variable("NEXUS_REGISTRY_PASSWORD", true, false)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "feature/demo");

        assertThat(result.isReady()).isTrue();
        assertThat(result.getExecutableRunnerCount()).isEqualTo(1);
        assertThat(result.getUnavailableProtectedVariables()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(message -> message.contains("비보호 ref"));
    }

    @Test
    void obtainsTagsAccessLevelAndStatusFromDetailsWhenProjectRunnerListOmitsThem() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L))
            .thenReturn(runnerDetails(
                77L, "online", false, List.of("docker", "offline"), "ref_protected"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", true, true),
            variable("NEXUS_REGISTRY_PASSWORD", true, true)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "main");

        assertThat(result.isReady()).isTrue();
        assertThat(result.getExecutableRunnerCount()).isEqualTo(1);
        verify(gitlabService).getRunnerDetails("token", null, 77L);
    }

    @Test
    void blocksReadinessWhenRunnerDetailsCannotBeRead() throws Exception {
        protectedBranch();
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            runnerSummary(77L, "online", false)
        ));
        when(gitlabService.getRunnerDetails("token", null, 77L))
            .thenThrow(new IllegalArgumentException(
                "GitLab Runner 상세 조회 권한이 없습니다. manage_runner scope를 확인해 주세요. (HTTP 403)"));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", true, true),
            variable("NEXUS_REGISTRY_PASSWORD", true, true)
        ));

        GitlabCiReadiness result = service.evaluate("token", null, project, "main");

        assertThat(result.isReady()).isFalse();
        assertThat(result.getExecutableRunnerCount()).isZero();
        assertThat(result.getBlockers())
            .anyMatch(message -> message.contains("Runner #77") && message.contains("manage_runner"));
    }

    private void protectedBranch() throws Exception {
        when(gitlabService.getRefProtection("token", null, 101L, "main"))
            .thenReturn(new GitlabService.RefProtection("main", "BRANCH", true));
    }

    private Map<String, Object> runnerSummary(long id, String status, boolean paused) {
        return Map.of(
            "id", id,
            "description", "offline-runner-" + id,
            "status", status,
            "online", "online".equals(status),
            "paused", paused
        );
    }

    private Map<String, Object> runnerDetails(
        long id,
        String status,
        boolean paused,
        List<String> tags,
        String accessLevel
    ) {
        return Map.of(
            "id", id,
            "status", status,
            "online", "online".equals(status),
            "paused", paused,
            "tagList", tags,
            "accessLevel", accessLevel
        );
    }

    private Map<String, Object> variable(String key, boolean masked, boolean isProtected) {
        return Map.of("key", key, "masked", masked, "protected", isProtected, "environmentScope", "*",
            "variableType", key.equals("MAVEN_SETTINGS_FILE") ? "file" : "env_var", "hasValue", true);
    }

    private List<Map<String, Object>> withBuildVariables(Map<String, Object>... variables) {
        var result = new java.util.ArrayList<Map<String, Object>>(List.of(variables));
        result.add(variable("DOCKER_BUILD_IMAGE", false, false));
        result.add(variable("CI_BUILD_IMAGE", false, false));
        return result;
    }

    @Test
    void mavenReleaseRequiresSettingsFileRuntimeAndMasksMavenPassword() throws Exception {
        protectedBranch();
        project.setBuildTool(com.portfolio.deployops.domain.BuildToolType.MAVEN);
        project.setDockerfilePath("./Dockerfile.release");
        when(gitlabService.getProjectRunners("token", null, 101L)).thenReturn(List.of(runnerSummary(77L, "online", false)));
        when(gitlabService.getRunnerDetails("token", null, 77L)).thenReturn(
            runnerDetails(77L, "online", false, List.of("offline"), "ref_protected"));
        var variables = new java.util.ArrayList<Map<String, Object>>(withBuildVariables(
            variable("NEXUS_REGISTRY_USER", false, true), variable("NEXUS_REGISTRY_PASSWORD", true, true),
            variable("MAVEN_BUILD_IMAGE", false, true), variable("MAVEN_SETTINGS_FILE", false, true),
            variable("NEXUS_MAVEN_USER", false, true), variable("NEXUS_MAVEN_PASSWORD", false, true),
            variable("RUNTIME_BASE_IMAGE", false, true)));
        when(gitlabService.getProjectVariables("token", null, 101L)).thenReturn(variables);
        var ready = service.evaluate("token", null, project, "main");
        assertThat(ready.isReady()).isTrue();
        assertThat(ready.getRequiredVariables()).contains("MAVEN_SETTINGS_FILE", "RUNTIME_BASE_IMAGE").doesNotContain("CI_BUILD_IMAGE");
        assertThat(ready.getWarnings()).anyMatch(w -> w.contains("NEXUS_MAVEN_PASSWORD") && w.contains("Masked"));
        variables.removeIf(v -> v.get("key").equals("MAVEN_SETTINGS_FILE"));
        variables.add(Map.of("key", "MAVEN_SETTINGS_FILE", "hasValue", false, "variableType", "env_var", "environmentScope", "*"));
        var invalid = service.evaluate("token", null, project, "main");
        assertThat(invalid.isReady()).isFalse();
        assertThat(invalid.getBlockers()).anyMatch(w -> w.contains("MAVEN_SETTINGS_FILE") && w.contains("file"))
            .anyMatch(w -> w.contains("MAVEN_SETTINGS_FILE") && w.contains("비어"));
    }
}
