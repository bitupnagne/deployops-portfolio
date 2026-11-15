package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalAction;
import com.portfolio.deployops.domain.ApprovalRecord;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ReleaseVerificationEvidence;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.service.ApprovalService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DashboardService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.DeploymentDutySeparationService;
import com.portfolio.deployops.service.NginxChangeService;
import com.portfolio.deployops.service.PolicyService;
import com.portfolio.deployops.service.ProjectImageHistoryService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.ServerGroupService;
import com.portfolio.deployops.service.ServerService;
import com.portfolio.deployops.service.SystemSettingService;
import com.portfolio.deployops.service.TeamService;
import com.portfolio.deployops.service.UserService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ProdRollbackUiGateTest {

    @ParameterizedTest
    @ValueSource(strings = {"DEVELOPER", "OPERATOR"})
    void imageRollbackPageUsesActualProjectEnvironmentPermissions(String role) {
        var repository = mock(com.portfolio.deployops.repository.ProjectUserPermissionRepository.class);
        ReflectionTestUtils.setField(controller, "projectPermissionService", new ProjectPermissionService(repository));
        when(user.getRole()).thenReturn(role);
        var project = Project.builder().id(10L).name("QA").serverGroups(List.of()).build();
        var projects = (ProjectService) ReflectionTestUtils.getField(controller, "projectService");
        when(projects.findByIdWithDetails(10L, user)).thenReturn(project);
        var model = new ExtendedModelMap();
        ReflectionTestUtils.invokeMethod(controller, "loadProjectDetail", model, 10L, user);
        assertThat(model.get("canRollbackTest")).isEqualTo(false);
        assertThat(model.get("canRollbackProd")).isEqualTo(false);
        when(repository.existsActivePermission(30L, 10L, ProjectPermissionType.ROLLBACK_EXECUTE,
            com.portfolio.deployops.domain.ProjectPermissionEnvironment.TEST)).thenReturn(true);
        ReflectionTestUtils.invokeMethod(controller, "loadProjectDetail", model, 10L, user);
        assertThat(model.get("canRollbackTest")).isEqualTo(true);
        assertThat(model.get("canRollbackProd")).isEqualTo(false);
        when(user.getRole()).thenReturn("SUPER_ADMIN");
        ReflectionTestUtils.invokeMethod(controller, "loadProjectDetail", model, 10L, user);
        assertThat(model.get("canRollbackTest")).isEqualTo(true);
        assertThat(model.get("canRollbackProd")).isEqualTo(true);
    }

    @Test
    void imageHistoryRollbackButtonsAreEnvironmentScopedAndMissingPermissionsFailClosed() {
        var histories = List.of(imageHistory(15L, DeployEnvironment.TEST), imageHistory(16L, DeployEnvironment.PROD));
        var project = Project.builder().id(10L).name("QA").imageHistories(histories).build();
        String missing = renderImageHistory(Map.of("project", project));
        assertThat(missing).contains("이 프로젝트·환경의 롤백 실행 권한이 필요합니다.");
        assertThat(rollbackButtons(missing)).hasSize(2).allSatisfy(button -> assertThat(button).contains("disabled=\"disabled\""));
        String testOnly = renderImageHistory(Map.of("project", project, "canRollbackTest", true, "canRollbackProd", false));
        var buttons = rollbackButtons(testOnly);
        assertThat(buttons.get(0)).doesNotContain("disabled=");
        assertThat(buttons.get(1)).contains("disabled=\"disabled\"");
        String both = renderImageHistory(Map.of("project", project, "canRollbackTest", true, "canRollbackProd", true));
        assertThat(rollbackButtons(both)).hasSize(2).allSatisfy(button -> assertThat(button).doesNotContain("disabled="));
        assertThat(both).contains(
            "PROD 롤백은 사람의 승인을 거칩니다.",
            "저장된 배포 계획이 있는 이력은 당시 이미지·런타임 설정을 사용하고 현재 대상 그룹·승인 정책을 다시 확인합니다.",
            "이전 배포 계획이 없는 단일 이미지 이력은 현재 설정을 사용하므로 포트·환경·볼륨·헬스체크를 비교해 주세요.",
            "배포 계획이 없는 다중 산출물 이력은 롤백할 수 없습니다.");
    }

    private List<String> rollbackButtons(String html) {
        return java.util.regex.Pattern.compile("<button\\b[^>]*onclick=\"requestRollback[^>]*>")
            .matcher(html).results().map(java.util.regex.MatchResult::group).toList();
    }

    private ProjectImageHistory imageHistory(Long id, DeployEnvironment environment) {
        return ProjectImageHistory.builder().id(id).environment(environment)
            .historyStatus(com.portfolio.deployops.domain.ProjectImageHistoryStatus.BUILD_COMPLETED)
            .createdAt(LocalDateTime.of(2026, 10, 9, 18, 0)).build();
    }

    private String renderImageHistory(Map<String, Object> variables) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var context = new MockServletContext(); var request = new MockHttpServletRequest(context);
        request.setRequestURI("/deploy/project-detail");
        var exchange = JakartaServletWebApplication.buildApplication(context)
            .buildExchange(request, new MockHttpServletResponse());
        return engine.process("pages/project-detail", java.util.Set.of("#tab-images"),
            new WebContext(exchange, Locale.KOREAN, variables));
    }

    @Test
    void failedPinnedSafeRequestRendersDistinctRetryAndRetainsDutySeparation() {
        DeployRequest request = executionRequest(false, DeployEnvironment.PROD, DeployStatus.PROD_FAILED);
        request.setDeployStrategy(DeployStrategy.SAFE);
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "a".repeat(64));
        request.getProject().setImageRepository("registry/app");
        request.getProject().setServerGroups(List.of(com.portfolio.deployops.domain.ServerGroup.builder()
            .environment(com.portfolio.deployops.domain.ServerEnvironment.PROD)
            .servers(List.of(com.portfolio.deployops.domain.Server.builder().id(20L)
                .serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build()));
        String html = render("pages/request-detail", detailModel(request));
        assertThat(html).contains("id=\"btn-retry-deploy\"", "실패 대상 재시도", "실패·미실행 대상만")
            .doesNotContain("id=\"btn-start-deploy\"");
        request.setRequestedBy(actor(30L));
        html = render("pages/request-detail", detailModel(request));
        assertThat(html).contains("요청자는 같은 요청을 직접 실행할 수 없습니다.")
            .doesNotContain("id=\"btn-retry-deploy\"");
    }

    @Test
    void failedWithoutFixedDigestAndManualRequestCannotRenderSafeRetry() {
        DeployRequest request = executionRequest(false, DeployEnvironment.TEST, DeployStatus.TEST_FAILED);
        assertThat(render("pages/request-detail", detailModel(request))).doesNotContain("id=\"btn-retry-deploy\"");
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "a".repeat(64));
        request.setDeployStrategy(DeployStrategy.MANUAL);
        assertThat(render("pages/request-detail", detailModel(request))).doesNotContain("id=\"btn-retry-deploy\"");
    }

    private final DashboardService dashboardService = mock(DashboardService.class);
    private final DeployRequestService deployRequestService = mock(DeployRequestService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final ProjectPermissionService permissionService = mock(ProjectPermissionService.class);
    private final ProjectImageHistoryService historyService = mock(ProjectImageHistoryService.class);
    private final ApprovalRecordRepository approvalRecordRepository = mock(ApprovalRecordRepository.class);
    private final DeploymentDutySeparationService dutySeparationService =
        new DeploymentDutySeparationService(approvalRecordRepository);
    private final PageViewController controller = new PageViewController(
        dashboardService, deployRequestService, approvalService, mock(ProjectService.class), permissionService,
        mock(ServerGroupService.class), mock(DeployComponentRepository.class), mock(ServerService.class),
        mock(NginxChangeService.class), mock(UserService.class), mock(TeamService.class),
        mock(PolicyService.class), mock(AuditLogService.class), mock(SystemSettingService.class),
        historyService, dutySeparationService);
    private final SecurityUserDetails user = mock(SecurityUserDetails.class);

    @BeforeEach
    void identifyCurrentOperator() {
        when(user.getId()).thenReturn(30L);
        when(user.getRole()).thenReturn("OPERATOR");
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prodRequesterCannotSeeExecuteActionEvenWhenSuperAdmin(boolean isRollback) {
        DeployRequest request = executionRequest(isRollback, DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED);
        request.setRequestedBy(actor(30L));
        when(user.getRole()).thenReturn("SUPER_ADMIN");
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("canExecute")).isEqualTo(false);
        assertThat(model.get("canViewExecutionLogs")).isEqualTo(true);
        assertThat(html).contains("요청자는 같은 요청을 직접 실행할 수 없습니다.", "다른 운영자가 실행해야 합니다.")
            .doesNotContain("id=\"btn-start-deploy\"", "id=\"manual-complete-btn\"");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prodApproverCannotSeeExecuteActionEvenWhenSuperAdmin(boolean isRollback) {
        DeployRequest request = executionRequest(isRollback, DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED);
        when(user.getRole()).thenReturn("SUPER_ADMIN");
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of(
            ApprovalRecord.builder().approver(actor(30L)).action(ApprovalAction.APPROVED).build()));
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("canExecute")).isEqualTo(false);
        assertThat(html).contains("승인자는 같은 요청을 직접 실행할 수 없습니다.")
            .doesNotContain("id=\"btn-start-deploy\"");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentProdOperatorKeepsExecuteAction(boolean isRollback) {
        DeployRequest request = executionRequest(isRollback, DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED);
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of(
            ApprovalRecord.builder().approver(actor(12L)).action(ApprovalAction.APPROVED).build(),
            ApprovalRecord.builder().approver(actor(30L)).action(ApprovalAction.REJECTED).build()));
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("canExecute")).isEqualTo(true);
        assertThat(model.get("executionActorBlockReason")).isNull();
        assertThat(html).contains("id=\"btn-start-deploy\"");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testRequesterKeepsExecuteAction(boolean isRollback) {
        DeployRequest request = executionRequest(isRollback, DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED);
        request.setRequestedBy(actor(30L));
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("canExecute")).isEqualTo(true);
        assertThat(model.get("executionActorBlockReason")).isNull();
        assertThat(html).contains("id=\"btn-start-deploy\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"requester", "approver"})
    void blockedProdActorCanViewLiveLogsWithoutManualExecutionActions(String actorType) {
        DeployRequest request = executionRequest(false, DeployEnvironment.PROD, DeployStatus.PROD_DEPLOYING);
        request.setDeployStrategy(DeployStrategy.MANUAL);
        if ("requester".equals(actorType)) {
            request.setRequestedBy(actor(30L));
        } else {
            when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of(
                ApprovalRecord.builder().approver(actor(30L)).action(ApprovalAction.APPROVED).build()));
        }
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("canExecute")).isEqualTo(false);
        assertThat(model.get("canViewExecutionLogs")).isEqualTo(true);
        assertThat(model.get("execLive")).isEqualTo(true);
        assertThat(html).contains("id=\"exec-panel\"", "id=\"deploy-log\"", "배포 진행 로그",
                "onclick=\"reconnectLogs()\"", "다른 운영자가 실행해야 합니다.")
            .doesNotContain("id=\"btn-start-deploy\"", "id=\"manual-steps\"", "id=\"manual-complete-btn\"");
    }

    @Test
    void pendingUnverifiedRollbackShowsReasonAndDisablesApproval() {
        DeployRequest request = blockedRollback(DeployStatus.APPROVAL_PENDING);
        when(approvalService.canApproveNow(request, user)).thenReturn(true);
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("prodRollbackBlockReason").toString())
            .contains("승인 정책", "PROD 원본 요청", "검증된 CI 출처 증적");
        assertThat(html).contains("검증된 CI 출처 증적", "disabled=\"disabled\"");
        assertThat(html).doesNotContain("id=\"btn-start-deploy\"");
    }

    @Test
    void approvedUnverifiedRollbackHasNoExecuteAction() {
        DeployRequest request = blockedRollback(DeployStatus.APPROVAL_COMPLETED);
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("canExecute")).isEqualTo(false);
        assertThat(model.get("execReady")).isEqualTo(true);
        assertThat(html).contains("검증된 CI 출처 증적").doesNotContain("id=\"btn-start-deploy\"");
    }

    @Test
    void verifiedRollbackKeepsExecutionActionAvailable() {
        DeployRequest request = rollback(DeployStatus.APPROVAL_COMPLETED);
        ExtendedModelMap model = detailModel(request);

        String html = render("pages/request-detail", model);

        assertThat(model.get("prodRollbackBlockReason")).isNull();
        assertThat(model.get("canExecute")).isEqualTo(true);
        assertThat(html).contains("id=\"btn-start-deploy\"");
    }

    @Test
    void dashboardMarksBlockedRollbackWithoutRemovingItFromQueue() {
        DeployRequest request = blockedRollback(DeployStatus.APPROVAL_PENDING);
        when(dashboardService.getPendingApprovals(user)).thenReturn(List.of(request));
        when(approvalService.canApproveNow(request, user)).thenReturn(true);
        ExtendedModelMap model = new ExtendedModelMap();

        ReflectionTestUtils.invokeMethod(controller, "loadDashboard", model, user);

        assertThat(model.get("pendingApprovals")).isEqualTo(List.of(request));
        assertThat(((Map<?, ?>) model.get("rollbackBlockReasons")).get(request.getId())).isNotNull();
    }

    @Test
    void failedProdRequestDisablesUnverifiedRollbackSuggestion() {
        DeployRequest failed = failedProdRequest();
        ProjectImageHistory candidate = history();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"CI 출처 미검증"))
            .when(deployRequestService).assertRollbackSuggestionAvailable(77L,10L,user);
        ExtendedModelMap model = detailModel(failed, Optional.of(candidate));

        String html = render("pages/request-detail", model);

        assertThat(model.get("rollbackSuggestionBlockReason").toString()).contains("검증된 CI 출처 증적");
        assertThat(html).contains("추천 이미지 태그", "검증된 CI 출처 증적")
            .containsPattern("(?s)disabled=\"disabled\"[^>]*>\\s*최신 안정 이미지로 롤백 요청 생성");
    }

    @Test
    void failedProdRequestKeepsVerifiedRollbackSuggestionAvailable() {
        DeployRequest failed = failedProdRequest();
        ProjectImageHistory candidate = history();
        // Historical failed request has no project policy; current policy is evaluated by the shared service.
        assertThat(failed.getProject().getApprovalPolicy()).isNull();
        ExtendedModelMap model = detailModel(failed, Optional.of(candidate));

        String html = render("pages/request-detail", model);

        org.mockito.Mockito.verify(deployRequestService).assertRollbackSuggestionAvailable(77L,10L,user);
        assertThat(model.get("rollbackSuggestionBlockReason")).isNull();
        assertThat(html).contains("실패 직전의 최신 안정 이미지로 롤백 승인 요청을 만들 수 있습니다.")
            .contains("최신 안정 이미지로 롤백 요청 생성")
            .doesNotContain("검증된 CI 출처 증적");
    }

    private ExtendedModelMap detailModel(DeployRequest request) {
        return detailModel(request, Optional.empty());
    }

    private ExtendedModelMap detailModel(DeployRequest request, Optional<ProjectImageHistory> candidate) {
        when(deployRequestService.findById(1L, user)).thenReturn(request);
        when(permissionService.hasPermission(user, 10L,
            request.getEnvironment() == DeployEnvironment.TEST
                ? ProjectPermissionType.TEST_EXECUTE : ProjectPermissionType.PROD_EXECUTE,
            request.getEnvironment()))
            .thenReturn(true);
        when(historyService.findLatestRollbackCandidate(request)).thenReturn(candidate);
        ExtendedModelMap model = new ExtendedModelMap();
        model.addAttribute("aiEnabled", false);
        ReflectionTestUtils.invokeMethod(controller, "loadRequestDetail", model, 1L, user);
        return model;
    }

    private DeployRequest executionRequest(boolean isRollback, DeployEnvironment environment, DeployStatus status) {
        DeployRequest request = rollback(status);
        request.setEnvironment(environment);
        request.setRequestedBy(actor(11L));
        if (!isRollback) {
            request.setReason("release");
            when(deployRequestService.isRollbackRequest(request)).thenReturn(false);
        }
        return request;
    }

    private User actor(Long id) {
        return User.builder().id(id).loginId("user" + id).name("user" + id).build();
    }

    private DeployRequest failedProdRequest() {
        return DeployRequest.builder().id(1L).reqNo("REQ-FAILED")
            .project(Project.builder().id(10L).name("payment-api")
                .imageRepository("registry/payment-api").build())
            .environment(DeployEnvironment.PROD).status(DeployStatus.PROD_FAILED)
            .reason("previous deploy failed").build();
    }

    private ProjectImageHistory history() {
        return ProjectImageHistory.builder().id(77L)
            .deployRequest(DeployRequest.builder().id(66L).build())
            .environment(DeployEnvironment.PROD)
            .imageRepository("registry/payment-api")
            .imageTag("release-01234567-77")
            .imageDigest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .gitlabPipelineId(77L)
            .commitSha("0123456789abcdef0123456789abcdef01234567")
            .build();
    }

    private DeployRequest unverifiedSource(Project project) {
        return DeployRequest.builder().id(66L).project(project)
            .environment(DeployEnvironment.PROD)
            .imageTag("release-01234567-77")
            .targetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29)).imageDigest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .gitlabPipelineId(77L)
            .commitSha("0123456789abcdef0123456789abcdef01234567")
            .build();
    }

    private DeployRequest blockedRollback(DeployStatus status) {
        DeployRequest request = rollback(status);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CI 출처 미검증"))
            .when(deployRequestService).assertProductionRollbackProvenance(request);
        return request;
    }

    private DeployRequest rollback(DeployStatus status) {
        DeployRequest request = DeployRequest.builder().id(1L).reqNo("REQ-1")
            .project(Project.builder().id(10L).name("payment-api").build())
            .environment(DeployEnvironment.PROD).status(status)
            .reason("[ROLLBACK][history:77] restore").build();
        when(deployRequestService.isRollbackRequest(request)).thenReturn(true);
        return request;
    }

    private String render(String name, Map<String, Object> variables) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        MockServletContext context = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(context);
        request.setRequestURI("/deploy/request-detail");
        var exchange = JakartaServletWebApplication.buildApplication(context)
            .buildExchange(request, new MockHttpServletResponse());
        return engine.process(name, new WebContext(exchange, Locale.KOREAN, variables));
    }
}
