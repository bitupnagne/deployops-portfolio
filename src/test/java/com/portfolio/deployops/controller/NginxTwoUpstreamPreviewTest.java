package com.portfolio.deployops.controller;
import static org.assertj.core.api.Assertions.*;
import com.portfolio.deployops.domain.NginxChangeRequest;
import java.nio.file.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
class NginxTwoUpstreamPreviewTest {
 @Test void approverSeesStoredSecondaryAndSettingsEscapedAndSinglePolicyHidesIt() throws Exception {
  var text=Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));var matcher=Pattern.compile("<div class=\"form-hint mono\" th:if=\"\\$\\{change.hasSecondaryUpstream\\(\\)\\}\"[^>]*>두 번째 WAS</div>").matcher(text);assertThat(matcher.find()).isTrue();
  var change=NginxChangeRequest.builder().secondaryUpstreamHost("<old-host>").secondaryUpstreamPort(18080).upstreamMaxFails(2).upstreamFailTimeoutSec(20).proxyConnectTimeoutSec(4).build();var context=new Context();context.setVariable("change",change);var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());var rendered=engine.process(matcher.group(),context);assertThat(rendered).contains("&lt;old-host&gt;:18080","4초","2회/20초").doesNotContain("<old-host>");change.setSecondaryUpstreamHost(null);assertThat(engine.process(matcher.group(),context)).isEmpty();
 }
}
