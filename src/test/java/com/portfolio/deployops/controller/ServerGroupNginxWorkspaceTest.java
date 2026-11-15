package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.service.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ServerGroupNginxWorkspaceTest {
    final ServerGroupService groups = mock(ServerGroupService.class);
    final ServerService servers = mock(ServerService.class);
    final NginxChangeService nginx = mock(NginxChangeService.class);
    final SecurityUserDetails user = mock(SecurityUserDetails.class);
    final PageViewController controller = new PageViewController(
        mock(DashboardService.class), mock(DeployRequestService.class), mock(ApprovalService.class),
        mock(ProjectService.class), mock(ProjectPermissionService.class), groups,
        mock(DeployComponentRepository.class), servers, nginx, mock(UserService.class), mock(TeamService.class),
        mock(PolicyService.class), mock(AuditLogService.class), mock(SystemSettingService.class),
        mock(ProjectImageHistoryService.class), mock(DeploymentDutySeparationService.class));
    ServerGroup first, second;
    Server web, was, database, otherWeb, ungrouped;

    @BeforeEach void fixture() {
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn("SUPER_ADMIN");
        first = ServerGroup.builder().id(10L).name("운영 A").environment(ServerEnvironment.PROD).build();
        second = ServerGroup.builder().id(20L).name("운영 B").environment(ServerEnvironment.PROD).build();
        web = server(1, "web-a", ServerType.WEB, first);
        was = server(2, "was-a", ServerType.WAS, first);
        database = server(3, "db-a", ServerType.DB, first);
        otherWeb = server(4, "web-b", ServerType.WEB, second);
        ungrouped = server(5, "web-unassigned", ServerType.WEB, null);
        first.setServers(List.of(was, database, web)); second.setServers(List.of(otherWeb));
        when(groups.findAll()).thenReturn(List.of(first,second));
        when(servers.findAll()).thenReturn(List.of(web,was,database,otherWeb,ungrouped));
        when(nginx.findAll()).thenReturn(List.of(change(101,web,"only-a"),change(102,otherWeb,"only-b")));
    }

    @Test void partitionsEveryTypeAndDoesNotClaimNginxIsRunningOrContactServer() {
        var model = load(null,new MockHttpServletResponse());
        String html = render(model);
        assertThat(html).contains("WEB 서버","WAS 서버","기타 서버","db-a","web-unassigned","미확인");
        assertThat(html).doesNotContain("id=\"nginx-policy-form\"");
        verify(nginx,never()).inspect(anyLong());
        assertThat(((Map<?,?>)model.get("groupServerSections")).keySet()).hasSize(2);
    }

    @Test void selectedWebIsFixedInOneEmbeddedWorkspaceWithOnlyItsOwnHistory() {
        var model = load("1",new MockHttpServletResponse());
        assertThat(model.get("webServers")).isEqualTo(List.of(web));
        assertThat(model.get("nginxContextGroupId")).isEqualTo(10L);
        String html = render(model);
        assertThat(html).contains("only-a","id=\"nginx-config-editor\"","name=\"rawConfig\"","nginx-current-editor","설정 원문")
            .doesNotContain("only-b");
        assertThat(html.split("id=\"nginx-server-workspace\"",-1)).hasSize(2);
        assertThat(html.split("id=\"nginx-policy-form\"",-1)).hasSize(2);
        assertThat(html).contains("data-nginx-server-id=\"1\"","data-nginx-group-id=\"10\"");
    }

    @Test void ungroupedWebStillHasPolicyWorkspaceAndOtherServerCannotBeSelected() {
        var model = load("5",new MockHttpServletResponse());
        assertThat(model.get("nginxContextGroupId")).isNull();
        assertThat(render(model)).contains("web-unassigned · Nginx 설정","id=\"nginx-config-editor\"");
        var response = new MockHttpServletResponse(); load("2",response);
        assertThat(response.getStatus()).isEqualTo(400);
        response = new MockHttpServletResponse(); load("999",response);
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test void approverCanReadWorkspaceButServerModificationControlsRemainHidden() {
        when(user.getRole()).thenReturn("APPROVER");
        String html = render(load("1",new MockHttpServletResponse()));
        assertThat(html).contains("Nginx 설정 관리","설정 원문");
        assertThat(html).doesNotContain("id=\"btn-refresh-status\"","id=\"group-modal\"","id=\"server-modal\"");
    }

    @Test void oldPolicyUrlResolvesToServerGroupContextWithoutDroppingAccessPath() {
        var request = new MockHttpServletRequest();
        assertThat(controller.page("nginx-policies",null,null,null,null,0,null,null,user,request,
            new MockHttpServletResponse(),new ExtendedModelMap())).isEqualTo("redirect:/deploy/server-groups?nginx=1");
        request.setParameter("nginxServerId","1");
        assertThat(controller.page("nginx-policies",null,null,null,null,0,null,null,user,request,
            new MockHttpServletResponse(),new ExtendedModelMap())).isEqualTo("redirect:/deploy/server-groups?nginxServerId=1");
    }

    ExtendedModelMap load(String selected,MockHttpServletResponse response) {
        var request = new MockHttpServletRequest(); if(selected!=null)request.setParameter("nginxServerId",selected);
        var model = new ExtendedModelMap();
        controller.page("server-groups",null,null,null,null,0,null,null,user,request,response,model);
        return model;
    }
    Server server(long id,String name,ServerType type,ServerGroup group) {
        return Server.builder().id(id).name(name).serverType(type).serverGroup(group).environment(ServerEnvironment.PROD)
            .ipAddress("10.0.0."+id).sshPort(2022).deployAccount("deploy").orderIndex((int)id).build();
    }
    NginxChangeRequest change(long id,Server server,String policy) {
        return NginxChangeRequest.builder().id(id).server(server).policyName(policy).serverName("service.local")
            .requestedBy(User.builder().id(9L).name("운영자").build()).status(NginxChangeStatus.PENDING)
            .createdAt(LocalDateTime.of(2026,10,20,10,0)).build();
    }
    String render(Map<String,Object> model) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();var request = new MockHttpServletRequest(servlet);request.setRequestURI("/deploy/server-groups");
        var exchange = JakartaServletWebApplication.buildApplication(servlet).buildExchange(request,new MockHttpServletResponse());
        return engine.process("pages/server-groups",new WebContext(exchange,Locale.KOREAN,model));
    }
}
