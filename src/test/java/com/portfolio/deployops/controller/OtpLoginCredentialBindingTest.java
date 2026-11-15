package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.config.UserDetailsServiceImpl;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.service.QrCodeService;
import com.portfolio.deployops.service.TotpService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.aop.framework.ProxyFactory;

class OtpLoginCredentialBindingTest {

    private final TotpService totp = org.mockito.Mockito.mock(TotpService.class);
    private final QrCodeService qr = org.mockito.Mockito.mock(QrCodeService.class);
    private final UserRepository repository = org.mockito.Mockito.mock(UserRepository.class);
    private final UserDetailsServiceImpl details = org.mockito.Mockito.mock(UserDetailsServiceImpl.class);
    private final com.portfolio.deployops.service.AuditLogService audit = org.mockito.Mockito.mock(com.portfolio.deployops.service.AuditLogService.class);
    private final com.portfolio.deployops.service.UserService loginRecorder = new com.portfolio.deployops.service.UserService(
        repository, org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
        org.mockito.Mockito.mock(com.portfolio.deployops.repository.TeamRepository.class),
        org.mockito.Mockito.mock(com.portfolio.deployops.service.SecretCryptoService.class), audit);
    private final CommitProbeTransactionManager transactions = new CommitProbeTransactionManager();
    private final OtpLoginController controller = transactionalController();
    private final MockHttpSession session = new MockHttpSession();

    @org.junit.jupiter.api.BeforeEach
    void allowLoginTimestampUpdate() {
        when(repository.updateLastLogin(any(), any())).thenReturn(1);
        when(repository.updateOtp(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passwordStepStoresItsOriginalCredentialAndClearsPreviousOtpState() {
        session.setAttribute(OtpController.OTP_VERIFIED, true);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "previous-secret");
        User original = user("authenticated-hash", true, false);
        OtpController.beginPendingLogin(session, new SecurityUserDetails(original));
        original.setPassword("later-hash");

        assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isEqualTo("authenticated-hash");
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
    }

    @Test
    void resetWhileEnrolledOtpIsPendingRejectsBeforeCodeOrAuthentication() {
        pending("old-hash", true, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(user("reset-hash", true, true)));

        var result = controller.verify("123456", session, request(), new MockHttpServletResponse());

        assertExpired(result);
        verifyNoInteractions(totp, details);
        verify(repository, never()).updateOtp(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void resetWhileEnrollmentIsPendingCannotWriteOtpSecret() {
        pending("old-hash", false, false);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(user("reset-hash", false, true)));

        var result = controller.verify("123456", session, request(), new MockHttpServletResponse());

        assertExpired(result);
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
        verifyNoInteractions(totp, details);
        verify(repository, never()).updateOtp(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void stateAfterResetDoesNotRevealPendingEnrollmentSecret() {
        pending("old-hash", false, false);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        when(repository.findById(3L)).thenReturn(Optional.of(user("reset-hash", false, true)));

        var result = controller.state(session);

        assertThat(result).containsEntry("pending", false).doesNotContainKeys("secret", "qrDataUri", "otpauth");
        assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isNull();
        verifyNoInteractions(qr, totp);
    }

    @Test
    void oldPendingSessionWithoutOriginalCredentialFailsClosed() {
        pending("current-hash", true, false);
        session.removeAttribute(OtpController.OTP_PENDING_PASSWORD_HASH);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(user("current-hash", true, false)));

        assertExpired(controller.verify("123456", session, request(), new MockHttpServletResponse()));
        verifyNoInteractions(totp, details);
    }

    @Test
    void unchangedEnrolledCredentialAuthenticatesAndKeepsForcedPasswordRedirect() throws Exception {
        pending("temporary-hash", true, true);
        User locked = user("temporary-hash", true, true);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("registered-secret", "123456")).thenReturn(true);

        var nestedRequest = request();
        nestedRequest.setContextPath("/company");
        var result = controller.verify("123456", session, nestedRequest, new MockHttpServletResponse());

        assertThat(result).containsEntry("ok", true).containsEntry("redirect", "/company/deploy/profile?pwchange=1");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(((SecurityUserDetails) auth.getPrincipal()).getPassword()).isEqualTo("temporary-hash");
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
        assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        assertThat(OtpLoginController.class.getMethod("verify", String.class, jakarta.servlet.http.HttpSession.class,
            jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class)
            .getAnnotation(Transactional.class)).isNotNull();
        verify(audit).recordAuthentication(locked, null, "로그인", com.portfolio.deployops.domain.AuditResult.SUCCESS,
            "AUTHENTICATION_COMPLETED", "127.0.0.1");
        org.mockito.Mockito.verifyNoMoreInteractions(audit);
    }

    @Test
    void unchangedUnenrolledCredentialPersistsOtpBeforeAuthenticating() {
        pending("current-hash", false, false);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        User locked = user("current-hash", false, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("candidate-secret", "123456")).thenReturn(true);

        var result = controller.verify("123456", session, request(), new MockHttpServletResponse());

        assertThat(result).containsEntry("ok", true).containsEntry("redirect", "/deploy/dashboard");
        verify(repository).updateOtp(3L, "candidate-secret", true);
        SecurityUserDetails principal = (SecurityUserDetails) SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();
        assertThat(principal.getUser().isOtpEnabled()).isTrue();
        assertThat(principal.getUser().getOtpSecret()).isEqualTo("candidate-secret");
        verify(audit).recordAuthentication(locked, null, "본인 OTP 등록", com.portfolio.deployops.domain.AuditResult.SUCCESS,
            "OTP_ENABLED", "127.0.0.1");
        verify(audit).recordAuthentication(locked, null, "로그인", com.portfolio.deployops.domain.AuditResult.SUCCESS,
            "AUTHENTICATION_COMPLETED", "127.0.0.1");
        org.mockito.Mockito.verifyNoMoreInteractions(audit);
    }

    @Test
    void disabledPendingAccountCannotAuthenticate() {
        pending("current-hash", true, false);
        User disabled = user("current-hash", true, false);
        disabled.setActive(false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(disabled));
        assertExpired(controller.verify("123456", session, request(), new MockHttpServletResponse()));
        verifyNoInteractions(totp, details);
    }

    @Test
    void wrongOtpDoesNotAuthenticateOrClearBoundPendingCredential() {
        pending("current-hash", true, false);
        User locked = user("current-hash", true, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("registered-secret", "000000")).thenReturn(false);

        var result = controller.verify("000000", session, request(), new MockHttpServletResponse());

        assertThat(result).containsEntry("ok", false).doesNotContainKey("expired");
        verify(repository, never()).updateLastLogin(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isEqualTo("current-hash");
        verify(audit).recordAuthentication(locked, null, "로그인", com.portfolio.deployops.domain.AuditResult.FAIL,
            "INVALID_OTP", "127.0.0.1");
    }

    @Test void successfulOtpLoginAuditFailureCannotPublishAuthentication() {
        pending("current-hash", true, false);
        User locked = user("current-hash", true, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("registered-secret", "123456")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAuthentication(
            locked, null, "로그인", com.portfolio.deployops.domain.AuditResult.SUCCESS,
            "AUTHENTICATION_COMPLETED", "127.0.0.1");
        assertThatThrownBy(() -> controller.verify("123456", session, request(), new MockHttpServletResponse()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(locked.getLastLoginAt()).isNull();
    }

    @Test void invalidOtpEnrollmentWritesOnlySafeFailureAudit() {
        pending("current-hash", false, false);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        User locked = user("current-hash", false, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        assertThat(controller.verify("000000", session, request(), new MockHttpServletResponse()))
            .containsEntry("ok", false);
        verify(audit).recordAuthentication(locked, null, "본인 OTP 등록", com.portfolio.deployops.domain.AuditResult.FAIL,
            "INVALID_OTP", "127.0.0.1");
        verify(repository, never()).updateOtp(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(repository, never()).updateLastLogin(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void otpEnrollmentCommitFailureLeavesNoAuthenticationOrVerifiedSession() {
        pending("current-hash", false, false);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        User locked = user("current-hash", false, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("candidate-secret", "123456")).thenReturn(true);
        transactions.beforeCommit = () -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(locked.getLastLoginAt()).isNull();
            assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
            assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isEqualTo("current-hash");
            assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isEqualTo("candidate-secret");
        };
        transactions.failCommit = true;

        assertThatThrownBy(() -> controller.verify("123456", session, request(), new MockHttpServletResponse()))
            .isInstanceOf(TransactionSystemException.class).hasMessage("Injected commit failure");

        verify(repository).updateOtp(3L, "candidate-secret", true);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(locked.getLastLoginAt()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
    }

    @Test
    void authenticationAppearsOnlyAfterSuccessfulProxyTransactionCommit() {
        pending("current-hash", true, false);
        User locked = user("current-hash", true, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("registered-secret", "123456")).thenReturn(true);
        transactions.beforeCommit = () -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
            assertThat(session.getAttribute(OtpController.OTP_PENDING_UID)).isEqualTo(3L);
        };

        var result = controller.verify("123456", session, request(), new MockHttpServletResponse());

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(result).containsEntry("ok", true);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(session.getAttribute(OtpController.OTP_PENDING_UID)).isNull();
        var at = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(repository).updateLastLogin(org.mockito.ArgumentMatchers.eq(3L), at.capture());
        assertThat(locked.getLastLoginAt()).isEqualTo(at.getValue());
        assertThat(((SecurityUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
            .getUser().getLastLoginAt()).isEqualTo(at.getValue());
    }

    @Test
    void invalidatedPendingSessionCannotLeaveAuthenticationInReplacementSession() {
        pending("current-hash", true, false);
        User locked = user("current-hash", true, false);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(locked));
        when(details.loadUserByUsername("approver")).thenReturn(new SecurityUserDetails(locked));
        when(totp.verify("registered-secret", "123456")).thenReturn(true);
        MockHttpServletRequest request = request();
        transactions.beforeCommit = () -> {
            session.invalidate();
            assertThat(request.getSession(true)).isNotSameAs(session);
        };

        assertThatThrownBy(() -> controller.verify("123456", session, request, new MockHttpServletResponse()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        var replacement = request.getSession(false);
        assertThat(replacement).isNotNull();
        assertThat(replacement.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(replacement.getAttribute(OtpController.OTP_VERIFIED)).isNull();
    }

    private OtpLoginController transactionalController() {
        ProxyFactory factory = new ProxyFactory(new OtpLoginController(totp, qr, repository, details, loginRecorder, audit));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (OtpLoginController) factory.getProxy();
    }

    private static class CommitProbeTransactionManager extends AbstractPlatformTransactionManager {
        boolean failCommit;
        int commits;
        int rollbacks;
        Runnable beforeCommit = () -> {};

        CommitProbeTransactionManager() {
            setRollbackOnCommitFailure(true);
        }

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            beforeCommit.run();
            if (failCommit) throw new TransactionSystemException("Injected commit failure");
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }

    private void assertExpired(java.util.Map<String, Object> result) {
        assertThat(result).containsEntry("ok", false).containsEntry("expired", true);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(session.getAttribute(OtpController.OTP_PENDING_UID)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_PENDING_PASSWORD_HASH)).isNull();
    }

    private void pending(String hash, boolean enrolled, boolean forced) {
        OtpController.beginPendingLogin(session, new SecurityUserDetails(user(hash, enrolled, forced)));
    }

    private User user(String hash, boolean enrolled, boolean forced) {
        return User.builder().id(3L).loginId("approver").role(UserRole.APPROVER).active(true)
            .password(hash).forcePasswordChange(forced).otpEnabled(enrolled)
            .otpSecret(enrolled ? "registered-secret" : null).build();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/otp/verify");
        request.setSession(session);
        return request;
    }
}
