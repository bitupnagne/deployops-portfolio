package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.service.StorageMountService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@PreAuthorize("isAuthenticated()")
public class StorageMountApiController {
    private final StorageMountService service;
    public record InspectForm(String rootPath,String kind) { }
    @GetMapping("/servers/{serverId}/storage")
    public ApiResponse<?> locations(@PathVariable Long serverId,@AuthenticationPrincipal SecurityUserDetails actor) { return ApiResponse.ok(service.locations(serverId,actor)); }
    @PostMapping("/servers/{serverId}/storage/inspect")
    public ApiResponse<?> inspect(@PathVariable Long serverId,@RequestBody InspectForm form,@AuthenticationPrincipal SecurityUserDetails actor) { return ApiResponse.ok(service.inspect(serverId,form.rootPath(),form.kind(),actor)); }
    @PutMapping("/servers/{serverId}/storage")
    public ApiResponse<?> save(@PathVariable Long serverId,@RequestBody StorageLocationForm form,@AuthenticationPrincipal SecurityUserDetails actor) {
        service.saveLocation(serverId,form,actor); return ApiResponse.ok("WAS 허용 저장소를 등록했습니다. 기존 승인 설정이 바뀌면 새 배포 요청이 필요합니다.",null);
    }
    @GetMapping("/projects/{projectId}/storage/{environment}")
    public ApiResponse<?> view(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@AuthenticationPrincipal SecurityUserDetails actor) { return ApiResponse.ok(service.view(projectId,environment,actor)); }
    @PutMapping("/projects/{projectId}/storage/{environment}")
    public ApiResponse<?> bind(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@RequestBody StorageBindingForm form,@AuthenticationPrincipal SecurityUserDetails actor) {
        service.bind(projectId,environment,form,actor); return ApiResponse.ok("연결을 저장했습니다. 서버별 경로·권한 검사를 완료한 뒤 새 배포 요청으로 승인해 주세요.",null);
    }
    @PostMapping("/projects/{projectId}/storage/{environment}/check")
    public ApiResponse<?> check(@PathVariable Long projectId,@PathVariable DeployEnvironment environment,@RequestParam Long serverId,@RequestParam(defaultValue="0") Long componentId,@AuthenticationPrincipal SecurityUserDetails actor) {
        service.checkBinding(projectId,environment,componentId,serverId,actor); return ApiResponse.ok("현재 마운트 원본과 컨테이너 UID:GID 접근 검사가 통과했습니다. 배포·기동 때 다시 검사합니다.",null);
    }
}
