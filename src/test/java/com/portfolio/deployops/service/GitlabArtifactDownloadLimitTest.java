package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GitlabArtifactDownloadLimitTest {
    @Test void defaultContractsRejectOversizeFixedAndChunkedBodiesBeforeParsing() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] oversizedEnv = "x".repeat(16*1024+1).getBytes(StandardCharsets.UTF_8);
        byte[] oversizedJson = "x".repeat(256*1024+1).getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/v4/projects/1/jobs/2/artifacts/deploy-image.env", exchange -> {
            exchange.sendResponseHeaders(200,oversizedEnv.length);
            try (var out=exchange.getResponseBody()) { out.write(oversizedEnv); } catch(java.io.IOException ignored) { }
        });
        server.createContext("/api/v4/projects/1/jobs/2/artifacts/deploy-release.json", exchange -> {
            exchange.sendResponseHeaders(200,0);
            try (var out=exchange.getResponseBody()) {
                for(int offset=0;offset<oversizedJson.length;offset+=4096)
                    out.write(oversizedJson,offset,Math.min(4096,oversizedJson.length-offset));
            } catch(java.io.IOException ignored) { }
        });
        server.start();
        try {
            var service = new GitlabService(mock(tools.jackson.databind.ObjectMapper.class),
                mock(SystemSettingService.class),mock(SecretCryptoService.class));
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            for(String artifact:java.util.List.of("deploy-image.env","deploy-release.json")) {
                assertThatThrownBy(() -> service.downloadJobArtifactFile("qa-token",url,1L,2L,artifact))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("metadata size");
            }
        } finally { server.stop(0); }
    }

    @Test void chunkedMultibyteBodyUsesByteLimitAndExactBoundarySucceeds() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] bytes="한글".getBytes(StandardCharsets.UTF_8);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).GET().build();
            assertThat(client.send(request,new BoundedArtifactBodyHandler(bytes.length)).body()).isEqualTo("한글");
            assertThatThrownBy(()->client.send(request,new BoundedArtifactBodyHandler(bytes.length-1)))
                .isInstanceOf(java.io.IOException.class);
        } finally {server.stop(0);}
    }

    @Test void headersFollowedByStalledBodyHaveIndependentBoundedDeadline() throws Exception {
        var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()){
                out.write('{');out.flush();
                try {release.await(3,TimeUnit.SECONDS);} catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } catch(java.io.IOException ignored) { }
        });
        server.start();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()))
                .timeout(Duration.ofSeconds(5)).GET().build();
            long started=System.nanoTime();
            assertThatThrownBy(()->client.send(request,new BoundedArtifactBodyHandler(1024,Duration.ofMillis(150))))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("timed out");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
        } finally {release.countDown();server.stop(0);}
    }
}
