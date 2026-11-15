package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitlabSourceFileReadTest {
    private HttpServer server;
    private GitlabService service;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> bytes = new AtomicReference<>("FROM internal/runtime".getBytes(StandardCharsets.UTF_8));
    private final AtomicReference<String> request = new AtomicReference<>();
    private String base;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString());
            var response = bytes.get(); exchange.sendResponseHeaders(status.get(), response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new GitlabService(JsonMapper.builder().build(), mock(SystemSettingService.class), mock(SecretCryptoService.class));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void exactCommitAndConfiguredPathAreEncodedInGetOnly() throws Exception {
        assertThat(service.readRepositoryFileBounded("test-token", base, 8, "api/Dockerfile", "a".repeat(40), 200))
            .isEqualTo("FROM internal/runtime");
        assertThat(request.get()).isEqualTo("GET /api/v4/projects/8/repository/files/api%2FDockerfile/raw?ref=" + "a".repeat(40));
    }
    @Test void genuine404OnlyMeansMissing() throws Exception {
        status.set(404);
        assertThat(service.readRepositoryFileBounded("test-token", base, 8, "Dockerfile", "main", 200)).isNull();
        for (int code : new int[]{401, 403, 429, 500}) {
            status.set(code);
            assertThatThrownBy(() -> service.readRepositoryFileBounded("test-token", base, 8, "Dockerfile", "main", 200))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + code);
        }
    }
    @Test void oversizedAndMalformedUtf8AreRejectedInsteadOfTruncatingForEditing() {
        bytes.set(new byte[201]);
        assertThatThrownBy(() -> service.readRepositoryFileBounded("test-token", base, 8, "Dockerfile", "main", 200))
            .isInstanceOf(IllegalArgumentException.class);
        bytes.set(new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThatThrownBy(() -> service.readRepositoryFileBounded("test-token", base, 8, "Dockerfile", "main", 200))
            .isInstanceOf(java.nio.charset.CharacterCodingException.class);
    }
    @Test void invalidReadBoundCannotReachNetwork() {
        assertThatThrownBy(() -> service.readRepositoryFileBounded("test-token", base, 8, "Dockerfile", "main", 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(request.get()).isNull();
    }
}
