package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.ApprovalActionForm;
import com.portfolio.deployops.service.ApprovalService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalApiController {

    private final ApprovalService approvalService;

    @GetMapping("/my-pending")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<?> myPending(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        return ApiResponse.ok(approvalService.getMyPending(currentUser));
    }

    @PostMapping("/{deployRequestId}/approve")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<?> approve(
        @PathVariable Long deployRequestId,
        @Valid @RequestBody ApprovalActionForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        approvalService.approve(deployRequestId, form.getComment(), currentUser, httpRequest.getRemoteAddr(),form.getExpectedStepOrder());
        return ApiResponse.ok("승인 처리되었습니다.", null);
    }

    @PostMapping("/{deployRequestId}/reject")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<?> reject(
        @PathVariable Long deployRequestId,
        @Valid @RequestBody ApprovalActionForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        approvalService.reject(deployRequestId, form.getComment(), currentUser, httpRequest.getRemoteAddr(),form.getExpectedStepOrder());
        return ApiResponse.ok("반려 처리되었습니다.", null);
    }
}
