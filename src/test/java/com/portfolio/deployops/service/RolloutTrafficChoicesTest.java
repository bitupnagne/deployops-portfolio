package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RolloutTrafficChoicesTest {
    final ProjectRolloutRouteRepository routes=mock(ProjectRolloutRouteRepository.class);
    final ProjectRepository projects=mock(ProjectRepository.class);
    final ServerRepository servers=mock(ServerRepository.class);
    final NginxChangeRequestRepository policies=mock(NginxChangeRequestRepository.class);
    final RolloutTrafficService service=new RolloutTrafficService(routes,projects,servers,policies,mock(SshCommandService.class),mock(DeployComponentRepository.class));

    Server fixture() {
        var group=ServerGroup.builder().id(5L).environment(ServerEnvironment.PROD).build();
        var web=Server.builder().id(8L).name("web-a").environment(ServerEnvironment.PROD)
            .serverType(ServerType.WEB).serverGroup(group).build();
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(group));
        when(servers.findByServerGroupId(5L)).thenReturn(List.of(web));
        when(servers.findById(8L)).thenReturn(Optional.of(web));
        when(projects.lockById(1L)).thenReturn(1L);
        when(routes.owner(any(),anyLong(),anyString())).thenReturn(null);
        return web;
    }
    NginxChangeRequest policy(boolean absent) {
        return NginxChangeRequest.builder().id(10L).policyName("api").nativeRequestId(10L)
            .appliedConfigHash("a".repeat(64)).upstreamHost("198.18.8.11").upstreamPort(8080)
            .restoreAbsent(absent).build();
    }
    @Test void choicesOnlyExposeCurrentSameEnvironmentUnownedAppliedPolicies() {
        fixture();
        when(policies.findCompletedPolicyNames(8L)).thenReturn(List.of("api","other","deleted"));
        when(policies.findLatestCompletedId(8L,"api")).thenReturn(10L);
        when(policies.findById(10L)).thenReturn(Optional.of(policy(false)));
        when(routes.owner(DeployEnvironment.PROD,8L,"other")).thenReturn(99L);
        when(policies.findLatestCompletedId(8L,"deleted")).thenReturn(11L);
        when(policies.findById(11L)).thenReturn(Optional.of(policy(true)));
        assertThat(service.choices(1L,DeployEnvironment.PROD)).extracting(RolloutTrafficService.Choice::policyName).containsExactly("api");
        assertThat(service.choices(1L,DeployEnvironment.TEST)).isEmpty();
        verify(policies,never()).findLatestCompletedId(8L,"other");
    }
    @Test void saveSerializesProjectAndRejectsUnverifiedGenerationBeforeDelete() {
        fixture();
        when(policies.findLatestCompletedId(8L,"api")).thenReturn(10L);
        var old=policy(false); old.setAppliedConfigHash(null);
        when(policies.findById(10L)).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> service.save(1L,DeployEnvironment.PROD,
            List.of(new ProjectRolloutRouteRepository.Binding(99L,DeployEnvironment.TEST,8L,"api"))))
            .isInstanceOf(ResponseStatusException.class);
        verify(projects).lockById(1L);
        verify(routes,never()).delete(anyLong(),any());
    }
    @Test void saveRejectsOtherProjectOwnerWithoutRemovingExistingBindings() {
        fixture();
        when(routes.owner(DeployEnvironment.PROD,8L,"api")).thenReturn(99L);
        assertThatThrownBy(() -> service.save(1L,DeployEnvironment.PROD,
            List.of(new ProjectRolloutRouteRepository.Binding(1L,DeployEnvironment.PROD,8L,"api"))))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("다른 프로젝트");
        verify(routes,never()).delete(anyLong(),any());
    }
}
