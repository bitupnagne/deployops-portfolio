package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.GitlabCiVariableForm;
import com.portfolio.deployops.service.GitlabCiVariableService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class GitlabCiVariableApiControllerTest {
    private static final String PATH = "/api/v1/gitlab/deploy-projects/3/ci-variables";
    private final GitlabCiVariableService service = mock(GitlabCiVariableService.class);
    private final SecurityUserDetails actor = new SecurityUserDetails(User.builder().id(5L).role(UserRole.PROJECT_MANAGER).active(true).build());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new GitlabCiVariableApiController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                @Override public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == SecurityUserDetails.class; }
                @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) { return actor; }
            }).build();
    }

    @Test
    void getReturnsMetadataOnlyWithProtectedJsonName() throws Exception {
        when(service.list(3L, actor)).thenReturn(new GitlabCiVariableService.View(false, List.of(
            new GitlabCiVariableService.Metadata("NEXUS_REGISTRY_PASSWORD", true, true, true, "env_var", "*"))));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canManage").value(false))
            .andExpect(jsonPath("$.data.variables[0].configured").value(true))
            .andExpect(jsonPath("$.data.variables[0].protected").value(true))
            .andExpect(jsonPath("$.data.variables[0].value").doesNotExist());
    }

    @Test
    void saveBindsWriteOnlyValueAndNeverReturnsIt() throws Exception {
        String secret = "sentinel-password";
        String response = mvc.perform(put(PATH + "/NEXUS_REGISTRY_PASSWORD").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + secret + "\",\"reason\":\"암호 교체\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
        var form = ArgumentCaptor.forClass(GitlabCiVariableForm.class);
        verify(service).put(eq(3L), eq("NEXUS_REGISTRY_PASSWORD"), form.capture(), same(actor), eq("127.0.0.1"));
        assertThat(form.getValue().value()).isEqualTo(secret);
        assertThat(response).doesNotContain(secret);
        assertThat(form.getValue().toString()).doesNotContain(secret);
        assertThat(JsonMapper.builder().build().writeValueAsString(form.getValue())).doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"value\":\"secret-password\",\"reason\":\" \"}", "{\"value\":\" \",\"reason\":\"사유\"}"})
    void incompleteMutationBodyFailsValidationBeforeService(String body) throws Exception {
        mvc.perform(put(PATH + "/CI_BUILD_IMAGE").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonNeverEchoesOrLogsSecretIncludingJacksonDiagnostic() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            String secret = "sentinel-malformed-password";
            String response = mvc.perform(put(PATH + "/CI_BUILD_IMAGE").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"" + secret + "\",\"reason\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_BODY"))
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(secret);
            assertThat(appender.list).isEmpty();
            verifyNoInteractions(service);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void permissionDenialRetains403AndNoSuccessPayload() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.")).when(service)
            .put(any(), any(), any(), any(), any());
        mvc.perform(put(PATH + "/CI_BUILD_IMAGE").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"builder:1\",\"reason\":\"교체\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deleteRequiresReasonAndUsesBodyNotSecretBearingQueryString() throws Exception {
        mvc.perform(delete(PATH + "/CI_BUILD_IMAGE").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(delete(PATH + "/CI_BUILD_IMAGE").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"정리\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).delete(eq(3L), eq("CI_BUILD_IMAGE"), argThat(f -> f.reason().equals("정리")), same(actor), eq("127.0.0.1"));
    }
}
