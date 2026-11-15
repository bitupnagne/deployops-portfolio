package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class RolloutTrafficRecoveryApiController {
    private final DeployRequestService requests;
    private final ProjectPermissionService permissions;
    private final DeploymentDutySeparationService duties;
    private final ExecutionPolicyService executions;
    private final RolloutTrafficRecoveryService recovery;
    private final AuditLogService audit;

    @PostMapping("/api/v1/operator/traffic-recover/{id}")
    public ApiResponse<?> recover(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails user, HttpServletRequest http) {
        DeployRequest request = requests.findById(id);
        permissions.assertPermission(user, request.getProject().getId(), request.getEnvironment() == DeployEnvironment.PROD
            ? ProjectPermissionType.PROD_EXECUTE : ProjectPermissionType.TEST_EXECUTE, request.getEnvironment());
        duties.assertCanOperate(request, user);
        DeployStatus failed = request.getStatus();
        if (failed != DeployStatus.TEST_FAILED && failed != DeployStatus.PROD_FAILED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "실패한 배포 요청에서만 트래픽을 복구할 수 있습니다.");
        DeployStatus executing = request.getEnvironment() == DeployEnvironment.PROD ? DeployStatus.PROD_DEPLOYING : DeployStatus.TEST_DEPLOYING;
        if (!executions.tryStart(request, failed, executing)) throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 작업이 이미 실행 중입니다.");
        try {
            audit.record(user.getName(), "배포 트래픽 복구 시작", "DeployRequest", id.toString(), request.getReqNo(), AuditResult.SUCCESS, http.getRemoteAddr());
            recovery.recover(id, failed, user.getName(), http.getRemoteAddr());
        } catch (RuntimeException error) {
            requests.tryTransitionToFailure(id, executing, failed, "트래픽 복구 작업을 시작하지 못했습니다.");
            throw error;
        }
        return ApiResponse.ok("정상 서비스의 프로젝트 트래픽 복구를 시작했습니다.", Map.of("requestId", id, "status", executing));
    }
}
