package com.portfolio.deployops.controller;
import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.domain.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
class StopGraceTemplateTest {
 private String render(List<ManagedRuntimeSettings> settings) throws Exception {
  String source=Files.readString(Path.of("src/main/resources/templates/pages/request-detail.html"));
  int start=source.indexOf("<section class=\"section-card\" aria-label=\"승인된 컨테이너 종료 유예\"");
  String fragment=source.substring(start,source.indexOf("</section>",start)+10);
  var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());var context=new Context();
  context.setVariable("request",DeployRequest.builder().project(Project.builder().managedRuntimeSettings(settings).build()).build());
  return engine.process(fragment,context);
 }
 @Test void approvalShowsFrozenGraceAndInheritanceWithoutSecretFingerprint() throws Exception {
  assertThat(render(List.of(new ManagedRuntimeSettings(0L,"MANAGED_REVISION",1L,"not-a-public-fingerprint",false,0,90),new ManagedRuntimeSettings(4L,"INHERIT",null,null,false,0))))
   .contains("배포 단위 #0 · 90초","배포 단위 #4 · 프로젝트 기본 설정 상속","강제 종료될 수 있습니다").doesNotContain("not-a-public-fingerprint");
 }
 @Test void oldSnapshotWithoutBindingsShowsDocumentedDefault() throws Exception {assertThat(render(null)).contains("기본 30초");}
}