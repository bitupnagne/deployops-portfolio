package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.service.ProjectPermissionManagementService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

class ProjectPermissionApiControllerTest {
    private final ProjectPermissionManagementService service = mock(ProjectPermissionManagementService.class);
    private final SecurityUserDetails actor = new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).active(true).build());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // URL/CSRF 경계는 SecurityAuthorizationBoundaryTest, 최신 DB 권한은 실제 service 테스트에서 검증한다.
        mvc = MockMvcBuilders.standaloneSetup(new ProjectPermissionApiController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == SecurityUserDetails.class; }
                @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) { return actor; }
            }).build();
    }

    @Test
    void validJsonBindsEnumsAndExplicitEmptyListCanRevoke() throws Exception {
        String body = "{\"grants\":[],\"reason\":\"업무 종료\",\"expectedRevision\":\"" + "a".repeat(64) + "\"}";
        mvc.perform(put("/api/v1/projects/10/permissions/2").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        var form = ArgumentCaptor.forClass(ProjectPermissionForm.class);
        verify(service).replace(eq(10L), eq(2L), form.capture(), same(actor), eq("127.0.0.1"));
        assertThat(form.getValue().grants()).isEmpty(); assertThat(form.getValue().reason()).isEqualTo("업무 종료");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"grants\":null}", "{\"grants\":[null]}",
        "{\"grants\":[{\"permission\":\"NOT_A_PERMISSION\",\"environment\":\"TEST\"}]}",
        "{\"grants\":[],\"reason\":\" \"}"})
    void malformedOrIncompleteBodyNeverCallsMutation(String body) throws Exception {
        mvc.perform(put("/api/v1/projects/10/permissions/2").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service);
    }

    @Test
    void optimisticConflictKeepsHttp409AndDoesNotReturnSuccess() throws Exception {
        when(service.replace(anyLong(), anyLong(), any(), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "최신 권한을 불러오세요."));
        mvc.perform(put("/api/v1/projects/10/permissions/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"grants\":[],\"reason\":\"사유\",\"expectedRevision\":\"" + "a".repeat(64) + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void userPickerReturnsOnlyTheSafeSummaryContract() throws Exception {
        when(service.projectUsers(10L, actor)).thenReturn(new ProjectPermissionView.ProjectUsers(10L, "프로젝트", List.of(
            new ProjectPermissionView.UserSummary(2L, "dev", "개발자", UserRole.DEVELOPER, "개발자", true))));
        mvc.perform(get("/api/v1/projects/10/permissions"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.users[0].loginId").value("dev"))
            .andExpect(jsonPath("$.data.users[0].password").doesNotExist())
            .andExpect(jsonPath("$.data.users[0].gitlabToken").doesNotExist())
            .andExpect(jsonPath("$.data.users[0].otpSecret").doesNotExist());
    }
}
