package com.portfolio.deployops.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Permit only reviewed, local-data reads before any controller or authentication hook. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
public class PublicDemoAccessFilter extends OncePerRequestFilter {
    public static final String MESSAGE = "공개 데모에서는 샘플 데이터 조회만 가능합니다. 실제 서버 연결·배포·설정 변경은 실행되지 않습니다.";

    private static final Set<String> PAGES = Set.of(
        "/", "/deploy", "/login", "/health", "/error", "/favicon.ico",
        "/deploy/dashboard", "/deploy/requests", "/deploy/request-create", "/deploy/request-detail",
        "/deploy/projects", "/deploy/project-detail", "/deploy/server-groups", "/deploy/servers",
        "/deploy/server-map", "/deploy/infrastructure", "/deploy/nginx-policies", "/deploy/nexus-operations",
        "/deploy/users", "/deploy/teams", "/deploy/policies", "/deploy/integration", "/deploy/exec-policy",
        "/deploy/audit", "/deploy/profile", "/deploy/ai-settings", "/deploy/security", "/deploy/mr-approvals"
    );
    private static final Pattern LOCAL_API = Pattern.compile(
        "/api/v1/(?:"
        + "dashboard/(?:summary|charts|approvals/pending|deployments/recent)"
        + "|deploy-requests(?:/[0-9]+(?:/evidence)?|/execution-policy|/promotion-sources)?"
        + "|approvals/my-pending|users|teams|policies"
        + "|projects/[0-9]+/(?:components|request-components|rollout-routes|deploy-logs|permissions(?:/[0-9]+)?)"
        + "|operator/(?:architecture/[0-9]+|topology/[0-9]+|logs/[0-9]+/persisted)"
        + ")"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean unambiguous = !path.contains("%") && !path.contains(";") && !path.contains("\\") && !path.contains("..");
        boolean login = "POST".equals(request.getMethod()) && ("/login".equals(path) || "/logout".equals(path));
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        boolean asset = path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/")
            || path.startsWith("/fonts/");
        if (unambiguous && (login || (read && (PAGES.contains(path) || asset || LOCAL_API.matcher(path).matches())))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"errorCode\":\"PUBLIC_DEMO_READ_ONLY\",\"message\":\"" + MESSAGE + "\"}");
    }
}
