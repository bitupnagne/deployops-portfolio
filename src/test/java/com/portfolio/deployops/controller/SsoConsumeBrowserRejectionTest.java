package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.service.SsoService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class SsoConsumeBrowserRejectionTest {
    private final SsoService service = mock(SsoService.class);
    private final SsoController controller = new SsoController(service);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"missing", "expired", "already-used"})
    void expectedTicketRejectionRedirectsBeforeNewSessionOrAuthentication(String ticket) throws Exception {
        when(service.consumeTicket(ticket)).thenThrow(new IllegalArgumentException("거부"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.consumeTicket(ticket, "/deploy/dashboard", request, response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=1");
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(service, never()).normalizeRedirect(any());
    }

    @Test
    void rejectedLinkPreservesExistingAuthenticationAndSession() throws Exception {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal(), "existing", principal().getAuthorities());
        var context = SecurityContextHolder.getContext();
        context.setAuthentication(authentication);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(OtpController.OTP_VERIFIED, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        when(service.consumeTicket("used")).thenThrow(new IllegalArgumentException("거부"));

        controller.consumeTicket("used", null, request, new MockHttpServletResponse());

        assertThat(SecurityContextHolder.getContext()).isSameAs(context);
        assertThat(context.getAuthentication()).isSameAs(authentication);
        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isSameAs(context);
        assertThat(session.getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(false);
    }

    @Test
    void successStillAuthenticatesStoresContextAndOtpAndUsesNormalizedRedirect() throws Exception {
        SecurityUserDetails user = principal();
        when(service.consumeTicket("valid")).thenReturn(user);
        when(service.normalizeRedirect("requested")).thenReturn("/deploy/dashboard");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.consumeTicket("valid", "requested", request, response);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isSameAs(user);
        assertThat(authentication.getAuthorities()).isEqualTo(user.getAuthorities());
        assertThat(request.getSession(false).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
            .isSameAs(SecurityContextHolder.getContext());
        assertThat(request.getSession(false).getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(true);
        assertThat(response.getRedirectedUrl()).isEqualTo("/deploy/dashboard");
    }

    @Test
    void unexpectedServiceFailureIsNotDisguisedAsExpiredLink() {
        when(service.consumeTicket("valid")).thenThrow(new IllegalStateException("unavailable"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.consumeTicket("valid", null, request, response))
            .isInstanceOf(IllegalStateException.class);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void actualLoginNoticeRendersOnlyForErrorFlagAndNeverReflectsItsValue() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/login.html"));
        var matcher = Pattern.compile("<div th:if=\"\\$\\{param\\.ssoError}\"[^>]*>.*?</div>", Pattern.DOTALL).matcher(template);
        assertThat(matcher.find()).isTrue();
        String fragment = matcher.group();
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.addParameter("ssoError", "untrusted-ticket-value");
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext context = new WebContext(application.buildExchange(request, new MockHttpServletResponse()));
        assertThat(engine.process(fragment, context))
            .contains("SSO 링크가 만료되었거나 이미 사용되었습니다. 오피스에서 배포 시스템을 다시 열어 주세요.")
            .contains("role=\"alert\"").doesNotContain("untrusted-ticket-value");
        context = new WebContext(application.buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()));
        assertThat(engine.process(fragment, context)).doesNotContain("SSO 링크");
    }

    private static SecurityUserDetails principal() {
        return new SecurityUserDetails(User.builder().id(7L).loginId("qa-user").password("encoded")
            .role(UserRole.DEVELOPER).build());
    }
}
