package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.GitlabCiVariableForm;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NexusMavenBuildServiceTest {
    private final NexusDependencyUploadService uploads = mock(NexusDependencyUploadService.class);
    private final NexusMetadataService nexus = mock(NexusMetadataService.class);
    private final GitlabCiVariableService variables = mock(GitlabCiVariableService.class);
    private final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SecurityUserDetails actor = mock(SecurityUserDetails.class);
    private final NexusMavenBuildService service = new NexusMavenBuildService(uploads,nexus,variables,permissions,settings);

    @BeforeEach void setup() throws Exception {
        when(uploads.binding(7L)).thenReturn(new NexusDependencyUploadService.Binding("project-hosted","maven2","com/acme"));
        when(nexus.listRepositories("maven2")).thenReturn(List.of(
            Map.of("name","maven-public","type","group"), Map.of("name","external-proxy","type","proxy")));
        when(nexus.runnerMavenBaseUrl()).thenReturn("https://nexus.internal");
        when(variables.list(7L,actor)).thenReturn(new GitlabCiVariableService.View(true,List.of(
            new GitlabCiVariableService.Metadata("NEXUS_MAVEN_USER",true,false,true,"env_var","*"),
            new GitlabCiVariableService.Metadata("NEXUS_MAVEN_PASSWORD",true,true,true,"env_var","*"))));
    }

    @Test void previewConnectsWholeMavenResolutionToProjectGroupWithoutCredentialsOrMutation() throws Exception {
        var plan=service.preview(7L,"maven-public",actor);
        assertThat(plan.groupName()).startsWith("deploy-p7-");
        assertThat(plan.settingsXml()).contains("<mirrorOf>*</mirrorOf>",plan.mirrorUrl(),
            "${env.NEXUS_MAVEN_USER}","${env.NEXUS_MAVEN_PASSWORD}");
        verify(permissions).assertPermission(actor,7L,ProjectPermissionType.CI_VARIABLE_MANAGE);
        verify(nexus,never()).ensureProjectMavenGroup(anyString(),anyString(),anyString());
        verifyNoInteractions(variables);
    }

    @Test void applyCreatesGroupThenUsesExistingProtectedFileVariablePathAndVerifiesActualValue() throws Exception {
        when(variables.matchesMavenSettings(eq(7L),anyString(),eq(actor))).thenReturn(true);
        var result=service.apply(7L,"maven-public","project-hosted","의존성 반입 빌드 연결",actor,"192.0.2.10");
        assertThat(result.settingsMatch()).isTrue();assertThat(result.credentialsConfigured()).isTrue();
        var order=inOrder(nexus,variables);
        order.verify(nexus).ensureProjectMavenGroup(startsWith("deploy-p7-"),eq("project-hosted"),eq("maven-public"));
        var form=ArgumentCaptor.forClass(GitlabCiVariableForm.class);
        order.verify(variables).put(eq(7L),eq("MAVEN_SETTINGS_FILE"),form.capture(),eq(actor),eq("192.0.2.10"));
        order.verify(nexus).assertProjectMavenGroup(startsWith("deploy-p7-"),eq(List.of("project-hosted","maven-public")));
        order.verify(variables).matchesMavenSettings(7L,form.getValue().value(),actor);
        assertThat(form.getValue().value()).contains("<mirrorOf>*</mirrorOf>");
        verify(settings).saveAll(eq("nexus-project-repositories"),eq(Map.of("nexus.dependency.7.maven-common","maven-public")));
    }

    @Test void changedBindingCannotPublishStalePreview() throws Exception {
        assertThatThrownBy(()->service.apply(7L,"maven-public","old-hosted","change",actor,"ip"))
            .hasMessageContaining("변경됐습니다");
        verifyNoInteractions(variables);
        verify(nexus,never()).ensureProjectMavenGroup(anyString(),anyString(),anyString());
    }

    @Test void rawAndDirectProxyAreNotPresentedAsSupportedMavenBuildIntegration() {
        assertThatThrownBy(()->service.preview(7L,"external-proxy",actor)).hasMessageContaining("hosted 또는 group");
        when(uploads.binding(7L)).thenReturn(new NexusDependencyUploadService.Binding("raw","raw",""));
        assertThatThrownBy(()->service.preview(7L,"maven-public",actor)).hasMessageContaining("Maven2");
        verifyNoInteractions(variables);
    }

    @Test void remoteVariableMismatchIsNeverReportedAsBuildReady() throws Exception {
        when(variables.matchesMavenSettings(eq(7L),anyString(),eq(actor))).thenReturn(false);
        var result=service.verify(7L,"maven-public",actor);
        assertThat(result.settingsMatch()).isFalse();
        assertThat(result.message()).contains("준비되지 않았습니다");
    }

    @Test void runnerEndpointIsUsedForPreviewApplyAndVerifyWithoutChangingManagementCalls() throws Exception {
        when(nexus.runnerMavenBaseUrl()).thenReturn("http://10.250.0.240:8081");
        var plan = service.preview(7L, "maven-public", actor);
        assertThat(plan.mirrorUrl()).startsWith("http://10.250.0.240:8081/repository/deploy-p7-");
        service.apply(7L, "maven-public", "project-hosted", "Runner 주소 분리", actor, "ip");
        verify(variables).put(eq(7L), eq("MAVEN_SETTINGS_FILE"),
            argThat(form -> form.value().contains(plan.mirrorUrl())), eq(actor), eq("ip"));
        verify(variables).matchesMavenSettings(7L, plan.settingsXml(), actor);
        verify(nexus).ensureProjectMavenGroup(plan.groupName(), "project-hosted", "maven-public");
        verify(nexus, never()).dependencyBaseUrl();
    }
}
