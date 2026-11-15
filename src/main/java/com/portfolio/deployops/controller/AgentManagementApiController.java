package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.AgentQueueService;
import com.portfolio.deployops.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/servers/{id}/agent")
@RequiredArgsConstructor
public class AgentManagementApiController {
    private final AgentQueueService agents;
    private final AuditLogService audit;
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> status(@PathVariable long id) { return ApiResponse.ok(agents.status(id)); }
    @PostMapping("/enrollment-code")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> issue(@PathVariable long id) {
        var result=agents.issueCode(id);
        audit.recordCurrentRequired("AGENT_ENROLLMENT_ISSUE","Server",String.valueOf(id),"에이전트 등록 코드 발급·기존 등록 세대 폐기",AuditResult.SUCCESS);
        return ApiResponse.ok("등록 코드는 15분 동안 한 번만 사용할 수 있습니다.",result);
    }
    @PostMapping("/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> approve(@PathVariable long id,@RequestBody RegistrationApproval body) {
        if(body==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"확인한 에이전트 정보를 보내 주세요.");
        agents.approve(id,body.agentBindingId(),body.machineId(),body.role());
        audit.recordCurrentRequired("AGENT_APPROVE","Server",String.valueOf(id),"호스트 정보 확인 후 에이전트 등록 승인",AuditResult.SUCCESS);
        return ApiResponse.ok("에이전트 등록을 승인했습니다.",null);
    }
    public record RegistrationApproval(String agentBindingId,String machineId,String role) { }
    @PostMapping("/revoke")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> revoke(@PathVariable long id) {
        agents.revoke(id);
        audit.recordCurrentRequired("AGENT_REVOKE","Server",String.valueOf(id),"에이전트 인증 폐기·대기 작업 중단·실행 중 결과 확인 필요",AuditResult.SUCCESS);
        return ApiResponse.ok("에이전트 등록을 해제했습니다. 실행 중이던 작업은 서버에서 확인해 주세요.",null);
    }
    public record QueryResolution(String reason, boolean acknowledged) { }
    @PostMapping("/tasks/{taskId}/resume-query")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> resumeQuery(@PathVariable long id, @PathVariable String taskId, @RequestBody QueryResolution body) {
        if (body==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"조회 재개 사유와 확인이 필요합니다.");
        agents.resolveReadOnlyQuery(id, taskId, body.reason(), body.acknowledged());
        audit.recordCurrentRequired("AGENT_READ_ONLY_QUERY_RESUME", "Server", String.valueOf(id),
            "task="+taskId+",읽기 전용 조회 종료; 성공 처리·백업 재실행 아님; 사유="+new com.portfolio.deployops.service.SecretMaskingService().mask(body.reason()), AuditResult.SUCCESS);
        return ApiResponse.ok("미확정 읽기 전용 조회를 종료했습니다. 백업·복원 화면에서 상태를 새로 조회하세요. 원래 작업을 성공 처리하거나 재실행하지 않았습니다.", null);
    }
    public record Resolution(String reason,boolean executionStopped) { }
    @PostMapping("/tasks/{taskId}/resolve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> resolve(@PathVariable long id,@PathVariable String taskId,@RequestBody Resolution body) {
        agents.resolveUnknown(id,taskId,body.reason(),body.executionStopped());
        audit.recordCurrentRequired("AGENT_UNKNOWN_RESOLVE","Server",String.valueOf(id),
            "task="+taskId+",실행 중지 확인; 성공 처리 아님; 사유="+new com.portfolio.deployops.service.SecretMaskingService().mask(body.reason()),AuditResult.SUCCESS);
        return ApiResponse.ok("미확정 작업 확인을 마쳤습니다. 서버 상태를 점검한 뒤 에이전트를 재등록해 주세요.",null);
    }
}
