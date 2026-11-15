package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RollingDeploymentPolicyTest {
    SshCommandService ssh = mock(SshCommandService.class);
    RemoteCommandLogService logs = mock(RemoteCommandLogService.class);
    SystemSettingService settings = mock(SystemSettingService.class);
    ImageArtifactService images = mock(ImageArtifactService.class);
    RolloutTrafficService traffic = mock(RolloutTrafficService.class);
    RemoteDeployService service = new RemoteDeployService(ssh, logs, settings, images);
    List<String> events = new ArrayList<>();
    ServerGroup group;
    Server was1, was2, web1, web2;
    DeployRequest request;

    @BeforeEach
    void setup() {
        group = ServerGroup.builder().id(1L).name("운영 A").environment(ServerEnvironment.PROD)
            .rollingIntervalSec(0).observationSeconds(0).minAvailable(0).build();
        was1 = node(11L, ServerType.WAS); was2 = node(12L, ServerType.WAS);
        web1 = node(21L, ServerType.WEB); web2 = node(22L, ServerType.WEB);
        group.setServers(List.of(was1, was2));
        Project project = Project.builder().id(100L).name("payments").imageRepository("registry/payments/api")
            .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL).healthCheckPort(8080).healthCheckPath("/ready")
            .serverGroups(List.of(group)).build();
        request = DeployRequest.builder().id(501L).reqNo("D-501").project(project).environment(DeployEnvironment.PROD)
            .deployScope(DeployScope.WAS_ONLY).imageDigest("sha256:" + "a".repeat(64)).selectedServerGroupIds(List.of(1L)).build();
        service.configureRolloutTraffic(traffic);
        AtomicLong ids = new AtomicLong();
        when(logs.beginTargetAttempt(any(), any(), any())).thenAnswer(call -> ids.incrementAndGet());
        when(settings.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(ssh.execute(any(), any())).thenAnswer(call -> {
            Server server = call.getArgument(0); List<String> args = call.getArgument(1);
            if (isAgentReadinessProbe(args)) return readyAgent(server);
            String action = action(args); events.add(server.getId() + ":" + action);
            return response(0, action.equals("capabilities") ? "STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nQUIESCE_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1" : "ok");
        });
    }

    @Test
    void proxyFailurePreservesRealAttemptDiagnosticsAndMasksDurableAndLiveOutput() {
        group.setServers(List.of(web1));
        request.setDeployScope(DeployScope.WEB_ONLY);
        request.getProject().setComponents(List.of(DeployComponent.builder().id(2L).code("web")
            .role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD).build()));
        var repository = mock(com.portfolio.deployops.repository.RemoteCommandLogRepository.class);
        var durableLogs = spy(new RemoteCommandLogService(repository, new SecretMaskingService()));
        doReturn(71L).when(durableLogs).beginTargetAttempt(any(),any(),any());
        var tested = new RemoteDeployService(ssh,durableLogs,settings,images);
        LocalDateTime started = LocalDateTime.of(2026,10,20,12,0);
        RemoteCommandResult failed = new RemoteCommandResult(7,"nginx syntax ok password=fixture-secret",
            "health HTTP 504\nPRIVATE-TOKEN: glpat-fixture-secret",
            "sudo -n deploy-web-proxy.sh reload --token=fixture-token",
            started,started.plusSeconds(19),19_456L);
        doReturn(failed).when(ssh).execute(eq(web1),argThat(args -> args != null && args.contains("reload")));
        List<String> live = new ArrayList<>();

        assertThatThrownBy(() -> tested.execute(request,live::add)).hasMessageContaining("exit=7");

        var saved = org.mockito.ArgumentCaptor.forClass(RemoteCommandLog.class);
        verify(repository,times(1)).insert(saved.capture());
        assertThat(saved.getValue().getTargetAttemptId()).isEqualTo(71L);
        assertThat(saved.getValue().getExitCode()).isEqualTo(7);
        assertThat(saved.getValue().getDurationMs()).isEqualTo(19_456L);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(started);
        assertThat(saved.getValue().getFinishedAt()).isEqualTo(started.plusSeconds(19));
        assertThat(saved.getValue().getCommandDisplay()).contains("deploy-web-proxy.sh reload").doesNotContain("fixture-token","rolling-target-incomplete");
        assertThat(saved.getValue().getStdoutText()).contains("nginx syntax ok","password=***").doesNotContain("fixture-secret");
        assertThat(saved.getValue().getStderrText()).contains("HTTP 504","PRIVATE-TOKEN: ***").doesNotContain("glpat-fixture-secret");
        assertThat(String.join("\n",live)).contains("nginx syntax ok","HTTP 504","PRIVATE-TOKEN: ***")
            .doesNotContain("fixture-secret","glpat-fixture-secret");
        verify(durableLogs,times(1)).saveAttemptResult(eq(request),eq(web1),same(failed),eq(71L));
    }

    @Test
    void containerCommandFailureKeepsRealFailureOnceThroughRecoveryChecks() {
        group.setServers(List.of(was1));
        LocalDateTime started = LocalDateTime.of(2026,10,20,12,0);
        var failed = new RemoteCommandResult(23,"native restored old container","new image unhealthy",
            "deploy-was-app.sh deploy --container payments",started,started.plusSeconds(8),8_123);
        doReturn(failed).when(ssh).execute(eq(was1),argThat(args -> args != null && args.contains("deploy")));

        assertThatThrownBy(() -> service.execute(request,ignored -> {})).hasMessageContaining("exit=23");

        verify(logs,times(1)).saveAttemptResult(eq(request),eq(was1),same(failed),any());
        verify(logs,never()).saveAttemptResult(eq(request),eq(was1),argThat(result -> result.exitCode()==0),any());
        assertThat(events).contains("11:probe").doesNotContain("11:restore");
    }

    @Test
    void selectedGroupDoesNotExecuteAnotherMappedGroup() {
        ServerGroup other = ServerGroup.builder().id(2L).name("운영 B").environment(ServerEnvironment.PROD).build();
        Server untouched = Server.builder().id(99L).serverType(ServerType.WAS).serverGroup(other).build();
        other.setServers(List.of(untouched));
        request.getProject().setServerGroups(List.of(group, other));
        assertThat(service.execute(request, ignored -> {})).isEqualTo(DeployStatus.COMPLETED);
        assertThat(events).contains("11:deploy", "12:deploy").noneMatch(event -> event.startsWith("99:"));
    }

    @Test
    void insufficientHealthyPeerBlocksBeforeDeploymentOrTrafficChange() {
        group.setMinAvailable(1);
        doReturn(response(1, "not ready")).when(ssh).execute(eq(was2), argThat(args -> args != null && args.contains("probe")));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("최소 가용");
        assertThat(events).doesNotContain("11:deploy");
        verifyNoInteractions(traffic);
        verify(logs, never()).beginTargetAttempt(any(), any(), any());
    }

    @Test
    void externalVipSequentialDeploymentDoesNotPretendToDrainL4() {
        withWebs();
        group.setWasVipEnabled(true);
        group.setWasVipAddress("198.18.12.100");
        group.setMinAvailable(1);
        List<String> live = new ArrayList<>();

        assertThat(service.execute(request, live::add)).isEqualTo(DeployStatus.COMPLETED);

        assertThat(events).contains("11:deploy", "12:deploy", "11:probe", "12:probe")
            .doesNotContain("11:quiesce", "12:quiesce");
        assertThat(events.indexOf("11:deploy")).isLessThan(events.indexOf("12:deploy"));
        verify(traffic, never()).change(any(), anyString(), any(), anyInt(), anyBoolean(), any());
        assertThat(String.join("\n", live)).contains("L4 멤버 제외·복귀 없이", "무중단은 보장하지 않습니다");
    }

    @Test
    void portlessProcOnVipGroupPublishesNoPortAndDoesNotClaimVipRouting() {
        group.setWasVipEnabled(true);group.setWasVipAddress("198.18.12.100");group.setMinAvailable(1);
        request.getProject().setHealthCheckMode(HealthCheckMode.CONTAINER_RUNNING);
        List<String> live=new ArrayList<>();
        assertThat(service.execute(request,live::add)).isEqualTo(DeployStatus.COMPLETED);
        assertThat(events).contains("11:deploy","12:deploy","11:probe","12:probe");
        assertThat(String.join("\n",live)).doesNotContain("외부 WAS VIP", "경유: L4");
        verify(ssh,atLeastOnce()).execute(eq(was1),argThat(args -> args!=null && action(args).equals("deploy")
            && args.contains("container") && !args.contains("--publish") && !args.contains("--host-port") && !args.contains("--health-port")));
        verify(traffic,never()).change(any(),anyString(),any(),anyInt(),anyBoolean(),any());
    }

    @Test
    void externalVipStillRejectsUnhealthyPeerBeforeContainerReplacement() {
        group.setWasVipEnabled(true);
        group.setWasVipAddress("198.18.12.100");
        group.setMinAvailable(1);
        doReturn(response(1, "unhealthy peer")).when(ssh)
            .execute(eq(was2), argThat(args -> args != null && args.contains("probe")));

        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("최소 가용");
        assertThat(events).doesNotContain("11:deploy", "12:deploy");
        verify(traffic, never()).change(any(), anyString(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void externalVipRejectsMixedDirectTrafficRoutesBeforeMutation() {
        group.setWasVipEnabled(true);
        group.setWasVipAddress("198.18.12.100");
        when(traffic.matching(any(), eq(was1), any())).thenReturn(List.of(route(web1)));

        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("혼합");
        assertThat(events).doesNotContain("11:deploy", "11:quiesce");
        verify(traffic, never()).change(any(), anyString(), any(), anyInt(), anyBoolean(), any());
        verify(logs, never()).beginTargetAttempt(any(), any(), any());
    }

    @Test
    void unavailableMountStopsBeforeTrafficDrainOrContainerReplacement() {
        var storage = mock(StorageMountService.class);
        service.configureStorageMounts(storage);
        request.getProject().setContainerUser("1001:1001");
        request.getProject().setStorageMounts(List.of(new StorageMountSettings(11L,0L,"files","NAS",
            "/mnt/files","payments","/app/files",false,"/mnt/files","nas:/files","nfs4")));
        when(traffic.matching(any(),eq(was1),any())).thenReturn(List.of(route(web1)));
        when(ssh.executeWithInput(eq(was1),any(),any())).thenReturn(response(1,"NAS unavailable"));

        assertThatThrownBy(()->service.execute(request,ignored->{})).hasMessageContaining("저장소 준비 검사");
        assertThat(events).doesNotContain("11:deploy","11:quiesce");
        verify(traffic,never()).change(any(),anyString(),any(),anyInt(),anyBoolean(),any());
        verify(logs,never()).beginTargetAttempt(any(),any(),any());
    }

    @Test
    void forgedMountReceiptNeverReachesDeploymentCommand() {
        service.configureStorageMounts(mock(StorageMountService.class));
        request.getProject().setContainerUser("1001:1001");
        request.getProject().setStorageMounts(List.of(new StorageMountSettings(11L,0L,"files","LOCAL",
            "/srv/files","payments","/app/files",false,null,null,null)));
        when(ssh.executeWithInput(eq(was1),any(),any())).thenReturn(response(0,
            "{\"path\":\"/tmp/forged.json\",\"sha256\":\""+"a".repeat(64)+"\"}"));
        assertThatThrownBy(()->service.execute(request,ignored->{})).hasMessageContaining("일치하지 않습니다");
        assertThat(events).doesNotContain("11:deploy");
        verify(logs,never()).beginTargetAttempt(any(),any(),any());
    }

    @Test
    void wasOnlyDeploymentStillRequiresEveryWebRoute() {
        withWebs();
        when(traffic.matching(any(), eq(was1), any())).thenReturn(List.of(route(web1)));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("각 WEB");
        verify(logs, never()).beginTargetAttempt(any(), any(), any());
    }

    @Test
    void failedObservationRestoresPreviousAndNeverRecordsSuccess() {
        doReturn(response(1, "unhealthy")).when(ssh).execute(eq(was1), argThat(args -> args != null && args.contains("probe")));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("관찰");
        assertThat(events).contains("11:deploy", "11:restore").doesNotContain("12:deploy");
        verify(logs, never()).saveAttemptResult(eq(request), eq(was1), argThat(result -> result.exitCode() == 0), any());
        verify(logs).saveAttemptResult(eq(request), eq(was1), argThat(result -> result.exitCode() != 0), any());
    }

    @Test
    void partialTrafficReturnKeepsHealthyNewContainerAndRecordsIncomplete() {
        withWebs();
        when(traffic.matching(any(), eq(was1), any())).thenReturn(List.of(route(web1), route(web2)));
        doAnswer(call -> {
            RolloutTrafficRoute route = call.getArgument(0); boolean drain = call.getArgument(4);
            events.add(route.web().id() + (drain ? ":drain" : ":return"));
            // Reverse restoration returns WEB2 first, then WEB1 fails.
            if (!drain && route.web().id().equals(web1.getId())) throw new RemoteExecutionException("WEB unavailable");
            return null;
        }).when(traffic).change(any(), anyString(), any(), anyInt(), anyBoolean(), any());
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("새 서비스는 유지");
        assertThat(events).contains("21:drain", "22:drain", "11:quiesce", "11:deploy", "22:return")
            .doesNotContain("11:restore", "12:deploy");
        verify(logs, never()).saveAttemptResult(eq(request), eq(was1), argThat(result -> result.exitCode() == 0), any());
    }

    @Test
    void trafficRecoveryChecksAllServicesBeforeRestoringSharedPolicy() {
        withWebs();
        when(traffic.matching(any(), any(), any())).thenReturn(List.of(route(web1)));
        doReturn(response(1, "unhealthy")).when(ssh).execute(eq(was2), argThat(args -> args != null && args.contains("probe")));
        assertThatThrownBy(() -> service.recoverTraffic(request, ignored -> {})).hasMessageContaining("상태 확인 실패");
        verify(traffic, never()).change(any(), anyString(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void groupCompensationInvalidatesEarlierSuccessForRetry() {
        group.setFailurePolicy(GroupFailurePolicy.ROLLBACK);
        doReturn(response(1, "bad image")).when(ssh).execute(eq(was2), argThat(args -> args != null && args.contains("deploy")));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("실패");
        assertThat(events).contains("11:restore");
        var results = org.mockito.ArgumentCaptor.forClass(RemoteCommandResult.class);
        verify(logs, times(2)).saveAttemptResult(eq(request), eq(was1), results.capture(), any());
        assertThat(results.getAllValues().getFirst().exitCode()).isZero();
        assertThat(results.getAllValues().getLast().exitCode()).isNotZero();
    }

    @Test
    void proxyAvailabilityRequiresActualHttpSuccessBeforeReload() {
        group.setServers(List.of(web1, web2));
        group.setMinAvailable(1);
        request.setDeployScope(DeployScope.WEB_ONLY);
        request.getProject().setComponents(List.of(DeployComponent.builder().id(2L).code("web").active(true)
            .role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD).healthCheckPort(80).healthCheckPath("/").build()));
        doReturn(response(0, "NGINX_ACTIVE=true\nHTTP_HEALTHY=false")).when(ssh)
            .execute(any(), argThat(args -> args != null && args.contains("status")));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("최소");
        assertThat(events).doesNotContain("21:reload", "22:reload");
        verify(logs, never()).beginTargetAttempt(any(), any(), any());
    }

    @Test
    void staticGroupRollbackRequiresExactRecoveryBeforeAnyDeployment() {
        group.setServers(List.of(web1, web2));
        group.setFailurePolicy(GroupFailurePolicy.ROLLBACK);
        request.setDeployScope(DeployScope.WEB_ONLY);
        request.getProject().setComponents(List.of(DeployComponent.builder().id(2L).code("web").active(true)
            .role(ServerType.WEB).artifactType(ArtifactType.STATIC_BUNDLE).webRoot("/var/www/payments").build()));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("정확한 릴리스 복원");
        verify(logs, never()).beginTargetAttempt(any(), any(), any());
        verifyNoInteractions(images);
    }

    @Test
    void ambiguousSameRoleComponentsNeverChooseFirstOrStartDeployment() {
        request.getProject().setComponents(List.of(
            DeployComponent.builder().id(1L).code("api").active(true).role(ServerType.WAS).artifactType(ArtifactType.CONTAINER).build(),
            DeployComponent.builder().id(2L).code("proc").active(true).role(ServerType.WAS).artifactType(ArtifactType.CONTAINER).build()));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("활성 배포 단위가 여러 개");
        verify(logs, never()).beginTargetAttempt(any(), any(), any());
        verifyNoInteractions(images);
    }

    @Test
    void staticCompensationNeverRemovesOnlyAvailableReleaseToRestoreAbsent() {
        group.setServers(List.of(web1, web2));
        group.setFailurePolicy(GroupFailurePolicy.ROLLBACK);
        group.setMinAvailable(1);
        request.setDeployScope(DeployScope.WEB_ONLY);
        request.getProject().setComponents(List.of(DeployComponent.builder().id(2L).code("web").active(true)
            .role(ServerType.WEB).artifactType(ArtifactType.STATIC_BUNDLE).webRoot("/var/www/payments").build()));
        Map<Long,String> releases = new HashMap<>();
        releases.put(web1.getId(), ""); releases.put(web2.getId(), "/var/www/payments/releases/old");
        doAnswer(call -> {
            Server node=call.getArgument(0); List<String> args=call.getArgument(1);
            if (isAgentReadinessProbe(args)) return readyAgent(node);
            String action=action(args); events.add(node.getId()+":"+action);
            if (action.equals("capabilities")) return response(0,"EXACT_RESTORE_VERSION=1");
            if (action.equals("status")) {
                String release=releases.get(node.getId());
                return response(0,"NGINX_ACTIVE=true\nCURRENT_RELEASE="+release+"\nRELEASE_EXISTS="+!release.isBlank());
            }
            if (action.equals("deploy")) {
                if (node==web2) { releases.put(node.getId(), ""); return response(1,"unhealthy WEB2"); }
                releases.put(node.getId(), "/var/www/payments/releases/new");
            }
            return response(0,"ok");
        }).when(ssh).execute(any(), any());
        when(ssh.copyFile(any(),any(),any())).thenReturn(response(0,"copied"));
        when(images.extractStaticBundle(any(),any(),any(),any())).thenReturn("a".repeat(64));
        List<String> messages=new ArrayList<>();
        assertThatThrownBy(() -> service.execute(request, messages::add)).hasMessageContaining("실패");
        assertThat(releases.get(web1.getId())).endsWith("/new");
        assertThat(events).contains("21:deploy", "22:deploy").doesNotContain("21:restore-exact");
        assertThat(messages).anyMatch(message -> message.contains("그룹 복구 실패") && message.contains("최소"));
    }

    private void withWebs() {
        group.setServers(List.of(was1, was2, web1, web2));
        request.getProject().setComponents(List.of(
            DeployComponent.builder().id(1L).code("api").active(true).role(ServerType.WAS).artifactType(ArtifactType.CONTAINER).build(),
            DeployComponent.builder().id(2L).code("web").active(true).role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD).build()));
    }

    private Server node(Long id, ServerType type) {
        return Server.builder().id(id).name(type.name() + id).ipAddress("192.0.2." + id).serverGroup(group)
            .environment(ServerEnvironment.PROD).serverType(type).orderIndex(id.intValue()).sshPort(2022).deployAccount("deploy").build();
    }

    private RolloutTrafficRoute route(Server web) {
        var connection = new DeploymentPlanSnapshot.ServerSettings(web.getId(), web.getName(), web.getIpAddress(),
            web.getEnvironment(), web.getServerType(), web.getSshPort(), web.getDeployAccount(), AuthType.SSH_KEY, null, 0, false, null);
        return new RolloutTrafficRoute(connection, "payments", 7L, "b".repeat(64),
            List.of(new RolloutTrafficRoute.Endpoint(was1.getIpAddress(), 8080), new RolloutTrafficRoute.Endpoint(was2.getIpAddress(), 8080)));
    }

    private boolean isAgentReadinessProbe(List<String> args) {
        return args.equals(List.of("/usr/local/deploy/bin/deploy-agent-probe"));
    }

    private RemoteCommandResult readyAgent(Server node) {
        String runtime = node.getServerType() == ServerType.WAS ? "docker" : "nginx";
        return response(0, """
            {"ready":true,"role":"%s","checks":{"helpers":{"ready":true},"%s":{"ready":true}}}
            """.formatted(node.getServerType(), runtime));
    }

    private String action(List<String> args) {
        for (int i = 0; i < args.size() - 1; i++) if (args.get(i).endsWith(".sh")) return args.get(i + 1);
        return "unknown";
    }

    private RemoteCommandResult response(int exit, String output) {
        return new RemoteCommandResult(exit, output, "", "fixture", LocalDateTime.now(), LocalDateTime.now(), 0);
    }
}
