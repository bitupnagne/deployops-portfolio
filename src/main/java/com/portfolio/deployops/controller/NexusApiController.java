package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.dto.ApiResponse;
import java.util.LinkedHashMap;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.ImageAvailabilityService;
import com.portfolio.deployops.service.NexusMetadataService;
import com.portfolio.deployops.service.NexusCleanupService;
import com.portfolio.deployops.service.ProjectPermissionService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/nexus")
@RequiredArgsConstructor
public class NexusApiController {

    private final ProjectRepository projectRepository;
    private final NexusMetadataService nexusMetadataService;
    private final ProjectPermissionService projectPermissionService;
    private final AuditLogService auditLogService;
    private final ImageAvailabilityService imageAvailabilityService;
    private final GitlabService gitlabService;
    private final NexusCleanupService nexusCleanupService;

    @GetMapping("/cleanup-requests")
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> cleanupRequests() {
        return ApiResponse.ok(nexusCleanupService.findRecent().stream().map(this::safeCleanup).toList());
    }

    @PostMapping("/cleanup-requests")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> requestCleanup(@RequestBody Map<String, Object> body,
                                         @AuthenticationPrincipal SecurityUserDetails currentUser) {
        Long projectId = number(body.get("projectId"), "프로젝트 ID가 필요합니다.");
        String tag = body.get("tag") == null ? null : String.valueOf(body.get("tag"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        var request = nexusCleanupService.create(projectId, tag, reason, currentUser.getUser().getId());
        auditLogService.recordCurrentRequired("Nexus 이미지 정리 요청", "NexusCleanup", String.valueOf(request.getId()),
            request.getRepositoryPath() + ":" + request.getImageTag(), AuditResult.SUCCESS);
        return ApiResponse.ok("Nexus 이미지 정리 승인을 요청했습니다.", safeCleanup(request));
    }

    @PostMapping("/cleanup-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> approveCleanup(@PathVariable Long id,
                                         @AuthenticationPrincipal SecurityUserDetails currentUser) {
        var request = nexusCleanupService.approveAndDelete(id, currentUser.getUser().getId());
        boolean completed = request.getStatus() == com.portfolio.deployops.domain.NexusCleanupStatus.COMPLETED;
        auditLogService.recordCurrent(completed ? "Nexus 이미지 정리 승인·삭제" : "Nexus 이미지 정리 결과 확인 필요",
            "NexusCleanup", String.valueOf(id), request.getImageDigest(),
            completed ? AuditResult.SUCCESS : AuditResult.FAIL);
        return completed
            ? ApiResponse.ok("승인된 Nexus 이미지를 삭제했습니다.", safeCleanup(request))
            : ApiResponse.error("NEXUS_CLEANUP_" + request.getStatus().name(), request.getResultMessage());
    }

    @PostMapping("/cleanup-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> rejectCleanup(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                        @AuthenticationPrincipal SecurityUserDetails currentUser) {
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        nexusCleanupService.reject(id, currentUser.getUser().getId(), reason);
        auditLogService.recordCurrentRequired("Nexus 이미지 정리 반려", "NexusCleanup", String.valueOf(id), reason,
            AuditResult.SUCCESS);
        return ApiResponse.ok("Nexus 이미지 정리 요청을 반려했습니다.");
    }

    @GetMapping("/deploy-projects/{deployProjectId}/docker-tags")
    public ApiResponse<?> listDockerTags(
        @PathVariable Long deployProjectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                try {
                    List<Map<String, Object>> tags = nexusMetadataService.getDockerTags(project);
                    attachCommitTitles(tags, project, currentUser);
                    return ApiResponse.ok(tags);
                } catch (IllegalArgumentException e) {
                    return ApiResponse.error("NEXUS_CONFIG", e.getMessage());
                } catch (Exception e) {
                    return ApiResponse.error("NEXUS_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    /**
     * 이미지 태그({@code ${branch}-${shortSha}-${pipelineId}})에서 shortSha 를 추출해
     * 빌드 당시의 GitLab 커밋 제목을 best-effort 로 부착한다(토큰/GitLab 연동 없으면 생략).
     */
    private void attachCommitTitles(
        List<Map<String, Object>> tags,
        com.portfolio.deployops.domain.Project project,
        SecurityUserDetails currentUser
    ) {
        if (tags == null || tags.isEmpty() || project.getGitlabProjectId() == null) {
            return;
        }
        String userToken = currentUser != null && currentUser.getUser() != null
            ? currentUser.getUser().getGitlabToken()
            : null;
        String role = currentUser != null ? currentUser.getRole() : null;
        String token = gitlabService.resolveAccessToken(userToken, role);
        if (token == null) {
            return;
        }
        for (Map<String, Object> entry : tags) {
            String shortSha = parseShortSha((String) entry.get("tag"));
            if (shortSha == null) {
                continue;
            }
            Map<String, Object> commit = gitlabService.getCommit(token, null, project.getGitlabProjectId(), shortSha);
            Object title = commit.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                entry.put("commitTitle", title);
                entry.put("commitShortId", commit.getOrDefault("shortId", shortSha));
            }
        }
    }

    /** 태그에서 shortSha 세그먼트(끝에서 두 번째, 7~40자리 hex) 추출. 형식 불일치 시 null. */
    private String parseShortSha(String tag) {
        if (tag == null) {
            return null;
        }
        String[] parts = tag.split("-");
        if (parts.length < 3) {
            return null;
        }
        String candidate = parts[parts.length - 2];
        return candidate.matches("[0-9a-fA-F]{7,40}") ? candidate : null;
    }

    @GetMapping("/deploy-projects/{deployProjectId}/docker-manifest")
    public ApiResponse<?> resolveDockerManifest(
        @PathVariable Long deployProjectId,
        @RequestParam String tag,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        if (tag == null || tag.isBlank()) {
            return ApiResponse.error("TAG_REQUIRED", "조회할 이미지 태그가 필요합니다.");
        }
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                try {
                    Map<String, Object> manifest = nexusMetadataService.resolveManifest(project, tag.trim());
                    return ApiResponse.ok(manifest);
                } catch (IllegalArgumentException e) {
                    return ApiResponse.error("NEXUS_CONFIG", e.getMessage());
                } catch (Exception e) {
                    return ApiResponse.error("NEXUS_ERROR", simplify(e));
                }
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    /**
     * 산출물 저장소 용량. 대시보드/통합설정의 용량 카드가 호출한다.
     *
     * <p>3망 구조에서 Nexus 는 배포망 한 대뿐이고 배포시스템도 같은 망에 있으므로 직접 조회한다.
     *
     * <p>접근 통제는 SecurityConfig 에서 OPERATOR/SUPER_ADMIN 으로 건다(전역 인프라 정보라
     * 프로젝트 단위 권한 검사 대상이 아니다).
     */
    @GetMapping("/storage")
    public ApiResponse<?> storageStatus() {
        try {
            return ApiResponse.ok(nexusMetadataService.getStorageStatus());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NEXUS_CONFIG", e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("NEXUS_ERROR", simplify(e));
        }
    }

    /**
     * 배포할 이미지가 왜 하나도 없는지 알아봐 준다.
     *
     * <p>목록이 비었을 때만 화면이 부른다. "없습니다" 한 줄로는 빌드가 도는 중인지,
     * 깨졌는지, 애초에 이미지를 안 만드는 프로젝트인지 구분할 수 없어
     * 파이프라인을 볼 줄 모르는 사람은 여기서 멈춘다.
     */
    @GetMapping("/deploy-projects/{deployProjectId}/image-status")
    public ApiResponse<?> imageStatus(
        @PathVariable Long deployProjectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, deployProjectId, ProjectPermissionType.PROJECT_VIEW);
        return projectRepository.findById(deployProjectId)
            .map(project -> {
                String userToken = currentUser != null && currentUser.getUser() != null
                    ? currentUser.getUser().getGitlabToken() : null;
                String role = currentUser != null ? currentUser.getRole() : null;
                String token = gitlabService.resolveAccessToken(userToken, role);
                return ApiResponse.ok(imageAvailabilityService.diagnose(project, token));
            })
            .orElse(ApiResponse.error("NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    /**
     * Nexus 에 만들어져 있는 저장소 목록. 화면이 손입력 대신 고르게 하려고 부른다.
     *
     * <p>레지스트리 주소({@code host:port})도 함께 준다 — 이미지 저장소 값은
     * 저장소 이름만으로는 완성되지 않고 그 주소가 앞에 붙어야 한다.
     */
    @GetMapping("/repositories")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> repositories(@RequestParam(required = false) String format) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("registry", nexusMetadataService.registryHostPort());
            out.put("repositories", nexusMetadataService.listRepositories(format));
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NEXUS_CONFIG", e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("NEXUS_ERROR", simplify(e));
        }
    }

    /**
     * 저장소를 새로 만든다.
     *
     * <p>없는 저장소를 만나면 Nexus 콘솔에 따로 들어가라고 하지 않는다 —
     * 배포 준비를 배포시스템 밖에서 하게 두면 그 절차는 문서로만 남고 재현되지 않는다.
     */
    @PostMapping("/repositories")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> createRepository(@RequestBody Map<String, Object> body) {
        String format = String.valueOf(body.getOrDefault("format", "docker"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
        try {
            Integer port = null;
            if (body.get("dockerHttpPort") != null) {
                if (!(body.get("dockerHttpPort") instanceof Number n) || n.doubleValue() != n.intValue()) {
                    throw new IllegalArgumentException("Docker 커넥터 포트는 정수여야 합니다.");
                }
                port = n.intValue();
            }
            String versionPolicy = body.get("versionPolicy") == null ? "RELEASE" : String.valueOf(body.get("versionPolicy"));
            Map<String, Object> created = nexusMetadataService.createHostedRepository(format, name, port, versionPolicy);
            auditLogService.recordCurrent("Nexus 저장소 생성", "NexusRepository", name,
                format + (port != null ? " :" + port : " " + versionPolicy), AuditResult.SUCCESS);
            String message = created.containsKey("unavailableReason")
                ? "저장소는 생성됐지만 아직 이미지 주소로 선택할 수 없습니다. " + created.get("unavailableReason")
                : "저장소를 만들었습니다.";
            return ApiResponse.ok(message, created);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NEXUS_CONFIG", e.getMessage());
        } catch (Exception e) {
            auditLogService.recordCurrent("Nexus 저장소 생성 실패", "NexusRepository", name,
                "-", AuditResult.FAIL);
            return ApiResponse.error("NEXUS_ERROR", simplify(e));
        }
    }

    private String simplify(Exception e) {
        String msg = e.getMessage();
        if (e.getClass().getSimpleName().contains("ConnectException")) {
            return "Nexus 연결이 거부되었습니다. 서버 상태와 포트를 확인해 주세요.";
        }
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        if (msg.contains("Connection refused")) {
            return "연결이 거부되었습니다. Nexus 서버 상태를 확인해 주세요.";
        }
        if (msg.contains("UnknownHost") || msg.contains("nodename")) {
            return "호스트를 찾을 수 없습니다.";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "연결 시간이 초과되었습니다.";
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    private Map<String, Object> safeCleanup(com.portfolio.deployops.domain.NexusCleanupRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", request.getId());
        out.put("projectId", request.getProject().getId());
        out.put("projectName", request.getProject().getName());
        out.put("status", request.getStatus().name());
        out.put("statusLabel", request.getStatus().getLabel());
        out.put("repositoryPath", request.getRepositoryPath());
        out.put("imageRepository", request.getImageRepository());
        out.put("tag", request.getImageTag());
        out.put("digest", request.getImageDigest());
        out.put("reason", request.getReason());
        out.put("resultMessage", request.getResultMessage());
        out.put("requestedById", request.getRequestedBy().getId());
        out.put("requestedBy", request.getRequestedBy().getName());
        out.put("approvedBy", request.getApprovedBy() == null ? null : request.getApprovedBy().getName());
        out.put("createdAt", request.getCreatedAt());
        return out;
    }

    private Long number(Object value, String message) {
        if (value instanceof Number n && n.longValue() > 0) return n.longValue();
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // 아래의 일관된 입력 오류로 처리한다.
            }
        }
        throw new IllegalArgumentException(message);
    }
}
