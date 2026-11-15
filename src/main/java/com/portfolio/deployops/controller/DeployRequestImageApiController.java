package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.NexusMetadataService;
import com.portfolio.deployops.service.ProjectPermissionService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Request-only readers see metadata from the project's fixed image repository, not Nexus operations. */
@RestController
@RequestMapping("/api/v1/nexus/request-projects/{projectId}")
@RequiredArgsConstructor
public class DeployRequestImageApiController {
    private final ProjectRepository projects;
    private final ProjectPermissionService permissions;
    private final NexusMetadataService nexus;

    @GetMapping("/docker-tags")
    public ResponseEntity<ApiResponse<?>> tags(@PathVariable Long projectId,
        @RequestParam DeployEnvironment environment,
        @AuthenticationPrincipal SecurityUserDetails actor) {
        Project project = authorizedProject(projectId, environment, actor);
        try {
            var summaries = nexus.getDockerTags(project).stream().map(row -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("tag", row.get("tag"));
                summary.put("createdAt", row.get("createdAt"));
                return summary;
            }).toList();
            return response(ApiResponse.ok(summaries));
        } catch (Exception e) {
            return unavailable();
        }
    }

    @GetMapping("/docker-manifest")
    public ResponseEntity<ApiResponse<?>> manifest(@PathVariable Long projectId,
        @RequestParam DeployEnvironment environment, @RequestParam String tag,
        @AuthenticationPrincipal SecurityUserDetails actor) {
        Project project = authorizedProject(projectId, environment, actor);
        if (tag == null || !tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 이미지 태그를 지정해 주세요.");
        }
        try {
            var manifest = nexus.resolveManifest(project, tag);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tag", tag);
            summary.put("digest", manifest.get("digest"));
            return response(ApiResponse.ok(summary));
        } catch (Exception e) {
            return unavailable();
        }
    }

    @GetMapping("/image-status")
    public ResponseEntity<ApiResponse<?>> status(@PathVariable Long projectId,
        @RequestParam DeployEnvironment environment,
        @AuthenticationPrincipal SecurityUserDetails actor) {
        Project project = authorizedProject(projectId, environment, actor);
        if (project.getImageRepository() == null || project.getImageRepository().isBlank()) {
            return response(ApiResponse.ok(Map.of("reason", "NO_IMAGE_REPOSITORY",
                "title", "프로젝트 이미지 저장소 설정이 필요합니다.",
                "detail", "이 프로젝트에 연결된 이미지 저장소가 없습니다.",
                "action", "프로젝트 관리자에게 이미지 저장소 설정을 요청해 주세요.")));
        }
        try {
            boolean available = !nexus.getDockerTags(project).isEmpty();
            return response(ApiResponse.ok(Map.of(
                "reason", available ? "IMAGES_AVAILABLE" : "NO_IMAGES",
                "title", available ? "배포할 이미지가 있습니다." : "배포할 이미지가 없습니다.",
                "detail", available ? "이 프로젝트의 연결된 저장소에 이미지 태그가 있습니다."
                    : "이 프로젝트의 연결된 저장소에서 이미지 태그를 찾지 못했습니다.",
                "action", available ? "이미지 목록을 다시 불러와 선택해 주세요."
                    : "프로젝트 관리자에게 빌드 결과와 이미지 등록 여부를 확인해 주세요.")));
        } catch (Exception e) {
            return unavailable();
        }
    }

    private Project authorizedProject(Long id, DeployEnvironment environment, SecurityUserDetails actor) {
        if (id == null || id <= 0 || environment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트와 배포 환경을 지정해 주세요.");
        }
        permissions.assertPermission(actor, id, ProjectPermissionType.DEPLOY_REQUEST, environment);
        return projects.findById(id).filter(Project::isActive)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 프로젝트를 찾을 수 없습니다."));
    }

    private ResponseEntity<ApiResponse<?>> unavailable() {
        // Raw upstream errors can contain registry addresses/credentials; request-only readers
        // need a retry/administrator action, not global infrastructure diagnostics.
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error("REQUEST_IMAGE_UNAVAILABLE",
                "프로젝트 이미지 저장소를 조회하지 못했습니다. 잠시 후 다시 시도하거나 관리자에게 확인해 주세요."));
    }

    private ResponseEntity<ApiResponse<?>> response(ApiResponse<?> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
