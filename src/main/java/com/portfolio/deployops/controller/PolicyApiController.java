package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.PolicyDto;
import com.portfolio.deployops.dto.PolicyForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
public class PolicyApiController {

    private final PolicyService policyService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<?> list() {
        List<PolicyDto> dtos = policyService.findAll().stream()
            .map(PolicyDto::new).toList();
        return ApiResponse.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@RequestBody PolicyForm form) {
        ApprovalPolicy p = policyService.create(form);
        auditLogService.recordCurrentRequired("승인 정책 생성", "ApprovalPolicy", String.valueOf(p.getId()),
            describe(p), AuditResult.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("승인 정책이 생성되었습니다.", Map.of(
                "id", p.getId(), "name", p.getName()
            )));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody PolicyForm form) {
        ApprovalPolicy p = policyService.update(id, form);
        auditLogService.recordCurrentRequired("승인 정책 수정", "ApprovalPolicy", String.valueOf(id),
            describe(p), AuditResult.SUCCESS);
        return ApiResponse.ok("승인 정책이 수정되었습니다.", Map.of(
            "id", p.getId(), "name", p.getName()
        ));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> delete(@PathVariable Long id) {
        String desc = describe(policyService.findById(id));
        policyService.delete(id);
        auditLogService.recordCurrentRequired("승인 정책 삭제", "ApprovalPolicy", String.valueOf(id),
            desc, AuditResult.SUCCESS);
        return ApiResponse.ok("승인 정책이 삭제되었습니다.", null);
    }

    /**
     * 감사로그용 정책 식별 문구 — <b>단계 수를 함께 남긴다.</b>
     *
     * <p>승인 정책 변경에서 실제로 위험한 것은 이름이 아니라 "승인 단계가 몇 개였는지"다.
     * 2단계 결재를 1단계로 줄인 것과 이름만 바꾼 것이 로그에서 구분되어야 한다.
     */
    private String describe(ApprovalPolicy p) {
        int steps = p.getSteps() == null ? 0 : p.getSteps().size();
        return p.getName() + " (승인 단계 " + steps + ")";
    }
}
