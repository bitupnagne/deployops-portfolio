package com.portfolio.deployops.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import com.portfolio.deployops.service.*;
import java.util.List;
import java.util.Map;
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

class DeployRequestImageApiTest {
    final ProjectRepository projects = mock(ProjectRepository.class);
    final ProjectUserPermissionRepository grants = mock(ProjectUserPermissionRepository.class);
    final ProjectPermissionService permissions = new ProjectPermissionService(grants);
    final NexusMetadataService nexus = mock(NexusMetadataService.class);
    final GitlabService gitlab = mock(GitlabService.class);
    final Project project = Project.builder().id(2L).active(true).imageRepository("registry/project-b").build();
    SecurityUserDetails actor;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        actor = new SecurityUserDetails(User.builder().id(3L).role(UserRole.OPERATOR).active(true).build());
        when(grants.existsActivePermission(3L, 2L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(true);
        when(projects.findById(2L)).thenReturn(Optional.of(project));
        when(nexus.getDockerTags(project)).thenReturn(List.of(Map.of("tag", "v1", "createdAt", "2026-10-20",
            "privateExtra", "DO_NOT_EXPOSE")));
        when(nexus.resolveManifest(project, "v1")).thenReturn(Map.of("digest", "sha256:" + "a".repeat(64),
            "repositoryPath", "private-path", "privateExtra", "DO_NOT_EXPOSE"));
        mvc = MockMvcBuilders.standaloneSetup(
            new DeployRequestImageApiController(projects, permissions, nexus),
            new NexusApiController(projects, nexus, permissions, mock(AuditLogService.class),
                mock(ImageAvailabilityService.class), gitlab, mock(NexusCleanupService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == SecurityUserDetails.class; }
                public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                    NativeWebRequest r, WebDataBinderFactory b) { return actor; }
            }).build();
    }

    @Test void requestOnlyUserGetsTagsDigestAndStatusFromFixedProjectRepository() throws Exception {
        mvc.perform(get("/api/v1/nexus/request-projects/2/docker-tags?environment=TEST&repository=other/project"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data[0].tag").value("v1"))
            .andExpect(jsonPath("$.data[0].privateExtra").doesNotExist());
        mvc.perform(get("/api/v1/nexus/request-projects/2/docker-manifest?environment=TEST&tag=v1&repository=other/project"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.digest").value("sha256:" + "a".repeat(64)))
            .andExpect(jsonPath("$.data.repositoryPath").doesNotExist())
            .andExpect(jsonPath("$.data.privateExtra").doesNotExist());
        mvc.perform(get("/api/v1/nexus/request-projects/2/image-status?environment=TEST"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("IMAGES_AVAILABLE"))
            .andExpect(jsonPath("$.data.pipelineUrl").doesNotExist());
        verify(nexus, times(2)).getDockerTags(same(project));
        verify(nexus).resolveManifest(same(project), eq("v1"));
        verifyNoInteractions(gitlab);
    }

    @ParameterizedTest @ValueSource(strings={"docker-tags", "docker-manifest", "image-status"})
    void wrongEnvironmentOtherProjectRevocationAndNullActorFailClosed(String endpoint) throws Exception {
        mvc.perform(get("/api/v1/nexus/request-projects/2/" + endpoint + "?environment=PROD&tag=v1"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/nexus/request-projects/1/" + endpoint + "?environment=TEST&tag=v1"))
            .andExpect(status().isForbidden());
        when(grants.existsActivePermission(3L, 2L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(false);
        mvc.perform(get("/api/v1/nexus/request-projects/2/" + endpoint + "?environment=TEST&tag=v1"))
            .andExpect(status().isForbidden());
        actor = null;
        mvc.perform(get("/api/v1/nexus/request-projects/2/" + endpoint + "?environment=TEST&tag=v1"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(projects, nexus, gitlab);
    }

    @ParameterizedTest @ValueSource(strings={"docker-tags", "docker-manifest", "image-status"})
    void existingProjectViewEndpointsRemainForbiddenToRequestOnlyUser(String endpoint) throws Exception {
        mvc.perform(get("/api/v1/nexus/deploy-projects/2/" + endpoint + "?tag=v1"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(projects, nexus, gitlab);
    }

    @ParameterizedTest @ValueSource(strings={"", "?environment=", "?environment=DEV", "?environment=null"})
    void missingAndUnsupportedEnvironmentFailsBeforePermissionAndRegistry(String query) throws Exception {
        for (String endpoint : List.of("docker-tags", "image-status", "docker-manifest")) {
            mvc.perform(get("/api/v1/nexus/request-projects/2/" + endpoint + query))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(grants, projects, nexus);
    }

    @Test void inactiveProjectAndInvalidTagCannotReachRegistry() throws Exception {
        mvc.perform(get("/api/v1/nexus/request-projects/2/docker-manifest")
                .param("environment", "TEST").param("tag", "../other:tag"))
            .andExpect(status().isBadRequest());
        project.setActive(false);
        for (String endpoint : List.of("docker-tags", "docker-manifest", "image-status")) {
            mvc.perform(get("/api/v1/nexus/request-projects/2/" + endpoint + "?environment=TEST&tag=v1"))
                .andExpect(status().isNotFound());
        }
        verifyNoInteractions(nexus);
    }

    @Test void projectViewWithoutRequestGrantDoesNotGrantRequestOnlyMetadata() throws Exception {
        when(grants.existsActivePermission(3L, 2L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(false);
        when(grants.existsActivePermission(3L, 2L, ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL)).thenReturn(true);
        mvc.perform(get("/api/v1/nexus/request-projects/2/docker-tags?environment=TEST"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(nexus);
    }

    @Test void upstreamFailureNeverLeaksCredentialsOrPretendsThereAreNoImages() throws Exception {
        when(nexus.getDockerTags(project)).thenThrow(new IllegalStateException("https://user:SECRET@private.registry"));
        mvc.perform(get("/api/v1/nexus/request-projects/2/docker-tags?environment=TEST"))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.success").value(false))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
        mvc.perform(get("/api/v1/nexus/request-projects/2/image-status?environment=TEST"))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.data").isEmpty());
    }
}
