package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.SystemSetting;
import com.portfolio.deployops.repository.SystemSettingRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SystemSettingServiceSecretViewTest {

    private final SystemSettingRepository repository = mock(SystemSettingRepository.class);
    private final SecretCryptoService crypto = mock(SecretCryptoService.class);
    private final SystemSettingService service = new SystemSettingService(repository, crypto);

    @Test
    void clientViewReportsConfiguredSecretsWithoutReturningStoredValues() {
        when(repository.findByCategory("integration")).thenReturn(List.of(
            setting("gitlab.url", "https://gitlab.example"),
            setting("gitlab.token", "enc:v2:opaque-gitlab"),
            setting("gitlab.webhook.secret", "legacy-webhook-secret"),
            setting("nexus.pass", "enc:v2:opaque-nexus"),
            setting("sso.shared-secret", "")));
        when(repository.findByCategory("ai")).thenReturn(List.of(
            setting("ai.model", "test-model"),
            setting("ai.api-key", "enc:v2:opaque-ai")));

        Map<String, Object> integration = service.getCategoryForClient("integration");
        Map<String, Object> ai = service.getCategoryForClient("ai");

        assertThat(integration).containsEntry("gitlab.url", "https://gitlab.example");
        assertThat(integration.keySet()).doesNotContain("gitlab.token", "gitlab.webhook.secret", "nexus.pass", "sso.shared-secret");
        assertThat(integration.get("configuredSecrets")).isEqualTo(Map.of(
            "gitlab.token", true, "gitlab.webhook.secret", true, "nexus.pass", true, "sso.shared-secret", false));
        assertThat(ai.keySet()).doesNotContain("ai.api-key");
        assertThat(ai.get("configuredSecrets")).isEqualTo(Map.of("ai.api-key", true));
        verify(crypto, never()).decryptIfEncrypted(any());
    }

    @Test
    void blankSecretRetainsStoredValueWhileNullClearsAndNewValueRotates() {
        SystemSetting existing = setting("gitlab.token", "existing-ciphertext");
        when(repository.findById("gitlab.token")).thenReturn(Optional.of(existing));
        when(crypto.encryptIfPlain("replacement")).thenReturn("new-ciphertext");

        service.saveAll("integration", Map.of("gitlab.token", " "));
        verify(repository, never()).save(any());
        assertThat(existing.getSettingValue()).isEqualTo("existing-ciphertext");

        Map<String, String> clear = new HashMap<>();
        clear.put("gitlab.token", null);
        service.saveAll("integration", clear);
        assertThat(existing.getSettingValue()).isEmpty();

        service.saveAll("integration", Map.of("gitlab.token", "replacement"));
        verify(repository, org.mockito.Mockito.times(2)).save(existing);
        assertThat(existing.getSettingValue()).isEqualTo("new-ciphertext");
        verify(crypto).encryptIfPlain("replacement");
    }

    private static SystemSetting setting(String key, String value) {
        return SystemSetting.builder().settingKey(key).settingValue(value).category("integration").build();
    }
}
