package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.SystemSetting;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.SystemSettingRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SecretMigrationService {

    // SystemSettingService.SENSITIVE_KEYS 와 반드시 동일해야 한다(불일치 시 일부 키가 평문 잔존).
    private static final List<String> SENSITIVE_SETTING_KEYS = List.of(
        "ai.api-key",
        "gitlab.token",
        "gitlab.webhook.secret",
        "nexus.pass",
        "sso.shared-secret"
    );

    private final SystemSettingRepository systemSettingRepository;
    private final UserRepository userRepository;
    private final SecretCryptoService secretCryptoService;

    @Transactional(readOnly = true)
    public SecretMigrationReport inspect() {
        List<SystemSetting> settings = findPlainSensitiveSettings();
        List<User> users = findUsersWithPlainGitlabToken();
        List<SystemSetting> rotatableSettings = findSecretsNotUsingCurrentKey();
        List<User> rotatableUsers = findUsersWithGitlabTokenNotUsingCurrentKey();
        return new SecretMigrationReport(
            secretCryptoService.currentKeyId(),
            settings.size(),
            settings.stream().map(SystemSetting::getSettingKey).toList(),
            users.size(),
            users.stream().map(User::getLoginId).toList(),
            rotatableSettings.size(),
            rotatableSettings.stream().map(SystemSetting::getSettingKey).toList(),
            rotatableUsers.size(),
            rotatableUsers.stream().map(User::getLoginId).toList()
        );
    }

    @Transactional(noRollbackFor = SecretMigrationConfigurationException.class)
    public SecretMigrationReport migratePlainSecrets() {
        List<SystemSetting> settings = findPlainSensitiveSettings();
        List<User> users = findUsersWithPlainGitlabToken();
        List<SettingSecretUpdate> settingUpdates = settings.stream()
            .map(setting -> new SettingSecretUpdate(setting,
                prepareSecret(() -> secretCryptoService.encryptIfPlain(setting.getSettingValue()))))
            .toList();
        List<UserSecretUpdate> userUpdates = users.stream()
            .map(user -> new UserSecretUpdate(user,
                prepareSecret(() -> secretCryptoService.encryptIfPlain(user.getGitlabToken()))))
            .toList();

        settingUpdates.forEach(update -> {
            update.setting().setSettingValue(update.value());
            systemSettingRepository.update(update.setting());
        });
        userUpdates.forEach(update ->
            userRepository.updateGitlabToken(update.user().getId(), update.value()));

        return new SecretMigrationReport(
            secretCryptoService.currentKeyId(),
            settings.size(),
            settings.stream().map(SystemSetting::getSettingKey).toList(),
            users.size(),
            users.stream().map(User::getLoginId).toList(),
            0,
            List.of(),
            0,
            List.of()
        );
    }

    @Transactional(readOnly = true)
    public SecretMigrationReport inspectRotation() {
        List<SystemSetting> settings = findSecretsNotUsingCurrentKey();
        List<User> users = findUsersWithGitlabTokenNotUsingCurrentKey();
        return new SecretMigrationReport(
            secretCryptoService.currentKeyId(),
            0,
            List.of(),
            0,
            List.of(),
            settings.size(),
            settings.stream().map(SystemSetting::getSettingKey).toList(),
            users.size(),
            users.stream().map(User::getLoginId).toList()
        );
    }

    @Transactional(noRollbackFor = SecretMigrationConfigurationException.class)
    public SecretMigrationReport rotateToCurrentKey() {
        List<SystemSetting> settings = findSecretsNotUsingCurrentKey();
        List<User> users = findUsersWithGitlabTokenNotUsingCurrentKey();
        List<SettingSecretUpdate> settingUpdates = settings.stream()
            .map(setting -> new SettingSecretUpdate(setting,
                prepareSecret(() -> secretCryptoService.reencryptWithCurrentKey(setting.getSettingValue()))))
            .toList();
        List<UserSecretUpdate> userUpdates = users.stream()
            .map(user -> new UserSecretUpdate(user,
                prepareSecret(() -> secretCryptoService.reencryptWithCurrentKey(user.getGitlabToken()))))
            .toList();

        settingUpdates.forEach(update -> {
            update.setting().setSettingValue(update.value());
            systemSettingRepository.update(update.setting());
        });
        userUpdates.forEach(update ->
            userRepository.updateGitlabToken(update.user().getId(), update.value()));

        return new SecretMigrationReport(
            secretCryptoService.currentKeyId(),
            0,
            List.of(),
            0,
            List.of(),
            settings.size(),
            settings.stream().map(SystemSetting::getSettingKey).toList(),
            users.size(),
            users.stream().map(User::getLoginId).toList()
        );
    }

    private List<SystemSetting> findPlainSensitiveSettings() {
        return systemSettingRepository.findByKeys(SENSITIVE_SETTING_KEYS).stream()
            .filter(setting -> hasPlainSecret(setting.getSettingValue()))
            .toList();
    }

    private List<User> findUsersWithPlainGitlabToken() {
        return userRepository.findUsersWithGitlabToken().stream()
            .filter(user -> hasPlainSecret(user.getGitlabToken()))
            .toList();
    }

    private List<SystemSetting> findSecretsNotUsingCurrentKey() {
        return systemSettingRepository.findByKeys(SENSITIVE_SETTING_KEYS).stream()
            .filter(setting -> isEncryptedWithOldKey(setting.getSettingValue()))
            .toList();
    }

    private List<User> findUsersWithGitlabTokenNotUsingCurrentKey() {
        return userRepository.findUsersWithGitlabToken().stream()
            .filter(user -> isEncryptedWithOldKey(user.getGitlabToken()))
            .toList();
    }

    private boolean hasPlainSecret(String value) {
        return value != null && !value.isBlank() && !secretCryptoService.isEncrypted(value);
    }

    private boolean isEncryptedWithOldKey(String value) {
        return secretCryptoService.isEncrypted(value) && !secretCryptoService.isEncryptedWithCurrentKey(value);
    }

    private String prepareSecret(SecretValueOperation operation) {
        try {
            return operation.apply();
        } catch (IllegalStateException ex) {
            throw new SecretMigrationConfigurationException(ex.getMessage(), ex);
        }
    }

    private record SettingSecretUpdate(SystemSetting setting, String value) { }

    private record UserSecretUpdate(User user, String value) { }

    @FunctionalInterface
    private interface SecretValueOperation {
        String apply();
    }

    public static class SecretMigrationConfigurationException extends IllegalStateException {
        public SecretMigrationConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record SecretMigrationReport(
        String currentKeyId,
        int plainSettingCount,
        List<String> plainSettingKeys,
        int plainUserGitlabTokenCount,
        List<String> plainUserLoginIds,
        int rotationSettingCount,
        List<String> rotationSettingKeys,
        int rotationUserGitlabTokenCount,
        List<String> rotationUserLoginIds
    ) {
    }
}
