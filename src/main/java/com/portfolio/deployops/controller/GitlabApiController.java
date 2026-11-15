package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.GitlabCiFileForm;
import com.portfolio.deployops.dto.GitlabDockerfileForm;
import com.portfolio.deployops.dto.GitlabCiTemplateMrForm;
import com.portfolio.deployops.dto.GitlabMergeRequestCreateForm;
import com.portfolio.deployops.dto.GitlabMergeRequestActionForm;
import com.portfolio.deployops.dto.GitlabMergeRequestRejectForm;
import com.portfolio.deployops.dto.GitlabPipelineTriggerForm;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.MergeRequestOriginRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.GitlabCiInspectService;
import com.portfolio.deployops.service.GitlabCiTemplateService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.OfficeNotificationService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.MergeRequestGovernanceService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.GitlabCiReadinessService;
import com.portfolio.deployops.service.SecretMaskingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gitlab")
@RequiredArgsConstructor
@Slf4j
public class GitlabApiController {

    private final GitlabService gitlabService;
    private final GitlabCiTemplateService gitlabCiTemplateService;
    private final GitlabCiInspectService gitlabCiInspectService;
    private final com.portfolio.deployops.service.BuildProfileService buildProfileService;
    private final ProjectRepository projectRepository;
    private final ProjectPermissionService projectPermissionService;
    private final com.portfolio.deployops.service.ProjectService projectService;
    private final AuditLogService auditLogService;
    private final OfficeNotificationService officeNotificationService;
    private final MergeRequestGovernanceService mergeRequestGovernanceService;
    private final ExecutionPolicyService executionPolicyService;
    private final MergeRequestOriginRepository mergeRequestOriginRepository;
    private final DeployRequestRepository deployRequestRepository;
    private final MergeRequestEvidenceRepository mergeRequestEvidenceRepository;
    private final GitlabCiReadinessService gitlabCiReadinessService;
    private final SecretMaskingService secretMaskingService;
    private DeployComponentRepository deployComponentRepository;

    @Autowired
    public void configureComponents(DeployComponentRepository deployComponentRepository) {
        this.deployComponentRepository = deployComponentRepository;
    }

    private Project hydrateCiProject(Project project) {
        // Production injection is required; constructor-only legacy fixtures retain their supplied components.
        if (deployComponentRepository != null) {
            project.setComponents(deployComponentRepository.findActiveByProjectId(project.getId()));
        }
        return project;
    }

    private static final int MAX_JOB_TRACE_CHARS = 262_144;

    /** Reads a small source manifest and returns the detected build profile plus file previews. */
    public ApiResponse<?> inspectBuildProfile(
        @PathVariable Long deployProjectId,
        @RequestParam(required = false) String ref,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        return inspectBuildProfileOptions(deployProjectId, ref, null, null, currentUser);
    }

    @GetMapping("/deploy-projects/{deployProjectId}/build-profile")
    public ApiResponse<?> inspectBuildProfileOptions(
        @PathVariable Long deployProjectId, @RequestParam(required = false) String ref,
        @RequestParam(required = false) String sourceType, @RequestParam(required = false) Long componentId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab token is not configured.");
        return projectRepository.findById(deployProjectId).map(project -> {
            if (project.getGitlabProjectId() == null) return ApiResponse.error("NO_GITLAB_ID", "GitLab project is not connected.");
            String branch = firstNonBlank(ref, project.getDefaultBranch(), "main");
            try {
                Project scope = buildFileScope(project, componentId);
                String commit = requireFullCommitSha(gitlabService.getBranchCommitSha(token, null, project.getGitlabProjectId(), branch));
                Map<String, String> files = new LinkedHashMap<>();
                List<String> scanWarnings = new java.util.ArrayList<>();
                collectBuildFiles(token, project.getGitlabProjectId(), "", commit, files, 0, new int[]{0}, scanWarnings);
                String dockerfile = dockerfilePath(scope);
                String dockerContent = gitlabService.readRepositoryFileBounded(token, null, project.getGitlabProjectId(), dockerfile, commit, MAX_SOURCE_FILE_BYTES);
                String ciContent = gitlabService.readRepositoryFileBounded(token, null, project.getGitlabProjectId(), ".gitlab-ci.yml", commit, MAX_SOURCE_FILE_BYTES);
                if (dockerContent != null) files.put(dockerfile, dockerContent);
                if (ciContent != null) files.put(".gitlab-ci.yml", ciContent);
                Map<String, Object> result = sourceType == null || sourceType.isBlank()
                    ? buildProfileService.inspect(files, scope) : buildProfileService.inspect(files, scope, sourceType);
                result.put("ref", branch);
                result.put("baseCommitSha", commit);
                result.put("componentId", componentId);
                result.put("dockerfilePath", dockerfile);
                result.put("sourceFiles", files.keySet());
                // null means the repository has no file; the UI uses this distinction to show the generated preview.
                result.put("existingDockerfile", dockerContent);
                result.put("existingCi", ciContent);
                result.put("hasDockerfile", dockerContent != null);
                result.put("hasGitlabCi", ciContent != null);
                if (!scanWarnings.isEmpty()) {
                    var warnings = new java.util.LinkedHashSet<String>();
                    if (result.get("warnings") instanceof List<?> existing) existing.forEach(v -> warnings.add(String.valueOf(v)));
                    warnings.addAll(scanWarnings); result.put("warnings", new java.util.ArrayList<>(warnings)); result.put("scanIncomplete", true);
                    if (sourceType == null || sourceType.isBlank()) { result.put("selectionRequired", true); result.remove("generatedDockerfile"); }
                }
                try {
                    if (!Boolean.TRUE.equals(result.get("selectionRequired"))) {
                        result.put("generatedCi", gitlabCiTemplateService.generate(ciDraftProject(hydrateCiProject(project), result, componentId)));
                        result.put("ciTemplateScope", "PROJECT");
                    }
                } catch (IllegalArgumentException e) {
                    result.put("generatedCiError", e.getMessage());
                }
                return ApiResponse.ok(result);
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        }).orElse(ApiResponse.error("NOT_FOUND", "Deploy project not found."));
    }

    private boolean isBuildMetadata(String path) {
        String p = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return p.equals("pom.xml") || p.equals("build.gradle") || p.equals("build.gradle.kts")
            || p.equals("package.json") || p.equals("dockerfile") || p.equals(".gitlab-ci.yml")
            || p.equals("index.html") || p.endsWith(".c");
    }

    private void collectBuildFiles(String token, long gitlabProjectId, String path, String branch,
                                   Map<String, String> files, int depth, int[] directories, List<String> warnings) throws Exception {
        // Keep repository inspection bounded; build manifests normally live within the first few levels.
        if (depth > 3 || ++directories[0] > 16 || files.size() >= 32) { warnings.add("저장소 탐색 한도에 도달했습니다. 빌드 파일·컨텍스트와 프로젝트 유형을 직접 선택하세요."); return; }
        List<Map<String, Object>> tree = gitlabService.listRepositoryTree(token, null,
            gitlabProjectId, path.isBlank() ? null : path, branch);
        for (Map<String, Object> node : tree) {
            String nodePath = String.valueOf(node.get("path"));
            if (!safeRepositoryPath(nodePath) || files.size() >= 32) continue;
            if ("tree".equals(node.get("type"))) {
                collectBuildFiles(token, gitlabProjectId, nodePath, branch, files, depth + 1, directories, warnings);
            } else if (isBuildMetadata(nodePath)) {
                String content = gitlabService.readRepositoryFileBounded(token, null, gitlabProjectId, nodePath, branch, MAX_SOURCE_FILE_BYTES);
                if (content != null) files.put(nodePath, content);
            }
        }
        if (tree.size() >= 100) warnings.add("저장소 목록은 디렉터리당 첫 100개입니다. 누락 가능성이 있어 경로·유형을 확인하세요.");
    }

    private static final int MAX_SOURCE_FILE_BYTES = 262_144;

    private boolean safeRepositoryPath(String path) {
        return path != null && path.length() <= 500 && path.matches("[A-Za-z0-9_./+-]+")
            && !path.startsWith("/") && !path.startsWith("-") && !java.util.Arrays.asList(path.split("/", -1)).contains("..")
            && !path.contains("//");
    }

    private String dockerfilePath(Project scope) {
        String path = firstNonBlank(scope.getDockerfilePath(), "Dockerfile");
        if (!safeRepositoryPath(path) || path.equals(".") || path.equals(".gitlab-ci.yml")) throw new IllegalArgumentException("저장된 Dockerfile 경로를 확인해 주세요.");
        return path;
    }

    private Project buildFileScope(Project project, Long componentId) {
        if (componentId == null) return project;
        if (deployComponentRepository == null) throw new IllegalArgumentException("배포 단위 정보를 읽을 수 없습니다.");
        var component = deployComponentRepository.findActiveByProjectId(project.getId()).stream()
            .filter(c -> java.util.Objects.equals(c.getId(), componentId) && java.util.Objects.equals(c.getProjectId(), project.getId()) && c.isActive())
            .filter(c -> c.getArtifactType() != com.portfolio.deployops.domain.ArtifactType.PROXY_RELOAD).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("이 프로젝트의 활성 이미지 배포 단위를 선택해 주세요."));
        Project copy = new Project(); org.springframework.beans.BeanUtils.copyProperties(project, copy);
        copy.setDockerfilePath(firstNonBlank(component.getDockerfilePath(), project.getDockerfilePath(), "Dockerfile"));
        copy.setDockerContextPath(firstNonBlank(component.getDockerContextPath(), project.getDockerContextPath(), "."));
        copy.setBuildFilePath(null);
        return copy;
    }

    private Project ciDraftProject(Project project, Map<String, Object> profile, Long componentId) {
        if (componentId != null || profile.get("sourceType") == null || !(profile.get("suggestions") instanceof Map<?, ?> settings)
                || (project.getComponents() == null ? java.util.stream.Stream.<com.portfolio.deployops.domain.DeployComponent>empty() : project.getComponents().stream()).filter(com.portfolio.deployops.domain.DeployComponent::isActive)
                    .filter(c -> c.getArtifactType() != com.portfolio.deployops.domain.ArtifactType.PROXY_RELOAD).count() > 1) return project;
        Project copy = new Project(); org.springframework.beans.BeanUtils.copyProperties(project, copy);
        if (settings.get("buildTool") instanceof String tool) copy.setBuildTool(com.portfolio.deployops.domain.BuildToolType.valueOf(tool));
        if (settings.get("pipelineType") instanceof String pipeline) copy.setPipelineType(com.portfolio.deployops.domain.PipelineType.valueOf(pipeline));
        if (settings.get("buildCommand") instanceof String command) copy.setBuildCommand(command);
        if (settings.get("artifactPath") instanceof String artifact) copy.setArtifactPath(artifact);
        return copy;
    }

    /** 내가 승인해야 할 MR 모음 — MR_APPROVE 권한 프로젝트들의 열린 MR을 한 화면에 집계. */
    @GetMapping("/my-merge-requests")
    public ApiResponse<?> myMergeRequests(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.List<String> unlinkedProjects = new java.util.ArrayList<>();
        java.util.List<String> failedMrProjects = new java.util.ArrayList<>();
        java.util.List<String> failedPermissionProjects = new java.util.ArrayList<>();
        java.util.Set<String> staleOriginProjects = new java.util.LinkedHashSet<>();
        for (Project p : projectService.findMrApprovable(currentUser)) {
            if (p.getGitlabProjectId() == null) {
                unlinkedProjects.add(p.getName());
                continue;
            }
            boolean canMerge = false;
            try {
                canMerge = projectPermissionService.hasPermission(
                    currentUser, p.getId(), ProjectPermissionType.MR_MERGE);
            } catch (Exception e) {
                failedPermissionProjects.add(p.getName());
                log.warn("MR merge permission lookup failed for deploy project {}", p.getId(), e);
            }
            try {
                for (Map<String, Object> mr : gitlabService.getMergeRequests(token, null, p.getGitlabProjectId(), "opened")) {
                    Long iid = asLong(mr.get("iid"));
                    if (iid == null || isOwnedByDifferentProject(p.getId(), p.getGitlabProjectId(), iid)) {
                        staleOriginProjects.add(p.getName());
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>(mr);
                    row.put("deployProjectId", p.getId());
                    row.put("projectName", p.getName());
                    row.put("canMerge", canMerge);
                    out.add(row);
                }
            } catch (Exception e) {
                failedMrProjects.add(p.getName());
                log.warn("GitLab MR list failed for deploy project {} (GitLab project {})",
                    p.getId(), p.getGitlabProjectId(), e);
            }
        }
        java.util.List<String> warningParts = new java.util.ArrayList<>();
        if (!unlinkedProjects.isEmpty()) {
            warningParts.add("GitLab 저장소가 연결되지 않은 프로젝트: " + String.join(", ", unlinkedProjects)
                + ". 프로젝트의 GitLab 설정을 확인해 주세요.");
        }
        if (!failedMrProjects.isEmpty()) {
            warningParts.add("GitLab MR 조회 실패: " + String.join(", ", failedMrProjects)
                + ". GitLab PAT 권한·만료와 연결 상태를 확인해 주세요.");
        }
        if (!failedPermissionProjects.isEmpty()) {
            warningParts.add("병합 권한 확인 실패: " + String.join(", ", failedPermissionProjects)
                + ". 권한 상태를 확인할 때까지 병합은 허용되지 않습니다.");
        }
        if (!staleOriginProjects.isEmpty()) {
            warningParts.add("다른 DeployOps 프로젝트에서 생성된 과거 MR은 현재 저장소 연결과 달라 목록에서 제외했습니다: "
                + String.join(", ", staleOriginProjects) + ". 프로젝트의 GitLab 연결과 MR 생성 이력을 확인해 주세요.");
        }
        String warning = warningParts.isEmpty() ? null : String.join(" ", warningParts);
        return ApiResponse.ok(warning, out);
    }

    @GetMapping("/projects")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> listProjects(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        try {
            List<Map<String, Object>> projects = gitlabService.getProjects(token, null);
            return ApiResponse.ok(projects);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("GITLAB_AUTH", e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("GITLAB_ERROR", simplify(e));
        }
    }

    @GetMapping("/projects/{gitlabProjectId}/branches")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> listBranches(
        @PathVariable long gitlabProjectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        try {
            List<Map<String, Object>> branches = gitlabService.getBranches(token, null, gitlabProjectId);
            return ApiResponse.ok(branches);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("GITLAB_AUTH", e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("GITLAB_ERROR", simplify(e));
        }
    }

    @GetMapping("/deploy-projects/{deployProjectId}/branches")
    public ApiResponse<?> listBranchesByDeployProject(
        @PathVariable Long deployProjectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error(
                        "NO_GITLAB_ID",
                        "이 프로젝트에는 GitLab 프로젝트가 연결되어 있지 않습니다. 프로젝트 설정에서 먼저 GitLab 프로젝트를 연결해 주세요."
                    );
                }
                try {
                    List<Map<String, Object>> branches = gitlabService.getBranches(token, null, gitlabId);
                    return ApiResponse.ok(branches);
                } catch (IllegalArgumentException e) {
                    return ApiResponse.error("GITLAB_AUTH", e.getMessage());
                } catch (Exception e) {
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @GetMapping("/deploy-projects/{deployProjectId}/pipelines")
    public ApiResponse<?> listPipelinesByDeployProject(
        @PathVariable Long deployProjectId,
        @RequestParam String ref,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        if (ref == null || ref.isBlank()) {
            return ApiResponse.error("REF_REQUIRED", "파이프라인을 조회하려면 브랜치 또는 태그가 필요합니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error(
                        "NO_GITLAB_ID",
                        "이 프로젝트에는 GitLab 프로젝트가 연결되어 있지 않습니다. 프로젝트 설정에서 먼저 GitLab 프로젝트를 연결해 주세요."
                    );
                }
                try {
                    List<Map<String, Object>> pipelines = gitlabService.getPipelines(token, null, gitlabId, ref.trim());
                    return ApiResponse.ok(pipelines);
                } catch (IllegalArgumentException e) {
                    return ApiResponse.error("GITLAB_AUTH", e.getMessage());
                } catch (Exception e) {
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @GetMapping("/deploy-projects/{deployProjectId}/pipelines/{pipelineId}")
    public ApiResponse<?> getPipelineByDeployProject(
        @PathVariable Long deployProjectId,
        @PathVariable long pipelineId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        return withGitlabProject(deployProjectId, gitlabProjectId -> {
            try {
                return ApiResponse.ok(gitlabService.getPipeline(token, null, gitlabProjectId, pipelineId));
            } catch (IllegalArgumentException e) {
                return ApiResponse.error("GITLAB_AUTH", e.getMessage());
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        });
    }

    @PostMapping("/deploy-projects/{deployProjectId}/pipeline")
    public ApiResponse<?> triggerPipelineByDeployProject(
        @PathVariable Long deployProjectId,
        @Valid @RequestBody GitlabPipelineTriggerForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.DEPLOY_REQUEST);
        if (executionPolicyService.requireMrEvidenceForBuild()) {
            return ApiResponse.error(
                "MR_EVIDENCE_REQUIRED",
                "새 이미지 pipeline은 승인·병합된 MR에서 TEST 배포 요청을 생성해 실행해야 합니다."
            );
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소가 연결되어 있지 않습니다. 프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요.");
                }
                try {
                    Map<String, Object> result = gitlabService.triggerPipeline(
                        token,
                        null,
                        gitlabId,
                        form.getRef(),
                        form.getVariables()
                    );
                    Long pipelineId = asLong(result.get("id"));
                    recordGitlabAudit(currentUser, "GitLab pipeline triggered", deployProjectId, gitlabId, pipelineId, project, AuditResult.SUCCESS, httpRequest);
                    return ApiResponse.ok("GitLab pipeline triggered.", result);
                } catch (IllegalArgumentException e) {
                    recordGitlabAudit(currentUser, "GitLab pipeline trigger failed", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("GITLAB_AUTH", e.getMessage());
                } catch (Exception e) {
                    recordGitlabAudit(currentUser, "GitLab pipeline trigger failed", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @GetMapping("/deploy-projects/{deployProjectId}/merge-requests")
    public ApiResponse<?> listMergeRequestsByDeployProject(
        @PathVariable Long deployProjectId,
        @RequestParam(defaultValue = "opened") String state,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error(
                        "NO_GITLAB_ID",
                        "이 프로젝트에는 GitLab 프로젝트가 연결되어 있지 않습니다. 프로젝트 설정에서 먼저 GitLab 프로젝트를 연결해 주세요."
                    );
                }
                try {
                    List<Map<String, Object>> mergeRequests = new java.util.ArrayList<>();
                    boolean excludedStaleOrigin = false;
                    for (Map<String, Object> mr : gitlabService.getMergeRequests(token, null, gitlabId, state)) {
                        Long iid = asLong(mr.get("iid"));
                        if (iid == null || isOwnedByDifferentProject(project.getId(), gitlabId, iid)) {
                            excludedStaleOrigin = true;
                            continue;
                        }
                        mergeRequests.add(mr);
                    }
                    String message = excludedStaleOrigin
                        ? "다른 DeployOps 프로젝트에서 생성된 과거 MR은 현재 저장소 연결과 달라 제외했습니다. 프로젝트 연결과 MR 이력을 확인해 주세요."
                        : null;
                    return ApiResponse.ok(message, mergeRequests);
                } catch (IllegalArgumentException e) {
                    return ApiResponse.error("GITLAB_AUTH", e.getMessage());
                } catch (Exception e) {
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @PostMapping("/deploy-projects/{deployProjectId}/merge-requests")
    public ApiResponse<?> createMergeRequest(
        @PathVariable Long deployProjectId,
        @Valid @RequestBody GitlabMergeRequestCreateForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error(
                        "NO_GITLAB_ID",
                        "GitLab 저장소가 연결되어 있지 않습니다. 프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요."
                    );
                }
                try {
                    String targetBranch = firstNonBlank(form.getTargetBranch(), project.getMrTargetBranch(), project.getDefaultBranch());
                    Map<String, Object> result = gitlabService.createMergeRequest(
                        token,
                        null,
                        gitlabId,
                        form.getSourceBranch(),
                        targetBranch,
                        form.getTitle(),
                        form.getDescription(),
                        form.getRemoveSourceBranch() != null
                            ? form.getRemoveSourceBranch()
                            : project.isDeleteSourceBranchOnMerge()
                    );
                    Long iid = asLong(result.get("iid"));
                    recordMergeRequestOrigin(deployProjectId, gitlabId, iid, result, currentUser);
                    recordGitlabAudit(currentUser, "GitLab MR created", deployProjectId, gitlabId, iid, project, AuditResult.SUCCESS, httpRequest);
                    officeNotificationService.notifyMergeRequestCreated(project, iid, currentUser.getUser());
                    return ApiResponse.ok("GitLab MR created.", result);
                } catch (IllegalArgumentException e) {
                    recordGitlabAudit(currentUser, "GitLab MR create failed", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("GITLAB_AUTH", e.getMessage());
                } catch (Exception e) {
                    recordGitlabAudit(currentUser, "GitLab MR create failed", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @GetMapping("/deploy-projects/{deployProjectId}/ci-template")
    public ApiResponse<?> previewCiTemplate(
        @PathVariable Long deployProjectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                try {
                    return ApiResponse.ok(Map.of(
                        "filePath", ".gitlab-ci.yml",
                        "content", gitlabCiTemplateService.generate(hydrateCiProject(project))
                    ));
                } catch (IllegalArgumentException e) {
                    return ApiResponse.error("CI_TEMPLATE_INVALID", e.getMessage());
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    /**
     * 저장소 트리 한 단계 — 화면의 경로 탐색기가 폴더를 열 때마다 부른다.
     *
     * <p>빌드 파일·산출물·Dockerfile 경로를 손으로 치던 것을 대신한다. 저장소 구조를
     * 외우지 않아도 되고, 존재하지 않는 경로를 저장해 두었다가 빌드가 깨지는 일도 없어진다.
     */
    @GetMapping("/deploy-projects/{deployProjectId}/tree")
    public ApiResponse<?> browseRepository(
        @PathVariable Long deployProjectId,
        @RequestParam(required = false) String path,
        @RequestParam(required = false) String ref,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 토큰이 설정되어 있지 않습니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error("NO_GITLAB_ID",
                        "이 프로젝트에는 GitLab 프로젝트가 연결되어 있지 않습니다.");
                }
                String branch = firstNonBlank(ref, project.getDefaultBranch(), "main");
                try {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("path", path == null ? "" : path);
                    result.put("ref", branch);
                    result.put("baseCommitSha", gitlabService.getBranchCommitSha(
                        token, null, gitlabId, branch));
                    result.put("entries", gitlabService.listRepositoryTree(
                        token, null, gitlabId, path, branch));
                    return ApiResponse.ok(result);
                } catch (Exception e) {
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    /**
     * 저장소에 실제로 들어 있는 {@code .gitlab-ci.yml} 을 그대로 가져온다.
     *
     * <p>{@code /ci-template} 과 다르다 — 저쪽은 프로젝트 설정으로 <b>만들어 낸</b> 예시고,
     * 여기는 지금 진짜 도는 파일이다. 둘을 같이 보여 줘야 "화면 설정과 실제 CI 가 다르다" 는
     * 상황을 알 수 있다. 지금까지는 그 차이를 볼 방법이 아예 없었다.
     *
     * <p>파일에서 읽어 낸 프로젝트 설정 후보도 함께 준다({@code suggestions}).
     * 이미지 저장소·Dockerfile 경로 같은 값은 CI 파일에 이미 적혀 있는데
     * 사람이 상세 화면에 같은 값을 다시 옮겨 적고 있었다.
     */
    @GetMapping("/deploy-projects/{deployProjectId}/dockerfile")
    public ApiResponse<?> readDockerfile(@PathVariable Long deployProjectId,
        @RequestParam(required = false) String ref, @RequestParam(required = false) Long componentId,
        @AuthenticationPrincipal SecurityUserDetails currentUser) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab 조회 토큰이 설정되어 있지 않습니다.");
        return projectRepository.findById(deployProjectId).map(project -> {
            if (project.getGitlabProjectId() == null) return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소를 먼저 연결해 주세요.");
            try {
                String path = dockerfilePath(buildFileScope(project, componentId));
                String branch = firstNonBlank(ref, project.getDefaultBranch(), "main");
                String sha = requireFullCommitSha(gitlabService.getBranchCommitSha(token, null, project.getGitlabProjectId(), branch));
                String content = gitlabService.readRepositoryFileBounded(token, null, project.getGitlabProjectId(), path, sha, MAX_SOURCE_FILE_BYTES);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("filePath", path); result.put("ref", branch); result.put("baseCommitSha", sha);
                result.put("componentId", componentId); result.put("exists", content != null);
                result.put("content", content == null ? "" : content);
                result.put("contentSha256", content == null ? null : contentSha256(content));
                return ApiResponse.ok(result);
            } catch (Exception e) { return ApiResponse.error("GITLAB_ERROR", simplify(e)); }
        }).orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @PostMapping("/deploy-projects/{deployProjectId}/dockerfile")
    public ApiResponse<?> writeDockerfile(@PathVariable Long deployProjectId, @RequestBody GitlabDockerfileForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser, HttpServletRequest httpRequest) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        String token = resolveToken(currentUser);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 허용된 시스템 토큰을 설정해 주세요.");
        return projectRepository.findById(deployProjectId).map(project -> {
            if (project.getGitlabProjectId() == null) return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소를 먼저 연결해 주세요.");
            Long gitlabId = project.getGitlabProjectId();
            try {
                String path = dockerfilePath(buildFileScope(project, form.getComponentId()));
                if (!path.equals(form.getFilePath())) return ApiResponse.error("INVALID_PATH", "저장된 Dockerfile 경로가 달라졌습니다. 다시 불러오세요.");
                assertEditorContent(form.getContent());
                String target = firstNonBlank(form.getTargetBranch(), project.getMrTargetBranch(), project.getDefaultBranch(), "main");
                String base = requireFullCommitSha(form.getBaseCommitSha());
                assertCurrentBase(token, gitlabId, target, base);
                assertOriginalFile(token, gitlabId, path, base, form.getOriginalSha256());
                String branch = newCiSourceBranch("dockerfile-edit");
                Map<String, Object> commit = gitlabService.commitRepositoryFile(token, null, gitlabId,
                    branch, base, path, form.getContent(), firstNonBlank(form.getCommitMessage(), "Update Dockerfile via Deploy System"));
                assertCurrentBase(token, gitlabId, target, base);
                Map<String, Object> mr = gitlabService.createMergeRequest(token, null, gitlabId, branch, target,
                    firstNonBlank(form.getTitle(), "Update " + path),
                    firstNonBlank(form.getDescription(), "프로젝트 코드 편집기에서 검토한 Dockerfile 변경입니다."), project.isDeleteSourceBranchOnMerge());
                Long iid = asLong(mr.get("iid"));
                recordMergeRequestOrigin(deployProjectId, gitlabId, iid, mr, currentUser);
                recordGitlabAudit(currentUser, "GitLab Dockerfile 수정 MR 생성", deployProjectId, gitlabId, iid, project, AuditResult.SUCCESS, httpRequest);
                officeNotificationService.notifyMergeRequestCreated(project, iid, currentUser.getUser());
                return ApiResponse.ok("Dockerfile 수정 MR을 만들었습니다.", Map.of("commit", commit, "mergeRequest", mr,
                    "sourceBranch", branch, "targetBranch", target, "filePath", path));
            } catch (FileBaselineConflict e) {
                recordGitlabAudit(currentUser, "GitLab Dockerfile 기준 변경", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                return ApiResponse.error(e.code, e.getMessage());
            } catch (Exception e) {
                recordGitlabAudit(currentUser, "GitLab Dockerfile 수정 MR 실패", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        }).orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    private static class FileBaselineConflict extends IllegalArgumentException {
        final String code;
        FileBaselineConflict(String code, String message) { super(message); this.code = code; }
    }

    private void assertCurrentBase(String token, long gitlabId, String target, String base) throws Exception {
        String current = requireFullCommitSha(gitlabService.getBranchCommitSha(token, null, gitlabId, target));
        if (!current.equals(base)) throw new FileBaselineConflict("STALE_BASE", "대상 브랜치가 변경됐습니다. 파일을 다시 불러와 비교한 뒤 MR을 만드세요.");
    }

    private void assertOriginalFile(String token, long gitlabId, String path, String base, String expected) throws Exception {
        String original = gitlabService.readRepositoryFileBounded(token, null, gitlabId, path, base, MAX_SOURCE_FILE_BYTES);
        if (original == null ? expected != null && !expected.isBlank()
                : expected == null || !expected.matches("[0-9a-fA-F]{64}") || !contentSha256(original).equalsIgnoreCase(expected))
            throw new FileBaselineConflict("STALE_FILE", "조회한 파일 원문과 기준이 일치하지 않습니다. 다시 불러온 뒤 수정하세요.");
    }

    private String contentSha256(String content) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void assertEditorContent(String content) {
        if (content == null || content.isBlank() || content.indexOf('\0') >= 0
                || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_FILE_BYTES)
            throw new IllegalArgumentException("파일 내용은 비어 있지 않은 UTF-8 텍스트 256 KiB 이내로 입력하세요.");
    }

    @GetMapping("/deploy-projects/{deployProjectId}/ci-file")
    public ApiResponse<?> readCiFile(
        @PathVariable Long deployProjectId,
        @RequestParam(required = false) String ref,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소가 연결되어 있지 않습니다. 프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요.");
                }
                String branch = firstNonBlank(ref, project.getDefaultBranch(), "main");
                try {
                    String baseCommitSha = requireFullCommitSha(
                        gitlabService.getBranchCommitSha(token, null, gitlabId, branch));
                    String content = gitlabService.readRepositoryFileBounded(
                        token, null, gitlabId, ".gitlab-ci.yml", baseCommitSha, MAX_SOURCE_FILE_BYTES);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("filePath", ".gitlab-ci.yml");
                    result.put("ref", branch);
                    result.put("baseCommitSha", baseCommitSha);
                    result.put("exists", content != null);
                    result.put("content", content == null ? "" : content);
                    result.put("contentSha256", content == null ? null : contentSha256(content));
                    result.put("suggestions", content == null
                        ? Map.of()
                        : gitlabCiInspectService.suggest(content, project));
                    // 아직 파일이 없으면 만들어 줄 예시를 같이 넘긴다 — 빈 편집기보다 낫다.
                    if (content == null) {
                        try {
                            result.put("template", gitlabCiTemplateService.generate(hydrateCiProject(project)));
                        } catch (IllegalArgumentException e) {
                            // 예시를 못 만드는 것은 여기서 실패할 이유가 아니다(설정이 아직 비어 있을 뿐).
                            result.put("templateError", e.getMessage());
                        }
                    }
                    return ApiResponse.ok(result);
                } catch (Exception e) {
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    /**
     * 화면에서 고친 {@code .gitlab-ci.yml} 을 저장소에 반영한다 — <b>MR 로</b>.
     *
     * <p>기본 브랜치에 바로 커밋하지 않는다. CI 파일은 그 저장소의 모든 빌드를 좌우하는데,
     * 배포시스템 화면에서 누른 한 번으로 남의 저장소 기본 브랜치가 바뀌면
     * GitLab 쪽 이력만 봐서는 누가 왜 바꿨는지 알기 어렵다. MR 로 두면 검토가 남는다.
     */
    @PostMapping("/deploy-projects/{deployProjectId}/ci-file")
    public ApiResponse<?> writeCiFile(
        @PathVariable Long deployProjectId,
        @RequestBody GitlabCiFileForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        if (form.getContent() == null || form.getContent().isBlank()) {
            return ApiResponse.error("EMPTY_CONTENT", "CI 파일 내용이 비어 있습니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소가 연결되어 있지 않습니다. 프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요.");
                }
                try {
                    String targetBranch = firstNonBlank(
                        form.getTargetBranch(), project.getMrTargetBranch(), project.getDefaultBranch());
                    String baseCommitSha = requireFullCommitSha(form.getBaseCommitSha());
                    assertCurrentBase(token, gitlabId, targetBranch, baseCommitSha);
                    assertOriginalFile(token, gitlabId, ".gitlab-ci.yml", baseCommitSha, form.getOriginalSha256());
                    assertEditorContent(form.getContent());
                    String sourceBranch = newCiSourceBranch("ci-edit");

                    Map<String, Object> commit = gitlabService.commitRepositoryFile(
                        token, null, gitlabId, sourceBranch, baseCommitSha,
                        ".gitlab-ci.yml", form.getContent(),
                        firstNonBlank(form.getCommitMessage(), "Update .gitlab-ci.yml via Deploy System"));

                    assertCurrentBase(token, gitlabId, targetBranch, baseCommitSha);

                    Map<String, Object> mr = gitlabService.createMergeRequest(
                        token, null, gitlabId, sourceBranch, targetBranch,
                        firstNonBlank(form.getTitle(), "Update .gitlab-ci.yml"),
                        firstNonBlank(form.getDescription(), "Edited in Deploy System project settings."),
                        project.isDeleteSourceBranchOnMerge());

                    Long iid = asLong(mr.get("iid"));
                    recordMergeRequestOrigin(deployProjectId, gitlabId, iid, mr, currentUser);
                    recordGitlabAudit(currentUser, "GitLab CI 파일 수정 MR 생성", deployProjectId, gitlabId,
                        iid, project, AuditResult.SUCCESS, httpRequest);
                    officeNotificationService.notifyMergeRequestCreated(project, iid, currentUser.getUser());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("commit", commit);
                    result.put("mergeRequest", mr);
                    result.put("sourceBranch", sourceBranch);
                    result.put("targetBranch", targetBranch);
                    return ApiResponse.ok("CI 파일 수정 MR 을 만들었습니다.", result);
                } catch (FileBaselineConflict e) {
                    recordGitlabAudit(currentUser, "GitLab CI 파일 기준 변경", deployProjectId, gitlabId,
                        null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error(e.code, e.getMessage());
                } catch (Exception e) {
                    recordGitlabAudit(currentUser, "GitLab CI 파일 수정 MR 실패", deployProjectId, gitlabId,
                        null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @PostMapping("/deploy-projects/{deployProjectId}/ci-template/merge-request")
    public ApiResponse<?> createCiTemplateMergeRequest(
        @PathVariable Long deployProjectId,
        @RequestBody GitlabCiTemplateMrForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소가 연결되어 있지 않습니다. 프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요.");
                }
                try {
                    String targetBranch = firstNonBlank(form.getTargetBranch(), project.getMrTargetBranch(), project.getDefaultBranch());
                    String baseCommitSha = requireFullCommitSha(form.getBaseCommitSha());
                    assertCurrentBase(token, gitlabId, targetBranch, baseCommitSha);
                    String sourceBranch = newCiSourceBranch("ci-template");
                    String content = gitlabCiTemplateService.generate(hydrateCiProject(project));
                    Map<String, Object> commit = gitlabService.commitRepositoryFile(
                        token,
                        null,
                        gitlabId,
                        sourceBranch,
                        baseCommitSha,
                        ".gitlab-ci.yml",
                        content,
                        "Add Deploy System GitLab CI template"
                    );
                    assertCurrentBase(token, gitlabId, targetBranch, baseCommitSha);
                    Map<String, Object> mr = gitlabService.createMergeRequest(
                        token,
                        null,
                        gitlabId,
                        sourceBranch,
                        targetBranch,
                        firstNonBlank(form.getTitle(), "Add Deploy System GitLab CI template"),
                        firstNonBlank(
                            form.getDescription(),
                            "Adds or updates .gitlab-ci.yml generated from Deploy System project settings."
                        ),
                        form.getRemoveSourceBranch() != null
                            ? form.getRemoveSourceBranch()
                            : project.isDeleteSourceBranchOnMerge()
                    );
                    Long iid = asLong(mr.get("iid"));
                    recordMergeRequestOrigin(deployProjectId, gitlabId, iid, mr, currentUser);
                    recordGitlabAudit(currentUser, "GitLab CI template MR created", deployProjectId, gitlabId, iid, project, AuditResult.SUCCESS, httpRequest);
                    officeNotificationService.notifyMergeRequestCreated(project, iid, currentUser.getUser());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("commit", commit);
                    result.put("mergeRequest", mr);
                    result.put("sourceBranch", sourceBranch);
                    result.put("targetBranch", targetBranch);
                    result.put("filePath", ".gitlab-ci.yml");
                    return ApiResponse.ok("GitLab CI template MR created.", result);
                } catch (IllegalArgumentException e) {
                    recordGitlabAudit(currentUser, "GitLab CI template MR create failed", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("CI_TEMPLATE_INVALID", e.getMessage());
                } catch (Exception e) {
                    recordGitlabAudit(currentUser, "GitLab CI template MR create failed", deployProjectId, gitlabId, null, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("GITLAB_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @GetMapping("/deploy-projects/{deployProjectId}/merge-requests/{mergeRequestIid}/commits")
    public ApiResponse<?> listMergeRequestCommits(
        @PathVariable Long deployProjectId,
        @PathVariable long mergeRequestIid,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        return withGitlabProject(deployProjectId, gitlabProjectId -> {
            try {
                return ApiResponse.ok(gitlabService.getMergeRequestCommits(token, null, gitlabProjectId, mergeRequestIid));
            } catch (IllegalArgumentException e) {
                return ApiResponse.error("GITLAB_AUTH", e.getMessage());
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        });
    }

    @GetMapping("/deploy-projects/{deployProjectId}/merge-requests/{mergeRequestIid}/changes")
    public ApiResponse<?> getMergeRequestChanges(
        @PathVariable Long deployProjectId,
        @PathVariable long mergeRequestIid,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_REVIEW);
        return withGitlabProject(deployProjectId, gitlabProjectId -> {
            try {
                Map<String, Object> changes = gitlabService.getMergeRequestChanges(
                    token, null, gitlabProjectId, mergeRequestIid);
                Map<String, Object> mergeRequest = gitlabService.getMergeRequest(
                    token, null, gitlabProjectId, mergeRequestIid);
                changes.put("canMerge", mergeRequest.get("canMerge"));
                changes.put("deployOpsCanMerge", projectPermissionService.hasPermission(
                    currentUser, deployProjectId, ProjectPermissionType.MR_MERGE));
                changes.put("deployOpsCanApprove", projectPermissionService.hasPermission(
                    currentUser, deployProjectId, ProjectPermissionType.MR_APPROVE));
                changes.put("deployOpsCanReject", changes.get("deployOpsCanApprove"));
                boolean approvalStateKnown = false;
                boolean approvedByCurrentUser = false;
                boolean canReconcileMerge = false;
                GitlabService.TokenResolution userToken = gitlabService.resolveAccessTokenInfo(
                    currentUser != null && currentUser.getUser() != null
                        ? currentUser.getUser().getGitlabToken() : null,
                    currentUser != null ? currentUser.getRole() : null,
                    currentUser != null ? currentUser.getUsername() : null);
                if ("USER_PAT".equals(userToken.source()) && userToken.token() != null
                    && currentUser != null && currentUser.getId() != null) {
                    var evidence = mergeRequestEvidenceRepository.findApproved(
                        deployProjectId, gitlabProjectId, mergeRequestIid,
                        String.valueOf(changes.get("sha")));
                    canReconcileMerge = evidence.isPresent()
                        && Boolean.TRUE.equals(changes.get("deployOpsCanMerge"))
                        && "merged".equals(mergeRequest.get("state"))
                        && "merged".equals(changes.get("state"))
                        && changes.get("sha") != null && changes.get("sha").equals(mergeRequest.get("sha"));
                    if (evidence.isPresent()
                        && currentUser.getId().equals(evidence.get().getReviewerId())) {
                        try {
                            GitlabService.GitlabUserIdentity tokenUser =
                                gitlabService.getAuthenticatedUserIdentity(userToken.token(), null);
                            var approverIds = gitlabService.getMergeRequestApproverIds(
                                userToken.token(), null, gitlabProjectId, mergeRequestIid);
                            approvalStateKnown = true;
                            approvedByCurrentUser = evidence.get().getReviewerGitlabUserId() != null
                                && evidence.get().getReviewerGitlabUserId() == tokenUser.id()
                                && approverIds.contains(tokenUser.id());
                        } catch (Exception ignored) {
                            // Keep review available when GitLab approval state is temporarily unavailable.
                        }
                    }
                }
                changes.put("approvalStateKnown", approvalStateKnown);
                changes.put("approvedByCurrentUser", approvedByCurrentUser);
                changes.put("deployOpsCanReconcileMerge", canReconcileMerge);
                return ApiResponse.ok(changes);
            } catch (IllegalArgumentException e) {
                return ApiResponse.error("GITLAB_AUTH", e.getMessage());
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        });
    }

    @PostMapping("/deploy-projects/{deployProjectId}/merge-requests/{mergeRequestIid}/approve")
    public ApiResponse<?> approveMergeRequest(
        @PathVariable Long deployProjectId,
        @PathVariable long mergeRequestIid,
        @Valid @RequestBody GitlabMergeRequestActionForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_APPROVE);
        if (currentUser.getUser().getGitlabUsername() == null
            || currentUser.getUser().getGitlabUsername().isBlank()) {
            return ApiResponse.error("GITLAB_IDENTITY_REQUIRED",
                "내 계정의 GitLab 사용자명 연결이 필요합니다. 내 계정에서 PAT 변경으로 본인 계정을 연결한 뒤 다시 승인해 주세요. 관리자가 연결했다면 다시 로그인해 주세요.");
        }
        GitlabService.TokenResolution approvalToken = gitlabService.resolveAccessTokenInfo(
            currentUser.getUser().getGitlabToken(), currentUser.getRole(), currentUser.getUsername());
        if (!"USER_PAT".equals(approvalToken.source()) || approvalToken.token() == null) {
            return ApiResponse.error("PERSONAL_PAT_REQUIRED", "MR 승인에는 본인 GitLab 개인 토큰이 필요합니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabProjectId = project.getGitlabProjectId();
                if (gitlabProjectId == null) {
                    return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소가 연결되어 있지 않습니다. 프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요.");
                }
                String targetId = gitlabProjectId + "!" + mergeRequestIid;
                String description = "deployProjectId=" + deployProjectId + ", reviewedSha=" + form.reviewedSha();
                auditLogService.record(currentUser.getName(), "GitLab MR approval requested", "GitLabMergeRequest",
                    targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
                try {
                    Map<String, Object> result = mergeRequestGovernanceService.approve(
                        project, mergeRequestIid, form.reviewedSha(), approvalToken.token(),
                        approvalToken.source(), form.reviewedTargetBranch(),
                        form.reviewedDiffBaseSha(), form.reviewedDiffStartSha(), currentUser);
                    auditLogService.record(currentUser.getName(), "GitLab MR approved", "GitLabMergeRequest",
                        targetId, description, AuditResult.SUCCESS, httpRequest.getRemoteAddr());
                    officeNotificationService.notifyMergeRequestApproved(project, mergeRequestIid, currentUser.getUser());
                    String message = Boolean.TRUE.equals(result.get("alreadyApproved"))
                        ? "GitLab에 이미 승인된 MR입니다. 검토한 변경 내용의 DeployOps 승인 증거를 기록했습니다."
                        : "GitLab MR 승인이 완료되었습니다.";
                    return ApiResponse.ok(message, result);
                } catch (GitlabService.SharedApprovalActorException e) {
                    auditLogService.record(currentUser.getName(), "GitLab MR approval blocked", "GitLabMergeRequest",
                        targetId, description, AuditResult.FAIL, httpRequest.getRemoteAddr());
                    return ApiResponse.error("MR_SHARED_APPROVAL_ACTOR", e.getMessage());
                } catch (IllegalArgumentException e) {
                    auditLogService.record(currentUser.getName(), "GitLab MR approval not confirmed", "GitLabMergeRequest",
                        targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
                    return ApiResponse.error("GITLAB_AUTH", "MR 작업 완료를 확인하지 못했습니다. 상태를 새로고침해 주세요.");
                } catch (Exception e) {
                    auditLogService.record(currentUser.getName(), "GitLab MR approval not confirmed", "GitLabMergeRequest",
                        targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
                    return ApiResponse.error("GITLAB_ERROR", "MR 작업 완료를 확인하지 못했습니다. GitLab 변경이 남았을 수 있으므로 상태를 새로고침해 주세요.");
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @PostMapping("/deploy-projects/{deployProjectId}/merge-requests/{mergeRequestIid}/reject")
    public ApiResponse<?> rejectMergeRequest(
        @PathVariable Long deployProjectId, @PathVariable long mergeRequestIid,
        @Valid @RequestBody GitlabMergeRequestRejectForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser, HttpServletRequest httpRequest
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_APPROVE);
        GitlabService.TokenResolution token = gitlabService.resolveAccessTokenInfo(
            currentUser.getUser().getGitlabToken(), currentUser.getRole(), currentUser.getUsername());
        if (!"USER_PAT".equals(token.source()) || token.token() == null || token.token().isBlank()) {
            return ApiResponse.error("PERSONAL_PAT_REQUIRED", "MR 반려에는 본인 GitLab 개인 토큰이 필요합니다.");
        }
        Project project = projectRepository.findById(deployProjectId).orElse(null);
        if (project == null) return ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다.");
        String targetId = project.getGitlabProjectId() + "!" + mergeRequestIid;
        String description = "deployProjectId=" + deployProjectId + ", reviewedSha=" + form.reviewedSha()
            + ", tokenSource=USER_PAT, reason=" + form.reason();
        // Persist intent before external side effects; a note/close cannot be rolled back locally.
        auditLogService.record(currentUser.getName(), "GitLab MR rejection requested", "GitLabMergeRequest",
            targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
        try {
            Map<String, Object> result = mergeRequestGovernanceService.reject(project, mergeRequestIid,
                form.reviewedSha(), token.token(), token.source(), form.reviewedTargetBranch(),
                form.reviewedDiffBaseSha(), form.reviewedDiffStartSha(), form.reason(), currentUser);
            auditLogService.record(currentUser.getName(), "GitLab MR rejected", "GitLabMergeRequest",
                targetId, description, AuditResult.SUCCESS, httpRequest.getRemoteAddr());
            return ApiResponse.ok("MR을 반려하고 GitLab에서 닫았습니다. 병합 또는 배포는 실행되지 않습니다.", result);
        } catch (GitlabService.SharedApprovalActorException e) {
            auditLogService.record(currentUser.getName(), "GitLab MR rejection blocked", "GitLabMergeRequest",
                targetId, description, AuditResult.FAIL, httpRequest.getRemoteAddr());
            return ApiResponse.error("MR_SHARED_APPROVAL_ACTOR", e.getMessage());
        } catch (Exception e) {
            auditLogService.record(currentUser.getName(), "GitLab MR rejection not confirmed", "GitLabMergeRequest",
                targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
            return ApiResponse.error("MR_REJECTION_NOT_CONFIRMED", "반려 완료를 확인하지 못했습니다. GitLab에 사유가 남거나 MR이 닫혔을 수 있으므로 상태를 새로고침해 주세요. 기존 승인 기록이 철회됐을 수 있어 계속 진행하려면 다시 검토·승인해야 합니다.");
        }
    }

    @PostMapping("/deploy-projects/{deployProjectId}/merge-requests/{mergeRequestIid}/merge")
    public ApiResponse<?> mergeMergeRequest(
        @PathVariable Long deployProjectId,
        @PathVariable long mergeRequestIid,
        @Valid @RequestBody GitlabMergeRequestActionForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        String token = resolveToken(currentUser);
        if (token == null) {
            return ApiResponse.error("NO_TOKEN", "GitLab 사용자 토큰 또는 시스템 토큰이 설정되어 있지 않습니다.");
        }
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.MR_MERGE);
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error(
                        "NO_GITLAB_ID",
                        "이 프로젝트에는 GitLab 프로젝트가 연결되어 있지 않습니다. 프로젝트 설정에서 먼저 GitLab 프로젝트를 연결해 주세요."
                    );
                }
                String targetId = gitlabId + "!" + mergeRequestIid;
                String description = "deployProjectId=" + deployProjectId + ", reviewedSha=" + form.reviewedSha();
                auditLogService.record(currentUser.getName(), "GitLab MR merge requested", "GitLabMergeRequest",
                    targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
                try {
                    Map<String, Object> result = mergeRequestGovernanceService.merge(
                        project, mergeRequestIid, form.reviewedSha(), token,
                        form.reviewedTargetBranch(), form.reviewedDiffBaseSha(),
                        form.reviewedDiffStartSha(), currentUser);
                    auditLogService.record(currentUser.getName(), "GitLab MR merged", "GitLabMergeRequest",
                        targetId, description, AuditResult.SUCCESS, httpRequest.getRemoteAddr());
                    officeNotificationService.notifyMergeRequestMerged(project, mergeRequestIid, currentUser.getUser());
                    return ApiResponse.ok("GitLab MR merge가 요청되었습니다.", result);
                } catch (MergeRequestGovernanceService.MergePreflightRejectedException e) {
                    auditLogService.record(currentUser.getName(), "GitLab MR merge rejected", "GitLabMergeRequest",
                        targetId, description + ", reason=" + e.getReason(), AuditResult.FAIL, httpRequest.getRemoteAddr());
                    throw e;
                } catch (IllegalArgumentException e) {
                    auditLogService.record(currentUser.getName(), "GitLab MR merge not confirmed", "GitLabMergeRequest",
                        targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
                    return ApiResponse.error("GITLAB_AUTH", "MR 작업 완료를 확인하지 못했습니다. 상태를 새로고침해 주세요.");
                } catch (Exception e) {
                    auditLogService.record(currentUser.getName(), "GitLab MR merge not confirmed", "GitLabMergeRequest",
                        targetId, description, AuditResult.UNKNOWN, httpRequest.getRemoteAddr());
                    return ApiResponse.error("GITLAB_ERROR", "MR 작업 완료를 확인하지 못했습니다. GitLab 변경이 남았을 수 있으므로 상태를 새로고침해 주세요.");
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    private ApiResponse<?> withGitlabProject(Long deployProjectId, GitlabProjectCallback callback) {
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                Long gitlabId = project.getGitlabProjectId();
                if (gitlabId == null) {
                    return ApiResponse.error(
                        "NO_GITLAB_ID",
                        "이 프로젝트에는 GitLab 프로젝트가 연결되어 있지 않습니다. 프로젝트 설정에서 먼저 GitLab 프로젝트를 연결해 주세요."
                    );
                }
                return callback.call(gitlabId);
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    private void recordGitlabAudit(
        SecurityUserDetails currentUser,
        String action,
        Long deployProjectId,
        Long gitlabProjectId,
        Long mergeRequestIid,
        Project project,
        AuditResult result,
        HttpServletRequest httpRequest
    ) {
        String actor = currentUser != null ? currentUser.getName() : "anonymous";
        boolean pipelineAction = action != null && action.contains("pipeline");
        String targetId = gitlabProjectId + (mergeRequestIid != null ? (pipelineAction ? "#" : "!") + mergeRequestIid : "");
        String targetDesc = "deployProjectId=" + deployProjectId
            + ", project=" + (project != null ? project.getName() : "-")
            + ", gitlabProjectId=" + gitlabProjectId
            + (mergeRequestIid != null ? (pipelineAction ? ", pipeline=#" : ", mr=!") + mergeRequestIid : "")
            // 2단계 인가 근거 기록: 행위자 역할 + GitLab 토큰 출처
            + ", actorRole=" + (currentUser != null ? currentUser.getRole() : "-")
            + ", tokenSource=" + tokenSourceFor(currentUser);
        auditLogService.record(
            actor,
            action,
            pipelineAction ? "GitLabPipeline" : "GitLabMergeRequest",
            targetId,
            targetDesc,
            result,
            httpRequest != null ? httpRequest.getRemoteAddr() : null
        );
    }

    @GetMapping("/deploy-projects/{deployProjectId}/ci-readiness")
    public ApiResponse<?> ciReadiness(
        @PathVariable Long deployProjectId,
        @RequestParam(required = false) String ref,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = gitlabService.resolveReadonlyCiAccessToken(null);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab token is not configured.");
        return projectRepository.findById(deployProjectId).map(project -> {
            String targetRef = firstNonBlank(ref, project.getDefaultBranch(), "main");
            try {
                hydrateCiProject(project);
                return ApiResponse.ok(executionPolicyService.requireMrEvidenceForBuild()
                    ? gitlabCiReadinessService.evaluateForMergeRequestBuild(token, null, project, targetRef)
                    : gitlabCiReadinessService.evaluate(token, null, project, targetRef));
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        }).orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    @GetMapping("/deploy-projects/{deployProjectId}/pipelines/{pipelineId}/jobs")
    public ApiResponse<?> pipelineJobs(
        @PathVariable Long deployProjectId, @PathVariable long pipelineId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab token is not configured.");
        return withGitlabProject(deployProjectId, gitlabProjectId -> {
            try {
                gitlabService.getPipeline(token, null, gitlabProjectId, pipelineId);
                return ApiResponse.ok(gitlabService.getPipelineJobs(token, null, gitlabProjectId, pipelineId));
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        });
    }

    @GetMapping("/deploy-projects/{deployProjectId}/pipelines/{pipelineId}/jobs/{jobId}/trace")
    public ApiResponse<?> jobTrace(
        @PathVariable Long deployProjectId, @PathVariable long pipelineId, @PathVariable long jobId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        String token = resolveReadonlyCiToken(currentUser);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab token is not configured.");
        return withGitlabProject(deployProjectId, gitlabProjectId -> {
            try {
                boolean belongs = gitlabService.getPipelineJobs(token, null, gitlabProjectId, pipelineId).stream()
                    .anyMatch(job -> java.util.Objects.equals(asLong(job.get("id")), jobId));
                if (!belongs) return ApiResponse.error("JOB_NOT_FOUND", "이 pipeline에 속한 job이 아닙니다.");
                String trace = secretMaskingService.mask(
                    gitlabService.getJobTrace(token, null, gitlabProjectId, jobId));
                boolean truncated = trace != null && trace.length() > MAX_JOB_TRACE_CHARS;
                if (truncated) trace = trace.substring(trace.length() - MAX_JOB_TRACE_CHARS);
                return ApiResponse.ok(Map.of("trace", trace == null ? "" : trace, "truncated", truncated));
            } catch (Exception e) {
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        });
    }

    @PostMapping("/deploy-projects/{deployProjectId}/pipelines/{pipelineId}/{action:retry|cancel}")
    public ApiResponse<?> operatePipeline(
        @PathVariable Long deployProjectId, @PathVariable long pipelineId, @PathVariable String action,
        @AuthenticationPrincipal SecurityUserDetails currentUser, HttpServletRequest httpRequest
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.DEPLOY_REQUEST);
        String token = resolveToken(currentUser);
        if (token == null) return ApiResponse.error("NO_TOKEN", "GitLab token is not configured.");
        if ("retry".equals(action) && executionPolicyService.requireMrEvidenceForBuild()) {
            return ApiResponse.error("PIPELINE_RETRY_GOVERNED",
                "MR 증거 필수 정책에서는 기존 pipeline 재시도를 허용하지 않습니다. 승인된 MR에서 새 배포 요청을 생성하세요.");
        }
        List<com.portfolio.deployops.domain.DeployStatus> inFlight = java.util.Arrays.stream(
                com.portfolio.deployops.domain.DeployStatus.values())
            .filter(com.portfolio.deployops.domain.DeployStatus::isInFlight).toList();
        if (deployRequestRepository.countByStatusInForProjectIds(inFlight, List.of(deployProjectId)) > 0) {
            return ApiResponse.error("DEPLOY_PIPELINE_LOCKED",
                "이 프로젝트에 진행 중인 배포 요청이 있어 pipeline을 재시도·취소할 수 없습니다.");
        }
        if (deployRequestRepository.countByProjectAndPipelineId(deployProjectId, pipelineId) > 0) {
            return ApiResponse.error("DEPLOY_PIPELINE_LOCKED",
                "배포 요청에 연결된 pipeline은 여기서 재시도·취소할 수 없습니다. 새 배포 요청으로 실행해 주세요.");
        }
        return projectRepository.findById(deployProjectId).map(project -> {
            Long gitlabId = project.getGitlabProjectId();
            if (gitlabId == null) return ApiResponse.error("NO_GITLAB_ID", "GitLab 저장소가 연결되어 있지 않습니다.");
            try {
                Map<String, Object> before = gitlabService.getPipeline(token, null, gitlabId, pipelineId);
                String pipelineStatus = String.valueOf(before.getOrDefault("status", "")).toLowerCase(java.util.Locale.ROOT);
                if ("retry".equals(action)) {
                    if (!"failed".equals(pipelineStatus)) {
                        recordGitlabAudit(currentUser, "GitLab pipeline retry rejected", deployProjectId, gitlabId,
                            pipelineId, project, AuditResult.FAIL, httpRequest);
                        return ApiResponse.error("PIPELINE_STATE_INVALID", "실패 상태인 pipeline만 재시도할 수 있습니다.");
                    }
                    var readiness = gitlabCiReadinessService.evaluate(token, null, hydrateCiProject(project),
                        firstNonBlank(before.get("ref") instanceof String ref ? ref : null,
                            project.getDefaultBranch(), "main"));
                    if (!readiness.isReady()) {
                        String blockers = readiness.getBlockers() == null ? "" : String.join(" · ", readiness.getBlockers());
                        recordGitlabAudit(currentUser, "GitLab pipeline retry blocked by CI readiness",
                            deployProjectId, gitlabId, pipelineId, project, AuditResult.FAIL, httpRequest);
                        return ApiResponse.error("CI_NOT_READY", "CI 사전점검을 통과해야 재시도할 수 있습니다. " + blockers);
                    }
                } else if (!java.util.Set.of("created", "waiting_for_resource", "preparing", "pending", "running")
                    .contains(pipelineStatus)) {
                    recordGitlabAudit(currentUser, "GitLab pipeline cancel rejected", deployProjectId, gitlabId,
                        pipelineId, project, AuditResult.FAIL, httpRequest);
                    return ApiResponse.error("PIPELINE_STATE_INVALID", "실행 중인 pipeline만 취소할 수 있습니다.");
                }
                Map<String, Object> result = "retry".equals(action)
                    ? gitlabService.retryPipeline(token, null, gitlabId, pipelineId)
                    : gitlabService.cancelPipeline(token, null, gitlabId, pipelineId);
                recordGitlabAudit(currentUser, "GitLab pipeline " + action, deployProjectId, gitlabId,
                    pipelineId, project, AuditResult.SUCCESS, httpRequest);
                Map<String, Object> response = new LinkedHashMap<>(result);
                response.put("previousStatus", before.get("status"));
                return ApiResponse.ok("GitLab pipeline " + action + " 요청이 완료되었습니다.", response);
            } catch (Exception e) {
                recordGitlabAudit(currentUser, "GitLab pipeline " + action + " failed", deployProjectId,
                    gitlabId, pipelineId, project, AuditResult.FAIL, httpRequest);
                return ApiResponse.error("GITLAB_ERROR", simplify(e));
            }
        }).orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    private void recordMergeRequestOrigin(
        Long deployProjectId,
        Long gitlabProjectId,
        Long iid,
        Map<String, Object> mergeRequest,
        SecurityUserDetails currentUser
    ) {
        if (iid == null || currentUser == null || currentUser.getId() == null) {
            throw new IllegalStateException("생성된 MR의 IID 또는 실제 요청자를 확인할 수 없습니다.");
        }
        Object authorValue = mergeRequest != null ? mergeRequest.get("author") : null;
        String gitlabAuthor = authorValue instanceof Map<?, ?> author && author.get("username") != null
            ? String.valueOf(author.get("username")) : null;
        mergeRequestOriginRepository.saveIfAbsent(
            deployProjectId, gitlabProjectId, iid, currentUser.getId(), gitlabAuthor);
    }

    private boolean isOwnedByDifferentProject(Long deployProjectId, Long gitlabProjectId, Long mergeRequestIid) {
        List<Long> owners = mergeRequestOriginRepository
            .findProjectIdsByGitlabProjectIdAndMergeRequestIid(gitlabProjectId, mergeRequestIid);
        return !owners.isEmpty()
            && (owners.size() != 1 || !deployProjectId.equals(owners.getFirst()));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "main";
    }

    private String newCiSourceBranch(String purpose) {
        return "deploy-system/" + purpose + "-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private String requireFullCommitSha(String value) {
        String sha = value == null ? null : value.trim();
        if (sha == null || !sha.matches("(?i)^[0-9a-f]{40,64}$")) {
            throw new IllegalArgumentException("CI 파일을 읽은 기준 commit SHA가 없습니다. 다시 불러온 뒤 MR을 만드세요.");
        }
        return sha.toLowerCase(java.util.Locale.ROOT);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveReadonlyCiToken(SecurityUserDetails currentUser) {
        String personal = currentUser != null && currentUser.getUser() != null
            ? currentUser.getUser().getGitlabToken() : null;
        return gitlabService.resolveReadonlyCiAccessToken(personal);
    }

    private String resolveToken(SecurityUserDetails currentUser) {
        String userToken = currentUser != null && currentUser.getUser() != null
            ? currentUser.getUser().getGitlabToken()
            : null;
        String role = currentUser != null ? currentUser.getRole() : null;
        String loginId = currentUser != null ? currentUser.getUsername() : null;
        return gitlabService.resolveAccessToken(userToken, role, loginId);
    }

    /** 감사용 — 이 사용자가 GitLab 호출 시 쓰게 될 토큰 출처(USER_PAT/SYSTEM_BOT/NONE). */
    private String tokenSourceFor(SecurityUserDetails currentUser) {
        if (currentUser == null) {
            return "-";
        }
        String userToken = currentUser.getUser() != null ? currentUser.getUser().getGitlabToken() : null;
        return gitlabService.resolveAccessTokenInfo(userToken, currentUser.getRole(), currentUser.getUsername()).source();
    }

    private String simplify(Exception e) {
        String msg = e.getMessage();
        if (e.getClass().getSimpleName().contains("ConnectException")) {
            return "GitLab 연결이 거부되었습니다. 서버 상태와 포트를 확인해 주세요.";
        }
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        if (msg.contains("Connection refused")) {
            return "연결이 거부되었습니다. GitLab 서버 상태를 확인해 주세요.";
        }
        if (msg.contains("UnknownHost") || msg.contains("nodename")) {
            return "호스트를 찾을 수 없습니다.";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "연결 시간이 초과되었습니다.";
        }
        return msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }

    @FunctionalInterface
    private interface GitlabProjectCallback {
        ApiResponse<?> call(long gitlabProjectId);
    }
}
