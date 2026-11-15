package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.controller.OtpController;
import com.portfolio.deployops.controller.ProfileApiController;
import com.portfolio.deployops.domain.AuditLog;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.AuditLogRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.sql.Connection;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthenticationAuditTransactionTest {
    final UserRepository users = mock(UserRepository.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final PasswordEncoder passwords = mock(PasswordEncoder.class);
    final TotpService totp = mock(TotpService.class);
    final Connection connection = mock(Connection.class);
    DataSourceTransactionManager transactions;

    @BeforeEach void prepare() throws Exception {
        DataSource datasource = mock(DataSource.class);
        when(datasource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transactions = new DataSourceTransactionManager(datasource);
    }

    @Test void requiredPasswordAuditFailureRollsBackAndLeavesPrincipalUnchanged() throws Exception {
        User database = user(false), sessionUser = user(false);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(database));
        when(passwords.matches("old", "stored-hash")).thenReturn(true);
        when(passwords.encode("NewSecure!Pass7")).thenReturn("new-hash");
        when(users.updatePassword(database)).thenReturn(1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAuthentication(
            eq(database), isNull(), eq("본인 비밀번호 변경"), eq(AuditResult.SUCCESS), eq("PASSWORD_CHANGED"), isNull());
        var service = proxy(new UserService(users, passwords, null, null, audit));
        var controller = new ProfileApiController(service, null);
        assertThatThrownBy(() -> controller.changePassword(java.util.Map.of(
            "currentPassword", "old", "newPassword", "NewSecure!Pass7"), new SecurityUserDetails(sessionUser)))
            .isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertThat(sessionUser.getPassword()).isEqualTo("stored-hash");
        assertThat(sessionUser.isForcePasswordChange()).isTrue();
    }

    @Test void otpEnableAuditFailureRollsBackWithoutVerifiedSession() throws Exception {
        User database = user(false);
        when(users.findById(7L)).thenReturn(Optional.of(database));
        when(totp.verify("private-secret", "123456")).thenReturn(true);
        when(users.enableOtpIfDisabled(7L, "private-secret")).thenReturn(1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAuthentication(
            eq(database), isNull(), eq("본인 OTP 등록"), eq(AuditResult.SUCCESS), eq("OTP_ENABLED"), isNull());
        var session = new MockHttpSession();
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "private-secret");
        assertThatThrownBy(() -> otp().confirm(new SecurityUserDetails(database), "123456", session))
            .isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isEqualTo("private-secret");
    }

    @Test void otpDisableAuditFailureKeepsExistingSessionFlags() throws Exception {
        User database = user(true);
        when(users.findById(7L)).thenReturn(Optional.of(database));
        when(users.updateOtp(7L, null, false)).thenReturn(1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAuthentication(
            eq(database), isNull(), eq("본인 OTP 해제"), eq(AuditResult.SUCCESS), eq("OTP_DISABLED"), isNull());
        var session = new MockHttpSession();
        session.setAttribute(OtpController.OTP_VERIFIED, true);
        assertThatThrownBy(() -> otp().off(new SecurityUserDetails(database), session)).isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
    }

    @Test void otpCommitFailureCannotPublishEnrollmentSessionFlags() throws Exception {
        transactions.setRollbackOnCommitFailure(true);
        doThrow(new java.sql.SQLException("injected commit failure")).when(connection).commit();
        User database = user(false);
        when(users.findById(7L)).thenReturn(Optional.of(database));
        when(totp.verify("private-secret", "123456")).thenReturn(true);
        when(users.enableOtpIfDisabled(7L, "private-secret")).thenReturn(1);
        var session = new MockHttpSession();
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "private-secret");
        assertThatThrownBy(() -> otp().confirm(new SecurityUserDetails(database), "123456", session))
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        verify(connection).rollback();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isEqualTo("private-secret");
    }

    @Test void otpCommitFailureCannotRemoveExistingVerification() throws Exception {
        transactions.setRollbackOnCommitFailure(true);
        doThrow(new java.sql.SQLException("injected commit failure")).when(connection).commit();
        User database = user(true);
        when(users.findById(7L)).thenReturn(Optional.of(database));
        when(users.updateOtp(7L, null, false)).thenReturn(1);
        var session = new MockHttpSession();
        session.setAttribute(OtpController.OTP_VERIFIED, true);
        assertThatThrownBy(() -> otp().off(new SecurityUserDetails(database), session))
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        verify(connection).rollback();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
    }

    @Test void absentOtpUpdateCannotRecordSuccessOrChangeSession() throws Exception {
        User database = user(true);
        when(users.findById(7L)).thenReturn(Optional.of(database));
        var session = new MockHttpSession();
        session.setAttribute(OtpController.OTP_VERIFIED, true);
        assertThatThrownBy(() -> otp().off(new SecurityUserDetails(database), session))
            .isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();
        verifyNoInteractions(audit);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
    }

    @Test void successfulOtpOnlyPublishesFlagsAfterCommitAndInvalidCodeRecordsFailure() throws Exception {
        User database = user(false);
        when(users.findById(7L)).thenReturn(Optional.of(database));
        when(totp.verify("private-secret", "123456")).thenReturn(true);
        when(users.enableOtpIfDisabled(7L, "private-secret")).thenReturn(1);
        var session = new MockHttpSession();
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "private-secret");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
            return null;
        }).when(audit).recordAuthentication(eq(database), isNull(), eq("본인 OTP 등록"), eq(AuditResult.SUCCESS), eq("OTP_ENABLED"), isNull());
        assertThat(otp().confirm(new SecurityUserDetails(database), "123456", session)).containsEntry("ok", true);
        verify(connection).commit();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
        session.removeAttribute(OtpController.OTP_VERIFIED);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "private-secret");
        assertThat(otp().confirm(new SecurityUserDetails(database), "000000", session)).containsEntry("ok", false);
        verify(audit).recordAuthentication(database, null, "본인 OTP 등록", AuditResult.FAIL, "INVALID_OTP", null);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
    }

    @Test void authenticationAuditUsesExplicitAccountAndContainsNoCredentials() {
        var repository = mock(AuditLogRepository.class);
        var masking = mock(SecretMaskingService.class);
        when(masking.mask(any())).thenAnswer(call -> call.getArgument(0));
        var actual = new AuditLogService(repository, masking);
        User account = user(true);
        account.setOtpSecret("secret-never-log");
        actual.recordAuthentication(account, null, "로그인", AuditResult.SUCCESS, "AUTHENTICATION_COMPLETED", "192.0.2.7");
        var captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.getActor()).isEqualTo("qa-account");
        assertThat(entry.getTargetId()).isEqualTo("7");
        assertThat(entry.getIpAddress()).isEqualTo("192.0.2.7");
        assertThat(entry.getTargetDesc()).isEqualTo("loginId=qa-account | AUTHENTICATION_COMPLETED")
            .doesNotContain("stored-hash", "secret-never-log", "123456");
    }

    @Test void passwordFailureAuditStorageFailureDoesNotBecomeAuthenticationSuccess() {
        when(users.findByLoginId("qa-account")).thenReturn(Optional.of(user(false)));
        doThrow(new IllegalStateException("unavailable")).when(audit).recordAuthentication(any(), any(), any(), any(), any(), any());
        var service = new UserService(users, passwords, null, null, audit);
        service.recordLoginFailure("qa-account", "192.0.2.7");
        verify(users, never()).updateLastLogin(any(), any());
    }

    private OtpController otp() { return proxy(new OtpController(totp, null, users, audit)); }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    private User user(boolean enabled) {
        return User.builder().id(7L).loginId("qa-account").name("QA").password("stored-hash")
            .role(UserRole.DEVELOPER).active(true).forcePasswordChange(true).otpEnabled(enabled).build();
    }
}
