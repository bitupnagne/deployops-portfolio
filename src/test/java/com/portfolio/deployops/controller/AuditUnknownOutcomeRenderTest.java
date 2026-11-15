package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.AuditLog;
import com.portfolio.deployops.domain.AuditResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class AuditUnknownOutcomeRenderTest {
    @Test
    void unknownRemoteOutcomeRendersInKoreanWithoutFailureOrSuccessBadge() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext); request.setRequestURI("/deploy/audit");
        var exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(request, new MockHttpServletResponse());
        var row = AuditLog.builder().actor("operator").action("CI_VARIABLE_UPSERT").result(AuditResult.UNKNOWN)
            .createdAt(LocalDateTime.of(2026, 10, 3, 1, 0)).build();
        String html = engine.process("pages/audit", new WebContext(exchange, Locale.KOREAN,
            Map.of("auditLogs", new PageImpl<>(List.of(row)))));
        assertThat(html).contains(">결과 불명</span>").doesNotContain(">실패</span>", ">성공</span>", ">UNKNOWN</span>");
    }
}
