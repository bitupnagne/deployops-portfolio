package com.portfolio.deployops.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class AuditIntegrityRolePresentationTest {
    private String renderActions(String role) throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pages/audit.html"));
        var actions = Pattern.compile("<div class=\"page-actions\">.*?</div>", Pattern.DOTALL).matcher(template);
        assertThat(actions.find()).isTrue();
        Context context = new Context();
        context.setVariable("currentUser", role == null ? null : Map.of("role", role));
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        return engine.process(actions.group(), context);
    }

    @Test
    void superAdminCanSeeIntegrityControlAndCurrentPageCsv() throws Exception {
        assertThat(renderActions("SUPER_ADMIN"))
            .contains("id=\"audit-integrity-status\"", "onclick=\"checkAuditIntegrity()\"", "무결성 검사")
            .contains("onclick=\"exportAudit()\"", "현재 페이지 CSV");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVER", "OPERATOR", "DEVELOPER"})
    void auditReadersDoNotSeeAdminIntegrityActionButKeepCurrentPageCsv(String role) throws Exception {
        assertThat(renderActions(role))
            .doesNotContain("audit-integrity-status", "checkAuditIntegrity()", "무결성 검사", "무결성 미확인")
            .contains("onclick=\"exportAudit()\"", "현재 페이지 CSV");
    }

    @Test
    void missingPrincipalDoesNotRenderAdministrativeAction() throws Exception {
        assertThat(renderActions(null))
            .doesNotContain("audit-integrity-status", "checkAuditIntegrity()", "무결성 검사")
            .contains("onclick=\"exportAudit()\"");
    }
}
