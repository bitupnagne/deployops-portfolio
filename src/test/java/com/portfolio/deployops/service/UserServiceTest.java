package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;

import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.UserForm;
import com.portfolio.deployops.repository.TeamRepository;
import com.portfolio.deployops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TeamRepository teamRepository;
    @Mock private SecretCryptoService secretCryptoService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private UserService userService;

    @Test
    void createRejectsMissingOrBlankInitialPassword() {
        UserForm form = validForm();
        form.setPassword("   ");

        assertThatThrownBy(() -> userService.create(form))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("초기 비밀번호를 입력해 주세요.");
        verifyNoInteractions(userRepository, passwordEncoder, teamRepository, secretCryptoService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"x", "short!9", "lettersOnly", "Letters123", "123456789!"})
    void createRejectsWeakInitialPasswordBeforeCredentialOrRepositoryAccess(String password) {
        UserForm form = validForm();
        form.setPassword(password);

        assertThatThrownBy(() -> userService.create(form))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("비밀번호는");
        verifyNoInteractions(userRepository, passwordEncoder, teamRepository, secretCryptoService, auditLogService);
    }

    @Test
    void createHashesProvidedPasswordAndRequiresChangeAtFirstLogin() {
        UserForm form = validForm();
        form.setPassword("Unique!Temporary9");
        when(userRepository.existsByLoginId("test-developer")).thenReturn(false);
        when(passwordEncoder.encode("Unique!Temporary9")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = userService.create(form);

        assertThat(created.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(created.isForcePasswordChange()).isTrue();
        verify(userRepository).save(created);
    }

    @Test
    void resetHashesTemporaryPasswordAndRequiresChange() {
        User target = User.builder().id(7L).password("old-hash").forcePasswordChange(false).build();
        target.setPasswordChangedAt(java.time.LocalDateTime.now());
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        when(passwordEncoder.matches("Unique!Pass9", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("Unique!Pass9")).thenReturn("new-hash");
        when(userRepository.updatePassword(target)).thenReturn(1);

        User saved = userService.resetPassword(7L, "Unique!Pass9");

        assertThat(saved.getPassword()).isEqualTo("new-hash");
        assertThat(saved.isForcePasswordChange()).isTrue();
        assertThat(saved.getPasswordChangedAt()).isNull();
        verify(userRepository).updatePassword(target);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resetRecoversAccountWhenLegacyStoredHashCannotBeMatched() {
        User target = User.builder().id(7L).password("legacy-password-hash").forcePasswordChange(false).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        when(passwordEncoder.matches("Unique!Pass9", "legacy-password-hash"))
            .thenThrow(new IllegalArgumentException("encoded password format is unsupported"));
        when(passwordEncoder.encode("Unique!Pass9")).thenReturn("new-hash");
        when(userRepository.updatePassword(target)).thenReturn(1);

        User saved = userService.resetPassword(7L, "Unique!Pass9");

        assertThat(saved.getPassword()).isEqualTo("new-hash");
        assertThat(saved.isForcePasswordChange()).isTrue();
        assertThat(saved.getPasswordChangedAt()).isNull();
        verify(userRepository).updatePassword(target);
    }

    @Test
    void resetRejectsWeakOrReusedPasswordWithoutSaving() {
        assertThatThrownBy(() -> userService.resetPassword(7L, "short"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepository, passwordEncoder);

        User target = User.builder().id(7L).password("old-hash").build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        when(passwordEncoder.matches("Unique!Pass9", "old-hash")).thenReturn(true);
        assertThatThrownBy(() -> userService.resetPassword(7L, "Unique!Pass9"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("기존 비밀번호");
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).updatePassword(any(User.class));
    }

    @Test
    void changeUsesLockedPersistedPasswordAndUpdatesOnlyCredentials() {
        User saved = User.builder().id(7L).password("latest-hash").active(true)
            .forcePasswordChange(true).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(saved));
        when(passwordEncoder.matches("Temp!Pass9", "latest-hash")).thenReturn(true);
        when(passwordEncoder.matches("New!Pass10", "latest-hash")).thenReturn(false);
        when(passwordEncoder.encode("New!Pass10")).thenReturn("new-hash");
        when(userRepository.updatePassword(saved)).thenReturn(1);

        User changed = userService.changePassword(7L, "Temp!Pass9", "New!Pass10");

        assertThat(changed.getPassword()).isEqualTo("new-hash");
        assertThat(changed.isForcePasswordChange()).isFalse();
        assertThat(changed.getPasswordChangedAt()).isNotNull();
        verify(auditLogService).recordAuthentication(saved, null, "본인 비밀번호 변경",
            com.portfolio.deployops.domain.AuditResult.SUCCESS, "PASSWORD_CHANGED", null);
        org.mockito.Mockito.verifyNoMoreInteractions(auditLogService);
        var order = inOrder(userRepository, passwordEncoder);
        order.verify(userRepository).findByIdForUpdate(7L);
        order.verify(passwordEncoder).matches("Temp!Pass9", "latest-hash");
        order.verify(passwordEncoder).matches("New!Pass10", "latest-hash");
        order.verify(passwordEncoder).encode("New!Pass10");
        order.verify(userRepository).updatePassword(saved);
        verify(userRepository, never()).findById(7L);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resetBeforeChangeRejectsPreviouslyValidCurrentPassword() {
        User reset = User.builder().id(7L).password("admin-reset-hash")
            .active(true).forcePasswordChange(true).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(reset));
        when(passwordEncoder.matches("Old!Pass9", "admin-reset-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(7L, "Old!Pass9", "New!Pass10"))
            .isInstanceOf(BadCredentialsException.class);
        assertThat(reset.isForcePasswordChange()).isTrue();
        assertThat(reset.getPassword()).isEqualTo("admin-reset-hash");
        verify(userRepository, never()).updatePassword(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void changingToSameTemporaryPasswordCannotClearForcedChange() {
        User reset = User.builder().id(7L).password("temp-hash")
            .active(true).forcePasswordChange(true).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(reset));
        when(passwordEncoder.matches("Temp!Pass9", "temp-hash")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(7L, "Temp!Pass9", "Temp!Pass9"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("다른 새 비밀번호");
        assertThat(reset.isForcePasswordChange()).isTrue();
        verify(userRepository, never()).updatePassword(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void passwordUpdateFailsWhenExactlyOneLockedRowWasNotUpdated() {
        User saved = User.builder().id(7L).password("old-hash").active(true).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(saved));
        when(passwordEncoder.encode("Unique!Pass9")).thenReturn("new-hash");
        when(userRepository.updatePassword(saved)).thenReturn(0);

        assertThatThrownBy(() -> userService.resetPassword(7L, "Unique!Pass9"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("변경 결과");
    }

    @Test
    void selfGitlabMutationUsesLatestLockedDemotedAccount() {
        User demoted = User.builder().id(7L).password("reset-hash").role(UserRole.PROJECT_MANAGER)
            .active(false).forcePasswordChange(true).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(demoted));
        when(secretCryptoService.encryptIfPlain("personal-token")).thenReturn("encrypted-token");
        when(userRepository.save(demoted)).thenReturn(demoted);

        User saved = userService.saveGitlabToken(7L, "personal-token", "reviewer");

        assertThat(saved.getRole()).isEqualTo(UserRole.PROJECT_MANAGER);
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getPassword()).isEqualTo("reset-hash");
        assertThat(saved.isForcePasswordChange()).isTrue();
        verify(userRepository).findByIdForUpdate(7L);
        verify(userRepository, never()).findById(7L);
    }

    @Test
    void accountConfigurationMutationsReadLockedRows() {
        User target = User.builder().id(7L).role(UserRole.DEVELOPER).active(true).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);

        userService.assignGitlabUsername(7L, "mapped-user");
        userService.update(7L, validForm());
        userService.deactivate(7L);

        verify(userRepository, org.mockito.Mockito.times(3)).findByIdForUpdate(7L);
        verify(userRepository, never()).findById(7L);
    }

    private UserForm validForm() {
        UserForm form = new UserForm();
        form.setLoginId("test-developer");
        form.setName("Test Developer");
        form.setRole(UserRole.DEVELOPER);
        return form;
    }
}
