package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalWasVipTest {
    @ParameterizedTest @ValueSource(strings={"localhost","127.0.0.1","0.0.0.0","224.0.0.1","10.01.2.3","vip.example.com","fe80::1%eth0","::1"})
    void unsafeOrAmbiguousVipAddressIsRejected(String address) {
        assertThatThrownBy(() -> WasVipPolicy.normalizeAddress(address)).hasMessageContaining("VIP");
    }

    @Test void mappedIpv6AndIpv4AreSameEndpointWithoutDns() {
        assertThat(WasVipPolicy.normalizeAddress("::ffff:10.20.30.100")).isEqualTo("10.20.30.100");
        assertThat(WasVipPolicy.sameHost("[2001:db8::100]","2001:db8:0:0:0:0:0:100")).isTrue();
    }

    @Test void nginxResolvesProjectVipPortNotTheWasHealthPortAndRejectsMixedInput() {
        var projects=mock(ProjectRepository.class);
        var resolver=new WasVipNginxResolver(projects);
        var group=ServerGroup.builder().id(2L).environment(ServerEnvironment.PROD).wasVipEnabled(true).wasVipAddress("10.20.30.100").build();
        var web=Server.builder().id(4L).serverType(ServerType.WEB).environment(ServerEnvironment.PROD).serverGroup(group).build();
        when(projects.findAll()).thenReturn(List.of(Project.builder().id(1L).name("서비스").active(true).wasVipPort(443).healthCheckPort(8080).build()));
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(group));
        var form=new NginxPolicyForm(); form.setWasVipProjectId(1L);form.setWasVipGroupId(2L);
        resolver.resolve(form,web);
        assertThat(form.getUpstreamHost()).isEqualTo("10.20.30.100");assertThat(form.getUpstreamPort()).isEqualTo(443);
        var mixed=new NginxPolicyForm();mixed.setWasVipProjectId(1L);mixed.setWasVipGroupId(2L);mixed.setUpstreamListPresent(true);
        assertThatThrownBy(() -> resolver.resolve(mixed,web)).hasMessageContaining("함께 지정");
        web.setEnvironment(ServerEnvironment.TEST);
        assertThat(resolver.choices(web)).isEmpty();
    }

    @Test void virtualEndpointCannotSelectUnmappedOrInactiveProject() {
        var projects=mock(ProjectRepository.class);var resolver=new WasVipNginxResolver(projects);
        var webGroup=ServerGroup.builder().id(2L).environment(ServerEnvironment.PROD).build();
        var other=ServerGroup.builder().id(3L).environment(ServerEnvironment.PROD).wasVipEnabled(true).wasVipAddress("10.20.30.100").build();
        when(projects.findAll()).thenReturn(List.of(Project.builder().id(1L).active(true).wasVipPort(443).build()));
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(other));
        var web=Server.builder().serverGroup(webGroup).environment(ServerEnvironment.PROD).serverType(ServerType.WEB).build();
        var form=new NginxPolicyForm();form.setWasVipProjectId(1L);form.setWasVipGroupId(3L);
        assertThatThrownBy(() -> resolver.resolve(form,web)).hasMessageContaining("연결된 VIP");
    }
    @Test void nginxPreviewRendersTrustedVipAndRetainsFrozenAddressAfterGroupEdit() {
        var projects=mock(ProjectRepository.class);var servers=mock(ServerRepository.class);
        var service=new NginxChangeService(mock(NginxChangeRequestRepository.class),servers,mock(SshCommandService.class),mock(ExecutionGuardRepository.class));
        service.setWasVipResolver(new WasVipNginxResolver(projects));
        var group=ServerGroup.builder().id(2L).environment(ServerEnvironment.PROD).wasVipEnabled(true).wasVipAddress("10.20.30.100").build();
        var web=Server.builder().id(4L).serverType(ServerType.WEB).environment(ServerEnvironment.PROD).serverGroup(group).build();
        when(servers.findById(4L)).thenReturn(Optional.of(web));
        when(projects.findAll()).thenReturn(List.of(Project.builder().id(1L).active(true).wasVipPort(8443).build()));
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(group));
        var form=new NginxPolicyForm();form.setServerId(4L);form.setWasVipProjectId(1L);form.setWasVipGroupId(2L);
        form.setPolicyName("external-vip");form.setServerName("service.example.test");form.setListenPort(8080);
        var preview=service.preview(form);
        assertThat(preview.config()).contains("10.20.30.100:8443").doesNotContain("10.20.30.100:8080");
        group.setWasVipAddress("10.20.30.101");
        assertThat(preview.config()).contains("10.20.30.100:8443");
    }

    @Test void oldGroupClientDoesNotSilentlyDisableVipAndExplicitToggleClearsAddress() {
        var groups=mock(ServerGroupRepository.class);var servers=mock(ServerRepository.class);
        var service=new ServerGroupService(groups,servers,mock(ProjectRepository.class));
        var existing=ServerGroup.builder().id(2L).name("prod").environment(ServerEnvironment.PROD).wasVipEnabled(true).wasVipAddress("10.20.30.100").build();
        when(groups.findByIdForUpdate(2L)).thenReturn(Optional.of(existing));when(groups.save(existing)).thenReturn(existing);
        var form=new com.portfolio.deployops.dto.ServerGroupForm();form.setName("renamed");form.setEnvironment(ServerEnvironment.PROD);
        service.update(2L,form);
        assertThat(existing.getWasVipEnabled()).isTrue();assertThat(existing.getWasVipAddress()).isEqualTo("10.20.30.100");
        form.setWasVipEnabled(false);service.update(2L,form);
        assertThat(existing.getWasVipEnabled()).isNull();assertThat(existing.getWasVipAddress()).isNull();
    }

}
