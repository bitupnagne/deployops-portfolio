package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ServerManagementRoleTemplateTest {
    @Test
    void onlySuperAdminSeesApprovalSelectorWhileOperatorKeepsPolicyIdInReadOnlyForm() {
        String admin = render("server-groups", "SUPER_ADMIN", false, true);
        assertThat(admin).containsPattern("<select[^>]*id=\"gm-approval-policy\"")
            .contains("승인 전용 정책", "data-approval-policy-id=\"4\"");
        String operator = render("server-groups", "OPERATOR", false, true);
        assertThat(operator).contains("type=\"hidden\" id=\"gm-approval-policy\"", "id=\"gm-approval-policy-readonly\"",
            "data-approval-policy-id=\"4\"", "추가 승인: 승인 전용 정책")
            .doesNotContainPattern("<select[^>]*id=\"gm-approval-policy\"");
    }
    @Test
    void approverRetainsGroupAndServerReadsWithoutCrudOrOrderingControls() {
        assertReadOnly("APPROVER");
    }

    @Test
    void absentOrUnrecognizedRoleDoesNotRenderManagementForms() {
        assertReadOnly(null);
        assertReadOnly("DEVELOPER");
    }

    @Test
    void operatorAndSuperAdminRetainAllExistingManagementEntryPoints() {
        for (String role : List.of("OPERATOR", "SUPER_ADMIN")) {
            String groups = render("server-groups", role, false);
            assertThat(groups).contains("onclick=\"openGroupModal()\"", "id=\"btn-order\"",
                "onclick=\"openGroupModalFrom(this)\"", "onclick=\"deleteGroup(this)\"",
                "onclick=\"openServerModalFrom(this)\"", "onclick=\"deleteServer(this)\"",
                "onclick=\"openServerModalForGroup(this)\"", "id=\"group-modal\"", "id=\"server-modal\"");
            assertThat(render("server-groups", role, true)).contains("첫 그룹 만들기");
            assertThat(render("servers", role, false)).contains("onclick=\"openServerModalFromBtn(this)\"",
                "onclick=\"deleteServer(this)\"", "id=\"server-modal\"",
                "onclick=\"openInfrastructureServerModal()\"", "인프라 서버 추가",
                "openServerModal(null, null, null, null, 'GITLAB');",
                "setServerModalSelect('server-group', groupId && groupId !== 'null' ? groupId : '')");
            assertThat(render("servers", role, true)).contains("onclick=\"openInfrastructureServerModal()\"");
        }
    }

    @Test
    void emptyServerListExplainsRegistrationWithoutOfferingUnavailableControls() {
        String managerHint = "배포할 WAS·WEB 서버는 그룹에 연결하고, 인프라 서버는 상단 버튼에서 추가합니다.";
        String readOnlyHint = "배포할 WAS·WEB 서버는 그룹에 연결되며, 인프라 서버는 별도로 등록됩니다.";
        for (String role : List.of("OPERATOR", "SUPER_ADMIN")) {
            assertThat(render("servers", role, true)).contains(managerHint, "onclick=\"openInfrastructureServerModal()\"")
                .doesNotContain(readOnlyHint);
        }
        assertThat(render("servers", "APPROVER", true)).contains(readOnlyHint)
            .doesNotContain(managerHint, "onclick=\"openInfrastructureServerModal()\"");
    }

    private void assertReadOnly(String role) {
        String groups = render("server-groups", role, false);
        assertThat(groups).contains("qa-role-group", "qa-role-server", "배포 간격 17초",
            "onclick=\"toggleGroup(this)\"", "전체 목록으로 보기")
            .doesNotContain("onclick=\"openGroupModal()\"", "id=\"btn-order\"", "id=\"btn-order-cancel\"",
                "onclick=\"moveGroup(this", "onclick=\"openGroupModalFrom(this)\"", "onclick=\"deleteGroup(this)\"",
                "onclick=\"openServerModalFrom(this)\"", "onclick=\"deleteServer(this)\"",
                "onclick=\"openServerModalForGroup(this)\"", "id=\"group-modal\"", "id=\"server-modal\"", "onclick=\"refreshAllStatus()\"");
        assertThat(render("server-groups", role, true)).contains("아직 서버 그룹이 없습니다.")
            .doesNotContain("첫 그룹 만들기", "id=\"group-modal\"", "id=\"server-modal\"");
        assertThat(render("servers", role, false)).contains("qa-role-server", "qa-role-group")
            .doesNotContain("onclick=\"openServerModalFromBtn(this)\"", "onclick=\"openInfrastructureServerModal()\"", "onclick=\"deleteServer(this)\"", "id=\"server-modal\"", "onclick=\"checkAllServers()\"");
    }

    private String render(String page, String role, boolean empty) {
        return render(page, role, empty, false);
    }

    private String render(String page, String role, boolean empty, boolean assignedPolicy) {
        var group = ServerGroup.builder().id(81L).name("qa-role-group").environment(ServerEnvironment.TEST)
            .rollingIntervalSec(17).description("QA role presentation").approvalPolicyId(assignedPolicy ? 4L : null).build();
        var server = Server.builder().id(82L).name("qa-role-server").ipAddress("192.0.2.82")
            .environment(ServerEnvironment.TEST).serverType(ServerType.WAS).serverGroup(group)
            .deployAccount("deploy").deployPath("/opt/qa-role").build();
        group.setServers(List.of(server));
        var variables = new HashMap<String, Object>();
        variables.put("serverGroups", empty ? List.of() : List.of(group));
        variables.put("canManageGroupApproval", "SUPER_ADMIN".equals(role));
        variables.put("groupApprovalPolicyNames", assignedPolicy ? Map.of(4L, "승인 전용 정책") : Map.of());
        variables.put("groupApprovalPolicies", "SUPER_ADMIN".equals(role)
            ? List.of(com.portfolio.deployops.domain.ApprovalPolicy.builder().id(4L).name("승인 전용 정책").build()) : List.of());
        variables.put("servers", empty ? List.of() : List.of(server));
        variables.put("ungroupedCount", 0);
        variables.put("ungroupedWebServers", List.of());
        variables.put("nginxChangeCounts", Map.of());
        variables.put("groupServerSections", Map.of(group.getId(), List.of(Map.of("key", "WAS", "label", "WAS 서버", "servers", List.of(server)))));
        if (role != null) variables.put("currentUser", Map.of("role", role));
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext(); var request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/deploy/" + page);
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
            .buildExchange(request, new MockHttpServletResponse());
        return engine.process("pages/" + page, new WebContext(exchange, Locale.KOREAN, variables));
    }
}
