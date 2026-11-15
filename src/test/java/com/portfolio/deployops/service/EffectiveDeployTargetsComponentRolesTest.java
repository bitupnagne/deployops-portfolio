package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EffectiveDeployTargetsComponentRolesTest {
    private Project project(List<DeployComponent> components) {
        return Project.builder().components(components).serverGroups(List.of(
            ServerGroup.builder().environment(ServerEnvironment.TEST).servers(List.of(
                Server.builder().id(1L).serverType(ServerType.WAS).build(),
                Server.builder().id(2L).serverType(ServerType.WEB).build())).build(),
            ServerGroup.builder().environment(ServerEnvironment.PROD).servers(List.of(
                Server.builder().id(3L).serverType(ServerType.WEB).build())).build())).build();
    }
    private DeployComponent component(ServerType role, boolean active) {
        return DeployComponent.builder().role(role).active(active).build();
    }
    private List<ServerType> missing(Project project, DeployEnvironment environment, DeployScope scope) {
        return EffectiveDeployTargets.missingComponentRoles(project, EffectiveDeployTargets.resolve(project, environment, scope));
    }
    @Test void legacyWithoutComponentsRetainsBothTargets() {
        var project = project(List.of());
        assertThat(EffectiveDeployTargets.resolve(project, DeployEnvironment.TEST, DeployScope.ALL)).extracting(Server::getId).containsExactly(1L, 2L);
        assertThat(missing(project, DeployEnvironment.TEST, DeployScope.ALL)).isEmpty();
    }
    @Test void explicitWasRejectsMappedWeb() {
        assertThat(missing(project(List.of(component(ServerType.WAS, true))), DeployEnvironment.TEST, DeployScope.ALL)).containsExactly(ServerType.WEB);
    }
    @Test void bothActiveRolesAreValid() {
        assertThat(missing(project(List.of(component(ServerType.WAS, true), component(ServerType.WEB, true))), DeployEnvironment.TEST, DeployScope.ALL)).isEmpty();
    }
    @Test void wasOnlyScopeDoesNotRequireWeb() {
        assertThat(missing(project(List.of(component(ServerType.WAS, true))), DeployEnvironment.TEST, DeployScope.WAS_ONLY)).isEmpty();
    }
    @Test void inactiveWebDoesNotSatisfyRole() {
        assertThat(missing(project(List.of(component(ServerType.WAS, true), component(ServerType.WEB, false))), DeployEnvironment.TEST, DeployScope.ALL)).containsExactly(ServerType.WEB);
    }
    @Test void inactiveOnlyComponentsAreNotLegacy() {
        assertThat(missing(project(List.of(component(ServerType.WAS, false))), DeployEnvironment.TEST, DeployScope.ALL)).containsExactly(ServerType.WAS, ServerType.WEB);
    }
    @Test void selectedEnvironmentLimitsRequiredRoles() {
        assertThat(missing(project(List.of(component(ServerType.WEB, true))), DeployEnvironment.PROD, DeployScope.ALL)).isEmpty();
    }
}
