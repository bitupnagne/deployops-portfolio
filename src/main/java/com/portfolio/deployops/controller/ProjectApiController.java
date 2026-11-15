package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.ProjectGitlabConnectionService;
import com.portfolio.deployops.service.ProjectReadinessService;
import com.portfolio.deployops.service.RemoteCommandLogService;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.dto.ProjectGitlabConnectionForm;
import com.portfolio.deployops.service.ProjectService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectApiController {

    private final RemoteCommandLogService remoteCommandLogService;
    private final ProjectPermissionService projectPermissionService;

    private final ProjectService projectService;
    private final AuditLogService auditLogService;
    private final ProjectReadinessService projectReadinessService;
    private final GitlabService gitlabService;
    private final ProjectGitlabConnectionService projectGitlabConnectionService;

    /** Check the selected GitLab project using the configured server-side service account. */
    @PostMapping("/{id}/gitlab/test")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testGitlabProject(
        @PathVariable Long id,
        @RequestBody(required = false) ProjectGitlabConnectionForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        projectPermissionService.assertPermission(currentUser, id, ProjectPermissionType.PROJECT_VIEW);
        Project project = projectService.findById(id);
        Long gitlabProjectId = project.getGitlabProjectId();
        String repositoryUrl = project.getGitlabRepoUrl();
        if (form != null) {
            gitlabProjectId = form.gitlabProjectId();
            repositoryUrl = form.repositoryUrl();
        }
        if (gitlabProjectId == null || gitlabProjectId <= 0) {
            return ApiResponse.error("NO_GITLAB_ID", "GitLab 프로젝트 ID를 입력해 주세요.");
        }
        try {
            var result = projectGitlabConnectionService.test(gitlabProjectId, repositoryUrl);
            String message = result.repositoryUrlVerified()
                ? "GitLab 프로젝트 접근 및 저장소 주소 일치를 확인했습니다. Git clone 통신은 검사하지 않았습니다."
                : "GitLab 프로젝트 접근만 확인했습니다. 저장소 주소는 입력되지 않아 검사하지 않았습니다.";
            return ApiResponse.ok(message, result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error("GITLAB_PROJECT_TEST_FAILED", e.getMessage());
        }
    }

    /**
     * 이 프로젝트를 지금 배포할 수 있는가, 없다면 무엇이 남았는가.
     *
     * <p>상세 화면 맨 위에서 부른다. 입력 칸 46개 중 첫 배포를 실제로 막는 것은 둘뿐인데
     * 화면이 그것을 구분해 주지 않아, 새 프로젝트 앞에서 무엇부터 할지 알 수 없었다.
     */
    @GetMapping("/{id}/readiness")
    public ApiResponse<?> readiness(@PathVariable Long id,
                                    @AuthenticationPrincipal SecurityUserDetails currentUser) {
        projectPermissionService.assertPermission(currentUser, id, ProjectPermissionType.PROJECT_VIEW);
        Project project = projectService.findByIdWithDetails(id, currentUser);
        String token = gitlabService.resolveReadonlyCiAccessToken(
            currentUser != null && currentUser.getUser() != null ? currentUser.getUser().getGitlabToken() : null);
        return ApiResponse.ok(projectReadinessService.evaluate(project, token));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@RequestBody ProjectForm form,
                                                  @AuthenticationPrincipal SecurityUserDetails currentUser) {
        if (form.getApprovalPolicyId() != null && !projectPermissionService.isSuperAdmin(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인 정책은 최고 관리자만 지정할 수 있습니다.");
        }
        Project project = projectService.create(form);
        auditLogService.recordCurrentRequired("프로젝트 생성", "Project", String.valueOf(project.getId()),
            project.getName(), AuditResult.SUCCESS);
        if (form.getServerGroupIds() != null && !form.getServerGroupIds().isEmpty()) {
            auditLogService.recordCurrentRequired("프로젝트 배포 대상 설정", "Project", String.valueOf(project.getId()),
                "serverGroups=" + projectService.describeServerGroupMappings(form.getServerGroupIds().stream().sorted().toList()),
                AuditResult.SUCCESS);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("프로젝트가 생성되었습니다.", Map.of(
                "id", project.getId(),
                "name", project.getName()
            )));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody ProjectForm form,
                                 @AuthenticationPrincipal SecurityUserDetails currentUser) {
        if (form.getServerGroupIds() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "배포 대상은 프로젝트 상세의 서버 그룹 설정에서 별도로 변경해 주세요.");
        }
        if (form.getApprovalPolicyId() != null && !projectPermissionService.isSuperAdmin(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인 정책은 최고 관리자만 변경할 수 있습니다.");
        }
        Long previousPolicyId = null;
        if (form.getApprovalPolicyId() != null) {
            Project existing = projectService.findById(id);
            previousPolicyId = existing.getApprovalPolicy() != null
                ? existing.getApprovalPolicy().getId() : null;
        }
        Project project = projectService.update(id, form);
        auditLogService.recordCurrentRequired("프로젝트 수정", "Project", String.valueOf(id),
            project.getName(), AuditResult.SUCCESS);
        if (form.getApprovalPolicyId() != null && !form.getApprovalPolicyId().equals(previousPolicyId)) {
            auditLogService.recordCurrentRequired("프로젝트 승인 정책 변경", "Project", String.valueOf(id),
                "previousPolicyId=" + previousPolicyId + ", policyId=" + form.getApprovalPolicyId(),
                AuditResult.SUCCESS);
        }
        return ApiResponse.ok("프로젝트가 수정되었습니다.", Map.of(
            "id", project.getId(),
            "name", project.getName()
        ));
    }

    @PutMapping("/{id}/server-groups")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> replaceServerGroups(@PathVariable Long id, @RequestBody ServerGroupMappingForm form) {
        List<Long> previousIds = projectService.findByIdWithDetails(id).getServerGroups().stream()
            .map(group -> group.getId()).filter(java.util.Objects::nonNull).sorted().toList();
        List<String> previousGroups = projectService.describeServerGroupMappings(previousIds);
        List<Long> serverGroupIds = projectService.replaceServerGroupMappings(id, form.serverGroupIds());
        if (!previousIds.equals(serverGroupIds)) {
            auditLogService.recordCurrentRequired("프로젝트 배포 대상 변경", "Project", String.valueOf(id),
                "previousServerGroups=" + previousGroups + ", serverGroups="
                    + projectService.describeServerGroupMappings(serverGroupIds),
                AuditResult.SUCCESS);
        }
        return ApiResponse.ok("프로젝트 배포 대상이 저장되었습니다.", Map.of("serverGroupIds", serverGroupIds));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> delete(@PathVariable Long id) {
        projectService.deactivate(id);
        auditLogService.recordCurrentRequired("프로젝트 비활성화", "Project", String.valueOf(id),
            "-", AuditResult.SUCCESS);
        return ApiResponse.ok("프로젝트가 비활성화되었습니다.", null);
    }

    /**
     * 프로젝트 상세의 "배포 로그" 패널.
     *
     * <p>내용은 {@code remote_command_log} 그대로다 — 저장 시점에 시크릿이 마스킹되어 있고,
     * 프로젝트 조회 권한이 있는 사람만 볼 수 있다. <b>AI 분석도 정확히 이 데이터만 본다.</b>
     */
    @GetMapping("/{projectId}/deploy-logs")
    public ApiResponse<?> deployLogs(
        @PathVariable Long projectId,
        @RequestParam(defaultValue = "30") int limit,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        assertProjectView(currentUser, projectId);
        List<Map<String, Object>> rows = remoteCommandLogService.findRecentByProject(projectId, limit).stream()
            .map(log -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", log.getId());
                m.put("deployRequestId", log.getDeployRequestId());
                m.put("serverName", log.getServerName());
                m.put("command", log.getCommandDisplay());
                m.put("exitCode", log.getExitCode());
                m.put("success", log.isSuccess());
                m.put("durationMs", log.getDurationMs());
                m.put("createdAt", log.getCreatedAt());
                m.put("stdout", log.getStdoutText());
                m.put("stderr", log.getStderrText());
                return m;
            })
            .toList();
        return ApiResponse.ok(rows);
    }

    /** 프로젝트를 볼 수 있는 사람인지. 없으면 403 — AI 분석도 같은 검사를 통과해야 한다. */
    private void assertProjectView(SecurityUserDetails currentUser, Long projectId) {
        if (currentUser == null
            || !projectPermissionService.hasPermission(
                   currentUser, projectId, ProjectPermissionType.PROJECT_VIEW,
                   (DeployEnvironment) null)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 프로젝트를 조회할 권한이 없습니다.");
        }
    }

    public record ServerGroupMappingForm(List<Long> serverGroupIds) { }
}
