package com.portfolio.deployops.controller;

import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.SystemSettingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연동 대상이 실제로 닿는지 눌러 보는 자리. 값을 <b>저장하기 전에</b> 확인하려는 것이므로
 * 저장된 설정이 아니라 화면에 입력한 값을 그대로 받는다.
 *
 * <p><b>권한</b> — 이 API 는 받은 값으로 <b>배포서버가 바깥으로 요청을 건다.</b>
 * 배포서버는 개발망과 운영망 양쪽에 닿는 자리라, 아무나 임의의 주소를 넣어 응답을 떠볼 수 있으면
 * 내부망을 훑는 수단이 된다({@code /test/deploy-ssh} 는 지정한 실행파일을 돌리기까지 한다).
 *
 * <p>그런데 이 화면(<code>/deploy/integration</code>)은 SUPER_ADMIN 전용인 반면
 * <b>API 에는 아무 제한이 없었다</b> — SecurityConfig 매처에 {@code /api/v1/integration/**} 이
 * 빠져 있어 {@code anyRequest().authenticated()} 로 흘러내렸고, 로그인한 개발자면 직접 호출할 수 있었다.
 * 화면이 막고 있으니 막힌 것처럼 보였을 뿐이다. 여기서 메서드 단위로 막는다.
 *
 * <p>모든 전역 연동 점검은 SUPER_ADMIN 전용이다. 프로젝트 상세의 GitLab 점검은
 * 별도 프로젝트 API를 사용하며, 공유 계정의 자격증명으로 임의 주소를 점검할 수 없다.
 */
@RestController
@RequestMapping("/api/v1/integration")
@RequiredArgsConstructor
public class IntegrationApiController {

    private final SystemSettingService settingService;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    @PostMapping("/test/gitlab")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testGitlab(@RequestBody Map<String, String> body) {
        String url = trim(body.get("url"));
        String token = credentialForConfiguredUrl(body.get("token"), "gitlab.token", url, "gitlab.url");
        if (url.isEmpty()) {
            return ApiResponse.error("URL_REQUIRED", "GitLab URL을 입력해 주세요.");
        }
        if (token.isEmpty()) {
            return ApiResponse.error("TOKEN_REQUIRED", "GitLab API 토큰을 입력하거나 현재 URL에 저장된 토큰을 설정해 주세요.");
        }

        try {
            // /version is public and returns 200 even when PRIVATE-TOKEN is
            // missing or invalid. /user requires authentication, so this tests
            // the credential that DeployOps will use for project operations.
            String apiUrl = url.replaceAll("/$", "") + "/api/v4/user";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(8))
                .GET();
            requestBuilder.header("PRIVATE-TOKEN", token);

            HttpResponse<String> response = HTTP_CLIENT.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                return ApiResponse.ok("GitLab API 토큰 인증에 성공했습니다. (HTTP 200)");
            }
            if (response.statusCode() == 401) {
                return ApiResponse.error("AUTH_FAIL", "토큰 인증에 실패했습니다. GitLab 토큰을 확인해 주세요.");
            }
            return ApiResponse.error("HTTP_" + response.statusCode(), "응답 코드: " + response.statusCode());
        } catch (Exception e) {
            return ApiResponse.error("CONNECT_FAIL", "연결 실패: " + simplify(e));
        }
    }

    @PostMapping("/test/nexus")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testNexus(@RequestBody Map<String, String> body) {
        String url = trim(body.get("url"));
        String user = trim(body.get("user"));
        String pass = user.equals(trim(settingService.get("nexus.user", "")))
            ? credentialForConfiguredUrl(body.get("pass"), "nexus.pass", url, "nexus.url")
            : trim(body.get("pass"));
        if (url.isEmpty()) {
            return ApiResponse.error("URL_REQUIRED", "Nexus URL을 입력해 주세요.");
        }

        try {
            // /status is intentionally available without authentication in Nexus, so it only
            // proves network reachability. Use an authenticated API required by the repository
            // browser and reject a false-positive connection when credentials are absent/invalid.
            String statusUrl = url.replaceAll("/$", "") + "/service/rest/v1/repositories";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(statusUrl))
                .timeout(Duration.ofSeconds(8))
                .GET();
            if (!user.isEmpty() && !pass.isEmpty()) {
                String encoded = Base64.getEncoder().encodeToString(
                    (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                requestBuilder.header("Authorization", "Basic " + encoded);
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                String registry = trim(body.get("registry"));
                if (!registry.isEmpty()) {
                    String registryApiUrl = trim(body.get("registryApiUrl"));
                    return testDockerRegistryEndpoint(url,
                        registryApiUrl.isEmpty() ? registry : registryApiUrl, user, pass);
                }
                return ApiResponse.ok("Nexus API 인증 및 연결에 성공했습니다. (HTTP 200)");
            }
            if (response.statusCode() == 401) {
                return ApiResponse.error("AUTH_FAIL", "인증에 실패했습니다. 사용자명과 비밀번호를 확인해 주세요.");
            }
            if (response.statusCode() == 403) {
                return ApiResponse.error(
                    "PERMISSION_DENIED",
                    "Nexus가 접근을 거부했습니다. 저장소 browse/read 권한과 Nexus 접근 정책을 확인해 주세요."
                );
            }
            return ApiResponse.error("HTTP_" + response.statusCode(), "응답 코드: " + response.statusCode());
        } catch (Exception e) {
            return ApiResponse.error("CONNECT_FAIL", "연결 실패: " + simplify(e));
        }
    }

    private ApiResponse<?> testDockerRegistryEndpoint(String nexusUrl, String registry, String user, String pass)
        throws Exception {
        String scheme = URI.create(nexusUrl).getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return ApiResponse.error("REGISTRY_INVALID", "Nexus URL은 HTTP 또는 HTTPS여야 합니다.");
        }
        URI registryUri;
        try {
            registryUri = URI.create(scheme + "://" + registry + "/v2/");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("REGISTRY_INVALID", "Docker Registry 주소 형식이 올바르지 않습니다.");
        }
        if (registryUri.getHost() == null || !"/v2/".equals(registryUri.getRawPath())
            || registryUri.getUserInfo() != null
            || registryUri.getQuery() != null || registryUri.getFragment() != null) {
            return ApiResponse.error("REGISTRY_INVALID", "Docker Registry에는 호스트와 포트만 입력해 주세요.");
        }

        HttpRequest.Builder registryRequest = HttpRequest.newBuilder()
            .uri(registryUri)
            .timeout(Duration.ofSeconds(8))
            .GET();
        if (!user.isEmpty() && !pass.isEmpty()) {
            String encoded = Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            registryRequest.header("Authorization", "Basic " + encoded);
        }

        HttpResponse<String> registryResponse = HTTP_CLIENT.send(
            registryRequest.build(), HttpResponse.BodyHandlers.ofString());
        String distributionVersion = registryResponse.headers()
            .firstValue("Docker-Distribution-API-Version").orElse("").trim();
        String registryBody = registryResponse.body() == null ? "" : registryResponse.body();
        if (registryResponse.statusCode() == 403
            && registryBody.toLowerCase(Locale.ROOT).contains("end user license agreement")) {
            return ApiResponse.error("REGISTRY_EULA",
                "Nexus 관리 API는 연결됐지만 Docker Registry가 사용 조건 미동의로 차단했습니다.");
        }
        boolean bearerChallenge = registryResponse.statusCode() == 401 && registryResponse.headers()
            .firstValue("WWW-Authenticate")
            .map(value -> value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()))
            .orElse(false);
        if (!"registry/2.0".equalsIgnoreCase(distributionVersion)
            || !(registryResponse.statusCode() == 200 || bearerChallenge)) {
            return ApiResponse.error("REGISTRY_UNAVAILABLE",
                "Nexus 관리 API는 연결됐지만 Docker Registry v2를 사용할 수 없습니다 (HTTP "
                    + registryResponse.statusCode() + ").");
        }
        return ApiResponse.ok("Nexus 관리 API와 Docker Registry v2 endpoint에 연결됐습니다. "
            + "실제 push 및 프로젝트별 권한은 별도 파이프라인으로 확인해야 합니다.");
    }

    @PostMapping("/test/deploy-ssh")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testDeploySsh(@RequestBody Map<String, String> body) {
        return ApiResponse.error("AGENT_REQUIRED", "서버 관리용 SSH는 지원하지 않습니다. 서버 화면에서 에이전트 연결을 검사해 주세요.");
    }

    @PostMapping("/test/office")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testOffice(@RequestBody Map<String, String> body) {
        String url = trim(body.get("url"));
        String secret = credentialForConfiguredUrl(body.get("secret"), "sso.shared-secret", url, "office.base-url");
        String recipientOfficeUserId = trim(body.get("recipientOfficeUserId"));
        if (url.isEmpty()) {
            return ApiResponse.error("URL_REQUIRED", "Office System URL을 입력해 주세요.");
        }

        try {
            if (recipientOfficeUserId.isEmpty()) {
                HttpResponse<String> response = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(url.replaceAll("/$", "") + "/login"))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 400) {
                    return ApiResponse.ok("Office System URL 연결에 성공했습니다. 실제 알림 전송은 테스트 수신자 id를 입력하면 확인할 수 있습니다.");
                }
                return ApiResponse.error("HTTP_" + response.statusCode(), "Office System 응답 코드: " + response.statusCode());
            }

            if (secret.isEmpty()) {
                return ApiResponse.error("SECRET_REQUIRED", "실제 알림 전송 테스트에는 SSO Shared Secret이 필요합니다.");
            }
            Long recipientId = parseLong(recipientOfficeUserId);
            if (recipientId == null) {
                return ApiResponse.error("RECIPIENT_INVALID", "테스트 수신자 Office user id는 숫자여야 합니다.");
            }

            String payload = """
                {"recipientOfficeUserId":%d,"senderOfficeUserId":null,"type":"DEPLOY_TEST","title":"Deploy System 연동 테스트","body":"Office/Windows 알림 채널 테스트입니다.","linkUrl":"/notifications"}
                """.formatted(recipientId).trim();
            HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url.replaceAll("/$", "") + "/api/internal/deploy/notifications"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("X-SSO-SECRET", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body().contains("\"success\":true")) {
                return ApiResponse.ok("Office 알림 전송에 성공했습니다. Office Web 알림과 Windows Toast를 확인해 주세요.");
            }
            if (response.statusCode() == 403 || response.statusCode() == 401) {
                return ApiResponse.error("AUTH_FAIL", "Office SSO Shared Secret 인증에 실패했습니다.");
            }
            return ApiResponse.error("HTTP_" + response.statusCode(), "Office 알림 API 응답: " + summarizeBody(response.body()));
        } catch (Exception e) {
            return ApiResponse.error("CONNECT_FAIL", "Office 연결 실패: " + simplify(e));
        }
    }

    @PostMapping("/test/deploy-public")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testDeployPublicUrl(@RequestBody Map<String, String> body) {
        String url = trim(body.get("url"));
        if (url.isEmpty()) {
            return ApiResponse.error("URL_REQUIRED", "Deploy System Public URL을 입력해 주세요.");
        }

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url.replaceAll("/$", "") + "/login"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return ApiResponse.ok("Deploy System Public URL 연결에 성공했습니다.");
            }
            return ApiResponse.error("HTTP_" + response.statusCode(), "Deploy System 응답 코드: " + response.statusCode());
        } catch (Exception e) {
            return ApiResponse.error("CONNECT_FAIL", "Deploy System Public URL 연결 실패: " + simplify(e));
        }
    }

    private String credentialForConfiguredUrl(String entered, String secretKey, String requestedUrl, String urlKey) {
        String value = trim(entered);
        if (!value.isEmpty()) {
            return value;
        }
        String savedUrl = trim(settingService.get(urlKey, ""));
        if (savedUrl.isEmpty() || !savedUrl.replaceAll("/+$", "").equals(requestedUrl.replaceAll("/+$", ""))) {
            return "";
        }
        return trim(settingService.get(secretKey, ""));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }

    private Long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String summarizeBody(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 160) + "..." : compact;
    }

    private String simplify(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        if (message.contains("Connection refused")) {
            return "연결이 거부되었습니다. 대상 서버 상태를 확인해 주세요.";
        }
        if (message.contains("UnknownHost") || message.contains("nodename")) {
            return "호스트를 찾을 수 없습니다.";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "연결 시간이 초과되었습니다.";
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}
