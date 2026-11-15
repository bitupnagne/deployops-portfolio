package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProjectPermissionService {

    private final ProjectUserPermissionRepository permissionRepository;

    // PROJECT_MANAGER 가 역할만으로 갖는 조회·MR 검토 권한. 배포 설정 변경 권한은 포함하지 않는다.
    // MR_MERGE/MR_APPROVE/*_APPROVE/*_EXECUTE/ROLLBACK_EXECUTE 는 여기에 없으므로
    // 병합·승인·실행은 명시 권한(project_user_permissions) + 승인정책을 따른다.
    private static final Set<ProjectPermissionType> PROJECT_MANAGER_ROLE_GRANTS = EnumSet.of(
        ProjectPermissionType.PROJECT_VIEW,
        ProjectPermissionType.MR_REVIEW
    );

    @Transactional(readOnly = true)
    public boolean hasPermission(
        SecurityUserDetails currentUser,
        Long projectId,
        ProjectPermissionType permission
    ) {
        return hasPermission(currentUser, projectId, permission, ProjectPermissionEnvironment.ALL);
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(
        SecurityUserDetails currentUser,
        Long projectId,
        ProjectPermissionType permission,
        DeployEnvironment environment
    ) {
        return hasPermission(currentUser, projectId, permission, toPermissionEnvironment(environment));
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(
        SecurityUserDetails currentUser,
        Long projectId,
        ProjectPermissionType permission,
        ProjectPermissionEnvironment environment
    ) {
        if (currentUser == null || projectId == null || permission == null) {
            return false;
        }
        if (isSuperAdmin(currentUser)) {
            return true;
        }
        // PROJECT_MANAGER: 프로젝트 조회·MR 검토(PROJECT_VIEW, MR_REVIEW)만 역할로 허용.
        if (isProjectManager(currentUser) && PROJECT_MANAGER_ROLE_GRANTS.contains(permission)) {
            return true;
        }
        ProjectPermissionEnvironment resolvedEnvironment = environment != null
            ? environment
            : ProjectPermissionEnvironment.ALL;
        return permissionRepository.existsActivePermission(
            currentUser.getId(),
            projectId,
            permission,
            resolvedEnvironment
        );
    }

    @Transactional(readOnly = true)
    public List<Long> findPermittedProjectIds(
        SecurityUserDetails currentUser,
        ProjectPermissionType permission,
        ProjectPermissionEnvironment environment
    ) {
        if (currentUser == null || permission == null) {
            return List.of();
        }
        if (isSuperAdmin(currentUser)) {
            return List.of();
        }
        // PROJECT_MANAGER 는 역할 허용 권한 범위에서 전체 프로젝트 대상(빈 리스트=필터 없음).
        if (isProjectManager(currentUser) && PROJECT_MANAGER_ROLE_GRANTS.contains(permission)) {
            return List.of();
        }
        ProjectPermissionEnvironment resolvedEnvironment = environment != null
            ? environment
            : ProjectPermissionEnvironment.ALL;
        return permissionRepository.findProjectIdsByUserAndPermission(
            currentUser.getId(),
            permission,
            resolvedEnvironment
        );
    }

    public void assertPermission(
        SecurityUserDetails currentUser,
        Long projectId,
        ProjectPermissionType permission
    ) {
        if (!hasPermission(currentUser, projectId, permission)) {
            throw forbidden(permission);
        }
    }

    public void assertPermission(
        SecurityUserDetails currentUser,
        Long projectId,
        ProjectPermissionType permission,
        DeployEnvironment environment
    ) {
        if (!hasPermission(currentUser, projectId, permission, environment)) {
            throw forbidden(permission);
        }
    }

    public boolean isSuperAdmin(SecurityUserDetails currentUser) {
        return currentUser != null && "SUPER_ADMIN".equals(currentUser.getRole());
    }

    public boolean isProjectManager(SecurityUserDetails currentUser) {
        return currentUser != null && "PROJECT_MANAGER".equals(currentUser.getRole());
    }

    private ProjectPermissionEnvironment toPermissionEnvironment(DeployEnvironment environment) {
        if (environment == null) {
            return ProjectPermissionEnvironment.ALL;
        }
        return switch (environment) {
            case TEST -> ProjectPermissionEnvironment.TEST;
            case PROD -> ProjectPermissionEnvironment.PROD;
        };
    }

    private ResponseStatusException forbidden(ProjectPermissionType permission) {
        return new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "이 프로젝트에 대한 권한이 없습니다: " + permission.name()
        );
    }
}
