package com.portfolio.deployops.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.service.*;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class DeployRequestWithdrawalApiTest {
    private final DeployRequestService service = mock(DeployRequestService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new DeployRequestApiController(service,
            mock(AuditLogService.class), mock(ExecutionPolicyService.class), new ObjectMapper(),
            mock(RemoteCommandLogRepository.class)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void blankAndOverlongReasonFailValidationWithoutCallingService() throws Exception {
        mvc.perform(post("/api/v1/deploy-requests/46/withdraw").contentType("application/json")
            .content("{\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/v1/deploy-requests/46/withdraw").contentType("application/json")
            .content("{\"reason\":\"" + "x".repeat(401) + "\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void authenticatedPrincipalAndReasonReachTransactionalService() throws Exception {
        var user = new SecurityUserDetails(User.builder().id(7L).name("요청자").role(UserRole.DEVELOPER).build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
        try {
            when(service.withdraw(eq(46L), eq("설정 변경"), eq(user), anyString()))
                .thenReturn(DeployRequest.builder().status(DeployStatus.CANCELLED).build());
            mvc.perform(post("/api/v1/deploy-requests/46/withdraw").contentType("application/json")
                .content("{\"reason\":\"설정 변경\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
            verify(service).withdraw(eq(46L), eq("설정 변경"), eq(user), anyString());
        } finally { SecurityContextHolder.clearContext(); }
    }
}
