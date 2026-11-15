package com.portfolio.deployops.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Tests one GitLab project with the server's configured API address and service-account PAT. */
@Service
@RequiredArgsConstructor
public class ProjectGitlabConnectionService {

    public record Result(long projectId, String pathWithNamespace, String defaultBranch,
                         boolean repositoryUrlVerified) {}

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private final GitlabService gitlabService;
    private final SystemSettingService settingService;
    private final ObjectMapper objectMapper;

    public Result test(long gitlabProjectId, String repositoryUrl) {
        if (gitlabProjectId <= 0) {
            throw new IllegalArgumentException("GitLab 프로젝트 ID를 입력해 주세요.");
        }
        String baseUrl = gitlabService.getSystemGitlabUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("중앙 GitLab API 주소를 설정해 주세요.");
        }
        String token = settingService.get("gitlab.token", "");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("중앙 GitLab 서비스 계정 토큰을 설정해 주세요.");
        }
        URI apiUri;
        try {
            URI base = parseHttpUrl(baseUrl);
            apiUri = URI.create(baseUrl.replaceFirst("/+$", "") + "/api/v4/projects/" + gitlabProjectId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("중앙 GitLab API 주소를 확인해 주세요.");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(apiUri)
                .timeout(Duration.ofSeconds(8))
                .header("PRIVATE-TOKEN", token)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new ConnectionFailure("GitLab 서비스 계정 토큰 인증에 실패했습니다.");
            }
            if (response.statusCode() == 403 || response.statusCode() == 404) {
                throw new ConnectionFailure("서비스 계정이 이 GitLab 프로젝트에 접근할 수 없거나 프로젝트가 없습니다.");
            }
            if (response.statusCode() != 200) {
                throw new ConnectionFailure("GitLab 프로젝트 조회에 실패했습니다. (HTTP " + response.statusCode() + ")");
            }

            Map<String, Object> project = objectMapper.readValue(response.body(), new TypeReference<>() {});
            long actualId = project.get("id") instanceof Number number ? number.longValue() : -1;
            String path = project.get("path_with_namespace") instanceof String text ? text : "";
            if (actualId != gitlabProjectId || path.isBlank()) {
                throw new ConnectionFailure("GitLab 프로젝트 응답을 확인할 수 없습니다.");
            }
            boolean repositoryVerified = repositoryUrl != null && !repositoryUrl.isBlank();
            if (repositoryVerified && !matchesRepositoryUrl(repositoryUrl, baseUrl, path, project)) {
                throw new ConnectionFailure("저장소 주소가 이 GitLab 프로젝트의 주소와 일치하지 않습니다.");
            }
            Object branch = project.get("default_branch");
            return new Result(actualId, path, branch instanceof String name ? name : "", repositoryVerified);
        } catch (ConnectionFailure e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException("GitLab 서버 연결 또는 프로젝트 응답 확인에 실패했습니다.");
        }
    }

    private boolean matchesRepositoryUrl(String repositoryUrl, String apiBaseUrl,
                                         String projectPath, Map<String, Object> project) {
        String requested = repositoryUrl.trim();
        String sshCloneUrl = project.get("ssh_url_to_repo") instanceof String value ? value : "";
        // GitLab advertises the exact SSH clone URL, including non-default port and scp syntax.
        if (!sshCloneUrl.isBlank() && requested.equals(sshCloneUrl.trim())) {
            return true;
        }
        try {
            URI candidate = parseHttpUrl(requested);
            String advertised = project.get("http_url_to_repo") instanceof String value ? value : "";
            if (!advertised.isBlank()) {
                try {
                    URI clone = parseHttpUrl(advertised);
                    if (sameOrigin(candidate, clone) && normalizedPath(candidate).equals(normalizedPath(clone))) {
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // A malformed GitLab-advertised clone URL cannot authorize an arbitrary host.
                }
            }
            if (matchesConfiguredOrigin(candidate, apiBaseUrl, projectPath)) {
                return true;
            }
            return matchesConfiguredOrigin(candidate, settingService.get("gitlab.url", ""), projectPath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean matchesConfiguredOrigin(URI candidate, String configuredUrl, String projectPath) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return false;
        }
        try {
            URI configured = parseHttpUrl(configuredUrl);
            String prefix = normalizedPath(configured);
            String expectedPath = (prefix.isEmpty() ? "" : prefix + "/") + projectPath;
            return sameOrigin(candidate, configured) && normalizedPath(candidate).equals(expectedPath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private URI parseHttpUrl(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
            || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid GitLab URL");
        }
        return uri;
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
            && left.getHost().equalsIgnoreCase(right.getHost())
            && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String normalizedPath(URI uri) {
        String path = uri.getPath();
        return path == null ? "" : path.replaceFirst("^/+", "").replaceFirst("\\.git/?$", "")
            .replaceFirst("/+$", "");
    }

    private static final class ConnectionFailure extends RuntimeException {
        private ConnectionFailure(String message) {
            super(message);
        }
    }
}
