package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ProfilePasswordChangeTemplateTest {
    @Test
    void staleQueryAfterPersistedChangeDoesNotShowWarningOrAutoOpen() {
        assertThat(render(false, true)).doesNotContain("id=\"force-password-change-notice\"", "setTimeout(function()");
    }

    @Test
    void actualForcedAccountShowsWarningAndAutoOpenEvenWithoutQueryHint() {
        assertThat(render(true, false)).contains("id=\"force-password-change-notice\"", "setTimeout(function()");
    }

    @Test
    void forcedRedirectShowsWarningAndAutoOpen() {
        assertThat(render(true, true)).contains("id=\"force-password-change-notice\"", "setTimeout(function()");
    }

    @Test
    void normalProfileDoesNotForcePasswordModal() {
        assertThat(render(false, false)).doesNotContain("id=\"force-password-change-notice\"", "setTimeout(function()");
    }

    private String render(boolean forced, boolean queryHint) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        request.setRequestURI("/deploy/profile");
        if (queryHint) request.addParameter("pwchange", "1");
        var exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(request, new MockHttpServletResponse());
        var principal = new SecurityUserDetails(User.builder().id(3L).name("검증 사용자")
            .loginId("qa-approver").role(UserRole.APPROVER).active(true).forcePasswordChange(forced).build());
        return engine.process("pages/profile", new WebContext(exchange, Locale.KOREAN, Map.of("currentUser", principal)));
    }
}
