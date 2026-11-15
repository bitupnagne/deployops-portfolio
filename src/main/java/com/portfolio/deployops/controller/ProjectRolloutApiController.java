package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.repository.ProjectRolloutRouteRepository;
import com.portfolio.deployops.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{id}/rollout-routes")
@RequiredArgsConstructor
public class ProjectRolloutApiController {
    private final RolloutTrafficService traffic;
    private final ProjectPermissionService permissions;
    private final ProjectService projects;
    private final AuditLogService audit;

    public record Selection(@NotNull @Positive Long webServerId,
                            @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,79}") String policyName) { }
    public record SaveForm(@NotNull @Size(max=100) List<@NotNull @Valid Selection> bindings) { }

    @GetMapping
    public ApiResponse<?> get(@PathVariable Long id, @RequestParam DeployEnvironment environment,
                             @AuthenticationPrincipal SecurityUserDetails user) {
        permissions.assertPermission(user,id,ProjectPermissionType.PROJECT_VIEW);
        projects.findById(id);
        return ApiResponse.ok("프로젝트 롤링 트래픽 연결",Map.of(
            "environment",environment,
            "canManage",permissions.isSuperAdmin(user),
            "bindings",traffic.bindings(id,environment),
            "choices",traffic.choices(id,environment)));
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> save(@PathVariable Long id, @RequestParam DeployEnvironment environment,
                              @Valid @RequestBody SaveForm form,
                              @AuthenticationPrincipal SecurityUserDetails user) {
        permissions.assertPermission(user,id,ProjectPermissionType.PROJECT_VIEW);
        projects.findById(id);
        var bindings = form.bindings().stream().map(selection ->
            new ProjectRolloutRouteRepository.Binding(id,environment,selection.webServerId(),selection.policyName())).toList();
        traffic.save(id,environment,bindings);
        audit.recordCurrentRequired("프로젝트 롤링 트래픽 연결 변경","Project",id.toString(),
            environment + " / " + bindings.stream().map(row -> row.webServerId() + ":" + row.policyName()).toList(),
            AuditResult.SUCCESS);
        return ApiResponse.ok("롤링 트래픽 연결을 저장했습니다. 이후 새 배포 요청부터 적용됩니다.",null);
    }
}
