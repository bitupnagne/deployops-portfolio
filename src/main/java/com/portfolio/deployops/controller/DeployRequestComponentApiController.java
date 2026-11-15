package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.DeployRequestComponentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class DeployRequestComponentApiController {
    private final DeployRequestComponentService service;

    @GetMapping("/api/v1/projects/{projectId}/request-components")
    public ResponseEntity<ApiResponse<List<DeployRequestComponentService.ComponentSummary>>> list(
        @PathVariable Long projectId,
        @RequestParam DeployEnvironment environment,
        @RequestParam DeployScope scope,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(service.list(projectId, environment, scope, currentUser)));
    }
}
