package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class RequestExecutionHeadingTemplateTest {
    enum Environment { TEST, PROD }

    String render(Environment environment, Long sourceMrIid, boolean rollback, boolean live) throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pages/request-detail.html"));
        int panel = template.indexOf("id=\"exec-panel\"");
        int start = template.indexOf("<p class=\"section-title\"", panel);
        int end = template.indexOf("</p>", start) + "</p>".length();
        assertThat(start).isGreaterThan(panel);
        Map<String, Object> request = new HashMap<>();
        request.put("environment", environment);
        request.put("sourceMrIid", sourceMrIid);
        Context context = new Context();
        context.setVariable("request", request);
        context.setVariable("isRollback", rollback);
        context.setVariable("execLive", live);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        return engine.process(template.substring(start, end), context);
    }

    @Test void testExistingImageDoesNotClaimToBuild() throws Exception {
        assertThat(render(Environment.TEST, null, false, false)).contains("TEST 이미지 배포 실행").doesNotContain("빌드");
    }
    @Test void testMergeRequestSourceDescribesBuild() throws Exception {
        assertThat(render(Environment.TEST, 1L, false, false)).contains("TEST 빌드 + 배포 실행");
    }
    @Test void prodHeadingDoesNotDescribeTestBuild() throws Exception {
        assertThat(render(Environment.PROD, 1L, false, false)).contains("PROD 배포 실행").doesNotContain("빌드");
    }
    @Test void rollbackTakesPrecedenceOverBuildSource() throws Exception {
        assertThat(render(Environment.TEST, 1L, true, false)).contains("TEST 롤백 실행").doesNotContain("빌드");
        assertThat(render(Environment.PROD, null, true, false)).contains("PROD 롤백 실행");
    }
    @Test void liveExecutionRemainsLogHeading() throws Exception {
        assertThat(render(Environment.TEST, null, false, true)).contains("배포 진행 로그").doesNotContain("이미지 배포 실행");
    }
}
