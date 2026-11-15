package com.portfolio.deployops.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class NginxBackupProofPresentationTest {
    @Test
    void successfulRowsShowPersistedBackupEvidenceButPendingAndFailedRowsDoNot() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));
        var matcher = Pattern.compile("(?s)<div class=\"nginx-backup-proof\".*?</div>\\s*</td>").matcher(template);
        assertThat(matcher.find()).isTrue();
        String proof = matcher.group().replaceFirst("</td>$", "");
        assertThat(proof).contains("sec:authorize=\"hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')\"");

        var change = NginxChangeRequest.builder()
            .status(NginxChangeStatus.APPLIED)
            .backupPath("/var/backups/deploy-system/nginx/<old-config>")
            .backupSnapshotHash("backup-hash")
            .appliedSnapshotHash("applied-hash")
            .appliedConfigHash("config-hash")
            .build();
        var context = new Context();
        context.setVariable("change", change);
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());

        String applied = engine.process(proof, context);
        assertThat(applied).contains("자동 백업 위치 /var/backups/deploy-system/nginx/&lt;old-config&gt;",
            "백업 SHA-256 backup-hash", "적용 상태 SHA-256 applied-hash", "적용 설정 SHA-256 config-hash")
            .doesNotContain("<old-config>");

        change.setStatus(NginxChangeStatus.ROLLED_BACK);
        assertThat(engine.process(proof, context)).contains("자동 백업 위치", "backup-hash");
        change.setStatus(NginxChangeStatus.PENDING);
        assertThat(engine.process(proof, context).trim()).isEmpty();
        change.setStatus(NginxChangeStatus.FAILED);
        assertThat(engine.process(proof, context).trim()).isEmpty();
    }
}
