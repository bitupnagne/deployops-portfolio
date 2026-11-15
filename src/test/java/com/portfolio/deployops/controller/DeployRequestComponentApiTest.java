package com.portfolio.deployops.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;

/** MVC enum binding and real permission decisions; only database repositories are mocked. */
class DeployRequestComponentApiTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DeployComponentRepository components = mock(DeployComponentRepository.class);
    private final ProjectUserPermissionRepository grants = mock(ProjectUserPermissionRepository.class);
    private final ProjectPermissionService permissions = new ProjectPermissionService(grants);
    private SecurityUserDetails actor;
    private MockMvc mvc;

    @BeforeEach void setup() {
        actor = new SecurityUserDetails(User.builder().id(7L).role(UserRole.DEVELOPER).active(true).build());
        var service = new DeployRequestComponentService(projects, components, permissions);
        mvc = MockMvcBuilders.standaloneSetup(new DeployRequestComponentApiController(service),
            new DeployComponentApiController(components, projects, permissions, mock(AuditLogService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == SecurityUserDetails.class; }
                public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                    NativeWebRequest r, WebDataBinderFactory b) { return actor; }
            }).build();
        when(grants.existsActivePermission(7L, 1L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(true);
        when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).active(true).build()));
        when(components.findByProjectId(1L)).thenReturn(List.of(
            component(11L, ServerType.WAS, true), component(12L, ServerType.WEB, true),
            component(13L, ServerType.WAS, false)));
    }

    private DeployComponent component(long id, ServerType role, boolean active) {
        return DeployComponent.builder().id(id).projectId(1L).code("component-" + id)
            .displayName("단위 " + id).role(role).artifactType(ArtifactType.CONTAINER)
            .imageRepository("private/service-" + id).active(active)
            .containerUser("SENSITIVE_USER").buildCommand("SENSITIVE_COMMAND")
            .webRoot("/SENSITIVE_PATH").healthCheckPath("/SENSITIVE_HEALTH").build();
    }

    @Test void requesterWithoutProjectViewGetsOnlyScopedActiveSummary() throws Exception {
        mvc.perform(get("/api/v1/projects/1/request-components?environment=TEST&scope=WAS_ONLY"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(11))
            .andExpect(jsonPath("$.data[0].role").value("WAS"))
            .andExpect(jsonPath("$.data[0].containerUser").doesNotExist())
            .andExpect(jsonPath("$.data[0].buildCommand").doesNotExist())
            .andExpect(jsonPath("$.data[0].healthCheckPath").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SENSITIVE"))));
        mvc.perform(get("/api/v1/projects/1/components")).andExpect(status().isForbidden());
    }

    @Test void allAndWebScopesReturnCorrectRoles() throws Exception {
        mvc.perform(get("/api/v1/projects/1/request-components?environment=TEST&scope=ALL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/projects/1/request-components?environment=TEST&scope=WEB_ONLY"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].role").value("WEB"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/1/request-components?environment=PROD&scope=ALL",
        "/2/request-components?environment=TEST&scope=ALL"
    })
    void environmentAndProjectPermissionsAreRequired(String suffix) throws Exception {
        mvc.perform(get("/api/v1/projects" + suffix)).andExpect(status().isForbidden());
        verifyNoInteractions(projects, components);
    }

    @Test void projectViewAloneDoesNotGrantRequestMetadata() throws Exception {
        when(grants.existsActivePermission(7L, 1L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(false);
        when(grants.existsActivePermission(7L, 1L, ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL)).thenReturn(true);
        mvc.perform(get("/api/v1/projects/1/request-components?environment=TEST&scope=ALL"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(projects, components);
    }

    @Test void nullPrincipalFailsClosed() throws Exception {
        actor = null;
        mvc.perform(get("/api/v1/projects/1/request-components?environment=TEST&scope=ALL"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(projects, components);
    }

    @Test void inactiveProjectCannotExposeComponentsEvenWithGrant() throws Exception {
        when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).active(false).build()));
        mvc.perform(get("/api/v1/projects/1/request-components?environment=TEST&scope=ALL"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(components);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/1/request-components?scope=ALL", "/1/request-components?environment=TEST",
        "/1/request-components?environment=&scope=ALL",
        "/1/request-components?environment=DEV&scope=ALL",
        "/1/request-components?environment=null&scope=ALL",
        "/1/request-components?environment=TEST&scope=",
        "/1/request-components?environment=TEST&scope=INVALID",
        "/1/request-components?environment=TEST&scope=null",
        "/0/request-components?environment=TEST&scope=ALL",
        "/-1/request-components?environment=TEST&scope=ALL"
    })
    void incompleteOrInvalidContextFailsBeforeDatabaseRead(String suffix) throws Exception {
        mvc.perform(get("/api/v1/projects" + suffix)).andExpect(status().isBadRequest());
        verifyNoInteractions(grants, projects, components);
    }
}
