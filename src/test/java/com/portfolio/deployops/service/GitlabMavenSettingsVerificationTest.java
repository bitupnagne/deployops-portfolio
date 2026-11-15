package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitlabMavenSettingsVerificationTest {
    @Test void comparesWildcardProtectedRawFileValueWithoutExposingIt() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var mapper=new ObjectMapper();
        var actual=new AtomicReference<>("<settings>expected</settings>");
        var scope=new AtomicReference<>("*");
        var requestPath=new AtomicReference<String>();
        server.createContext("/",exchange->{
            requestPath.set(exchange.getRequestURI().toString());
            byte[] body=mapper.writeValueAsString(Map.of("key","MAVEN_SETTINGS_FILE","variable_type","file",
                "protected",true,"raw",true,"environment_scope",scope.get(),"value",actual.get()))
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var settings=mock(SystemSettingService.class);
            when(settings.get("gitlab.api-url","")).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            var service=new GitlabService(mapper,settings,mock(SecretCryptoService.class));
            assertThat(service.matchesMavenSettingsFile("server-side-token",91L,"<settings>expected</settings>")).isTrue();
            assertThat(requestPath.get()).isEqualTo("/api/v4/projects/91/variables/MAVEN_SETTINGS_FILE?filter%5Benvironment_scope%5D=%2A");
            actual.set("<settings>different</settings>");
            assertThat(service.matchesMavenSettingsFile("server-side-token",91L,"<settings>expected</settings>")).isFalse();
            actual.set("<settings>expected</settings>");scope.set("production");
            assertThat(service.matchesMavenSettingsFile("server-side-token",91L,"<settings>expected</settings>")).isFalse();
        } finally {server.stop(0);}
    }
}
