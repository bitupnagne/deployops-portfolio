package com.portfolio.deployops.controller;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.service.NexusRestoreService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/nexus/restore-requests") @RequiredArgsConstructor
public class NexusRestoreApiController {
    private final NexusRestoreService service;
    @GetMapping("/readiness") @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')") public ApiResponse<?> readiness(){
        return ApiResponse.ok(Map.of("ready",service.ready()));
    }
    @GetMapping @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')") public ApiResponse<?> recent(){
        return ApiResponse.ok(service.recent());
    }
    @GetMapping("/{id}") @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')") public ApiResponse<?> detail(@PathVariable Long id){
        return ApiResponse.ok(service.require(id));
    }
    @PostMapping @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody NexusRestoreForm form,@AuthenticationPrincipal SecurityUserDetails actor){
        return ResponseEntity.status(201).body(ApiResponse.ok("격리복원 승인을 요청했습니다.",service.create(form,actor.getId())));
    }
    @PostMapping("/{id}/approve") @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")public ResponseEntity<ApiResponse<?>> approve(@PathVariable Long id,@AuthenticationPrincipal SecurityUserDetails actor){
        return ResponseEntity.accepted().body(ApiResponse.ok("원본을 덮어쓰지 않는 격리복원이 대기열에 등록되었습니다.",service.approve(id,actor.getId())));
    }
    @PostMapping("/{id}/reject") @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')")public ApiResponse<?> reject(@PathVariable Long id,@RequestBody Map<String,String> form,@AuthenticationPrincipal SecurityUserDetails actor){
        service.reject(id,actor.getId(),form.get("reason"));
        return ApiResponse.ok("격리복원을 반려했습니다.",null);
    }
    @PostMapping("/{id}/functional-confirmation") @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")public ApiResponse<?> confirm(@PathVariable Long id,@Valid @RequestBody NexusRestoreConfirmation form,@AuthenticationPrincipal SecurityUserDetails actor){
        return ApiResponse.ok("운영자 수동 확인을 저장했습니다. 자동 기능검증 성공과는 별개입니다.",service.confirm(id,form,actor.getId()));
    }
    @PostMapping("/{id}/recheck") @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<?>> recheck(@PathVariable Long id,@AuthenticationPrincipal SecurityUserDetails actor){
        return ResponseEntity.accepted().body(ApiResponse.ok("복원을 재실행하지 않고 실행 전 실패 상태를 재확인합니다.",service.requestRecoveryCheck(id,actor.getId())));
    }
    @PostMapping("/{id}/existing-clone-recheck") @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<?>> existingCloneRecheck(@PathVariable Long id,@AuthenticationPrincipal SecurityUserDetails actor){
        return ResponseEntity.accepted().body(ApiResponse.ok("복원을 재실행하지 않고 기존 복원본의 기동·접속 준비를 확인합니다. 기능 검증은 별도입니다.",service.requestExistingCloneCheck(id,actor.getId())));
    }
}
