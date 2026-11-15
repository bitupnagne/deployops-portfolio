package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class GitlabBuildFileEditorControllerTest {
    private static final String SHA = "a".repeat(40);
    private final GitlabService gitlab = mock(GitlabService.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DeployComponentRepository components = mock(DeployComponentRepository.class);
    private final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    private final MergeRequestOriginRepository origins = mock(MergeRequestOriginRepository.class);
    private final OfficeNotificationService notifications = mock(OfficeNotificationService.class);
    private final GitlabApiController controller = new GitlabApiController(gitlab, new GitlabCiTemplateService(),
        mock(GitlabCiInspectService.class), new BuildProfileService(), projects, permissions,
        mock(ProjectService.class), mock(AuditLogService.class), notifications,
        mock(MergeRequestGovernanceService.class), mock(ExecutionPolicyService.class), origins,
        mock(DeployRequestRepository.class), mock(MergeRequestEvidenceRepository.class),
        new GitlabCiReadinessService(gitlab), mock(SecretMaskingService.class));
    private final SecurityUserDetails actor = new SecurityUserDetails(User.builder().id(5L)
        .loginId("reviewer").role(UserRole.APPROVER).gitlabToken("personal").build());
    private Project project;

    @BeforeEach void setup() throws Exception {
        project = Project.builder().id(7L).name("qa").gitlabProjectId(101L).defaultBranch("main")
            .dockerfilePath("docker/Dockerfile.api").dockerContextPath(".")
            .imageRepository("registry.local/qa/api").buildTool(BuildToolType.MAVEN).build();
        controller.configureComponents(components);
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(gitlab.resolveAccessToken("personal", "APPROVER", "reviewer")).thenReturn("token");
        when(gitlab.resolveReadonlyCiAccessToken("personal")).thenReturn("read-token");
        when(gitlab.resolveAccessTokenInfo("personal", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("token", "USER_PAT"));
        when(gitlab.getBranchCommitSha(anyString(), isNull(), eq(101L), eq("main"))).thenReturn(SHA);
        when(gitlab.commitRepositoryFile(anyString(), isNull(), eq(101L), anyString(), eq(SHA),
            anyString(), anyString(), anyString())).thenReturn(Map.of("id", "b".repeat(40)));
        when(gitlab.createMergeRequest(anyString(), isNull(), eq(101L), anyString(), eq("main"),
            anyString(), anyString(), anyBoolean())).thenReturn(Map.of("iid", 42L));
    }

    @Test void viewPermissionIsCheckedBeforeGitlabOrProjectLookup() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(permissions)
            .assertPermission(actor, 7L, ProjectPermissionType.PROJECT_VIEW);
        assertThatThrownBy(() -> controller.readDockerfile(7L, "main", null, actor)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(projects, gitlab);
    }

    @Test void readsConfiguredFileAtImmutableCommitWithReadonlyTokenAndHash() throws Exception {
        when(gitlab.readRepositoryFileBounded("read-token", null, 101L, "docker/Dockerfile.api", SHA, 262144))
            .thenReturn("FROM internal/runtime\n");
        var response = controller.readDockerfile(7L, "main", null, actor);
        assertThat(response.isSuccess()).isTrue();
        assertThat(data(response)).containsEntry("baseCommitSha", SHA).containsEntry("exists", true)
            .containsEntry("filePath", "docker/Dockerfile.api").containsEntry("contentSha256", hash("FROM internal/runtime\n"));
        verify(gitlab, never()).resolveAccessToken(any(), any(), any());
    }

    @Test void genuineMissingIsDifferentFromPermissionFailure() throws Exception {
        var missing = controller.readDockerfile(7L, "main", null, actor);
        assertThat(data(missing)).containsEntry("exists", false).containsEntry("content", "").containsEntry("contentSha256", null);
        when(gitlab.readRepositoryFileBounded("read-token", null, 101L, "docker/Dockerfile.api", SHA, 262144))
            .thenThrow(new IllegalStateException("GitLab file HTTP 403"));
        var forbidden = controller.readDockerfile(7L, "main", null, actor);
        assertThat(forbidden.isSuccess()).isFalse();
        assertThat(forbidden.getErrorCode()).isEqualTo("GITLAB_ERROR");
    }

    @Test void componentPathComesOnlyFromActiveSameProjectImageUnit() throws Exception {
        when(components.findActiveByProjectId(7L)).thenReturn(List.of(DeployComponent.builder()
            .id(9L).projectId(7L).artifactType(ArtifactType.STATIC_BUNDLE).dockerfilePath("ui/Dockerfile").build()));
        assertThat(data(controller.readDockerfile(7L, "main", 9L, actor))).containsEntry("filePath", "ui/Dockerfile");
        verify(gitlab).readRepositoryFileBounded("read-token", null, 101L, "ui/Dockerfile", SHA, 262144);
    }

    @Test void foreignInactiveAndProxyComponentsCannotChooseRepositoryPaths() {
        for (DeployComponent c : List.of(
            DeployComponent.builder().id(9L).projectId(8L).artifactType(ArtifactType.CONTAINER).build(),
            DeployComponent.builder().id(9L).projectId(7L).active(false).artifactType(ArtifactType.CONTAINER).build(),
            DeployComponent.builder().id(9L).projectId(7L).artifactType(ArtifactType.PROXY_RELOAD).build())) {
            when(components.findActiveByProjectId(7L)).thenReturn(List.of(c));
            assertThat(controller.readDockerfile(7L, "main", 9L, actor).isSuccess()).isFalse();
        }
    }

    @Test void changedPathOrBranchCannotCreateAnyCommit() throws Exception {
        var path = form(); path.setFilePath("../../secret");
        assertThat(controller.writeDockerfile(7L, path, actor, request()).getErrorCode()).isEqualTo("INVALID_PATH");
        when(gitlab.getBranchCommitSha("token", null, 101L, "main")).thenReturn("c".repeat(40));
        assertThat(controller.writeDockerfile(7L, form(), actor, request()).getErrorCode()).isEqualTo("STALE_BASE");
        verify(gitlab, never()).commitRepositoryFile(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test void existingFileRequiresMatchingOriginalHash() throws Exception {
        when(gitlab.readRepositoryFileBounded("token", null, 101L, "docker/Dockerfile.api", SHA, 262144)).thenReturn("old");
        assertThat(controller.writeDockerfile(7L, form(), actor, request()).getErrorCode()).isEqualTo("STALE_FILE");
        var form = form(); form.setOriginalSha256(hash("old"));
        assertThat(controller.writeDockerfile(7L, form, actor, request()).isSuccess()).isTrue();
    }

    @Test void savesToUniqueFeatureBranchAndCreatesMrWithOriginAndNotification() throws Exception {
        var response = controller.writeDockerfile(7L, form(), actor, request());
        assertThat(response.isSuccess()).as(response.getMessage()).isTrue();
        var branch = ArgumentCaptor.forClass(String.class);
        verify(gitlab).commitRepositoryFile(eq("token"), isNull(), eq(101L), branch.capture(), eq(SHA),
            eq("docker/Dockerfile.api"), eq("FROM internal/new"), anyString());
        assertThat(branch.getValue()).startsWith("deploy-system/dockerfile-edit-").isNotEqualTo("main");
        verify(gitlab).createMergeRequest(eq("token"), isNull(), eq(101L), eq(branch.getValue()), eq("main"), anyString(), anyString(), anyBoolean());
        verify(origins).saveIfAbsent(7L, 101L, 42L, 5L, null);
        verify(notifications).notifyMergeRequestCreated(project, 42L, actor.getUser());
        verify(permissions).assertPermission(actor, 7L, ProjectPermissionType.MR_REVIEW);
    }

    @Test void branchAdvancingDuringFeatureCommitPreventsMrPublication() throws Exception {
        when(gitlab.getBranchCommitSha("token", null, 101L, "main")).thenReturn(SHA, "b".repeat(40));
        assertThat(controller.writeDockerfile(7L, form(), actor, request()).getErrorCode()).isEqualTo("STALE_BASE");
        verify(gitlab, never()).createMergeRequest(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(origins, notifications);
    }

    @Test void existingCiAlsoRequiresOriginalHashAndPublishesOnlyMr() throws Exception {
        when(gitlab.readRepositoryFileBounded("token", null, 101L, ".gitlab-ci.yml", SHA, 262144)).thenReturn("old ci");
        var form = new GitlabCiFileForm(); form.setBaseCommitSha(SHA); form.setTargetBranch("main"); form.setContent("new ci");
        assertThat(controller.writeCiFile(7L, form, actor, request()).getErrorCode()).isEqualTo("STALE_FILE");
        form.setOriginalSha256(hash("old ci"));
        assertThat(controller.writeCiFile(7L, form, actor, request()).isSuccess()).isTrue();
    }

    @Test void scanUsesSingleCommitAndUnknownSourceDoesNotGenerateGuessedTemplates() throws Exception {
        when(gitlab.listRepositoryTree("read-token", null, 101L, null, SHA)).thenReturn(List.of(
            Map.of("path", "pom.xml", "type", "blob")));
        when(gitlab.readRepositoryFileBounded("read-token", null, 101L, "pom.xml", SHA, 262144)).thenReturn("<project/>");
        var response = controller.inspectBuildProfileOptions(7L, "main", null, null, actor);
        assertThat(response.isSuccess()).isTrue();
        assertThat(data(response)).containsEntry("selectionRequired", true).containsEntry("baseCommitSha", SHA)
            .doesNotContainKey("generatedCi").doesNotContainKey("generatedDockerfile");
        assertThat(project.getBuildCommand()).isNull();
    }

    @Test void repeatedDirectoryLimitsAreShownOnceAndStillRequireManualSelection() throws Exception {
        var tree = new ArrayList<Map<String, Object>>();
        tree.add(Map.of("path", "pom.xml", "type", "blob"));
        for (int i = 1; i <= 18; i++) tree.add(Map.of("path", "module" + i, "type", "tree"));
        when(gitlab.listRepositoryTree("read-token", null, 101L, null, SHA)).thenReturn(tree);
        when(gitlab.readRepositoryFileBounded("read-token", null, 101L, "pom.xml", SHA, 262144))
            .thenReturn("<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent></project>");
        var response = data(controller.inspectBuildProfileOptions(7L, "main", null, null, actor));
        assertThat(response).containsEntry("sourceType", "MAVEN_BOOT").containsEntry("selectionRequired", true)
            .containsEntry("scanIncomplete", true).doesNotContainKey("generatedDockerfile").doesNotContainKey("generatedCi");
        var warnings = (List<?>) response.get("warnings");
        String limit = "저장소 탐색 한도에 도달했습니다. 빌드 파일·컨텍스트와 프로젝트 유형을 직접 선택하세요.";
        assertThat(warnings).doesNotHaveDuplicates();
        assertThat(warnings.stream().filter(limit::equals).count()).isEqualTo(1);
        assertThat(warnings.getLast()).isEqualTo(limit);
        // The root and first fifteen directories consume the sixteen-directory budget.
        // The remaining three directories produce the same warning, without API calls.
        verify(gitlab, times(16)).listRepositoryTree(eq("read-token"), isNull(), eq(101L), nullable(String.class), eq(SHA));
    }

    private GitlabDockerfileForm form() { var f = new GitlabDockerfileForm(); f.setFilePath("docker/Dockerfile.api"); f.setTargetBranch("main"); f.setBaseCommitSha(SHA); f.setContent("FROM internal/new"); return f; }
    private MockHttpServletRequest request() { return new MockHttpServletRequest(); }
    @SuppressWarnings("unchecked") private Map<String, Object> data(ApiResponse<?> r) { assertThat(r.isSuccess()).as(r.getMessage()).isTrue(); return (Map<String, Object>) r.getData(); }
    private String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
}
