package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProjectPermissionServiceTest {

    @Mock
    private ProjectUserPermissionRepository permissionRepository;

    @InjectMocks
    private ProjectPermissionService permissionService;

    @Test
    void superAdminBypassesProjectPermissionLookup() {
        SecurityUserDetails admin = user(1L, UserRole.SUPER_ADMIN);

        assertThat(permissionService.hasPermission(admin, 10L, ProjectPermissionType.PROD_EXECUTE)).isTrue();
        verify(permissionRepository, never()).existsActivePermission(
            1L,
            10L,
            ProjectPermissionType.PROD_EXECUTE,
            ProjectPermissionEnvironment.ALL
        );
    }

    @Test
    void userPermissionCanBeEnvironmentScoped() {
        SecurityUserDetails operator = user(2L, UserRole.OPERATOR);
        when(permissionRepository.existsActivePermission(
            2L,
            10L,
            ProjectPermissionType.PROD_EXECUTE,
            ProjectPermissionEnvironment.PROD
        )).thenReturn(true);

        assertThat(permissionService.hasPermission(
            operator,
            10L,
            ProjectPermissionType.PROD_EXECUTE,
            DeployEnvironment.PROD
        )).isTrue();
    }

    @Test
    void assertPermissionRejectsMissingProjectPermission() {
        SecurityUserDetails operator = user(2L, UserRole.OPERATOR);

        assertThatThrownBy(() -> permissionService.assertPermission(
            operator,
            10L,
            ProjectPermissionType.PROD_EXECUTE
        )).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("PROD_EXECUTE");
    }

    private SecurityUserDetails user(Long id, UserRole role) {
        return new SecurityUserDetails(User.builder()
            .id(id)
            .loginId("user" + id)
            .password("{noop}password")
            .name("사용자" + id)
            .role(role)
            .active(true)
            .build());
    }
}
