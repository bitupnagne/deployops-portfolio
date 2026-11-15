package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicDemoAccessFilterTest {
    private final PublicDemoAccessFilter filter = new PublicDemoAccessFilter();

    @ParameterizedTest
    @CsvSource({
        "POST,/api/v1/operator/execute/1", "POST,/api/v1/approvals/1/approve",
        "POST,/api/v1/servers", "PUT,/api/v1/settings/integration",
        "DELETE,/api/v1/projects/1", "POST,/api/agent/v1/enroll",
        "POST,/api/agent/v1/poll", "POST,/api/v1/gitlab/webhook",
        "POST,/api/internal/sso/tickets", "POST,/api/v1/profile/password",
        "GET,/api/v1/gitlab/projects", "GET,/api/v1/servers/browse",
        "GET,/api/v1/operator/architecture/1/health", "GET,/api/v1/operator/services/1",
        "GET,/api/v1/operator/servers/1/container-log", "GET,/api/v1/nexus/repositories",
        "GET,/api/v1/ai/dashboard-insight", "GET,/sso/consume",
        "GET,/otp/setup/data", "GET,/api/v1/new-unreviewed-endpoint",
        "GET,/js/../api/v1/servers/browse", "GET,/js/%2e%2e/api/v1/servers/browse",
        "POST,/health", "TRACE,/deploy/dashboard"
    })
    void blocksExecutionExternalReadsAndUnknownRoutesBeforeAnyHandler(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setParameter("host", "127.0.0.1");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PUBLIC_DEMO_READ_ONLY", "실제 서버 연결");
    }

    @ParameterizedTest
    @CsvSource({
        "GET,/login", "POST,/login", "POST,/logout", "GET,/health",
        "GET,/deploy/dashboard", "GET,/deploy/project-detail", "GET,/deploy/request-detail",
        "GET,/js/app.js", "HEAD,/css/styles.css", "GET,/api/v1/dashboard/charts",
        "GET,/api/v1/deploy-requests/4/evidence", "GET,/api/v1/operator/architecture/1",
        "GET,/api/v1/operator/topology/2", "GET,/api/v1/projects/1/deploy-logs"
    })
    void reviewedLocalReadsAndSessionActionsContinueToExistingSecurity(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
