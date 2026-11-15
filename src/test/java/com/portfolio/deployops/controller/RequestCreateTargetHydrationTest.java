package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/** Exercise the controller and real hydration service, not a manually populated render model. */
class RequestCreateTargetHydrationTest {
    final ProjectRepository projects = mock(ProjectRepository.class);
    final ServerRepository servers = mock(ServerRepository.class);
    final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    final DeployComponentRepository components = mock(DeployComponentRepository.class);
    final ProjectImageHistoryService histories = mock(ProjectImageHistoryService.class);
    final SecurityUserDetails actor = new SecurityUserDetails(User.builder()
        .id(7L).loginId("requester").role(UserRole.OPERATOR).build());
    final ProjectService projectService = new ProjectService(projects, mock(ServerGroupRepository.class),
        servers, mock(ApprovalPolicyRepository.class), mock(ApprovalStepRepository.class),
        histories, permissions, components);
    final PageViewController controller = new PageViewController(mock(DashboardService.class),
        mock(DeployRequestService.class), mock(ApprovalService.class), projectService, permissions,
        mock(ServerGroupService.class), components, mock(ServerService.class), mock(NginxChangeService.class),
        mock(UserService.class), mock(TeamService.class), mock(PolicyService.class),
        mock(AuditLogService.class), mock(SystemSettingService.class), histories,
        mock(DeploymentDutySeparationService.class));
    final Project basic = Project.builder().id(1L).name("릴리스 프로젝트").build();

    @BeforeEach void repositoryRows() {
        // Match MyBatis: basic project rows have no attached relationships.
        when(projects.findById(1L)).thenReturn(Optional.of(basic));
        when(projects.findActiveByIds(List.of(1L))).thenReturn(List.of(basic));
        when(projects.findByIdWithDetails(1L)).thenReturn(Optional.of(
            Project.builder().id(1L).name("릴리스 프로젝트").build()));
        var dev = ServerGroup.builder().id(10L).name("개발 검증 그룹").environment(ServerEnvironment.TEST).build();
        var prodA = ServerGroup.builder().id(20L).name("운영 그룹 A").environment(ServerEnvironment.PROD).build();
        var prodB = ServerGroup.builder().id(30L).name("운영 그룹 B").environment(ServerEnvironment.PROD).build();
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(dev, prodA, prodB));
        when(servers.findByServerGroupId(10L)).thenReturn(List.of(server(101, ServerType.WAS), server(102, ServerType.WEB)));
        when(servers.findByServerGroupId(20L)).thenReturn(List.of(server(201, ServerType.WAS), server(202, ServerType.WEB)));
        when(servers.findByServerGroupId(30L)).thenReturn(List.of(server(301, ServerType.WAS), server(302, ServerType.WEB)));
    }

    @Test void prodRequesterSeesEveryMappedProdGroupAndHydratedRoleCountsWithoutExtraViewPermission() {
        allow(ProjectPermissionEnvironment.PROD);
        var model = load();
        assertThat(groups(model).get(1L)).extracting(ServerGroup::getId).containsExactly(20L, 30L);
        assertThat(groups(model).get(1L)).allSatisfy(group -> assertThat(group.getServers()).hasSize(2));
        assertThat(counts(model).get(1L)).containsEntry("PROD_WAS_ONLY", 2).containsEntry("PROD_WEB_ONLY", 2);
        String html = render(model);
        assertThat(html).contains("운영 그룹 A", "운영 그룹 B", "data-permitted-environments=\"PROD\"",
            "data-was=\"1\"", "data-web=\"1\"", "data-prod-was=\"2\"");
        assertThat(html).doesNotContain("개발 검증 그룹");
        verify(projects).findByIdWithDetails(1L);
        verify(projects, never()).findById(anyLong());
        verify(permissions, never()).assertPermission(actor, 1L, ProjectPermissionType.PROJECT_VIEW);
        verify(projects, never()).findByIdWithDetails(2L);
    }

    @Test void testOnlyRequesterCannotSeeProductionGroupOptions() {
        allow(ProjectPermissionEnvironment.TEST);
        var model = load();
        assertThat(groups(model).get(1L)).extracting(ServerGroup::getId).containsExactly(10L);
        String html = render(model);
        assertThat(html).contains("개발 검증 그룹", "data-permitted-environments=\"TEST\"");
        assertThat(html).doesNotContain("운영 그룹 A", "운영 그룹 B");
    }

    @Test void noRequestPermissionDoesNotLoadAnyProjectRelationships() {
        var model = load();
        assertThat(groups(model)).isEmpty();
        assertThat(model.get("projects")).isEqualTo(List.of());
        verify(projects, never()).findByIdWithDetails(anyLong());
        verify(projects, never()).findServerGroupsByProjectId(anyLong());
        verifyNoInteractions(servers);
    }

    void allow(ProjectPermissionEnvironment environment) {
        when(permissions.findPermittedProjectIds(actor, ProjectPermissionType.DEPLOY_REQUEST, environment))
            .thenReturn(List.of(1L));
        when(permissions.hasPermission(actor, 1L, ProjectPermissionType.DEPLOY_REQUEST, environment)).thenReturn(true);
    }
    ExtendedModelMap load() {
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();
        controller.page("request-create", null, null, null, null, 0, null, null, actor,
            new MockHttpServletRequest(), response, model);
        assertThat(response.getStatus()).isEqualTo(200);
        return model;
    }
    @SuppressWarnings("unchecked")
    Map<Long, List<ServerGroup>> groups(ExtendedModelMap model) {
        return (Map<Long, List<ServerGroup>>) model.get("requestGroups");
    }
    @SuppressWarnings("unchecked")
    Map<Long, Map<String, Integer>> counts(ExtendedModelMap model) {
        return (Map<Long, Map<String, Integer>>) model.get("requestTargetCounts");
    }
    Server server(long id, ServerType type) {
        return Server.builder().id(id).name("server-" + id).serverType(type).build();
    }
    String render(Map<String, Object> model) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var request = new MockHttpServletRequest(servlet); request.setRequestURI("/deploy/request-create");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
            .buildExchange(request, new MockHttpServletResponse());
        return engine.process("pages/request-create", new WebContext(exchange, Locale.KOREAN, model));
    }
}
