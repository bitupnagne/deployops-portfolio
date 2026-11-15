package com.portfolio.deployops.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NexusDependencyHttpTest {
    @TempDir Path directory;
    @Test void standardMavenChecksumsAreReadOnlyUntilExplicitlyPublishedAndNeverOverwrite() throws Exception {
        var assets=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        var puts=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
        String path="/repository/maven-hosted/com/acme/lib/1/lib-1.jar";
        byte[] payload=new byte[]{1,2,3,4,5};
        assets.put(path,payload);
        var lostReply=new java.util.concurrent.atomic.AtomicBoolean(true);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/repository/",exchange -> {
            String target=exchange.getRequestURI().getPath();
            try {
                if("PUT".equals(exchange.getRequestMethod())) {
                    puts.merge(target,1,Integer::sum);
                    byte[] bytes=exchange.getRequestBody().readAllBytes();
                    if(assets.putIfAbsent(target,bytes)!=null) exchange.sendResponseHeaders(400,-1);
                    else exchange.sendResponseHeaders(target.endsWith(".sha1") && lostReply.compareAndSet(true,false)?500:201,-1);
                } else {
                    byte[] bytes=assets.get(target);
                    if(bytes==null)exchange.sendResponseHeaders(404,-1);
                    else {exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}
                }
            } finally {exchange.close();}
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var settings=mock(SystemSettingService.class);
            when(settings.get("nexus.url","")).thenReturn(base);
            when(settings.get("nexus.user","")).thenReturn("");
            when(settings.get("nexus.pass","")).thenReturn("");
            when(settings.get("nexus.transfer.idle-seconds","120")).thenReturn("120");
            var service=new NexusMetadataService(new ObjectMapper(),settings);
            String relative="com/acme/lib/1/lib-1.jar";
            var hashes=service.dependencyChecksums(base,"maven-hosted",relative,()->{});
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload)),hashes.get("sha256"));
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(payload)),hashes.get("sha1"));
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(payload)),hashes.get("md5"));
            assertFalse(service.dependencyChecksumFiles(base,"maven-hosted",relative,hashes,false,()->{}));
            assertTrue(puts.isEmpty());
            // Nexus accepted SHA-1 but the response failed: verification cannot silently write.
            assertThrows(IllegalStateException.class,()->service.dependencyChecksumFiles(base,"maven-hosted",relative,hashes,true,()->{}));
            assertFalse(service.dependencyChecksumFiles(base,"maven-hosted",relative,hashes,false,()->{}));
            assertEquals(1,puts.get(path+".sha1"));
            assertTrue(service.dependencyChecksumFiles(base,"maven-hosted",relative,hashes,true,()->{}));
            assertTrue(service.dependencyChecksumFiles(base,"maven-hosted",relative,hashes,false,()->{}));
            assertEquals(1,puts.get(path+".sha1"));
            assertEquals(1,puts.get(path+".md5"));
            assertEquals(1,puts.get(path+".sha256"));
            assertFalse(puts.containsKey(path));
            // A pre-existing conflict is detected before any missing sibling is written.
            assets.put(path+".sha1","0".repeat(40).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assets.remove(path+".md5");
            assertThrows(IllegalStateException.class,()->service.dependencyChecksumFiles(base,"maven-hosted",relative,hashes,true,()->{}));
            assertEquals(1,puts.get(path+".md5"));
        } finally {server.stop(0);}
    }
    @Test void dependencyBindingRequiresImmutableReleaseOrRawRepository() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var policy=new java.util.concurrent.atomic.AtomicReference<>("ALLOW_ONCE");
        var versions=new java.util.concurrent.atomic.AtomicReference<>("RELEASE");
        server.createContext("/service/rest/v1/repositories",exchange -> {
            String path=exchange.getRequestURI().getPath();
            String json=path.endsWith("/repositories")
                ? "[{\"name\":\"raw-hosted\",\"format\":\"raw\",\"type\":\"hosted\"},{\"name\":\"maven-hosted\",\"format\":\"maven2\",\"type\":\"hosted\"}]"
                : "{\"storage\":{\"writePolicy\":\""+policy.get()+"\"},\"maven\":{\"versionPolicy\":\""+versions.get()+"\"}}";
            byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            var settings=mock(SystemSettingService.class);
            when(settings.get("nexus.url","")).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(settings.get("nexus.user","")).thenReturn("");
            when(settings.get("nexus.pass","")).thenReturn("");
            var service=new NexusMetadataService(new ObjectMapper(),settings);
            assertDoesNotThrow(()->service.assertDependencyRepository("raw-hosted","raw"));
            assertDoesNotThrow(()->service.assertDependencyRepository("maven-hosted","maven2"));
            policy.set("ALLOW");
            assertThrows(IllegalArgumentException.class,()->service.assertDependencyRepository("raw-hosted","raw"));
            policy.set("ALLOW_ONCE");
            versions.set("SNAPSHOT");
            assertThrows(IllegalArgumentException.class,()->service.assertDependencyRepository("maven-hosted","maven2"));
            versions.set("MIXED");
            assertThrows(IllegalArgumentException.class,()->service.assertDependencyRepository("maven-hosted","maven2"));
        } finally {server.stop(0);}
    }
    @Test void filePublisherStreamsToRepositoryAndRemoteBytesAreVerified() throws Exception {
        Path source=directory.resolve("source.bin"),stored=directory.resolve("stored.bin");
        try(var output=Files.newOutputStream(source)){
            byte[] block=new byte[64*1024];
            for(int i=0;i<32;i++){java.util.Arrays.fill(block,(byte)i);output.write(block);}
        }
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicLong sent=new AtomicLong();
        server.createContext("/repository/raw-hosted/a+b.bin", exchange -> {
            try {
                assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Basic "));
                switch(exchange.getRequestMethod()){
                    case "PUT" -> {try(var in=exchange.getRequestBody()){Files.copy(in,stored);}exchange.sendResponseHeaders(201,-1);}
                    case "HEAD" -> exchange.sendResponseHeaders(Files.exists(stored)?200:404,-1);
                    case "GET" -> {exchange.sendResponseHeaders(200,Files.size(stored));try(var out=exchange.getResponseBody()){Files.copy(stored,out);}}
                    default -> exchange.sendResponseHeaders(405,-1);
                }
            } finally {exchange.close();}
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var settings=mock(SystemSettingService.class);
            when(settings.get("nexus.url","")).thenReturn(base);
            when(settings.get("nexus.user","")).thenReturn("test-user");
            when(settings.get("nexus.pass","")).thenReturn("local-test-only");
            when(settings.get("nexus.transfer.idle-seconds","120")).thenReturn("120");
            var service=new NexusMetadataService(new ObjectMapper(),settings);
            assertFalse(service.dependencyExists(base,"raw-hosted","a+b.bin"));
            service.publishDependency(base,"raw-hosted","a+b.bin",source,sent::set);
            assertEquals(Files.size(source),sent.get());
            assertTrue(service.dependencyExists(base,"raw-hosted","a+b.bin"));
            assertEquals(NexusDependencyUploadService.hash(source,()->{}),
                service.dependencyDigest(base,"raw-hosted","a+b.bin",()->{}));
            assertThrows(IllegalStateException.class,()->service.dependencyExists("http://other","raw-hosted","a+b.bin"));
        } finally {server.stop(0);}
    }
}
