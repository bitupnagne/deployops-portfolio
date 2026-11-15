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
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class AuditTableLayoutTemplateTest {
    private String render(List<AuditLog> logs) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var sc = new MockServletContext(); var request = new MockHttpServletRequest(sc);
        request.setRequestURI("/deploy/audit");
        var exchange = JakartaServletWebApplication.buildApplication(sc).buildExchange(request, new MockHttpServletResponse());
        return engine.process("pages/audit", new WebContext(exchange, Locale.KOREAN,
            Map.of("auditLogs", new PageImpl<>(logs, PageRequest.of(0, 20), logs.isEmpty() ? 0 : 40), "filterActor", "", "filterFrom", "", "filterTo", "")));
    }
    @Test void longTargetRemainsEscapedAndExportableInScrollableTable() throws Exception {
        String target = "TLS 정책 devweb1 qa-demo-app localhost:443 upstream=198.18.18.11:9090 certificate=qa-localhost <img src=x onerror=alert(1)>";
        var log = AuditLog.builder().actor("qa-admin").action("NGINX_TLS_POLICY_APPLY").targetType("NginxChangeRequest")
            .targetId("12").targetDesc(target).result(AuditResult.SUCCESS).ipAddress("198.18.7.10")
            .createdAt(LocalDateTime.of(2026,10,8,12,0)).build();
        String html=render(List.of(log));
        assertThat(html).contains("role=\"region\"", "tabindex=\"0\"", "가로로 스크롤", "min-width:1280px;table-layout:fixed", "width:360px", "word-break:normal;overflow-wrap:anywhere", "audit-log-table");
        var columns = java.util.regex.Pattern.compile("<col[^>]*style=\"width:(\\d+)px\"[^>]*>").matcher(html);
        var widths = new java.util.ArrayList<Integer>();
        while (columns.find()) widths.add(Integer.parseInt(columns.group(1)));
        assertThat(widths).containsExactly(140, 120, 220, 220, 360, 90, 130);
        assertThat(html).contains("NginxChangeRequest");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;", "NGINX_TLS_POLICY_APPLY", "id=\"audit-table\"", "data-row=\"1\"");
        assertThat(html).contains("onclick=\"navigateAuditPage(1)\"");
        assertThat(html).doesNotContain("<img src=x", "text-overflow:ellipsis", "word-break:break-all");
        java.nio.file.Path path=java.nio.file.Path.of(".work/artifacts/audit-layout-author-fixture.html");
        java.nio.file.Files.createDirectories(path.getParent());
        String stylesheet = java.nio.file.Path.of("src/main/resources/static/css/styles.css").toAbsolutePath().toUri().toString();
        java.nio.file.Files.writeString(path,"<html><head><link rel=\"stylesheet\" href=\""+stylesheet+"\"></head><body><main style=\"width:680px;margin:20px\">"+html+"</main></body></html>");
    }
    @Test void emptyStateAndSevenColumnsRemain() {
        String html=render(List.of());
        assertThat(html).contains("조건에 맞는 기록이 없습니다.", "colspan=\"7\"", "현재 페이지 CSV");
        assertThat(html.split("scope=\"col\"",-1)).hasSize(8);
    }
}
