package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class GitlabCiComponentHydrationTest {
    private final GitlabService gitlab = mock(GitlabService.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DeployComponentRepository components = mock(DeployComponentRepository.class);
    private final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    private final BuildProfileService profiles = mock(BuildProfileService.class);
    private final ExecutionPolicyService policy = mock(ExecutionPolicyService.class);
    private final GitlabApiController controller = new GitlabApiController(
        gitlab, new GitlabCiTemplateService(), mock(GitlabCiInspectService.class), profiles,
        projects, permissions, mock(ProjectService.class), mock(AuditLogService.class),
        mock(OfficeNotificationService.class), mock(MergeRequestGovernanceService.class), policy,
        mock(MergeRequestOriginRepository.class), mock(DeployRequestRepository.class),
        mock(MergeRequestEvidenceRepository.class), new GitlabCiReadinessService(gitlab),
        mock(SecretMaskingService.class));
    private final SecurityUserDetails actor = new SecurityUserDetails(User.builder()
        .id(5L).loginId("reviewer").role(UserRole.APPROVER).gitlabToken("pat").build());
    private final List<DeployComponent> storedComponents = List.of(
        DeployComponent.builder().id(1L).projectId(7L).code("api").role(ServerType.WAS)
            .artifactType(ArtifactType.CONTAINER).imageRepository("registry.local/c/api")
            .buildTool(BuildToolType.MAVEN).buildCommand("mvn -B package")
            .artifactPath("target/*.jar").dockerfilePath("Dockerfile.api").dockerContextPath(".").build(),
        DeployComponent.builder().id(2L).projectId(7L).code("web").role(ServerType.WEB)
            .artifactType(ArtifactType.STATIC_BUNDLE).imageRepository("registry.local/c/web")
            .buildTool(BuildToolType.STATIC).buildCommand("test -s dist/index.html")
            .artifactPath("dist").dockerfilePath("Dockerfile.web").dockerContextPath(".").build());

    private Project basicProject() {
        return Project.builder().id(7L).name("multi-c").gitlabProjectId(101L).active(true)
            .defaultBranch("main").buildTool(BuildToolType.MAVEN).imageRepository("registry.local/c/api")
            .dockerfilePath("Dockerfile.api").dockerContextPath(".").artifactPath("target/*.jar").build();
    }

    @BeforeEach
    void setup() {
        controller.configureComponents(components);
        // Simulate the real repository's basic-row result on every call, not a hydrated fixture.
        when(projects.findById(7L)).thenAnswer(ignored -> Optional.of(basicProject()));
        when(components.findActiveByProjectId(7L)).thenReturn(storedComponents);
        when(gitlab.resolveAccessToken("pat", "APPROVER", "reviewer")).thenReturn("token");
        when(gitlab.resolveAccessTokenInfo("pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("token", "USER_PAT"));
        when(gitlab.resolveReadonlyCiAccessToken(null)).thenReturn("token");
        when(gitlab.resolveReadonlyCiAccessToken("pat")).thenReturn("token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"preview", "profile", "missing-file", "merge-request"})
    void everyTemplateEntryBuildsBothStoredComponentsAndReleaseManifest(String entry) throws Exception {
        when(gitlab.listRepositoryTree("token", null, 101L, null, "main")).thenReturn(List.of());
        when(profiles.inspect(anyMap(), any())).thenReturn(new LinkedHashMap<>());
        when(gitlab.getBranchCommitSha("token", null, 101L, "main")).thenReturn("a".repeat(40));
        when(gitlab.commitRepositoryFile(eq("token"), isNull(), eq(101L), anyString(),
            eq("a".repeat(40)), eq(".gitlab-ci.yml"), anyString(), anyString()))
            .thenReturn(Map.of("id", "b".repeat(40)));
        when(gitlab.createMergeRequest(eq("token"), isNull(), eq(101L), anyString(), eq("main"),
            anyString(), anyString(), anyBoolean())).thenReturn(Map.of("iid", 42L));

        ApiResponse<?> response;
        String yaml;
        if (entry.equals("merge-request")) {
            var form = new GitlabCiTemplateMrForm();
            form.setTargetBranch("main");
            form.setBaseCommitSha("a".repeat(40));
            response = controller.createCiTemplateMergeRequest(7L, form, actor, new MockHttpServletRequest());
            assertThat(response.isSuccess()).as(response.getMessage()).isTrue();
            var content = ArgumentCaptor.forClass(String.class);
            verify(gitlab).commitRepositoryFile(eq("token"), isNull(), eq(101L), anyString(),
                eq("a".repeat(40)), eq(".gitlab-ci.yml"), content.capture(), anyString());
            yaml = content.getValue();
            verify(permissions).assertPermission(actor, 7L, ProjectPermissionType.MR_REVIEW);
        } else {
            response = switch (entry) {
                case "profile" -> controller.inspectBuildProfile(7L, "main", actor);
                case "missing-file" -> controller.readCiFile(7L, "main", actor);
                default -> controller.previewCiTemplate(7L, actor);
            };
            assertThat(response.isSuccess()).as(response.getMessage()).isTrue();
            String key = entry.equals("profile") ? "generatedCi" : entry.equals("missing-file") ? "template" : "content";
            yaml = (String) ((Map<?, ?>) response.getData()).get(key);
            verify(permissions).assertPermission(actor, 7L, ProjectPermissionType.PROJECT_VIEW);
        }
        assertThat(yaml).contains("build_app_api:", "build_app_web:", "deploy-release.json",
            "Dockerfile.api", "Dockerfile.web", "registry.local/c/api", "registry.local/c/web",
            "$MAVEN_BUILD_IMAGE", "$CI_BUILD_IMAGE");
        verify(components).findActiveByProjectId(7L);
    }

    private List<Map<String, Object>> readyVariablesExceptSecondaryBuilder() throws Exception {
        when(gitlab.getRefProtection("token", null, 101L, "main"))
            .thenReturn(new GitlabService.RefProtection("main", "BRANCH", true));
        when(gitlab.getProjectRunners("token", null, 101L)).thenReturn(List.of(
            Map.of("id", 77L, "description", "offline", "status", "online", "online", true, "paused", false)));
        when(gitlab.getRunnerDetails("token", null, 77L)).thenReturn(Map.of(
            "id", 77L, "status", "online", "online", true, "paused", false,
            "tagList", List.of("offline"), "accessLevel", "ref_protected"));
        Project hydrated = basicProject();
        hydrated.setComponents(storedComponents);
        var values = new ArrayList<Map<String, Object>>();
        for (String key : GitlabCiReadinessService.requiredVariables(hydrated)) {
            if (!key.equals("CI_BUILD_IMAGE")) values.add(variable(key));
        }
        when(gitlab.getProjectVariables("token", null, 101L)).thenReturn(values);
        return values;
    }

    private Map<String, Object> variable(String key) {
        return Map.of("key", key, "masked", key.endsWith("_PASSWORD"), "protected", true,
            "environmentScope", "*", "variableType", key.equals("MAVEN_SETTINGS_FILE") ? "file" : "env_var",
            "hasValue", true);
    }

    @Test
    void readinessCannotReportReadyUntilSecondaryComponentBuilderExists() throws Exception {
        var variables = readyVariablesExceptSecondaryBuilder();
        var blocked = (GitlabCiReadiness) controller.ciReadiness(7L, "main", actor).getData();
        assertThat(blocked.isReady()).isFalse();
        assertThat(blocked.getMissingVariables()).containsExactly("CI_BUILD_IMAGE");
        variables.add(variable("CI_BUILD_IMAGE"));
        var ready = (GitlabCiReadiness) controller.ciReadiness(7L, "main", actor).getData();
        assertThat(ready.isReady()).isTrue();
        verify(components, times(2)).findActiveByProjectId(7L);
    }

    @Test
    void retryDoesNotReachGitlabMutationWhenSecondaryBuilderIsMissing() throws Exception {
        readyVariablesExceptSecondaryBuilder();
        when(gitlab.getPipeline("token", null, 101L, 88L)).thenReturn(Map.of("status", "failed", "ref", "main"));
        var response = controller.operatePipeline(7L, 88L, "retry", actor, new MockHttpServletRequest());
        assertThat(response.getErrorCode()).isEqualTo("CI_NOT_READY");
        assertThat(response.getMessage()).contains("CI_BUILD_IMAGE");
        verify(gitlab, never()).retryPipeline(any(), any(), anyLong(), anyLong());
        verify(components).findActiveByProjectId(7L);
    }

    @Test
    void revokedViewPermissionDoesNotLoadComponentMetadataOrContactGitlab() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(permissions)
            .assertPermission(actor, 7L, ProjectPermissionType.PROJECT_VIEW);
        assertThatThrownBy(() -> controller.previewCiTemplate(7L, actor)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ciReadiness(7L, "main", actor)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(projects, components, gitlab);
    }
}
