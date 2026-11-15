package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitlabServiceReadinessApiTest {

    private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestMethods = new CopyOnWriteArrayList<>();
    private final Map<String, String> nextPages = new ConcurrentHashMap<>();
    private HttpServer server;
    private String gitlabUrl;
    private GitlabService service;
    private SystemSettingService settings;

    @Test
    void readsExactImmutableTagWildcardWithGetOnlyBeforeAnyTagExists() throws Exception {
        stub("/api/v4/projects/101/protected_tags/deploy%2Fmr-*", 200,
            "{\"name\":\"deploy/mr-*\",\"create_access_levels\":[{\"access_level\":40}]}");
        assertThat(service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
            .isEqualTo(new GitlabService.ImmutableTagPolicy(true, true));
        assertThat(requests).containsExactly("/api/v4/projects/101/protected_tags/deploy%2Fmr-*");
        assertThat(requestBodies).containsExactly("");
        assertThat(requestMethods).containsExactly("GET");
    }

    @Test
    void missingWildcardDoesNotInheritMainProtectionOrCreateAnyPolicy() throws Exception {
        stub("/api/v4/projects/101/protected_tags/deploy%2Fmr-*", 404, "{}");
        assertThat(service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
            .isEqualTo(new GitlabService.ImmutableTagPolicy(false, false));
        assertThat(requests).containsExactly("/api/v4/projects/101/protected_tags/deploy%2Fmr-*",
            "/api/v4/projects/101/protected_tags/deploy%2F*");
        assertThat(requestBodies).containsExactly("", "");
    }

    @Test
    void noAccessPolicyCannotCreateTag() throws Exception {
        stub("/api/v4/projects/101/protected_tags/deploy%2Fmr-*", 200,
            "{\"name\":\"deploy/mr-*\",\"create_access_levels\":[{\"access_level\":0}]}");
        assertThat(service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
            .isEqualTo(new GitlabService.ImmutableTagPolicy(true, false));
    }

    @Test
    void policyLookupRefusesForbiddenMalformedAndWrongWildcard() {
        for (StubResponse response : List.of(new StubResponse(403, "{}"),
            new StubResponse(200, "{\"name\":\"*\",\"create_access_levels\":[{\"access_level\":40}]}"),
            new StubResponse(200, "{\"name\":\"deploy/mr-*\"}"),
            new StubResponse(200, "{\"name\":\"deploy/mr-*\",\"create_access_levels\":[{}]}"),
            new StubResponse(200, "{\"name\":\"deploy/mr-*\",\"create_access_levels\":[{\"access_level\":30.5}]}"))) {
            stub("/api/v4/projects/101/protected_tags/deploy%2Fmr-*", response.status(), response.body());
            assertThatThrownBy(() -> service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
                .isInstanceOf(Exception.class);
        }
    }

    @Test
    void broaderDeployWildcardProtectsFutureMrFamilyWithGetOnly() throws Exception {
        stub("/api/v4/projects/101/protected_tags/deploy%2F*", 200,
            "{\"name\":\"deploy/*\",\"create_access_levels\":[{\"access_level\":40}]}");
        assertThat(service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
            .isEqualTo(new GitlabService.ImmutableTagPolicy(true, true, "deploy/*"));
        assertThat(requests).containsExactly("/api/v4/projects/101/protected_tags/deploy%2Fmr-*",
            "/api/v4/projects/101/protected_tags/deploy%2F*");
        assertThat(requestMethods).containsExactly("GET", "GET");
        assertThat(requestBodies).containsExactly("", "");
    }

    @Test
    void overlappingBroadRuleCanAllowCreationWhenExactRuleHasNoAccess() throws Exception {
        stub("/api/v4/projects/101/protected_tags/deploy%2Fmr-*", 200,
            "{\"name\":\"deploy/mr-*\",\"create_access_levels\":[{\"access_level\":0}]}");
        stub("/api/v4/projects/101/protected_tags/deploy%2F*", 200,
            "{\"name\":\"deploy/*\",\"create_access_levels\":[{\"access_level\":40}]}");
        assertThat(service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
            .isEqualTo(new GitlabService.ImmutableTagPolicy(true, true, "deploy/*"));
    }

    @Test
    void broadNoAccessAndIndividualProtectedTagDoNotProveFutureFamilyCreation() throws Exception {
        stub("/api/v4/projects/101/protected_tags/deploy%2F*", 200,
            "{\"name\":\"deploy/*\",\"create_access_levels\":[{\"access_level\":0}]}");
        stub("/api/v4/projects/101/repository/tags/deploy%2Fmr-2-abc", 200,
            "{\"name\":\"deploy/mr-2-abc\",\"protected\":true}");
        assertThat(service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
            .isEqualTo(new GitlabService.ImmutableTagPolicy(true, false, "deploy/*"));
        assertThat(requests).noneMatch(path -> path.contains("/repository/tags/"));
    }

    @Test
    void broadRuleLookupErrorsAndMalformedOrNarrowResponseFailClosed() {
        for (StubResponse response : List.of(new StubResponse(403, "{}"),
            new StubResponse(500, "{}"),
            new StubResponse(200, "{\"name\":\"deploy/mr-2-*\",\"create_access_levels\":[{\"access_level\":40}]}"),
            new StubResponse(200, "{\"name\":\"deploy/*\",\"create_access_levels\":[]}"),
            new StubResponse(200, "{\"name\":\"deploy/*\",\"create_access_levels\":[{\"access_level\":50}]}"))) {
            stub("/api/v4/projects/101/protected_tags/deploy%2F*", response.status(), response.body());
            assertThatThrownBy(() -> service.getImmutableMrTagPolicy("token", gitlabUrl, 101L))
                .isInstanceOf(Exception.class);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        gitlabUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        settings = mock(SystemSettingService.class);
        service = new GitlabService(
            JsonMapper.builder().build(),
            settings,
            mock(SecretCryptoService.class));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void resolvesProtectedBranchAndUrlEncodesSlashInRef() throws Exception {
        stub("/api/v4/projects/101/repository/branches/feature%2Fdemo", 200,
            "{\"name\":\"feature/demo\",\"protected\":true}");
        stub("/api/v4/projects/101/repository/tags/feature%2Fdemo", 404, "{}");

        GitlabService.RefProtection result = service.getRefProtection(
            "token", gitlabUrl, 101L, "feature/demo");

        assertThat(result).isEqualTo(new GitlabService.RefProtection("feature/demo", "BRANCH", true));
        assertThat(requests).containsExactly(
            "/api/v4/projects/101/repository/branches/feature%2Fdemo",
            "/api/v4/projects/101/repository/tags/feature%2Fdemo");
    }

    @Test
    void resolvesUnprotectedTagWhenBranchDoesNotExist() throws Exception {
        stub("/api/v4/projects/101/repository/branches/v1.2.3", 404, "{}");
        stub("/api/v4/projects/101/repository/tags/v1.2.3", 200,
            "{\"name\":\"v1.2.3\",\"protected\":false}");

        GitlabService.RefProtection result = service.getRefProtection(
            "token", gitlabUrl, 101L, "v1.2.3");

        assertThat(result).isEqualTo(new GitlabService.RefProtection("v1.2.3", "TAG", false));
    }

    @Test
    void rejectsAmbiguousBranchAndTagName() {
        stub("/api/v4/projects/101/repository/branches/release", 200,
            "{\"name\":\"release\",\"protected\":true}");
        stub("/api/v4/projects/101/repository/tags/release", 200,
            "{\"name\":\"release\",\"protected\":false}");

        assertThatThrownBy(() -> service.getRefProtection("token", gitlabUrl, 101L, "release"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("브랜치와 태그가 모두 존재");
    }

    @Test
    void mapsRunnerAccessLevelFromDetailsApi() throws Exception {
        stub("/api/v4/runners/77", 200,
            "{\"id\":77,\"status\":\"online\",\"online\":true,\"paused\":false,"
                + "\"tag_list\":[\"docker\",\"offline\"],\"access_level\":\"ref_protected\"}");

        Map<String, Object> result = service.getRunnerDetails("token", gitlabUrl, 77L);

        assertThat(result)
            .containsEntry("id", 77)
            .containsEntry("accessLevel", "ref_protected")
            .containsEntry("online", true);
        assertThat(result.get("tagList")).isEqualTo(List.of("docker", "offline"));
    }

    @Test
    void mapsProjectRunnerListWithoutAssumingDetailOnlyFields() throws Exception {
        stub("/api/v4/projects/101/runners", 200, """
            [{
              "id":77,
              "description":"offline-runner",
              "ip_address":"",
              "active":true,
              "paused":false,
              "status":"online",
              "online":true,
              "job_execution_status":"idle"
            }]
            """);

        List<Map<String, Object>> result = service.getProjectRunners("token", gitlabUrl, 101L);

        assertThat(result).singleElement().satisfies(runner -> {
            assertThat(runner)
                .containsEntry("id", 77)
                .containsEntry("description", "offline-runner")
                .containsEntry("status", "online")
                .containsEntry("online", true)
                .containsEntry("paused", false);
            assertThat(runner).doesNotContainKeys("tagList", "accessLevel");
        });
    }

    @Test
    void mapsExactMergeRequestReviewIdentity() throws Exception {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        stub("/api/v4/projects/101/merge_requests?per_page=50&order_by=updated_at&sort=desc&state=opened", 200,
            "[{\"id\":9,\"iid\":7,\"title\":\"safe change\",\"state\":\"opened\","
                + "\"source_branch\":\"feature\",\"target_branch\":\"main\",\"sha\":\"" + sha + "\","
                + "\"source_project_id\":101,\"target_project_id\":101,"
                + "\"diff_refs\":{\"base_sha\":\"base\",\"head_sha\":\"" + sha + "\",\"start_sha\":\"start\"}}]");

        Map<String, Object> result = service.getMergeRequests("token", gitlabUrl, 101L, "opened").getFirst();

        assertThat(result).containsEntry("sha", sha)
            .containsEntry("sourceProjectId", 101)
            .containsEntry("targetProjectId", 101);
        assertThat(result.get("diffRefs")).asString().contains(sha);
    }

    @Test
    void mapsGitlabCurrentUserMergePermissionFromMergeRequestResponse() throws Exception {
        String path = "/api/v4/projects/101/merge_requests/7";
        stub(path, 200,
            "{\"iid\":7,\"state\":\"opened\",\"sha\":\"0123456789abcdef0123456789abcdef01234567\","
                + "\"target_branch\":\"main\",\"user\":{\"can_merge\":false}}" );

        Map<String, Object> result = service.getMergeRequest("token", gitlabUrl, 101L, 7L);

        assertThat(result).containsEntry("canMerge", false);
        assertThat(requests).containsExactly(path);
    }

    @Test
    void approveAndMergeSendReviewedShaAsGitlabCompareAndSet() throws Exception {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        String path = "/api/v4/projects/101/merge_requests/7";
        stub(path + "/approve", 201, "{\"approved\":true}");
        stub(path + "/merge", 200, "{\"state\":\"merged\"}");

        service.approveMergeRequest("token", gitlabUrl, 101L, 7L, sha);
        service.mergeMergeRequest("token", gitlabUrl, 101L, 7L, sha, true);

        assertThat(requests).containsExactly(path + "/approve", path + "/merge");
        assertThat(requestBodies).allSatisfy(body -> assertThat(body).contains("\"sha\":\"" + sha + "\""));
        assertThat(requestBodies.get(1)).contains("\"should_remove_source_branch\":true");
    }

    @Test
    void refusesAbbreviatedReviewedShaBeforeCallingGitlab() {
        assertThatThrownBy(() -> service.approveMergeRequest("token", gitlabUrl, 101L, 7L, "abc123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("full reviewed Git commit SHA");
        assertThat(requests).isEmpty();
    }

    @Test
    void explainsRequiredPermissionWhenRunnerDetailsReturnForbidden() {
        stub("/api/v4/runners/77", 403, "{\"message\":\"403 Forbidden\"}");

        assertThatThrownBy(() -> service.getRunnerDetails("token", gitlabUrl, 77L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manage_runner")
            .hasMessageContaining("Maintainer")
            .hasMessageContaining("HTTP 403");
    }

    private void stub(String rawPath, int status, String body) {
        responses.put(rawPath, new StubResponse(status, body));
    }

    @Test
    void readsAllVariablePagesAndNeverReturnsSecretValues() throws Exception {
        String first = "/api/v4/projects/101/variables?per_page=100&page=1";
        String second = "/api/v4/projects/101/variables?per_page=100&page=2";
        stub(first, 200, "[" + variableJson("NEXUS_MAVEN_PASSWORD", "env_var", "sensitive-fixture") + "]");
        stub(second, 200, "[" + variableJson("MAVEN_SETTINGS_FILE", "file", "  ") + "]");
        nextPages.put(first, "2");
        nextPages.put(second, "");
        var result = service.getProjectVariables("token", gitlabUrl, 101L);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("hasValue", true).doesNotContainKey("value");
        assertThat(result.get(1)).containsEntry("hasValue", false).containsEntry("variableType", "file");
        assertThat(result.toString()).doesNotContain("sensitive-fixture");
        assertThat(requests).containsExactly(first, second);
    }

    @Test
    void rejectsLoopingVariablePaginationWithoutFollowingHeaderUrl() {
        String first = "/api/v4/projects/101/variables?per_page=100&page=1";
        stub(first, 200, "[]");
        nextPages.put(first, "1");
        assertThatThrownBy(() -> service.getProjectVariables("token", gitlabUrl, 101L))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지");
        assertThat(requests).containsExactly(first);
    }

    @Test
    void continuesPastOneHundredVariablesWhenPaginationHeadersAreMissing() throws Exception {
        String first = "/api/v4/projects/101/variables?per_page=100&page=1";
        String second = "/api/v4/projects/101/variables?per_page=100&page=2";
        String body = java.util.stream.IntStream.range(0, 100)
            .mapToObj(i -> variableJson("VARIABLE_" + i, "env_var", "fixture"))
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        stub(first, 200, body);
        stub(second, 200, "[" + variableJson("DOCKER_BUILD_IMAGE", "env_var", "fixture") + "]");
        var result = service.getProjectVariables("token", gitlabUrl, 101L);
        assertThat(result).hasSize(101);
        assertThat(result.get(100)).containsEntry("key", "DOCKER_BUILD_IMAGE");
        assertThat(requests).containsExactly(first, second);
    }

    private String variableJson(String key, String type, String value) {
        return "{\"key\":\"" + key + "\",\"variable_type\":\"" + type + "\",\"value\":\"" + value
            + "\",\"protected\":true,\"masked\":true,\"environment_scope\":\"*\"}";
    }

    @Test
    void acceptsExplicitRedactedMaskedHiddenSecretsWithoutPublishingValues() throws Exception {
        stub("/api/v4/projects/101/variables?per_page=100&page=1", 200,
            "[{\"key\":\"NEXUS_REGISTRY_PASSWORD\",\"variable_type\":\"env_var\",\"value\":null,"
                + "\"protected\":true,\"masked\":true,\"hidden\":true,\"environment_scope\":\"*\"}]");
        var result = service.getProjectVariables("token", gitlabUrl, 101L);
        assertThat(result.getFirst()).containsEntry("hasValue", true).containsEntry("hidden", true)
            .containsEntry("masked", true).doesNotContainKey("value");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "\"value\":\"\",\"hidden\":true,\"masked\":true",
        "\"value\":\"  \",\"hidden\":true,\"masked\":true",
        "\"hidden\":true,\"masked\":true",
        "\"value\":null,\"hidden\":false,\"masked\":true",
        "\"value\":null,\"hidden\":true,\"masked\":false",
        "\"value\":null,\"masked\":true",
        "\"value\":42,\"hidden\":true,\"masked\":true"
    })
    void neverTreatsEmptyMissingOrUnvalidatedHiddenValueAsConfigured(String fields) throws Exception {
        stub("/api/v4/projects/101/variables?per_page=100&page=1", 200,
            "[{\"key\":\"NEXUS_MAVEN_PASSWORD\",\"variable_type\":\"env_var\",\"protected\":true,"
                + "\"environment_scope\":\"*\"," + fields + "}]");
        assertThat(service.getProjectVariables("token", gitlabUrl, 101L).getFirst())
            .containsEntry("hasValue", false).doesNotContainKey("value");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "\"true\"", "1"})
    void rejectsMalformedHiddenMetadata(String hidden) {
        stub("/api/v4/projects/101/variables?per_page=100&page=1", 200,
            "[{\"key\":\"NEXUS_MAVEN_PASSWORD\",\"variable_type\":\"env_var\",\"value\":null,"
                + "\"protected\":true,\"masked\":true,\"hidden\":" + hidden + ",\"environment_scope\":\"*\"}]");
        assertThatThrownBy(() -> service.getProjectVariables("token", gitlabUrl, 101L))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("메타데이터");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {403, 500})
    void variableHttpErrorsNeverExposeResponseBody(int status) {
        stub("/api/v4/projects/101/variables?per_page=100&page=1", status, "secret-error-payload");
        assertThatThrownBy(() -> service.getProjectVariables("token", gitlabUrl, 101L))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP " + status)
            .hasMessageNotContaining("secret-error-payload");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "protected", "masked", "variable_type", "environment_scope", "key"
    })
    void missingOrWrongMetadataFailsClosedWithoutLeakingValue(String field) throws Exception {
        var mapper = JsonMapper.builder().build();
        Map<String, Object> valid = mapper.readValue(variableJson("TEST", "env_var", "secret-metadata-value"), Map.class);
        for (boolean remove : List.of(true, false)) {
            var malformed = new java.util.LinkedHashMap<String, Object>(valid);
            if (remove) malformed.remove(field); else malformed.put(field, 42);
            stub("/api/v4/projects/101/variables?per_page=100&page=1", 200,
                mapper.writeValueAsString(List.of(malformed)));
            assertThatThrownBy(() -> service.getProjectVariables("token", gitlabUrl, 101L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("메타데이터")
                .hasMessageNotContaining("secret-metadata-value");
        }
    }

    @Test
    void malformedVariableJsonNeverExposesItsContent() {
        stub("/api/v4/projects/101/variables?per_page=100&page=1", 200, "[{secret-invalid-json");
        assertThatThrownBy(() -> service.getProjectVariables("token", gitlabUrl, 101L))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("해석")
            .hasMessageNotContaining("secret-invalid-json");
    }

    @Test
    void ciCommitCreatesNewBranchFromExactReviewedSha() throws Exception {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        stub("/api/v4/projects/101/repository/files/.gitlab-ci.yml?ref=" + sha, 200, "{}");
        stub("/api/v4/projects/101/repository/commits", 201, "{\"id\":\"" + sha + "\"}");

        service.commitRepositoryFile("token", gitlabUrl, 101L,
            "deploy-system/ci-edit-safe", sha, ".gitlab-ci.yml", "stages: [build]", "safe edit");

        assertThat(requestBodies.getLast())
            .contains("\"branch\":\"deploy-system/ci-edit-safe\"")
            .contains("\"start_sha\":\"" + sha + "\"")
            .doesNotContain("\"start_branch\"");
    }

    @Test
    void readsJobTraceAndPostsPipelineOperations() throws Exception {
        stub("/api/v4/projects/101/jobs/701/trace", 200, "compile failed\n");
        stub("/api/v4/projects/101/pipelines/77/retry", 200, "{\"id\":78,\"status\":\"pending\"}");
        stub("/api/v4/projects/101/pipelines/77/cancel", 200, "{\"id\":77,\"status\":\"canceled\"}");

        assertThat(service.getJobTrace("token", gitlabUrl, 101L, 701L)).contains("compile failed");
        assertThat(service.retryPipeline("token", gitlabUrl, 101L, 77L)).containsEntry("id", 78);
        assertThat(service.cancelPipeline("token", gitlabUrl, 101L, 77L)).containsEntry("status", "canceled");
        assertThat(requests).containsExactly(
            "/api/v4/projects/101/jobs/701/trace",
            "/api/v4/projects/101/pipelines/77/retry",
            "/api/v4/projects/101/pipelines/77/cancel");
    }

    @Test
    void currentPatIdentityReadsNumericIdUsernameAndAdminFlag() throws Exception {
        stub("/api/v4/user", 200, "{\"id\":71,\"username\":\"reviewer\",\"is_admin\":false}");

        assertThat(service.getAuthenticatedUserIdentity("personal-token", gitlabUrl))
            .isEqualTo(new GitlabService.GitlabUserIdentity(71L, "reviewer", false));
        assertThat(requests).containsExactly("/api/v4/user");
    }

    @Test
    void adminSystemPatIsRejectedBeforeMergeHttpRequest() {
        when(settings.get("gitlab.token", "")).thenReturn("system-token");
        stub("/api/v4/user", 200, "{\"id\":1,\"username\":\"root\",\"is_admin\":true}");

        assertThatThrownBy(() -> service.mergeMergeRequest(
            "system-token", gitlabUrl, 101L, 4L,
            "0123456789abcdef0123456789abcdef01234567", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("root/admin");
        assertThat(requests).containsExactly("/api/v4/user");
    }

    @Test
    void approvalApiReturnsExactApproverIdsEvenWhenApprovedFlagIsFalse() throws Exception {
        stub("/api/v4/projects/101/merge_requests/4/approvals", 200,
            "{\"approved\":false,\"approved_by\":[{\"user\":{\"id\":7,\"username\":\"reviewer\"}}]}");

        assertThat(service.getMergeRequestApproverIds("token", gitlabUrl, 101L, 4L))
            .containsExactly(7L);
    }

    @Test
    void approvalApiNeverTreatsApprovedFlagAsHumanEvidence() throws Exception {
        stub("/api/v4/projects/101/merge_requests/4/approvals", 200,
            "{\"approved\":true,\"approved_by\":[]}");

        assertThat(service.getMergeRequestApproverIds("token", gitlabUrl, 101L, 4L))
            .isEmpty();
    }

    @Test
    void approvalApiFailsClosedWhenApproverListIsMissing() {
        stub("/api/v4/projects/101/merge_requests/4/approvals", 200, "{\"approved\":true}");

        assertThatThrownBy(() -> service.getMergeRequestApproverIds("token", gitlabUrl, 101L, 4L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("승인자 목록");
    }

    private Map<String, Object> diffFixture(boolean collapsed) {
        return new java.util.LinkedHashMap<>(Map.of("iid", 4, "sha", "a".repeat(40),
            "source_branch", "feature", "target_branch", "main", "state", "opened",
            "diff_refs", Map.of("base_sha", "b".repeat(40), "start_sha", "c".repeat(40)),
            "overflow", false, "changes", List.of(Map.of("old_path", "Dockerfile",
                "new_path", "Dockerfile", "diff", collapsed ? "" : "+FROM java",
                "collapsed", collapsed, "too_large", false))));
    }

    private void stubDiff(Map<String, Object> initial, Map<String, Object> recovered) throws Exception {
        stub("/api/v4/projects/101/merge_requests/4/changes", 200, JsonMapper.builder().build().writeValueAsString(initial));
        stub("/api/v4/projects/101/merge_requests/4/changes?access_raw_diffs=true", 200,
            JsonMapper.builder().build().writeValueAsString(recovered));
    }

    @Test void recoversCollapsedDiffOnceAndRetainsBinaryEmptyDiffAsReviewable() throws Exception {
        Map<String, Object> initial = diffFixture(true), recovered = diffFixture(false);
        recovered.put("changes", List.of(Map.of("old_path", "Dockerfile", "new_path", "Dockerfile",
            "diff", "+FROM java", "collapsed", false), Map.of("old_path", "icon.png", "new_path", "icon.png", "diff", "")));
        initial.put("changes", List.of(Map.of("old_path", "Dockerfile", "new_path", "Dockerfile",
            "diff", "", "collapsed", true), Map.of("old_path", "icon.png", "new_path", "icon.png", "diff", "")));
        stubDiff(initial, recovered);
        assertThat(service.getMergeRequestChanges("token", gitlabUrl, 101, 4))
            .containsEntry("reviewComplete", true).containsEntry("rawDiffRecovery", "RECOVERED");
        assertThat(requestMethods).containsExactly("GET", "GET");
        assertThat(requests.get(1)).endsWith("?access_raw_diffs=true");
    }

    @Test void rawRecoveryRejectsChangedReviewContextAndFileMembership() throws Exception {
        for (String key : List.of("sha", "iid", "source_branch", "target_branch", "state", "diff_refs", "changes")) {
            Map<String, Object> changed = diffFixture(false);
            changed.put(key, switch (key) {
                case "iid" -> 5;
                case "diff_refs" -> Map.of("base_sha", "d".repeat(40), "start_sha", "c".repeat(40));
                case "changes" -> List.of(Map.of("old_path", "other", "new_path", "other", "diff", "+x"));
                default -> "d".repeat(40);
            });
            requests.clear(); requestMethods.clear();
            stubDiff(diffFixture(true), changed);
            assertThat(service.getMergeRequestChanges("token", gitlabUrl, 101, 4))
                .containsEntry("reviewComplete", false).containsEntry("rawDiffRecovery", "CONTEXT_CHANGED");
            assertThat(requestMethods).containsExactly("GET", "GET");
        }
    }

    @Test void normalEmptyDiffDoesNotFetchRawAndTooLargeRemainsBlocked() throws Exception {
        Map<String, Object> normal = diffFixture(false);
        normal.put("changes", List.of(Map.of("old_path", "icon.png", "new_path", "icon.png", "diff", "")));
        stubDiff(normal, normal);
        assertThat(service.getMergeRequestChanges("token", gitlabUrl, 101, 4)).containsEntry("reviewComplete", true);
        assertThat(requestMethods).containsExactly("GET");
        requests.clear(); requestMethods.clear();
        normal.put("changes", List.of(Map.of("old_path", "huge", "new_path", "huge", "too_large", true)));
        stubDiff(normal, normal);
        assertThat(service.getMergeRequestChanges("token", gitlabUrl, 101, 4)).containsEntry("reviewComplete", false);
        assertThat(requestMethods).containsExactly("GET");
    }

    @Test void unavailableOrStillCollapsedRawDiffCannotBecomeComplete() throws Exception {
        stubDiff(diffFixture(true), diffFixture(true));
        assertThat(service.getMergeRequestChanges("token", gitlabUrl, 101, 4)).containsEntry("reviewComplete", false);
        stub("/api/v4/projects/101/merge_requests/4/changes?access_raw_diffs=true", 403, "{}");
        assertThat(service.getMergeRequestChanges("token", gitlabUrl, 101, 4))
            .containsEntry("reviewComplete", false).containsEntry("rawDiffRecovery", "UNAVAILABLE");
    }

    private void respond(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        String withQuery = rawPath + "?" + exchange.getRequestURI().getRawQuery();
        if (responses.containsKey(withQuery)) rawPath = withQuery;
        requests.add(rawPath);
        requestMethods.add(exchange.getRequestMethod());
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        StubResponse stub = responses.getOrDefault(rawPath, new StubResponse(404, "{}"));
        byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (nextPages.containsKey(rawPath)) exchange.getResponseHeaders().add("X-Next-Page", nextPages.get(rawPath));
        exchange.sendResponseHeaders(stub.status(), body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private record StubResponse(int status, String body) {}
}
