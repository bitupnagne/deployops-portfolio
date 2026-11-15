package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RolloutTrafficCoverageTest {
    @Test void agentConnectionIdentityIsPinnedAndRetainedForTrafficCommands() {
        web1.setControlMode(ServerControlMode.AGENT);
        web1.setAgentBindingId("web-registration-1");
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L),binding(22L)));
        var frozen=service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup));
        var route=frozen.stream().filter(row -> row.web().id().equals(21L)).findFirst().orElseThrow();
        assertThat(route.web().controlMode()).isEqualTo(ServerControlMode.AGENT);
        assertThat(route.web().agentBindingId()).isEqualTo("web-registration-1");
        when(ssh.execute(any(),any())).thenReturn(new RemoteCommandResult(0,"","","traffic",null,null,0));
        service.change(route,"request-42",was,8080,true,ignored -> {});
        var target=org.mockito.ArgumentCaptor.forClass(Server.class);
        verify(ssh).execute(target.capture(),any());
        assertThat(target.getValue().isAgentControlled()).isTrue();
        assertThat(target.getValue().getAgentBindingId()).isEqualTo("web-registration-1");
    }
    final ProjectRolloutRouteRepository bindings=mock(ProjectRolloutRouteRepository.class);
    final ProjectRepository projects=mock(ProjectRepository.class);
    final ServerRepository servers=mock(ServerRepository.class);
    final NginxChangeRequestRepository policies=mock(NginxChangeRequestRepository.class);
    final DeployComponentRepository components=mock(DeployComponentRepository.class);
    final SshCommandService ssh=mock(SshCommandService.class);
    final RolloutTrafficService service=new RolloutTrafficService(bindings,projects,servers,policies,
        ssh,components);
    final ServerGroup wasGroup=ServerGroup.builder().id(10L).environment(ServerEnvironment.PROD).build();
    final ServerGroup webGroup=ServerGroup.builder().id(20L).environment(ServerEnvironment.PROD).build();
    final Server was=Server.builder().id(11L).serverType(ServerType.WAS).environment(ServerEnvironment.PROD)
        .ipAddress("198.18.34.1").serverGroup(wasGroup).build();
    final Server web1=Server.builder().id(21L).name("web1").serverType(ServerType.WEB)
        .environment(ServerEnvironment.PROD).serverGroup(webGroup).build();
    final Server web2=Server.builder().id(22L).name("web2").serverType(ServerType.WEB)
        .environment(ServerEnvironment.PROD).serverGroup(webGroup).build();
    @Test void onlyExplicitZeroAvailabilityAddsLastUpstreamDowntimePermission() {
        var settings = new DeploymentPlanSnapshot.ServerSettings(21L,"web1","198.18.11.1",
            ServerEnvironment.PROD,ServerType.WEB,2022,"deploy",AuthType.SSH_KEY,null,0,false,null);
        var route = new RolloutTrafficRoute(settings,"api",101L,"a".repeat(64),
            List.of(new RolloutTrafficRoute.Endpoint(was.getIpAddress(),8080)));
        List<List<String>> commands = new ArrayList<>();
        when(ssh.execute(any(),any())).thenAnswer(call -> {
            commands.add(List.copyOf(call.<List<String>>getArgument(1)));
            return new RemoteCommandResult(0,"ok","","",null,null,0);
        });
        wasGroup.setMinAvailable(1);
        service.change(route,"request-1",was,8080,true,ignored -> {});
        wasGroup.setMinAvailable(0);
        service.change(route,"request-1",was,8080,true,ignored -> {});
        service.change(route,"request-1",was,8080,false,ignored -> {});
        was.setServerGroup(null);
        service.change(route,"request-1",was,8080,true,ignored -> {});
        assertThat(commands.get(0)).doesNotContain("--rollout-allow-empty");
        assertThat(commands.get(1)).containsSubsequence("--rollout-allow-empty","true");
        assertThat(commands.get(2)).doesNotContain("--rollout-allow-empty");
        assertThat(commands.get(3)).doesNotContain("--rollout-allow-empty");
    }
    @BeforeEach void setup() {
        wasGroup.setServers(List.of(was));
        when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).healthCheckPort(8080).build()));
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(wasGroup,webGroup));
        when(servers.findByServerGroupId(10L)).thenReturn(List.of(was));
        when(servers.findByServerGroupId(20L)).thenReturn(List.of(web1,web2));
        when(bindings.owner(any(),anyLong(),anyString())).thenReturn(null);
        policy(21L,101L,"api","198.18.34.1",8080);
        policy(22L,102L,"api","198.18.34.1",8080);
    }
    void policy(long webId,long id,String name,String host,int port) {
        when(policies.findCompletedPolicyNames(webId)).thenReturn(List.of(name));
        when(policies.findLatestCompletedId(webId,name)).thenReturn(id);
        when(policies.findById(id)).thenReturn(Optional.of(NginxChangeRequest.builder().id(id).policyName(name)
            .nativeRequestId(id).appliedConfigHash("a".repeat(64)).upstreamHost(host).upstreamPort(port).build()));
    }
    ProjectRolloutRouteRepository.Binding binding(long webId) {
        return new ProjectRolloutRouteRepository.Binding(1L,DeployEnvironment.PROD,webId,"api");
    }
    @Test void wasOnlyWithSeparateWebGroupRejectsOneMissingIngressBinding() {
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L)));
        // Request selection contains only WAS group 10; both actual WEB ingress policies still matter.
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("web2").hasMessageContaining("롤링 연결");
    }
    @Test void completeBindingsIncludeBothExternalWebsInSnapshot() {
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L),binding(22L)));
        assertThat(service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup)))
            .extracting(route -> route.web().id()).containsExactly(21L,22L);
    }
    @Test void anotherProductionGroupsHostDoesNotBlockOrEnterSelectedSnapshot() {
        policy(22L,102L,"api","198.18.11.1",8080);
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L)));
        assertThat(service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup)))
            .extracting(route -> route.web().id()).containsExactly(21L);
    }
    @Test void anotherProjectsPolicyOnAnotherPortIsNotClaimed() {
        policy(22L,102L,"api","198.18.34.1",9090);
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L)));
        when(bindings.owner(DeployEnvironment.PROD,22L,"api")).thenReturn(2L);
        assertThat(service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).hasSize(1);
    }
    @Test void anotherProjectsPolicyOnSameEndpointBlocksWithoutMutatingItsBinding() {
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L)));
        when(bindings.owner(DeployEnvironment.PROD,22L,"api")).thenReturn(2L);
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("다른 프로젝트(ID 2)")
            .hasMessageContaining("프로젝트별 전용 정책으로 분리");
        verify(bindings,never()).delete(anyLong(),any());
        verify(bindings,never()).insert(any());
    }
    @Test void activeWasComponentPortOverrideDefinesActualIngress() {
        when(components.findActiveByProjectId(1L)).thenReturn(List.of(DeployComponent.builder()
            .id(90L).role(ServerType.WAS).active(true).healthCheckPort(9090).build()));
        // Project default 8080 belongs to no deployed WAS component after the override.
        assertThat(service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).isNull();
        policy(22L,102L,"api","198.18.34.1",9090);
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("web2");
    }
    void vip() {
        wasGroup.setWasVipEnabled(true);wasGroup.setWasVipAddress("198.18.35.100");
        when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).healthCheckPort(8080).wasVipPort(8443).healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL).build()));
        policy(21L,101L,"api","198.18.35.100",8443);policy(22L,102L,"api","198.18.35.100",8443);
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of(binding(21L),binding(22L)));
    }
    @Test void vipPolicyIsFrozenButNeverUsedToDrainAnIndividualWas() {
        vip();
        var frozen=service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup));
        assertThat(frozen).hasSize(2);
        assertThat(frozen.getFirst().upstreams().getFirst().port()).isEqualTo(8443);
        var project=Project.builder().rolloutTrafficRoutes(frozen).build();
        assertThat(service.matching(project,was,8080)).isEmpty();
        assertThatThrownBy(() -> service.change(frozen.getFirst(),"request-1",was,8080,true,ignored -> {})).hasMessageContaining("외부 VIP");
        verifyNoInteractions(ssh);
    }
    @Test void vipModeRejectsResidualDirectIngressAndMissingVipCoverage() {
        vip();policy(22L,102L,"api","198.18.34.1",8080);
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).hasMessageContaining("혼재");
        policy(21L,101L,"api","198.18.35.100",443);policy(22L,102L,"api","198.18.35.100",443);
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).hasMessageContaining("서비스 포트");
    }
    @Test void vipCannotSubstituteVipHealthForActualWasHealth() {
        vip();when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).healthCheckPort(8080).wasVipPort(8443).healthCheckMode(HealthCheckMode.NONE).build()));
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).hasMessageContaining("개별 WAS");
    }
    @Test void unselectedGroupSharingVipIsRejectedRatherThanSilentlyClaimed() {
        vip();var other=ServerGroup.builder().id(30L).environment(ServerEnvironment.PROD).wasVipEnabled(true).wasVipAddress("198.18.35.100").build();
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(wasGroup,webGroup,other));
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).hasMessageContaining("공유");
    }

    @Test void webOnlyDoesNotRequireUnusedVipPortOrWasHealthAndKeepsExistingDirectCoverage() {
        vip();when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).healthCheckPort(8080).healthCheckMode(HealthCheckMode.NONE).build()));
        when(bindings.find(1L,DeployEnvironment.PROD)).thenReturn(List.of());
        assertThat(service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup,webGroup),DeployScope.WEB_ONLY)).isNull();
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup),DeployScope.WAS_ONLY)).hasMessageContaining("VIP 서비스 포트");
        policy(21L,101L,"api","198.18.34.1",8080);
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup,webGroup),DeployScope.WEB_ONLY)).hasMessageContaining("롤링 연결");
    }

    @Test void portlessProcSharesVipGroupWithoutClaimingOtherServicesIngress() {
        vip();var proc=Project.builder().id(1L).healthCheckMode(HealthCheckMode.CONTAINER_RUNNING).healthCheckPort(8080).build();
        when(projects.findById(1L)).thenReturn(Optional.of(proc));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of(DeployComponent.builder().role(ServerType.WAS)
            .artifactType(ArtifactType.CONTAINER).healthCheckMode(HealthCheckMode.CONTAINER_RUNNING).healthCheckPort(9090).build()));
        when(bindings.owner(DeployEnvironment.PROD,21L,"api")).thenReturn(99L);
        assertThat(service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).isNull();
        verifyNoInteractions(ssh);
        proc.setContainerPorts("18080:8080");
        assertThatThrownBy(() -> service.describeRoutes(1L,DeployEnvironment.PROD,List.of(wasGroup))).hasMessageContaining("VIP 서비스 포트");
    }

}
