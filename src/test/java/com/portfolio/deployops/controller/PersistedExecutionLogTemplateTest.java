package com.portfolio.deployops.controller;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;
class PersistedExecutionLogTemplateTest {
 String render(boolean permission,String status)throws Exception{
  String template=Files.readString(Path.of("src/main/resources/templates/pages/request-detail.html"));
  int start=template.indexOf("    <section id=\"persisted-execution-log-panel\"");
  int end=template.indexOf("    </section>",start)+"    </section>".length();
  assertThat(start).isGreaterThan(0);
  SpringTemplateEngine engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());
  Context context=new Context();context.setVariable("canViewExecutionLogs",permission);context.setVariable("request",Map.of("id",9,"status",status));
  return engine.process(template.substring(start,end),context);
 }
 @Test void failedTerminalRequestStillShowsSavedLogPanelAfterReload()throws Exception{String result=render(true,"TEST_FAILED");assertThat(result).contains("data-request-id=\"9\"","저장된 실행 로그","persisted-execution-log-text");}
 @Test void prodFailedAndCompletedHaveReadOnlyStoredLogControls()throws Exception{assertThat(render(true,"PROD_FAILED")).contains("저장된 로그 새로고침");assertThat(render(true,"COMPLETED")).contains("저장된 실행 로그").doesNotContain("execute/");}
 @Test void viewerWithoutExecutionLogPermissionHasNoPanel()throws Exception{assertThat(render(false,"TEST_FAILED")).doesNotContain("persisted-execution-log-panel","저장된 로그 새로고침");}
}