package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.DeployComponentForm;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real MVC JSON binding + method security, including old clients that omit new fields. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DeployComponentBuildSettingsApiTest.Config.class)
@WithMockUser(roles = "SUPER_ADMIN")
class DeployComponentBuildSettingsApiTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean DeployComponentRepository components() { return mock(DeployComponentRepository.class); }
        @Bean ProjectRepository projects() { return mock(ProjectRepository.class); }
        @Bean AuditLogService audits() { return mock(AuditLogService.class); }
        @Bean DeployComponentApiController controller(DeployComponentRepository components,
            ProjectRepository projects, AuditLogService audits) {
            return new DeployComponentApiController(components, projects, mock(ProjectPermissionService.class), audits);
        }
    }
    @Autowired DeployComponentApiController controller;
    @Autowired DeployComponentRepository components;
    @Autowired ProjectRepository projects;
    @Autowired AuditLogService audits;
    MockMvc mvc;
    static final String BASE = """
        {"code":"web","role":"WEB","artifactType":"STATIC_BUNDLE","webRoot":"/var/www/c",
         "imageRepository":"registry.local/c/web"%s}
        """;
    @BeforeEach void setup() {
        reset(components, projects, audits);
        when(projects.lockById(7L)).thenReturn(7L);
        when(components.save(any())).thenAnswer(call -> {
            DeployComponent c=call.getArgument(0); if(c.getId()==null)c.setId(21L); return c;
        });
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    DeployComponent saved() {
        ArgumentCaptor<DeployComponent> capture=ArgumentCaptor.forClass(DeployComponent.class);
        verify(components).save(capture.capture());
        return capture.getValue();
    }
    DeployComponent existing() {
        DeployComponent c=DeployComponent.builder().id(21L).projectId(7L).code("web").role(ServerType.WEB)
            .artifactType(ArtifactType.STATIC_BUNDLE).buildTool(BuildToolType.STATIC)
            .dockerfilePath("Dockerfile.web").dockerContextPath(".").buildCommand("mkdir -p web/dist")
            .artifactPath("web/dist/").build();
        when(components.findById(21L)).thenReturn(Optional.of(c));
        return c;
    }
    @Test void createsBothBuildPathsAndCommandThroughActualJsonForm() throws Exception {
        mvc.perform(post("/api/v1/projects/7/components").contentType("application/json").content(BASE.formatted("""
            ,"buildTool":"STATIC","dockerfilePath":"Dockerfile.web","dockerContextPath":".",
             "buildCommand":"mkdir -p web/dist && cp web/index.html web/dist/","artifactPath":"web/dist/"
            """))).andExpect(status().isCreated());
        DeployComponent c=saved();
        assertThat(c.getBuildTool()).isEqualTo(BuildToolType.STATIC);
        assertThat(c.getDockerfilePath()).isEqualTo("Dockerfile.web");
        assertThat(c.getDockerContextPath()).isEqualTo(".");
        assertThat(c.getBuildCommand()).contains("cp web/index.html");
        assertThat(c.getArtifactPath()).isEqualTo("web/dist/");
        verify(audits).recordCurrentRequired(eq("배포 단위 추가"),eq("DeployComponent"),eq("21"),any(),eq(AuditResult.SUCCESS));
    }
    @Test void oldUiEditPreservesAllExistingBuildSettings() throws Exception {
        DeployComponent original=existing();
        mvc.perform(put("/api/v1/projects/7/components/21").contentType("application/json").content(BASE.formatted("")))
            .andExpect(status().isOk());
        assertThat(saved()).usingRecursiveComparison().comparingOnlyFields(
            "buildTool","dockerfilePath","dockerContextPath","buildCommand","artifactPath").isEqualTo(original);
        var order=inOrder(projects,components);
        order.verify(projects).lockById(7L);
        order.verify(components).findById(21L);
    }
    @Test void explicitNullClearsToInheritanceWithoutClearingOmittedFields() throws Exception {
        existing();
        mvc.perform(put("/api/v1/projects/7/components/21").contentType("application/json")
            .content(BASE.formatted(",\"dockerfilePath\":null,\"buildTool\":null"))).andExpect(status().isOk());
        DeployComponent c=saved();
        assertThat(c.getDockerfilePath()).isNull();
        assertThat(c.getBuildTool()).isNull();
        assertThat(c.getBuildCommand()).isEqualTo("mkdir -p web/dist");
        assertThat(c.getArtifactPath()).isEqualTo("web/dist/");
    }
    @ParameterizedTest @ValueSource(strings = {
        "\"dockerfilePath\":\"../../Dockerfile\"", "\"dockerContextPath\":\"/etc\"",
        "\"artifactPath\":\"web/../secret\"", "\"buildCommand\":\"echo ok\\nwhoami\"",
        "\"buildTool\":\"INVALID\"", "\"dockerContextPath\":\"$(touch x)\""
    })
    void invalidSourcePathsAndCommandAreRejectedBeforePersistence(String field) throws Exception {
        mvc.perform(post("/api/v1/projects/7/components").contentType("application/json")
            .content(BASE.formatted(","+field))).andExpect(status().isBadRequest());
        verify(components,never()).save(any());
        verifyNoInteractions(audits);
    }
    @Test void distinctRoleCannotReuseAnotherActiveArtifactRepository() throws Exception {
        when(components.findByProjectId(7L)).thenReturn(List.of(DeployComponent.builder().id(3L)
            .role(ServerType.WAS).artifactType(ArtifactType.CONTAINER).active(true)
            .imageRepository("registry.local/c/web").build()));
        mvc.perform(post("/api/v1/projects/7/components").contentType("application/json").content(BASE.formatted("")))
            .andExpect(status().isConflict());
        verify(components,never()).save(any());
    }
    @Test @WithMockUser(roles = "OPERATOR")
    void operatorCannotForgeSourceBuildSettingsMutation() throws Exception {
        mvc.perform(post("/api/v1/projects/7/components").contentType("application/json")
            .content(BASE.formatted(",\"buildCommand\":\"arbitrary command\""))).andExpect(status().isForbidden());
        verifyNoInteractions(components, projects, audits);
    }
    @Test void deletionParticipatesInTheSameProjectMutationLock() throws Exception {
        existing();
        mvc.perform(delete("/api/v1/projects/7/components/21")).andExpect(status().isOk());
        var order=inOrder(projects,components);
        order.verify(projects).lockById(7L);
        order.verify(components).findById(21L);
        order.verify(components).deleteById(21L);
    }
}
