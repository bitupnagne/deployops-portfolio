package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.SystemSetting;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.SystemSettingRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SecretMigrationServiceTest {

    private final SystemSettingRepository settingRepository = Mockito.mock(SystemSettingRepository.class);
    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final SecretCryptoService cryptoService = new SecretCryptoService("migration-test-key");
    private final SecretMigrationService migrationService =
        new SecretMigrationService(settingRepository, userRepository, cryptoService);

    @Test
    void inspectReportsOnlyPlainSecretOwners() {
        when(settingRepository.findByKeys(any())).thenReturn(List.of(
            SystemSetting.builder().settingKey("gitlab.token").settingValue("plain-token").build(),
            SystemSetting.builder().settingKey("nexus.pass").settingValue(cryptoService.encryptIfPlain("encrypted-pass")).build()
        ));
        when(userRepository.findUsersWithGitlabToken()).thenReturn(List.of(
            User.builder().loginId("plain-user").gitlabToken("plain-user-token").build(),
            User.builder().loginId("encrypted-user").gitlabToken(cryptoService.encryptIfPlain("encrypted-user-token")).build()
        ));

        SecretMigrationService.SecretMigrationReport report = migrationService.inspect();

        assertThat(report.plainSettingCount()).isEqualTo(1);
        assertThat(report.plainSettingKeys()).containsExactly("gitlab.token");
        assertThat(report.plainUserGitlabTokenCount()).isEqualTo(1);
        assertThat(report.plainUserLoginIds()).containsExactly("plain-user");
    }

    @Test
    void migrateEncryptsOnlyPlainSecrets() {
        SystemSetting plainSetting = SystemSetting.builder()
            .settingKey("sso.shared-secret")
            .settingValue("plain-secret")
            .category("integration")
            .build();
        User plainUser = User.builder()
            .id(10L)
            .loginId("plain-user")
            .gitlabToken("plain-user-token")
            .build();

        when(settingRepository.findByKeys(any())).thenReturn(List.of(plainSetting));
        when(userRepository.findUsersWithGitlabToken()).thenReturn(List.of(plainUser));

        SecretMigrationService.SecretMigrationReport report = migrationService.migratePlainSecrets();

        ArgumentCaptor<SystemSetting> settingCaptor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(settingRepository).update(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getSettingValue()).startsWith("enc:v2:primary:");
        assertThat(cryptoService.decryptIfEncrypted(settingCaptor.getValue().getSettingValue())).isEqualTo("plain-secret");

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updateGitlabToken(eq(10L), tokenCaptor.capture());
        assertThat(tokenCaptor.getValue()).startsWith("enc:v2:primary:");
        assertThat(cryptoService.decryptIfEncrypted(tokenCaptor.getValue())).isEqualTo("plain-user-token");

        assertThat(report.plainSettingCount()).isEqualTo(1);
        assertThat(report.plainUserGitlabTokenCount()).isEqualTo(1);
    }

    @Test
    void migrateSkipsAlreadyEncryptedSecrets() {
        when(settingRepository.findByKeys(any())).thenReturn(List.of(
            SystemSetting.builder().settingKey("gitlab.token").settingValue(cryptoService.encryptIfPlain("token")).build()
        ));
        when(userRepository.findUsersWithGitlabToken()).thenReturn(List.of(
            User.builder().id(11L).loginId("encrypted-user").gitlabToken(cryptoService.encryptIfPlain("token")).build()
        ));

        SecretMigrationService.SecretMigrationReport report = migrationService.migratePlainSecrets();

        verify(settingRepository, never()).update(any());
        verify(userRepository, never()).updateGitlabToken(any(), any());
        assertThat(report.plainSettingCount()).isZero();
        assertThat(report.plainUserGitlabTokenCount()).isZero();
    }

    @Test
    void missingEncryptionKeyFailsBeforePersistingAnyPlainSecret() {
        SecretMigrationService noKeyService = new SecretMigrationService(
            settingRepository, userRepository, new SecretCryptoService(""));
        when(settingRepository.findByKeys(any())).thenReturn(List.of(
            SystemSetting.builder().settingKey("gitlab.token").settingValue("plain-token").build()));
        when(userRepository.findUsersWithGitlabToken()).thenReturn(List.of(
            User.builder().id(15L).loginId("plain-user").gitlabToken("plain-user-token").build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(noKeyService::migratePlainSecrets)
            .isInstanceOf(SecretMigrationService.SecretMigrationConfigurationException.class);

        verify(settingRepository, never()).update(any());
        verify(userRepository, never()).updateGitlabToken(any(), any());
    }

    @Test
    void rotateReencryptsOldKeySecretsWithCurrentKey() {
        SecretCryptoService oldCryptoService = new SecretCryptoService("old-key", "old", "");
        SecretCryptoService newCryptoService = new SecretCryptoService("new-key", "new", "old=old-key");
        SecretMigrationService rotationService =
            new SecretMigrationService(settingRepository, userRepository, newCryptoService);
        SystemSetting oldSetting = SystemSetting.builder()
            .settingKey("gitlab.token")
            .settingValue(oldCryptoService.encryptIfPlain("old-setting-token"))
            .category("integration")
            .build();
        User oldUser = User.builder()
            .id(20L)
            .loginId("old-user")
            .gitlabToken(oldCryptoService.encryptIfPlain("old-user-token"))
            .build();

        when(settingRepository.findByKeys(any())).thenReturn(List.of(oldSetting));
        when(userRepository.findUsersWithGitlabToken()).thenReturn(List.of(oldUser));

        SecretMigrationService.SecretMigrationReport report = rotationService.rotateToCurrentKey();

        ArgumentCaptor<SystemSetting> settingCaptor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(settingRepository).update(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getSettingValue()).startsWith("enc:v2:new:");
        assertThat(newCryptoService.decryptIfEncrypted(settingCaptor.getValue().getSettingValue()))
            .isEqualTo("old-setting-token");

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updateGitlabToken(eq(20L), tokenCaptor.capture());
        assertThat(tokenCaptor.getValue()).startsWith("enc:v2:new:");
        assertThat(newCryptoService.decryptIfEncrypted(tokenCaptor.getValue())).isEqualTo("old-user-token");

        assertThat(report.currentKeyId()).isEqualTo("new");
        assertThat(report.rotationSettingCount()).isEqualTo(1);
        assertThat(report.rotationUserGitlabTokenCount()).isEqualTo(1);
    }

    @Test
    void rotationPreparesEverySecretBeforePersistingAnyUpdate() {
        SecretCryptoService oldCryptoService = new SecretCryptoService("old-key", "old", "");
        SecretCryptoService newCryptoService = new SecretCryptoService("new-key", "new", "old=old-key");
        SecretMigrationService rotationService =
            new SecretMigrationService(settingRepository, userRepository, newCryptoService);
        when(settingRepository.findByKeys(any())).thenReturn(List.of(
            SystemSetting.builder().settingKey("ai.api-key")
                .settingValue(oldCryptoService.encryptIfPlain("known-key-secret")).build(),
            SystemSetting.builder().settingKey("gitlab.token")
                .settingValue(new SecretCryptoService("unknown-key", "unknown", "")
                    .encryptIfPlain("unavailable-key-secret")).build()));
        when(userRepository.findUsersWithGitlabToken()).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(rotationService::rotateToCurrentKey)
            .isInstanceOf(SecretMigrationService.SecretMigrationConfigurationException.class);

        verify(settingRepository, never()).update(any());
        verify(userRepository, never()).updateGitlabToken(any(), any());
    }
}
