package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes=NexusBackupHttpSecurityTest.Config.class)
class NexusBackupHttpSecurityTest {
    @Configuration @EnableWebMvc @Import({SecurityConfig.class,NexusBackupApiController.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean UserDetailsServiceImpl uds(){return mock(UserDetailsServiceImpl.class);}
        @Bean LoginAttemptService attempts(){return mock(LoginAttemptService.class);}
        @Bean SystemSettingService settings(){return mock(SystemSettingService.class);}
        @Bean UserService users(){return mock(UserService.class);}
        @Bean NexusBackupService backup(){return mock(NexusBackupService.class);}
    }
    @Autowired WebApplicationContext context;
    @Autowired UserService users;
    @Autowired NexusBackupService backup;
    MockMvc mvc;
    SecurityUserDetails actor(UserRole role){var u=User.builder().id(21L).loginId("backup-fixture").password("unused").role(role).active(true).build();when(users.findById(21L)).thenReturn(u);return new SecurityUserDetails(u);}
    @BeforeEach void setup(){reset(backup,users);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
    @Test void readinessExposesOnlySafeStateToInfraRoles()throws Exception{when(backup.ready()).thenReturn(false);when(backup.readinessCode()).thenReturn("SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE");for(var role:new UserRole[]{UserRole.OPERATOR,UserRole.APPROVER,UserRole.SUPER_ADMIN})mvc.perform(get("/api/v1/nexus/backup-requests/readiness").with(user(actor(role)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(false)).andExpect(jsonPath("$.data.reasonCode").value("SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE")).andExpect(jsonPath("$.data.length()").value(2));verify(backup,times(3)).ready();}
    @ParameterizedTest @EnumSource(value=UserRole.class,names={"DEVELOPER","PROJECT_MANAGER"}) void readinessDeniesOtherRoles(UserRole role)throws Exception{mvc.perform(get("/api/v1/nexus/backup-requests/readiness").with(user(actor(role)))).andExpect(status().isForbidden());verifyNoInteractions(backup);}
    @Test void missingCsrfDeniedBeforeSubmission()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests").with(user(actor(UserRole.OPERATOR))).contentType("application/json").content("{\"reason\":\"유지보수\",\"externalWritersStopped\":true}")).andExpect(status().isForbidden());verifyNoInteractions(backup);}
    @ParameterizedTest @EnumSource(value=UserRole.class,names={"DEVELOPER","PROJECT_MANAGER"}) void globalInfraRoleDeniesReadAndPost(UserRole role)throws Exception{var u=actor(role);mvc.perform(get("/api/v1/nexus/backup-requests").with(user(u))).andExpect(status().isForbidden());mvc.perform(post("/api/v1/nexus/backup-requests").with(user(u)).with(csrf()).contentType("application/json").content("{\"reason\":\"유지보수\",\"externalWritersStopped\":true}")).andExpect(status().isForbidden());verifyNoInteractions(backup);}
    @Test void validCsrfOperatorRequest201()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests").with(user(actor(UserRole.OPERATOR))).with(csrf()).contentType("application/json").content("{\"reason\":\"유지보수\",\"externalWritersStopped\":true}")).andExpect(status().isCreated());verify(backup).create("유지보수",true,21L);}
    @Test void operatorCannotApproveDespiteValidCsrf()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests/1/approve").with(user(actor(UserRole.OPERATOR))).with(csrf()).contentType("application/json").content("{\"externalWritersStopped\":true}")).andExpect(status().isForbidden());verifyNoInteractions(backup);}
    @Test void approver202AndServiceSelfApproval409()throws Exception{var u=actor(UserRole.APPROVER);mvc.perform(post("/api/v1/nexus/backup-requests/1/approve").with(user(u)).with(csrf()).contentType("application/json").content("{\"externalWritersStopped\":true}")).andExpect(status().isAccepted());doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"요청자와 승인자는 달라야 합니다.")).when(backup).approve(1L,21L,true);mvc.perform(post("/api/v1/nexus/backup-requests/1/approve").with(user(u)).with(csrf()).contentType("application/json").content("{\"externalWritersStopped\":true}")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("요청자와 승인자는 달라야 합니다."));}
    @Test void operatorRecheck202DoesNotInvokeApprovalOrBackup()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests/1/recheck").with(user(actor(UserRole.OPERATOR))).with(csrf())).andExpect(status().isAccepted());verify(backup).requestRecoveryCheck(1L,21L);verifyNoMoreInteractions(backup);}
    @Test void approverCannotRecheckAndMissingCsrfRejectsOperator()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests/1/recheck").with(user(actor(UserRole.APPROVER))).with(csrf())).andExpect(status().isForbidden());mvc.perform(post("/api/v1/nexus/backup-requests/1/recheck").with(user(actor(UserRole.OPERATOR)))).andExpect(status().isForbidden());verifyNoInteractions(backup);}
    @Test void explicitExpectedCheckSupersedeIsOperator202WithCsrf()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests/1/rechecks/7/supersede").with(user(actor(UserRole.OPERATOR))).with(csrf())).andExpect(status().isAccepted());verify(backup).supersedeRecoveryCheck(1L,7L,21L);}
    @Test void supersedeDeniesApproverAndMissingCsrf()throws Exception{mvc.perform(post("/api/v1/nexus/backup-requests/1/rechecks/7/supersede").with(user(actor(UserRole.APPROVER))).with(csrf())).andExpect(status().isForbidden());mvc.perform(post("/api/v1/nexus/backup-requests/1/rechecks/7/supersede").with(user(actor(UserRole.OPERATOR)))).andExpect(status().isForbidden());verifyNoInteractions(backup);}
}
