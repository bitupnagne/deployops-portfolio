package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.DeployRequestCreateForm;
import com.portfolio.deployops.dto.DeployRequestDto;
import com.portfolio.deployops.dto.DeployRequestWithdrawalForm;
import com.portfolio.deployops.dto.RollbackRequestForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deploy-requests")
@RequiredArgsConstructor
public class DeployRequestApiController {

    private final DeployRequestService deployRequestService;
    private final AuditLogService auditLogService;
    private final ExecutionPolicyService executionPolicyService;
    private final ObjectMapper objectMapper;
    private final RemoteCommandLogRepository remoteCommandLogRepository;

    @GetMapping("/execution-policy")
    public ApiResponse<?> executionPolicy() {
        return ApiResponse.ok(Map.of(
            "requireTestFirst", executionPolicyService.requireTestFirst(),
            "requireApproval", executionPolicyService.requireApproval()
        ));
    }

    @GetMapping
    public ApiResponse<?> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long projectId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        DeployStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = DeployStatus.valueOf(status);
            } catch (Exception ignored) {
            }
        }

        Page<DeployRequest> result = deployRequestService.findWithFilter(
            statusEnum,
            projectId,
            LocalDateTime.now().minusDays(7),
            LocalDateTime.now().plusDays(1),
            PageRequest.of(page, size),
            currentUser
        );

        return ApiResponse.ok(Map.of(
            "content", result.getContent().stream().map(DeployRequestDto::from).toList(),
            "totalElements", result.getTotalElements(),
            "totalPages", result.getTotalPages(),
            "number", result.getNumber()
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(
        @PathVariable Long id,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        DeployRequest request = deployRequestService.findById(id, currentUser);
        return ApiResponse.ok(DeployRequestDto.from(request));
    }

    @GetMapping("/{id}/evidence")
    public ResponseEntity<byte[]> evidence(
        @PathVariable Long id,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        DeployRequest request = deployRequestService.findById(id, currentUser);
        deployRequestService.assertEvidencePermission(currentUser, request);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("exportedAt", LocalDateTime.now());
        evidence.put("request", DeployRequestDto.from(request));
        evidence.put("approvalRecords", request.getApprovalRecords());
        evidence.put("statusHistories", request.getStatusHistories());
        evidence.put("items", request.getItems());
        Map<String, Object> deploymentPlan = new LinkedHashMap<>();
        deploymentPlan.put("schemaVersion", request.getPlanSnapshotVersion());
        deploymentPlan.put("sha256", request.getPlanSnapshotHash());
        if (request.getPlanSnapshotJson() != null && !request.getPlanSnapshotJson().isBlank()) {
            try {
                deploymentPlan.put("snapshot", objectMapper.readTree(request.getPlanSnapshotJson()));
            } catch (Exception ex) {
                deploymentPlan.put("snapshotReadError", "저장된 배포 계획 JSON을 읽을 수 없습니다.");
            }
        }
        evidence.put("deploymentPlan", deploymentPlan);
        evidence.put("remoteCommandLogs", remoteCommandLogRepository.findByDeployRequestId(request.getId()));
        try {
            byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence)
                .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"deploy-evidence-" + request.getReqNo() + ".json\"")
                .body(body);
        } catch (Exception ex) {
            throw new IllegalStateException("배포 증적을 생성할 수 없습니다.", ex);
        }
    }

    @GetMapping("/promotion-sources")
    public ApiResponse<?> promotionSources(
        @RequestParam Long projectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        List<Map<String, Object>> sources = deployRequestService.findPromotionSources(projectId, currentUser).stream()
            .map(request -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", request.getId());
                row.put("reqNo", request.getReqNo());
                row.put("branch", request.getBranch());
                row.put("imageTag", request.getImageTag());
                row.put("imageDigest", request.getImageDigest());
                row.put("componentImages", request.getItems());
                row.put("commitSha", request.getCommitSha());
                row.put("confirmedAt", request.getTestConfirmedAt());
                return row;
            })
            .toList();
        return ApiResponse.ok(sources);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(
        @Valid @RequestBody DeployRequestCreateForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest saved = deployRequestService.create(form, currentUser);
        auditLogService.record(
            currentUser.getName(),
            saved.getEnvironment().name() + " 배포 요청 생성",
            "DeployRequest",
            String.valueOf(saved.getId()),
            auditLogService.describeDeployRequest(saved),
            AuditResult.SUCCESS,
            httpRequest.getRemoteAddr()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("배포 요청이 생성되었습니다.", Map.of(
                "id", saved.getId(),
                "reqNo", saved.getReqNo(),
                "status", saved.getStatus()
            )));
    }

    @PostMapping("/rollback")
    public ResponseEntity<ApiResponse<?>> createRollback(
        @Valid @RequestBody RollbackRequestForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest saved = deployRequestService.createRollbackRequest(form, currentUser);
        String action = saved.getEnvironment().name()
            + (saved.getStatus() == DeployStatus.APPROVAL_COMPLETED
            ? " emergency rollback request created"
            : " rollback request created");

        auditLogService.record(
            currentUser.getName(),
            action,
            "DeployRequest",
            String.valueOf(saved.getId()),
            auditLogService.describeDeployRequest(saved),
            AuditResult.SUCCESS,
            httpRequest.getRemoteAddr()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Rollback request created.", Map.of(
                "id", saved.getId(),
                "reqNo", saved.getReqNo(),
                "status", saved.getStatus()
            )));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<?> withdraw(@PathVariable Long id,
        @Valid @RequestBody DeployRequestWithdrawalForm form,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest) {
        DeployRequest request = deployRequestService.withdraw(id, form.reason(), currentUser,
            httpRequest.getRemoteAddr());
        return ApiResponse.ok("배포 요청이 철회되었습니다.", Map.of("status", request.getStatus()));
    }

    @PostMapping("/{id}/confirm-test")
    public ApiResponse<?> confirmTest(
        @PathVariable Long id,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest request = deployRequestService.confirmTest(id, currentUser);
        auditLogService.record(
            currentUser.getName(),
            "TEST 확인 완료",
            "DeployRequest",
            String.valueOf(request.getId()),
            auditLogService.describeDeployRequest(request),
            AuditResult.SUCCESS,
            httpRequest.getRemoteAddr()
        );
        return ApiResponse.ok("TEST 확인 완료 처리되었습니다.", Map.of("status", request.getStatus()));
    }
}
