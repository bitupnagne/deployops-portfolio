package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.dto.DeployComponentForm;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ProjectPermissionService;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class DeployComponentHealthModeApiTest {

    private final DeployComponentRepository components = mock(DeployComponentRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final DeployComponentApiController controller = new DeployComponentApiController(
        components, projects, mock(ProjectPermissionService.class), audits);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String STATIC_FORM = """
        {"code":"web","role":"WEB","artifactType":"STATIC_BUNDLE","webRoot":"/var/www/app",
         "healthCheckPort":80,"healthCheckPath":"/index.html"%s}
        """;

    @BeforeEach
    void saveWithGeneratedId() {
        when(projects.lockById(7L)).thenReturn(7L);
        when(components.save(any())).thenAnswer(invocation -> {
            DeployComponent component = invocation.getArgument(0);
            if (component.getId() == null) component.setId(21L);
            return component;
        });
    }

    @Test
    void legacyCreateDefaultsToLocalCurlAndKeepsStaticHealthTarget() {
        controller.create(7L, form(""));
        DeployComponent saved = saved();
        assertThat(saved.getHealthCheckMode()).isEqualTo(HealthCheckMode.SSH_LOCAL_CURL);
        assertThat(saved.getHealthCheckPort()).isEqualTo(80);
        assertThat(saved.getHealthCheckPath()).isEqualTo("/index.html");
        assertThat(saved.getWebRoot()).isEqualTo("/var/www/app");
    }

    @Test
    void explicitlyDisabledHealthChecksAreNotSilentlyEnabledOnCreate() {
        controller.create(7L, form(",\"healthCheckMode\":\"NONE\""));
        assertThat(saved().getHealthCheckMode()).isEqualTo(HealthCheckMode.NONE);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(HealthCheckMode.class)
    void editingWithLegacyPayloadPreservesExistingModeIncludingUnset(HealthCheckMode mode) {
        existing(mode);
        controller.update(7L, 21L, form(""));
        DeployComponent saved = saved();
        assertThat(saved.getId()).isEqualTo(21L);
        assertThat(saved.getHealthCheckMode()).isEqualTo(mode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    void nullOrBlankMeansKeepTheExistingMode(String value) {
        existing(HealthCheckMode.NONE);
        controller.update(7L, 21L, form(",\"healthCheckMode\":" + value));
        assertThat(saved().getHealthCheckMode()).isEqualTo(HealthCheckMode.NONE);
    }

    @Test
    void operatorCanRepairAnUnsetModeThroughTheActualJsonForm() {
        existing(null);
        controller.update(7L, 21L, form(",\"healthCheckMode\":\" ssh_local_curl \""));
        assertThat(saved().getHealthCheckMode()).isEqualTo(HealthCheckMode.SSH_LOCAL_CURL);
    }

    @Test
    void unknownModeIsRejectedBeforePersistenceOrAuditSuccess() {
        assertThatThrownBy(() -> controller.create(7L, form(",\"healthCheckMode\":\"TCP\"")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(components, never()).save(any());
        verify(audits, never()).recordCurrent(any(), any(), any(), any(), any());
    }

    @Test
    void secondActiveComponentForTheSameRoleIsRejectedInsteadOfSilentlyIgnored() {
        when(components.findByProjectId(7L)).thenReturn(List.of(
            DeployComponent.builder().id(11L).projectId(7L)
                .role(com.portfolio.deployops.domain.ServerType.WEB).active(true).build()));

        assertThatThrownBy(() -> controller.create(7L, form("")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getReason()).contains("프로젝트를 분리");
            });
        verify(components, never()).save(any());
    }

    @Test
    void inactiveSameRoleComponentDoesNotBlockTheActiveRuntimeSlot() {
        when(components.findByProjectId(7L)).thenReturn(List.of(
            DeployComponent.builder().id(11L).projectId(7L)
                .role(com.portfolio.deployops.domain.ServerType.WEB).active(false).build()));

        controller.create(7L, form(""));

        assertThat(saved().isActive()).isTrue();
    }

    private DeployComponentForm form(String extra) {
        return mapper.readValue(STATIC_FORM.formatted(extra), DeployComponentForm.class);
    }

    @Test
    void proxyHostCreateAcceptsTrimmedHostname() {
        DeployComponentForm form = form("");
        form.setArtifactType("PROXY_RELOAD");
        form.setProxyHealthHost(" portal.internal ");
        controller.create(7L, form);
        assertThat(saved().getProxyHealthHost()).isEqualTo("portal.internal");
    }

    @Test
    void proxyHostJsonEditReplacesThePersistedValueAndRecordsRequiredAudit() {
        when(components.findById(21L)).thenReturn(Optional.of(DeployComponent.builder()
            .id(21L).projectId(7L).proxyHealthHost("old.internal").build()));
        DeployComponentForm form = form(",\"proxyHealthHost\":\"new.internal\"");
        form.setArtifactType("PROXY_RELOAD");
        controller.update(7L, 21L, form);
        assertThat(saved().getProxyHealthHost()).isEqualTo("new.internal");
        verify(audits).recordCurrentRequired(any(), any(), any(), any(), any());
    }

    @Test
    void legacyEditPreservesProxyHostWhileExplicitNullClearsIt() {
        DeployComponent existing = DeployComponent.builder().id(21L).projectId(7L)
            .proxyHealthHost("portal.internal").build();
        when(components.findById(21L)).thenReturn(Optional.of(existing));
        DeployComponentForm form = form("");
        form.setArtifactType("PROXY_RELOAD");
        controller.update(7L, 21L, form);
        assertThat(saved().getProxyHealthHost()).isEqualTo("portal.internal");
        org.mockito.Mockito.clearInvocations(components);
        form = form(",\"proxyHealthHost\":null");
        form.setArtifactType("PROXY_RELOAD");
        controller.update(7L, 21L, form);
        assertThat(saved().getProxyHealthHost()).isNull();
    }

    @Test
    void switchingAwayFromProxyClearsHostUnlessUnsupportedHostWasExplicitlySupplied() {
        when(components.findById(21L)).thenReturn(Optional.of(DeployComponent.builder()
            .id(21L).projectId(7L).proxyHealthHost("portal.internal").build()));
        controller.update(7L, 21L, form(""));
        assertThat(saved().getProxyHealthHost()).isNull();
        assertThatThrownBy(() -> controller.create(7L, form(",\"proxyHealthHost\":\"portal.internal\"")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("WEB PROXY_RELOAD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://internal/", "portal:80", "*.internal", "bad host", "bad\r\nX: y", "-bad", "bad..host", "host-"})
    void invalidProxyHostsAreRejectedBeforePersistence(String host) {
        DeployComponentForm form = form("");
        form.setArtifactType("PROXY_RELOAD");
        form.setProxyHealthHost(host);
        assertThatThrownBy(() -> controller.create(7L, form)).isInstanceOf(ResponseStatusException.class);
        verify(components, never()).save(any());
    }

    private void existing(HealthCheckMode mode) {
        when(components.findById(21L)).thenReturn(Optional.of(
            DeployComponent.builder().id(21L).projectId(7L).healthCheckMode(mode).build()));
    }

    private DeployComponent saved() {
        ArgumentCaptor<DeployComponent> capture = ArgumentCaptor.forClass(DeployComponent.class);
        verify(components).save(capture.capture());
        return capture.getValue();
    }

    @Test
    void environmentHostsPreserveOmittedFieldsAndExplicitBlankFallsBack() {
        DeployComponent existing = DeployComponent.builder().id(21L).projectId(7L)
            .proxyHealthHost("common.internal").proxyHealthHostTest("test.internal")
            .proxyHealthHostProd("prod.internal").build();
        when(components.findById(21L)).thenReturn(Optional.of(existing));
        DeployComponentForm form = form(",\"proxyHealthHostTest\":\" \"");
        form.setArtifactType("PROXY_RELOAD");
        controller.update(7L, 21L, form);
        DeployComponent saved = saved();
        assertThat(saved.getProxyHealthHostTest()).isNull();
        assertThat(saved.getProxyHealthHostProd()).isEqualTo("prod.internal");
        assertThat(saved.resolveProxyHealthHost(com.portfolio.deployops.domain.DeployEnvironment.TEST)).isEqualTo("common.internal");
        assertThat(saved.resolveProxyHealthHost(com.portfolio.deployops.domain.DeployEnvironment.PROD)).isEqualTo("prod.internal");
    }

    @Test
    void distinctEnvironmentHostsAreNormalizedAndPersisted() {
        DeployComponentForm form = form(",\"proxyHealthHostTest\":\" test.internal \",\"proxyHealthHostProd\":\" prod.internal \"");
        form.setArtifactType("PROXY_RELOAD");
        controller.create(7L, form);
        assertThat(saved().getProxyHealthHostTest()).isEqualTo("test.internal");
        assertThat(saved().getProxyHealthHostProd()).isEqualTo("prod.internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://internal/", "portal:80", "bad\r\nX: y"})
    void invalidEnvironmentHostsAreRejectedBeforeSave(String host) {
        DeployComponentForm form = form("");
        form.setArtifactType("PROXY_RELOAD");
        form.setProxyHealthHostTest(host);
        assertThatThrownBy(() -> controller.create(7L, form)).isInstanceOf(ResponseStatusException.class);
        verify(components, never()).save(any());
    }

    @Test
    void environmentHostsCannotBeAssignedToNonProxyComponents() {
        DeployComponentForm form = form("");
        form.setProxyHealthHostProd("prod.internal");
        assertThatThrownBy(() -> controller.create(7L, form)).isInstanceOf(ResponseStatusException.class);
        verify(components, never()).save(any());
    }
}
