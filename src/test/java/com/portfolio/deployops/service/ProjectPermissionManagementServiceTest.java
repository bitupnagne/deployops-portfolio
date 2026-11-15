package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ProjectPermissionForm;
import com.portfolio.deployops.dto.ProjectPermissionForm.Grant;
import com.portfolio.deployops.repository.*;
import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

class ProjectPermissionManagementServiceTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProjectUserPermissionRepository permissions = mock(ProjectUserPermissionRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ProjectPermissionManagementService service = new ProjectPermissionManagementService(projects, users, permissions, audit);
    private final User admin = user(1L, UserRole.SUPER_ADMIN);
    private final User target = user(2L, UserRole.DEVELOPER);
    private final SecurityUserDetails principal = new SecurityUserDetails(user(1L, UserRole.SUPER_ADMIN));
    private final Project project = Project.builder().id(10L).name("서비스 A").active(true).build();
    private final List<ProjectUserPermission> rows = new ArrayList<>();
    private static final Grant TEST_REQUEST = grant(ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.TEST);

    @BeforeEach
    void repositories() {
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        when(users.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(projects.lockById(10L)).thenReturn(10L);
        when(permissions.findByProjectIdAndUserId(10L, 2L)).thenAnswer(call -> List.copyOf(rows));
        when(permissions.findByProjectIdAndUserIdForUpdate(10L, 2L)).thenAnswer(call -> List.copyOf(rows));
        when(permissions.insert(any())).thenAnswer(call -> { rows.add(call.getArgument(0)); return 1; });
        when(permissions.setActive(eq(10L), eq(2L), any(), any(), anyBoolean())).thenAnswer(call -> {
            rows.stream().filter(row -> row.getPermission() == call.getArgument(2) && row.getEnvironment() == call.getArgument(3))
                .forEach(row -> row.setActive(call.getArgument(4)));
            return 1;
        });
    }

    @Test
    void grantsAreScopedAuditedAndImmediatelyEffective() {
        var result = service.replace(10L, 2L, form(List.of(TEST_REQUEST)), principal, "127.0.0.1");
        assertThat(result.rawPermissions()).singleElement().satisfies(row -> {
            assertThat(row.grant()).isEqualTo(TEST_REQUEST); assertThat(row.active()).isTrue();
        });
        assertThat(result.effectivePermissions()).extracting(row -> row.grant()).containsExactly(TEST_REQUEST);
        verify(audit).record(eq("user-1"), eq("프로젝트 권한 변경"), eq("ProjectUserPermission"), eq("10:2"),
            argThat(desc -> desc.contains("actorId=1 projectId=10 userId=2") && desc.contains("environment=TEST active=false->true reason=담당 업무 변경")),
            eq(AuditResult.SUCCESS), eq("127.0.0.1"));
    }

    @Test
    void legacyAllRemainsRawAndIsNotExpandedDuringSave() {
        Grant legacy = grant(ProjectPermissionType.TEST_EXECUTE, ProjectPermissionEnvironment.ALL);
        add(legacy, true);
        var result = service.replace(10L, 2L, form(List.of(legacy, TEST_REQUEST)), principal, "127.0.0.1");
        assertThat(result.rawPermissions()).extracting(row -> row.grant()).containsExactlyInAnyOrder(legacy, TEST_REQUEST);
        assertThat(result.effectivePermissions()).extracting(row -> row.grant()).contains(grant(ProjectPermissionType.TEST_EXECUTE, ProjectPermissionEnvironment.TEST));
        verify(permissions, never()).setActive(anyLong(), anyLong(), any(), any(), anyBoolean());
        verify(permissions, times(1)).insert(any());
    }

    @Test
    void twoEnvironmentRowsDoNotBecomeAllPipelinePermission() {
        add(TEST_REQUEST, true);
        add(grant(ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.PROD), true);
        assertThat(service.get(10L, 2L, principal).effectivePermissions()).extracting(row -> row.grant())
            .doesNotContain(grant(ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.ALL));
    }

    @Test
    void revokeThenRegrantUsesExistingUniqueRow() {
        add(TEST_REQUEST, true);
        service.replace(10L, 2L, form(List.of()), principal, "127.0.0.1");
        assertThat(rows.getFirst().isActive()).isFalse();
        service.replace(10L, 2L, form(List.of(TEST_REQUEST)), principal, "127.0.0.1");
        assertThat(rows).hasSize(1); assertThat(rows.getFirst().isActive()).isTrue();
        verify(permissions, never()).insert(any());
        verify(audit, times(2)).record(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"PROJECT_MANAGER", "DEVELOPER", "OPERATOR", "APPROVER"})
    void nonAdminsCannotReadOrWrite(UserRole role) {
        var other = new SecurityUserDetails(user(3L, role));
        denied(HttpStatus.FORBIDDEN, () -> service.get(10L, 2L, other));
        denied(HttpStatus.FORBIDDEN, () -> service.replace(10L, 2L, new ProjectPermissionForm(List.of(), "사유", "a".repeat(64)), other, "ip"));
        verifyNoInteractions(audit);
    }

    @Test
    void staleSessionCannotManageAfterAdminRoleIsRevoked() {
        var form = form(List.of(TEST_REQUEST));
        admin.setRole(UserRole.DEVELOPER);
        denied(HttpStatus.FORBIDDEN, () -> service.get(10L, 2L, principal));
        denied(HttpStatus.FORBIDDEN, () -> service.replace(10L, 2L, form, principal, "ip"));
        verify(permissions, never()).insert(any()); verifyNoInteractions(audit);
    }

    @Test
    void inactiveAdminCannotReadOrWriteWithOldSession() {
        var form = form(List.of(TEST_REQUEST)); admin.setActive(false);
        denied(HttpStatus.FORBIDDEN, () -> service.projectUsers(10L, principal));
        denied(HttpStatus.FORBIDDEN, () -> service.byUser(2L, principal));
        denied(HttpStatus.FORBIDDEN, () -> service.replace(10L, 2L, form, principal, "ip"));
        verifyNoInteractions(audit);
    }

    @Test
    void selfModificationIsForbiddenEvenForSuperAdmin() {
        denied(HttpStatus.FORBIDDEN, () -> service.replace(10L, 1L, form(List.of()), principal, "ip"));
        verify(users, never()).findByIdForUpdate(anyLong()); verifyNoInteractions(audit);
    }

    @Test
    void conflictingRevisionAndTargetRoleChangeRejectWithoutWrites() {
        var stale = form(List.of(TEST_REQUEST));
        add(grant(ProjectPermissionType.PROJECT_VIEW, ProjectPermissionEnvironment.ALL), true);
        denied(HttpStatus.CONFLICT, () -> service.replace(10L, 2L, stale, principal, "ip"));
        var beforeRole = form(List.of(TEST_REQUEST)); target.setRole(UserRole.OPERATOR);
        denied(HttpStatus.CONFLICT, () -> service.replace(10L, 2L, beforeRole, principal, "ip"));
        verify(permissions, never()).insert(any()); verifyNoInteractions(audit);
    }

    @Test
    void inactiveTargetAllowsRevokeButNoNewGrant() {
        add(TEST_REQUEST, true); target.setActive(false);
        denied(HttpStatus.BAD_REQUEST, () -> service.replace(10L, 2L, form(List.of(TEST_REQUEST,
            grant(ProjectPermissionType.PROJECT_VIEW, ProjectPermissionEnvironment.ALL))), principal, "ip"));
        var result = service.replace(10L, 2L, form(List.of()), principal, "ip");
        assertThat(result.effectivePermissions()).isEmpty(); assertThat(result.canGrant()).isFalse();
        assertThat(rows.getFirst().isActive()).isFalse();
    }

    @Test
    void superAdminAndInactiveProjectCannotReceiveNewDirectGrants() {
        target.setRole(UserRole.SUPER_ADMIN);
        denied(HttpStatus.BAD_REQUEST, () -> service.replace(10L, 2L, form(List.of(TEST_REQUEST)), principal, "ip"));
        target.setRole(UserRole.DEVELOPER); project.setActive(false);
        denied(HttpStatus.BAD_REQUEST, () -> service.replace(10L, 2L, form(List.of(TEST_REQUEST)), principal, "ip"));
        verifyNoInteractions(audit);
    }

    @Test
    void pmImplicitRightsAndApproverEligibilityAreSeparateFromDirectGrants() {
        target.setRole(UserRole.PROJECT_MANAGER);
        var pm = service.get(10L, 2L, principal);
        assertThat(pm.rawPermissions()).isEmpty();
        assertThat(pm.effectivePermissions()).extracting(row -> row.grant().permission())
            .containsExactly(ProjectPermissionType.PROJECT_VIEW, ProjectPermissionType.MR_REVIEW);
        target.setRole(UserRole.APPROVER);
        var approver = service.get(10L, 2L, principal);
        assertThat(approver.effectivePermissions()).isEmpty();
        assertThat(approver.roleNotes()).anyMatch(note -> note.contains("전역 배포 승인 자격"));
    }

    @Test
    void invalidCombinationsDuplicatesAndOmittedPayloadCannotRevoke() {
        denied(HttpStatus.BAD_REQUEST, () -> service.replace(10L, 2L, form(List.of(
            grant(ProjectPermissionType.TEST_EXECUTE, ProjectPermissionEnvironment.PROD))), principal, "ip"));
        denied(HttpStatus.BAD_REQUEST, () -> service.replace(10L, 2L, form(List.of(TEST_REQUEST, TEST_REQUEST)), principal, "ip"));
        denied(HttpStatus.BAD_REQUEST, () -> service.replace(10L, 2L, new ProjectPermissionForm(null, "사유", "a".repeat(64)), principal, "ip"));
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailurePropagatesThroughTransactionalProxyAndRollsBackJdbcTransaction() throws Exception {
        DataSource dataSource = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        var transactional = (ProjectPermissionManagementService) factory.getProxy();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> transactional.replace(10L, 2L, form(List.of(TEST_REQUEST)), principal, "ip"))
            .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
        verify(permissions).insert(any()); verify(connection).rollback(); verify(connection, never()).commit();
    }

    @Test
    void mapperLocksAndUpdatesOnlyTheExactProjectUserPermissionEnvironmentTuple() {
        Configuration configuration = new Configuration(); configuration.addMapper(ProjectUserPermissionRepository.class);
        var values = Map.of("projectId", 10L, "userId", 2L, "permission", ProjectPermissionType.DEPLOY_REQUEST,
            "environment", ProjectPermissionEnvironment.TEST, "active", false);
        String prefix = ProjectUserPermissionRepository.class.getName() + ".";
        var update = configuration.getMappedStatement(prefix + "setActive").getBoundSql(values);
        assertThat(update.getSql().replaceAll("\\s+", " ")).contains("WHERE project_id = ? AND user_id = ? AND permission = ? AND environment = ?");
        assertThat(update.getParameterMappings()).extracting(mapping -> mapping.getProperty())
            .containsExactly("active", "projectId", "userId", "permission", "environment");
        assertThat(configuration.getMappedStatement(prefix + "findByProjectIdAndUserIdForUpdate").getBoundSql(values).getSql())
            .contains("pup.project_id = ? AND pup.user_id = ?").endsWith("FOR UPDATE");
    }

    private ProjectPermissionForm form(List<Grant> grants) {
        return new ProjectPermissionForm(grants, "담당 업무 변경", service.get(10L, 2L, principal).revision());
    }
    private void add(Grant grant, boolean active) {
        rows.add(ProjectUserPermission.builder().id((long) rows.size() + 1).project(project).user(target)
            .permission(grant.permission()).environment(grant.environment()).active(active).build());
    }
    private static User user(Long id, UserRole role) { return User.builder().id(id).loginId("login-" + id).name("user-" + id).role(role).active(true).build(); }
    private static Grant grant(ProjectPermissionType permission, ProjectPermissionEnvironment environment) { return new Grant(permission, environment); }
    private static void denied(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
}
