package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.RemoteCommandLog;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RemoteCommandLogServiceTest {

    @Test
    void beginAttemptLocksFirstAndRequiresRunningPinnedInsertAndGeneratedId() {
        var guard = Mockito.mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class);
        var service = new RemoteCommandLogService(repository, new SecretMaskingService(), guard);
        Mockito.when(guard.lockSingleton()).thenReturn(1);
        Mockito.when(repository.insertTargetAttempt(Mockito.any())).thenAnswer(call -> {
            com.portfolio.deployops.domain.TargetDeploymentAttempt attempt = call.getArgument(0);
            attempt.setId(77L); return 1;
        });
        var request = DeployRequest.builder().id(10L).planSnapshotHash("a".repeat(64)).build();
        assertThat(service.beginTargetAttempt(request, Server.builder().id(20L).build(), "registry/app@sha256:fixed")).isEqualTo(77L);
        var order = Mockito.inOrder(guard, repository);
        order.verify(guard).lockSingleton();
        var captor = ArgumentCaptor.forClass(com.portfolio.deployops.domain.TargetDeploymentAttempt.class);
        order.verify(repository).insertTargetAttempt(captor.capture());
        assertThat(captor.getValue().getPlanHash()).isEqualTo("a".repeat(64));
        assertThat(captor.getValue().getArtifactHash()).matches("[a-f0-9]{64}").doesNotContain("registry");
    }

    @Test
    void lockOrInsertFailurePreventsAttemptFromBeingAccepted() {
        var guard = Mockito.mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class);
        var service = new RemoteCommandLogService(repository, new SecretMaskingService(), guard);
        var request = DeployRequest.builder().id(10L).planSnapshotHash("a".repeat(64)).build();
        var server = Server.builder().id(20L).build();
        Mockito.when(guard.lockSingleton()).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.beginTargetAttempt(request, server, "artifact"))
            .hasMessageContaining("잠금");
        Mockito.verifyNoInteractions(repository);
        Mockito.when(guard.lockSingleton()).thenReturn(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.beginTargetAttempt(request, server, "artifact"))
            .hasMessageContaining("저장하지 못했습니다");
    }

    private final RemoteCommandLogRepository repository = Mockito.mock(RemoteCommandLogRepository.class);
    private final RemoteCommandLogService service = new RemoteCommandLogService(repository, new SecretMaskingService());

    @Test
    void masksSecretsBeforeSavingCommandLog() {
        LocalDateTime now = LocalDateTime.now();
        RemoteCommandResult result = new RemoteCommandResult(
            1,
            "login failed password=DemoAdmin!2026",
            "PRIVATE-TOKEN: glpat-secret-token",
            "ssh -i C:\\Users\\demo-user\\.ssh\\id_rsa deploy@host bash -lc 'echo token=abc123'",
            now,
            now,
            12L
        );

        service.save(
            DeployRequest.builder().id(10L).build(),
            Server.builder().id(20L).name("prod-was").build(),
            result
        );

        ArgumentCaptor<RemoteCommandLog> captor = ArgumentCaptor.forClass(RemoteCommandLog.class);
        verify(repository).insert(captor.capture());
        RemoteCommandLog saved = captor.getValue();

        assertThat(saved.getCommandDisplay()).contains("-i ***").contains("token=***");
        assertThat(saved.getStdoutText()).contains("password=***");
        assertThat(saved.getStderrText()).contains("PRIVATE-TOKEN: ***");
        assertThat(saved.getCommandDisplay()).doesNotContain("id_rsa").doesNotContain("abc123");
        assertThat(saved.getStdoutText()).doesNotContain("DemoAdmin!2026");
        assertThat(saved.getStderrText()).doesNotContain("glpat-secret-token");
    }

    @Test
    void recentByProjectCapsTheLimitSoOnePanelCannotPullEverything() {
        RemoteCommandLogService service =
            new RemoteCommandLogService(repository, Mockito.mock(SecretMaskingService.class));

        // 화면과 AI 프롬프트가 같은 메서드를 쓴다. 상한이 없으면 프롬프트가 무한정 커지고
        // 한 번의 호출로 프로젝트 전체 로그가 모델로 넘어간다.
        service.findRecentByProject(7L, 99999);
        Mockito.verify(repository).findRecentByProjectId(7L, RemoteCommandLogService.MAX_RECENT);

        service.findRecentByProject(7L, 0);
        Mockito.verify(repository).findRecentByProjectId(7L, 1);

        service.findRecentByProject(7L, 12);
        Mockito.verify(repository).findRecentByProjectId(7L, 12);
    }

    @Test
    void aiAnalysisReadsLogsOnTheServerInsteadOfTrustingTheRequestBody() throws Exception {
        String controller = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src", "main", "java", "com", "portfolio", "deployops", "controller", "AiApiController.java"));

        // 핵심 보안 성질 — 클라이언트는 프로젝트 ID 만 보낸다.
        // 로그 본문을 받아 그대로 모델에 넘기면 ① 사내 모델이 아무 텍스트나 넣는 챗봇이 되고
        // ② 서버가 무엇을 모델에 보냈는지 보장할 수 없다.
        int start = controller.indexOf("analyze-deploy-log");
        assertThat(start).isPositive();
        String block = controller.substring(start, Math.min(start + 2500, controller.length()));

        // 서버가 직접 조회한다
        assertThat(block).contains("remoteCommandLogService.findRecentByProject");
        // 권한을 다시 검사한다 — 패널을 볼 수 있는 사람만 분석도 할 수 있다
        assertThat(block).contains("ProjectPermissionType.PROJECT_VIEW");
        // 요청 본문에서 로그/프롬프트를 꺼내 쓰지 않는다
        assertThat(block)
            .doesNotContain("body.get(\"logs\")")
            .doesNotContain("body.get(\"stdout\")")
            .doesNotContain("body.get(\"prompt\")")
            .doesNotContain("body.get(\"text\")");
    }
}
