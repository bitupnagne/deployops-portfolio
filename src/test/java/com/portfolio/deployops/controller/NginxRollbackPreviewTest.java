package com.portfolio.deployops.controller;
import static org.assertj.core.api.Assertions.*;
import com.portfolio.deployops.domain.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
class NginxRollbackPreviewTest {
 @Test void realRemovalAndTargetIdentityPreviewAreTruthfulAndEscaped() throws Exception {
  String source=Files.readString(Path.of("src/main/resources/templates/pages/nginx-policies.html"));var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());var context=new Context();var change=NginxChangeRequest.builder().restoreAbsent(true).rollbackSnapshotHash("<digest>").restoreTargetId(2L).build();context.setVariable("change",change);
  var removal=Pattern.compile("<td th:if=\"\\$\\{change.restoreAbsent\\}\">.*?</td>").matcher(source);assertThat(removal.find()).isTrue();assertThat(engine.process(removal.group(),context)).contains("관리 정책 제거").doesNotContain("new2");
  var identity=Pattern.compile("<div class=\"form-hint\" th:if=\"\\$\\{change.rollbackSnapshotHash != null\\}\".*?</div>").matcher(source);assertThat(identity.find()).isTrue();assertThat(engine.process(identity.group(),context)).contains("설정 없음");change.setRestoreAbsent(false);assertThat(engine.process(identity.group(),context)).contains("실제 복원 설정 #2");
  var digest=Pattern.compile("<div class=\"form-hint mono\" th:if=\"\\$\\{change.rollbackSnapshotHash != null\\}\".*?</div>").matcher(source);assertThat(digest.find()).isTrue();assertThat(engine.process(digest.group(),context)).contains("&lt;digest&gt;").doesNotContain("<digest>");
 }
}
