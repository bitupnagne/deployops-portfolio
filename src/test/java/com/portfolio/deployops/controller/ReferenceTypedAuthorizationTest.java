package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ReferenceReport;
import com.portfolio.deployops.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ContextConfiguration(classes=ReferenceTypedAuthorizationTest.Config.class) class ReferenceTypedAuthorizationTest {
    @Configuration @EnableWebMvc @Import( {
        SecurityConfig.class,ReferenceApiController.class,GlobalExceptionHandler.class
    }
    )  static class Config {
        @Bean UserDetailsServiceImpl uds() {
            return mock(UserDetailsServiceImpl.class);
        }
        @Bean LoginAttemptService attempts() {
            return mock(LoginAttemptService.class);
        }
        @Bean SystemSettingService settings() {
            return mock(SystemSettingService.class);
        }
        @Bean UserService users() {
            return mock(UserService.class);
        }
        @Bean ReferenceLookupService references() {
            return mock(ReferenceLookupService.class);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserService users;
    @Autowired ReferenceLookupService references;
    MockMvc mvc;
    SecurityUserDetails actor(UserRole role) {
        var u=User.builder().id(21L).loginId("reference-fixture").password("unused").role(role).active(true).build();
        when(users.findById(21L)).thenReturn(u);
        return new SecurityUserDetails(u);
    }

    @BeforeEach void setup() {
        reset(users,references);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void operatorCanReadOnlyServerGroupBlockers()throws Exception {
        when(references.lookup("server-group",3L)).thenReturn(ReferenceReport.builder().type("server-group").id(3L).name("QA").deletable(false).build());
        mvc.perform(get("/api/v1/references/server-group/3").with(user(actor(UserRole.OPERATOR)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("server-group")).andExpect(jsonPath("$.data.deletable").value(false));
        verify(references).lookup("server-group",3L);
        verifyNoMoreInteractions(references);
    }

    @Test
    void operatorCannotReadServerPolicyOrUnknownTypes()throws Exception {
        for(String type:new String[] {
            "server","policy","unknown","SERVER-GROUP"
        }
        )mvc.perform(get("/api/v1/references/"+type+"/3").with(user(actor(UserRole.OPERATOR)))).andExpect(status().isForbidden());
        verifyNoInteractions(references);
    }

    @Test
    void approverAndDeveloperCannotReadAnyReferenceType()throws Exception {
        for(var role:new UserRole[] {
            UserRole.APPROVER,UserRole.DEVELOPER
        }
        )for(String type:new String[] {
            "server-group","server","policy"
        }
        )mvc.perform(get("/api/v1/references/"+type+"/3").with(user(actor(role)))).andExpect(status().isForbidden());
        verifyNoInteractions(references);
    }

    @Test
    void administratorRetainsAllExistingTypeAccess()throws Exception {
        for(String type:new String[] {
            "server-group","server","policy"
        }
        ) {
            mvc.perform(get("/api/v1/references/"+type+"/3").with(user(actor(UserRole.SUPER_ADMIN)))).andExpect(status().isOk());
            verify(references).lookup(type,3L);
        }
        verifyNoMoreInteractions(references);
    }

    @Test
    void administratorUnknownTypeKeepsServiceValidation()throws Exception {
        when(references.lookup("unknown",3L)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"참조를 조회할 수 없는 종류입니다: unknown"));
        mvc.perform(get("/api/v1/references/unknown/3").with(user(actor(UserRole.SUPER_ADMIN)))).andExpect(status().isBadRequest());
        verify(references).lookup("unknown",3L);
    }
}
