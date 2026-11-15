package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.service.AiChatService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.SecretMigrationService;
import com.portfolio.deployops.service.SystemSettingService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsSecretResponseTest {

    @Test
    void getEndpointUsesClientSafeViewInsteadOfInternalDecryptedSettings() {
        SystemSettingService settings = mock(SystemSettingService.class);
        Map<String, Object> safeView = Map.of(
            "gitlab.url", "https://gitlab.example",
            "configuredSecrets", Map.of("gitlab.token", true));
        when(settings.getCategoryForClient("integration")).thenReturn(safeView);
        SettingsApiController controller = new SettingsApiController(settings,
            mock(SecretMigrationService.class), mock(AiChatService.class),
            mock(AuditLogService.class), mock(ExecutionPolicyService.class));

        assertThat(controller.get("integration").getData()).isEqualTo(safeView);
        verify(settings).getCategoryForClient("integration");
        verify(settings, never()).getCategory("integration");
    }
}
