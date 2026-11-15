package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nexus/deploy-projects/{projectId}/dependencies")
@RequiredArgsConstructor
public class NexusDependencyApiController {
    private final NexusDependencyUploadService uploads;
    private final NexusMetadataService nexus;
    private final ProjectPermissionService permissions;
    private final AuditLogService audit;

    @GetMapping
    public ApiResponse<?> settings(@PathVariable Long projectId, @AuthenticationPrincipal SecurityUserDetails user) {
        permissions.assertPermission(user, projectId, ProjectPermissionType.PROJECT_VIEW);
        return ApiResponse.ok(Map.of("binding", uploads.binding(projectId),
            "chunkBytes", NexusDependencyUploadService.CHUNK_BYTES,
            "canManage", "SUPER_ADMIN".equals(user.getRole()),
            "canUpload", canUpload(user, projectId)));
    }

    @GetMapping("/repositories")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> repositories(@PathVariable Long projectId,
            @RequestParam String format, @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        permissions.assertPermission(user, projectId, ProjectPermissionType.PROJECT_VIEW);
        NexusDependencyPolicy.format(format);
        return ApiResponse.ok(nexus.listRepositories(format).stream().filter(r -> "hosted".equals(r.get("type"))).toList());
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> save(@PathVariable Long projectId, @RequestBody NexusDependencyUploadService.Binding binding,
            @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        permissions.assertPermission(user, projectId, ProjectPermissionType.PROJECT_VIEW);
        uploads.bind(projectId, binding);
        audit.recordCurrentRequired("프로젝트 의존성 저장소 연결", "Project", projectId.toString(),
            binding.repository() + "/" + binding.namespace(), AuditResult.SUCCESS);
        return ApiResponse.ok(uploads.binding(projectId));
    }

    @GetMapping("/uploads")
    public ApiResponse<?> recent(@PathVariable Long projectId, @AuthenticationPrincipal SecurityUserDetails user) {
        assertUpload(user, projectId);
        return ApiResponse.ok(uploads.recent(projectId, sessionOwner(user)));
    }

    public record CreateUpload(String relativePath, long totalBytes) {}

    @PostMapping("/uploads")
    public ApiResponse<?> create(@PathVariable Long projectId, @RequestBody CreateUpload form,
            @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        assertUpload(user, projectId);
        audit.recordCurrentRequired("Nexus 파일 반입 시작 요청", "Project", projectId.toString(),
            form.relativePath(), AuditResult.SUCCESS);
        return ApiResponse.ok(uploads.create(projectId, user.getId(), form.relativePath(), form.totalBytes()));
    }

    @PutMapping(value="/uploads/{id}/chunk", consumes="application/octet-stream")
    public ApiResponse<?> chunk(@PathVariable Long projectId, @PathVariable String id, @RequestParam long offset,
            @RequestParam long length, HttpServletRequest request,
            @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        assertUpload(user, projectId);
        return ApiResponse.ok(uploads.chunk(id, projectId, sessionOwner(user), offset, length, request.getInputStream()));
    }

    @PostMapping("/uploads/{id}/publish")
    public ApiResponse<?> publish(@PathVariable Long projectId, @PathVariable String id,
            @AuthenticationPrincipal SecurityUserDetails user) {
        assertUpload(user, projectId);
        uploads.require(id, projectId, sessionOwner(user));
        audit.recordCurrentRequired("Nexus 파일 등록 실행 요청", "NexusUpload", id, "등록 결과는 전송 이력에서 확인", AuditResult.SUCCESS);
        return ApiResponse.ok(uploads.publish(id, projectId, sessionOwner(user)));
    }

    @PostMapping("/uploads/{id}/verify")
    public ApiResponse<?> verify(@PathVariable Long projectId, @PathVariable String id,
            @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        assertUpload(user, projectId);
        return ApiResponse.ok(uploads.verify(id, projectId, sessionOwner(user)));
    }

    @PostMapping("/uploads/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable Long projectId, @PathVariable String id,
            @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        assertUpload(user, projectId);
        var result = uploads.cancel(id, projectId, sessionOwner(user));
        audit.recordCurrent("Nexus 파일 전송 취소 요청", "NexusUpload", id, result.getStatus(), AuditResult.SUCCESS);
        return ApiResponse.ok(result);
    }

    @PostMapping("/uploads/{id}/checksums")
    public ApiResponse<?> repairChecksums(@PathVariable Long projectId, @PathVariable String id,
            @AuthenticationPrincipal SecurityUserDetails user) throws Exception {
        assertUpload(user, projectId);
        uploads.require(id, projectId, sessionOwner(user));
        audit.recordCurrentRequired("Maven 누락 checksum 등록 재개 요청", "NexusUpload", id,
            "원본 SHA-256 확인 후 누락 checksum만 등록; 기존 파일 덮어쓰기 없음", AuditResult.SUCCESS);
        return ApiResponse.ok(uploads.repairChecksums(id, projectId, sessionOwner(user)));
    }

    private boolean canUpload(SecurityUserDetails user, Long projectId) {
        return ("OPERATOR".equals(user.getRole()) || "SUPER_ADMIN".equals(user.getRole()))
            && permissions.hasPermission(user, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
    }
    private Long sessionOwner(SecurityUserDetails user) {
        return "SUPER_ADMIN".equals(user.getRole()) ? null : user.getId();
    }
    private void assertUpload(SecurityUserDetails user, Long projectId) {
        if (!canUpload(user, projectId)) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN, "운영자 역할과 프로젝트 CI 관리 권한이 필요합니다.");
    }
}
