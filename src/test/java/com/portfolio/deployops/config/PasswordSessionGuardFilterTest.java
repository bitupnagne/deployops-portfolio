package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.service.UserService;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PasswordSessionGuardFilterTest {

    private final UserService userService = org.mockito.Mockito.mock(UserService.class);
    private final PasswordSessionGuardFilter filter = new PasswordSessionGuardFilter(userService);
    private final FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminResetInvalidatesOldSessionBeforeApiRuns() throws Exception {
        authenticate("old-hash", false);
        when(userService.findById(3L)).thenReturn(user("new-hash", true));
        MockHttpServletRequest request = request("/api/v1/approvals/pending");
        MockHttpSession session = (MockHttpSession) request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void adminResetRedirectsOldPageSessionToLogin() throws Exception {
        authenticate("old-hash", false);
        when(userService.findById(3L)).thenReturn(user("new-hash", true));
        MockHttpServletRequest request = request("/deploy/dashboard");
        MockHttpSession session = (MockHttpSession) request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(chain);
    }

    @Test
    void adminResetBlocksOldAuthenticatedOtpMutationRoutes() throws Exception {
        when(userService.findById(3L)).thenReturn(user("new-hash", true));
        for (String path : List.of("/otp", "/otp/confirm", "/otp/off", "/otp/setup/data", "/login/otp/verify")) {
            authenticate("old-hash", false);
            MockHttpServletRequest request = request(path);
            MockHttpSession session = (MockHttpSession) request.getSession();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getRedirectedUrl()).isEqualTo("/login");
            assertThat(session.isInvalid()).isTrue();
        }
        verifyNoInteractions(chain);
    }

    @Test
    void roleDemotionInvalidatesFormerAdministratorBeforeAuthorization() throws Exception {
        User formerAdmin = user("current-hash", false);
        formerAdmin.setRole(UserRole.SUPER_ADMIN);
        SecurityUserDetails principal = new SecurityUserDetails(formerAdmin);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        User demoted = user("current-hash", false);
        demoted.setRole(UserRole.PROJECT_MANAGER);
        when(userService.findById(3L)).thenReturn(demoted);
        MockHttpServletRequest request = request("/api/v1/settings/integration");
        MockHttpSession session = (MockHttpSession) request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void databaseFailureFailsClosedAndInvalidatesSession() throws Exception {
        authenticate("old-hash", false);
        when(userService.findById(3L)).thenThrow(new IllegalStateException("DB unavailable"));
        MockHttpServletRequest request = request("/api/v1/users");
        MockHttpSession session = (MockHttpSession) request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(chain);
    }

    @Test
    void forcedUserCanChangePasswordButCannotUseOtherApi() throws Exception {
        authenticate("current-hash", true);
        when(userService.findById(3L)).thenReturn(user("current-hash", true));
        MockHttpServletRequest allowed = request("/api/v1/profile/password");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, chain);
        verify(chain).doFilter(allowed, allowedResponse);

        MockHttpServletRequest blocked = request("/api/v1/profile/gitlab-token");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        filter.doFilter(blocked, blockedResponse, chain);
        assertThat(blockedResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void loginSsoAndStaticPathsDoNotCallDatabase() throws Exception {
        authenticate("old-hash", false);
        for (String path : List.of("/login", "/sso/consume", "/css/app.css", "/js/app.js")) {
            MockHttpServletRequest request = request(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
        verifyNoInteractions(userService);
    }

    private void authenticate(String hash, boolean forced) {
        User principalUser = user(hash, forced);
        SecurityUserDetails principal = new SecurityUserDetails(principalUser);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private User user(String hash, boolean forced) {
        return User.builder().id(3L).loginId("approver").role(UserRole.APPROVER)
            .password(hash).forcePasswordChange(forced).active(true).build();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
