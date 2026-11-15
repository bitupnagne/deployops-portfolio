package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.NexusPromotionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/nexus/promotion-requests")
public class NexusPromotionApiController {
    private final NexusPromotionService service;
    public record Create(@NotNull @Positive Long restoreRequestId,@NotBlank @Size(max=1000) String reason,boolean externalWritersStopped){}
    public record Approve(boolean externalWritersStopped){}
    public record Reject(@NotBlank @Size(max=1000) String reason){}
    @GetMapping @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')") public ApiResponse<?> recent(){return ApiResponse.ok(service.recent());}
    @GetMapping("/readiness") @PreAuthorize("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')") public ApiResponse<?> readiness(){return ApiResponse.ok(Map.of("ready",service.ready()));}
    @PostMapping @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')") public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody Create form,@AuthenticationPrincipal SecurityUserDetails actor){return ResponseEntity.status(201).body(ApiResponse.ok("Nexus 운영 전환 승인을 요청했습니다.",service.create(form.restoreRequestId(),form.reason(),form.externalWritersStopped(),actor.getId())));}
    @PostMapping("/{id}/approve") @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')") public ResponseEntity<ApiResponse<?>> approve(@PathVariable Long id,@RequestBody Approve form,@AuthenticationPrincipal SecurityUserDetails actor){return ResponseEntity.accepted().body(ApiResponse.ok("Nexus 운영 전환이 대기열에 등록되었습니다.",service.approve(id,form.externalWritersStopped(),actor.getId())));}
    @PostMapping("/{id}/reject") @PreAuthorize("hasAnyRole('APPROVER','SUPER_ADMIN')") public ApiResponse<?> reject(@PathVariable Long id,@Valid @RequestBody Reject form,@AuthenticationPrincipal SecurityUserDetails actor){service.reject(id,form.reason(),actor.getId());return ApiResponse.ok("Nexus 운영 전환 요청을 반려했습니다.");}
}
