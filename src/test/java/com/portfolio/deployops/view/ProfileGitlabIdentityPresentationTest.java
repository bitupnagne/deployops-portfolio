package com.portfolio.deployops.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class ProfileGitlabIdentityPresentationTest {
    private String render(String token, String username) throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pages/profile.html"));
        int start = template.indexOf("<div id=\"profile-gitlab-status\">");
        int end = template.indexOf("<div th:unless=", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        Context context = new Context();
        context.setVariable("currentUser", new SecurityUserDetails(User.builder()
            .gitlabToken(token).gitlabUsername(username).build()));
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        return engine.process(template.substring(start, end), context);
    }

    @Test
    void storedTokenWithoutIdentityDoesNotClaimAccountIsLinked() throws Exception {
        for (String username : new String[] {null, "", "   "}) {
            assertThat(render("opaque-token", username)).contains("사용자명 연결 필요", "PAT 변경")
                .doesNotContain("연동됨", "계정 연결됨", "opaque-token");
        }
    }

    @Test
    void linkedIdentityDisplaysEscapedUsernameWithoutExposingToken() throws Exception {
        assertThat(render("opaque-token", "reviewer<&>"))
            .contains("연동됨", "reviewer&lt;&amp;&gt;")
            .doesNotContain("사용자명 연결 필요", "opaque-token");
    }

    @Test
    void noTokenDoesNotClaimLinkedIdentity() throws Exception {
        assertThat(render(null, "reviewer"))
            .doesNotContain("연동됨", "사용자명 연결 필요", "<strong");
    }
}
