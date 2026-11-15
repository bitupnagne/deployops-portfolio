package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.domain.Project;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ProjectPermissionTemplateTest {
    @Test void rolloutFragmentHasEnvironmentSelectionReadOnlyDefaultAndSeparateSave() {
        String html = render("fragments/project-rollout",Map.of("project",project()));
        assertThat(html).contains("id=\"tab-rollout\"","data-project-id=\"10\"","ProjectRollout.open()",
            "ProjectRollout.save()","data-rollout=\"save\" hidden","다른 프로젝트가 연결한 정책");
    }
    @Test void selectedGroupFormRendersEachMappedGroupAndRoleCounts() {
        var group = com.portfolio.deployops.domain.ServerGroup.builder().id(20L).name("운영 A")
            .environment(com.portfolio.deployops.domain.ServerEnvironment.PROD)
            .failurePolicy(com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK)
            .servers(List.of(com.portfolio.deployops.domain.Server.builder().id(1L)
                .serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build();
        String html = render("pages/request-create", Map.of("projects",List.of(project()),
            "requestEnvironments",Map.of(10L,List.of("PROD")),
            "requestGroups",Map.of(10L,List.of(group))));
        assertThat(html).contains("name=\"request-group\"", "data-project=\"10\"", "data-environment=\"PROD\"",
            "data-was=\"1\"", "data-web=\"0\"", "운영 A", "data-failure-policy=\"ROLLBACK\"",
            "실패 시 그룹 복구 · 수동 전략 사용 불가", "id=\"req-strategy-warning\"");
        assertThat(html).doesNotContain("(서비스 안 멈춤)");
        assertThat(html).contains("data-additional-approval=\"false\"").doesNotContain("추가 승인 필요</span>");
        group.setApprovalPolicyId(7L);
        String withApproval = render("pages/request-create", Map.of("projects",List.of(project()),
            "requestEnvironments",Map.of(10L,List.of("PROD")),
            "requestGroups",Map.of(10L,List.of(group))));
        assertThat(withApproval).contains("data-additional-approval=\"true\"", "추가 승인 필요</span>",
            "TEST 배포도 수동 승인이 필요하며", "그룹 ID 순서대로 추가 승인");
    }
    @Test
    void requestCreateRendersPerProjectEnvironmentPermissions() {
        String html = render("pages/request-create", Map.of("projects", List.of(project()), "requestEnvironments", Map.of(10L, List.of("TEST"))));
        assertThat(html).contains("data-permitted-environments=\"TEST\"").doesNotContain("data-permitted-environments=\"TEST,PROD\"");
    }

    @Test
    void permissionFragmentResolvesProjectAndPolicyLinkWithoutInlineUserData() {
        String html = render("fragments/project-permissions", Map.of("project", project()));
        assertThat(html).contains("data-project-id=\"10\"", "href=\"/deploy/policies\"", "ProjectPermissions.save()",
            "MR 변경내용과 diff를 검토하려면", "프로젝트 조회", "MR 검토", "MR 승인과 병합은 각각 별도 권한입니다.");
    }

    @Test
    void requestCreateMissingCountsAndMissingProjectMemberRenderFailClosed() {
        var base = new java.util.HashMap<String, Object>();
        base.put("projects", List.of(project()));
        base.put("requestEnvironments", Map.of(10L, List.of("TEST")));
        assertThat(render("pages/request-create", base)).contains("data-test-was=\"0\"", "data-prod-was=\"0\"");
        base.put("requestTargetCounts", Map.of(99L, Map.of("TEST_WAS_ONLY", 5)));
        assertThat(render("pages/request-create", base)).contains("data-test-was=\"0\"", "data-prod-was=\"0\"");
        base.put("requestTargetCounts", Map.of(10L, Map.of("TEST_WAS_ONLY", 2)));
        assertThat(render("pages/request-create", base)).contains("data-test-was=\"2\"", "data-test-web=\"0\"");
    }

    private Project project() { return Project.builder().id(10L).name("프로젝트").active(true).build(); }
    private String render(String name, Map<String, Object> variables) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servletContext = new MockServletContext(); var request = new MockHttpServletRequest(servletContext); request.setRequestURI("/deploy/request-create");
        var exchange = JakartaServletWebApplication.buildApplication(servletContext).buildExchange(request, new MockHttpServletResponse());
        return engine.process(name, new WebContext(exchange, Locale.KOREAN, variables));
    }
}
