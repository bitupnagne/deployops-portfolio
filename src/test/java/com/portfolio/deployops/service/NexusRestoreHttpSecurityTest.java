package com.portfolio.deployops.service;

import com.portfolio.deployops.config.*;
import com.portfolio.deployops.controller.GlobalExceptionHandler;
import com.portfolio.deployops.controller.NexusRestoreApiController;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual controller, method security, filter chain and transaction proxy; local H2 only. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = NexusRestoreHttpSecurityTest.App.class)
class NexusRestoreHttpSecurityTest {
    static final String ROOT = "/api/v1/nexus/restore-requests";
    @Autowired WebApplicationContext context;
    @Autowired NexusRestoreRequestRepository requests;
    @Autowired NexusBackupRequestRepository backups;
    @Autowired ExecutionGuardRepository guard;
    @Autowired NexusRestoreJobGateway gateway;
    @Autowired AuditLogService audit;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired NexusRestoreService service;
    MockMvc mvc;

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    @Import({SecurityConfig.class, NexusRestoreApiController.class, GlobalExceptionHandler.class})
    static class App {
        @Bean UserDetailsServiceImpl userDetailsService() { return mock(UserDetailsServiceImpl.class); }
        @Bean LoginAttemptService loginAttemptService() { return mock(LoginAttemptService.class); }
        @Bean SystemSettingService settings() { return mock(SystemSettingService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean NexusRestoreRequestRepository requests() { return mock(NexusRestoreRequestRepository.class); }
        @Bean NexusBackupRequestRepository backups() { return mock(NexusBackupRequestRepository.class); }
        @Bean ExecutionGuardRepository guard() { return mock(ExecutionGuardRepository.class, CALLS_REAL_METHODS); }
        @Bean NexusRestoreJobGateway gateway() { return mock(NexusRestoreJobGateway.class); }
        @Bean DeployRequestRepository deployments() { return mock(DeployRequestRepository.class); }
        @Bean AuditLogService audit() { return mock(AuditLogService.class); }
        @Bean NexusRestoreService service(NexusRestoreRequestRepository r, NexusBackupRequestRepository b,
                ExecutionGuardRepository g, DeployRequestRepository d, NexusRestoreJobGateway f, AuditLogService a) {
            return new NexusRestoreService(r, b, g, d, f, a);
        }
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:restore_http;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }

    @BeforeEach void setup() {
        reset(requests, backups, guard, gateway, audit, users);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("DROP TABLE IF EXISTS restore_state");
        jdbc.execute("CREATE TABLE restore_state(id BIGINT PRIMARY KEY,status VARCHAR(32),owner BIGINT)");
        jdbc.update("INSERT INTO restore_state VALUES(1,'PENDING',NULL)");
        var request = new NexusRestoreRequest();
        request.setId(1L); request.setBackupRequestId(2L); request.setRequestedById(10L);
        request.setManifestSha256("a".repeat(64)); request.setArtifactId("nexus-backup-2");
        when(requests.find(1L)).thenAnswer(invocation -> {
            request.setStatus(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1", String.class));
            return Optional.of(request);
        });
        var backup = new NexusBackupRequest();
        backup.setId(2L); backup.setStatus("COMPLETED"); backup.setArtifactId("nexus-backup-2");
        backup.setManifestSha256("a".repeat(64));
        when(backups.findById(2L)).thenReturn(Optional.of(backup));
        when(gateway.ready()).thenReturn(true);
        when(guard.lockSingleton()).thenReturn(1);
        when(guard.nexusMaintenanceOwner()).thenReturn(null);when(guard.nexusPromotionOwner()).thenReturn(null);
        when(guard.nexusRestoreOwner()).thenAnswer(invocation -> jdbc.queryForObject(
            "SELECT owner FROM restore_state WHERE id=1", Long.class));
        when(requests.approve(1L, 20L)).thenAnswer(invocation -> jdbc.update(
            "UPDATE restore_state SET status='QUEUED' WHERE id=1 AND status='PENDING'"));
        when(guard.reserveNexusRestore(1L)).thenAnswer(invocation -> jdbc.update(
            "UPDATE restore_state SET owner=1 WHERE id=1 AND owner IS NULL"));
    }

    SecurityUserDetails actor(UserRole role, long id) {
        var user = new User();
        user.setId(id); user.setLoginId("qa-" + id); user.setName("QA");
        user.setRole(role); user.setActive(true); user.setPassword("TEST_HASH_ONLY");
        when(users.findById(id)).thenReturn(user);
        return new SecurityUserDetails(user);
    }

    @Test void anonymousAndExpiredSessionCannotRead() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().is3xxRedirection());
        mvc.perform(get(ROOT).session(new MockHttpSession())).andExpect(status().is3xxRedirection());
        verifyNoInteractions(requests);
    }

    @Test void missingCsrfBlocksEvenAuthorizedApprover() throws Exception {
        mvc.perform(post(ROOT + "/1/approve").with(user(actor(UserRole.APPROVER, 20))))
            .andExpect(status().isForbidden());
        verify(requests, never()).approve(anyLong(), anyLong());
    }

    @Test void operatorCannotApprove() throws Exception {
        mvc.perform(post(ROOT + "/1/approve").with(user(actor(UserRole.OPERATOR, 20))).with(csrf()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
    }

    @Test void approverCannotCreate() throws Exception {
        mvc.perform(post(ROOT).with(user(actor(UserRole.APPROVER, 20))).with(csrf())
            .contentType("application/json").content("{\"backupRequestId\":2,\"reason\":\"QA\"}"))
            .andExpect(status().isForbidden());
        verify(requests, never()).insert(any());
    }

    @Test void developerCannotReadGlobalRestoreMetadata() throws Exception {
        mvc.perform(get(ROOT).with(user(actor(UserRole.DEVELOPER, 20))))
            .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
    }

    @Test void selfApprovalIs409ThroughActualService() throws Exception {
        mvc.perform(post(ROOT + "/1/approve").with(user(actor(UserRole.APPROVER, 10))).with(csrf()))
            .andExpect(status().isConflict());
        verify(requests, never()).approve(anyLong(), anyLong());
    }

    @Test void distinctApprovalIs202AndCasLoserIs409() throws Exception {
        var actor = actor(UserRole.APPROVER, 20);
        mvc.perform(post(ROOT + "/1/approve").with(user(actor)).with(csrf())).andExpect(status().isAccepted());
        mvc.perform(post(ROOT + "/1/approve").with(user(actor)).with(csrf())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1", Long.class)).isEqualTo(1L);
    }

    @Test void auditFailureRollsBackRealJdbcWritesAndReturns500() throws Exception {
        doThrow(new IllegalStateException("FAKE_AUDIT_UNAVAILABLE")).when(audit)
            .recordCurrentRequired(anyString(), anyString(), anyString(), anyString(), any());
        mvc.perform(post(ROOT + "/1/approve").with(user(actor(UserRole.APPROVER, 20))).with(csrf()))
            .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1", Long.class)).isNull();
    }

    @Test void inactiveExistingSessionIsUnauthorizedBeforeController() throws Exception {
        var principal = actor(UserRole.OPERATOR, 20);
        var saved = new User(); saved.setId(20L); saved.setActive(false);
        saved.setRole(UserRole.OPERATOR); saved.setPassword(principal.getPassword());
        when(users.findById(20L)).thenReturn(saved);
        mvc.perform(get(ROOT).with(user(principal))).andExpect(status().isUnauthorized());
        verifyNoInteractions(requests);
    }
    private void submittedJdbcFixture() {
        jdbc.update("UPDATE restore_state SET status='RECONCILING',owner=1 WHERE id=1");
        when(requests.publicationRejected(1L)).thenAnswer(call -> jdbc.update(
                "UPDATE restore_state SET status='FAILED' WHERE id=1 AND status='RECONCILING'"));
        when(guard.releaseNexusRestore(1L)).thenAnswer(call -> jdbc.update(
                "UPDATE restore_state SET owner=NULL WHERE id=1 AND owner=1"));
    }

    @Test void definitePrewriteRejectionCommitsFailedStateAndReservationRelease() {
        submittedJdbcFixture();
        service.publicationRejected(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1", Long.class)).isNull();
    }

    @Test void definitePrewriteRejectionAuditFailureRollsBackAndRetainsHold() {
        submittedJdbcFixture();
        doThrow(new IllegalStateException("SAFE_REQUIRED_AUDIT_FAILURE")).when(audit)
                .recordCurrentRequired(anyString(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.publicationRejected(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1", String.class)).isEqualTo("RECONCILING");
        assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1", Long.class)).isEqualTo(1L);
    }


    private void recheckFixture(){
        var r=new NexusRestoreRequest();r.setId(1L);r.setBackupRequestId(2L);r.setManifestSha256("a".repeat(64));r.setSubmitted(true);r.setStatus("RECOVERY_REQUIRED");when(requests.find(1L)).thenReturn(Optional.of(r));jdbc.update("UPDATE restore_state SET status='RECOVERY_REQUIRED',owner=1 WHERE id=1");
        when(requests.insertCheck(any())).thenAnswer(c->{((NexusRestoreRecoveryCheck)c.getArgument(0)).setId(7L);return 1;});when(requests.activateCheck(1L,7L)).thenAnswer(c->jdbc.update("UPDATE restore_state SET status='CHECKING' WHERE id=1 AND status='RECOVERY_REQUIRED'"));
    }
    @Test void recheckOperatorCsrfAndApproverBoundary()throws Exception{
        recheckFixture();mvc.perform(post(ROOT+"/1/recheck").with(user(actor(UserRole.APPROVER,20))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/1/recheck").with(user(actor(UserRole.OPERATOR,20)))).andExpect(status().isForbidden());verify(requests,never()).insertCheck(any());
        mvc.perform(post(ROOT+"/1/recheck").with(user(actor(UserRole.OPERATOR,20))).with(csrf())).andExpect(status().isAccepted());
    }
    @Test void recheckRequiredAuditFailureRollsBackActualCas()throws Exception{
        recheckFixture();doThrow(new IllegalStateException("AUDIT_FAILED")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        mvc.perform(post(ROOT+"/1/recheck").with(user(actor(UserRole.OPERATOR,20))).with(csrf())).andExpect(status().isInternalServerError());assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1",String.class)).isEqualTo("RECOVERY_REQUIRED");assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1",Long.class)).isEqualTo(1L);
    }

    @Test void confirmedFailureAuditRollsBackCheckTransitionAndKeepsReservation(){
        recheckFixture();var r=requests.find(1L).orElseThrow();r.setActiveCheckId(7L);
        var check=new NexusRestoreRecoveryCheck();check.setId(7L);check.setRequestId(1L);check.setStatus("SUBMITTED");when(requests.findCheck(7L)).thenReturn(Optional.of(check));
        when(requests.finishCheck(any())).thenAnswer(c->jdbc.update("UPDATE restore_state SET status='CHECK_DONE' WHERE id=1"));
        doThrow(new IllegalStateException("AUDIT_FAILED")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        var result=new NexusRestoreJobGateway.CheckResult(2,"a".repeat(64),"VERIFIED_FAILED","PRECONTAINER_FAILURE_CONFIRMED",true,false,java.time.LocalDateTime.of(2026,10,12,1,0));
        assertThatThrownBy(()->service.acceptRecoveryCheck(1L,7L,result)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1",String.class)).isEqualTo("RECOVERY_REQUIRED");assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1",Long.class)).isEqualTo(1L);verify(guard,never()).releaseNexusRestore(any());
    }
    @Test void existingCloneCheckRequiresOperatorCsrfAndPersistsAction()throws Exception{
        recheckFixture();requests.find(1L).orElseThrow().setErrorCode("RESULT_UNCERTAIN");
        mvc.perform(post(ROOT+"/1/existing-clone-recheck").with(user(actor(UserRole.APPROVER,20))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/1/existing-clone-recheck").with(user(actor(UserRole.OPERATOR,20)))).andExpect(status().isForbidden());
        verify(requests,never()).insertCheck(any());
        mvc.perform(post(ROOT+"/1/existing-clone-recheck").with(user(actor(UserRole.OPERATOR,20))).with(csrf())).andExpect(status().isAccepted());
        verify(requests).insertCheck(argThat(c->NexusRestoreJobGateway.EXISTING_CLONE_CHECK.equals(c.getReasonCode())));
    }
    @Test void existingCloneRequestRequiredAuditRollsBackActualActivation()throws Exception{
        recheckFixture();requests.find(1L).orElseThrow().setErrorCode("RESULT_UNCERTAIN");
        doThrow(new IllegalStateException("AUDIT_FAILED")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        mvc.perform(post(ROOT+"/1/existing-clone-recheck").with(user(actor(UserRole.OPERATOR,20))).with(csrf())).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1",String.class)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1",Long.class)).isEqualTo(1L);
    }
    @Test void readinessResultAuditRollsBackActualCheckWriteAndNeverReleases()throws Exception{
        recheckFixture();var r=requests.find(1L).orElseThrow();r.setActiveCheckId(7L);r.setErrorCode("RESULT_UNCERTAIN");
        var c=new NexusRestoreRecoveryCheck();c.setId(7L);c.setRequestId(1L);c.setStatus("SUBMITTED");c.setReasonCode(NexusRestoreJobGateway.EXISTING_CLONE_CHECK);when(requests.findCheck(7L)).thenReturn(Optional.of(c));
        when(requests.finishCheck(any())).thenAnswer(call->jdbc.update("UPDATE restore_state SET status='CHECK_DONE' WHERE id=1"));
        doThrow(new IllegalStateException("AUDIT_FAILED")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),any());
        var f=new NexusExistingCloneGatewayTest();var p=f.primary();var n=f.check(p);var proof=NexusRestoreJobGateway.parseReadinessCheck(n,f.bytes(p),1,7);
        assertThatThrownBy(()->service.acceptExistingCloneCheck(1L,7L,proof)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM restore_state WHERE id=1",String.class)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT owner FROM restore_state WHERE id=1",Long.class)).isEqualTo(1L);verify(guard,never()).releaseNexusRestore(any());
    }
}
