package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.service.RuntimeConfigurationService;
import com.portfolio.deployops.service.RuntimeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/runtime/{environment}")
@PreAuthorize("isAuthenticated()")
public class RuntimeConfigurationApiController {
    private final RuntimeConfigurationService service;
    private final RuntimeLogService logs;
    @GetMapping
    public ApiResponse<?> view(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok(service.view(projectId,environment,actor));
    }
    @PostMapping("/revisions")
    public ApiResponse<?> create(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@RequestBody RuntimeRevisionForm form,@AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok("새 런타임 설정을 보관했습니다. 승인된 배포 때 적용됩니다.",service.create(projectId,environment,form,actor));
    }
    @PutMapping("/binding")
    public ApiResponse<?> bind(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@RequestBody RuntimeBindingForm form,@AuthenticationPrincipal SecurityUserDetails actor) {
        service.bind(projectId,environment,form,actor); return ApiResponse.ok("적용할 설정을 저장했습니다. 새 배포 요청으로 승인해 주세요.",null);
    }
    @GetMapping("/logs")
    public ApiResponse<?> logs(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@RequestParam Long serverId,@RequestParam(defaultValue="0") Long componentId,@RequestParam(defaultValue="200") int lines,@AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok(logs.read(projectId,environment,serverId,componentId,lines,actor));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> unreadable(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_BODY","요청 형식을 확인해 주세요."));
    }
}
