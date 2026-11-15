package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.NginxChangeService;
import com.portfolio.deployops.service.RemoteExecutionException;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/nginx-changes")
@RequiredArgsConstructor
public class NginxChangeApiController {
    private final NginxChangeService service;
    private final AuditLogService auditLogService;
    private final com.portfolio.deployops.service.CertificateExpiryService certificateExpiryService;

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> preview(@Valid @ModelAttribute NginxPolicyForm form) {
        return ApiResponse.ok(service.preview(form));
    }

    @GetMapping("/{id}/config")
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> requestedConfiguration(@PathVariable Long id) {
        return ApiResponse.ok(service.requestedConfiguration(id));
    }

    @GetMapping("/servers/{serverId}/configuration")
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> currentConfiguration(@PathVariable Long serverId, @RequestParam String policyName) {
        return ApiResponse.ok(service.currentConfiguration(serverId, policyName));
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ApiResponse<?> create(@Valid @ModelAttribute NginxPolicyForm form,
                                 @RequestPart(required = false) MultipartFile certificate,
                                 @RequestPart(required = false) MultipartFile privateKey,
                                 @AuthenticationPrincipal SecurityUserDetails user) {
        NginxChangeRequest request = service.create(form, certificate, privateKey, user.getId());
        auditLogService.recordCurrent("Nginx 변경 요청", "NginxChangeRequest", String.valueOf(request.getId()),
            describe(request), AuditResult.SUCCESS);
        return ApiResponse.ok("Nginx 변경 승인을 요청했습니다.", Map.of("id", request.getId()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> approve(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails user) {
        try {
            NginxChangeRequest request = service.approveAndApply(id, user.getId());
            auditLogService.recordCurrent("Nginx 변경 승인 및 적용", "NginxChangeRequest", String.valueOf(id),
                describe(request), AuditResult.SUCCESS);
            return ApiResponse.ok("Nginx 설정을 검증하고 적용했습니다.", Map.of("status", request.getStatus()));
        } catch (RuntimeException ex) {
            auditLogService.recordCurrent("Nginx 변경 승인 및 적용", "NginxChangeRequest", String.valueOf(id),
                "적용 실패: " + ex.getMessage(), AuditResult.FAIL);
            if (ex instanceof RemoteExecutionException) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nginx 설정 검증 또는 원격 적용에 실패했습니다. 변경 이력에서 상세 사유를 확인해 주세요.");
            }
            throw ex;
        }
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> reject(@PathVariable Long id, @RequestParam String reason,
                                 @AuthenticationPrincipal SecurityUserDetails user) {
        service.reject(id, user.getId(), reason);
        auditLogService.recordCurrentRequired("Nginx 변경 반려", "NginxChangeRequest", String.valueOf(id), reason, AuditResult.SUCCESS);
        return ApiResponse.ok("변경 요청을 반려했습니다.", null);
    }

    @PostMapping("/{id}/rollback-requests")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> requestRollback(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails user) {
        NginxChangeRequest rollback = service.requestRollback(id, user.getId());
        auditLogService.recordCurrentRequired("Nginx 복구 요청", "NginxChangeRequest", String.valueOf(rollback.getId()),
            "source=" + id + " | restore=" + (rollback.isRestoreAbsent() ? "설정 없음" : rollback.getRestoreTargetId())
                + " | snapshot=" + rollback.getRollbackSnapshotHash() + " | " + describe(rollback), AuditResult.SUCCESS);
        return ApiResponse.ok("이전 설정 복구 승인을 요청했습니다.", Map.of("id", rollback.getId()));
    }

    @GetMapping("/servers/{serverId}/inspect")
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> inspect(@PathVariable Long serverId) {
        return ApiResponse.ok(Map.of("output", service.inspect(serverId)));
    }

    /** 만료가 임박한(또는 이미 만료된) 인증서. 화면 상단 경고 배너가 쓴다. */
    @GetMapping("/certificates/expiring")
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> expiringCertificates() {
        return ApiResponse.ok(certificateExpiryService.findExpiring().stream()
            .map(item -> Map.of(
                "id", item.request().getId(),
                "policyName", item.request().getPolicyName(),
                "serverName", item.request().getServer() != null ? item.request().getServer().getName() : "",
                "subject", item.request().getCertificateSubject() == null ? "" : item.request().getCertificateSubject(),
                "notAfter", String.valueOf(item.request().getCertificateNotAfter()),
                "daysLeft", item.daysLeft(),
                "expired", item.expired()))
            .toList());
    }

    private String describe(NginxChangeRequest request) {
        if (request.isRestoreAbsent()) return request.getPolicyName() + " | server=" + request.getServer().getName()
            + " | 관리 정책 제거 · 이전 백업 상태 복원 | snapshot=" + request.getRollbackSnapshotHash();
        return request.getPolicyName() + " | server=" + request.getServer().getName()
            + " | " + request.getServerName() + ":" + request.getListenPort()
            + " -> " + (request.getPolicyType() == com.portfolio.deployops.domain.NginxPolicyType.REVERSE_PROXY
                ? request.getUpstreamHost() + ":" + request.getUpstreamPort() : request.getWebRoot() + "/current")
            + " | WAS=" + request.getEffectiveUpstreams().size() + "개 (승인 요청에 보관된 목록)"
            + (request.hasSecondaryUpstream() ? " | secondary=" + request.getSecondaryUpstreamHost() + ":"
                + request.getSecondaryUpstreamPort() + " | passive=" + request.getUpstreamMaxFails() + "/"
                + request.getUpstreamFailTimeoutSec() + "s | connect=" + request.getProxyConnectTimeoutSec() + "s" : "")
            + (request.isApiProxyEnabled() ? " | API " + request.getApiPrefix() + " -> "
                + request.getApiUpstreamHost() + ":" + request.getApiUpstreamPort() : "");
    }
}
