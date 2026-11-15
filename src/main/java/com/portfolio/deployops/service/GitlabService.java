package com.portfolio.deployops.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GitlabService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ObjectMapper objectMapper;
    private final SystemSettingService settingService;
    private final SecretCryptoService secretCryptoService;

    /**
     * 서버→GitLab API 호출의 베이스 URL. 내부 주소(gitlab.api-url) 우선, 없으면 공인(gitlab.url) 폴백.
     * 공인 IP는 hairpin/방화벽 때문에 같은 서버에서 도달하지 못할 수 있으므로 서버간 통신은 내부 주소를 쓴다.
     * (브라우저용 링크는 gitlab.url 을 별도로 사용)
     */
    public String getSystemGitlabUrl() {
        String apiUrl = settingService.get("gitlab.api-url", "");
        if (apiUrl != null && !apiUrl.isBlank()) {
            return apiUrl.replaceAll("/$", "");
        }
        String modernKey = settingService.get("gitlab.url", "");
        if (modernKey != null && !modernKey.isBlank()) {
            return modernKey.replaceAll("/$", "");
        }
        return settingService.get("gitlab_url", "").replaceAll("/$", "");
    }

    /** 토큰 해석 결과. source: USER_PAT(본인 PAT) | SYSTEM_BOT(시스템 봇 폴백) | NONE(없음/불허). */
    public record TokenResolution(String token, String source) {}

    /** GitLab /user에 귀속된 실제 PAT 계정. */
    public record GitlabUserIdentity(long id, String username, boolean admin) {
        public GitlabUserIdentity(long id, String username) {
            this(id, username, false);
        }
    }

    /** 파이프라인 ref의 실제 종류와 GitLab 보호 상태. */
    public record RefProtection(String ref, String type, boolean protectedRef) {}

    public static final String IMMUTABLE_MR_TAG_POLICY = "deploy/mr-*";

    /** 규칙 존재와 생성 허용 여부만 조회한다. 특정 PAT의 태그 생성 권한을 증명하지 않는다. */
    public record ImmutableTagPolicy(boolean present, boolean creationEnabled, String matchedPolicyName) {
        public ImmutableTagPolicy(boolean present, boolean creationEnabled) {
            this(present, creationEnabled, present ? IMMUTABLE_MR_TAG_POLICY : null);
        }
    }

    public ImmutableTagPolicy getImmutableMrTagPolicy(String token, String gitlabUrl, long projectId)
        throws Exception {
        ImmutableTagPolicy found = new ImmutableTagPolicy(false, false);
        // 두 규칙 모두 앞으로 생성될 MR 태그 전체를 보호한다. 개별 태그 상태로 대체하지 않는다.
        for (String name : List.of(IMMUTABLE_MR_TAG_POLICY, "deploy/*")) {
            ImmutableTagPolicy policy = getImmutableMrTagPolicyRule(token, gitlabUrl, projectId, name);
            if (policy.creationEnabled()) return policy;
            if (policy.present() && !found.present()) found = policy;
        }
        return found;
    }

    private ImmutableTagPolicy getImmutableMrTagPolicyRule(
        String token, String gitlabUrl, long projectId, String name
    ) throws Exception {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> policy = getOptionalRepositoryRef(token, resolveBaseUrl(gitlabUrl)
            + "/api/v4/projects/" + projectId + "/protected_tags/" + encoded);
        if (policy == null) return new ImmutableTagPolicy(false, false);
        if (!name.equals(policy.get("name"))
            || !(policy.get("create_access_levels") instanceof List<?> levels) || levels.isEmpty()) {
            throw new IllegalArgumentException("GitLab 보호 태그 정책 응답을 확인할 수 없습니다.");
        }
        boolean creationEnabled = false;
        for (Object value : levels) {
            if (!(value instanceof Map<?, ?> level)) {
                throw new IllegalArgumentException("GitLab 보호 태그 생성 권한 응답을 확인할 수 없습니다.");
            }
            Object access = level.get("access_level");
            if (access != null && (!(access instanceof Number number)
                || !Set.of(0, 30, 40).contains(number.intValue())
                || number.doubleValue() != number.intValue())) {
                throw new IllegalArgumentException("GitLab 보호 태그 생성 권한 응답을 확인할 수 없습니다.");
            }
            boolean identifiedCreator = false;
            for (String key : List.of("user_id", "group_id", "deploy_key_id")) {
                Object id = level.get(key);
                if (id == null) continue;
                if (!(id instanceof Number number) || number.longValue() <= 0
                    || number.doubleValue() != number.longValue()) {
                    throw new IllegalArgumentException("GitLab 보호 태그 생성 권한 응답을 확인할 수 없습니다.");
                }
                identifiedCreator = true;
            }
            if (access == null && !identifiedCreator) {
                throw new IllegalArgumentException("GitLab 보호 태그 생성 권한 응답을 확인할 수 없습니다.");
            }
            creationEnabled |= identifiedCreator || (access instanceof Number number && number.intValue() > 0);
        }
        return new ImmutableTagPolicy(true, creationEnabled, name);
    }

    public String resolveAccessToken(String preferredToken, String role) {
        return resolveAccessToken(preferredToken, role, null);
    }

    public String resolveAccessToken(String preferredToken, String role, String loginId) {
        return resolveAccessTokenInfo(preferredToken, role, loginId).token();
    }

    /** PROJECT_VIEW 인가 후 호출하는 CI 조회 전용 경로. 개인 MR 작업에는 사용하지 않는다. */
    public String resolveReadonlyCiAccessToken(String preferredToken) {
        String personal = trimToNull(secretCryptoService.decryptIfEncrypted(preferredToken));
        if (personal != null) return personal;
        String system = resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR);
        if (system == null) return null;
        try {
            GitlabUserIdentity identity = getAuthenticatedUserIdentity(system, null);
            if (identity == null || identity.id() <= 0 || identity.username() == null
                || identity.username().isBlank() || identity.admin()
                || "root".equalsIgnoreCase(identity.username())) return null;
            return system;
        } catch (Exception ignored) {
            // 원격 응답이나 토큰을 사용자 오류·로그에 노출하지 않는다.
            return null;
        }
    }

    /** 2단계 인가 1차: 본인 PAT 우선, 없으면 승격 정책에 따라 시스템봇 토큰 폴백. */
    public TokenResolution resolveAccessTokenInfo(String preferredToken, String role, String loginId) {
        String token = trimToNull(secretCryptoService.decryptIfEncrypted(preferredToken));
        if (token != null) {
            return new TokenResolution(token, "USER_PAT");
        }
        if (!isSystemTokenFallbackAllowed(role, loginId)) {
            return new TokenResolution(null, "NONE");
        }
        String system = trimToNull(settingService.get("gitlab.token", ""));
        return new TokenResolution(system, system != null ? "SYSTEM_BOT" : "NONE");
    }

    public boolean isSystemTokenFallbackAllowed(String role) {
        return isSystemTokenFallbackAllowed(role, null);
    }

    /**
     * 시스템 봇 토큰 폴백 허용 여부.
     * admin-only=false 면 전체 허용. 그 외엔 SUPER_ADMIN 또는 승격 화이트리스트
     * (gitlab.system-token.allowed-users, 콤마 구분 loginId)에 포함된 사용자만 허용.
     */
    public boolean isSystemTokenFallbackAllowed(String role, String loginId) {
        boolean adminOnly = parseBoolean(settingService.get("gitlab.system-token.admin-only", "true"), true);
        if (!adminOnly) {
            return true;
        }
        String normalizedRole = trimToNull(role);
        if (normalizedRole != null && ("SUPER_ADMIN".equalsIgnoreCase(normalizedRole)
            || AutomaticDispatchService.SYSTEM_ACTOR.equalsIgnoreCase(normalizedRole))) {
            return true;
        }
        String normalizedLogin = trimToNull(loginId);
        if (normalizedLogin != null) {
            String whitelist = settingService.get("gitlab.system-token.allowed-users", "");
            if (whitelist != null && !whitelist.isBlank()) {
                for (String entry : whitelist.split(",")) {
                    if (entry.trim().equalsIgnoreCase(normalizedLogin)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public List<Map<String, Object>> getProjects(String token, String gitlabUrl) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects?membership=true&per_page=50&order_by=last_activity_at&sort=desc";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, false, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(project -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", valueOrDefault(project.get("id"), 0));
            mapped.put("name", valueOrDefault(project.get("name"), ""));
            mapped.put("nameWithNamespace", valueOrDefault(project.get("name_with_namespace"), ""));
            // 이미지 경로를 추천할 때 쓴다 — worksystem/worksystem-server 처럼
            // 저장소 구조를 그대로 옮기면 사람이 규칙을 외우지 않아도 된다.
            mapped.put("pathWithNamespace", valueOrDefault(project.get("path_with_namespace"), ""));
            mapped.put("httpUrlToRepo", valueOrDefault(project.get("http_url_to_repo"), ""));
            mapped.put("defaultBranch", valueOrDefault(project.get("default_branch"), "main"));
            mapped.put("description", valueOrDefault(project.get("description"), ""));
            return mapped;
        }).toList();
    }

    public List<Map<String, Object>> getBranches(String token, String gitlabUrl, long projectId) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/repository/branches?per_page=100&order_by=updated_at";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(branch -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> commit = (Map<String, Object>) branch.getOrDefault("commit", Map.of());
            return Map.<String, Object>of(
                "name", branch.getOrDefault("name", ""),
                "isDefault", branch.getOrDefault("default", false),
                "isProtected", branch.getOrDefault("protected", false),
                "commitId", commit.getOrDefault("short_id", ""),
                "commitSha", commit.getOrDefault("id", ""),
                "commitTitle", commit.getOrDefault("title", ""),
                "commitDate", commit.getOrDefault("committed_date", "")
            );
        }).toList();
    }

    /** 커밋 SHA(short/full)로 단일 커밋 정보를 조회한다(best-effort, 실패 시 빈 맵). */
    public Map<String, Object> getCommit(String token, String gitlabUrl, long projectId, String sha) {
        if (sha == null || sha.isBlank()) {
            return Map.of();
        }
        try {
            String base = resolveBaseUrl(gitlabUrl);
            String apiUrl = base + "/api/v4/projects/" + projectId + "/repository/commits/"
                + URLEncoder.encode(sha.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(10))
                .header("PRIVATE-TOKEN", token)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of();
            }
            Map<String, Object> commit = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("shortId", valueOrDefault(commit.get("short_id"), ""));
            mapped.put("title", valueOrDefault(commit.get("title"), ""));
            mapped.put("message", valueOrDefault(commit.get("message"), ""));
            mapped.put("authorName", valueOrDefault(commit.get("author_name"), ""));
            mapped.put("createdAt", valueOrDefault(commit.get("created_at"), ""));
            return mapped;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public List<Map<String, Object>> getUsers(String token, String gitlabUrl, String search) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        StringBuilder apiUrl = new StringBuilder(base)
            .append("/api/v4/users?active=true&without_project_bots=true&per_page=100");

        String normalizedSearch = trimToNull(search);
        if (normalizedSearch != null) {
            apiUrl.append("&search=").append(URLEncoder.encode(normalizedSearch, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl.toString()))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, false, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(user -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", user.getOrDefault("id", 0));
            mapped.put("username", user.getOrDefault("username", ""));
            mapped.put("name", user.getOrDefault("name", ""));
            mapped.put("state", user.getOrDefault("state", ""));
            mapped.put("webUrl", user.getOrDefault("web_url", ""));
            mapped.put("avatarUrl", user.getOrDefault("avatar_url", ""));
            return mapped;
        }).toList();
    }

    public List<Map<String, Object>> getPipelines(String token, String gitlabUrl, long projectId, String ref) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/pipelines?per_page=20&order_by=updated_at&sort=desc&ref=" + encodedRef;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(pipeline -> Map.<String, Object>of(
            "id", pipeline.getOrDefault("id", 0),
            "iid", pipeline.getOrDefault("iid", 0),
            "status", pipeline.getOrDefault("status", ""),
            "ref", pipeline.getOrDefault("ref", ""),
            "sha", pipeline.getOrDefault("sha", ""),
            "webUrl", pipeline.getOrDefault("web_url", ""),
            "updatedAt", pipeline.getOrDefault("updated_at", ""),
            "createdAt", pipeline.getOrDefault("created_at", "")
        )).toList();
    }

    public List<Map<String, Object>> getMergeRequests(
        String token,
        String gitlabUrl,
        long projectId,
        String state
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String normalizedState = trimToNull(state);
        if (normalizedState == null) {
            normalizedState = "opened";
        }
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/merge_requests?per_page=50&order_by=updated_at&sort=desc&state="
            + URLEncoder.encode(normalizedState, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(mr -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> author = (Map<String, Object>) mr.getOrDefault("author", Map.of());
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", mr.getOrDefault("id", 0));
            mapped.put("iid", mr.getOrDefault("iid", 0));
            mapped.put("title", mr.getOrDefault("title", ""));
            mapped.put("state", mr.getOrDefault("state", ""));
            mapped.put("sourceBranch", mr.getOrDefault("source_branch", ""));
            mapped.put("targetBranch", mr.getOrDefault("target_branch", ""));
            mapped.put("sha", mr.getOrDefault("sha", ""));
            mapped.put("sourceProjectId", mr.getOrDefault("source_project_id", 0));
            mapped.put("targetProjectId", mr.getOrDefault("target_project_id", projectId));
            mapped.put("diffRefs", mr.getOrDefault("diff_refs", Map.of()));
            mapped.put("mergeCommitSha", mr.getOrDefault("merge_commit_sha", ""));
            mapped.put("squashCommitSha", mr.getOrDefault("squash_commit_sha", ""));
            mapped.put("authorName", author.getOrDefault("name", ""));
            mapped.put("authorId", author.getOrDefault("id", 0));
            mapped.put("authorUsername", author.getOrDefault("username", ""));
            mapped.put("assignees", mr.getOrDefault("assignees", List.of()));
            mapped.put("reviewers", mr.getOrDefault("reviewers", List.of()));
            mapped.put("draft", mr.getOrDefault("draft", mr.getOrDefault("work_in_progress", false)));
            mapped.put("mergeStatus", mr.getOrDefault("merge_status", ""));
            mapped.put("detailedMergeStatus", mr.getOrDefault("detailed_merge_status", ""));
            mapped.put("hasConflicts", mr.getOrDefault("has_conflicts", false));
            mapped.put("webUrl", mr.getOrDefault("web_url", ""));
            mapped.put("createdAt", mr.getOrDefault("created_at", ""));
            mapped.put("updatedAt", mr.getOrDefault("updated_at", ""));
            mapped.put("userNotesCount", mr.getOrDefault("user_notes_count", 0));
            return mapped;
        }).toList();
    }

    public Map<String, Object> getMergeRequest(
        String token, String gitlabUrl, long projectId, long mergeRequestIid
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequestIid;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10)).header("PRIVATE-TOKEN", token).GET().build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);
        Map<String, Object> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) raw.getOrDefault("author", Map.of());
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("iid", raw.getOrDefault("iid", mergeRequestIid));
        mapped.put("state", raw.getOrDefault("state", ""));
        mapped.put("title", raw.getOrDefault("title", ""));
        mapped.put("sourceBranch", raw.getOrDefault("source_branch", ""));
        mapped.put("targetBranch", raw.getOrDefault("target_branch", ""));
        mapped.put("sha", raw.getOrDefault("sha", ""));
        mapped.put("sourceProjectId", raw.getOrDefault("source_project_id", 0));
        mapped.put("targetProjectId", raw.getOrDefault("target_project_id", projectId));
        mapped.put("diffRefs", raw.getOrDefault("diff_refs", Map.of()));
        mapped.put("authorUsername", author.getOrDefault("username", ""));
        mapped.put("authorId", author.getOrDefault("id", 0));
        mapped.put("draft", raw.getOrDefault("draft", raw.getOrDefault("work_in_progress", false)));
        mapped.put("mergeCommitSha", raw.getOrDefault("merge_commit_sha", ""));
        mapped.put("squashCommitSha", raw.getOrDefault("squash_commit_sha", ""));
        Object userValue = raw.get("user");
        if (userValue instanceof Map<?, ?> currentUser && currentUser.get("can_merge") instanceof Boolean canMerge) {
            mapped.put("canMerge", canMerge);
        } else {
            mapped.put("canMerge", null);
        }
        return mapped;
    }

    /** Create-once tag bound to an exact commit; an existing tag is accepted only for the same SHA. */
    public Map<String, Object> ensureImmutableTag(
        String token, String gitlabUrl, long projectId, String tagName, String expectedSha
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String tag = trimToNull(tagName);
        String sha = requireCommitSha(expectedSha);
        if (tag == null || !tag.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")) {
            throw new IllegalArgumentException("Invalid immutable build tag name.");
        }
        Map<String, Object> existing = getTagIfPresent(token, gitlabUrl, projectId, tag);
        if (existing == null) {
            String base = resolveBaseUrl(gitlabUrl);
            String apiUrl = base + "/api/v4/projects/" + projectId + "/repository/tags";
            String body = objectMapper.writeValueAsString(Map.of("tag_name", tag, "ref", sha));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15)).header("PRIVATE-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response, true, true, 200, 201);
            existing = objectMapper.readValue(response.body(), new TypeReference<>() {});
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> commit = (Map<String, Object>) existing.getOrDefault("commit", Map.of());
        String actual = trimToNull(String.valueOf(commit.getOrDefault("id", "")));
        if (actual == null || !sha.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Immutable build tag exists at a different commit SHA.");
        }
        return existing;
    }

    private Map<String, Object> getTagIfPresent(
        String token, String gitlabUrl, long projectId, String tagName
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String encoded = URLEncoder.encode(tagName, StandardCharsets.UTF_8).replace("+", "%20");
        String apiUrl = base + "/api/v4/projects/" + projectId + "/repository/tags/" + encoded;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10)).header("PRIVATE-TOKEN", token).GET().build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        ensureSuccess(response, true, false, 200);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 대상 브랜치가 가리키는 현재 commit SHA. fast-forward 병합 결과를 확정할 때 사용한다. */
    public String getBranchCommitSha(
        String token, String gitlabUrl, long projectId, String branchName
    ) throws Exception {
        String branch = trimToNull(branchName);
        if (branch == null) throw new IllegalArgumentException("GitLab branch name is required.");
        String base = resolveBaseUrl(gitlabUrl);
        String encoded = URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> raw = getOptionalRepositoryRef(
            token, base + "/api/v4/projects/" + projectId + "/repository/branches/" + encoded);
        if (raw == null) throw new IllegalArgumentException("GitLab target branch를 찾을 수 없습니다.");
        @SuppressWarnings("unchecked")
        Map<String, Object> commit = (Map<String, Object>) raw.getOrDefault("commit", Map.of());
        return requireCommitSha(String.valueOf(commit.getOrDefault("id", "")));
    }

    public List<Map<String, Object>> getMergeRequestCommits(
        String token,
        String gitlabUrl,
        long projectId,
        long mergeRequestIid
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/merge_requests/" + mergeRequestIid + "/commits?per_page=100";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(commit -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", commit.getOrDefault("id", ""));
            mapped.put("shortId", commit.getOrDefault("short_id", ""));
            mapped.put("title", commit.getOrDefault("title", ""));
            mapped.put("message", commit.getOrDefault("message", ""));
            mapped.put("authorName", commit.getOrDefault("author_name", ""));
            mapped.put("authoredDate", commit.getOrDefault("authored_date", ""));
            mapped.put("committedDate", commit.getOrDefault("committed_date", ""));
            mapped.put("webUrl", commit.getOrDefault("web_url", ""));
            return mapped;
        }).toList();
    }

    public Map<String, Object> getMergeRequestChanges(
        String token,
        String gitlabUrl,
        long projectId,
        long mergeRequestIid
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/merge_requests/" + mergeRequestIid + "/changes";

        Map<String, Object> raw = readMergeRequestChanges(token, apiUrl);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialChanges = (List<Map<String, Object>>) raw.getOrDefault("changes", List.of());
        String recovery = "NOT_NEEDED";
        // Individual diffs can be omitted even with overflow=false. Retry only
        // explicit collapsed diffs, once, without changing GitLab's global limits.
        if (initialChanges.stream().anyMatch(change -> Boolean.TRUE.equals(change.get("collapsed")))) {
            recovery = "UNAVAILABLE";
            if (initialChanges.size() <= 1000) {
                try {
                    Map<String, Object> recovered = readMergeRequestChanges(token, apiUrl + "?access_raw_diffs=true");
                    if (sameDiffReview(raw, recovered)) {
                        raw = recovered;
                        recovery = "RECOVERED";
                    } else {
                        recovery = "CONTEXT_CHANGED";
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                } catch (Exception unavailable) {
                    // Preserve the omissions and fail closed; never log tokens or response bodies.
                    recovery = "UNAVAILABLE";
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) raw.getOrDefault("changes", List.of());
        List<Map<String, Object>> mappedChanges = changes.stream().map(change -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("oldPath", change.getOrDefault("old_path", ""));
            mapped.put("newPath", change.getOrDefault("new_path", ""));
            mapped.put("diff", change.getOrDefault("diff", ""));
            mapped.put("newFile", change.getOrDefault("new_file", false));
            mapped.put("renamedFile", change.getOrDefault("renamed_file", false));
            mapped.put("deletedFile", change.getOrDefault("deleted_file", false));
            mapped.put("collapsed", change.getOrDefault("collapsed", false));
            mapped.put("tooLarge", change.getOrDefault("too_large", false));
            return mapped;
        }).toList();

        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("iid", raw.getOrDefault("iid", mergeRequestIid));
        mapped.put("title", raw.getOrDefault("title", ""));
        mapped.put("sourceBranch", raw.getOrDefault("source_branch", ""));
        mapped.put("targetBranch", raw.getOrDefault("target_branch", ""));
        mapped.put("state", raw.getOrDefault("state", ""));
        mapped.put("sha", raw.getOrDefault("sha", ""));
        mapped.put("diffRefs", raw.getOrDefault("diff_refs", Map.of()));
        mapped.put("overflow", raw.getOrDefault("overflow", false));
        mapped.put("webUrl", raw.getOrDefault("web_url", ""));
        mapped.put("changesCount", raw.getOrDefault("changes_count", mappedChanges.size()));
        mapped.put("changes", mappedChanges);
        mapped.put("rawDiffRecovery", recovery);
        mapped.put("reviewComplete", !hasIncompleteMergeRequestDiff(mapped));
        return mapped;
    }

    private Map<String, Object> readMergeRequestChanges(String token, String apiUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(15))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);

        if (response.body().length() > 8 * 1024 * 1024) {
            throw new IllegalStateException("MR diff 응답이 안전한 조회 크기를 초과했습니다.");
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private boolean sameDiffReview(Map<String, Object> before, Map<String, Object> after) {
        String sha = String.valueOf(before.get("sha"));
        if (!sha.matches("(?i)[0-9a-f]{40,64}") || !sha.equals(after.get("sha"))) return false;
        for (String key : List.of("iid", "source_branch", "target_branch", "state", "diff_refs")) {
            if (before.get(key) == null || !before.get(key).equals(after.get(key))) return false;
        }
        if (!(before.get("diff_refs") instanceof Map<?, ?> refs)
            || !String.valueOf(refs.get("base_sha")).matches("(?i)[0-9a-f]{40,64}")
            || !String.valueOf(refs.get("start_sha")).matches("(?i)[0-9a-f]{40,64}")) return false;
        List<Map<String, Object>> initial = (List<Map<String, Object>>) before.get("changes");
        List<Map<String, Object>> recovered = (List<Map<String, Object>>) after.get("changes");
        if (initial == null || recovered == null || initial.size() != recovered.size()) return false;
        Set<List<Object>> initialFiles = initial.stream().map(file -> List.of(
            file.get("old_path"), file.get("new_path"), file.getOrDefault("new_file", false),
            file.getOrDefault("renamed_file", false), file.getOrDefault("deleted_file", false)))
            .collect(java.util.stream.Collectors.toSet());
        Set<List<Object>> recoveredFiles = recovered.stream().map(file -> List.of(
            file.get("old_path"), file.get("new_path"), file.getOrDefault("new_file", false),
            file.getOrDefault("renamed_file", false), file.getOrDefault("deleted_file", false)))
            .collect(java.util.stream.Collectors.toSet());
        return initialFiles.size() == initial.size() && recoveredFiles.size() == recovered.size()
            && initialFiles.equals(recoveredFiles);
    }

    public static boolean hasIncompleteMergeRequestDiff(Map<String, Object> review) {
        if (Boolean.TRUE.equals(review.get("overflow")) || Boolean.FALSE.equals(review.get("reviewComplete"))) return true;
        if (review.get("changes") instanceof List<?> changes) {
            return changes.stream().anyMatch(file -> file instanceof Map<?, ?> change
                && (Boolean.TRUE.equals(change.get("collapsed")) || Boolean.TRUE.equals(change.get("tooLarge"))));
        }
        return false;
    }

    public Map<String, Object> approveMergeRequest(
        String token,
        String gitlabUrl,
        long projectId,
        long mergeRequestIid,
        String reviewedSha
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/merge_requests/" + mergeRequestIid + "/approve";

        Map<String, Object> payload = Map.of("sha", requireCommitSha(reviewedSha));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(15))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    public Map<String, Object> createMergeRequest(
        String token,
        String gitlabUrl,
        long projectId,
        String sourceBranch,
        String targetBranch,
        String title,
        String description,
        boolean removeSourceBranch
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/merge_requests";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_branch", trimToNull(sourceBranch));
        payload.put("target_branch", trimToNull(targetBranch));
        payload.put("title", trimToNull(title));
        payload.put("description", trimToNull(description));
        payload.put("remove_source_branch", removeSourceBranch);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(20))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** MR 을 close 상태로 전환한다. */
    public void closeMergeRequest(String token, String gitlabUrl, long projectId, long mergeRequestIid)
            throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequestIid
            + "?state_event=close";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);
    }

    /** MR 에 코멘트(note)를 남긴다. */
    public void postMergeRequestNote(String token, String gitlabUrl, long projectId, long mergeRequestIid, String body)
            throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/merge_requests/" + mergeRequestIid + "/notes";
        String json = objectMapper.writeValueAsString(Map.of("body", body == null ? "" : body));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
    }

    public Map<String, Object> commitRepositoryFile(
        String token,
        String gitlabUrl,
        long projectId,
        String branch,
        String startBranch,
        String filePath,
        String content,
        String commitMessage
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/repository/commits";
        String normalizedFilePath = trimToNull(filePath);
        String normalizedBranch = trimToNull(branch);
        String normalizedStartBranch = trimToNull(startBranch);
        boolean fileExists = repositoryFileExists(token, gitlabUrl, projectId, normalizedFilePath, normalizedStartBranch);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action", fileExists ? "update" : "create");
        action.put("file_path", normalizedFilePath);
        action.put("content", content);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branch", normalizedBranch);
        if (normalizedStartBranch != null && normalizedStartBranch.matches("(?i)^[0-9a-f]{40,64}$")) {
            payload.put("start_sha", normalizedStartBranch);
        } else {
            payload.put("start_branch", normalizedStartBranch);
        }
        payload.put("commit_message", trimToNull(commitMessage));
        payload.put("actions", List.of(action));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(20))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /**
     * 저장소 파일을 원문 그대로 읽는다. 없으면 null.
     *
     * <p>{@code .gitlab-ci.yml} 을 화면에서 보고 고치기 위한 것이다. 지금까지 배포시스템은
     * 프로젝트 설정으로부터 CI 파일을 <b>만들어 주기만</b> 했다. 그래서 이미 CI 를 쓰고 있는
     * 저장소에서는 화면이 보여 주는 것과 실제로 도는 것이 다른데도 그 차이를 알 방법이 없었다.
     *
     * <p>{@code /files/{path}/raw} 를 쓴다 — base64 로 감싸 오는 쪽은 줄바꿈 처리에서
     * 원문이 미묘하게 달라질 수 있고, 우리는 사용자가 고칠 글자 그대로가 필요하다.
     */
    public String readRepositoryFile(
        String token,
        String gitlabUrl,
        long projectId,
        String filePath,
        String ref
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/repository/files/" + encodedPath + "/raw?ref=" + encodedRef;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(15))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;   // 아직 CI 파일이 없는 저장소 — 오류가 아니라 정상적인 출발점이다
        }
        ensureSuccess(response, true, false, 200);
        return response.body();
    }

    /** A bounded UTF-8 editor/manifest read. Only a genuine HTTP 404 means missing. */
    public String readRepositoryFileBounded(String token, String gitlabUrl, long projectId,
                                            String filePath, String ref, int maximumBytes) throws Exception {
        if (maximumBytes < 1 || maximumBytes > 1_048_576) throw new IllegalArgumentException("Invalid source read bound");
        String path = URLEncoder.encode(filePath, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(resolveBaseUrl(gitlabUrl)
                + "/api/v4/projects/" + projectId + "/repository/files/" + path + "/raw?ref=" + encodedRef))
            .timeout(Duration.ofSeconds(15)).header("PRIVATE-TOKEN", token).GET().build();
        HttpResponse<java.io.InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) throw new IllegalStateException("GitLab 파일 조회 실패 (HTTP " + response.statusCode() + ")");
            byte[] bytes = body.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) throw new IllegalArgumentException("파일이 편집·분석 한도보다 큽니다. 파일 크기를 확인해 주세요.");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }

    /**
     * 저장소의 한 디렉터리를 훑는다 — 탐색기처럼 폴더를 열어 들어가기 위한 것.
     *
     * <p>빌드 파일 경로·산출물 경로·Dockerfile 위치를 지금까지 손으로 치게 했다.
     * 그러려면 저장소 구조를 외우고 있어야 하고, 오타를 내도 저장은 되며
     * 그 사실은 빌드가 깨질 때에야 드러난다. 실제 트리를 보여 주고 고르게 한다.
     *
     * <p>{@code recursive=false} 로 한 단계씩만 읽는다. 큰 저장소를 통째로 받으면
     * 응답이 수 MB 가 되고 화면도 그만큼 느려진다.
     *
     * @param path 비우면 저장소 루트
     * @return {@code [{name, type(tree|blob), path}]} — 폴더 먼저, 그다음 이름순
     */
    public List<Map<String, Object>> listRepositoryTree(
        String token,
        String gitlabUrl,
        long projectId,
        String path,
        String ref
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        StringBuilder url = new StringBuilder(base)
            .append("/api/v4/projects/").append(projectId)
            .append("/repository/tree?per_page=100&ref=")
            .append(URLEncoder.encode(ref, StandardCharsets.UTF_8));
        if (path != null && !path.isBlank()) {
            url.append("&path=").append(URLEncoder.encode(path.trim(), StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .timeout(Duration.ofSeconds(15))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // 빈 저장소이거나 그런 경로가 없다 — 오류로 다루면 탐색기가 못 열린다.
            return List.of();
        }
        ensureSuccess(response, true, false, 200);

        List<Map<String, Object>> raw =
            objectMapper.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> node : raw) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", node.get("name"));
            item.put("type", node.get("type"));   // tree(폴더) · blob(파일)
            item.put("path", node.get("path"));
            out.add(item);
        }
        // 폴더를 위로 — 탐색기에서 눈이 먼저 가는 곳에 들어갈 것을 둔다.
        out.sort((a, b) -> {
            boolean aDir = "tree".equals(a.get("type"));
            boolean bDir = "tree".equals(b.get("type"));
            if (aDir != bDir) {
                return aDir ? -1 : 1;
            }
            return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
        });
        return out;
    }

    public boolean repositoryFileExists(
        String token,
        String gitlabUrl,
        long projectId,
        String filePath,
        String ref
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/repository/files/" + encodedPath
            + "?ref=" + encodedRef;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return false;
        }
        ensureSuccess(response, true, false, 200);
        return true;
    }

    public Map<String, Object> mergeMergeRequest(
        String token,
        String gitlabUrl,
        long projectId,
        long mergeRequestIid,
        String reviewedSha,
        boolean removeSourceBranch
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/merge_requests/" + mergeRequestIid + "/merge";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sha", requireCommitSha(reviewedSha));
        payload.put("should_remove_source_branch", removeSourceBranch);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(20))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 특정 pipeline의 job 목록. artifact 정본을 정확한 pipeline/job id로 찾을 때 사용한다. */
    public List<Map<String, Object>> getPipelineJobs(
        String token,
        String gitlabUrl,
        long projectId,
        long pipelineId
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/pipelines/" + pipelineId + "/jobs?per_page=100&include_retried=true";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);

        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(job -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", job.getOrDefault("id", 0));
            mapped.put("name", valueOrDefault(job.get("name"), ""));
            mapped.put("status", valueOrDefault(job.get("status"), ""));
            mapped.put("stage", valueOrDefault(job.get("stage"), ""));
            mapped.put("ref", valueOrDefault(job.get("ref"), ""));
            mapped.put("commitSha", valueOrDefault(job.get("commit") instanceof Map<?, ?> commit
                ? commit.get("id") : null, ""));
            mapped.put("retried", valueOrDefault(job.get("retried"), false));
            mapped.put("duration", valueOrDefault(job.get("duration"), 0));
            mapped.put("createdAt", valueOrDefault(job.get("created_at"), ""));
            mapped.put("finishedAt", valueOrDefault(job.get("finished_at"), ""));
            mapped.put("webUrl", valueOrDefault(job.get("web_url"), ""));
            return mapped;
        }).toList();
    }

    /** 사용자용 CI 장애 조사 로그. GitLab이 마스킹한 원문을 호출자가 추가 마스킹·길이 제한한다. */
    public String getJobTrace(
        String token, String gitlabUrl, long projectId, long jobId
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/jobs/" + jobId + "/trace";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(20)).header("PRIVATE-TOKEN", token).GET().build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);
        return response.body();
    }

    public Map<String, Object> retryPipeline(
        String token, String gitlabUrl, long projectId, long pipelineId
    ) throws Exception {
        return pipelineAction(token, gitlabUrl, projectId, pipelineId, "retry");
    }

    public Map<String, Object> cancelPipeline(
        String token, String gitlabUrl, long projectId, long pipelineId
    ) throws Exception {
        return pipelineAction(token, gitlabUrl, projectId, pipelineId, "cancel");
    }

    private Map<String, Object> pipelineAction(
        String token, String gitlabUrl, long projectId, long pipelineId, String action
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/pipelines/" + pipelineId + "/" + action;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(20)).header("PRIVATE-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** job archive 안의 단일 artifact 파일을 가져온다. */
    public String downloadJobArtifactFile(
        String token,
        String gitlabUrl,
        long projectId,
        long jobId,
        String artifactPath
    ) throws Exception {
        return downloadJobArtifactFile(token, gitlabUrl, projectId, jobId, artifactPath,
            GitlabCiContract.RELEASE_ARTIFACT_FILE.equals(artifactPath) ? 256 * 1024 : 16 * 1024);
    }

    public String downloadJobArtifactFile(String token, String gitlabUrl, long projectId,
                                         long jobId, String artifactPath, int maximumBytes) throws Exception {
        String normalizedPath = trimToNull(artifactPath);
        if (normalizedPath == null) {
            throw new IllegalArgumentException("GitLab artifact 파일 경로가 비어 있습니다.");
        }
        String base = resolveBaseUrl(gitlabUrl);
        String encodedPath = URLEncoder.encode(normalizedPath, StandardCharsets.UTF_8).replace("+", "%20");
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/jobs/" + jobId + "/artifacts/" + encodedPath;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(15))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, new BoundedArtifactBodyHandler(maximumBytes));
        ensureSuccess(response, true, true, 200);
        return response.body();
    }

    /** 프로젝트에 연결된 Runner 요약을 읽는다. tag/access_level은 목록 계약에 없으므로 상세 API에서 확인한다. */
    public List<Map<String, Object>> getProjectRunners(
        String token,
        String gitlabUrl,
        long projectId
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/runners?per_page=100";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);
        List<Map<String, Object>> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return raw.stream().map(runner -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", runner.getOrDefault("id", 0));
            mapped.put("description", valueOrDefault(runner.get("description"), ""));
            mapped.put("status", valueOrDefault(runner.get("status"), ""));
            mapped.put("online", valueOrDefault(runner.get("online"), false));
            mapped.put("paused", valueOrDefault(runner.get("paused"), false));
            return mapped;
        }).toList();
    }

    /** Runner 상세 API에서 보호 ref 전용 여부(access_level)를 읽는다. */
    public Map<String, Object> getRunnerDetails(String token, String gitlabUrl, long runnerId) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/runners/" + runnerId;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403) {
            throw new IllegalArgumentException(
                "GitLab Runner 상세 조회 권한이 없습니다. 토큰의 manage_runner scope와 프로젝트 Security Manager, Maintainer 또는 Owner 역할을 확인해 주세요. (HTTP 403)");
        }
        ensureSuccess(response, true, true, 200);
        Map<String, Object> runner = objectMapper.readValue(response.body(), new TypeReference<>() {});
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", valueOrDefault(runner.get("id"), runnerId));
        mapped.put("accessLevel", valueOrDefault(runner.get("access_level"), ""));
        mapped.put("status", valueOrDefault(runner.get("status"), ""));
        mapped.put("online", valueOrDefault(runner.get("online"), false));
        mapped.put("paused", valueOrDefault(runner.get("paused"), false));
        mapped.put("tagList", stringList(runner.get("tag_list")));
        return mapped;
    }

    /**
     * 파이프라인 생성 전에 ref가 브랜치인지 태그인지와 보호 상태를 확정한다.
     * 같은 이름의 브랜치와 태그가 모두 있으면 GitLab ref 해석 오인을 막기 위해 실패시킨다.
     */
    public RefProtection getRefProtection(
        String token,
        String gitlabUrl,
        long projectId,
        String ref
    ) throws Exception {
        String normalizedRef = trimToNull(ref);
        if (normalizedRef == null) {
            throw new IllegalArgumentException("GitLab ref가 비어 있습니다.");
        }

        String base = resolveBaseUrl(gitlabUrl);
        String encodedRef = URLEncoder.encode(normalizedRef, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> branch = getOptionalRepositoryRef(
            token, base + "/api/v4/projects/" + projectId + "/repository/branches/" + encodedRef);
        Map<String, Object> tag = getOptionalRepositoryRef(
            token, base + "/api/v4/projects/" + projectId + "/repository/tags/" + encodedRef);

        if (branch != null && tag != null) {
            throw new IllegalArgumentException(
                "GitLab ref '" + normalizedRef + "'와 같은 이름의 브랜치와 태그가 모두 존재합니다. 이름을 구분해 주세요.");
        }
        if (branch != null) {
            return new RefProtection(normalizedRef, "BRANCH", Boolean.TRUE.equals(branch.get("protected")));
        }
        if (tag != null) {
            return new RefProtection(normalizedRef, "TAG", Boolean.TRUE.equals(tag.get("protected")));
        }
        throw new IllegalArgumentException("GitLab ref '" + normalizedRef + "'를 브랜치 또는 태그에서 찾을 수 없습니다.");
    }

    private Map<String, Object> getOptionalRepositoryRef(String token, String apiUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        ensureSuccess(response, true, false, 200);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 값 자체는 내보내지 않고 필수 CI/CD 변수의 존재·보호 상태만 읽는다. */
    public List<Map<String, Object>> getProjectVariables(
        String token,
        String gitlabUrl,
        long projectId
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            String apiUrl = base + "/api/v4/projects/" + projectId + "/variables?per_page=100&page=" + page;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(10))
                .header("PRIVATE-TOKEN", token)
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("GitLab CI/CD 변수 조회 실패. 토큰 권한과 API 상태를 확인해 주세요. (HTTP "
                    + response.statusCode() + ")");
            }
            List<Map<String, Object>> raw;
            try {
                raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
                if (raw == null) throw new IllegalArgumentException();
            } catch (Exception ignored) {
                throw new IllegalArgumentException("GitLab CI/CD 변수 응답을 해석할 수 없습니다.");
            }
            for (Map<String, Object> variable : raw) {
                if (variable == null
                    || !(variable.get("key") instanceof String key) || key.isBlank()
                    || !(variable.get("protected") instanceof Boolean)
                    || !(variable.get("masked") instanceof Boolean)
                    || (variable.containsKey("hidden") && !(variable.get("hidden") instanceof Boolean))
                    || !(variable.get("variable_type") instanceof String type)
                    || !(type.equals("file") || type.equals("env_var"))
                    || !(variable.get("environment_scope") instanceof String scope) || scope.isBlank()) {
                    throw new IllegalArgumentException("GitLab CI/CD 변수 메타데이터가 누락되었거나 올바르지 않아 확인을 중단합니다.");
                }
                Map<String, Object> mapped = new LinkedHashMap<>();
                mapped.put("key", valueOrDefault(variable.get("key"), ""));
                mapped.put("variableType", valueOrDefault(variable.get("variable_type"), "env_var"));
                mapped.put("protected", valueOrDefault(variable.get("protected"), false));
                mapped.put("masked", valueOrDefault(variable.get("masked"), false));
                mapped.put("hidden", valueOrDefault(variable.get("hidden"), false));
                mapped.put("environmentScope", valueOrDefault(variable.get("environment_scope"), "*"));
                // GitLab redacts masked-and-hidden values to explicit JSON null.
                // Empty strings and missing value metadata are still unavailable.
                boolean redactedConfiguredValue = variable.containsKey("value") && variable.get("value") == null
                    && Boolean.TRUE.equals(variable.get("hidden")) && Boolean.TRUE.equals(variable.get("masked"));
                mapped.put("hasValue", (variable.get("value") instanceof String value && !value.isBlank())
                    || redactedConfiguredValue);
                result.add(mapped);
            }
            String nextPage = response.headers().firstValue("X-Next-Page").orElse(null);
            if (nextPage != null) {
                if (nextPage.isBlank()) return List.copyOf(result);
                if (!String.valueOf(page + 1).equals(nextPage.trim())) {
                    throw new IllegalArgumentException("GitLab CI/CD 변수 페이지 응답이 올바르지 않습니다.");
                }
            } else if (raw.size() < 100) {
                return List.copyOf(result);
            }
        }
        throw new IllegalArgumentException("GitLab CI/CD 변수가 조회 한도를 초과하여 전체 확인할 수 없습니다.");
    }

    /** Compares one expected File variable internally; no remote value is returned to callers. */
    public boolean matchesMavenSettingsFile(String token, long projectId, String expected) throws Exception {
        String url = resolveBaseUrl(null) + "/api/v4/projects/" + projectId
            + "/variables/MAVEN_SETTINGS_FILE?filter%5Benvironment_scope%5D=%2A";
        var response = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10)).header("PRIVATE-TOKEN", token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() != 200) throw new IllegalStateException("GitLab Maven 설정 확인 실패");
        Map<String, Object> value = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return "MAVEN_SETTINGS_FILE".equals(value.get("key")) && "file".equals(value.get("variable_type"))
            && "*".equals(value.get("environment_scope")) && Boolean.TRUE.equals(value.get("protected"))
            && Boolean.TRUE.equals(value.get("raw")) && expected.equals(value.get("value"));
    }

    /** 고정 CI 계약 변수만 변경한다. GitLab이 되돌려주는 값/오류 본문은 읽거나 전달하지 않는다. */
    public void upsertProjectVariable(String token, String gitlabUrl, long projectId, String key, String value) {
        GitlabCiVariablePolicy.validateValue(key, value);
        boolean dispatched = false;
        try {
            assertNonAdminSystemToken(token, gitlabUrl);
            boolean exists = getProjectVariables(token, gitlabUrl, projectId).stream()
                .anyMatch(variable -> key.equals(variable.get("key")) && "*".equals(variable.get("environmentScope")));
            String collection = resolveBaseUrl(gitlabUrl) + "/api/v4/projects/" + projectId + "/variables";
            String apiUrl = exists ? collection + "/" + key + "?filter%5Benvironment_scope%5D=%2A" : collection;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("key", key);
            payload.put("value", value);
            payload.put("variable_type", GitlabCiVariablePolicy.variableType(key));
            payload.put("protected", true);
            payload.put("masked", GitlabCiVariablePolicy.masked(key));
            payload.put("raw", true);
            payload.put("environment_scope", "*");
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(10)).header("PRIVATE-TOKEN", token)
                .header("Content-Type", "application/json")
                .method(exists ? "PUT" : "POST", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            dispatched = true;
            HttpResponse<Void> response = sendCiVariableMutation(request);
            checkCiVariableMutationStatus(response.statusCode(), exists ? 200 : 201);
        } catch (GitlabAdminSystemTokenException safe) {
            throw safe;
        } catch (GitlabCiVariableMutationException safe) {
            throw safe;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new GitlabCiVariableMutationException(dispatched);
        } catch (Exception ignored) {
            throw new GitlabCiVariableMutationException(dispatched);
        }
    }

    public void deleteProjectVariable(String token, String gitlabUrl, long projectId, String key) {
        GitlabCiVariablePolicy.requireKey(key);
        boolean dispatched = false;
        try {
            assertNonAdminSystemToken(token, gitlabUrl);
            String apiUrl = resolveBaseUrl(gitlabUrl) + "/api/v4/projects/" + projectId + "/variables/" + key
                + "?filter%5Benvironment_scope%5D=%2A";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(10)).header("PRIVATE-TOKEN", token).DELETE().build();
            dispatched = true;
            HttpResponse<Void> response = sendCiVariableMutation(request);
            checkCiVariableMutationStatus(response.statusCode(), 204);
        } catch (GitlabAdminSystemTokenException safe) {
            throw safe;
        } catch (GitlabCiVariableMutationException safe) {
            throw safe;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new GitlabCiVariableMutationException(dispatched);
        } catch (Exception ignored) {
            throw new GitlabCiVariableMutationException(dispatched);
        }
    }

    HttpResponse<Void> sendCiVariableMutation(HttpRequest request) throws java.io.IOException, InterruptedException {
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private void checkCiVariableMutationStatus(int actual, int expected) {
        if (actual == expected) return;
        // 프록시 timeout/5xx 등은 GitLab 적용 여부를 증명하지 않는다. 명시적인 클라이언트 거절만 FAIL.
        boolean rejected = actual == 400 || actual == 401 || actual == 403 || actual == 404
            || actual == 405 || actual == 409 || actual == 422 || actual == 429;
        throw new GitlabCiVariableMutationException(!rejected);
    }

    public Map<String, Object> getPipeline(String token, String gitlabUrl, long projectId, long pipelineId) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/pipelines/" + pipelineId;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    public Map<String, Object> triggerPipeline(
        String token,
        String gitlabUrl,
        long projectId,
        String ref,
        Map<String, String> variables
    ) throws Exception {
        assertNonAdminSystemToken(token, gitlabUrl);
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId + "/pipeline";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(15))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildPipelineBody(ref, variables)))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200, 201);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    public GitlabUserIdentity getAuthenticatedUserIdentity(String token, String gitlabUrl) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/user";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(8))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, false, 200);

        Map<String, Object> user = objectMapper.readValue(response.body(), new TypeReference<>() {});
        Object rawId = user.get("id");
        Object rawUsername = user.get("username");
        if (!(rawId instanceof Number id) || id.longValue() <= 0
            || !(rawUsername instanceof String username) || username.isBlank()) {
            throw new IllegalStateException("GitLab PAT 계정의 ID와 사용자명을 확인할 수 없습니다.");
        }
        return new GitlabUserIdentity(id.longValue(), username, Boolean.TRUE.equals(user.get("is_admin")));
    }

    /** Raised before any MR mutation when the reviewer is the integration account. */
    public static final class SharedApprovalActorException extends IllegalArgumentException {
        public SharedApprovalActorException() {
            super("현재 GitLab 계정이 Deploy 시스템 연동 계정과 같습니다. MR 승인·반려에는 연동 계정과 다른 개인 GitLab 계정을 사용해 주세요.");
        }
    }

    /** An approval may not be attributed to the same GitLab account as the shared system PAT. */
    public void assertDistinctApprovalActor(String personalToken, GitlabUserIdentity reviewer) {
        String systemToken = trimToNull(settingService.get("gitlab.token", ""));
        if (systemToken == null) return;
        if (systemToken.equals(personalToken)) {
            throw new SharedApprovalActorException();
        }
        try {
            GitlabUserIdentity systemActor = getAuthenticatedUserIdentity(systemToken, null);
            if (systemActor.id() == reviewer.id()) {
                throw new SharedApprovalActorException();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GitLab 시스템 계정을 확인할 수 없어 MR 승인을 중단했습니다.");
        }
    }

    /** Free/CE-compatible approval state; `approved` is intentionally ignored. */
    public Set<Long> getMergeRequestApproverIds(
        String token, String gitlabUrl, long projectId, long mergeRequestIid
    ) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/projects/" + projectId
            + "/merge_requests/" + mergeRequestIid + "/approvals";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10)).header("PRIVATE-TOKEN", token).GET().build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, true, 200);
        Map<String, Object> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        if (!(raw.get("approved_by") instanceof List<?> approvedBy)) {
            throw new IllegalStateException("GitLab MR 승인자 목록을 확인할 수 없습니다.");
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (Object approval : approvedBy) {
            if (!(approval instanceof Map<?, ?> row)
                || !(row.get("user") instanceof Map<?, ?> user)
                || !(user.get("id") instanceof Number id)
                || id.longValue() <= 0) {
                throw new IllegalStateException("GitLab MR 승인자 ID를 확인할 수 없습니다.");
            }
            userIds.add(id.longValue());
        }
        return Set.copyOf(userIds);
    }

    /** Shared PATs may perform mutations only as a dedicated non-admin service account. */
    private void assertNonAdminSystemToken(String token, String gitlabUrl) throws Exception {
        String systemToken = trimToNull(settingService.get("gitlab.token", ""));
        if (systemToken == null || !systemToken.equals(token)) return;
        GitlabUserIdentity actor = getAuthenticatedUserIdentity(token, gitlabUrl);
        if (actor.admin() || "root".equalsIgnoreCase(actor.username())) {
            throw new GitlabAdminSystemTokenException();
        }
    }

    public Map<String, Object> testConnection(String token, String gitlabUrl) throws Exception {
        String base = resolveBaseUrl(gitlabUrl);
        String apiUrl = base + "/api/v4/user";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(8))
            .header("PRIVATE-TOKEN", token)
            .GET()
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, true, false, 200);
        Map<String, Object> user = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return Map.of(
            "username", user.getOrDefault("username", ""),
            "name", user.getOrDefault("name", "")
        );
    }

    private String resolveBaseUrl(String gitlabUrl) {
        if (gitlabUrl == null || gitlabUrl.isBlank()) {
            // 서버측 API 호출 전용 내부 주소(옵션) — 라우터가 공인IP 헤어핀을 막는 환경 대응.
            // 사용자 브라우저용 링크는 gitlab.url(공인)을 그대로 쓴다.
            String apiOverride = settingService.get("gitlab.api-url", "").trim();
            if (!apiOverride.isBlank()) {
                return apiOverride.replaceAll("/$", "");
            }
        }
        String base = (gitlabUrl == null || gitlabUrl.isBlank()) ? getSystemGitlabUrl() : gitlabUrl.replaceAll("/$", "");
        if (base.isBlank()) {
            throw new IllegalArgumentException("GitLab URL이 설정되어 있지 않습니다. 시스템 설정에서 GitLab URL을 먼저 등록해 주세요.");
        }
        return base;
    }

    private String buildPipelineBody(String ref, Map<String, String> variables) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("ref", ref);

            if (variables != null && !variables.isEmpty()) {
                java.util.List<Map<String, String>> pipelineVariables = new java.util.ArrayList<>();
                variables.forEach((key, value) -> {
                    if (key == null || key.isBlank() || value == null || value.isBlank()) {
                        return;
                    }
                    pipelineVariables.add(Map.of(
                        "key", key,
                        "value", value
                    ));
                });
                if (!pipelineVariables.isEmpty()) {
                    payload.put("variables", pipelineVariables);
                }
            }

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize GitLab pipeline payload.", e);
        }
    }

    private void ensureSuccess(
        HttpResponse<String> response,
        boolean checkUnauthorized,
        boolean checkNotFound,
        int... successStatuses
    ) {
        if (checkUnauthorized && response.statusCode() == 401) {
            throw new IllegalArgumentException("GitLab 인증에 실패했습니다. 토큰을 다시 확인해 주세요. (HTTP 401)");
        }
        if (checkNotFound && response.statusCode() == 404) {
            throw new IllegalArgumentException("요청한 GitLab 대상을 찾을 수 없습니다. (HTTP 404)");
        }
        for (int successStatus : successStatuses) {
            if (response.statusCode() == successStatus) {
                return;
            }
        }
        throw new IllegalStateException(
            "GitLab 호출 실패: HTTP " + response.statusCode() + buildBodySuffix(response.body())
        );
    }

    private String buildBodySuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 160) {
            compact = compact.substring(0, 160) + "...";
        }
        return " / " + compact;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requireCommitSha(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || !normalized.matches("(?i)^[0-9a-f]{40,64}$")) {
            throw new IllegalArgumentException("A full reviewed Git commit SHA is required.");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private Object valueOrDefault(Object value, Object defaultValue) {
        return value != null ? value : defaultValue;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(item -> item != null && !String.valueOf(item).isBlank())
            .map(item -> String.valueOf(item).trim())
            .toList();
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return defaultValue;
        }
        return switch (normalized.toLowerCase()) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }
}
