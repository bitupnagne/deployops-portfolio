package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.service.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuntimeConfigurationApiTest {
    private final RuntimeConfigurationService service=mock(RuntimeConfigurationService.class);
    private final RuntimeLogService logs=mock(RuntimeLogService.class);
    private final SecurityUserDetails actor=new SecurityUserDetails(User.builder().id(2L).role(UserRole.OPERATOR).build());
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new RuntimeConfigurationApiController(service,logs)).setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
            public boolean supportsParameter(MethodParameter parameter){return parameter.getParameterType()==SecurityUserDetails.class;}
            public Object resolveArgument(MethodParameter parameter,ModelAndViewContainer container,NativeWebRequest request,WebDataBinderFactory factory){return actor;}
        }).build();
    }
    @Test void malformedSecretBodyReturnsNoInputExcerpt() throws Exception {
        mvc.perform(post("/api/v1/projects/1/runtime/TEST/revisions").contentType("application/json").content("{\"content\":\"PRIVATE_SENTINEL\",broken}"))
            .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_SENTINEL"))));
        verifyNoInteractions(service);
    }
    @Test void revisionEndpointReturnsOnlyMetadata() throws Exception {
        when(service.create(eq(1L),eq(DeployEnvironment.TEST),any(),eq(actor))).thenReturn(new RuntimeConfigurationService.RevisionView(9L,0L,List.of("PASSWORD"),null));
        mvc.perform(post("/api/v1/projects/1/runtime/TEST/revisions").contentType("application/json").content("{\"componentId\":0,\"content\":\"PASSWORD=PRIVATE_SENTINEL\",\"reason\":\"test\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(9)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_SENTINEL"))));
    }
    @Test void bindingIsScopedWithoutRemoteExecutionEndpoint() throws Exception {
        mvc.perform(put("/api/v1/projects/1/runtime/PROD/binding").contentType("application/json").content("{\"componentId\":0,\"mode\":\"NONE\",\"persistentLogs\":true,\"tmpMiB\":32,\"reason\":\"test\"}"))
            .andExpect(status().isOk());
        verify(service).bind(eq(1L),eq(DeployEnvironment.PROD),argThat(f->f.persistentLogs() && f.tmpMiB()==32 && f.revisionId()==null),eq(actor));
        verifyNoInteractions(logs);
    }
    @Test void logEndpointUsesMappedIdentifiersRatherThanHostPath() throws Exception {
        when(logs.read(1L,DeployEnvironment.TEST,3L,0L,20,actor)).thenReturn(new RuntimeLogService.LogView("literal log",false));
        mvc.perform(get("/api/v1/projects/1/runtime/TEST/logs?serverId=3&componentId=0&lines=20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.text").value("literal log"));
        verify(logs).read(1L,DeployEnvironment.TEST,3L,0L,20,actor);
    }
}
