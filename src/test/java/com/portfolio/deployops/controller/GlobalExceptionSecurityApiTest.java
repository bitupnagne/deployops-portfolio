package com.portfolio.deployops.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.config.LoginAttemptService;
import com.portfolio.deployops.config.SecurityConfig;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.config.UserDetailsServiceImpl;
import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.CertificateExpiryService;
import com.portfolio.deployops.service.NginxChangeService;
import com.portfolio.deployops.service.RemoteExecutionException;
import com.portfolio.deployops.service.SystemSettingService;
import com.portfolio.deployops.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 실제 필터·메서드 인가·MVC advice를 함께 태워 승인 거부가 500으로 바뀌던 경로를 재현한다. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = GlobalExceptionSecurityApiTest.TestApp.class)
class GlobalExceptionSecurityApiTest {
    @Autowired WebApplicationContext context;
    @Autowired NginxChangeService service;
    @Autowired AuditLogService audit;
    @Autowired CertificateExpiryService certificates;
    @Autowired UserService users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, audit, certificates, users);
        when(users.findById(20L)).thenReturn(User.builder().id(20L).loginId("reviewer")
            .password("unused").role(UserRole.APPROVER).active(true).build());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void operatorPassesUrlRuleButMethodDenialIs403AndApprovalNeverRuns() throws Exception {
        when(users.findById(20L)).thenReturn(actor(UserRole.OPERATOR).getUser());
        mvc.perform(post("/api/v1/nginx-changes/1/approve").with(user(actor(UserRole.OPERATOR))).with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("이 작업을 수행할 권한이 없습니다."));
        verifyNoInteractions(service, audit, certificates);
    }

    @Test
    void resetPasswordMakesExistingSessionUnusableInActualSecurityChain() throws Exception {
        when(users.findById(20L)).thenReturn(User.builder().id(20L).loginId("reviewer")
            .password("new-hash").role(UserRole.APPROVER).active(true).build());

        mvc.perform(post("/api/v1/nginx-changes/1/approve")
                .with(user(actor(UserRole.APPROVER))).with(csrf()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, audit, certificates);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"APPROVER", "SUPER_ADMIN"})
    void authorizedApprovalStillReachesTheRealController(UserRole role) throws Exception {
        when(users.findById(20L)).thenReturn(actor(role).getUser());
        when(service.approveAndApply(1L, 20L)).thenReturn(NginxChangeRequest.builder()
            .id(1L).status(NginxChangeStatus.APPLIED).server(Server.builder().name("web").build())
            .policyName("site").serverName("site.test").listenPort(80).upstreamHost("127.0.0.1").upstreamPort(9090).build());

        mvc.perform(post("/api/v1/nginx-changes/1/approve").with(user(actor(role))).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("APPLIED"));
        verify(service).approveAndApply(1L, 20L);
    }

    @Test
    void persistedRoleDemotionRejectsFormerAdministratorInActualSecurityChain() throws Exception {
        mvc.perform(post("/api/v1/nginx-changes/1/approve")
                .with(user(actor(UserRole.SUPER_ADMIN))).with(csrf()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, audit, certificates);
    }

    @Test
    void businessConflictKeepsItsStatusAndReason() throws Exception {
        when(service.approveAndApply(1L, 20L)).thenThrow(new ResponseStatusException(
            HttpStatus.CONFLICT, "요청자와 승인자는 달라야 합니다."));
        mvc.perform(post("/api/v1/nginx-changes/1/approve").with(user(actor(UserRole.APPROVER))).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorCode").value("409 CONFLICT"))
            .andExpect(jsonPath("$.message").value("요청자와 승인자는 달라야 합니다."));
        verify(service).approveAndApply(1L, 20L);
    }

    @Test
    void remoteNginxApplyFailureIsReportedAsGatewayFailureAfterAudit() throws Exception {
        when(service.approveAndApply(1L, 20L)).thenThrow(new RemoteExecutionException(
            "nginx -t 실패로 기존 설정과 인증서를 복구했습니다."));

        mvc.perform(post("/api/v1/nginx-changes/1/approve")
                .with(user(actor(UserRole.APPROVER))).with(csrf()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorCode").value("502 BAD_GATEWAY"))
            .andExpect(jsonPath("$.message").value(
                "Nginx 설정 검증 또는 원격 적용에 실패했습니다. 변경 이력에서 상세 사유를 확인해 주세요."));

        verify(audit).recordCurrent(eq("Nginx 변경 승인 및 적용"), eq("NginxChangeRequest"), eq("1"),
            contains("적용 실패: nginx -t 실패"), eq(AuditResult.FAIL));
        verify(service).approveAndApply(1L, 20L);
    }

    @Test
    void csrfDenialStaysAtTheFilterAndNeverApproves() throws Exception {
        mvc.perform(post("/api/v1/nginx-changes/1/approve").with(user(actor(UserRole.APPROVER))))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service, audit, certificates);
    }

    @Test
    void anonymousRequestKeepsExistingLoginRedirectAndNeverApproves() throws Exception {
        mvc.perform(post("/api/v1/nginx-changes/1/approve").with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        verifyNoInteractions(service, audit, certificates);
    }

    @Test
    void mvcAuthenticationFailureIs401WithoutCredentialDetails() throws Exception {
        mvc.perform(get("/api/v1/security-error-fixture/authentication").with(user(actor(UserRole.APPROVER))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("인증이 필요합니다. 다시 로그인해 주세요."));
    }

    @Test
    void mvcAccessDenialDoesNotRevealInternalPolicyDetails() throws Exception {
        mvc.perform(get("/api/v1/security-error-fixture/authorization").with(user(actor(UserRole.APPROVER))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("이 작업을 수행할 권한이 없습니다."));
    }

    private SecurityUserDetails actor(UserRole role) {
        return new SecurityUserDetails(User.builder().id(20L).loginId("reviewer").password("unused")
            .role(role).active(true).build());
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, NginxChangeApiController.class, GlobalExceptionHandler.class, ErrorFixture.class})
    static class TestApp {
        @Bean UserDetailsServiceImpl userDetailsService() { return mock(UserDetailsServiceImpl.class); }
        @Bean LoginAttemptService loginAttemptService() { return mock(LoginAttemptService.class); }
        @Bean SystemSettingService settings() { return mock(SystemSettingService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean NginxChangeService nginxService() { return mock(NginxChangeService.class); }
        @Bean com.portfolio.deployops.service.WasVipNginxResolver wasVipResolver() { return mock(com.portfolio.deployops.service.WasVipNginxResolver.class); }
        @Bean AuditLogService audit() { return mock(AuditLogService.class); }
        @Bean CertificateExpiryService certificates() { return mock(CertificateExpiryService.class); }
    }

    @RestController
    static class ErrorFixture {
        @GetMapping("/api/v1/security-error-fixture/authentication")
        void authentication() { throw new BadCredentialsException("internal credential detail"); }

        @GetMapping("/api/v1/security-error-fixture/authorization")
        void authorization() { throw new AccessDeniedException("internal policy detail"); }
    }
}
