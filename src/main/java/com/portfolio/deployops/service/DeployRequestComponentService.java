package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 배포 요청 작성에 필요한 산출물 식별 정보만 제공한다. 런타임 설정 조회 권한은 부여하지 않는다. */
@Service
@RequiredArgsConstructor
public class DeployRequestComponentService {
    private final ProjectRepository projects;
    private final DeployComponentRepository components;
    private final ProjectPermissionService permissions;

    @Transactional(readOnly = true)
    public List<ComponentSummary> list(Long projectId, DeployEnvironment environment,
                                       DeployScope scope, SecurityUserDetails actor) {
        if (projectId == null || projectId <= 0 || environment == null || scope == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트, 배포 환경과 범위를 지정해 주세요.");
        }
        permissions.assertPermission(actor, projectId, ProjectPermissionType.DEPLOY_REQUEST, environment);
        var project = projects.findById(projectId)
            .filter(Project::isActive)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 프로젝트를 찾을 수 없습니다."));
        return components.findByProjectId(project.getId()).stream()
            .filter(DeployComponent::isActive)
            .filter(component -> (component.getRole() == ServerType.WAS || component.getRole() == ServerType.WEB)
                && scope.includes(component.getRole()))
            .map(component -> new ComponentSummary(component.getId(), component.getCode(),
                component.getDisplayName(), component.getRole(), component.getArtifactType(),
                component.getImageRepository(), component.isActive()))
            .toList();
    }

    public record ComponentSummary(Long id, String code, String displayName, ServerType role,
                                   ArtifactType artifactType, String imageRepository, boolean active) {}
}
