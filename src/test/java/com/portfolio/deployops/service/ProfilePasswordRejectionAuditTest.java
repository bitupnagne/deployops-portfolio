package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.controller.ProfileApiController;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilePasswordRejectionAuditTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    UserService service;
    ProfileApiController controller;
    User principal;
    final AtomicBoolean auditUnavailable = new AtomicBoolean();

    @BeforeEach void prepare() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:profile_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,password VARCHAR(100),force_change BOOLEAN,changed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE audit_fixture(actor VARCHAR(100),action VARCHAR(100),target_id VARCHAR(100),description VARCHAR(500),result VARCHAR(20),ip VARCHAR(100))");
        jdbc.update("INSERT INTO account VALUES(7,'stored-hash',TRUE,NULL)");
        var users = mock(UserRepository.class);
        when(users.findByIdForUpdate(7L)).thenAnswer(call -> Optional.of(account()));
        when(users.updatePassword(any())).thenAnswer(call -> {
            User u=call.getArgument(0); return jdbc.update("UPDATE account SET password=?,force_change=?,changed_at=? WHERE id=?",u.getPassword(),u.isForcePasswordChange(),u.getPasswordChangedAt(),u.getId());
        });
        var passwords=mock(PasswordEncoder.class);
        when(passwords.matches("Correct!Pass7","stored-hash")).thenReturn(true);
        when(passwords.encode("NewSecure!Pass7")).thenReturn("new-hash");
        var repo=mock(AuditLogRepository.class);var mask=mock(SecretMaskingService.class);
        when(mask.mask(any())).thenAnswer(call -> call.getArgument(0));
        when(repo.save(any())).thenAnswer(call -> {
            if(auditUnavailable.get()) throw new IllegalStateException("audit unavailable");
            AuditLog e=call.getArgument(0);
            jdbc.update("INSERT INTO audit_fixture VALUES(?,?,?,?,?,?)",e.getActor(),e.getAction(),e.getTargetId(),e.getTargetDesc(),e.getResult().name(),e.getIpAddress()); return e;
        });
        var audit=new AuditLogService(repo,mask);
        var target=new UserService(users,passwords,null,null,audit);
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        service=(UserService)factory.getProxy();controller=new ProfileApiController(service,null);
        principal=account();
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.19");
        request.addHeader("X-Forwarded-For","198.51.100.99");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    User account(){return User.builder().id(7L).loginId("qa-profile").name("QA").password("stored-hash").role(UserRole.OPERATOR).active(true).forcePasswordChange(true).build();}
    void credentialsUnchanged(){
        assertThat(jdbc.queryForObject("SELECT password FROM account WHERE id=7",String.class)).isEqualTo("stored-hash");
        assertThat(jdbc.queryForObject("SELECT force_change FROM account WHERE id=7",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT changed_at FROM account WHERE id=7",java.sql.Timestamp.class)).isNull();
        assertThat(principal.getPassword()).isEqualTo("stored-hash");assertThat(principal.isForcePasswordChange()).isTrue();assertThat(principal.getPasswordChangedAt()).isNull();
    }
    @Test void wrongCurrentRecordsStableFailureAfterRollbackWithTrustedActorAndRequestIp(){
        var result=controller.changePassword(Map.of("currentPassword","Wrong!Secret9","newPassword","NewSecure!Pass7"),new SecurityUserDetails(principal));
        assertThat(result.isSuccess()).isFalse();assertThat(result.getErrorCode()).isEqualTo("WRONG_PASSWORD");credentialsUnchanged();
        var row=jdbc.queryForMap("SELECT * FROM audit_fixture");assertThat(row).containsEntry("ACTOR","qa-profile").containsEntry("TARGET_ID","7").containsEntry("RESULT","FAIL").containsEntry("IP","192.0.2.19");
        assertThat(row.get("DESCRIPTION").toString()).isEqualTo("loginId=qa-profile | WRONG_CURRENT_PASSWORD").doesNotContain("Wrong!Secret9","NewSecure!Pass7","stored-hash");
    }
    @Test void policyRejectionRecordsFailureAndPreservesBothCredentialCopies(){
        var result=controller.changePassword(Map.of("currentPassword","Correct!Pass7","newPassword","short"),new SecurityUserDetails(principal));
        assertThat(result.isSuccess()).isFalse();assertThat(result.getErrorCode()).isEqualTo("INVALID_PASSWORD");credentialsUnchanged();
        assertThat(jdbc.queryForObject("SELECT description FROM audit_fixture",String.class)).isEqualTo("loginId=qa-profile | PASSWORD_POLICY_REJECTED");
    }
    @Test void failureAuditSurvivesAnUnrelatedOuterRollback(){
        new TransactionTemplate(transactions).execute(status->{service.recordPasswordChangeFailure(principal,"WRONG_CURRENT_PASSWORD");status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_fixture",Integer.class)).isEqualTo(1);credentialsUnchanged();
    }
    @Test void rejectionAuditFailurePropagatesInsteadOfReturningAHandledRejection(){
        auditUnavailable.set(true);
        assertThatThrownBy(()->controller.changePassword(Map.of("currentPassword","Wrong!Secret9","newPassword","NewSecure!Pass7"),new SecurityUserDetails(principal))).isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
        credentialsUnchanged();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_fixture",Integer.class)).isZero();
    }
    @Test void existingSuccessfulPasswordAuditFailureStillRollsBackAndNeverPublishesSessionChanges(){
        auditUnavailable.set(true);
        assertThatThrownBy(()->controller.changePassword(Map.of("currentPassword","Correct!Pass7","newPassword","NewSecure!Pass7"),new SecurityUserDetails(principal))).isInstanceOf(IllegalStateException.class);
        credentialsUnchanged();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_fixture",Integer.class)).isZero();
    }
    @Test void successfulPasswordAndAuditCommitBeforeSessionSync(){
        var result=controller.changePassword(Map.of("currentPassword","Correct!Pass7","newPassword","NewSecure!Pass7"),new SecurityUserDetails(principal));
        assertThat(result.isSuccess()).isTrue();assertThat(jdbc.queryForObject("SELECT password FROM account WHERE id=7",String.class)).isEqualTo("new-hash");assertThat(principal.getPassword()).isEqualTo("new-hash");assertThat(principal.isForcePasswordChange()).isFalse();
        assertThat(jdbc.queryForObject("SELECT result FROM audit_fixture",String.class)).isEqualTo("SUCCESS");assertThat(jdbc.queryForObject("SELECT description FROM audit_fixture",String.class)).isEqualTo("loginId=qa-profile | PASSWORD_CHANGED");
    }
    @Test void arbitraryCategoryAndUnauthenticatedActorCannotReachAuditStorage(){
        assertThatThrownBy(()->service.recordPasswordChangeFailure(principal,"secret-injected")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.recordPasswordChangeFailure(null,"WRONG_CURRENT_PASSWORD")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_fixture",Integer.class)).isZero();credentialsUnchanged();
    }
}
