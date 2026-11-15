package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.ProjectPermissionForm;
import com.portfolio.deployops.service.ProjectPermissionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ProjectPermissionApiController {
    private final ProjectPermissionManagementService service;

    @GetMapping("/projects/{projectId}/permissions")
    public ApiResponse<?> projectUsers(@PathVariable Long projectId, @AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok(service.projectUsers(projectId, actor));
    }

    @GetMapping("/projects/{projectId}/permissions/{userId}")
    public ApiResponse<?> get(@PathVariable Long projectId, @PathVariable Long userId, @AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok(service.get(projectId, userId, actor));
    }

    @GetMapping("/users/{userId}/project-permissions")
    public ApiResponse<?> byUser(@PathVariable Long userId, @AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok(service.byUser(userId, actor));
    }

    @PutMapping("/projects/{projectId}/permissions/{userId}")
    public ApiResponse<?> replace(@PathVariable Long projectId, @PathVariable Long userId,
                                 @Valid @RequestBody ProjectPermissionForm form,
                                 @AuthenticationPrincipal SecurityUserDetails actor, HttpServletRequest request) {
        return ApiResponse.ok("프로젝트 권한을 저장했습니다.", service.replace(projectId, userId, form, actor, request.getRemoteAddr()));
    }
}
