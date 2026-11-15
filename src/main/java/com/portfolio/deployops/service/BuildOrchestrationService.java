package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.MergeRequestEvidenceStatus;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import com.portfolio.deployops.dto.GitlabCiReadiness;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BuildOrchestrationService {

    private static final Duration PIPELINE_POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration PIPELINE_TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> RUNNING_PIPELINE_STATUSES = Set.of(
        "created",
        "pending",
        "preparing",
        "running",
        "waiting_for_resource"
    );
    private static final Set<String> SUCCESS_PIPELINE_STATUSES = Set.of("success");

    private final GitlabService gitlabService;
    private final GitlabPipelineArtifactService gitlabPipelineArtifactService;
    private final GitlabCiReadinessService gitlabCiReadinessService;
    private final NexusMetadataService nexusMetadataService;
    private final SystemSettingService settingService;
    private final UserRepository userRepository;
    private final DeployRequestRepository deployRequestRepository;
    private final MergeRequestEvidenceRepository mergeRequestEvidenceRepository;
    private final com.portfolio.deployops.repository.ExecutionGuardRepository executionGuardRepository;

    /**
     * GitLab의 정확한 pipeline job artifact와 Nexus manifest digest를 모두 검증한 경우에만
     * provenance로 저장할 수 있는 정본 메타데이터를 반환한다. 사용자가 지정한 IMAGE_ONLY
     * 이미지는 Nexus에 존재하더라도 GitLab 산출물 provenance가 아니므로 {@code null}이다.
     */
    public GitlabBuildArtifactMetadata ensureBuildArtifact(
        DeployRequest request,
        String actorRole,
        Consumer<String> logSink
    ) {
        requireRequest(request);
        executionGuardRepository.assertNoNexusMaintenance();

        if (request.getEnvironment() != DeployEnvironment.TEST) {
            return null;
        }

        if (hasReusableImage(request)) {
            logSink.accept("[build] 요청에 지정된 기존 이미지의 manifest를 검증합니다.");
            verifyManifestDigest(request.getProject(), request.getImageTag(), request.getImageDigest(), logSink);
            return null;
        }

        Project project = request.getProject();
        if (project.getGitlabProjectId() == null) {
            throw new RemoteExecutionException("GitLab project id is not configured for this project.");
        }

        String gitlabToken = resolveGitlabToken(request, actorRole);
        String expectedCommitSha = trimToNull(request.getExpectedCommitSha());
        String ref;
        if (expectedCommitSha != null) {
            if (!expectedCommitSha.matches("^[0-9a-fA-F]{40,64}$")) {
                throw new RemoteExecutionException("MR expected commit SHA is invalid.");
            }
            ref = requireText(request.getImmutableBuildRef(), "Immutable build ref is missing for the MR-bound request.");
        } else {
            ref = requireText(request.getBranch(), "Git ref is required for build orchestration.");
        }

        try {
            Map<String, Object> pipeline;
            Long existingPipelineId = request.getGitlabPipelineId();
            if (existingPipelineId != null) {
                if (expectedCommitSha != null) {
                    throw new RemoteExecutionException(
                        "MR-bound build cannot reuse a pipeline that was not created in this execution.");
                }
                logSink.accept("[build] Reusing GitLab pipeline #" + existingPipelineId + ".");
                pipeline = waitForPipeline(gitlabToken, project.getGitlabProjectId(), existingPipelineId, logSink);
                assertPipelineSha(pipeline, expectedCommitSha);
            } else {
                Map<String, String> pipelineVariables = buildPipelineVariables(project, request);
                if (expectedCommitSha != null) {
                    logSink.accept("[build] MR 병합 결과 SHA에 고유 태그 " + ref + "를 결속합니다.");
                    gitlabService.ensureImmutableTag(
                        gitlabToken, null, project.getGitlabProjectId(), ref, expectedCommitSha);
                }
                assertCiReady(gitlabToken, project, ref, expectedCommitSha != null, logSink);
                logSink.accept("[build] Triggering a new GitLab pipeline for ref " + ref + ".");
                Map<String, Object> triggered = gitlabService.triggerPipeline(
                    gitlabToken,
                    null,
                    project.getGitlabProjectId(),
                    ref,
                    pipelineVariables
                );
                assertPipelineSha(triggered, expectedCommitSha);
                long pipelineId = requireLong(triggered.get("id"), "Unable to read the created GitLab pipeline id.");
                if (deployRequestRepository.bindCreatedPipeline(request.getId(), pipelineId) != 1) {
                    throw new RemoteExecutionException(
                        "Created GitLab pipeline could not be bound to the active deploy request.");
                }
                request.setGitlabPipelineId(pipelineId);
                logSink.accept("[build] GitLab pipeline #" + pipelineId + " was created.");
                pipeline = waitForPipeline(gitlabToken, project.getGitlabProjectId(), pipelineId, logSink);
                assertPipelineSha(pipeline, expectedCommitSha);
                assertPipelineSource(pipeline, expectedCommitSha);
            }

            long pipelineId = requireLong(pipeline.get("id"), "Unable to read the finished GitLab pipeline id.");
            logSink.accept("[build] pipeline artifact에서 이미지 계약을 확인합니다.");
            GitlabBuildArtifactMetadata artifact = gitlabPipelineArtifactService.resolveAndVerify(
                gitlabToken, null, project, pipeline);
            if (GitlabReleaseManifest.required(project)) GitlabReleaseManifest.validateMetadata(project, artifact);
            if (expectedCommitSha != null
                && (trimToNull(artifact.commitSha()) == null
                    || !expectedCommitSha.equalsIgnoreCase(artifact.commitSha()))) {
                throw new RemoteExecutionException(
                    "GitLab artifact commit SHA does not match the MR merged result SHA.");
            }
            verifyManifestDigest(project, artifact.tag(), artifact.digest(), logSink);
            for (var component : artifact.components()) {
                Project componentProject = Project.builder()
                    .imageRepository(component.repository())
                    .nexusDockerRepository(project.getNexusDockerRepository())
                    .deploymentRuntimeSettings(project.getDeploymentRuntimeSettings()).build();
                verifyManifestDigest(componentProject, component.tag(), component.digest(), logSink);
                logSink.accept("[build] 배포 단위 " + component.code() + "의 Nexus digest를 검증했습니다.");
            }

            // artifact와 Nexus manifest 검증이 모두 끝난 뒤에만 배포 요청의 정본 메타데이터로 반영한다.
            request.setGitlabPipelineId(pipelineId);
            request.setCommitSha(artifact.commitSha());
            request.setImageTag(artifact.tag());
            request.setImageDigest(artifact.digest());
            logSink.accept("[build] 검증된 이미지 태그: " + artifact.tag());
            return artifact;
        } catch (RemoteExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteExecutionException("Build orchestration failed: " + simplify(e.getMessage()), e);
        }
    }

    private void assertPipelineSha(Map<String, Object> pipeline, String expectedCommitSha) {
        if (expectedCommitSha == null) {
            return;
        }
        String actual = trimToNull(String.valueOf(pipeline.getOrDefault("sha", "")));
        if (actual == null || !expectedCommitSha.equalsIgnoreCase(actual)) {
            throw new RemoteExecutionException(
                "GitLab pipeline commit SHA does not match the MR merged result SHA.");
        }
    }

    private void assertPipelineSource(Map<String, Object> pipeline, String expectedCommitSha) {
        if (expectedCommitSha == null) {
            return;
        }
        String source = trimToNull(String.valueOf(pipeline.getOrDefault("source", "")));
        if (!"api".equalsIgnoreCase(source)) {
            throw new RemoteExecutionException(
                "MR-bound GitLab pipeline source is not the DeployOps API execution.");
        }
    }

    private Map<String, Object> waitForPipeline(
        String gitlabToken,
        long gitlabProjectId,
        long pipelineId,
        Consumer<String> logSink
    ) throws Exception {
        long deadline = System.nanoTime() + PIPELINE_TIMEOUT.toNanos();
        String lastStatus = null;

        while (System.nanoTime() < deadline) {
            Map<String, Object> pipeline = gitlabService.getPipeline(gitlabToken, null, gitlabProjectId, pipelineId);
            String status = normalizeStatus(String.valueOf(pipeline.getOrDefault("status", "")));
            if (!status.equals(lastStatus)) {
                logSink.accept("[build] Pipeline #" + pipelineId + " status=" + status);
                lastStatus = status;
            }

            if (SUCCESS_PIPELINE_STATUSES.contains(status)) {
                return pipeline;
            }
            if (!RUNNING_PIPELINE_STATUSES.contains(status)) {
                throw new RemoteExecutionException("GitLab pipeline #" + pipelineId + " finished with status=" + status + ".");
            }

            try {
                Thread.sleep(PIPELINE_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RemoteExecutionException("Pipeline polling was interrupted.", e);
            }
        }

        throw new RemoteExecutionException(
            "GitLab pipeline #" + pipelineId + " did not finish within " + PIPELINE_TIMEOUT.toMinutes() + " minutes."
        );
    }

    private Map<String, String> buildPipelineVariables(Project project, DeployRequest request) {
        Map<String, String> variables = new LinkedHashMap<>();
        putIfPresent(variables, "DEPLOY_REQUEST_ID", request.getReqNo());
        putIfPresent(variables, "DEPLOY_TARGET_ENV", request.getEnvironment().name());
        putIfPresent(variables, "DEPLOY_SOURCE_REF",
            request.getImmutableBuildRef() != null ? request.getImmutableBuildRef() : request.getBranch());
        putIfPresent(variables, "DEPLOY_IMAGE_REPOSITORY", project.getImageRepository());
        putIfPresent(variables, "DEPLOY_BUILD_TOOL", project.getBuildTool() != null ? project.getBuildTool().name() : null);
        putIfPresent(variables, "DEPLOY_BUILD_COMMAND", project.getBuildCommand());
        putIfPresent(variables, "DEPLOY_BUILD_FILE_PATH", project.getBuildFilePath());
        putIfPresent(variables, "DEPLOY_ARTIFACT_PATH", project.getArtifactPath());
        putIfPresent(variables, "DEPLOY_DOCKERFILE_PATH", project.getDockerfilePath());
        putIfPresent(variables, "DEPLOY_DOCKER_CONTEXT_PATH", project.getDockerContextPath());
        putIfPresent(variables, "NEXUS_DOCKER_REPOSITORY", project.getNexusDockerRepository());
        putIfPresent(variables, "NEXUS_MAVEN_RELEASE_REPOSITORY", project.getNexusMavenReleaseRepository());
        putIfPresent(variables, "NEXUS_MAVEN_SNAPSHOT_REPOSITORY", project.getNexusMavenSnapshotRepository());
        String mavenBase = executionSetting(project, "nexus.maven.base-url", "");
        putIfPresent(variables, "NEXUS_URL", NexusMavenEndpoint.normalizeOptional(
            mavenBase == null || mavenBase.isBlank() ? executionSetting(project, "nexus.url", "") : mavenBase));
        putIfPresent(variables, "NEXUS_REGISTRY", validatedPipelineRegistry(project));
        // 레지스트리 자격증명은 GitLab CI/CD masked variable(NEXUS_REGISTRY_USER/PASSWORD)로 관리하는 것이 기본.
        // 트리거 변수는 파이프라인 화면·API로 노출될 수 있어, 레거시 파이프라인 호환이 필요할 때만 opt-in.
        if (Boolean.parseBoolean(executionSetting(project, "nexus.pipeline-credentials", "false"))) {
            putIfPresent(variables, "NEXUS_USER", settingService.get("nexus.user", ""));
            putIfPresent(variables, "NEXUS_PASS", settingService.get("nexus.pass", ""));
        }
        return variables;
    }

    private String validatedPipelineRegistry(Project project) {
        String configured = trimToNull(executionSetting(project, "nexus.registry", ""));
        String repository = trimToNull(project.getImageRepository());
        if (configured == null || repository == null) {
            throw new RemoteExecutionException("이미지 저장소와 Nexus Registry 주소를 설정한 뒤 CI를 실행해 주세요.");
        }
        try {
            java.net.URI registry = java.net.URI.create(
                configured.contains("://") ? configured : "https://" + configured);
            if (!("http".equals(registry.getScheme()) || "https".equals(registry.getScheme()))
                || registry.getHost() == null || registry.getUserInfo() != null
                || registry.getQuery() != null || registry.getFragment() != null
                || !(registry.getPath().isEmpty() || "/".equals(registry.getPath()))) {
                throw new IllegalArgumentException();
            }
            String authority = registry.getRawAuthority();
            int slash = repository.indexOf('/');
            if (slash <= 0 || slash == repository.length() - 1
                || !repository.substring(0, slash).equalsIgnoreCase(authority)) {
                throw new RemoteExecutionException(
                    "프로젝트 이미지 주소와 Nexus Registry가 다릅니다. 연동 설정을 확인한 뒤 새 배포 요청을 만들어 주세요.");
            }
            return repository.substring(0, slash);
        } catch (IllegalArgumentException e) {
            throw new RemoteExecutionException("Nexus Registry 주소는 http(s)://호스트:포트 또는 호스트:포트 형식이어야 합니다.");
        }
    }

    private String executionSetting(Project project, String key, String defaultValue) {
        DeploymentRuntimeSettings snapshot = project != null ? project.getDeploymentRuntimeSettings() : null;
        if (snapshot != null) {
            return switch (key) {
                case "nexus.url" -> snapshot.nexusUrl();
                case "nexus.maven.base-url" -> snapshot.nexusMavenBaseUrl();
                case "nexus.registry" -> snapshot.nexusRegistry();
                case "nexus.registry.api-url" -> snapshot.nexusRegistryApiUrl();
                case "nexus.pipeline-credentials" -> String.valueOf(snapshot.nexusPipelineCredentials());
                default -> defaultValue;
            };
        }
        return settingService.get(key, defaultValue);
    }

    private void verifyManifestDigest(Project project, String imageTag, String expectedDigest, Consumer<String> logSink) {
        String tag = requireText(imageTag, "검증할 이미지 태그가 없습니다.");
        String expected = requireText(expectedDigest, "이미지 digest가 없어 TEST 배포를 중단합니다.");
        try {
            Map<String, Object> manifest = nexusMetadataService.resolveManifest(project, tag);
            String actual = trimToNull(String.valueOf(manifest.getOrDefault("digest", "")));
            if (actual == null) {
                throw new RemoteExecutionException("Nexus manifest가 digest를 반환하지 않아 TEST 배포를 중단합니다.");
            }
            if (!expected.equalsIgnoreCase(actual)) {
                throw new RemoteExecutionException(
                    "CI artifact digest와 Nexus manifest digest가 다릅니다. TEST 배포를 중단합니다.");
            }
            logSink.accept("[build] CI artifact와 Nexus manifest digest가 일치합니다.");
        } catch (RemoteExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteExecutionException(
                "Nexus manifest를 검증할 수 없어 TEST 배포를 중단합니다: " + simplify(e.getMessage()), e);
        }
    }

    private void assertCiReady(
        String gitlabToken,
        Project project,
        String ref,
        boolean requireProtectedRef,
        Consumer<String> logSink
    ) throws Exception {
        logSink.accept("[build] GitLab ref 보호 상태, Runner와 CI/CD 필수 변수를 사전점검합니다.");
        GitlabCiReadiness readiness = gitlabCiReadinessService.evaluate(gitlabToken, null, project, ref);
        for (String warning : readiness.getWarnings()) {
            logSink.accept("[build] 경고: " + warning);
        }
        if (!readiness.isReady()) {
            throw new RemoteExecutionException("GitLab CI 사전점검 실패: " + String.join(" ", readiness.getBlockers()));
        }
        if (requireProtectedRef && !readiness.isProtectedRef()) {
            throw new RemoteExecutionException(
                "MR 병합 결과 빌드 태그가 보호되지 않았습니다. GitLab protected tag 'deploy/mr-*'를 설정해 주세요.");
        }
        logSink.accept("[build] GitLab CI 사전점검 통과: " + readiness.getRefType() + " '"
            + readiness.getRef() + "' / 실행 가능 Runner " + readiness.getExecutableRunnerCount() + "대");
    }

    private boolean hasReusableImage(DeployRequest request) {
        return request.getImageTag() != null && !request.getImageTag().isBlank();
    }

    private String resolveGitlabToken(DeployRequest request, String actorRole) {
        if (hasMergeBinding(request)) {
            assertMergedBuildBinding(request);
            return requireMergedBuildSystemToken();
        }
        if (AutomaticDispatchService.SYSTEM_ACTOR.equals(actorRole)) {
            String automationToken = gitlabService.resolveAccessToken(null, actorRole);
            if (automationToken != null) {
                return automationToken;
            }
            throw new RemoteExecutionException("GitLab system automation token is not configured.");
        }

        User requester = request.getRequestedBy();
        String requesterToken = requester != null ? requester.getGitlabToken() : null;
        String requesterRole = requester != null && requester.getRole() != null ? requester.getRole().name() : null;

        if ((requesterToken == null || requesterToken.isBlank()) && requester != null && requester.getId() != null) {
            User resolvedRequester = userRepository.findById(requester.getId()).orElse(null);
            if (resolvedRequester != null) {
                requesterToken = resolvedRequester.getGitlabToken();
                if (resolvedRequester.getRole() != null) {
                    requesterRole = resolvedRequester.getRole().name();
                }
            }
        }

        String token = gitlabService.resolveAccessToken(requesterToken, requesterRole);
        if (token != null) {
            return token;
        }

        if ("SUPER_ADMIN".equals(actorRole)) {
            token = gitlabService.resolveAccessToken(null, actorRole);
            if (token != null) {
                return token;
            }
            throw new RemoteExecutionException("GitLab automation token is not configured for SUPER_ADMIN execution.");
        }

        throw new RemoteExecutionException("GitLab personal access token is required for the deploy requester.");
    }

    private boolean hasMergeBinding(DeployRequest request) {
        return request.getSourceMrIid() != null || request.getMrEvidenceId() != null
            || trimToNull(request.getExpectedCommitSha()) != null
            || trimToNull(request.getImmutableBuildRef()) != null;
    }

    private void assertMergedBuildBinding(DeployRequest request) {
        Project project = request.getProject();
        String sha = trimToNull(request.getExpectedCommitSha());
        Long iid = request.getSourceMrIid();
        Long evidenceId = request.getMrEvidenceId();
        if (project == null || project.getId() == null || project.getGitlabProjectId() == null
            || iid == null || iid <= 0 || evidenceId == null || evidenceId <= 0
            || sha == null || !sha.matches("^[0-9a-fA-F]{40,64}$")) {
            throw new RemoteExecutionException("MR-bound build evidence is incomplete.");
        }
        MergeRequestEvidence evidence = mergeRequestEvidenceRepository
            .findMerged(project.getId(), project.getGitlabProjectId(), iid)
            .orElseThrow(() -> new RemoteExecutionException("Approved and merged MR build evidence is missing."));
        String expectedRef = "deploy/mr-" + iid + "-" + sha.toLowerCase(Locale.ROOT).substring(0, 12);
        if (!Objects.equals(evidence.getId(), evidenceId)
            || !Objects.equals(evidence.getProjectId(), project.getId())
            || !Objects.equals(evidence.getGitlabProjectId(), project.getGitlabProjectId())
            || !Objects.equals(evidence.getMergeRequestIid(), iid)
            || evidence.getStatus() != MergeRequestEvidenceStatus.MERGED
            || !"USER_PAT".equals(evidence.getTokenSource())
            || !sha.equalsIgnoreCase(trimToNull(evidence.getMergedResultSha()))
            || !Objects.equals(trimToNull(request.getBranch()), trimToNull(evidence.getTargetBranch()))
            || !expectedRef.equals(request.getImmutableBuildRef())) {
            throw new RemoteExecutionException("MR-bound build does not match the approved merged evidence.");
        }
    }

    private String requireMergedBuildSystemToken() {
        String token = trimToNull(gitlabService.resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR));
        if (token == null) {
            throw new RemoteExecutionException("MR-bound build requires a configured GitLab system bot token.");
        }
        try {
            GitlabService.GitlabUserIdentity identity = gitlabService.getAuthenticatedUserIdentity(token, null);
            if (identity == null || identity.id() <= 0 || trimToNull(identity.username()) == null
                || identity.admin() || "root".equalsIgnoreCase(identity.username())) {
                throw new RemoteExecutionException("MR-bound build requires a non-admin GitLab system bot account.");
            }
            return token;
        } catch (RemoteExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteExecutionException("GitLab system bot identity could not be verified for the MR-bound build.", e);
        }
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private void requireRequest(DeployRequest request) {
        if (request == null || request.getProject() == null) {
            throw new RemoteExecutionException("Deploy request or project information is missing.");
        }
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new RemoteExecutionException(message);
        }
        return normalized;
    }

    private long requireLong(Object value, String message) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            throw new RemoteExecutionException(message, e);
        }
    }

    private String normalizeStatus(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String simplify(String value) {
        if (value == null || value.isBlank()) {
            return "unknown error";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 160) + "..." : compact;
    }
}
