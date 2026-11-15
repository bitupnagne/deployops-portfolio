package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.GitlabCiVariableDeleteForm;
import com.portfolio.deployops.dto.GitlabCiVariableForm;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class GitlabCiVariableServiceTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectUserPermissionRepository grants = mock(ProjectUserPermissionRepository.class);
    private final ProjectPermissionService permissions = new ProjectPermissionService(grants);
    private final GitlabService gitlab = mock(GitlabService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final GitlabCiVariableService service = new GitlabCiVariableService(projects, permissions, gitlab, audit);
    private final SecurityUserDetails manager = actor(UserRole.PROJECT_MANAGER);
    private final String secret = "sentinel-secret-password";

    @BeforeEach
    void setUp() {
        lenient().when(projects.findById(3L)).thenReturn(Optional.of(Project.builder().id(3L).active(true).gitlabProjectId(91L).build()));
        lenient().when(gitlab.resolveAccessToken(null, "PROJECT_MANAGER", "qa-manager")).thenReturn("private-pat");
        lenient().when(gitlab.resolveReadonlyCiAccessToken(null)).thenReturn("private-pat");
    }

    @Test
    void managerCanViewButCannotManageWithoutExplicitGrantAndMetadataNeverContainsValues() throws Exception {
        when(gitlab.getProjectVariables("private-pat", null, 91L)).thenReturn(List.of(
            Map.of("key", "NEXUS_REGISTRY_PASSWORD", "environmentScope", "*", "hasValue", true,
                "masked", true, "protected", true, "variableType", "env_var", "value", secret),
            Map.of("key", "CI_BUILD_IMAGE", "environmentScope", "production", "hasValue", true,
                "masked", false, "protected", false, "variableType", "env_var")));
        var view = service.list(3L, manager);
        assertThat(view.canManage()).isFalse();
        assertThat(view.variables()).hasSize(9);
        assertThat(view.variables().stream().filter(v -> v.key().equals("NEXUS_REGISTRY_PASSWORD")).findFirst().orElseThrow().configured()).isTrue();
        assertThat(view.variables().stream().filter(v -> v.key().equals("CI_BUILD_IMAGE")).findFirst().orElseThrow().configured()).isFalse();
        String serialized = JsonMapper.builder().build().writeValueAsString(view);
        assertThat(serialized).doesNotContain(secret, "\"value\":", "private-pat");
        assertThat(serialized).contains("\"protected\":true");
    }

    @Test
    void requiredMetadataUsesHydratedComponentToolsAndRuntimeDockerfileUnion() throws Exception {
        var components = mock(com.portfolio.deployops.repository.DeployComponentRepository.class);
        service.configureComponents(components);
        when(projects.findById(3L)).thenReturn(Optional.of(Project.builder().id(3L).active(true)
            .gitlabProjectId(91L).buildTool(BuildToolType.MAVEN).dockerfilePath("Dockerfile.api").build()));
        when(components.findByProjectId(3L)).thenReturn(List.of(
            DeployComponent.builder().id(1L).active(true).artifactType(ArtifactType.CONTAINER)
                .buildTool(BuildToolType.MAVEN).dockerfilePath("Dockerfile.release").build(),
            DeployComponent.builder().id(2L).active(true).artifactType(ArtifactType.STATIC_BUNDLE)
                .buildTool(BuildToolType.STATIC).dockerfilePath("Dockerfile.web").build(),
            DeployComponent.builder().id(3L).active(false).artifactType(ArtifactType.CONTAINER).build()));
        when(gitlab.getProjectVariables("private-pat", null, 91L)).thenReturn(List.of());
        var view = service.list(3L, manager);
        assertThat(view.requiredVariables()).contains("MAVEN_BUILD_IMAGE","CI_BUILD_IMAGE",
            "MAVEN_SETTINGS_FILE","NEXUS_MAVEN_USER","NEXUS_MAVEN_PASSWORD","RUNTIME_BASE_IMAGE")
            .doesNotHaveDuplicates();
        assertThat(view.canManage()).isFalse();
        assertThat(view.variables()).hasSize(9);
        verify(components).findByProjectId(3L);
    }

    @Test
    void managerRoleAloneCannotSaveOrDeleteAndDeniedRequestNeverReachesGitlab() {
        assertThatThrownBy(() -> service.put(3L, "CI_BUILD_IMAGE", new GitlabCiVariableForm("image:1", "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm("사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(projects, gitlab, audit);
    }

    @Test
    void otherProjectGrantDoesNotAuthorizeCurrentProject() {
        when(grants.existsActivePermission(5L, 4L, ProjectPermissionType.CI_VARIABLE_MANAGE, ProjectPermissionEnvironment.ALL)).thenReturn(true);
        assertThat(permissions.hasPermission(manager, 4L, ProjectPermissionType.CI_VARIABLE_MANAGE)).isTrue();
        assertThatThrownBy(() -> service.put(3L, "CI_BUILD_IMAGE", new GitlabCiVariableForm("image:1", "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(projects, gitlab, audit);
    }

    @Test
    void viewerPermissionIsCheckedBeforeLoadingProjectOrTokens() {
        assertThatThrownBy(() -> service.list(3L, actor(UserRole.DEVELOPER)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> service.list(3L, null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(projects, gitlab, audit);
    }

    @Test
    void explicitManagePermissionAuditsIntentBeforeRemoteChangeAndResultAfterwards() {
        permit();
        service.put(3L, "NEXUS_REGISTRY_PASSWORD", new GitlabCiVariableForm(secret, "암호 교체"), manager, "127.0.0.1");
        var order = inOrder(audit, gitlab);
        order.verify(gitlab).resolveAccessToken(null, "PROJECT_MANAGER", "qa-manager");
        order.verify(audit).record(eq("담당자"), eq("CI_VARIABLE_UPSERT_REQUEST"), eq("GitlabCiVariable"), eq("3:NEXUS_REGISTRY_PASSWORD"),
            eq("actorId=5 projectId=3 key=NEXUS_REGISTRY_PASSWORD reason=암호 교체"), eq(AuditResult.SUCCESS), eq("127.0.0.1"));
        order.verify(gitlab).upsertProjectVariable("private-pat", null, 91L, "NEXUS_REGISTRY_PASSWORD", secret);
        order.verify(audit).record(eq("담당자"), eq("CI_VARIABLE_UPSERT"), eq("GitlabCiVariable"), eq("3:NEXUS_REGISTRY_PASSWORD"),
            argThat(desc -> !desc.contains(secret)), eq(AuditResult.SUCCESS), eq("127.0.0.1"));
    }

    @Test
    void explicitManageGrantIsReflectedInCanManage() throws Exception {
        permit();
        when(gitlab.getProjectVariables("private-pat", null, 91L)).thenReturn(List.of());
        assertThat(service.list(3L, manager).canManage()).isTrue();
    }

    @Test
    void superAdminRetainsExistingAllProjectPermissionRule() {
        var admin = actor(UserRole.SUPER_ADMIN);
        when(gitlab.resolveAccessToken(null, "SUPER_ADMIN", "qa-manager")).thenReturn("admin-personal-pat");
        service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm("설정 정리"), admin, "ip");
        verify(gitlab).deleteProjectVariable("admin-personal-pat", null, 91L, "CI_BUILD_IMAGE");
        verifyNoInteractions(grants);
    }

    @Test
    void failedIntentAuditPreventsRemoteMutation() {
        permit();
        doThrow(new IllegalStateException(secret)).when(audit).record(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.put(3L, "NEXUS_REGISTRY_PASSWORD", new GitlabCiVariableForm(secret, "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503").hasMessageNotContaining(secret).hasNoCause();
        verify(gitlab, never()).upsertProjectVariable(any(), any(), anyLong(), any(), any());
    }

    @Test
    void failedResultAuditDoesNotReturnSuccessOrRepeatRemoteMutation() {
        permit();
        doThrow(new IllegalStateException(secret)).when(audit).record(any(), eq("CI_VARIABLE_DELETE"), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm("정리"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503").hasMessageNotContaining(secret).hasNoCause();
        verify(gitlab, times(1)).deleteProjectVariable("private-pat", null, 91L, "CI_BUILD_IMAGE");
    }

    @Test
    void unclassifiedUpstreamFailureIsUnknownAndDetailsAreNotReturnedOrStoredInAudit() {
        permit();
        doThrow(new IllegalStateException("echoed upstream value=" + secret)).when(gitlab)
            .upsertProjectVariable(any(), any(), anyLong(), any(), any());
        assertThatThrownBy(() -> service.put(3L, "NEXUS_REGISTRY_PASSWORD", new GitlabCiVariableForm(secret, "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("504")
            .hasMessageContaining("이미 적용되었을 수").hasMessageNotContaining(secret).hasNoCause();
        verify(audit).record(any(), eq("CI_VARIABLE_UPSERT"), any(), any(), argThat(s -> !s.contains(secret)), eq(AuditResult.UNKNOWN), any());
    }

    @Test
    void timeoutRecordsUnknownAndMetadataRequeryDoesNotPretendItVerifiedTheSecretValue() throws Exception {
        permit();
        doThrow(new GitlabCiVariableMutationException(true)).when(gitlab)
            .upsertProjectVariable(any(), any(), anyLong(), any(), any());
        assertThatThrownBy(() -> service.put(3L, "NEXUS_REGISTRY_PASSWORD", new GitlabCiVariableForm(secret, "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("504").hasMessageContaining("재전송 전에");
        when(gitlab.getProjectVariables("private-pat", null, 91L)).thenReturn(List.of(Map.of(
            "key", "NEXUS_REGISTRY_PASSWORD", "environmentScope", "*", "hasValue", true,
            "masked", true, "protected", true, "variableType", "env_var")));
        assertThat(service.list(3L, manager).variables().get(1).configured()).isTrue();
        verify(audit).record(any(), eq("CI_VARIABLE_UPSERT"), any(), any(), any(), eq(AuditResult.UNKNOWN), any());
        verify(audit, never()).record(any(), eq("CI_VARIABLE_UPSERT"), any(), any(), any(), eq(AuditResult.SUCCESS), any());
        verify(audit, never()).record(any(), eq("CI_VARIABLE_UPSERT"), any(), any(), any(), eq(AuditResult.FAIL), any());
        verify(gitlab, times(1)).upsertProjectVariable(any(), any(), anyLong(), any(), any());
    }

    @Test
    void unknownDeleteIsNotRecordedAsFailureOrAutomaticallyRetried() {
        permit();
        doThrow(new GitlabCiVariableMutationException(true)).when(gitlab).deleteProjectVariable(any(), any(), anyLong(), any());
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm("정리"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("504").hasMessageContaining("이미 적용되었을 수");
        verify(audit).record(any(), eq("CI_VARIABLE_DELETE"), any(), any(), any(), eq(AuditResult.UNKNOWN), any());
        verify(audit, never()).record(any(), eq("CI_VARIABLE_DELETE"), any(), any(), any(), eq(AuditResult.FAIL), any());
        verify(gitlab, times(1)).deleteProjectVariable(any(), any(), anyLong(), any());
    }

    @Test
    void knownAdminSystemTokenRejectionGuidesConfigurationWithoutUnknownOutcomeOrSecrets() {
        permit();
        doThrow(new GitlabAdminSystemTokenException()).when(gitlab)
            .upsertProjectVariable(any(), any(), anyLong(), any(), any());
        doThrow(new GitlabAdminSystemTokenException()).when(gitlab)
            .deleteProjectVariable(any(), any(), anyLong(), any());
        assertThatThrownBy(() -> service.put(3L, "NEXUS_REGISTRY_PASSWORD",
            new GitlabCiVariableForm(secret, "설정 확인"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400")
            .hasMessageContaining("비관리자 전용 계정 PAT").hasMessageContaining("Maintainer")
            .hasMessageContaining("변경되지 않았습니다").hasMessageNotContaining(secret)
            .hasMessageNotContaining("private-pat").hasNoCause();
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE",
            new GitlabCiVariableDeleteForm("설정 확인"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400")
            .hasMessageContaining("시스템 연동 설정").hasNoCause();
        verify(audit).record(any(), eq("CI_VARIABLE_UPSERT"), any(), any(), any(), eq(AuditResult.FAIL), any());
        verify(audit).record(any(), eq("CI_VARIABLE_DELETE"), any(), any(), any(), eq(AuditResult.FAIL), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), eq(AuditResult.UNKNOWN), any());
    }

    @Test
    void confirmedPreflightFailureOrRejectionStillRecordsFail() {
        permit();
        doThrow(new GitlabCiVariableMutationException(false)).when(gitlab).deleteProjectVariable(any(), any(), anyLong(), any());
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm("정리"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("502");
        verify(audit).record(any(), eq("CI_VARIABLE_DELETE"), any(), any(), any(), eq(AuditResult.FAIL), any());
    }

    @Test
    void accidentalExactValueInReasonIsRedactedBeforeAuditing() {
        permit();
        service.put(3L, "NEXUS_REGISTRY_PASSWORD", new GitlabCiVariableForm(secret, "변경 " + secret), manager, "ip");
        verify(audit, times(2)).record(any(), any(), any(), any(), argThat(s -> !s.contains(secret) && s.contains("[REDACTED]")), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITLAB_TOKEN", "NEXUS_REGISTRY_PASSWORD/evil", "nexus_registry_user", ""})
    void onlySupportedKeysCanBeMutated(String key) {
        permit();
        assertThatThrownBy(() -> service.put(3L, key, new GitlabCiVariableForm(secret, "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.delete(3L, key, new GitlabCiVariableDeleteForm("사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(projects, gitlab, audit);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "short", "password with spaces", "secret\nline"})
    void maskedPasswordValidationRejectsUnsafeValuesWithoutEcho(String value) {
        permit();
        assertThatThrownBy(() -> service.put(3L, "NEXUS_REGISTRY_PASSWORD", new GitlabCiVariableForm(value, "사유"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(projects, gitlab, audit);
    }

    @Test
    void excessiveValueAndMissingOrLongReasonAreRejected() {
        permit();
        for (var form : List.of(new GitlabCiVariableForm("x".repeat(10001), "사유"),
            new GitlabCiVariableForm(secret, " "), new GitlabCiVariableForm(secret, "x".repeat(201)))) {
            assertThatThrownBy(() -> service.put(3L, "NEXUS_REGISTRY_PASSWORD", form, manager, "ip"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm(" "), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(projects, gitlab, audit);
    }

    @Test
    void incrementalEnumMigrationPreservesEveryExistingValueInOrderAndGrantsNothing() throws Exception {
        String baseline = Files.readString(Path.of("src/main/resources/db/migration/V1__baseline.sql"));
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V23__ci_variable_manage_permission.sql"));
        String oldValues = "'PROJECT_VIEW','DEPLOY_REQUEST','MR_REVIEW','MR_APPROVE','MR_MERGE','TEST_APPROVE','PROD_APPROVE','TEST_EXECUTE','PROD_EXECUTE','ROLLBACK_EXECUTE'";
        assertThat(baseline).contains("`permission` enum(" + oldValues + ")");
        assertThat(migration).contains("ENUM(" + oldValues + ",'CI_VARIABLE_MANAGE') NOT NULL");
        assertThat(migration.toUpperCase()).doesNotContain("INSERT", "DELETE", "UPDATE ");
    }

    @Test
    void unknownAuditMigrationPreservesHistoricalResultEnumValuesWithoutChangingRows() throws Exception {
        String baseline = Files.readString(Path.of("src/main/resources/db/migration/V1__baseline.sql"));
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V24__audit_unknown_outcome.sql"));
        assertThat(baseline).contains("`result` enum('FAIL','SUCCESS') DEFAULT NULL");
        assertThat(migration).contains("ENUM('FAIL','SUCCESS','UNKNOWN') DEFAULT NULL");
        assertThat(migration.toUpperCase()).doesNotContain("INSERT", "DELETE", "UPDATE ");
    }

    @Test
    void absentValidatedReadonlyTokenRefusesMetadataWithoutRemoteLookup() throws Exception {
        when(gitlab.resolveReadonlyCiAccessToken(null)).thenReturn(null);
        assertThatThrownBy(() -> service.list(3L, manager))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("GitLab 토큰");
        verify(gitlab, never()).getProjectVariables(any(), any(), anyLong());
        verify(gitlab, never()).resolveAccessToken(any(), any(), any());
    }

    @Test
    void readonlyBotDoesNotAuthorizeVariableMutationOrReplacePersonalMutationToken() {
        permit();
        when(gitlab.resolveAccessToken(null, "PROJECT_MANAGER", "qa-manager")).thenReturn(null);
        assertThatThrownBy(() -> service.delete(3L, "CI_BUILD_IMAGE", new GitlabCiVariableDeleteForm("정리"), manager, "ip"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("GitLab 토큰");
        verify(gitlab, never()).resolveReadonlyCiAccessToken(any());
        verify(gitlab, never()).deleteProjectVariable(any(), any(), anyLong(), any());
    }

    private void permit() {
        when(grants.existsActivePermission(5L, 3L, ProjectPermissionType.CI_VARIABLE_MANAGE, ProjectPermissionEnvironment.ALL)).thenReturn(true);
    }
    private SecurityUserDetails actor(UserRole role) {
        return new SecurityUserDetails(User.builder().id(5L).loginId("qa-manager").name("담당자").role(role).active(true).build());
    }
}
