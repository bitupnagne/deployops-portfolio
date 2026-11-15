package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitlabCiVariableApiClientTest {
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String url;
    private GitlabService service;
    private SystemSettingService settings;
    private String variableResponse = "[]";
    private int mutationStatus = 201;
    private final String secret = "new-secret-value";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        settings = mock(SystemSettingService.class);
        service = new GitlabService(JsonMapper.builder().build(), settings, mock(SecretCryptoService.class));
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void createsOnlyWildcardProtectedMaskedPasswordWithoutReturningSecret() {
        service.upsertProjectVariable("pat", url, 91L, "NEXUS_REGISTRY_PASSWORD", secret);
        assertThat(requests).containsExactly("GET /api/v4/projects/91/variables?per_page=100&page=1", "POST /api/v4/projects/91/variables");
        Map<?, ?> payload = JsonMapper.builder().build().readValue(bodies.get(1), Map.class);
        assertThat(payload.get("key")).isEqualTo("NEXUS_REGISTRY_PASSWORD");
        assertThat(payload.get("value")).isEqualTo(secret);
        assertThat(payload.get("protected")).isEqualTo(true);
        assertThat(payload.get("masked")).isEqualTo(true);
        assertThat(payload.get("raw")).isEqualTo(true);
        assertThat(payload.get("environment_scope")).isEqualTo("*");
        assertThat(payload.get("variable_type")).isEqualTo("env_var");
    }

    @Test
    void updateSelectsWildcardScopeAndKeepsMavenSettingsAsProtectedNonMaskedFile() {
        variableResponse = """
            [{"key":"MAVEN_SETTINGS_FILE","value":"old","protected":false,"masked":false,
              "variable_type":"env_var","environment_scope":"*"}]
            """;
        mutationStatus = 200;
        service.upsertProjectVariable("pat", url, 91L, "MAVEN_SETTINGS_FILE", "<settings>\n</settings>");
        assertThat(requests.get(1)).isEqualTo("PUT /api/v4/projects/91/variables/MAVEN_SETTINGS_FILE?filter%5Benvironment_scope%5D=%2A");
        Map<?, ?> payload = JsonMapper.builder().build().readValue(bodies.get(1), Map.class);
        assertThat(payload.get("variable_type")).isEqualTo("file");
        assertThat(payload.get("protected")).isEqualTo(true);
        assertThat(payload.get("masked")).isEqualTo(false);
        assertThat(payload.get("raw")).isEqualTo(true);
    }

    @Test
    void otherScopeDoesNotOverwriteOrCountAsWildcardVariable() {
        variableResponse = """
            [{"key":"CI_BUILD_IMAGE","value":"other","protected":true,"masked":false,
              "variable_type":"env_var","environment_scope":"production"}]
            """;
        service.upsertProjectVariable("pat", url, 91L, "CI_BUILD_IMAGE", "build:1");
        assertThat(requests.get(1)).isEqualTo("POST /api/v4/projects/91/variables");
    }

    @Test
    void deleteTargetsWildcardOnlyAndHasNoRequestBody() {
        mutationStatus = 204;
        service.deleteProjectVariable("pat", url, 91L, "CI_BUILD_IMAGE");
        assertThat(requests).containsExactly("DELETE /api/v4/projects/91/variables/CI_BUILD_IMAGE?filter%5Benvironment_scope%5D=%2A");
        assertThat(bodies).containsExactly("");
    }

    @Test
    void upstreamMutationErrorBodyAndCauseAreNeverExposed() {
        mutationStatus = 400;
        assertThatThrownBy(() -> service.upsertProjectVariable("pat", url, 91L, "NEXUS_REGISTRY_PASSWORD", secret))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(secret).hasNoCause();
        assertThatThrownBy(() -> service.deleteProjectVariable("pat", url, 91L, "NEXUS_REGISTRY_PASSWORD"))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(secret).hasNoCause();
    }

    @Test
    void timeoutAfterDispatchReportsUnknownWithoutEchoOrCause() throws Exception {
        GitlabService client = spy(service);
        doThrow(new HttpTimeoutException(secret)).when(client).sendCiVariableMutation(any());
        assertThatThrownBy(() -> client.upsertProjectVariable("pat", url, 91L, "NEXUS_REGISTRY_PASSWORD", secret))
            .isInstanceOfSatisfying(GitlabCiVariableMutationException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue())
            .hasMessageNotContaining(secret).hasNoCause();
        verify(client, times(1)).sendCiVariableMutation(any());
    }

    @Test
    void lostConnectionAfterDeleteDispatchReportsUnknown() throws Exception {
        GitlabService client = spy(service);
        doThrow(new IOException(secret)).when(client).sendCiVariableMutation(any());
        assertThatThrownBy(() -> client.deleteProjectVariable("pat", url, 91L, "CI_BUILD_IMAGE"))
            .isInstanceOfSatisfying(GitlabCiVariableMutationException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue())
            .hasMessageNotContaining(secret).hasNoCause();
        verify(client, times(1)).sendCiVariableMutation(any());
    }

    @Test
    void interruptionAfterDispatchPreservesInterruptAndReportsUnknown() throws Exception {
        GitlabService client = spy(service);
        doThrow(new InterruptedException(secret)).when(client).sendCiVariableMutation(any());
        try {
            assertThatThrownBy(() -> client.deleteProjectVariable("pat", url, 91L, "CI_BUILD_IMAGE"))
                .isInstanceOfSatisfying(GitlabCiVariableMutationException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void explicitRejectionIsKnownFailureButServerErrorRemainsUnknown() {
        mutationStatus = 403;
        assertThatThrownBy(() -> service.deleteProjectVariable("pat", url, 91L, "CI_BUILD_IMAGE"))
            .isInstanceOfSatisfying(GitlabCiVariableMutationException.class, ex -> assertThat(ex.isOutcomeUnknown()).isFalse());
        mutationStatus = 504;
        assertThatThrownBy(() -> service.deleteProjectVariable("pat", url, 91L, "CI_BUILD_IMAGE"))
            .isInstanceOfSatisfying(GitlabCiVariableMutationException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
    }

    @Test
    void failedPreflightHasNoMutationAndIsKnownFailure() {
        variableResponse = "not-json-" + secret;
        assertThatThrownBy(() -> service.upsertProjectVariable("pat", url, 91L, "NEXUS_REGISTRY_PASSWORD", secret))
            .isInstanceOfSatisfying(GitlabCiVariableMutationException.class, ex -> assertThat(ex.isOutcomeUnknown()).isFalse())
            .hasMessageNotContaining(secret).hasNoCause();
        assertThat(requests).containsExactly("GET /api/v4/projects/91/variables?per_page=100&page=1");
    }

    @Test
    void sharedRootSystemTokenIsRejectedBeforeVariableReadOrMutation() {
        when(settings.get("gitlab.token", "")).thenReturn("root-pat");
        assertThatThrownBy(() -> service.upsertProjectVariable("root-pat", url, 91L, "CI_BUILD_IMAGE", "build:1"))
            .isInstanceOf(GitlabAdminSystemTokenException.class).hasMessageNotContaining("root-pat").hasNoCause();
        assertThat(requests).containsExactly("GET /api/v4/user");
        requests.clear();
        assertThatThrownBy(() -> service.deleteProjectVariable("root-pat", url, 91L, "CI_BUILD_IMAGE"))
            .isInstanceOf(GitlabAdminSystemTokenException.class).hasMessageNotContaining("root-pat").hasNoCause();
        assertThat(requests).containsExactly("GET /api/v4/user");
    }

    @Test
    void invalidKeyNeverReachesGitlab() {
        assertThatThrownBy(() -> service.upsertProjectVariable("pat", url, 91L, "UNRESTRICTED", secret))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteProjectVariable("pat", url, 91L, "UNRESTRICTED"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    private void respond(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        requests.add(method + " " + exchange.getRequestURI());
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String response = "GET".equals(method) ? variableResponse : "{\"value\":\"" + secret + "\"}";
        int status = "GET".equals(method) ? 200 : mutationStatus;
        if (exchange.getRequestURI().getPath().equals("/api/v4/user")) response = "{\"id\":1,\"username\":\"root\",\"is_admin\":true}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 204) exchange.sendResponseHeaders(status, -1);
        else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
