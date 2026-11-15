package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WasVipPortlessWorkerTest {
    private Project worker() {
        return Project.builder().healthCheckMode(HealthCheckMode.CONTAINER_RUNNING)
            .healthCheckPort(8080).build(); // Hidden historical HTTP value is not published.
    }
    private DeployComponent component() {
        return DeployComponent.builder().role(ServerType.WAS).artifactType(ArtifactType.CONTAINER)
            .healthCheckMode(HealthCheckMode.CONTAINER_RUNNING).healthCheckPort(9090).build();
    }
    @Test void legacyAndExplicitWorkersIgnoreUnusedHttpHealthPort() {
        var p=worker(); assertThat(WasVipPolicy.portlessWorker(p)).isTrue();
        p.setComponents(List.of(component())); assertThat(WasVipPolicy.portlessWorker(p)).isTrue();
        p.getComponents().getFirst().setHealthCheckMode(null);
        assertThat(WasVipPolicy.portlessWorker(p)).isTrue();
    }
    @Test void anyPublishedPortOrExplicitVipRemainsSubjectToVipContract() {
        var p=worker();p.setContainerPorts("18080:8080");assertThat(WasVipPolicy.portlessWorker(p)).isFalse();
        p.setContainerPorts(null);p.setWasVipPort(8443);assertThat(WasVipPolicy.portlessWorker(p)).isFalse();
    }
    @Test void httpNoneOrUnknownHealthCannotUseWorkerException() {
        for (var mode : new HealthCheckMode[]{HealthCheckMode.SSH_LOCAL_CURL,HealthCheckMode.NONE,null}) {
            var p=worker();p.setHealthCheckMode(mode);assertThat(WasVipPolicy.portlessWorker(p)).isFalse();
            p=worker();var c=component();c.setHealthCheckMode(mode);p.setComponents(List.of(c));
            if(mode!=null) assertThat(WasVipPolicy.portlessWorker(p)).isFalse();
        }
    }
    @Test void webOrNonContainerComponentPreventsWorkerException() {
        var p=worker(); var c=component(); c.setRole(ServerType.WEB);p.setComponents(List.of(c));
        assertThat(WasVipPolicy.portlessWorker(p)).isFalse();
        c.setRole(ServerType.WAS);c.setArtifactType(ArtifactType.STATIC_BUNDLE);
        assertThat(WasVipPolicy.portlessWorker(p)).isFalse();
    }
    @Test void onlyKnownIsolatedDockerModesCanEstablishNoIngress() {
        var p=worker();
        for (String network : new String[]{null,""," ","bridge","none"}) {
            p.setDockerNetwork(network);assertThat(WasVipPolicy.portlessWorker(p)).as("network %s",network).isTrue();
        }
        for (String network : new String[]{"host","macvlan-prod","company-internal","container:other"}) {
            p.setDockerNetwork(network);assertThat(WasVipPolicy.portlessWorker(p)).as("network %s",network).isFalse();
        }
    }
}
