package com.portfolio.deployops.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.NginxChangeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class NginxFailurePresentationTest {
    private static final String WARNING = "bash: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)";

    @Test
    void mixedKnownWarningLinesLeaveActualCauseAndPersistedRawMessageIntact() {
        String raw = WARNING + "\r\n/bin/sh: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)\r\n인증서와 개인키가 일치하지 않습니다.";
        NginxChangeRequest change = NginxChangeRequest.builder().id(57L).errorMessage(raw).build();
        var display = NginxFailurePresentation.displayErrors(List.of(change));
        assertThat(display.get(57L)).isEqualTo("인증서와 개인키가 일치하지 않습니다.");
        assertThat(change.getErrorMessage()).isEqualTo(raw);
    }

    @Test
    void unknownWarningsAndEmbeddedLocaleTextArePreserved() {
        String raw = "warning: certificate issuer unknown\n" + WARNING + " 실제 오류도 같은 줄에 있습니다.\nnginx: configuration test failed";
        assertThat(NginxFailurePresentation.format(raw)).isEqualTo(raw);
        assertThat(NginxFailurePresentation.format("custom-agent: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)"))
            .startsWith("custom-agent:");
    }

    @Test
    void onlyKnownWarningsUseKoreanFallbackWhileAbsentMessagesStayAbsent() {
        assertThat(NginxFailurePresentation.format(WARNING + "\n  " + WARNING + "  "))
            .isEqualTo("원격 적용에 실패했습니다. 서버 로그에서 상세 원인을 확인해 주세요.");
        assertThat(NginxFailurePresentation.format(null)).isEmpty();
        assertThat(NginxFailurePresentation.format(" \n ")).isEmpty();
    }

    @Test
    void actualTemplateFailureCellRendersCleanedCauseAndEscapesUnknownErrorMarkup() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));
        var matcher = Pattern.compile("<div class=\"form-hint\" th:if=\"[^\"]*\" th:text=\"\\$\\{nginxDisplayErrors\\[change.id\\]\\}\">오류</div>")
            .matcher(template);
        assertThat(matcher.find()).isTrue();
        NginxChangeRequest change = NginxChangeRequest.builder().id(57L)
            .errorMessage(WARNING + "\n인증서 오류 <script>unknown</script>").build();
        Context context = new Context();
        context.setVariable("change", change);
        context.setVariable("nginxDisplayErrors", NginxFailurePresentation.displayErrors(List.of(change)));
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        String rendered = engine.process(matcher.group(), context);
        assertThat(rendered).contains("인증서 오류", "&lt;script&gt;unknown&lt;/script&gt;").doesNotContain("setlocale", "<script>");
        assertThat(change.getErrorMessage()).contains(WARNING);
    }
}
