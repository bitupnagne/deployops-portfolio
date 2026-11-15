package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.repository.ServerRepository;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * 부팅 복구는 기동 직후 <b>등록된 서버 전체</b>에 root NOPASSWD 로 systemctl 을 쏜다.
 * 표면이 넓어서 "누구에게 무엇을 보내지 않는가" 가 특히 중요하다.
 *
 * <p>도달성 판정이 실제 TCP 접속이라, 성공 경로는 임시 ServerSocket 을 열어 만든다
 * (가짜 IP 를 쓰면 3초 타임아웃을 그대로 기다리게 된다).
 */
class BootRecoveryServiceTest {

    private final ServerRepository serverRepository = mock(ServerRepository.class);
    private final SshCommandService sshCommandService = mock(SshCommandService.class);
    private final ServerService serverService = mock(ServerService.class);
    private final MockEnvironment environment = new MockEnvironment();

    private BootRecoveryService service() {
        return new BootRecoveryService(serverRepository, sshCommandService, serverService, environment);
    }

    @Test
    void agentServerUsesAgentReadinessWithoutOpeningItsSshPort() {
        Server agent = server(11L, "agent-was", true, "docker.service", 0);
        agent.setIpAddress("192.0.2.1");
        agent.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        agent.setAgentBindingId("registration-1");
        when(serverRepository.findAll()).thenReturn(List.of(agent));
        when(serverService.checkServer(11L)).thenReturn(java.util.Map.of("reachable", true));
        when(sshCommandService.execute(any(), any())).thenReturn(ok());

        service().reconcile();

        verify(sshCommandService).execute(org.mockito.ArgumentMatchers.same(agent),
            org.mockito.ArgumentMatchers.eq(List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-service-control.sh", "start", "docker.service")));
        verify(serverService, org.mockito.Mockito.times(2)).checkServer(11L);
    }

    @Test
    void disconnectedAgentDoesNotFallBackToAnOpenSshPort() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Server agent = server(12L, "offline-agent", true, "docker.service", listening.getLocalPort());
            agent.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
            when(serverRepository.findAll()).thenReturn(List.of(agent));
            when(serverService.checkServer(12L)).thenReturn(java.util.Map.of("reachable", false));

            service().reconcile();

            verify(sshCommandService, never()).execute(any(), any());
        }
    }

    @Test
    void startsOnlyServersThatOptedInWithAUnitName() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Server enabled = server(1L, "prdwas1", true, "deploy-worksystem.service", listening.getLocalPort());
            enabled.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
            when(serverService.checkServer(1L)).thenReturn(java.util.Map.of("reachable", true));
            Server notOptedIn = server(2L, "prdwas2", false, "deploy-worksystem.service", listening.getLocalPort());
            Server noUnit = server(3L, "prdweb1", true, null, listening.getLocalPort());
            when(serverRepository.findAll()).thenReturn(List.of(enabled, notOptedIn, noUnit));
            when(sshCommandService.execute(any(), any())).thenReturn(ok());

            service().reconcile();

            ArgumentCaptor<Server> target = ArgumentCaptor.forClass(Server.class);
            ArgumentCaptor<List<String>> args = ArgumentCaptor.captor();
            verify(sshCommandService).execute(target.capture(), args.capture());
            assertThat(target.getValue().getName()).isEqualTo("prdwas1");
            assertThat(args.getValue()).containsExactly(
                "sudo", "-n", "/usr/local/deploy/bin/deploy-service-control.sh",
                "start", "deploy-worksystem.service");
        }
    }

    /*
      한 대가 안 켜졌다고 나머지를 포기하면, 전원 복구 때 하필 늦게 뜬 노드 하나가
      뒤의 서버를 전부 막는다. 도달 못 하는 서버는 건너뛰고 계속 간다.
    */
    @Test
    void skipsUnreachableServersWithoutSendingAnything() {
        // 192.0.2.0/24 는 문서용 예약 대역이라 어디에도 연결되지 않는다.
        Server unreachable = server(1L, "gone", true, "deploy-worksystem.service", 2022);
        unreachable.setIpAddress("192.0.2.1");
        when(serverRepository.findAll()).thenReturn(List.of(unreachable));

        service().reconcile();

        verify(sshCommandService, never()).execute(any(), any());
        verify(serverService).checkServer(1L);
    }

    @Test
    void refreshesServerStatusAfterStarting() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Server enabled = server(7L, "prdwas1", true, "deploy-worksystem.service", listening.getLocalPort());
            enabled.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
            when(serverService.checkServer(7L)).thenReturn(java.util.Map.of("reachable", true));
            when(serverRepository.findAll()).thenReturn(List.of(enabled));
            when(sshCommandService.execute(any(), any())).thenReturn(ok());

            service().reconcile();

            verify(serverService, org.mockito.Mockito.times(2)).checkServer(7L);
        }
    }

    /*
      복구를 끄고 기동해야 하는 상황이 있다(대량 장애 조사 중 등).
      스위치가 실제로 아무것도 보내지 않는지 확인한다.
    */
    @Test
    void legacyRegistrationNeverContactsListeningPort() throws Exception {
        try (ServerSocket port = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Server legacy = server(19L, "legacy", true, "docker.service", port.getLocalPort());
            legacy.setControlMode(com.portfolio.deployops.domain.ServerControlMode.SSH);
            when(serverRepository.findAll()).thenReturn(List.of(legacy));
            service().reconcile();
            port.setSoTimeout(150);
            org.junit.jupiter.api.Assertions.assertThrows(java.net.SocketTimeoutException.class, port::accept);
            verify(sshCommandService, never()).execute(any(), any());
        }
    }

    @Test
    void disabledSwitchSendsNothing() {
        environment.setProperty("deploy.boot-recovery.enabled", "false");

        service().recover();

        verify(serverRepository, never()).findAll();
    }

    private RemoteCommandResult ok() {
        return new RemoteCommandResult(0, "", "", "start", LocalDateTime.now(), LocalDateTime.now(), 1L);
    }

    private Server server(Long id, String name, boolean autoStart, String unit, int sshPort) {
        return Server.builder()
            .id(id)
            .name(name)
            .ipAddress("127.0.0.1")
            .sshPort(sshPort)
            .serverType(ServerType.WAS)
            .autoStart(autoStart)
            .startupService(unit)
            .build();
    }
}
