package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Real request and permission services exercise owner access separately from full evidence access. */
class RequestOwnerAccessTest {
    final DeployRequestRepository requests = mock(DeployRequestRepository.class);
    final ProjectRepository projects = mock(ProjectRepository.class);
    final ProjectUserPermissionRepository grants = mock(ProjectUserPermissionRepository.class);
    final ProjectPermissionService permissions = new ProjectPermissionService(grants);
    final RemoteCommandLogRepository logs = mock(RemoteCommandLogRepository.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final DeployRequestService service = new DeployRequestService(requests, projects,
        mock(ApprovalRecordRepository.class), mock(StatusHistoryRepository.class),
        mock(ServerRepository.class), mock(ProjectImageHistoryService.class),
        mock(OfficeNotificationService.class), permissions, mock(NexusMetadataService.class),
        mock(DeployComponentRepository.class), mock(DeployRequestItemRepository.class),
        mock(ExecutionPolicyService.class), mock(DeploymentPlanSnapshotService.class), audit,
        mock(ApplicationEventPublisher.class), mock(MergeRequestEvidenceRepository.class));
    SecurityUserDetails actor;
    DeployRequest request;
    MockMvc mvc;

    @BeforeEach void setup() {
        actor = user(7L, UserRole.DEVELOPER);
        request = DeployRequest.builder().id(11L).reqNo("QA-11")
            .project(Project.builder().id(1L).name("테스트").build())
            .requestedBy(actor.getUser()).environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_REQUESTED).build();
        when(requests.findByIdWithProject(11L)).thenReturn(Optional.of(request));
        when(requests.findById(11L)).thenReturn(Optional.of(request));
        when(requests.lockById(11L)).thenReturn(11L);
        when(grants.existsActivePermission(7L, 1L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(true);
        var controller = new DeployRequestApiController(service, audit, mock(ExecutionPolicyService.class),
            JsonMapper.builder().findAndAddModules().build(), logs);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == SecurityUserDetails.class; }
                public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                    NativeWebRequest r, WebDataBinderFactory b) { return actor; }
            }).build();
    }

    SecurityUserDetails user(long id, UserRole role) {
        return new SecurityUserDetails(User.builder().id(id).role(role).name("사용자 " + id).active(true).build());
    }

    @Test void ownerCanReadStatusWithoutProjectViewButCannotReadFullEvidence() throws Exception {
        mvc.perform(get("/api/v1/deploy-requests/11")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("TEST_REQUESTED"));
        assertThat(service.findById(11L, actor)).isSameAs(request);
        assertThat(service.canViewEvidence(actor, request)).isFalse();
        mvc.perform(get("/api/v1/deploy-requests/11/evidence")).andExpect(status().isForbidden());
        verifyNoInteractions(logs);
    }

    @Test void ownerCanWithdrawWithOnlyEnvironmentRequestPermission() {
        assertThat(service.canWithdraw(actor, request)).isTrue();
        when(requests.withdrawPending(11L, DeployStatus.TEST_REQUESTED, "설정 수정")).thenReturn(1);
        assertThat(service.withdraw(11L, "설정 수정", actor, "ip").getStatus()).isEqualTo(DeployStatus.CANCELLED);
        verify(requests).withdrawPending(11L, DeployStatus.TEST_REQUESTED, "설정 수정");
    }

    @Test void sameProjectRequestPermissionDoesNotExposeAnotherPersonsRequest() throws Exception {
        request.setRequestedBy(user(8L, UserRole.DEVELOPER).getUser());
        mvc.perform(get("/api/v1/deploy-requests/11")).andExpect(status().isForbidden());
        assertThat(service.canWithdraw(actor, request)).isFalse();
        assertThatThrownBy(() -> service.withdraw(11L, "철회", actor, "ip"))
            .isInstanceOf(ResponseStatusException.class);
        verify(requests, never()).withdrawPending(anyLong(), any(), anyString());
    }

    @Test void testOnlyOwnerCannotReadOrWithdrawProdRequest() throws Exception {
        request.setEnvironment(DeployEnvironment.PROD);
        mvc.perform(get("/api/v1/deploy-requests/11")).andExpect(status().isForbidden());
        assertThat(service.canWithdraw(actor, request)).isFalse();
        assertThatThrownBy(() -> service.withdraw(11L, "철회", actor, "ip"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void revokedOwnerGrantImmediatelyBlocksReadAndWithdraw() throws Exception {
        when(grants.existsActivePermission(7L, 1L, ProjectPermissionType.DEPLOY_REQUEST,
            ProjectPermissionEnvironment.TEST)).thenReturn(false);
        mvc.perform(get("/api/v1/deploy-requests/11")).andExpect(status().isForbidden());
        assertThat(service.canWithdraw(actor, request)).isFalse();
        assertThatThrownBy(() -> service.withdraw(11L, "철회", actor, "ip"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void nullActorCannotReadOwnerRequest() throws Exception {
        actor = null;
        mvc.perform(get("/api/v1/deploy-requests/11")).andExpect(status().isForbidden());
    }

    @Test void existingProjectViewerKeepsDetailAndEvidenceButNotOwnerMutation() throws Exception {
        actor = user(8L, UserRole.DEVELOPER);
        when(grants.existsActivePermission(8L, 1L, ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL)).thenReturn(true);
        mvc.perform(get("/api/v1/deploy-requests/11")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/deploy-requests/11/evidence")).andExpect(status().isOk());
        verify(logs).findByDeployRequestId(11L);
        assertThat(service.canWithdraw(actor, request)).isFalse();
    }

    @Test void existingGlobalApproverAndAdminKeepFullEvidence() throws Exception {
        for (var role : List.of(UserRole.APPROVER, UserRole.SUPER_ADMIN)) {
            actor = user(9L, role);
            mvc.perform(get("/api/v1/deploy-requests/11/evidence")).andExpect(status().isOk());
            assertThat(service.canViewEvidence(actor, request)).isTrue();
        }
    }

    @Test void ownerListUsesPagedPermissionUnionInsteadOfUnfilteredRepositoryRead() {
        var page = PageRequest.of(2, 20);
        var result = new PageImpl<DeployRequest>(List.of(), page, 0);
        when(requests.findVisibleOrOwnedWithFilter(isNull(), isNull(), eq(List.of()), eq(7L),
            isNull(), isNull(), eq(page))).thenReturn(result);
        assertThat(service.findWithFilter(null, null, null, null, page, actor)).isSameAs(result);
        verify(requests, never()).findWithFilter(any(), any(), any(), any(), any());
        verify(requests).findVisibleOrOwnedWithFilter(null, null, List.of(), 7L, null, null, page);
    }

    @Test void scopedOwnerListAllowsGrantedProjectButRejectsUnrelatedProject() {
        var page = PageRequest.of(0, 20);
        service.findWithFilter(null, 1L, null, null, page, actor);
        verify(requests).findVisibleOrOwnedWithFilter(null, 1L, List.of(), 7L, null, null, page);
        assertThatThrownBy(() -> service.findWithFilter(null, 2L, null, null, page, actor))
            .isInstanceOf(ResponseStatusException.class);
        verify(requests, never()).findVisibleOrOwnedWithFilter(any(), eq(2L), any(), any(), any(), any(), any());
    }

    @Test void evidenceLinkRendersOnlyWithExplicitFullEvidencePermission() throws Exception {
        String template = new String(getClass().getResourceAsStream("/templates/pages/request-detail.html").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        int start = template.indexOf("<div th:if=\"${canViewRequestEvidence == true}\"");
        assertThat(start).isNotNegative();
        String fragment = template.substring(start, template.indexOf("</div>", start) + 6);
        // Use a normal URL expression context for the real fragment.
        var resolver = new org.thymeleaf.templateresolver.StringTemplateResolver();
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servletContext = new org.springframework.mock.web.MockServletContext();
        var httpRequest = new org.springframework.mock.web.MockHttpServletRequest(servletContext);
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(httpRequest, new org.springframework.mock.web.MockHttpServletResponse());
        var context = new org.thymeleaf.context.WebContext(exchange);
        context.setVariable("request", request);
        context.setVariable("canViewRequestEvidence", false);
        assertThat(engine.process(fragment, context)).doesNotContain("증적 JSON", "/evidence");
        context.setVariable("canViewRequestEvidence", true);
        assertThat(engine.process(fragment, context)).contains("증적 JSON", "/11/evidence");
    }
}
