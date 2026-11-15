package com.portfolio.deployops.controller;

import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditIntegrityApiController {

    private final AuditLogService auditLogService;

    @GetMapping("/integrity")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> integrity() {
        return ApiResponse.ok(auditLogService.verifyIntegrity());
    }
}
