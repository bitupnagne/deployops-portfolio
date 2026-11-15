package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.SystemSetting;
import com.portfolio.deployops.repository.SystemSettingRepository;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final List<String> SENSITIVE_KEYS = List.of(
        "ai.api-key",
        "gitlab.token",
        "gitlab.webhook.secret",
        "nexus.pass",
        "sso.shared-secret"
    );

    private final SystemSettingRepository settingRepository;
    private final SecretCryptoService secretCryptoService;

    @Transactional(readOnly = true)
    public Map<String, String> getCategory(String category) {
        return settingRepository.findByCategory(category).stream()
            .collect(Collectors.toMap(
                SystemSetting::getSettingKey,
                s -> revealValue(s.getSettingKey(), s.getSettingValue())
            ));
    }

    /** Browser-facing settings never contain a stored secret, even in a password field. */
    @Transactional(readOnly = true)
    public Map<String, Object> getCategoryForClient(String category) {
        Map<String, Object> visible = new LinkedHashMap<>();
        Map<String, Boolean> configuredSecrets = new LinkedHashMap<>();
        settingRepository.findByCategory(category).forEach(setting -> {
            String key = setting.getSettingKey();
            if (obsoleteTransportKey(key)) return;
            String value = setting.getSettingValue();
            if (isSensitive(key)) {
                configuredSecrets.put(key, value != null && !value.isBlank());
            } else {
                visible.put(key, value == null ? "" : value);
            }
        });
        visible.put("configuredSecrets", configuredSecrets);
        return visible;
    }

    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return settingRepository.findById(key)
            .map(s -> revealValue(s.getSettingKey(), s.getSettingValue()))
            .orElse(defaultValue);
    }

    @Transactional
    public void saveAll(String category, Map<String, String> values) {
        if (values.keySet().stream().anyMatch(SystemSettingService::obsoleteTransportKey))
            throw new IllegalArgumentException("서버 관리용 SSH 설정은 지원하지 않습니다. 서버 에이전트를 등록해 주세요.");
        // Validate before writing any setting; this public endpoint must not contain credentials.
        if (values.containsKey("nexus.maven.base-url")) {
            NexusMavenEndpoint.normalizeOptional(values.get("nexus.maven.base-url"));
        }
        values.forEach((key, value) -> {
            // An empty secret field retains the existing value. JSON null explicitly clears it.
            if (isSensitive(key) && value != null && value.isBlank()) {
                return;
            }
            SystemSetting setting = settingRepository.findById(key)
                .orElse(SystemSetting.builder()
                    .settingKey(key)
                    .category(category)
                    .build());
            setting.setSettingValue(isSensitive(key) && value == null ? "" : storeValue(key, value));
            setting.setCategory(category);
            settingRepository.save(setting);
        });
    }

    private String revealValue(String key, String value) {
        if (value == null) {
            return "";
        }
        return isSensitive(key) ? secretCryptoService.decryptIfEncrypted(value) : value;
    }

    private String storeValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return isSensitive(key) ? secretCryptoService.encryptIfPlain(value) : value;
    }

    private static boolean obsoleteTransportKey(String key) {
        return key != null && (key.startsWith("deploy.ssh.") || key.startsWith("deploy.scp."));
    }
    private boolean isSensitive(String key) {
        return SENSITIVE_KEYS.contains(key);
    }
}
