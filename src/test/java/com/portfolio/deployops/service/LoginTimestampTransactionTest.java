package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class LoginTimestampTransactionTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final UserService service = new UserService(repository, null, null, null, audit);
    private final ProbeTransactions manager = new ProbeTransactions();
    private final TransactionTemplate transaction = new TransactionTemplate(manager);

    @Test void commitsOnlyTimestampAndSynchronizesSameUserAfterCommit() throws Exception {
        User user = user();
        when(repository.updateLastLogin(eq(7L), any())).thenReturn(1);
        transaction.executeWithoutResult(status -> {
            service.recordLogin(user);
            assertThat(user.getLastLoginAt()).isNull();
        });
        var at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).updateLastLogin(eq(7L), at.capture());
        verifyNoMoreInteractions(repository);
        assertThat(user.getLastLoginAt()).isEqualTo(at.getValue());
        assertThat(at.getValue().getNano() % 1000).isZero();
        assertThat(user.getPassword()).isEqualTo("unchanged-password");
        assertThat(user.getRole()).isEqualTo(UserRole.DEVELOPER);
        assertThat(user.isForcePasswordChange()).isTrue();
        String sql = UserRepository.class.getMethod("updateLastLogin", Long.class, LocalDateTime.class)
            .getAnnotation(org.apache.ibatis.annotations.Update.class).value()[0];
        assertThat(sql).isEqualTo("UPDATE users SET last_login_at = #{at} WHERE id = #{id} AND active = b'1'");
    }

    @Test void rollbackLeavesPrincipalTimestampUnchanged() {
        User user = user();
        when(repository.updateLastLogin(eq(7L), any())).thenReturn(1);
        transaction.executeWithoutResult(status -> { service.recordLogin(user); status.setRollbackOnly(); });
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test void missingOrInactiveUserCannotPublishSuccessfulTimestamp() {
        User user = user();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.recordLogin(user)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test void untransactionalCallIsRejectedBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.recordLogin(user())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test void requiredLoginAuditFailureRollsBackWithoutPrincipalTimestamp() {
        User user = user();
        when(repository.updateLastLogin(eq(7L), any())).thenReturn(1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordAuthentication(
            eq(user), isNull(), eq("로그인"), eq(com.portfolio.deployops.domain.AuditResult.SUCCESS),
            eq("AUTHENTICATION_COMPLETED"), eq("192.0.2.7"));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.recordLogin(user, "192.0.2.7")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test void passwordChangeKeepsPreviousLoginTimestampAndDoesNotRecordAnotherLogin() {
        var encoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        var passwordService = new UserService(repository, encoder, null, null, audit);
        User user = user();
        var previous = LocalDateTime.of(2026, 10, 10, 12, 0);
        user.setLastLoginAt(previous);
        when(repository.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(user));
        when(encoder.matches("current", "unchanged-password")).thenReturn(true);
        when(encoder.encode("Unique!Pass9")).thenReturn("new-password");
        when(repository.updatePassword(user)).thenReturn(1);
        transaction.executeWithoutResult(status -> passwordService.changePassword(7L, "current", "Unique!Pass9"));
        assertThat(user.getLastLoginAt()).isEqualTo(previous);
        assertThat(user.isForcePasswordChange()).isFalse();
        verify(repository, never()).updateLastLogin(any(), any());
    }

    @Test void ssoTicketAndTimestampShareCommitBeforeReturnedPrincipalIsPublished() {
        var tickets = mock(com.portfolio.deployops.repository.SsoRepository.class);
        User user = user();
        when(tickets.findUserByValidTicket("valid")).thenReturn(java.util.Optional.of(user));
        when(tickets.markTicketUsed("valid")).thenReturn(1);
        when(repository.updateLastLogin(eq(7L), any())).thenReturn(1);
        var sso = new SsoService(tickets, null, service);
        var principal = transaction.execute(status -> {
            var value = sso.consumeTicket("valid");
            assertThat(value.getUser().getLastLoginAt()).isNull();
            return value;
        });
        verify(tickets).markTicketUsed("valid");
        assertThat(principal.getUser().getLastLoginAt()).isNotNull();
        assertThat(manager.rollbacks).isZero();
    }

    @Test void ssoTimestampFailureRollsBackTicketTransactionWithoutPrincipalPublication() {
        var tickets = mock(com.portfolio.deployops.repository.SsoRepository.class);
        User user = user();
        when(tickets.findUserByValidTicket("valid")).thenReturn(java.util.Optional.of(user));
        when(tickets.markTicketUsed("valid")).thenReturn(1);
        var sso = new SsoService(tickets, null, service);
        assertThatThrownBy(() -> transaction.execute(status -> sso.consumeTicket("valid")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test void lostTicketClaimRollsBackBeforeTimestampWrite() {
        var tickets = mock(com.portfolio.deployops.repository.SsoRepository.class);
        User user = user();
        when(tickets.findUserByValidTicket("racing")).thenReturn(java.util.Optional.of(user));
        when(tickets.markTicketUsed("racing")).thenReturn(0);
        var sso = new SsoService(tickets, null, service);
        assertThatThrownBy(() -> transaction.execute(status -> sso.consumeTicket("racing")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(manager.rollbacks).isEqualTo(1);
        verifyNoInteractions(repository);
        assertThat(user.getLastLoginAt()).isNull();
    }

    private User user() {
        return User.builder().id(7L).password("unchanged-password").role(UserRole.DEVELOPER)
            .active(true).forcePasswordChange(true).build();
    }
    private static class ProbeTransactions extends AbstractPlatformTransactionManager {
        int rollbacks;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) {}
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
