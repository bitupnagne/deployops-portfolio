package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.NginxChangeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class NginxStaticApiPreviewTest {
    @Test
    void staticApiPairShowsBothStoredEndpointsWithoutHtmlExecution() throws Exception {
        String template=Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));
        var api=Pattern.compile("<div class=\"form-hint mono ng-api-preview\"[^>]*>API 경로</div>").matcher(template);
        var pair=Pattern.compile("<div class=\"form-hint mono\" th:if=\"\\$\\{change.hasSecondaryUpstream\\(\\)\\}\"[^>]*>두 번째 WAS</div>").matcher(template);
        assertThat(api.find()).isTrue();assertThat(pair.find()).isTrue();
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());
        for(var type:java.util.List.of(com.portfolio.deployops.domain.NginxPolicyType.STATIC_SPA,com.portfolio.deployops.domain.NginxPolicyType.STATIC_FILES)) {
            var change=NginxChangeRequest.builder().policyType(type).apiProxyEnabled(true).apiPrefix("/api/").apiUpstreamHost("<primary>").apiUpstreamPort(18082).secondaryUpstreamHost("<secondary>").secondaryUpstreamPort(18082).proxyConnectTimeoutSec(3).upstreamMaxFails(1).upstreamFailTimeoutSec(10).build();
            var context=new Context();context.setVariable("change",change);
            assertThat(engine.process(api.group()+pair.group(),context)).contains("API /api/","&lt;primary&gt;:18082","&lt;secondary&gt;:18082","3초").doesNotContain("<primary>","<secondary>");
        }
    }

    @Test
    void approverPreviewUsesStoredRouteAndEscapesUntrustedHistoricalText() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));
        var matcher = Pattern.compile("<div class=\"form-hint mono ng-api-preview\"[^>]*>API 경로</div>").matcher(template);
        assertThat(matcher.find()).isTrue();
        var change = NginxChangeRequest.builder().apiProxyEnabled(true).apiPrefix("/api/")
            .apiUpstreamHost("<script>old</script>").apiUpstreamPort(9090).build();
        Context context = new Context(); context.setVariable("change", change);
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(new StringTemplateResolver());
        String rendered = engine.process(matcher.group(), context);
        assertThat(rendered).contains("API /api/", "9090", "경로 유지", "&lt;script&gt;old&lt;/script&gt;").doesNotContain("<script>");
        change.setApiProxyEnabled(false);
        assertThat(engine.process(matcher.group(), context)).isEmpty();
    }
}
