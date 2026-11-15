package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.service.QrCodeService;
import com.portfolio.deployops.service.TotpService;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

class OtpAuthenticatedCurrentStateTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final TotpService totp = mock(TotpService.class);
    private final QrCodeService qr = mock(QrCodeService.class);
    private final com.portfolio.deployops.service.AuditLogService audit = mock(com.portfolio.deployops.service.AuditLogService.class);
    private final OtpController controller = transactionalController();
    private final MockHttpSession session = new MockHttpSession();

    private OtpController transactionalController() {
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) {}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) {}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {}
        };
        var factory = new org.springframework.aop.framework.ProxyFactory(new OtpController(totp, qr, repository, audit));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (OtpController) factory.getProxy();
    }

    @org.junit.jupiter.api.BeforeEach void allowDisableUpdate() {
        when(repository.updateOtp(any(), any(), anyBoolean())).thenReturn(1);
    }

    private User user(boolean enabled, String secret) {
        return User.builder().id(71L).loginId("qa-otp-test").name("QA")
                .role(UserRole.DEVELOPER).active(true).otpEnabled(enabled).otpSecret(secret).build();
    }

    @Test
    void registeredDatabaseStateHidesSetupForStaleUnenrolledPrincipal() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(true, "current-secret")));
        var principal = new SecurityUserDetails(user(false, null));
        assertThat(controller.setupData(principal, session)).containsEntry("already", true)
                .doesNotContainKeys("secret", "otpauth", "qrDataUri");
        var model = new ExtendedModelMap();
        assertThat(controller.setup(principal, session, model)).isEqualTo("otp-setup");
        assertThat(model).containsEntry("already", true).doesNotContainKey("secret");
        assertThat(controller.otpPage(principal)).isEqualTo("otp");
        verifyNoInteractions(totp, qr);
    }

    @Test
    void removedDatabaseEnrollmentAllowsSetupDespiteStaleEnrolledPrincipal() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(false, null)));
        when(totp.generateSecret()).thenReturn("new-setup-secret");
        var principal = new SecurityUserDetails(user(true, "old-secret"));
        assertThat(controller.setupData(principal, session)).containsEntry("already", false)
                .containsEntry("secret", "new-setup-secret");
        assertThat(controller.otpPage(principal)).isEqualTo("redirect:/deploy/dashboard");
    }

    @Test
    void samePrincipalRegistersThenImmediatelyReadsAlreadyEnrolled() {
        var database = new AtomicReference<>(user(false, null));
        when(repository.findById(71L)).thenAnswer(inv -> Optional.of(database.get()));
        when(totp.verify("candidate-secret", "123456")).thenReturn(true);
        when(repository.enableOtpIfDisabled(71L, "candidate-secret")).thenAnswer(inv -> {
            database.set(user(true, "candidate-secret")); return 1;
        });
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        var principal = new SecurityUserDetails(user(false, null));
        assertThat(controller.confirm(principal, "123456", session)).containsEntry("ok", true);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
        assertThat(controller.setupData(principal, session)).containsEntry("already", true)
                .doesNotContainKey("secret");
    }

    @Test
    void samePrincipalDisablesThenReadsFreshRegistrationState() {
        var database = new AtomicReference<>(user(true, "old-secret"));
        when(repository.findById(71L)).thenAnswer(inv -> Optional.of(database.get()));
        doAnswer(inv -> {database.set(user(false, null)); return 1;})
                .when(repository).updateOtp(71L, null, false);
        when(totp.generateSecret()).thenReturn("new-setup-secret");
        session.setAttribute(OtpController.OTP_VERIFIED, true);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "stale-setup-secret");
        var principal = new SecurityUserDetails(user(true, "old-secret"));
        assertThat(controller.off(principal, session)).containsEntry("ok", true);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
        assertThat(controller.setupData(principal, session)).containsEntry("already", false)
                .containsEntry("secret", "new-setup-secret");
    }

    @Test
    void verificationUsesCurrentSecretAndRejectsOldPrincipalSecret() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(true, "current-secret")));
        when(totp.verify("current-secret", "123456")).thenReturn(true);
        var principal = new SecurityUserDetails(user(true, "old-secret"));
        assertThat(controller.verifyAjax(principal, "123456", session)).containsEntry("ok", true);
        assertThat(controller.verify(principal, "123456", session)).isEqualTo("redirect:/deploy/dashboard");
        verify(totp, times(2)).verify("current-secret", "123456");
        verify(totp, never()).verify("old-secret", "123456");
    }

    @Test
    void disabledEnrollmentCannotVerifyUsingOldPrincipalSecret() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(false, null)));
        var principal = new SecurityUserDetails(user(true, "old-secret"));
        assertThat(controller.verifyAjax(principal, "123456", session)).containsEntry("ok", false);
        assertThat(controller.verify(principal, "123456", session)).isEqualTo("redirect:/otp?error");
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        verifyNoInteractions(totp);
    }

    @Test
    void staleSetupCannotOverwriteRegisteredSecretThroughEitherEndpoint() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(true, "current-secret")));
        var principal = new SecurityUserDetails(user(false, null));
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "stale-secret");
        assertThat(controller.confirm(principal, "123456", session)).containsEntry("ok", false);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "stale-secret");
        assertThat(controller.confirmSetup(principal, "123456", session)).isEqualTo("redirect:/otp/setup?error");
        verify(repository, never()).enableOtpIfDisabled(any(), any());
        verify(repository, never()).updateOtp(any(), any(), anyBoolean());
        verifyNoInteractions(totp);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
    }

    @Test
    void losingConditionalEnableRaceDoesNotVerifySessionOrReportSuccess() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(false, null)));
        when(totp.verify("candidate-secret", "123456")).thenReturn(true);
        when(repository.enableOtpIfDisabled(71L, "candidate-secret")).thenReturn(0);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        assertThat(controller.confirm(new SecurityUserDetails(user(false, null)), "123456", session))
                .containsEntry("ok", false);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
    }

    @Test
    void missingInactiveAndMismatchedUsersCannotReadOrMutateOtp() {
        var inactive = user(false, null); inactive.setActive(false);
        var wrongId = user(false, null); wrongId.setId(72L);
        var wrongLogin = user(false, null); wrongLogin.setLoginId("another-account");
        var principal = new SecurityUserDetails(user(false, null));
        for (Optional<User> state : List.of(Optional.<User>empty(), Optional.of(inactive), Optional.of(wrongId), Optional.of(wrongLogin))) {
            when(repository.findById(71L)).thenReturn(state);
            assertThat(controller.setupData(principal, session)).containsEntry("error", "unauthorized");
            assertThat(controller.confirm(principal, "123456", session)).containsEntry("ok", false);
            assertThat(controller.off(principal, session)).containsEntry("ok", false);
            assertThat(controller.disable(principal, session)).isEqualTo("redirect:/login");
        }
        verify(repository, never()).enableOtpIfDisabled(any(), any());
        verify(repository, never()).updateOtp(any(), any(), anyBoolean());
        verifyNoInteractions(totp, qr);
    }

    @Test
    void pageEnrollmentAndDisableClearBothSessionOtpAttributes() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(false, null)));
        when(totp.verify("candidate-secret", "123456")).thenReturn(true);
        when(repository.enableOtpIfDisabled(71L, "candidate-secret")).thenReturn(1);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        var principal = new SecurityUserDetails(user(false, null));
        assertThat(controller.confirmSetup(principal, "123456", session))
                .isEqualTo("redirect:/deploy/dashboard?otp=on");
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "old-setup-secret");
        assertThat(controller.disable(principal, session)).isEqualTo("redirect:/deploy/dashboard?otp=off");
        verify(repository).updateOtp(71L, null, false);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
    }

    @Test
    void invalidCodeKeepsPendingSecretWithoutWritingOrVerifying() {
        when(repository.findById(71L)).thenReturn(Optional.of(user(false, null)));
        session.setAttribute(OtpController.OTP_SETUP_SECRET, "candidate-secret");
        assertThat(controller.confirm(new SecurityUserDetails(user(false, null)), "000000", session))
                .containsEntry("ok", false);
        assertThat(session.getAttribute(OtpController.OTP_SETUP_SECRET)).isEqualTo("candidate-secret");
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isNull();
        verify(repository, never()).enableOtpIfDisabled(any(), any());
    }
}
