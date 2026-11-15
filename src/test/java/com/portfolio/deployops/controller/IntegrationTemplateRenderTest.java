package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class IntegrationTemplateRenderTest {

    @Test
    void integrationPageRendersOfficeAndNexusControls() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        request.setRequestURI("/deploy/integration");
        var exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(request, new MockHttpServletResponse());

        String html = engine.process("pages/integration",
            new WebContext(exchange, Locale.KOREAN, Map.of("projects", List.of())));

        assertThat(html).contains("Office System URL", "Office 연결 테스트", "이미지 정리 승인",
            "DeployOps 내부 Registry 주소 (선택)", "nexus-registry-api-url");
        assertThat(html).contains("Office 알림 전송 기록", "id=\"office-notification-outbox\"",
            "data-outbox=\"count-DEAD_LETTER\"", "data-outbox=\"rows\"", "aria-live=\"polite\"",
            "<option value=\"DEAD_LETTER\" selected>실패</option>", "알림 본문과 오류 원문은 표시하지 않습니다.",
            "요청 접수와 실제 전송 상태는 구분됩니다.");
    }
}
