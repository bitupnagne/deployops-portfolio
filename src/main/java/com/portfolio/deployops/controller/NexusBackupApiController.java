package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.service.NexusBackupService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nexus/backup-requests")
@RequiredArgsConstructor
public class NexusBackupApiController {
    private final NexusBackupService service;
    @GetMapping("/readiness") @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> readiness(){return ApiResponse.ok(Map.of("ready",service.ready(),"reasonCode",java.util.Objects.toString(service.readinessCode(),"")));}
    @GetMapping @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> list(){return ApiResponse.ok(service.recent());}
    @GetMapping("/{id}") @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> detail(@PathVariable Long id){return ApiResponse.ok(service.require(id));}
    @PostMapping @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody NexusBackupRequestForm form,@AuthenticationPrincipal SecurityUserDetails user){
        return ResponseEntity.status(201).body(ApiResponse.ok("백업 승인을 요청했습니다.",service.create(form.reason(),form.externalWritersStopped(),user.getUser().getId())));
    }
    @PostMapping("/{id}/approve") @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<?>> approve(@PathVariable Long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal SecurityUserDetails user){
        return ResponseEntity.accepted().body(ApiResponse.ok("백업이 대기열에 등록되었습니다.",service.approve(id,user.getUser().getId(),Boolean.TRUE.equals(body.get("externalWritersStopped")))));
    }
    @PostMapping("/{id}/reject") @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")
    public ApiResponse<?> reject(@PathVariable Long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal SecurityUserDetails user){service.reject(id,user.getUser().getId(),body.get("reason") instanceof String ? (String)body.get("reason"):null);return ApiResponse.ok("백업 요청을 반려했습니다.");}
    @PostMapping("/{id}/recheck") @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<?>> recheck(@PathVariable Long id,@AuthenticationPrincipal SecurityUserDetails user){
        return ResponseEntity.accepted().body(ApiResponse.ok("원본을 변경하지 않는 상태 재확인을 요청했습니다.",service.requestRecoveryCheck(id,user.getUser().getId())));
    }
    @PostMapping("/{id}/rechecks/{checkId}/supersede") @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<?>> supersede(@PathVariable Long id,@PathVariable Long checkId,@AuthenticationPrincipal SecurityUserDetails user){
        return ResponseEntity.accepted().body(ApiResponse.ok("이전 확인을 미확정으로 종료하고 새 상태 확인을 요청했습니다. 유지보수 차단은 유지됩니다.",service.supersedeRecoveryCheck(id,checkId,user.getUser().getId())));
    }
}
