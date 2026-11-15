package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.BuildConfigMode;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ProjectConfigurationTemplateTest {

    @Test
    void managerWithoutMrPermissionDoesNotSeeMrPanelsOrConfigurationEdits() {
        String html = render(UserRole.PROJECT_MANAGER, false);

        assertThat(html).contains("data-can-configure=\"false\"", "data-can-operate-ci-pipelines=\"false\"",
                "기본 프로젝트 설정은 조회만 가능합니다.", "배포 요청 권한이 있어야 사용할 수 있습니다.")
            .doesNotContain("onclick=\"saveProject()\"", "onclick=\"openComponentModal()\"",
                "onclick=\"openComponentModal(this)\"", "onclick=\"deleteComponent(this.dataset.id)\"",
                "id=\"component-modal\"");
        var fieldsets = Pattern.compile("<fieldset\\b[^>]*>.*?</fieldset>", Pattern.DOTALL)
            .matcher(html).results().map(result -> result.group())
            .filter(fieldset -> fieldset.contains("class=\"project-config-fields\"")).toList();
        assertThat(fieldsets).hasSize(3).allSatisfy(fieldset -> {
            assertThat(fieldset.substring(0, fieldset.indexOf('>'))).contains("disabled=\"disabled\"");
            assertThat(fieldset).doesNotContain("id=\"ci-editor\"", "id=\"ci-ops-ref\"");
        });
        assertThat(html).doesNotContain("id=\"tabbtn-mrs\"", "id=\"tab-mrs\"")
            .doesNotContain("id=\"ci-save\"")
            .contains("id=\"ci-editor\"", "MR 검토 권한이 필요합니다",
            "id=\"ci-ops-ref\"", "id=\"tab-services\"", "id=\"tab-deploylog\"");
        assertThat(html).doesNotContain("id=\"project-server-group-mapping\"",
            "onclick=\"saveProjectServerGroups()\"");
    }

    @Test
    void adminCanEditConfigurationAndManageDeploymentUnits() {
        String html = render(UserRole.SUPER_ADMIN, true);

        assertThat(html).contains("data-can-configure=\"true\"", "data-can-operate-ci-pipelines=\"true\"",
            "data-require-mr-evidence-for-build=\"true\"", "!mrEvidenceRequired && status === 'failed'",
            "승인·병합 MR 증거 정책이 켜져 있어 여기서 기존 pipeline을 재시도할 수 없습니다.",
            "id=\"tabbtn-mrs\"", "id=\"tab-mrs\"",
            "onclick=\"saveProject()\"",
            "onclick=\"openComponentModal()\"", "onclick=\"openComponentModal(this)\"",
            "onclick=\"deleteComponent(this.dataset.id)\"", "id=\"component-modal\"",
            "id=\"project-server-group-mapping\"", "class=\"project-server-group-checkbox\"",
            "배포 대상 설정", "PROD 서버 그룹 연결은 운영 배포 대상을 추가하며",
            "onclick=\"saveProjectServerGroups()\"", "/server-groups");
        var fieldsetStarts = Pattern.compile("<fieldset\\b[^>]*>").matcher(html)
            .results().map(result -> result.group())
            .filter(fieldset -> fieldset.contains("class=\"project-config-fields\"")).toList();
        assertThat(fieldsetStarts).hasSize(3).allSatisfy(fieldset -> assertThat(fieldset).doesNotContain("disabled"));
    }

    @Test
    void projectManagerWithMrReviewPermissionCanSeeTheMrPanelsWithoutGettingAdminTools() {
        String html = render(UserRole.PROJECT_MANAGER, true);

        assertThat(html).contains("id=\"tabbtn-mrs\"", "id=\"tab-mrs\"")
            .doesNotContain("onclick=\"saveProject()\"", "id=\"tabbtn-permissions\"");
    }

    @Test
    void runtimeEditorStartsHiddenAndDisabledUntilScopedApiPermissionsArriveForEveryRole() {
        for (UserRole role : List.of(UserRole.SUPER_ADMIN, UserRole.PROJECT_MANAGER, UserRole.DEVELOPER)) {
            String html = render(role, role != UserRole.DEVELOPER);
            var runtime = Pattern.compile("<fieldset\\b[^>]*data-runtime=\"editor\"[^>]*>")
                .matcher(html).results().map(result -> result.group()).toList();
            assertThat(runtime).hasSize(1).allSatisfy(tag -> assertThat(tag).contains("disabled", "hidden"));
            assertThat(html).contains("id=\"tab-runtime\"", "id=\"runtime-environment\"", "id=\"runtime-component\"");
        }
    }

    @Test
    void ciRetryPolicyNoticeIsHiddenWhenMrEvidenceIsNotRequired() {
        String html = render(UserRole.SUPER_ADMIN, true, false);

        assertThat(html).contains("data-require-mr-evidence-for-build=\"false\"")
            .doesNotContain("승인·병합 MR 증거 정책이 켜져 있어 여기서 기존 pipeline을 재시도할 수 없습니다.");
    }

    @Test
    void rendersOnlyThePersistedCombinedBuildModeForAllThreeModes() {
        for (BuildConfigMode mode : BuildConfigMode.values()) {
            String html = render(UserRole.SUPER_ADMIN, true, true, mode);
            String select = Pattern.compile("<select\\b[^>]*id=\"proj-build-config-mode\"[^>]*>.*?</select>", Pattern.DOTALL)
                .matcher(html).results().map(result -> result.group()).findFirst().orElseThrow();
            var selected = Pattern.compile("<option\\b[^>]*selected=\"selected\"[^>]*>")
                .matcher(select).results().map(result -> result.group()).toList();
            assertThat(selected).hasSize(1);
            assertThat(selected.getFirst()).contains("value=\"" + mode.name() + "\"");
            assertThat(html).contains("Dockerfile·CI 설정 모드", "Dockerfile과 GitLab CI에 함께 적용하는 모드입니다.")
                .doesNotContain("id=\"proj-docker-config-mode\"", "id=\"proj-ci-config-mode\"",
                    "Dockerfile과 CI는 서로 독립적으로 관리할 수 있습니다.");
        }
    }

    private String render(UserRole role, boolean canReviewMergeRequests) {
        return render(role, canReviewMergeRequests, true);
    }

    private String render(UserRole role, boolean canReviewMergeRequests, boolean requireMrEvidenceForBuild) {
        return render(role, canReviewMergeRequests, requireMrEvidenceForBuild, BuildConfigMode.AUTO);
    }

    private String render(UserRole role, boolean canReviewMergeRequests, boolean requireMrEvidenceForBuild, BuildConfigMode mode) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        request.setRequestURI("/deploy/project-detail");
        var exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(request, new MockHttpServletResponse());
        var project = Project.builder().id(10L).name("프로젝트").active(true).buildConfigMode(mode).build();
        var serverGroup = ServerGroup.builder().id(50L).name("UIQA-WAS")
            .environment(ServerEnvironment.TEST).build();
        var component = DeployComponent.builder().id(20L).projectId(10L).code("api")
            .role(ServerType.WAS).artifactType(ArtifactType.CONTAINER).build();
        var principal = new SecurityUserDetails(User.builder().id(2L).loginId("tester").role(role).build());
        return engine.process("pages/project-detail", new WebContext(exchange, Locale.KOREAN, Map.ofEntries(
            Map.entry("project", project), Map.entry("components", List.of(component)),
            Map.entry("currentUser", principal), Map.entry("approvalPolicies", List.of()),
            Map.entry("servers", List.of()), Map.entry("availableServerGroups", List.of(serverGroup)),
            Map.entry("mappedServerGroupIds", List.of(50L)), Map.entry("projLogPaths", ""),
            Map.entry("canOperateCiPipelines", role == UserRole.SUPER_ADMIN),
            Map.entry("requireMrEvidenceForBuild", requireMrEvidenceForBuild),
            Map.entry("canReviewMergeRequests", canReviewMergeRequests))));
    }
}
