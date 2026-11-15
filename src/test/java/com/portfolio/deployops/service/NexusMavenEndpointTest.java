package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.repository.SystemSettingRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NexusMavenEndpointTest {
    @Test void validatesRunnerRouteAndKeepsManagementRouteSeparate() {
        SystemSettingService settings = mock(SystemSettingService.class);
        NexusMetadataService nexus = mock(NexusMetadataService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(nexus, "settingService", settings);
        when(settings.get("nexus.url", "")).thenReturn("http://127.0.0.1:28183");
        when(settings.get("nexus.maven.base-url", "")).thenReturn("http://10.250.0.240:8081/nexus/");
        assertThat(nexus.runnerMavenBaseUrl()).isEqualTo("http://10.250.0.240:8081/nexus");
        assertThat(nexus.dependencyBaseUrl()).isEqualTo("http://127.0.0.1:28183");
        when(settings.get("nexus.maven.base-url", "")).thenReturn("");
        assertThat(nexus.runnerMavenBaseUrl()).isEqualTo("http://127.0.0.1:28183");
    }

    @Test void rejectsNonHttpCredentialsQueryFragmentTraversalAndInvalidPortsBeforePersistence() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        SystemSettingService settings = new SystemSettingService(repository, mock(SecretCryptoService.class));
        for (String invalid : new String[]{"file:///etc/passwd", "http://user:secret@host",
            "https://host?token=secret", "https://host/#fragment", "https://host/../nexus",
            "https://host/%2e%2e/nexus", "https://host:0", "https://host:65536", "host:8081"}) {
            assertThatThrownBy(() -> settings.saveAll("integration", Map.of("nexus.maven.base-url", invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test void oldRuntimeSnapshotRetainsExactJsonWhenOptionalRunnerAddressIsAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String oldJson = "{\"imageTransfer\":\"ssh\",\"imageStagingDirectory\":\"/tmp\","
            + "\"staticImagePath\":\"/dist\",\"containerMemory\":\"2g\",\"nexusUrl\":\"http://management\","
            + "\"nexusRegistry\":\"registry:5000\",\"nexusRegistryApiUrl\":\"\",\"nexusPipelineCredentials\":false}";
        DeploymentRuntimeSettings old = mapper.readValue(oldJson, DeploymentRuntimeSettings.class);
        assertThat(old.nexusMavenBaseUrl()).isNull();
        assertThat(mapper.writeValueAsString(old)).isEqualTo(oldJson);
        DeploymentRuntimeSettings updated = new DeploymentRuntimeSettings("ssh", "/tmp", "/dist", "2g",
            "http://management", "registry:5000", "", false, "http://runner:8081");
        assertThat(mapper.readValue(mapper.writeValueAsString(updated), DeploymentRuntimeSettings.class)
            .nexusMavenBaseUrl()).isEqualTo("http://runner:8081");
    }
}
