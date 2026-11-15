package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResponse<?> summary(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        return ApiResponse.ok(dashboardService.getSummary(currentUser));
    }

    /**
     * 대시보드 차트 데이터.
     *
     * <p>{@code days} 는 서비스에서 1~90 으로 clamp 한다 — 화면 파라미터로 몇 년치를 긁게 두면
     * 배포 이력이 쌓인 뒤 이 한 방에 DB 가 눕는다.
     */
    @GetMapping("/charts")
    public ApiResponse<?> charts(
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        @RequestParam(defaultValue = "14") int days
    ) {
        return ApiResponse.ok(dashboardService.getCharts(currentUser, days));
    }

    @GetMapping("/approvals/pending")
    public ApiResponse<?> pendingApprovals(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        return ApiResponse.ok(dashboardService.getPendingApprovals(currentUser));
    }

    @GetMapping("/deployments/recent")
    public ApiResponse<?> recentDeployments(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        return ApiResponse.ok(dashboardService.getRecentDeployments(currentUser));
    }
}
