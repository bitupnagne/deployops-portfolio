package com.portfolio.deployops.config;

import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** 관리자 재설정 후 기존 인증 세션을 다음 보호 요청에서 폐기한다. */
@RequiredArgsConstructor
public class PasswordSessionGuardFilter extends OncePerRequestFilter {

    private final UserService userService;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/deploy/") && !path.startsWith("/api/v1/")
            && !path.equals("/otp") && !path.startsWith("/otp/")
            && !path.startsWith("/login/otp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SecurityUserDetails principal)) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        User saved;
        try {
            saved = userService.findById(principal.getId());
        } catch (RuntimeException ex) {
            logoutHandler.logout(request, response, auth);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (!saved.isActive() || principal.getUser() == null
                || !Objects.equals(saved.getPassword(), principal.getPassword())
                || !Objects.equals(saved.getRole(), principal.getUser().getRole())) {
            logoutHandler.logout(request, response, auth);
            if (path.startsWith("/api/v1/")) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
            return;
        }

        if (saved.isForcePasswordChange()
                && !path.equals("/deploy/profile")
                && !path.equals("/api/v1/profile/password")) {
            if (path.startsWith("/api/v1/")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            } else {
                response.sendRedirect(request.getContextPath() + "/deploy/profile?pwchange=1");
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
