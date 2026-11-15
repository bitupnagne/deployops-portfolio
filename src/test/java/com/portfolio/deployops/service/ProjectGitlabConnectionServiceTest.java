package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProjectGitlabConnectionServiceTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final GitlabService gitlab = mock(GitlabService.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private ProjectGitlabConnectionService service;

    @BeforeEach
    void startGitlabStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/7", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            byte[] body = ("""
                {"id":7,"path_with_namespace":"root/lab-static-service","default_branch":"main",
                "http_url_to_repo":"http://127.0.0.1:%d/root/lab-static-service.git",
                "ssh_url_to_repo":"git@gitlab-internal:root/lab-static-service.git"}
                """.formatted(server.getAddress().getPort())).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        when(gitlab.getSystemGitlabUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(settings.get("gitlab.token", "")).thenReturn("stored-bot-token");
        when(settings.get("gitlab.url", "")).thenReturn("https://gitlab.example.test");
        service = new ProjectGitlabConnectionService(gitlab, settings, new ObjectMapper());
    }

    @AfterEach
    void stopGitlabStub() {
        server.stop(0);
    }

    @Test
    void testsExactProjectIdWithServerSideBotAndConfiguredExternalCloneHost() {
        var result = service.test(7, "https://gitlab.example.test/root/lab-static-service.git");

        assertThat(result.projectId()).isEqualTo(7);
        assertThat(result.pathWithNamespace()).isEqualTo("root/lab-static-service");
        assertThat(result.defaultBranch()).isEqualTo("main");
        assertThat(result.repositoryUrlVerified()).isTrue();
        assertThat(receivedToken.get()).isEqualTo("stored-bot-token");
    }

    @Test
    void acceptsGitlabAdvertisedInternalHttpAndSshCloneUrlsForLab() {
        var http = service.test(7, "http://127.0.0.1:" + server.getAddress().getPort()
            + "/root/lab-static-service.git");
        var ssh = service.test(7, "git@gitlab-internal:root/lab-static-service.git");

        assertThat(http.repositoryUrlVerified()).isTrue();
        assertThat(ssh.repositoryUrlVerified()).isTrue();
    }

    @Test
    void rejectsCloneUrlThatPointsToDifferentRepository() {
        assertThatThrownBy(() -> service.test(7, "https://gitlab.example.test/root/other.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    void rejectsUnrelatedHostWithIdenticalRepositoryPath() {
        assertThatThrownBy(() -> service.test(7, "https://unrelated.example/root/lab-static-service.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    void rejectsWrongPortEvenOnConfiguredGitlabHost() {
        assertThatThrownBy(() -> service.test(7, "https://gitlab.example.test:9443/root/lab-static-service.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    void rejectsSshLookalikeThatGitlabDidNotAdvertise() {
        assertThatThrownBy(() -> service.test(7, "git@unrelated:root/lab-static-service.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    void handlesInaccessibleProjectWithoutReturningGitlabResponseBodyOrToken() {
        status.set(404);
        assertThatThrownBy(() -> service.test(7, "https://gitlab.example.test/root/lab-static-service.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("접근할 수 없거나");
    }

    @Test
    void failsClosedOnRedirectInsteadOfReportingHostAsProjectConnection() {
        status.set(302);
        assertThatThrownBy(() -> service.test(7, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTP 302");
    }

    @Test
    void rejectsCredentialsEmbeddedInCloneUrl() {
        assertThatThrownBy(() -> service.test(7, "https://pat@example.test/root/lab-static-service.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    void reportsOnlyProjectAccessWhenRepositoryUrlIsAbsent() {
        assertThat(service.test(7, null).repositoryUrlVerified()).isFalse();
    }
}
