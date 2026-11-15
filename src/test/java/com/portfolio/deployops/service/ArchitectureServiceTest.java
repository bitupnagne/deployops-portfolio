package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerControlMode;
import com.portfolio.deployops.domain.ServerStatus;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class ArchitectureServiceTest {

    @Test
    void healthUsesOnlyOutcomesForRequestedProjectAndServer() throws IOException {
        Map<String, Object> health = healthForProjectWithOutcomes(List.of(true, true, true));

        Map<String, Object> serverHealth = firstServerHealth(health);
        assertThat(serverHealth).containsEntry("state", "OK")
            .doesNotContainKey("recentFailures");
    }

    @Test
    void projectScopedFailuresLowerReachableServerToWarning() throws IOException {
        Map<String, Object> health = healthForProjectWithOutcomes(List.of(true, false, true));

        Map<String, Object> serverHealth = firstServerHealth(health);
        assertThat(serverHealth).containsEntry("state", "WARN")
            .containsEntry("recentFailures", 1L)
            .containsEntry("recentTotal", 3)
            .extractingByKey("detail").asString().contains("최근 3회 중 1회 실패");
    }

    @Test
    void recentOutcomeQueryFiltersProjectAndServerBeforeApplyingLimit() {
        Configuration configuration = new Configuration();
        configuration.addMapper(RemoteCommandLogRepository.class);
        BoundSql query = configuration.getMappedStatement(RemoteCommandLogRepository.class.getName()
            + ".findRecentOutcomesByProjectIdAndServerId")
            .getBoundSql(Map.of("projectId", 7L, "serverId", 11L, "limit", 20));

        assertThat(query.getSql().replaceAll("\\s+", " ").trim())
            .contains("JOIN deploy_request r ON r.id = l.deploy_request_id")
            .contains("WHERE r.project_id = ? AND l.server_id = ? ORDER BY l.id DESC LIMIT ?");
        assertThat(query.getParameterMappings()).extracting(mapping -> mapping.getProperty())
            .containsExactly("projectId", "serverId", "limit");
    }

    private Map<String, Object> healthForProjectWithOutcomes(List<Boolean> outcomes) throws IOException {
        AgentFixture fixture = agentFixture();
        when(fixture.agents().status(11L)).thenReturn(agentState("ACTIVE", true, true));
        when(fixture.logs().findRecentOutcomesByProjectIdAndServerId(7L, 11L, 20)).thenReturn(outcomes);
        var health = fixture.service().health(7L, null);
        verify(fixture.logs()).findRecentOutcomesByProjectIdAndServerId(7L, 11L, 20);
        return health;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstServerHealth(Map<String, Object> health) {
        return ((List<Map<String, Object>>) health.get("servers")).get(0);
    }

    @Test
    void registryHealthUsesDockerProtocolProbeSeparatelyFromNexusManagementTcp() {
        SystemSettingService settings = mock(SystemSettingService.class);
        ProjectService projects = mock(ProjectService.class);
        DeployComponentRepository components = mock(DeployComponentRepository.class);
        RemoteCommandLogRepository commandLogs = mock(RemoteCommandLogRepository.class);
        NexusMetadataService nexusMetadata = mock(NexusMetadataService.class);
        when(settings.get(anyString(), anyString())).thenReturn("");
        when(settings.get("nexus.url", "")).thenReturn("http://127.0.0.1:1");
        when(settings.get("nexus.registry", "")).thenReturn("127.0.0.1:5000");
        when(projects.findByIdWithDetails(eq(7L), isNull()))
            .thenReturn(Project.builder().serverGroups(List.of()).build());
        when(nexusMetadata.probeRegistryEndpoint()).thenReturn(Map.of(
            "state", "BROKEN",
            "detail", "Nexus 사용 조건 동의가 완료되지 않아 Docker Registry가 요청을 차단합니다.",
            "latencyMs", 4L));

        ArchitectureService service = new ArchitectureService(
            settings, projects, components, commandLogs, nexusMetadata, mock(AgentQueueService.class));

        Map<String, Object> health = service.health(7L, null);

        assertThat(health.get("nexus")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("state", "BROKEN");
        assertThat(health.get("registry")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("state", "BROKEN")
            .containsEntry("detail", "Nexus 사용 조건 동의가 완료되지 않아 Docker Registry가 요청을 차단합니다.");
        verify(nexusMetadata).probeRegistryEndpoint();
    }

    @Test
    void activeFreshReadyAgentDoesNotDependOnAnySshEndpoint() {
        AgentFixture fixture = agentFixture();
        when(fixture.agents().status(11L)).thenReturn(agentState("ACTIVE", true, true));

        assertThat(firstServerHealth(fixture.service().health(7L, null)))
            .containsEntry("state", "OK").containsEntry("controlMode", "AGENT")
            .extractingByKey("detail").asString().contains("heartbeat").doesNotContain("SSH");
        verify(fixture.agents()).status(11L);
        verifyNoMoreInteractions(fixture.agents());
    }

    @Test
    void staleAgentHeartbeatOverridesPreviouslyNormalServerStatus() {
        AgentFixture fixture = agentFixture();
        when(fixture.agents().status(11L)).thenReturn(agentState("ACTIVE", false, true));

        assertThat(firstServerHealth(fixture.service().health(7L, null)))
            .containsEntry("state", "BROKEN").containsEntry("registrationState", "ACTIVE")
            .extractingByKey("detail").asString().contains("heartbeat");
    }

    @Test
    void unregisteredPendingAndRevokedAgentsNeverAppearReady() {
        AgentFixture fixture = agentFixture();
        for (String state : List.of("NOT_REGISTERED", "AWAITING_ENROLLMENT", "PENDING", "REVOKED")) {
            when(fixture.agents().status(11L)).thenReturn(agentState(state, true, true));
            assertThat(firstServerHealth(fixture.service().health(7L, null)))
                .containsEntry("state", "UNCONFIGURED").containsEntry("registrationState", state);
        }
    }

    @Test
    void connectedAgentWithoutRequiredToolsIsWarningInsteadOfSshFailure() {
        AgentFixture fixture = agentFixture();
        when(fixture.agents().status(11L)).thenReturn(agentState("ACTIVE", true, false));

        assertThat(firstServerHealth(fixture.service().health(7L, null)))
            .containsEntry("state", "WARN").extractingByKey("detail").asString().contains("Docker/nginx");
    }

    @Test
    void agentStatusLookupFailureStaysUnknownAndDoesNotExposeRawFailure() {
        AgentFixture fixture = agentFixture();
        when(fixture.agents().status(11L)).thenThrow(new IllegalStateException("private-diagnostic"));

        assertThat(firstServerHealth(fixture.service().health(7L, null)))
            .containsEntry("state", "UNKNOWN").extractingByKey("detail").asString()
            .doesNotContain("private-diagnostic", "SSH");
    }

    @Test
    @SuppressWarnings("unchecked")
    void structureExposesAgentTransportAndWaitsForFreshHealthInsteadOfCachedReachability() {
        AgentFixture fixture = agentFixture();
        Map<String, Object> structure = fixture.service().structure(7L, null);
        var zones = (List<Map<String, Object>>) structure.get("environments");
        var groups = (List<Map<String, Object>>) zones.get(0).get("groups");
        var servers = (List<Map<String, Object>>) groups.get(0).get("servers");

        assertThat(servers.get(0)).containsEntry("controlMode", "AGENT").containsEntry("reachable", "UNKNOWN");
        verifyNoInteractions(fixture.agents());
    }

    @Test
    void legacySshServerRequiresRegistrationEvenWhenPortIsOpen() throws IOException {
        try (ServerSocket target = new ServerSocket(0)) {
            AgentFixture fixture = fixture(Server.builder().id(11L).name("ssh-was").ipAddress("127.0.0.1")
                .sshPort(target.getLocalPort()).controlMode(ServerControlMode.SSH).build());

            assertThat(firstServerHealth(fixture.service().health(7L, null)))
                .containsEntry("state", "UNCONFIGURED").containsEntry("controlMode", "REGISTRATION_REQUIRED");
            verifyNoInteractions(fixture.agents());
        }
    }

    private Map<String, Object> agentState(String state, boolean connected, boolean ready) {
        return Map.of("registrationState", state, "connected", connected, "readiness", Map.of("ready", ready));
    }

    private AgentFixture agentFixture() {
        return fixture(Server.builder().id(11L).name("agent-was").controlMode(ServerControlMode.AGENT)
            .ipAddress(null).sshPort(0).status(ServerStatus.NORMAL).build());
    }

    private AgentFixture fixture(Server server) {
        SystemSettingService settings = mock(SystemSettingService.class);
        ProjectService projects = mock(ProjectService.class);
        DeployComponentRepository components = mock(DeployComponentRepository.class);
        RemoteCommandLogRepository commandLogs = mock(RemoteCommandLogRepository.class);
        NexusMetadataService nexus = mock(NexusMetadataService.class);
        AgentQueueService agents = mock(AgentQueueService.class);
        when(settings.get(anyString(), anyString())).thenReturn("");
        when(nexus.probeRegistryEndpoint()).thenReturn(Map.of("state", "OK", "detail", "ready"));
        when(components.findByProjectId(7L)).thenReturn(List.of());
        when(projects.findByIdWithDetails(eq(7L), isNull())).thenReturn(Project.builder().id(7L)
            .serverGroups(List.of(ServerGroup.builder().servers(List.of(server)).build())).build());
        return new AgentFixture(new ArchitectureService(settings, projects, components, commandLogs, nexus, agents), agents, commandLogs);
    }

    private record AgentFixture(ArchitectureService service, AgentQueueService agents, RemoteCommandLogRepository logs) { }
}
