package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.DeployComponentForm;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ProjectPermissionService;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트별 배포 단위(SSR/CSR) 관리 API. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/components")
@RequiredArgsConstructor
public class DeployComponentApiController {

    private final DeployComponentRepository deployComponentRepository;
    private final ProjectRepository projectRepository;
    private final ProjectPermissionService projectPermissionService;
    private final AuditLogService auditLogService;

    /**
     * 프로젝트의 배포 단위 목록.
     *
     * <p><b>프로젝트 조회 권한을 확인한다.</b> {@code /api/v1/projects/**} 는 SecurityConfig 의
     * 역할 매처에 없어 HTTP 레벨에서는 "인증만" 걸린다. 확인이 없으면 로그인한 아무나
     * 남의 프로젝트 이미지명·포트·레지스트리 경로를 열람할 수 있다.
     * 같은 프로젝트의 배포 로그({@code ProjectApiController#deployLogs})는 이미 이 검사를 하고 있어
     * 정책이 한쪽만 적용돼 있던 상태였다.
     */
    @GetMapping
    public ApiResponse<?> list(@PathVariable Long projectId,
                               @AuthenticationPrincipal SecurityUserDetails currentUser) {
        assertProjectView(currentUser, projectId);
        return ApiResponse.ok(deployComponentRepository.findByProjectId(projectId));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@PathVariable Long projectId,
                                                 @RequestBody DeployComponentForm form) {
        lockProject(projectId);
        DeployComponent candidate = toComponent(projectId, null, form);
        assertSingleActiveComponentPerRole(candidate);
        DeployComponent saved = deployComponentRepository.save(candidate);
        auditLogService.recordCurrentRequired("배포 단위 추가", "DeployComponent",
            String.valueOf(saved.getId()),
            "project=" + projectId + " code=" + saved.getCode(), AuditResult.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("배포 단위가 추가되었습니다.", Map.of("id", saved.getId(), "code", saved.getCode())));
    }

    private void assertProjectView(SecurityUserDetails currentUser, Long projectId) {
        if (currentUser == null
            || !projectPermissionService.hasPermission(
                   currentUser, projectId, ProjectPermissionType.PROJECT_VIEW,
                   (DeployEnvironment) null)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 프로젝트를 조회할 권한이 없습니다.");
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long projectId,
                                 @PathVariable Long id,
                                 @RequestBody DeployComponentForm form) {
        lockProject(projectId);
        DeployComponent existing = deployComponentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "배포 단위를 찾을 수 없습니다."));
        if (!projectId.equals(existing.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 프로젝트의 배포 단위가 아닙니다.");
        }
        DeployComponent candidate = toComponent(projectId, existing, form);
        assertSingleActiveComponentPerRole(candidate);
        deployComponentRepository.save(candidate);
        auditLogService.recordCurrentRequired("배포 단위 수정", "DeployComponent", String.valueOf(id),
            "project=" + projectId + " code=" + existing.getCode(), AuditResult.SUCCESS);
        return ApiResponse.ok("배포 단위가 수정되었습니다.", Map.of("id", id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> delete(@PathVariable Long projectId, @PathVariable Long id) {
        lockProject(projectId);
        DeployComponent existing = deployComponentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "배포 단위를 찾을 수 없습니다."));
        if (!projectId.equals(existing.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 프로젝트의 배포 단위가 아닙니다.");
        }
        deployComponentRepository.deleteById(id);
        auditLogService.recordCurrentRequired("배포 단위 삭제", "DeployComponent", String.valueOf(id),
            "project=" + projectId + " code=" + existing.getCode(), AuditResult.SUCCESS);
        return ApiResponse.ok("배포 단위가 삭제되었습니다.", null);
    }

    private DeployComponent toComponent(Long projectId, DeployComponent existing, DeployComponentForm form) {
        String code = normalize(form.getCode());
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "컴포넌트 코드를 입력해 주세요.");
        }
        ServerType role = parseRole(form.getRole());
        ArtifactType artifactType = parseArtifactType(form.getArtifactType());
        String proxyHealthHost;
        String proxyHealthHostTest;
        String proxyHealthHostProd;
        try {
            proxyHealthHost = com.portfolio.deployops.domain.ProxyHealthHost.normalize(form.getProxyHealthHost());
            proxyHealthHostTest = com.portfolio.deployops.domain.ProxyHealthHost.normalize(form.getProxyHealthHostTest());
            proxyHealthHostProd = com.portfolio.deployops.domain.ProxyHealthHost.normalize(form.getProxyHealthHostProd());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        boolean proxyWeb = role == ServerType.WEB && artifactType == ArtifactType.PROXY_RELOAD;
        if (!proxyWeb && (proxyHealthHost != null || proxyHealthHostTest != null || proxyHealthHostProd != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP Host는 WEB PROXY_RELOAD 배포 단위에서만 사용할 수 있습니다.");
        }
        if (proxyWeb && !form.isProxyHealthHostProvided() && existing != null) {
            proxyHealthHost = existing.getProxyHealthHost();
        }
        if (proxyWeb && existing != null) {
            if (!form.isProxyHealthHostTestProvided()) proxyHealthHostTest = existing.getProxyHealthHostTest();
            if (!form.isProxyHealthHostProdProvided()) proxyHealthHostProd = existing.getProxyHealthHostProd();
        }
        if (artifactType == ArtifactType.STATIC_BUNDLE && normalize(form.getWebRoot()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "STATIC_BUNDLE 컴포넌트는 WebRoot가 필요합니다.");
        }
        if (Boolean.TRUE.equals(form.getContainerPrivileged()) && artifactType != ArtifactType.CONTAINER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "privileged 설정은 CONTAINER 배포 단위에서만 사용할 수 있습니다.");
        }
        return DeployComponent.builder()
            .id(existing == null ? null : existing.getId())
            .projectId(projectId)
            .code(code)
            .displayName(normalize(form.getDisplayName()))
            .role(role)
            .artifactType(artifactType)
            .imageRepository(normalize(form.getImageRepository()))
            .webRoot(normalizeWebRoot(form.getWebRoot()))
            .buildTool(form.isBuildToolProvided() ? parseBuildTool(form.getBuildTool())
                : existing == null ? null : existing.getBuildTool())
            .dockerfilePath(form.isDockerfilePathProvided() ? buildPath(form.getDockerfilePath(), "Dockerfile 경로", false)
                : existing == null ? null : existing.getDockerfilePath())
            .dockerContextPath(form.isDockerContextPathProvided() ? buildPath(form.getDockerContextPath(), "Docker 컨텍스트", false)
                : existing == null ? null : existing.getDockerContextPath())
            .buildCommand(form.isBuildCommandProvided() ? buildCommand(form.getBuildCommand())
                : existing == null ? null : existing.getBuildCommand())
            .artifactPath(form.isArtifactPathProvided() ? buildPath(form.getArtifactPath(), "산출물 경로", true)
                : existing == null ? null : existing.getArtifactPath())
            .healthCheckMode(resolveHealthCheckMode(form.getHealthCheckMode(), existing))
            .healthCheckPort(form.getHealthCheckPort())
            .healthCheckPath(normalize(form.getHealthCheckPath()))
            .proxyHealthHost(proxyHealthHost)
            .proxyHealthHostTest(proxyHealthHostTest)
            .proxyHealthHostProd(proxyHealthHostProd)
            .containerUser(normalize(form.getContainerUser()))
            .containerPrivileged(Boolean.TRUE.equals(form.getContainerPrivileged()))
            .containerCapAdd(normalize(form.getContainerCapAdd()))
            .containerReadOnlyRootFs(Boolean.TRUE.equals(form.getContainerReadOnlyRootFs()))
            .deployOrder(form.getDeployOrder() != null ? form.getDeployOrder() : 0)
            .active(form.getActive() == null || form.getActive())
            .build();
    }

    private void lockProject(Long projectId) {
        if (projectId == null || projectRepository.lockById(projectId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
        }
    }

    /**
     * 현재 실행 엔진은 대상 서버 역할마다 한 번만 배포한다. 같은 역할의 활성 컴포넌트를
     * 여러 개 허용하면 화면에는 모두 보이지만 첫 번째만 실행되므로 저장 시점에 거부한다.
     * PROC와 PROC 관리자는 각각 독립 프로젝트로 등록해야 컨테이너·승인·롤백 이력도 분리된다.
     */
    private void assertSingleActiveComponentPerRole(DeployComponent candidate) {
        if (!candidate.isActive()) {
            return;
        }
        boolean duplicate = deployComponentRepository.findByProjectId(candidate.getProjectId()).stream()
            .filter(DeployComponent::isActive)
            .filter(component -> component.getRole() == candidate.getRole())
            .anyMatch(component -> candidate.getId() == null || !candidate.getId().equals(component.getId()));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "한 프로젝트에는 역할별 활성 배포 단위를 하나만 둘 수 있습니다. 별도 서비스는 프로젝트를 분리하세요.");
        }
        if (candidate.getArtifactType() != ArtifactType.PROXY_RELOAD && candidate.getImageRepository() != null) {
            boolean sharedRepository = deployComponentRepository.findByProjectId(candidate.getProjectId()).stream()
                .filter(DeployComponent::isActive)
                .filter(component -> component.getArtifactType() != ArtifactType.PROXY_RELOAD)
                .filter(component -> !java.util.Objects.equals(component.getId(), candidate.getId()))
                .anyMatch(component -> candidate.getImageRepository().equals(component.getImageRepository()));
            if (sharedRepository) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "활성 배포 단위마다 서로 다른 이미지 저장소를 지정해 주세요. 대표 저장소는 한 단위에서만 사용합니다.");
            }
        }
    }

    private HealthCheckMode resolveHealthCheckMode(String raw, DeployComponent existing) {
        String value = normalize(raw);
        if (value == null) {
            // 구버전 폼의 생략을 설정 변경으로 취급하지 않는다. 미설정(null)도 그대로 보존한다.
            return existing == null ? HealthCheckMode.SSH_LOCAL_CURL : existing.getHealthCheckMode();
        }
        try {
            return HealthCheckMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "헬스체크 방식은 NONE, HTTP_DIRECT, SSH_LOCAL_CURL, CONTAINER_RUNNING 중 하나여야 합니다.");
        }
    }

    /**
     * web root 는 대상 서버에서 root 권한으로 {@code install -d} 와 오래된 릴리스
     * {@code rm -rf} 가 도는 경로다. 에이전트도 검사하지만 저장 시점에 같이 막는다 —
     * 형식만 보고 저장하면 배포를 눌러야 거절당하고, 화면에는 이유가 남지 않는다.
     */
    private String normalizeWebRoot(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        if (!value.matches("/[A-Za-z0-9._/+:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "web root 는 절대경로여야 하며 허용되지 않은 문자를 포함할 수 없습니다.");
        }
        // '.' 이 허용 문자라 정규식만으로는 /var/www/../../etc 가 통과한다.
        if (value.equals("/") || value.matches(".*(^|/)\\.\\.(/|$).*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "web root 에 상위 경로(..)나 루트(/)를 지정할 수 없습니다.");
        }
        return value;
    }

    private ServerType parseRole(String raw) {
        String value = normalize(raw);
        if (value != null) {
            try {
                ServerType role = ServerType.valueOf(value.toUpperCase(Locale.ROOT));
                if (role == ServerType.WAS || role == ServerType.WEB) {
                    return role;
                }
            } catch (IllegalArgumentException ignored) {
                // fallthrough
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "역할은 WAS 또는 WEB이어야 합니다.");
    }

    private ArtifactType parseArtifactType(String raw) {
        String value = normalize(raw);
        if (value != null) {
            try {
                return ArtifactType.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fallthrough
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "산출물 종류는 CONTAINER, STATIC_BUNDLE, PROXY_RELOAD 중 하나여야 합니다.");
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BuildToolType parseBuildTool(String raw) {
        String value = normalize(raw);
        if (value == null) return null;
        try {
            return BuildToolType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 배포 단위 빌드 도구입니다.");
        }
    }

    private String buildPath(String raw, String label, boolean glob) {
        String value = normalize(raw);
        if (value == null) return null;
        String allowed = glob ? "[A-Za-z0-9._/*?\\[\\]-]+" : "[A-Za-z0-9._/-]+";
        if (value.length() > 500 || !value.matches(allowed) || value.startsWith("/")
            || value.startsWith("-") || value.matches("(^|.*/)\\.\\.(/.*|$)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                label + "는 저장소 안의 상대 경로로 입력하고 상위 경로(..)를 사용하지 마세요.");
        }
        return value;
    }

    private String buildCommand(String raw) {
        String value = normalize(raw);
        if (value == null) return null;
        if (value.length() > 500 || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "빌드 명령은 제어 문자 없이 500자 이내 한 줄로 입력해 주세요.");
        }
        return value;
    }
}
