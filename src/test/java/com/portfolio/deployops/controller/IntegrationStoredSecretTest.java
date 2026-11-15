package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.service.SystemSettingService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntegrationStoredSecretTest {

    @Test
    void nexusConnectionCheckReportsRegistryEulaFailureAfterManagementApiSucceeds() throws IOException {
        HttpServer nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/service/rest/v1/repositories", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        nexus.createContext("/v2/", exchange -> {
            exchange.getResponseHeaders().add("Docker-Distribution-API-Version", "registry/2.0");
            byte[] response = "Nexus Repository Manager End User License Agreement must be accepted"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        nexus.start();
        try {
            String url = "http://127.0.0.1:" + nexus.getAddress().getPort();
            IntegrationApiController controller = new IntegrationApiController(mock(SystemSettingService.class));

            var result = controller.testNexus(Map.of(
                "url", url,
                "user", "qa-nexus",
                "pass", "qa-password",
                "registry", "127.0.0.1:" + nexus.getAddress().getPort()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("REGISTRY_EULA");
            assertThat(result.getMessage()).contains("관리 API는 연결됐지만 Docker Registry가 사용 조건 미동의로 차단");
        } finally {
            nexus.stop(0);
        }
    }

    @Test
    void nexusConnectionCheckUsesInternalRegistryAddressWhenConfigured() throws IOException {
        AtomicReference<String> registryPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/service/rest/v1/repositories", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        nexus.createContext("/v2/", exchange -> {
            registryPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Docker-Distribution-API-Version", "registry/2.0");
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"http://nexus:8081/token\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        nexus.start();
        try {
            String baseUrl = "http://127.0.0.1:" + nexus.getAddress().getPort();
            IntegrationApiController controller = new IntegrationApiController(mock(SystemSettingService.class));

            var result = controller.testNexus(Map.of(
                "url", baseUrl,
                "user", "qa-nexus",
                "pass", "qa-password",
                "registry", "host.docker.internal:15000",
                "registryApiUrl", "127.0.0.1:" + nexus.getAddress().getPort()));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().toString()).contains("Docker Registry v2");
            assertThat(registryPath.get()).isEqualTo("/v2/");
            assertThat(authorization.get()).startsWith("Basic ");
        } finally {
            nexus.stop(0);
        }
    }

    @Test
    void blankPatIsUsedOnlyForTheConfiguredGitlabUrl() throws IOException {
        AtomicReference<String> configuredHeader = new AtomicReference<>();
        AtomicReference<String> configuredPath = new AtomicReference<>();
        AtomicReference<String> otherHeader = new AtomicReference<>();
        AtomicReference<String> otherPath = new AtomicReference<>();
        HttpServer configured = server(configuredHeader, configuredPath);
        HttpServer other = server(otherHeader, otherPath);
        try {
            String configuredUrl = "http://127.0.0.1:" + configured.getAddress().getPort();
            String otherUrl = "http://127.0.0.1:" + other.getAddress().getPort();
            SystemSettingService settings = mock(SystemSettingService.class);
            when(settings.get("gitlab.url", "")).thenReturn(configuredUrl);
            when(settings.get("gitlab.token", "")).thenReturn("test-stored-secret");
            IntegrationApiController controller = new IntegrationApiController(settings);

            assertThat(controller.testGitlab(Map.of("url", configuredUrl, "token", "")).isSuccess()).isTrue();
            assertThat(configuredHeader.get()).isEqualTo("test-stored-secret");
            assertThat(configuredPath.get()).isEqualTo("/api/v4/user");

            var otherUrlResult = controller.testGitlab(Map.of("url", otherUrl, "token", ""));
            assertThat(otherUrlResult.isSuccess()).isFalse();
            assertThat(otherUrlResult.getErrorCode()).isEqualTo("TOKEN_REQUIRED");
            assertThat(otherHeader.get()).isNull();
            assertThat(otherPath.get()).isNull();
        } finally {
            configured.stop(0);
            other.stop(0);
        }
    }

    @Test
    void publicVersionSuccessCannotMaskAnInvalidGitlabToken() throws IOException {
        AtomicReference<String> tokenHeader = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer gitlab = server(tokenHeader, requestPath);
        try {
            String url = "http://127.0.0.1:" + gitlab.getAddress().getPort();
            IntegrationApiController controller = new IntegrationApiController(mock(SystemSettingService.class));

            var result = controller.testGitlab(Map.of("url", url, "token", "invalid-token"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("AUTH_FAIL");
            assertThat(requestPath.get()).isEqualTo("/api/v4/user");
            assertThat(tokenHeader.get()).isEqualTo("invalid-token");
        } finally {
            gitlab.stop(0);
        }
    }

    @Test
    void nexusConnectionCheckUsesAnAuthenticatedApiInsteadOfPublicStatus() throws IOException {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/service/rest/v1/repositories", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorization.set(header);
            int status = ("Basic " + Base64.getEncoder().encodeToString(
                "qa-nexus:qa-password".getBytes(StandardCharsets.UTF_8))).equals(header) ? 200 : 401;
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        nexus.start();
        try {
            String url = "http://127.0.0.1:" + nexus.getAddress().getPort();
            IntegrationApiController controller = new IntegrationApiController(mock(SystemSettingService.class));

            var unauthenticated = controller.testNexus(Map.of("url", url, "user", "", "pass", ""));
            assertThat(unauthenticated.isSuccess()).isFalse();
            assertThat(unauthenticated.getErrorCode()).isEqualTo("AUTH_FAIL");
            assertThat(requestPath.get()).isEqualTo("/service/rest/v1/repositories");
            assertThat(authorization.get()).isNull();

            var authenticated = controller.testNexus(Map.of(
                "url", url, "user", "qa-nexus", "pass", "qa-password"));
            assertThat(authenticated.isSuccess()).isTrue();
            assertThat(authorization.get()).startsWith("Basic ");
        } finally {
            nexus.stop(0);
        }
    }

    @Test
    void nexusConnectionCheckExplainsRepositoryPermissionDenial() throws IOException {
        HttpServer nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/service/rest/v1/repositories", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        nexus.start();
        try {
            String url = "http://127.0.0.1:" + nexus.getAddress().getPort();
            IntegrationApiController controller = new IntegrationApiController(mock(SystemSettingService.class));

            var result = controller.testNexus(Map.of("url", url, "user", "qa-nexus", "pass", "qa-password"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("PERMISSION_DENIED");
            assertThat(result.getMessage()).contains("browse/read");
        } finally {
            nexus.stop(0);
        }
    }

    private static HttpServer server(AtomicReference<String> tokenHeader, AtomicReference<String> requestPath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/user", exchange -> {
            tokenHeader.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            requestPath.set(exchange.getRequestURI().getPath());
            int status = "test-stored-secret".equals(tokenHeader.get()) ? 200 : 401;
            byte[] response = (status == 200 ? "{\"id\":2,\"username\":\"deployops-bot\"}" : "{\"message\":\"401 Unauthorized\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.createContext("/api/v4/version", exchange -> {
            byte[] response = "{\"version\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        return server;
    }
}
