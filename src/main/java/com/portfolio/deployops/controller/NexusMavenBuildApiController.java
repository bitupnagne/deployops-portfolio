package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/nexus/deploy-projects/{projectId}/dependencies/maven-build")
public class NexusMavenBuildApiController {
    private final NexusMavenBuildService service;
    private final AuditLogService audit;
    public record Form(String commonRepository, String expectedUploadRepository, String reason) {}

    @GetMapping
    public ApiResponse<?> configuration(@PathVariable Long projectId,
            @AuthenticationPrincipal SecurityUserDetails actor) throws Exception {
        return ApiResponse.ok(service.configuration(projectId, actor));
    }

    @GetMapping("/preview")
    public ApiResponse<?> preview(@PathVariable Long projectId, @RequestParam String commonRepository,
            @AuthenticationPrincipal SecurityUserDetails actor) throws Exception {
        return ApiResponse.ok(service.preview(projectId, commonRepository, actor));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> apply(@PathVariable Long projectId, @RequestBody Form form,
            @AuthenticationPrincipal SecurityUserDetails actor, HttpServletRequest http) throws Exception {
        audit.recordCurrentRequired("프로젝트 Maven 빌드 연결 요청", "Project", projectId.toString(),
            "전용 group 및 MAVEN_SETTINGS_FILE 등록", AuditResult.SUCCESS);
        return ApiResponse.ok(service.apply(projectId, form.commonRepository(), form.expectedUploadRepository(),
            form.reason(), actor, http.getRemoteAddr()));
    }

    @GetMapping("/verify")
    public ApiResponse<?> verify(@PathVariable Long projectId, @RequestParam String commonRepository,
            @AuthenticationPrincipal SecurityUserDetails actor) throws Exception {
        return ApiResponse.ok(service.verify(projectId, commonRepository, actor));
    }
}
