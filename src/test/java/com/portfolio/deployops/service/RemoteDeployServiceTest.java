package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

class RemoteDeployServiceTest {

    @Test
    void webWithoutDockerRejectsWholePlanBeforeFirstWasMutation() {
        var group = group("TEST", ServerEnvironment.TEST);
        var was = server("was", ServerType.WAS, group, 1);
        var web = server("web", ServerType.WEB, group, 2);
        was.setId(1L); web.setId(2L);
        was.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        web.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        group.setServers(List.of(was, web));
        var request = request(DeployEnvironment.TEST, List.of(group));
        org.mockito.Mockito.doReturn(agentProbe(ServerType.WEB, false)).when(agentConnection)
            .execute(eq(web), eq(List.of("/usr/local/deploy/bin/deploy-agent-probe")));

        assertThatThrownBy(() -> service.execute(request, ignored -> {}))
            .hasMessageContaining("web").hasMessageContaining("docker")
            .hasMessageContaining("대상 서버 변경은 시작하지 않았습니다");
        assertThatThrownBy(() -> service.executeServer(request, 1L, ignored -> {}))
            .hasMessageContaining("web").hasMessageContaining("docker");

        org.mockito.Mockito.verifyNoInteractions(sshCommandService, imageExportService, remoteCommandLogService);
        verify(agentConnection, times(2)).execute(eq(was), eq(List.of("/usr/local/deploy/bin/deploy-agent-probe")));
    }

    @Test
    void nativeWebProxyDoesNotRequireDockerBeforeReload() {
        var request = proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, 80, "/ready");
        var web = request.getProject().getServerGroups().getFirst().getServers().getFirst();
        web.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        org.mockito.Mockito.doReturn(agentProbe(ServerType.WEB, false)).when(agentConnection)
            .execute(eq(web), eq(List.of("/usr/local/deploy/bin/deploy-agent-probe")));
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());

        assertThat(service.execute(request, ignored -> {})).isEqualTo(DeployStatus.TEST_DEPLOYED);
        verify(sshCommandService).execute(eq(web), org.mockito.ArgumentMatchers.argThat(args ->
            args.contains("/usr/local/deploy/bin/deploy-web-proxy.sh") && args.contains("reload")));
    }

    @Test
    void malformedProbeBlocksWithoutLeakingItsContentOrStartingDeployment() {
        var group = group("TEST", ServerEnvironment.TEST);
        var was = server("was", ServerType.WAS, group, 1);
        was.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        group.setServers(List.of(was));
        var now = LocalDateTime.now();
        org.mockito.Mockito.doReturn(new RemoteCommandResult(0, "sensitive-invalid-output", "", "probe", now, now, 1))
            .when(agentConnection).execute(eq(was), eq(List.of("/usr/local/deploy/bin/deploy-agent-probe")));

        assertThatThrownBy(() -> service.execute(request(DeployEnvironment.TEST, List.of(group)), ignored -> {}))
            .hasMessageContaining("준비 확인 실패").hasMessageNotContaining("sensitive-invalid-output");
        org.mockito.Mockito.verifyNoInteractions(sshCommandService, imageExportService, remoteCommandLogService);
    }

    private static RemoteCommandResult agentProbe(ServerType role, boolean dockerReady) {
        var now = LocalDateTime.now();
        return new RemoteCommandResult(0, "{\"ready\":true,\"role\":\"" + role.name()
            + "\",\"checks\":{\"helpers\":{\"ready\":true},\"nginx\":{\"ready\":true},\"docker\":{\"ready\":"
            + dockerReady + "}}}", "", "agent-probe", now, now, 1);
    }

    @Test
    void missingWebComponentRejectsWholePlanBeforeFirstWasSideEffect() {
        var group = group("TEST", ServerEnvironment.TEST);
        group.setServers(List.of(server("was", ServerType.WAS, group, 1), server("web", ServerType.WEB, group, 2)));
        var request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setComponents(List.of(DeployComponent.builder().role(ServerType.WAS).active(true).build()));
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("WEB").hasMessageContaining("활성 배포 단위");
        org.mockito.Mockito.verifyNoInteractions(sshCommandService, imageExportService, remoteCommandLogService);
    }

    @Test
    void retryStillChecksWholePlanBeforeExecutingRemainingWas() {
        var group = group("TEST", ServerEnvironment.TEST);
        var was = server("was", ServerType.WAS, group, 1); was.setId(1L);
        var web = server("web", ServerType.WEB, group, 2); web.setId(2L);
        group.setServers(List.of(was, web));
        var request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setComponents(List.of(DeployComponent.builder().role(ServerType.WAS).active(true).build()));
        when(remoteCommandLogService.latestSuccessfulServerIds(request.getId())).thenReturn(List.of(2L));
        assertThatThrownBy(() -> service.executeRetry(request, ignored -> {})).hasMessageContaining("WEB");
        org.mockito.Mockito.verifyNoInteractions(sshCommandService, imageExportService);
        verify(remoteCommandLogService, never()).beginTargetAttempt(any(), any(), any());
    }

    @Test
    void failureToPersistAttemptStopsBeforeRuntimeOrSshSideEffects() {
        var group = group("PROD-WAS", ServerEnvironment.PROD);
        var was = server("was1", ServerType.WAS, group, 1);
        group.setServers(List.of(was));
        var request = request(DeployEnvironment.PROD, List.of(group));
        org.mockito.Mockito.doThrow(new RemoteExecutionException("attempt unavailable"))
            .when(remoteCommandLogService).beginTargetAttempt(eq(request), eq(was), any());
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("attempt unavailable");
        org.mockito.Mockito.verifyNoInteractions(sshCommandService, imageExportService);
    }

    @Test
    void thrownSshStillHasDurableAttemptBeforeFailure() {
        var group = group("PROD-WAS", ServerEnvironment.PROD);
        var was = server("was1", ServerType.WAS, group, 1);
        group.setServers(List.of(was));
        var request = request(DeployEnvironment.PROD, List.of(group));
        when(remoteCommandLogService.beginTargetAttempt(eq(request), eq(was), any())).thenReturn(77L);
        org.mockito.Mockito.doThrow(new RemoteExecutionException("ssh timeout"))
            .when(sshCommandService).execute(eq(was), any());
        assertThatThrownBy(() -> service.execute(request, ignored -> {})).hasMessageContaining("ssh timeout");
        var order = org.mockito.Mockito.inOrder(remoteCommandLogService, sshCommandService);
        order.verify(remoteCommandLogService).beginTargetAttempt(eq(request), eq(was), any());
        order.verify(sshCommandService).execute(eq(was), any());
        ArgumentCaptor<RemoteCommandResult> failure = ArgumentCaptor.forClass(RemoteCommandResult.class);
        verify(remoteCommandLogService).saveAttemptResult(eq(request), eq(was), failure.capture(), eq(77L));
        assertThat(failure.getValue().exitCode()).isEqualTo(1);
        assertThat(failure.getValue().output()).contains("ssh timeout");
    }

    @Test
    void resultPersistenceFailureDoesNotReportDeploymentComplete() {
        var group = group("PROD-WAS", ServerEnvironment.PROD);
        var was = server("was1", ServerType.WAS, group, 1);
        group.setServers(List.of(was));
        var request = request(DeployEnvironment.PROD, List.of(group));
        when(remoteCommandLogService.beginTargetAttempt(eq(request), eq(was), any())).thenReturn(77L);
        when(sshCommandService.execute(eq(was), any())).thenReturn(successResult());
        org.mockito.Mockito.doThrow(new IllegalStateException("result storage unavailable"))
            .when(remoteCommandLogService).saveAttemptResult(eq(request), eq(was), any(), eq(77L));
        assertThatThrownBy(() -> service.execute(request, ignored -> {}))
            .isInstanceOf(RemoteExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        var order = org.mockito.Mockito.inOrder(remoteCommandLogService, sshCommandService);
        order.verify(remoteCommandLogService).beginTargetAttempt(eq(request), eq(was), any());
        order.verify(sshCommandService).execute(eq(was), any());
        // Both the final success write and the fallback failure write are attempted.
        // Neither may turn a failed durable completion write into a completed deployment.
        order.verify(remoteCommandLogService, times(2)).saveAttemptResult(eq(request), eq(was), any(), eq(77L));
    }

    @Test
    void unknownObservationProbeStopsWithoutRestoreGroupCompensationOrNextTarget() {
        var group=group("PROD-AGENTS",ServerEnvironment.PROD);
        group.setId(91L); group.setFailurePolicy(com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK);
        var first=server("was1",ServerType.WAS,group,1); first.setId(11L);
        var second=server("was2",ServerType.WAS,group,2); second.setId(12L);
        var third=server("was3",ServerType.WAS,group,3); third.setId(13L);
        for(var item:List.of(first,second,third)) {
            item.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
            item.setAgentBindingId("agent-"+item.getId());
        }
        group.setServers(List.of(first,second,third));
        var request=request(DeployEnvironment.PROD,List.of(group));
        when(sshCommandService.execute(any(),any())).thenReturn(successResult());
        var unknown=new AgentOutcomeUnknownException("second observation is still running");
        org.mockito.Mockito.doThrow(unknown).when(agentConnection).execute(eq(second),
            org.mockito.ArgumentMatchers.argThat(RemoteDeployServiceTest::isReadOnlyWasProbe));

        assertThatThrownBy(()->service.execute(request,ignored->{})).isSameAs(unknown);

        verify(remoteCommandLogService,times(1)).beginTargetAttempt(eq(request),eq(first),any());
        verify(remoteCommandLogService,times(1)).beginTargetAttempt(eq(request),eq(second),any());
        verify(remoteCommandLogService,never()).beginTargetAttempt(eq(request),eq(third),any());
        verify(agentConnection).execute(eq(third),eq(List.of("/usr/local/deploy/bin/deploy-agent-probe")));
        verify(agentConnection,never()).execute(eq(third),org.mockito.ArgumentMatchers.argThat(args ->
            !List.of("/usr/local/deploy/bin/deploy-agent-probe").equals(args)));
        verify(agentConnection,never()).execute(any(),org.mockito.ArgumentMatchers.argThat(args->{
            var command=withoutSudo(args); return command.size()>1 && command.get(1).equals("restore");
        }));
        verify(sshCommandService,times(2)).execute(any(),any());
    }

    @Test
    void previouslyUnresolvedAgentJobStopsWholePlanBeforeAnyNewAttempt() {
        var group=group("PROD-BUSY-AGENTS",ServerEnvironment.PROD);
        group.setId(92L); group.setFailurePolicy(com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK);
        var first=server("was1",ServerType.WAS,group,1); first.setId(11L);
        var busy=server("was2",ServerType.WAS,group,2); busy.setId(12L);
        var pending=server("was3",ServerType.WAS,group,3); pending.setId(13L);
        for(var item:List.of(first,busy,pending)) {
            item.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
            item.setAgentBindingId("agent-"+item.getId());
        }
        group.setServers(List.of(first,busy,pending));
        var request=request(DeployEnvironment.PROD,List.of(group));
        when(sshCommandService.execute(any(),any())).thenReturn(successResult());
        var unknown=new AgentOutcomeUnknownException("previous agent job has an unknown result");
        org.mockito.Mockito.doThrow(unknown).when(agentConnection).execute(eq(busy),any());

        assertThatThrownBy(()->service.execute(request,ignored->{})).isSameAs(unknown);

        verify(remoteCommandLogService,never()).beginTargetAttempt(eq(request),eq(first),any());
        verify(remoteCommandLogService,never()).beginTargetAttempt(eq(request),eq(busy),any());
        verify(remoteCommandLogService,never()).beginTargetAttempt(eq(request),eq(pending),any());
        verify(agentConnection,never()).execute(eq(pending),any());
        verify(agentConnection,never()).execute(any(),org.mockito.ArgumentMatchers.argThat(args->{
            var command=withoutSudo(args); return command.size()>1 && command.get(1).equals("restore");
        }));
        verify(sshCommandService,never()).execute(any(),any());
    }

    @Test
    void retrySkipsLatestSuccessfulTargetAndExecutesFailedAndUnattemptedInOrder() {
        ServerGroup group = group("PROD-WEB", ServerEnvironment.PROD);
        Server first = server("web1", ServerType.WEB, group, 1); first.setId(11L);
        Server failed = server("web2", ServerType.WEB, group, 2); failed.setId(12L);
        Server pending = server("web3", ServerType.WEB, group, 3); pending.setId(13L);
        group.setServers(List.of(first, failed, pending));
        DeployRequest request = request(DeployEnvironment.PROD, List.of(group));
        when(remoteCommandLogService.latestSuccessfulServerIds(request.getId())).thenReturn(List.of(11L));
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());
        assertThat(service.executeRetry(request, ignored -> {})).isEqualTo(DeployStatus.COMPLETED);
        var order = org.mockito.Mockito.inOrder(sshCommandService);
        order.verify(sshCommandService).execute(eq(failed), any());
        order.verify(sshCommandService).execute(eq(pending), any());
        verify(sshCommandService, never()).execute(eq(first), any());
        verify(remoteCommandLogService, times(2)).saveAttemptResult(eq(request), any(), any(), any());
    }

    @Test
    void retryAbortsAfterFailedTargetWithoutReexecutingPriorSuccessOrLaterTarget() {
        ServerGroup group = group("PROD-WEB", ServerEnvironment.PROD);
        Server success = server("web1", ServerType.WEB, group, 1); success.setId(11L);
        Server failed = server("web2", ServerType.WEB, group, 2); failed.setId(12L);
        Server pending = server("web3", ServerType.WEB, group, 3); pending.setId(13L);
        group.setServers(List.of(success, failed, pending));
        DeployRequest request = request(DeployEnvironment.PROD, List.of(group));
        when(remoteCommandLogService.latestSuccessfulServerIds(request.getId())).thenReturn(List.of(11L));
        when(sshCommandService.execute(eq(failed), any())).thenReturn(new RemoteCommandResult(1, "", "nginx inactive", "reload", LocalDateTime.now(), LocalDateTime.now(), 1));
        assertThatThrownBy(() -> service.executeRetry(request, ignored -> {})).isInstanceOf(RemoteExecutionException.class);
        verify(remoteCommandLogService).saveAttemptResult(eq(request), eq(failed), any(), any());
        verify(sshCommandService, never()).execute(eq(success), any());
        verify(sshCommandService, never()).execute(eq(pending), any());
    }

    private final SshCommandService sshCommandService = mock(SshCommandService.class);
    // Read-only capability discovery is separate from the commands whose side effects
    // and durable-attempt ordering these tests exercise. All other calls retain the
    // original mock's exact results, exceptions, captures and invocation counts.
    private final SshCommandService agentConnection = mock(SshCommandService.class, invocation -> {
        if (invocation.getMethod().getName().equals("execute")) {
            List<String> args = invocation.getArgument(1);
            if (List.of("/usr/local/deploy/bin/deploy-agent-probe").equals(args)) {
                Server server = invocation.getArgument(0);
                return agentProbe(server.getServerType(), true);
            }
            if (isStopGraceCapabilityCommand(args)) {
                LocalDateTime now = LocalDateTime.now();
                return new RemoteCommandResult(0, "STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nQUIESCE_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1",
                    "", "capabilities", now, now, 1);
            }
            if (isReadOnlyWasProbe(args)) {
                LocalDateTime now = LocalDateTime.now();
                return new RemoteCommandResult(0, "Health check passed", "", "probe", now, now, 1);
            }
            List<String> command = withoutSudo(args);
            if (command.size() == 4
                && command.get(0).equals("/usr/local/deploy/bin/deploy-web-static.sh")
                && command.get(1).equals("status") && command.get(2).equals("--web-root")
                && List.of("/var/www/portal", "/srv/web/app").contains(command.get(3))) {
                LocalDateTime now = LocalDateTime.now();
                return new RemoteCommandResult(0, "ARTIFACT_TYPE=STATIC_BUNDLE\nCURRENT_RELEASE=fixture-release\nNGINX_ACTIVE=true",
                    "", "status", now, now, 1);
            }
        }
        return org.mockito.AdditionalAnswers.delegatesTo(sshCommandService).answer(invocation);
    });
    private final RemoteCommandLogService remoteCommandLogService = mock(RemoteCommandLogService.class);
    private final SystemSettingService settingService = mock(SystemSettingService.class);
    private final ImageArtifactService imageExportService = mock(ImageArtifactService.class);
    private final RemoteDeployService service =
        new RemoteDeployService(agentConnection, remoteCommandLogService, settingService, imageExportService);

    private static boolean isStopGraceCapabilityCommand(List<String> args) {
        List<String> command = withoutSudo(args);
        return command.size() == 2 && command.get(1).equals("capabilities")
            && List.of("/usr/local/deploy/bin/deploy-was-app.sh", "/usr/local/deploy/bin/deploy-web-app.sh")
                .contains(command.get(0));
    }

    private static List<String> withoutSudo(List<String> args) {
        if (args == null) return List.of();
        return args.size() >= 2 && args.get(0).equals("sudo") && args.get(1).equals("-n")
            ? args.subList(2, args.size()) : args;
    }

    private static boolean isReadOnlyWasProbe(List<String> args) {
        List<String> command = withoutSudo(args);
        if (command.size() < 8 || command.size() % 2 != 0
            || !List.of("/usr/local/deploy/bin/deploy-was-app.sh", "/usr/local/deploy/bin/deploy-web-app.sh").contains(command.get(0))
            || !command.get(1).equals("probe")) return false;
        java.util.Map<String, String> options = new java.util.HashMap<>();
        for (int i = 2; i < command.size(); i += 2) {
            if (!List.of("--container", "--managed-project", "--health-mode", "--stop-grace-seconds", "--health-port", "--health-path")
                .contains(command.get(i)) || options.put(command.get(i), command.get(i + 1)) != null) return false;
        }
        return options.getOrDefault("--container", "").matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
            && List.of("http", "container", "none").contains(options.getOrDefault("--health-mode", ""))
            && options.getOrDefault("--stop-grace-seconds", "").matches("[0-9]+")
            && (!options.containsKey("--health-port") || options.get("--health-port").matches("[0-9]+"))
            && (!options.containsKey("--health-path") || options.get("--health-path").startsWith("/"))
            && (!"http".equals(options.get("--health-mode"))
                || (options.containsKey("--health-port") && options.containsKey("--health-path")));
    }

    @Test
    void unsupportedAgentStopsBeforeDurableAttemptRuntimeProvisioningOrArtifactTransfer() {
        var group = group("TEST-WAS", ServerEnvironment.TEST);
        var was = server("was", ServerType.WAS, group, 1);
        group.setServers(List.of(was));
        var request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setId(12L);
        request.getProject().setManagedRuntimeSettings(List.of(
            new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L, "MANAGED_REVISION", 7L, "f".repeat(64), false, 0)));
        var configuration = mock(RuntimeConfigurationService.class);
        var configuredService = new RemoteDeployService(agentConnection, remoteCommandLogService,
            settingService, imageExportService, configuration);

        org.mockito.Mockito.doReturn(new RemoteCommandResult(
            0, "prefix STOP_GRACE_VERSION=1", "", "capabilities", null, null, 1))
            .when(agentConnection).execute(eq(was), org.mockito.ArgumentMatchers.argThat(
                RemoteDeployServiceTest::isStopGraceCapabilityCommand));

        assertThatThrownBy(() -> configuredService.execute(request, ignored -> {}))
            .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("종료 유예");
        verify(agentConnection).execute(eq(was), org.mockito.ArgumentMatchers.argThat(
            RemoteDeployServiceTest::isStopGraceCapabilityCommand));
        org.mockito.Mockito.verifyNoInteractions(sshCommandService, imageExportService, configuration);
        verify(remoteCommandLogService, never()).beginTargetAttempt(any(), any(), any());
    }

    @BeforeEach
    void imageExportsCarryVerifiedArchiveIdentity() {
        when(imageExportService.exportImage(any(), any(), any()))
            .thenReturn(new ImageArtifactService.ImageArchive("c".repeat(64), "sha256:" + "b".repeat(64)));
    }

    @Test
    void managedRevisionUsesSensitiveStdinAndBoundedStorageArgs() {
        ServerGroup wasGroup=group("TEST-WAS",ServerEnvironment.TEST);
        Server was=server("test-was1",ServerType.WAS,wasGroup,1); wasGroup.setServers(List.of(was));
        DeployRequest request=request(DeployEnvironment.TEST,List.of(wasGroup));
        request.getProject().setId(12L);
        request.getProject().setContainerUser("10001:10001");
        request.getProject().setManagedRuntimeSettings(List.of(new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L,"MANAGED_REVISION",7L,"f".repeat(64),true,32)));
        RuntimeConfigurationService configuration=mock(RuntimeConfigurationService.class);
        when(configuration.decryptPinned(eq(12L),eq(DeployEnvironment.TEST),any())).thenReturn("PASSWORD=never-argv\n");
        when(sshCommandService.executeSensitive(any(),any(),any())).thenReturn(successResult());
        when(sshCommandService.execute(any(),any())).thenReturn(successResult());
        new RemoteDeployService(agentConnection,remoteCommandLogService,settingService,imageExportService,configuration).execute(request,ignored->{});
        ArgumentCaptor<List<String>> sensitive=ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).executeSensitive(eq(was),sensitive.capture(),any());
        assertThat(sensitive.getValue()).containsSubsequence("/usr/local/deploy/bin/deploy-runtime.sh","env");
        assertThat(sensitive.getValue().toString()).doesNotContain("never-argv","PASSWORD=");
        ArgumentCaptor<List<String>> command=ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(eq(was),command.capture());
        assertThat(command.getValue()).containsSubsequence("--env-file","/etc/deploy/runtime/12/TEST/7.env")
            .containsSubsequence("--tmp-mib","32").contains("--persistent-logs").doesNotContain("never-argv");
    }

    @Test
    void sensitiveProvisioningFailureStopsBeforeContainerCommand() {
        ServerGroup group=group("TEST-WAS",ServerEnvironment.TEST);
        Server server=server("test-was1",ServerType.WAS,group,1); group.setServers(List.of(server));
        DeployRequest request=request(DeployEnvironment.TEST,List.of(group)); request.getProject().setId(12L);
        request.getProject().setManagedRuntimeSettings(List.of(new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L,"MANAGED_REVISION",7L,"f".repeat(64),false,0)));
        RuntimeConfigurationService configuration=mock(RuntimeConfigurationService.class);
        when(configuration.decryptPinned(any(),any(),any())).thenReturn("PASSWORD=private");
        when(sshCommandService.executeSensitive(any(),any(),any())).thenReturn(new RemoteCommandResult(1,"private","private","",LocalDateTime.now(),LocalDateTime.now(),1));
        assertThatThrownBy(()->new RemoteDeployService(agentConnection,remoteCommandLogService,settingService,imageExportService,configuration).execute(request,ignored->{}))
            .isInstanceOf(RemoteExecutionException.class).hasMessageNotContaining("private");
        verify(sshCommandService,never()).execute(any(),any());
    }

    @Test
    void executeDeploysOnlyTestWasAndWebTargetsWithHealthArguments() {
        ServerGroup testWasGroup = group("TEST-WAS", ServerEnvironment.TEST);
        ServerGroup testWebGroup = group("TEST-WEB", ServerEnvironment.TEST);
        ServerGroup prodWasGroup = group("PROD-WAS", ServerEnvironment.PROD);
        Server testWas = server("test-was1", ServerType.WAS, testWasGroup, 2);
        Server testWeb = server("test-web1", ServerType.WEB, testWebGroup, 1);
        Server prodWas = server("prod-was1", ServerType.WAS, prodWasGroup, 1);
        testWasGroup.setServers(List.of(testWas));
        testWebGroup.setServers(List.of(testWeb));
        prodWasGroup.setServers(List.of(prodWas));

        DeployRequest request = request(DeployEnvironment.TEST, List.of(testWasGroup, testWebGroup, prodWasGroup));

        when(sshCommandService.execute(any(Server.class), any()))
            .thenReturn(successResult());

        DeployStatus status = service.execute(request, ignored -> {
        });

        assertThat(status).isEqualTo(DeployStatus.TEST_DEPLOYED);

        ArgumentCaptor<Server> serverCaptor = ArgumentCaptor.forClass(Server.class);
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService, times(2)).execute(serverCaptor.capture(), argsCaptor.capture());

        assertThat(serverCaptor.getAllValues()).extracting(Server::getName)
            .containsExactly("test-was1", "test-web1");

        List<List<String>> commands = argsCaptor.getAllValues();
        assertThat(commands.get(0)).containsSubsequence(
            "sudo", "-n", "/usr/local/deploy/bin/deploy-was-app.sh",
            "deploy", "--image", "192.0.2.10:5000/payment/payment-api@sha256:abc",
            "--container", "payment-api",
            "--health-mode", "http",
            "--host-port", "8080",
            "--container-port", "8080",
            "--health-port", "8080",
            "--health-path", "/actuator/health"
        );
        assertThat(commands.get(1)).contains(
            "/usr/local/deploy/bin/deploy-web-app.sh",
            "--reload-nginx",
            "true"
        );
        verify(remoteCommandLogService, times(2)).saveAttemptResult(any(), any(), any(), any());
    }

    @Test
    void containerRunningHealthDoesNotInventHttpPortOrPath() {
        ServerGroup group = group("TEST-WAS", ServerEnvironment.TEST);
        Server was = server("test-was1", ServerType.WAS, group, 1);
        group.setServers(List.of(was));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setHealthCheckMode(HealthCheckMode.CONTAINER_RUNNING);
        request.getProject().setHealthCheckPort(null);
        request.getProject().setHealthCheckPath(null);
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(eq(was), command.capture());
        assertThat(command.getValue())
            .containsSubsequence("--health-mode", "container")
            .doesNotContain("--health-port", "--health-path", "--host-port", "--container-port");
    }

    @Test
    void componentHealthSettingsOverrideProjectDefaults() {
        ServerGroup group = group("TEST-WAS", ServerEnvironment.TEST);
        Server was = server("test-was1", ServerType.WAS, group, 1);
        group.setServers(List.of(was));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setComponents(List.of(DeployComponent.builder()
            .id(51L).code("api").role(ServerType.WAS).artifactType(ArtifactType.CONTAINER)
            .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL)
            .healthCheckPort(19090).healthCheckPath("/ready").active(true).build()));
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(eq(was), command.capture());
        assertThat(command.getValue())
            .containsSubsequence("--health-mode", "http", "--host-port", "19090",
                "--container-port", "19090", "--health-port", "19090", "--health-path", "/ready")
            .doesNotContain("/actuator/health");
    }

    @Test
    void executeFailsWhenNoTargetServersExist() {
        DeployRequest request = request(DeployEnvironment.TEST, List.of(group("PROD-WAS", ServerEnvironment.PROD)));

        assertThatThrownBy(() -> service.execute(request, ignored -> {
        }))
            .isInstanceOf(RemoteExecutionException.class);

        verify(sshCommandService, never()).execute(any(), any());
    }

    @Test
    void executeProdTargetsWasBeforeWebAndUsesServerOrder() {
        ServerGroup prodWebGroup = group("A-WEB", ServerEnvironment.PROD);
        ServerGroup prodWasGroup = group("Z-WAS", ServerEnvironment.PROD);
        Server prodWeb1 = server("prod-web1", ServerType.WEB, prodWebGroup, 1);
        Server prodWeb2 = server("prod-web2", ServerType.WEB, prodWebGroup, 2);
        Server prodWas1 = server("prod-was1", ServerType.WAS, prodWasGroup, 1);
        Server prodWas2 = server("prod-was2", ServerType.WAS, prodWasGroup, 2);
        prodWebGroup.setServers(List.of(prodWeb2, prodWeb1));
        prodWasGroup.setServers(List.of(prodWas2, prodWas1));

        DeployRequest request = request(DeployEnvironment.PROD, List.of(prodWebGroup, prodWasGroup));
        when(sshCommandService.execute(any(Server.class), any()))
            .thenReturn(successResult());

        DeployStatus status = service.execute(request, ignored -> {
        });

        assertThat(status).isEqualTo(DeployStatus.COMPLETED);
        ArgumentCaptor<Server> serverCaptor = ArgumentCaptor.forClass(Server.class);
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService, times(4)).execute(serverCaptor.capture(), argsCaptor.capture());
        assertThat(serverCaptor.getAllValues()).extracting(Server::getName)
            .containsExactly("prod-was1", "prod-was2", "prod-web1", "prod-web2");
    }

    @Test
    void executeServerDeploysOnlySpecifiedTarget() {
        ServerGroup wasGroup = group("WAS", ServerEnvironment.PROD);
        Server was1 = server("was1", ServerType.WAS, wasGroup, 1);
        Server was2 = server("was2", ServerType.WAS, wasGroup, 2);
        was1.setId(101L);
        was2.setId(102L);
        wasGroup.setServers(List.of(was1, was2));

        DeployRequest request = request(DeployEnvironment.PROD, List.of(wasGroup));
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.executeServer(request, 102L, ignored -> {
        });

        ArgumentCaptor<Server> serverCaptor = ArgumentCaptor.forClass(Server.class);
        verify(sshCommandService, times(1)).execute(serverCaptor.capture(), any());
        assertThat(serverCaptor.getValue().getName()).isEqualTo("was2");
    }

    @Test
    void executeWaveStrategyInterleavesByHostIndex() {
        ServerGroup webGroup = group("WEB", ServerEnvironment.PROD);
        ServerGroup wasGroup = group("WAS", ServerEnvironment.PROD);
        Server was1 = server("was1", ServerType.WAS, wasGroup, 1);
        Server was2 = server("was2", ServerType.WAS, wasGroup, 2);
        Server web1 = server("web1", ServerType.WEB, webGroup, 1);
        Server web2 = server("web2", ServerType.WEB, webGroup, 2);
        wasGroup.setServers(List.of(was1, was2));
        webGroup.setServers(List.of(web1, web2));

        DeployRequest request = request(DeployEnvironment.PROD, List.of(webGroup, wasGroup));
        request.setDeployStrategy(DeployStrategy.WAVE);
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.execute(request, ignored -> {
        });

        ArgumentCaptor<Server> serverCaptor = ArgumentCaptor.forClass(Server.class);
        verify(sshCommandService, times(4)).execute(serverCaptor.capture(), any());
        assertThat(serverCaptor.getAllValues()).extracting(Server::getName)
            .containsExactly("was1", "web1", "was2", "web2");
    }

    @Test
    void executeScopeWasOnlyDeploysOnlyWasServers() {
        ServerGroup wasGroup = group("WAS", ServerEnvironment.PROD);
        ServerGroup webGroup = group("WEB", ServerEnvironment.PROD);
        Server was1 = server("was1", ServerType.WAS, wasGroup, 1);
        Server web1 = server("web1", ServerType.WEB, webGroup, 1);
        wasGroup.setServers(List.of(was1));
        webGroup.setServers(List.of(web1));

        DeployRequest request = request(DeployEnvironment.PROD, List.of(wasGroup, webGroup));
        request.setDeployScope(DeployScope.WAS_ONLY);
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.execute(request, ignored -> {
        });

        ArgumentCaptor<Server> serverCaptor = ArgumentCaptor.forClass(Server.class);
        verify(sshCommandService, times(1)).execute(serverCaptor.capture(), any());
        assertThat(serverCaptor.getAllValues()).extracting(Server::getName).containsExactly("was1");
    }

    @Test
    void executeUsesStaticScriptForStaticBundleWebComponent() {
        ServerGroup prodWebGroup = group("PROD-WEB", ServerEnvironment.PROD);
        Server prodWeb = server("prod-web1", ServerType.WEB, prodWebGroup, 1);
        prodWebGroup.setServers(List.of(prodWeb));

        Project project = Project.builder()
            .name("portal")
            .imageRepository("reg:5000/portal/web")
            .serverGroups(new ArrayList<>(List.of(prodWebGroup)))
            .components(new ArrayList<>(List.of(
                DeployComponent.builder()
                    .code("web").role(ServerType.WEB).artifactType(ArtifactType.STATIC_BUNDLE)
                    .webRoot("/var/www/portal").healthCheckPort(80).healthCheckPath("/").active(true).build()
            )))
            .build();
        DeployRequest request = DeployRequest.builder()
            .id(1L).reqNo("REQ-S").environment(DeployEnvironment.PROD)
            .imageDigest("sha256:web").project(project).build();

        prodWeb.setId(77L);
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        when(sshCommandService.copyFile(any(), any(), any())).thenReturn(successResult());
        when(imageExportService.extractStaticBundle(any(), any(), any(), any())).thenReturn("bundlesha");

        service.execute(request, ignored -> {
        });

        // 정적 산출물은 배포망이 이미지에서 꺼낸다(DMZ 에는 docker 를 두지 않는다).
        verify(imageExportService).extractStaticBundle(
            eq("reg:5000/portal/web@sha256:web"), eq("/dist"), any(), any());
        verify(sshCommandService).copyFile(any(Server.class), any(), any());

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        // 스테이징 디렉터리 생성 + 배포 명령
        verify(sshCommandService, times(2)).execute(any(Server.class), argsCaptor.capture());
        assertThat(argsCaptor.getAllValues().get(1)).containsSubsequence(
            "/usr/local/deploy/bin/deploy-web-static.sh", "deploy",
            "--bundle", "/var/tmp/deploy-ops/REQ-S-77.tar.gz",
            "--bundle-sha256", "bundlesha",
            "--web-root", "/var/www/portal"
        );
    }

    @Test
    void executeUsesPerComponentImageWhenRequestItemPresent() {
        ServerGroup wasGroup = group("WAS", ServerEnvironment.PROD);
        Server was1 = server("was1", ServerType.WAS, wasGroup, 1);
        wasGroup.setServers(List.of(was1));

        com.portfolio.deployops.domain.DeployComponent api = com.portfolio.deployops.domain.DeployComponent.builder()
            .id(55L).code("api").role(ServerType.WAS)
            .artifactType(ArtifactType.CONTAINER)
            .imageRepository("reg:5000/app/api").active(true).build();

        Project project = Project.builder()
            .name("csr-app")
            .imageRepository("reg:5000/app/fallback")
            .healthCheckPort(8080)
            .healthCheckPath("/actuator/health")
            .serverGroups(new ArrayList<>(List.of(wasGroup)))
            .components(new ArrayList<>(List.of(api)))
            .build();

        DeployRequest request = DeployRequest.builder()
            .id(9L).reqNo("REQ-CSR").environment(DeployEnvironment.PROD)
            .imageDigest("sha256:fallback").project(project)
            .items(new ArrayList<>(List.of(
                com.portfolio.deployops.domain.DeployRequestItem.builder()
                    .componentId(55L).componentCode("api").imageDigest("sha256:apiimg").build()
            )))
            .build();

        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        service.execute(request, ignored -> {
        });

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(Server.class), argsCaptor.capture());
        // 컴포넌트(api)의 저장소 + 항목의 digest 사용 (요청 단일 이미지 폴백 아님)
        assertThat(argsCaptor.getValue()).contains("reg:5000/app/api@sha256:apiimg");
        assertThat(argsCaptor.getValue()).doesNotContain("reg:5000/app/fallback@sha256:fallback");
    }

    @Test
    void executeUsesProxyReloadScriptForProxyWebComponent() {
        ServerGroup prodWebGroup = group("PROD-WEB", ServerEnvironment.PROD);
        Server prodWeb = server("prod-web1", ServerType.WEB, prodWebGroup, 1);
        prodWebGroup.setServers(List.of(prodWeb));

        Project project = Project.builder()
            .name("portal")
            .imageRepository("reg:5000/portal/api")
            .serverGroups(new ArrayList<>(List.of(prodWebGroup)))
            .components(new ArrayList<>(List.of(
                DeployComponent.builder()
                    .code("web").role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD)
                    .healthCheckPort(48080).healthCheckPath("/ready?probe=1").active(true).build()
            )))
            .build();
        DeployRequest request = DeployRequest.builder()
            .id(2L).reqNo("REQ-P").environment(DeployEnvironment.PROD)
            .imageDigest("sha256:api").project(project).build();

        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        service.execute(request, ignored -> {
        });

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(Server.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsSubsequence(
            "/usr/local/deploy/bin/deploy-web-proxy.sh", "reload",
            "--health-port", "48080", "--health-path", "/ready?probe=1"
        );
    }

    @Test
    void proxyHttpFailureCannotReturnDeploymentComplete() {
        DeployRequest request = proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, 48080, "/");
        LocalDateTime now = LocalDateTime.now();
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(
            new RemoteCommandResult(1, "nginx reloaded; HTTP health failed", "", "ssh ...", now, now, 1L));
        assertThatThrownBy(() -> service.execute(request, ignored -> {}))
            .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("exit=1");
        verify(remoteCommandLogService).saveAttemptResult(eq(request), any(Server.class), any(), any());
    }

    @Test
    void proxyRejectsDisabledHealthAndInvalidInputsBeforeSsh() {
        for (HealthCheckMode mode : List.of(HealthCheckMode.NONE, HealthCheckMode.CONTAINER_RUNNING)) {
            assertThatThrownBy(() -> service.execute(proxyRequest(mode, 80, "/"), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("HTTP 헬스체크");
        }
        for (int port : List.of(0, -1, 65536)) {
            assertThatThrownBy(() -> service.execute(proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, port, "/"), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("올바르지");
        }
        for (String path : List.of("http://other/", "//other/", "/a\nX: y", "/a#fragment")) {
            assertThatThrownBy(() -> service.execute(proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, 80, path), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("올바르지");
        }
        verify(sshCommandService, never()).execute(any(), any());
    }

    @Test
    void proxyInheritsProjectHealthSettingsWhenComponentHasNoOverrides() {
        DeployRequest request = proxyRequest(HealthCheckMode.HTTP_DIRECT, null, null);
        request.getProject().setHealthCheckPort(19090);
        request.getProject().setHealthCheckPath("/ready");
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        service.execute(request, ignored -> {});
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(), args.capture());
        assertThat(args.getValue()).containsSubsequence("--health-port", "19090", "--health-path", "/ready");
    }

    private DeployRequest proxyRequest(HealthCheckMode mode, Integer port, String path) {
        ServerGroup group = group("TEST-WEB", ServerEnvironment.TEST);
        group.setServers(List.of(server("test-web", ServerType.WEB, group, 1)));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setComponents(new ArrayList<>(List.of(DeployComponent.builder()
            .code("web").role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD)
            .healthCheckMode(mode).healthCheckPort(port).healthCheckPath(path).active(true).build())));
        return request;
    }

    @Test
    void proxyHostIsASeparateValidatedHeaderArgumentNotAConnectionTarget() {
        DeployRequest request = proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, 8081, "/ready");
        request.getProject().getComponents().getFirst().setProxyHealthHost("portal.internal");
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());
        service.execute(request, ignored -> {});
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(), args.capture());
        assertThat(args.getValue()).containsSubsequence("--health-host", "portal.internal");
    }

    private DeployRequest request(DeployEnvironment environment, List<ServerGroup> groups) {
        return DeployRequest.builder()
            .id(10L)
            .reqNo("REQ-001")
            .environment(environment)
            .imageDigest("sha256:abc")
            .project(Project.builder()
                .name("payment-api")
                .imageRepository("192.0.2.10:5000/payment/payment-api")
                .healthCheckPort(8080)
                .healthCheckPath("/actuator/health")
                .serverGroups(new ArrayList<>(groups))
                .build())
            .build();
    }

    private ServerGroup group(String name, ServerEnvironment environment) {
        return ServerGroup.builder()
            .name(name)
            .environment(environment)
            .rollingIntervalSec(0)
            .minAvailable(0)
            .observationSeconds(0)
            .servers(new ArrayList<>())
            .build();
    }

    private Server server(String name, ServerType type, ServerGroup group, int orderIndex) {
        return Server.builder()
            .name(name)
            .ipAddress("127.0.0.1")
            .sshPort(22)
            .deployAccount("deploy")
            .serverType(type)
            .serverGroup(group)
            .orderIndex(orderIndex)
            .build();
    }

    private RemoteCommandResult successResult() {
        LocalDateTime now = LocalDateTime.now();
        return new RemoteCommandResult(0, "Health check passed", "", "ssh ...", now, now, 10L);
    }

    // ── 이미지 전달 방식 ──────────────────────────────────────────────
    // 망분리로 대상 서버가 레지스트리에 붙을 수 없는 구성에서는 배포시스템이 tar 를 밀어 넣는다.
    // 설정을 건드리지 않으면 종전(registry pull) 그대로여야 한다.

    @Test
    void defaultsToRegistryPullSoExistingDeploymentsAreUnchanged() {
        ServerGroup wasGroup = group("TEST-WAS", ServerEnvironment.TEST);
        Server was = server("test-was1", ServerType.WAS, wasGroup, 1);
        wasGroup.setServers(List.of(was));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(wasGroup));

        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(Server.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).doesNotContain("--image-tar");
        verify(sshCommandService, never()).copyFile(any(), any(), any());
        verify(imageExportService, never()).exportImage(any(), any(), any());
    }

    @Test
    void pushModeExportsImageTransfersTarAndPassesItToAgent() {
        ServerGroup wasGroup = group("TEST-WAS", ServerEnvironment.TEST);
        Server was = server("test-was1", ServerType.WAS, wasGroup, 1);
        wasGroup.setServers(List.of(was));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(wasGroup));

        when(settingService.get(eq(RemoteDeployService.IMAGE_TRANSFER_SETTING), any())).thenReturn("push");
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        when(sshCommandService.copyFile(any(), any(), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        verify(imageExportService).exportImage(
            eq("192.0.2.10:5000/payment/payment-api@sha256:abc"), any(), any());
        verify(sshCommandService).copyFile(any(Server.class), any(), any());

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        // mkdir(스테이징 디렉터리) + 배포 명령 2회
        verify(sshCommandService, times(2)).execute(any(Server.class), argsCaptor.capture());
        List<String> deployArgs = argsCaptor.getAllValues().get(1);
        assertThat(deployArgs).containsSubsequence("--image-tar");
        assertThat(deployArgs).containsSubsequence("--image-id", "sha256:" + "b".repeat(64), "--image-sha256", "c".repeat(64));
        assertThat(deployArgs).containsSubsequence("--image", "192.0.2.10:5000/payment/payment-api@sha256:abc");
        assertThat(deployArgs).anyMatch(arg -> arg.endsWith(".tar"));
    }

    @Test
    void pushModeCreatesStagingDirectoryBeforeTransfer() {
        ServerGroup wasGroup = group("TEST-WAS", ServerEnvironment.TEST);
        Server was = server("test-was1", ServerType.WAS, wasGroup, 1);
        wasGroup.setServers(List.of(was));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(wasGroup));

        when(settingService.get(eq(RemoteDeployService.IMAGE_TRANSFER_SETTING), any())).thenReturn("push");
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        when(sshCommandService.copyFile(any(), any(), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService, times(2)).execute(any(Server.class), argsCaptor.capture());
        // 스테이징 디렉터리는 sudo 없이 배포 계정 소유로 만든다
        assertThat(argsCaptor.getAllValues().get(0))
            .containsExactly("mkdir", "-p", "/var/tmp/deploy-ops");
    }

    @Test
    void imageExportWithoutIdentityFailsBeforeTransferAndRemovesTemporaryArchive() {
        ServerGroup group = group("TEST-WAS", ServerEnvironment.TEST);
        group.setServers(List.of(server("was", ServerType.WAS, group, 1)));
        when(settingService.get(eq(RemoteDeployService.IMAGE_TRANSFER_SETTING), any())).thenReturn("push");
        when(imageExportService.exportImage(any(), any(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.execute(request(DeployEnvironment.TEST, List.of(group)), ignored -> {}))
            .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("무결성 메타데이터");
        verify(sshCommandService, never()).copyFile(any(), any(), any());
        verify(sshCommandService, never()).execute(any(), any());
        ArgumentCaptor<java.nio.file.Path> archive = ArgumentCaptor.forClass(java.nio.file.Path.class);
        verify(imageExportService).exportImage(any(), archive.capture(), any());
        assertThat(archive.getValue()).doesNotExist();
    }

    @Test
    void staticBundleKeepsItsChecksumContractWithoutContainerIdentityArguments() {
        ServerGroup group = group("TEST-WEB", ServerEnvironment.TEST);
        group.setServers(List.of(server("web", ServerType.WEB, group, 1)));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setComponents(List.of(DeployComponent.builder().code("web").role(ServerType.WEB)
            .artifactType(ArtifactType.STATIC_BUNDLE).webRoot("/srv/web/app").active(true).build()));
        when(imageExportService.extractStaticBundle(any(), any(), any(), any())).thenReturn("d".repeat(64));
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());
        when(sshCommandService.copyFile(any(), any(), any())).thenReturn(successResult());
        assertThat(service.execute(request, ignored -> {})).isEqualTo(DeployStatus.TEST_DEPLOYED);
        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService, times(2)).execute(any(), commands.capture());
        assertThat(commands.getAllValues().getLast()).containsSubsequence("--bundle-sha256", "d".repeat(64))
            .doesNotContain("--image-id", "--image-sha256", "--image-tar");
        verify(imageExportService, never()).exportImage(any(), any(), any());
    }

    @Test
    void explicitTcpBindingsReachBothAgentsWithoutExtraHealthPortPublication() {
        ServerGroup group = group("TEST", ServerEnvironment.TEST);
        group.setServers(List.of(server("was", ServerType.WAS, group, 1), server("web", ServerType.WEB, group, 1)));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(group));
        request.getProject().setContainerPorts("9090:9090,9080:9080");
        request.getProject().setHealthCheckPort(9090);
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService, times(2)).execute(any(Server.class), commands.capture());
        for (List<String> command : commands.getAllValues()) {
            assertThat(command).containsSubsequence("--publish", "9080:9080", "--publish", "9090:9090", "--health-port", "9090")
                .doesNotContain("--host-port", "--container-port", "--network", "host");
        }
    }

    @Test
    void approvedSnapshotRuntimeSettingsOverrideLaterGlobalChanges() {
        ServerGroup wasGroup = group("TEST-WAS", ServerEnvironment.TEST);
        Server was = server("test-was1", ServerType.WAS, wasGroup, 1);
        wasGroup.setServers(List.of(was));
        DeployRequest request = request(DeployEnvironment.TEST, List.of(wasGroup));
        request.getProject().setDeploymentRuntimeSettings(new DeploymentRuntimeSettings(
            "push", "/approved/staging", "/approved/static", "1536m",
            "http://approved-nexus:8081", "approved-nexus:5000", "", false
        ));

        when(settingService.get(eq(RemoteDeployService.IMAGE_TRANSFER_SETTING), any())).thenReturn("registry");
        when(settingService.get(eq(RemoteDeployService.STAGING_DIR_SETTING), any())).thenReturn("/changed/staging");
        when(sshCommandService.execute(any(Server.class), any())).thenReturn(successResult());
        when(sshCommandService.copyFile(any(), any(), any())).thenReturn(successResult());

        service.execute(request, ignored -> { });

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService, times(2)).execute(any(Server.class), argsCaptor.capture());
        assertThat(argsCaptor.getAllValues().get(0)).containsExactly("mkdir", "-p", "/approved/staging");
        assertThat(argsCaptor.getAllValues().get(1)).containsSubsequence("--memory", "1536m");
        verify(imageExportService).exportImage(any(), any(), any());
    }

    @Test
    void proxyCommandUsesRequestEnvironmentOverrideForLegacyUnsnapshottedRequest() {
        DeployRequest request = proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, 80, "/ready");
        DeployComponent component = request.getProject().getComponents().getFirst();
        component.setProxyHealthHost("common.internal");
        component.setProxyHealthHostTest("test.internal");
        component.setProxyHealthHostProd("prod.internal");
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());
        service.execute(request, ignored -> {});
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(), args.capture());
        assertThat(args.getValue()).containsSubsequence("--health-host", "test.internal")
            .doesNotContain("prod.internal", "common.internal");
    }

    @Test
    void proxyProdCommandUsesProdOverrideAndDoesNotReuseTestHost() {
        ServerGroup group = group("PROD-WEB", ServerEnvironment.PROD);
        group.setServers(List.of(server("prod-web", ServerType.WEB, group, 1)));
        DeployRequest request = request(DeployEnvironment.PROD, List.of(group));
        request.getProject().setComponents(new ArrayList<>(List.of(DeployComponent.builder()
            .code("web").role(ServerType.WEB).artifactType(ArtifactType.PROXY_RELOAD)
            .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL).healthCheckPort(80).healthCheckPath("/ready")
            .proxyHealthHost("common.internal").proxyHealthHostTest("test.internal")
            .proxyHealthHostProd("prod.internal").active(true).build())));
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());
        service.execute(request, ignored -> {});
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(), args.capture());
        assertThat(args.getValue()).containsSubsequence("--health-host", "prod.internal").doesNotContain("test.internal");
    }

    @Test
    void statusWithUnknownEnvironmentUsesCommonHostRatherThanGuessingTest() {
        DeployRequest request = proxyRequest(HealthCheckMode.SSH_LOCAL_CURL, 80, "/ready");
        DeployComponent component = request.getProject().getComponents().getFirst();
        component.setProxyHealthHost("common.internal");
        component.setProxyHealthHostTest("test.internal");
        Server server = request.getProject().getServerGroups().getFirst().getServers().getFirst();
        server.setEnvironment(null);
        when(sshCommandService.execute(any(), any())).thenReturn(successResult());
        service.controlService(request.getProject(), server, "status");
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(any(), args.capture());
        assertThat(args.getValue()).containsSubsequence("--health-host", "common.internal").doesNotContain("test.internal");
    }
}
