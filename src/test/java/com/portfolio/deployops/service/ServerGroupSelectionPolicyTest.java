package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ServerGroupForm;
import com.portfolio.deployops.repository.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ServerGroupSelectionPolicyTest {
    private ServerGroup group(long id, ServerEnvironment env, long serverId) {
        var group = ServerGroup.builder().id(id).name("group-" + id).environment(env).build();
        var server = Server.builder().id(serverId).serverGroup(group).environment(env).serverType(ServerType.WAS).build();
        group.setServers(List.of(server));
        return group;
    }

    @Test void selectedProdGroupDoesNotExpandToOtherMappedGroups() {
        var a = group(10, ServerEnvironment.PROD, 100);
        var b = group(20, ServerEnvironment.PROD, 200);
        var dev = group(30, ServerEnvironment.TEST, 300);
        var project = Project.builder().serverGroups(List.of(a,b,dev)).build();
        var request = DeployRequest.builder().project(project).environment(DeployEnvironment.PROD)
            .selectedServerGroupIds(List.of(20L)).build();
        assertThat(EffectiveDeployTargets.resolve(request)).extracting(Server::getId).containsExactly(200L);
        assertThat(EffectiveDeployTargets.selectGroups(project, DeployEnvironment.TEST, null))
            .extracting(ServerGroup::getId).containsExactly(30L);
    }

    @Test void rejectsForeignEnvironmentUnknownOrDuplicateGroups() {
        var project = Project.builder().serverGroups(List.of(group(10,ServerEnvironment.TEST,100))).build();
        for (List<Long> ids : List.of(List.of(10L), List.of(99L), List.of(10L,10L))) {
            assertThatThrownBy(() -> EffectiveDeployTargets.selectGroups(project, DeployEnvironment.PROD, ids))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test void legacyDevCannotSilentlyBecomeTest() {
        var project = Project.builder().serverGroups(List.of(group(10,ServerEnvironment.DEV,100))).build();
        assertThatThrownBy(() -> EffectiveDeployTargets.selectGroups(project, DeployEnvironment.TEST, List.of(10L)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("TEST");
    }

    private ServerGroupForm form() {
        var form = new ServerGroupForm(); form.setName("운영"); form.setEnvironment(ServerEnvironment.PROD);
        return form;
    }
    @Test void defaultPolicyAndExplicitOutageConsentAreStored() {
        var repo = mock(ServerGroupRepository.class);
        when(repo.save(any())).thenAnswer(c -> c.getArgument(0));
        var service = new ServerGroupService(repo,mock(ServerRepository.class),mock(ProjectRepository.class));
        var saved = service.create(form());
        assertThat(saved.getMinAvailable()).isEqualTo(1);
        assertThat(saved.getMaxUnavailable()).isEqualTo(1);
        assertThat(saved.getObservationSeconds()).isEqualTo(30);
        assertThat(saved.getFailurePolicy()).isEqualTo(GroupFailurePolicy.STOP);
        var outage = form(); outage.setMinAvailable(0); outage.setFailurePolicy(GroupFailurePolicy.ROLLBACK);
        assertThat(service.create(outage).getMinAvailable()).isZero();
    }

    @Test void invalidPolicyAndDevAreRejectedBeforePersistence() {
        var repo = mock(ServerGroupRepository.class);
        var service = new ServerGroupService(repo,mock(ServerRepository.class),mock(ProjectRepository.class));
        var parallel = form(); parallel.setMaxUnavailable(2);
        var negative = form(); negative.setMinAvailable(-1);
        var observation = form(); observation.setObservationSeconds(601);
        var dev = form(); dev.setEnvironment(ServerEnvironment.DEV);
        for (var invalid : List.of(parallel,negative,observation,dev)) {
            assertThatThrownBy(() -> service.create(invalid)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(repo);
    }
}
