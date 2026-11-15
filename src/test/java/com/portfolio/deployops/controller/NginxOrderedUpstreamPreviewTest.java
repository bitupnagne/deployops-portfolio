package com.portfolio.deployops.controller;
import static org.assertj.core.api.Assertions.*;
import com.portfolio.deployops.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
class NginxOrderedUpstreamPreviewTest {
 @Test void realTemplateDisplaysEveryStoredEndpointInOrderEscapedAndLegacyFallback() throws Exception {
  var source=Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));
  var start=source.indexOf("th:each=\"endpoint :");var from=source.lastIndexOf("<div",start);var end=source.indexOf("</div>",start)+6;var fragment=source.substring(from,end);
  var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());var context=new Context();
  var q=NginxChangeRequest.builder().upstreamSnapshots(List.of(new NginxUpstream("PROXY",0,2L,"first",9090),new NginxUpstream("PROXY",1,3L,"<second>",9091),new NginxUpstream("PROXY",2,4L,"third",9092))).build();context.setVariable("change",q);
  var rendered=engine.process(fragment,context);assertThat(rendered).contains("WAS 1: first:9090","WAS 2: &lt;second&gt;:9091","WAS 3: third:9092").doesNotContain("<second>");assertThat(rendered.indexOf("first")).isLessThan(rendered.indexOf("third"));
  q.setUpstreamSnapshots(List.of());q.setUpstreamHost("legacy1");q.setUpstreamPort(80);q.setSecondaryUpstreamHost("legacy2");q.setSecondaryUpstreamPort(81);assertThat(engine.process(fragment,context)).contains("legacy1:80","legacy2:81");
  q.setRestoreAbsent(true);assertThat(engine.process(fragment,context)).isEmpty();
 }
}
