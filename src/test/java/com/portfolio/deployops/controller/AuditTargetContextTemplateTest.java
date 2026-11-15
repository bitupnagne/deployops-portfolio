package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.domain.AuditLog;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class AuditTargetContextTemplateTest {
    private String render(String id, String description) throws Exception {
        String source=Files.readString(Path.of("src/main/resources/templates/pages/audit.html"));
        int start=source.indexOf("<td class=\"audit-target\"");
        assertThat(start).isGreaterThan(0);
        String cell=source.substring(start,source.indexOf("</td>",start)+5);
        var engine=new SpringTemplateEngine(); engine.setTemplateResolver(new StringTemplateResolver());
        var context=new Context(); context.setVariable("log",AuditLog.builder().targetId(id).targetDesc(description).build());
        return engine.process(cell,context);
    }
    @Test void projectIdsStayDistinguishableEvenWithIdenticalEnvironmentDetails() throws Exception {
        String detail="environment=TEST,component=0,mode=MANAGED_REVISION,revision=5";
        assertThat(render("1",detail)).contains("대상 ID: 1",detail).doesNotContain("대상 ID: 2");
        assertThat(render("2",detail)).contains("대상 ID: 2",detail);
    }
    @Test void legacyRowsWithoutDescriptionKeepTargetIdVisibleWithoutFabricatingNewDetail() throws Exception {
        assertThat(render("1",null)).contains("대상 ID: 1",">-</span>").doesNotContain("revision=", "fingerprint");
        assertThat(render(null,"environment=PROD,component=0,mode=NONE")).doesNotContain("대상 ID:").contains("mode=NONE");
    }
    @Test void targetIdentityAndDescriptionRemainEscaped() throws Exception {
        assertThat(render("<script>bad</script>","<img src=x onerror=bad>"))
            .contains("&lt;script&gt;", "&lt;img").doesNotContain("<script>","<img src=x");
    }
}
