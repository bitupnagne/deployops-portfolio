package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import com.portfolio.deployops.dto.GitlabCiReadiness;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.MergeRequestEvidenceStatus;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

class BuildOrchestrationServiceTest {

    private static final String REPOSITORY = "192.0.2.10:5000/worksystem/app";
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final GitlabService gitlabService = mock(GitlabService.class);
    private final GitlabPipelineArtifactService artifactService = mock(GitlabPipelineArtifactService.class);
    private final GitlabCiReadinessService readinessService = mock(GitlabCiReadinessService.class);
    private final NexusMetadataService nexusMetadataService = mock(NexusMetadataService.class);
    private final SystemSettingService settingService = mock(SystemSettingService.class);
    private final com.portfolio.deployops.repository.DeployRequestRepository deployRequestRepository = mock(com.portfolio.deployops.repository.DeployRequestRepository.class);
    private final MergeRequestEvidenceRepository evidenceRepository = mock(MergeRequestEvidenceRepository.class);
    private final BuildOrchestrationService service = new BuildOrchestrationService(
        gitlabService,
        artifactService,
        readinessService,
        nexusMetadataService,
        settingService,
        mock(UserRepository.class),
        deployRequestRepository,
        evidenceRepository,
        mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class)
    );

    @BeforeEach
    void allowPipelineBinding() {
        when(settingService.get("nexus.registry", "")).thenReturn("192.0.2.10:5000");
        when(deployRequestRepository.bindCreatedPipeline(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(1);
    }

    private Map<String, String> buildVariables() {
        Project project = Project.builder()
            .id(1L)
            .name("app")
            .imageRepository(REPOSITORY)
            .imageTagPattern("release-${commitSha}-${pipelineId}")
            .build();
        DeployRequest request = DeployRequest.builder()
            .id(1L)
            .reqNo("REQ-1")
            .environment(DeployEnvironment.TEST)
            .branch("main")
            .project(project)
            .build();
        return ReflectionTestUtils.invokeMethod(service, "buildPipelineVariables", project, request);
    }

    @Test
    void nexusCredentialsAreNotPassedToPipelineByDefault() {
        when(settingService.get("nexus.pipeline-credentials", "false")).thenReturn("false");
        when(settingService.get("nexus.user", "")).thenReturn("bot");
        when(settingService.get("nexus.pass", "")).thenReturn("secret");

        Map<String, String> variables = buildVariables();

        assertThat(variables)
            .doesNotContainKeys("NEXUS_USER", "NEXUS_PASS")
            // pipeline 변수는 raw/expand 제어가 없어 패턴 토큰을 훼손할 수 있다. 생성 YAML만 정본으로 쓴다.
            .doesNotContainKey("DEPLOY_IMAGE_TAG_PATTERN");
    }

    @Test
    void mavenPipelineUsesRunnerEndpointAndFrozenEndpointSurvivesGlobalSettingChanges() {
        when(settingService.get("nexus.url", "")).thenReturn("http://127.0.0.1:28183");
        when(settingService.get("nexus.maven.base-url", "")).thenReturn("http://runner-nexus:8081/");
        assertThat(buildVariables()).containsEntry("NEXUS_URL", "http://runner-nexus:8081");
        Project project = Project.builder().id(1L).name("app").imageRepository(REPOSITORY)
            .deploymentRuntimeSettings(new DeploymentRuntimeSettings(
                "registry", "/tmp", "/dist", "2g", "http://management:8081",
                "192.0.2.10:5000", "", false, "http://approved-runner:8081")).build();
        DeployRequest request = newBuildRequest(project);
        Map<String, String> frozen = ReflectionTestUtils.invokeMethod(service, "buildPipelineVariables", project, request);
        assertThat(frozen).containsEntry("NEXUS_URL", "http://approved-runner:8081")
            .containsEntry("NEXUS_REGISTRY", "192.0.2.10:5000");
        assertThat(project.getDeploymentRuntimeSettings().nexusUrl()).isEqualTo("http://management:8081");
    }

    @Test
    void nexusCredentialsArePassedOnlyWhenOptedIn() {
        when(settingService.get("nexus.pipeline-credentials", "false")).thenReturn("true");
        when(settingService.get("nexus.user", "")).thenReturn("bot");
        when(settingService.get("nexus.pass", "")).thenReturn("secret");

        Map<String, String> variables = buildVariables();

        assertThat(variables)
            .containsEntry("NEXUS_USER", "bot")
            .containsEntry("NEXUS_PASS", "secret");
    }

    @Test
    void successfulPipelineUsesVerifiedArtifactMetadataAndMatchingManifest() throws Exception {
        Project project = Project.builder()
            .id(1L)
            .name("app")
            .gitlabProjectId(101L)
            .imageRepository(REPOSITORY)
            .deploymentRuntimeSettings(new DeploymentRuntimeSettings(
                "registry", "/tmp/images", "/srv/static", "1g",
                "https://nexus.local", "192.0.2.10:5000", "http://nexus-api:8081", false))
            .build();
        DeployRequest request = newBuildRequest(project);
        Map<String, Object> pipeline = Map.of("id", 77L, "status", "success", "sha", COMMIT_SHA);

        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");
        when(readinessService.evaluate("token", null, project, "main")).thenReturn(GitlabCiReadiness.builder()
            .ready(true)
            .ref("main")
            .refType("BRANCH")
            .protectedRef(true)
            .runnerTag("offline")
            .matchingOnlineRunnerCount(1)
            .executableRunnerCount(1)
            .blockers(List.of())
            .warnings(List.of())
            .build());
        when(gitlabService.triggerPipeline(
            org.mockito.ArgumentMatchers.eq("token"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(101L),
            org.mockito.ArgumentMatchers.eq("main"),
            org.mockito.ArgumentMatchers.anyMap()
        )).thenReturn(Map.of("id", 77L));
        when(gitlabService.getPipeline("token", null, 101L, 77L)).thenReturn(pipeline);
        when(artifactService.resolveAndVerify("token", null, project, pipeline)).thenReturn(
            new GitlabBuildArtifactMetadata(701L, 77L, REPOSITORY, "artifact-tag", DIGEST, COMMIT_SHA));
        when(nexusMetadataService.resolveManifest(project, "artifact-tag")).thenReturn(Map.of("digest", DIGEST));

        GitlabBuildArtifactMetadata verified = service.ensureBuildArtifact(
            request, "OPERATOR", message -> { });

        assertThat(request.getGitlabPipelineId()).isEqualTo(77L);
        assertThat(request.getCommitSha()).isEqualTo(COMMIT_SHA);
        assertThat(request.getImageTag()).isEqualTo("artifact-tag");
        assertThat(request.getImageDigest()).isEqualTo(DIGEST);
        assertThat(verified.jobId()).isEqualTo(701L);
        verify(gitlabService).triggerPipeline(
            org.mockito.ArgumentMatchers.eq("token"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(101L),
            org.mockito.ArgumentMatchers.eq("main"),
            org.mockito.ArgumentMatchers.argThat(variables ->
                REPOSITORY.equals(variables.get("DEPLOY_IMAGE_REPOSITORY"))
                    && "192.0.2.10:5000".equals(variables.get("NEXUS_REGISTRY"))));
    }

    @Test
    void staticComponentManifestMismatchPreventsPublishingAnyVerifiedRelease() throws Exception {
        Project project = GitlabReleaseManifestTest.project();
        when(settingService.get("nexus.registry", "")).thenReturn("registry");
        DeployRequest request = newBuildRequest(project);
        Map<String,Object> pipeline = Map.of("id",8L,"status","success","sha",GitlabReleaseManifestTest.SHA);
        when(gitlabService.resolveAccessToken(null,(String)null)).thenReturn("token");
        when(readinessService.evaluate("token",null,project,"main")).thenReturn(
            GitlabCiReadiness.builder().ready(true).blockers(List.of()).warnings(List.of()).build());
        when(gitlabService.triggerPipeline(eq("token"),org.mockito.ArgumentMatchers.isNull(),eq(9L),eq("main"),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("id",8L));
        when(gitlabService.getPipeline("token",null,9L,8L)).thenReturn(pipeline);
        var metadata = GitlabReleaseManifest.parse(GitlabReleaseManifestTest.body(),project,GitlabReleaseManifestTest.primary());
        when(artifactService.resolveAndVerify("token",null,project,pipeline)).thenReturn(metadata);
        when(nexusMetadataService.resolveManifest(any(),eq("v1"))).thenAnswer(call -> {
            Project queried = call.getArgument(0);
            return Map.of("digest",queried.getImageRepository().endsWith("/api") ? GitlabReleaseManifestTest.API : "sha256:"+"d".repeat(64));
        });
        assertThatThrownBy(() -> service.ensureBuildArtifact(request,"OPERATOR",message -> {}))
            .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("digest");
        assertThat(request.getImageDigest()).isNull();
        assertThat(request.getReleaseManifestSha256()).isNull();
        verify(nexusMetadataService).resolveManifest(org.mockito.ArgumentMatchers.argThat(
            queried -> "registry/qa/web".equals(queried.getImageRepository())),eq("v1"));
    }

    @Test
    void mrBoundBuildCreatesExactTagAndRejectsPipelineAtDifferentSha() throws Exception {
        Project project = Project.builder().id(1L).name("app").gitlabProjectId(101L)
            .imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        bindMergedEvidence(request);
        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");
        when(readinessService.evaluate("token", null, project, request.getImmutableBuildRef()))
            .thenReturn(GitlabCiReadiness.builder().ready(true).ref(request.getImmutableBuildRef())
                .refType("TAG").protectedRef(true).executableRunnerCount(1)
                .matchingOnlineRunnerCount(1).blockers(List.of()).warnings(List.of()).build());
        when(gitlabService.triggerPipeline(
            eq("token"), org.mockito.ArgumentMatchers.isNull(), eq(101L),
            eq(request.getImmutableBuildRef()), org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(Map.of("id", 88L, "sha", "ffffffffffffffffffffffffffffffffffffffff"));

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("pipeline commit SHA");
        verify(gitlabService).ensureImmutableTag(
            "token", null, 101L, request.getImmutableBuildRef(), COMMIT_SHA);
        verify(artifactService, never()).resolveAndVerify(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void mrBoundBuildRejectsArtifactAtDifferentShaBeforeManifestCheck() throws Exception {
        Project project = Project.builder().id(1L).name("app").gitlabProjectId(101L)
            .imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        bindMergedEvidence(request);
        Map<String, Object> pipeline = Map.of(
            "id", 88L, "status", "success", "sha", COMMIT_SHA, "source", "api");
        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");
        when(readinessService.evaluate("token", null, project, request.getImmutableBuildRef()))
            .thenReturn(GitlabCiReadiness.builder().ready(true).ref(request.getImmutableBuildRef())
                .refType("TAG").protectedRef(true).executableRunnerCount(1)
                .matchingOnlineRunnerCount(1).blockers(List.of()).warnings(List.of()).build());
        when(gitlabService.triggerPipeline(
            eq("token"), org.mockito.ArgumentMatchers.isNull(), eq(101L),
            eq(request.getImmutableBuildRef()), org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(Map.of("id", 88L, "sha", COMMIT_SHA));
        when(gitlabService.getPipeline("token", null, 101L, 88L)).thenReturn(pipeline);
        when(artifactService.resolveAndVerify("token", null, project, pipeline)).thenReturn(
            new GitlabBuildArtifactMetadata(701L, 88L, REPOSITORY, "artifact-tag", DIGEST,
                "ffffffffffffffffffffffffffffffffffffffff"));

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("artifact commit SHA");
        verify(nexusMetadataService, never()).resolveManifest(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void mrBoundBuildRejectsPersistedPipelineIdInsteadOfTrustingExternalPipeline() throws Exception {
        Project project = Project.builder().id(1L).gitlabProjectId(101L).imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        bindMergedEvidence(request);
        request.setGitlabPipelineId(88L);
        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("cannot reuse");
        verify(gitlabService, never()).getPipeline(anyString(), any(), eq(101L), eq(88L));
    }

    @Test
    void mrBoundBuildRejectsNonApiPipelineEvenAtExactSha() throws Exception {
        Project project = Project.builder().id(1L).name("app").gitlabProjectId(101L)
            .imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        bindMergedEvidence(request);
        Map<String, Object> pipeline = Map.of(
            "id", 88L, "status", "success", "sha", COMMIT_SHA, "source", "push");
        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");
        when(readinessService.evaluate("token", null, project, request.getImmutableBuildRef()))
            .thenReturn(GitlabCiReadiness.builder().ready(true).ref(request.getImmutableBuildRef())
                .refType("TAG").protectedRef(true).executableRunnerCount(1)
                .matchingOnlineRunnerCount(1).blockers(List.of()).warnings(List.of()).build());
        when(gitlabService.triggerPipeline(
            eq("token"), org.mockito.ArgumentMatchers.isNull(), eq(101L),
            eq(request.getImmutableBuildRef()), org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(Map.of("id", 88L, "sha", COMMIT_SHA));
        when(gitlabService.getPipeline("token", null, 101L, 88L)).thenReturn(pipeline);

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("pipeline source");
        verify(artifactService, never()).resolveAndVerify(anyString(), any(), any(), any());
    }

    @Test
    void mrBoundBuildRequiresProtectedImmutableTag() throws Exception {
        Project project = Project.builder().id(1L).name("app").gitlabProjectId(101L)
            .imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        bindMergedEvidence(request);
        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");
        when(readinessService.evaluate("token", null, project, request.getImmutableBuildRef()))
            .thenReturn(GitlabCiReadiness.builder().ready(true).ref(request.getImmutableBuildRef())
                .refType("TAG").protectedRef(false).executableRunnerCount(1)
                .matchingOnlineRunnerCount(1).blockers(List.of()).warnings(List.of()).build());

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("protected");
        verify(gitlabService, never()).triggerPipeline(anyString(), any(), eq(101L), anyString(), any());
    }

    @Test
    void blocksPipelineCreationWhenGitlabCiIsNotReady() throws Exception {
        Project project = Project.builder()
            .id(1L)
            .name("app")
            .gitlabProjectId(101L)
            .imageRepository(REPOSITORY)
            .build();
        DeployRequest request = newBuildRequest(project);
        when(gitlabService.resolveAccessToken(null, (String) null)).thenReturn("token");
        when(readinessService.evaluate("token", null, project, "main")).thenReturn(GitlabCiReadiness.builder()
            .ready(false)
            .ref("main")
            .refType("BRANCH")
            .protectedRef(false)
            .runnerTag("offline")
            .matchingOnlineRunnerCount(0)
            .executableRunnerCount(0)
            .blockers(List.of("offline 태그 Runner가 없습니다."))
            .warnings(List.of())
            .build());

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("사전점검 실패")
            .hasMessageContaining("Runner");
        verify(gitlabService, never()).triggerPipeline(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap()
        );
    }

    @Test
    void reusableTestImageFailsClosedWhenDigestIsMissing() {
        Project project = Project.builder().id(1L).imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setImageTag("existing-tag");

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("digest")
            .hasMessageContaining("중단");
    }

    @Test
    void existingImageWithPreservedScmDoesNotCreateOrReverifyAGitlabBuild() throws Exception {
        Project project = Project.builder().id(1L).imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setImageTag("existing-tag");
        request.setImageDigest(DIGEST);
        request.setGitlabPipelineId(4L);
        request.setCommitSha(COMMIT_SHA);
        when(nexusMetadataService.resolveManifest(project, "existing-tag")).thenReturn(Map.of("digest", DIGEST));
        assertThat(service.ensureBuildArtifact(request, "OPERATOR", message -> { })).isNull();
        org.mockito.Mockito.verifyNoInteractions(gitlabService, artifactService, readinessService);
        assertThat(request.getGitlabPipelineId()).isEqualTo(4L);
        assertThat(request.getCommitSha()).isEqualTo(COMMIT_SHA);
    }

    @Test
    void reusableTestImageFailsClosedWhenManifestDigestDiffers() throws Exception {
        Project project = Project.builder().id(1L).imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setImageTag("existing-tag");
        request.setImageDigest(DIGEST);
        when(nexusMetadataService.resolveManifest(project, "existing-tag")).thenReturn(Map.of(
            "digest", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("다릅니다")
            .hasMessageContaining("중단");
    }

    @Test
    void reusableTestImageFailsClosedWhenManifestCannotBeRead() throws Exception {
        Project project = Project.builder().id(1L).imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setImageTag("missing-tag");
        request.setImageDigest(DIGEST);
        when(nexusMetadataService.resolveManifest(project, "missing-tag"))
            .thenThrow(new IllegalArgumentException("선택한 이미지 태그를 Nexus에서 찾을 수 없습니다."));

        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("manifest")
            .hasMessageContaining("중단");
    }

    @Test
    void systemAutomationUsesOnlyTheSystemGitlabTokenPath() {
        DeployRequest request = newBuildRequest(Project.builder().id(1L).build());
        request.setRequestedBy(User.builder().id(7L).gitlabToken("requester-pat").build());
        when(gitlabService.resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR))
            .thenReturn("system-token");

        String token = ReflectionTestUtils.invokeMethod(
            service, "resolveGitlabToken", request, AutomaticDispatchService.SYSTEM_ACTOR);

        assertThat(token).isEqualTo("system-token");
        verify(gitlabService).resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR);
        verify(gitlabService, never()).resolveAccessToken(eq("requester-pat"), anyString());
    }

    @Test
    void systemAutomationFailsClosedWhenSystemGitlabTokenIsMissing() {
        DeployRequest request = newBuildRequest(Project.builder().id(1L).build());
        when(gitlabService.resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR))
            .thenReturn(null);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            service, "resolveGitlabToken", request, AutomaticDispatchService.SYSTEM_ACTOR))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("system automation token");
    }

    @Test
    void asyncApprovedMergedDeveloperRequestUsesOneSystemBotAcrossTagReadinessTriggerPollAndArtifact() throws Exception {
        Project project = Project.builder().id(1L).gitlabProjectId(101L).imageRepository(REPOSITORY).build();
        DeployRequest request = newBuildRequest(project);
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        request.setRequestedBy(User.builder().id(7L).role(UserRole.DEVELOPER).gitlabToken("developer-pat").build());
        bindMergedEvidence(request);
        when(gitlabService.resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR)).thenReturn("maintainer-bot");
        when(gitlabService.getAuthenticatedUserIdentity("maintainer-bot", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(5L, "ci-bot", false));
        when(readinessService.evaluate("maintainer-bot", null, project, request.getImmutableBuildRef()))
            .thenReturn(GitlabCiReadiness.builder().ready(true).protectedRef(true)
                .blockers(List.of()).warnings(List.of()).build());
        Map<String, Object> pipeline = Map.of("id", 88L, "status", "success", "sha", COMMIT_SHA, "source", "api");
        when(gitlabService.triggerPipeline(eq("maintainer-bot"), any(), eq(101L),
            eq(request.getImmutableBuildRef()), any())).thenReturn(Map.of("id", 88L, "sha", COMMIT_SHA));
        when(gitlabService.getPipeline("maintainer-bot", null, 101L, 88L))
            .thenReturn(Map.of("id", 88L, "status", "running"), pipeline);
        GitlabBuildArtifactMetadata artifact = new GitlabBuildArtifactMetadata(
            701L, 88L, REPOSITORY, "tag", DIGEST, COMMIT_SHA);
        when(artifactService.resolveAndVerify("maintainer-bot", null, project, pipeline)).thenReturn(artifact);
        when(nexusMetadataService.resolveManifest(project, "tag")).thenReturn(Map.of("digest", DIGEST));

        assertThat(java.util.concurrent.CompletableFuture.supplyAsync(
            () -> service.ensureBuildArtifact(request, "OPERATOR", message -> { })).join()).isEqualTo(artifact);

        verify(gitlabService).ensureImmutableTag("maintainer-bot", null, 101L, request.getImmutableBuildRef(), COMMIT_SHA);
        verify(gitlabService, org.mockito.Mockito.times(2)).getPipeline("maintainer-bot", null, 101L, 88L);
        verify(gitlabService, never()).resolveAccessToken(eq("developer-pat"), any());
    }

    @Test
    void mergedBuildDoesNotFallBackToDeveloperWhenSystemBotIsMissing() throws Exception {
        DeployRequest request = mergedRequest();
        request.setRequestedBy(User.builder().role(UserRole.DEVELOPER).gitlabToken("developer-pat").build());
        when(gitlabService.resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR)).thenReturn(null);
        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "SUPER_ADMIN", message -> { }))
            .hasMessageContaining("configured GitLab system bot");
        verify(gitlabService, never()).ensureImmutableTag(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(gitlabService, never()).resolveAccessToken(eq("developer-pat"), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,ci-bot", "false,root"})
    void mergedBuildRejectsAdminOrRootSystemIdentityBeforeTag(boolean admin, String username) throws Exception {
        DeployRequest request = mergedRequest();
        when(gitlabService.getAuthenticatedUserIdentity("token", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(5L, username, admin));
        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .hasMessageContaining("non-admin");
        verify(gitlabService, never()).ensureImmutableTag(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void unavailableSystemBotIdentityFailsBeforeTagWithoutRequesterFallback() throws Exception {
        DeployRequest request = mergedRequest();
        when(gitlabService.getAuthenticatedUserIdentity("token", null)).thenThrow(new java.io.IOException("unavailable"));
        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .hasMessageContaining("identity could not be verified");
        verify(gitlabService, never()).ensureImmutableTag(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "id", "project", "gitlab", "iid", "sha", "branch", "ref", "status", "tokenSource", "partial"})
    void mismatchedOrPartialMrBindingNeverSelectsSystemBot(String mismatch) throws Exception {
        DeployRequest request = mergedRequest();
        MergeRequestEvidence evidence = evidenceRepository.findMerged(1L, 101L, 4L).orElseThrow();
        switch (mismatch) {
            case "missing" -> when(evidenceRepository.findMerged(1L, 101L, 4L)).thenReturn(Optional.empty());
            case "id" -> evidence.setId(999L);
            case "project" -> evidence.setProjectId(2L);
            case "gitlab" -> evidence.setGitlabProjectId(102L);
            case "iid" -> evidence.setMergeRequestIid(5L);
            case "sha" -> evidence.setMergedResultSha("f".repeat(40));
            case "branch" -> evidence.setTargetBranch("other");
            case "ref" -> request.setImmutableBuildRef("deploy/mr-4-arbitrary");
            case "status" -> evidence.setStatus(MergeRequestEvidenceStatus.APPROVED);
            case "tokenSource" -> evidence.setTokenSource("SYSTEM_BOT");
            case "partial" -> request.setMrEvidenceId(null);
            default -> throw new AssertionError(mismatch);
        }
        assertThatThrownBy(() -> service.ensureBuildArtifact(request, AutomaticDispatchService.SYSTEM_ACTOR, message -> { }))
            .isInstanceOf(RemoteExecutionException.class);
        verify(gitlabService, never()).resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR);
        verify(gitlabService, never()).ensureImmutableTag(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void nonMrManualBuildKeepsRequesterPersonalToken() {
        DeployRequest request = newBuildRequest(Project.builder().id(1L).build());
        request.setRequestedBy(User.builder().role(UserRole.DEVELOPER).gitlabToken("developer-pat").build());
        when(gitlabService.resolveAccessToken("developer-pat", "DEVELOPER")).thenReturn("developer-pat");
        assertThat(ReflectionTestUtils.<String>invokeMethod(service, "resolveGitlabToken", request, "OPERATOR"))
            .isEqualTo("developer-pat");
        verify(gitlabService, never()).resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR);
    }

    private DeployRequest mergedRequest() throws Exception {
        DeployRequest request = newBuildRequest(Project.builder().id(1L).gitlabProjectId(101L).build());
        request.setExpectedCommitSha(COMMIT_SHA);
        request.setImmutableBuildRef("deploy/mr-4-0123456789ab");
        bindMergedEvidence(request);
        return request;
    }

    private void bindMergedEvidence(DeployRequest request) throws Exception {
        request.setSourceMrIid(4L);
        request.setMrEvidenceId(44L);
        when(evidenceRepository.findMerged(request.getProject().getId(), request.getProject().getGitlabProjectId(), 4L))
            .thenReturn(Optional.of(MergeRequestEvidence.builder().id(44L).projectId(request.getProject().getId())
                .gitlabProjectId(request.getProject().getGitlabProjectId()).mergeRequestIid(4L)
                .status(MergeRequestEvidenceStatus.MERGED).tokenSource("USER_PAT")
                .targetBranch("main").mergedResultSha(COMMIT_SHA).build()));
        when(gitlabService.resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR)).thenReturn("token");
        when(gitlabService.getAuthenticatedUserIdentity("token", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(5L, "ci-bot", false));
    }

    private DeployRequest newBuildRequest(Project project) {
        if (project.getImageRepository() == null) {
            project.setImageRepository(REPOSITORY);
        }
        return DeployRequest.builder()
            .id(1L)
            .reqNo("REQ-1")
            .environment(DeployEnvironment.TEST)
            .branch("main")
            .project(project)
            .build();
    }

    @Test
    void mismatchedFrozenRegistryRejectsBeforeTagOrPipelineWithoutChangingSnapshot() throws Exception {
        DeployRequest request = mergedRequest();
        DeploymentRuntimeSettings frozen = new DeploymentRuntimeSettings(
            "registry", "/tmp/images", "/srv/static", "1g", "http://nexus.local",
            "host.docker.internal:25000", "http://nexus-api:8081", false);
        request.getProject().setDeploymentRuntimeSettings(frozen);
        request.getProject().setImageRepository("10.250.0.11:5000/demo-app-final06f9");
        assertThatThrownBy(() -> service.ensureBuildArtifact(request, "OPERATOR", message -> { }))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("프로젝트 이미지 주소와 Nexus Registry가 다릅니다");
        verify(gitlabService, never()).ensureImmutableTag(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(gitlabService, never()).triggerPipeline(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(request.getGitlabPipelineId()).isNull();
        assertThat(request.getProject().getDeploymentRuntimeSettings()).isSameAs(frozen);
        assertThat(frozen.nexusRegistry()).isEqualTo("host.docker.internal:25000");
        assertThat(request.getProject().getImageRepository()).isEqualTo("10.250.0.11:5000/demo-app-final06f9");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "192.0.2.10:5000", "http://192.0.2.10:5000/", "https://192.0.2.10:5000"
    })
    void supportedRegistryFormsProduceExactCiAuthority(String registry) {
        when(settingService.get("nexus.registry", "")).thenReturn(registry);
        assertThat(buildVariables()).containsEntry("NEXUS_REGISTRY", "192.0.2.10:5000")
            .containsEntry("DEPLOY_IMAGE_REPOSITORY", REPOSITORY);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "", "http://user:credential@192.0.2.10:5000", "http://192.0.2.10:5000/path",
        "http://192.0.2.10:5000?secret=value", "http://192.0.2.10:5000#fragment",
        "ftp://192.0.2.10:5000", "different.example:5000", "http://"
    })
    void invalidOrForeignRegistryFailsWithoutExposingConfiguredValue(String registry) throws Exception {
        when(settingService.get("nexus.registry", "")).thenReturn(registry);
        assertThatThrownBy(this::buildVariables).isInstanceOf(RemoteExecutionException.class)
            .hasMessageNotContaining("credential").hasMessageNotContaining("secret=value");
        verify(gitlabService, never()).triggerPipeline(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }
}
