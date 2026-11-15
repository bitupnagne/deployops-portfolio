package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NexusMavenGroupHttpTest {
    @Test void createsNewGroupAndRejectsChangedExistingMembershipWithoutPut() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var group=new AtomicReference<String>();
        var creates=new AtomicInteger();
        var writes=new AtomicInteger();
        var mapper=new ObjectMapper();
        server.createContext("/service/rest/v1/repositories",exchange->{
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            String body;
            if(!method.equals("GET"))writes.incrementAndGet();
            if(path.equals("/service/rest/v1/repositories/maven/group") && method.equals("POST")) {
                group.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                creates.incrementAndGet();exchange.sendResponseHeaders(201,-1);exchange.close();return;
            }
            if(path.equals("/service/rest/v1/repositories")) {
                body="[{\"name\":\"project-hosted\",\"format\":\"maven2\",\"type\":\"hosted\"},"
                    +"{\"name\":\"maven-public\",\"format\":\"maven2\",\"type\":\"group\"}"
                    +(group.get()==null?"":",{\"name\":\"deploy-p7-group\",\"format\":\"maven2\",\"type\":\"group\"}")+"]";
            } else if(path.endsWith("/deploy-p7-group")) body=group.get();
            else if(path.endsWith("/project-hosted")) body="{\"online\":true,\"storage\":{\"writePolicy\":\"ALLOW_ONCE\"},\"maven\":{\"versionPolicy\":\"RELEASE\"}}";
            else body="{\"online\":true,\"group\":{\"memberNames\":[\"project-hosted\"]}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        try {
            var settings=mock(SystemSettingService.class);
            when(settings.get("nexus.url","")).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(settings.get("nexus.user","")).thenReturn("");when(settings.get("nexus.pass","")).thenReturn("");
            var service=new NexusMetadataService(mapper,settings);
            service.ensureProjectMavenGroup("deploy-p7-group","project-hosted","maven-public");
            assertThat(mapper.readTree(group.get()).path("group").path("memberNames").toString())
                .isEqualTo("[\"project-hosted\",\"maven-public\"]");
            service.ensureProjectMavenGroup("deploy-p7-group","project-hosted","maven-public");
            assertThat(creates.get()).isEqualTo(1);
            group.set("{\"online\":true,\"group\":{\"memberNames\":[\"other\"]}}");
            assertThatThrownBy(()->service.ensureProjectMavenGroup("deploy-p7-group","project-hosted","maven-public"))
                .hasMessageContaining("구성원이 예상과 다릅니다");
            assertThat(writes.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
}
