package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class CiFileCapabilityTemplateTest {
    @Test
    void projectViewerCanReadFileButCannotEditOrProposeMergeRequest() {
        assertThat(render(Map.of("canReviewMergeRequests", false)))
            .contains("readonly=\"readonly\"", "loadCiFile(true)", "MR 검토 권한이 필요합니다")
            .doesNotContain("id=\"ci-save\"", "showCiTemplate()");
    }

    @Test
    void reviewerCanEditAndProposeWhileReadActionsRemainAvailable() {
        String html = render(Map.of("canReviewMergeRequests", true));
        assertThat(html).contains("id=\"ci-save\"", "showCiTemplate()", "loadCiFile(true)")
            .doesNotContain("MR 검토 권한이 필요합니다");
        assertThat(html).containsPattern("(?s)<textarea[^>]*id=\"ci-editor\"(?![^>]*readonly)[^>]*>");
    }

    @Test
    void missingCapabilityDefaultsToReadOnly() {
        assertThat(render(Map.of())).contains("readonly=\"readonly\"")
            .doesNotContain("id=\"ci-save\"", "showCiTemplate()");
    }

    private String render(Map<String, Object> variables) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        return engine.process("pages/project-detail", Set.of("#ci-file-panel"), new Context(Locale.KOREAN, variables));
    }
}
