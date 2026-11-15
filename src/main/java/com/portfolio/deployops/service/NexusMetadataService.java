package com.portfolio.deployops.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.domain.Project;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NexusMetadataService {

    public enum TagDeleteResult {
        DELETED,
        ABSENT,
        MOVED
    }

    private static final String DOCKER_MANIFEST_ACCEPT =
        "application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json,"
            + "application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final Pattern NEXT_PAGE_LINK = Pattern.compile(
        "<([^>]+)>\\s*;\\s*rel\\s*=\\s*(?:\"next\"|next)(?=\\s*(?:;|,|$))");

    private final ObjectMapper objectMapper;
    private final SystemSettingService settingService;

    /**
     * 이미지 태그 목록을 생성일자(이미지 config 의 created)와 함께 반환한다.
     * 각 항목: {@code {tag, createdAt}} — createdAt 은 조회 실패 시 null.
     * 생성일자 최신순으로 정렬하며, 날짜를 알 수 없는 항목은 뒤로 보낸다.
     */
    public List<Map<String, Object>> getDockerTags(Project project) throws Exception {
        String repositoryPath = resolveRepositoryPath(project);
        String registryBase = resolveRegistryBase(project);
        String url = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath) + "/tags/list?n=50";

        Set<String> tagNames = new LinkedHashSet<>();
        Set<URI> visited = new LinkedHashSet<>();
        URI firstPage = URI.create(url);
        URI page = firstPage;
        while (page != null) {
            if (!visited.add(page) || visited.size() > 1000) {
                throw new IllegalStateException("Nexus 태그 페이지가 반복되거나 조회 한도를 초과했습니다.");
            }
            HttpResponse<String> response = HTTP_CLIENT.send(
                baseRequest(page.toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                if (response.statusCode() == 403 && response.body().toLowerCase(Locale.ROOT)
                        .contains("end user license agreement")) {
                    throw new IllegalArgumentException(
                        "Nexus 사용 조건 동의가 완료되지 않았습니다. Nexus 초기 설정에서 내용을 확인한 뒤 다시 검사해 주세요.");
                }
                throw new IllegalArgumentException("Nexus Docker Registry 인증에 실패했습니다. 연동 계정을 확인해 주세요.");
            }
            if (response.statusCode() == 404) {
                throw new IllegalArgumentException("Nexus Docker Registry에서 이미지 저장소를 찾을 수 없습니다.");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Nexus Docker Registry 응답 오류: HTTP " + response.statusCode());
            }
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (payload.get("tags") instanceof List<?> tags) {
                tags.stream().filter(String.class::isInstance).map(String.class::cast).forEach(tagNames::add);
            }
            page = nextTagPage(firstPage, page, response.headers().allValues("Link"));
        }

        // 태그별 생성일자를 병렬로 조회(best-effort). 실패한 항목은 createdAt=null 로 둔다.
        List<CompletableFuture<Map<String, Object>>> futures = tagNames.stream()
            .map(tag -> CompletableFuture.supplyAsync(() -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tag", tag);
                entry.put("createdAt", fetchCreatedAt(registryBase, repositoryPath, tag));
                return entry;
            }))
            .toList();

        List<Map<String, Object>> result = new ArrayList<>(futures.stream()
            .map(CompletableFuture::join)
            .toList());

        result.sort((a, b) -> compareCreatedDesc(
            (String) a.get("createdAt"), (String) b.get("createdAt")));
        return result;
    }

    private URI nextTagPage(URI firstPage, URI currentPage, List<String> links) {
        URI next = null;
        for (String link : links) {
            Matcher matcher = NEXT_PAGE_LINK.matcher(link);
            if (matcher.find()) {
                URI candidate = currentPage.resolve(matcher.group(1));
                // Registry 계정이 다른 호스트나 API 경로로 전달되지 않도록 Link를 제한한다.
                if (!sameOrigin(firstPage, candidate) || !firstPage.getRawPath().equals(candidate.getRawPath())
                        || candidate.getUserInfo() != null || candidate.getFragment() != null || next != null) {
                    throw new IllegalStateException("Nexus 태그의 다음 페이지 주소가 올바르지 않습니다.");
                }
                next = candidate;
            } else if (!link.isBlank()) {
                throw new IllegalStateException("Nexus 태그의 다음 페이지 Link 형식을 확인할 수 없습니다.");
            }
        }
        return next;
    }

    /** 이미지 manifest -> config blob 의 created 타임스탬프를 조회(best-effort, 실패 시 null). */
    private String fetchCreatedAt(String registryBase, String repositoryPath, String tag) {
        try {
            String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
            String manifestUrl = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath)
                + "/manifests/" + encodedTag;
            HttpResponse<String> manifestResponse = HTTP_CLIENT.send(
                baseRequest(manifestUrl).GET().header("Accept", DOCKER_MANIFEST_ACCEPT).build(),
                HttpResponse.BodyHandlers.ofString());
            if (manifestResponse.statusCode() != 200) {
                return null;
            }
            Map<String, Object> manifest = objectMapper.readValue(
                manifestResponse.body(), new TypeReference<>() {});
            // buildkit/멀티아치는 OCI index(매니페스트 목록)로 오므로 플랫폼 매니페스트로 한 번 더 해석한다.
            manifest = resolvePlatformManifest(registryBase, repositoryPath, manifest);
            if (manifest == null || !(manifest.get("config") instanceof Map<?, ?> config)) {
                return null;
            }
            Object digest = config.get("digest");
            if (digest == null) {
                return null;
            }
            // digest 는 'sha256:...' 형태이며 경로 세그먼트에 그대로 사용 가능하다(인코딩 시 깨짐).
            String blobUrl = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath)
                + "/blobs/" + digest;
            HttpResponse<String> blobResponse = HTTP_CLIENT.send(
                baseRequest(blobUrl).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (blobResponse.statusCode() != 200) {
                return null;
            }
            Map<String, Object> blob = objectMapper.readValue(
                blobResponse.body(), new TypeReference<>() {});
            Object created = blob.get("created");
            return created == null ? null : String.valueOf(created);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 매니페스트가 OCI index/manifest list 면 실제 플랫폼 매니페스트(linux, amd64 우선)를 조회해 돌려준다.
     * buildkit 의 attestation 항목(architecture=unknown)은 제외. 단일 매니페스트면 그대로 반환.
     */
    private Map<String, Object> resolvePlatformManifest(String registryBase, String repositoryPath,
                                                        Map<String, Object> manifest) {
        if (manifest == null || manifest.get("config") instanceof Map<?, ?>) {
            return manifest;
        }
        if (!(manifest.get("manifests") instanceof List<?> entries)) {
            return null;
        }
        String preferred = null;
        String fallback = null;
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> m) || m.get("digest") == null) {
                continue;
            }
            String arch = m.get("platform") instanceof Map<?, ?> p
                ? String.valueOf(p.get("architecture")) : null;
            if ("unknown".equals(arch)) {
                continue;
            }
            if (fallback == null) {
                fallback = String.valueOf(m.get("digest"));
            }
            if ("amd64".equals(arch)) {
                preferred = String.valueOf(m.get("digest"));
                break;
            }
        }
        String target = preferred != null ? preferred : fallback;
        if (target == null) {
            return null;
        }
        try {
            String url = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath) + "/manifests/" + target;
            HttpResponse<String> response = HTTP_CLIENT.send(
                baseRequest(url).GET().header("Accept", DOCKER_MANIFEST_ACCEPT).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Map<String, Object> resolved = objectMapper.readValue(response.body(), new TypeReference<>() {});
            return resolved.get("config") instanceof Map<?, ?> ? resolved : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** createdAt(ISO-8601) 최신순 비교. 파싱 실패/null 은 가장 뒤로. */
    private int compareCreatedDesc(String left, String right) {
        java.time.Instant leftAt = parseInstant(left);
        java.time.Instant rightAt = parseInstant(right);
        if (leftAt == null && rightAt == null) {
            return 0;
        }
        if (leftAt == null) {
            return 1;
        }
        if (rightAt == null) {
            return -1;
        }
        return rightAt.compareTo(leftAt);
    }

    private java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value.trim());
        } catch (Exception e) {
            try {
                // buildkit 이미지의 created 는 '+09:00' 같은 오프셋 표기도 온다.
                return java.time.OffsetDateTime.parse(value.trim()).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public Map<String, Object> resolveManifest(Project project, String tag) throws Exception {
        String repositoryPath = resolveRepositoryPath(project);
        String registryBase = resolveRegistryBase(project);
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
        String manifestUrl = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath) + "/manifests/" + encodedTag;

        String digest = headManifestDigest(manifestUrl);
        if (digest == null) {
            throw new IllegalArgumentException("선택한 이미지 태그를 Nexus에서 찾을 수 없습니다.");
        }
        return Map.of(
            "repositoryPath", repositoryPath,
            "tag", tag,
            "digest", digest
        );
    }

    /**
     * Nexus blob store 사용량을 조회한다(산출물 저장소 용량 카드).
     *
     * <p>3망 구조에서 Nexus 와 배포시스템은 둘 다 배포망(deploy01)에 있어 직접 조회할 수 있다.
     * 다른 망의 레지스트리를 여기로 가리키면 망분리가 깨지므로 주소를 바꾸지 말 것.
     *
     * <p>REST {@code /service/rest/v1/blobstores} 는 관리 API 라서 {@code nexus.user} 계정에
     * {@code nx-blobstores-read} 권한이 있어야 한다.
     *
     * @return {@code {stores:[…], totalSizeInBytes, availableSpaceInBytes, blobCount, usagePercent}}
     *         — 값을 알 수 없으면 -1.
     */
    public Map<String, Object> getStorageStatus() throws Exception {
        String url = requireNexusUrl() + "/service/rest/v1/blobstores";

        HttpResponse<String> response = HTTP_CLIENT.send(
            baseRequest(url).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalArgumentException(
                "Nexus 관리 API 접근이 거부되었습니다. 통합설정의 Nexus 계정에 blobstore 조회 권한(nx-blobstores-read)을 부여하세요.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Nexus 저장소 조회 실패: HTTP " + response.statusCode());
        }

        List<Map<String, Object>> raw =
            objectMapper.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() { });

        List<Map<String, Object>> stores = new ArrayList<>();
        long totalSize = 0L;
        long blobCount = 0L;
        long available = -1L;

        for (Map<String, Object> store : raw) {
            long size = asLong(store.get("totalSizeInBytes"));
            long free = asLong(store.get("availableSpaceInBytes"));
            long blobs = asLong(store.get("blobCount"));

            if (size > 0) {
                totalSize += size;
            }
            if (blobs > 0) {
                blobCount += blobs;
            }
            // 여러 blob store 가 같은 파일시스템을 공유할 수 있어 여유 공간은 합산하지 않고
            // 가장 빠듯한 값을 대표로 쓴다(합산하면 실제보다 낙관적으로 보인다).
            if (free >= 0) {
                available = (available < 0) ? free : Math.min(available, free);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", store.get("name"));
            item.put("type", store.get("type"));
            item.put("unavailable", Boolean.TRUE.equals(store.get("unavailable")));
            item.put("blobCount", blobs);
            item.put("totalSizeInBytes", size);
            item.put("availableSpaceInBytes", free);
            stores.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stores", stores);
        result.put("totalSizeInBytes", totalSize);
        result.put("availableSpaceInBytes", available);
        result.put("blobCount", blobCount);
        // 사용률 = 사용량 / (사용량 + 여유). Nexus 가 파티션 전체 크기를 주지 않아 이렇게 근사한다.
        result.put("usagePercent", available >= 0 && (totalSize + available) > 0
            ? Math.round(totalSize * 1000.0 / (totalSize + available)) / 10.0
            : -1);
        return result;
    }

    /**
     * Probe the Docker Registry v2 connector used by CI and deployment.
     * A TCP connection (or a healthy Nexus management API) does not prove that
     * the registry is usable: the Community Edition EULA can still block /v2/.
     * A 401 Bearer challenge proves only protocol reachability, not repository
     * push/pull permissions, so keep that state visible as WARN.
     */
    public Map<String, Object> probeRegistryEndpoint() {
        long started = System.nanoTime();
        try {
            if (settingService.get("nexus.url", "").isBlank()
                    || settingService.get("nexus.registry", "").isBlank()) {
                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                return registryProbeResult("UNCONFIGURED",
                    "Nexus 관리 주소와 Docker Registry 주소를 먼저 설정해 주세요.", latencyMs);
            }
            URI endpoint = configuredConnector().resolve("/v2/");
            HttpRequest request = baseRequest(endpoint.toString())
                .timeout(Duration.ofMillis(2500))
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            boolean dockerRegistry = response.headers().firstValue("Docker-Distribution-API-Version")
                .orElse("").equalsIgnoreCase("registry/2.0");
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() == 403 && body.toLowerCase(Locale.ROOT)
                    .contains("end user license agreement")) {
                return registryProbeResult("BROKEN",
                    "Nexus 사용 조건 동의가 완료되지 않아 Docker Registry가 요청을 차단합니다.", latencyMs);
            }
            if (response.statusCode() == 429) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse("").trim();
                String retryHint = retryAfter.matches("\\d{1,5}")
                    ? " Retry-After: " + retryAfter + "초 후 다시 확인하세요."
                    : " Retry-After 응답 헤더가 있으면 그 시간 이후 다시 확인하세요.";
                return registryProbeResult("BROKEN",
                    "Docker Registry endpoint 또는 중간 프록시·게이트웨이가 요청을 일시 제한했습니다 (HTTP 429). 반복 확인을 멈추고 관리자에게 Nexus 계정·요청 제한과 프록시·게이트웨이 로그를 확인하도록 요청하세요."
                        + retryHint,
                    latencyMs);
            }
            boolean anonymousAccess = response.statusCode() == 200;
            boolean bearerChallenge = response.statusCode() == 401 && response.headers()
                .firstValue("WWW-Authenticate")
                .map(value -> value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()))
                .orElse(false);
            if (dockerRegistry && (anonymousAccess || bearerChallenge)) {
                return registryProbeResult("WARN",
                    "Docker Registry v2 endpoint에 연결됐습니다. CI push 및 프로젝트별 pull 권한은 별도 확인이 필요합니다.",
                    latencyMs);
            }
            return registryProbeResult("BROKEN",
                "Docker Registry v2 endpoint를 사용할 수 없습니다 (HTTP " + response.statusCode() + ").", latencyMs);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            return registryProbeResult("BROKEN",
                "Docker Registry 연결 확인 실패 — " + e.getClass().getSimpleName(), latencyMs);
        }
    }

    private Map<String, Object> registryProbeResult(String state, String detail, long latencyMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("detail", detail);
        result.put("latencyMs", latencyMs);
        return result;
    }

    /**
     * Nexus 에 만들어져 있는 저장소 목록.
     *
     * <p>왜 필요한가 — 프로젝트를 만들 때 Nexus 저장소 이름을 <b>손으로 치게</b> 되어 있었다.
     * 그러려면 Nexus 에 먼저 들어가 무엇이 있는지 보고 와야 하고, 오타를 내도 저장은 되며
     * 그 사실은 배포가 실패할 때에야 드러난다. 있는 것을 골라 쓰게 한다.
     *
     * @param format {@code docker} · {@code maven2} 등. 비우면 전부.
     */
    public List<Map<String, Object>> listRepositories(String format) throws Exception {
        String url = requireNexusUrl() + "/service/rest/v1/repositories";
        HttpResponse<String> response = HTTP_CLIENT.send(
            baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalArgumentException(
                "Nexus 관리 API 접근이 거부되었습니다. 연동 설정의 Nexus 계정에 "
                + "저장소 조회 권한(nx-repository-admin-*-*-read)을 부여하세요.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Nexus 저장소 목록 조회 실패: HTTP " + response.statusCode());
        }

        List<Map<String, Object>> raw =
            objectMapper.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() { });

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> repo : raw) {
            String repoFormat = String.valueOf(repo.get("format"));
            if (format != null && !format.isBlank() && !format.equalsIgnoreCase(repoFormat)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", repo.get("name"));
            item.put("format", repoFormat);
            item.put("type", repo.get("type"));      // hosted · proxy · group
            item.put("url", repo.get("url"));
            if ("docker".equals(repoFormat) || "maven2".equals(repoFormat)) {
                try {
                    Map<String, Object> detail = repositoryDetails(repoFormat,
                        String.valueOf(repo.get("type")), String.valueOf(repo.get("name")));
                    if ("docker".equals(repoFormat)) {
                        applyDockerEndpoint(item, detail);
                    } else if (detail.get("maven") instanceof Map<?, ?> maven) {
                        item.put("versionPolicy", maven.get("versionPolicy"));
                    }
                } catch (Exception e) {
                    item.put("unavailableReason", "저장소 상세 설정을 확인할 수 없습니다. Nexus 조회 권한과 연결을 확인하세요.");
                }
            }
            out.add(item);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    /**
     * 저장소를 Nexus 에 새로 만든다.
     *
     * <p>없으면 Nexus 콘솔에 따로 들어가 만들어 오라고 하는 대신 여기서 끝낸다 —
     * 배포에 필요한 준비를 배포시스템 밖에서 하게 만들면, 그 단계는 문서로만 남고
     * 결국 아무도 정확히 재현하지 못한다.
     *
     * @param format {@code docker} 또는 {@code maven2}
     * @param name   저장소 이름
     * @param dockerHttpPort docker 형식일 때 현재 연동 설정에 지정한 HTTP 커넥터 포트
     */
    public Map<String, Object> createHostedRepository(String format, String name, Integer dockerHttpPort)
            throws Exception {
        return createHostedRepository(format, name, dockerHttpPort, "RELEASE");
    }

    /**
     * 승인 시점에 다시 확인한 manifest digest만 삭제한다. 태그를 DELETE에 직접 쓰지 않아
     * 승인 대기 중 태그가 다른 이미지로 이동해도 새 이미지를 지우지 않는다.
     */
    public void deleteManifest(Project project, String digest) throws Exception {
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("삭제할 이미지 digest 형식이 올바르지 않습니다.");
        }
        String repositoryPath = resolveRepositoryPath(project);
        String registryBase = resolveRegistryBase(project);
        String url = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath) + "/manifests/" + digest;
        // Nexus가 202를 반환하고도 첫 DELETE에서 manifest를 그대로 노출하는 경우가 있다.
        // 성공 코드를 완료 증거로 쓰지 않고, 정확한 digest를 HEAD로 재확인한 뒤 한 번만
        // 같은 idempotent DELETE를 반복한다. 그래도 남으면 상위 계층이 RECONCILING으로 남긴다.
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> response = HTTP_CLIENT.send(
                baseRequest(url).DELETE().header("Accept", DOCKER_MANIFEST_ACCEPT).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalArgumentException("Nexus 이미지 삭제 권한이 없습니다. 전용 정리 계정 권한을 확인하세요.");
            }
            if (response.statusCode() != 202 && response.statusCode() != 200 && response.statusCode() != 404) {
                throw new IllegalStateException("Nexus manifest 삭제 실패: HTTP " + response.statusCode());
            }
            if (!manifestExists(project, digest)) {
                return;
            }
        }
        throw new java.io.IOException("Nexus가 삭제를 수락했지만 승인 digest가 계속 조회됩니다.");
    }

    /**
     * Nexus 3에서 digest DELETE 뒤에도 남을 수 있는 태그 별칭을 정리한다.
     * DELETE 직전에 태그가 승인된 digest를 가리키는지 다시 확인하며, 다른 digest로
     * 이동한 태그는 절대 삭제하지 않는다.
     */
    public TagDeleteResult deleteManifestTag(Project project, String tag, String expectedDigest) throws Exception {
        if (tag == null || tag.isBlank() || tag.length() > 255) {
            throw new IllegalArgumentException("삭제할 이미지 태그 형식이 올바르지 않습니다.");
        }
        if (expectedDigest == null || !expectedDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("삭제할 이미지 digest 형식이 올바르지 않습니다.");
        }
        String repositoryPath = resolveRepositoryPath(project);
        String registryBase = resolveRegistryBase(project);
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
        String url = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath) + "/manifests/" + encodedTag;

        String currentDigest = headManifestDigest(url);
        if (currentDigest == null) {
            return TagDeleteResult.ABSENT;
        }
        requireVerifiableDigest(currentDigest);
        if (!expectedDigest.equalsIgnoreCase(currentDigest)) {
            return TagDeleteResult.MOVED;
        }

        HttpResponse<String> response = HTTP_CLIENT.send(
            baseRequest(url).DELETE().header("Accept", DOCKER_MANIFEST_ACCEPT).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalArgumentException("Nexus 이미지 삭제 권한이 없습니다. 전용 정리 계정 권한을 확인하세요.");
        }
        if (response.statusCode() != 202 && response.statusCode() != 200 && response.statusCode() != 404) {
            throw new IllegalStateException("Nexus 태그 manifest 삭제 실패: HTTP " + response.statusCode());
        }

        String remainingDigest = headManifestDigest(url);
        if (remainingDigest == null) {
            return TagDeleteResult.DELETED;
        }
        requireVerifiableDigest(remainingDigest);
        if (!expectedDigest.equalsIgnoreCase(remainingDigest)) {
            return TagDeleteResult.MOVED;
        }
        throw new java.io.IOException("Nexus가 삭제를 수락했지만 승인 태그가 계속 같은 digest를 가리킵니다.");
    }

    private boolean manifestExists(Project project, String digest) throws Exception {
        String repositoryPath = resolveRepositoryPath(project);
        String registryBase = resolveRegistryBase(project);
        String url = registryBase + "/v2/" + encodeRepositoryPath(repositoryPath) + "/manifests/" + digest;
        HttpResponse<Void> response = HTTP_CLIENT.send(
            baseRequest(url).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("Accept", DOCKER_MANIFEST_ACCEPT).build(),
            HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() == 200) return true;
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalArgumentException("Nexus 이미지 삭제 결과 확인 권한이 없습니다.");
        }
        throw new IllegalStateException("Nexus manifest 삭제 결과 확인 실패: HTTP " + response.statusCode());
    }

    private String headManifestDigest(String url) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
            baseRequest(url).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("Accept", DOCKER_MANIFEST_ACCEPT).build(),
            HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalArgumentException("Nexus Docker Registry 인증에 실패했습니다. 연동 계정을 확인해 주세요.");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Nexus Docker Registry 응답 오류: HTTP " + response.statusCode());
        }
        return response.headers().firstValue("Docker-Content-Digest").orElse("");
    }

    private void requireVerifiableDigest(String digest) {
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException("Nexus가 검증 가능한 manifest digest를 반환하지 않았습니다.");
        }
    }

    public Map<String, Object> createHostedRepository(String format, String name, Integer dockerHttpPort,
                                                     String versionPolicy) throws Exception {
        String type = "docker".equalsIgnoreCase(format) ? "docker"
            : "maven2".equalsIgnoreCase(format) ? "maven" : "raw".equalsIgnoreCase(format) ? "raw" : null;
        if (type == null) {
            throw new IllegalArgumentException("지원하지 않는 저장소 형식입니다: " + format);
        }
        if (name == null || !name.matches("[A-Za-z0-9._\\-]{1,60}")) {
            // Nexus 가 거절하는 이름을 먼저 걸러 준다 — 그쪽 오류 메시지는 원인을 알기 어렵다.
            throw new IllegalArgumentException(
                "저장소 이름은 영문·숫자와 . _ - 만 쓸 수 있습니다(60자 이내).");
        }

        String policy = versionPolicy == null ? "RELEASE" : versionPolicy.trim().toUpperCase(java.util.Locale.ROOT);
        if ("maven".equals(type) && !Set.of("RELEASE", "SNAPSHOT").contains(policy)) {
            throw new IllegalArgumentException("Maven 버전 정책은 RELEASE 또는 SNAPSHOT이어야 합니다.");
        }
        if ("docker".equals(type)) {
            URI connector = configuredConnector();
            if (!"http".equals(connector.getScheme()) || dockerHttpPort == null
                    || dockerHttpPort < 1 || dockerHttpPort > 65535 || dockerHttpPort != effectivePort(connector)) {
                throw new IllegalArgumentException("새 Docker 저장소는 연동 설정에서 지정한 HTTP 커넥터 포트로만 만들 수 있습니다. "
                    + "추가 포트는 Nexus 컨테이너 게시·방화벽·연동 주소를 먼저 구성해야 합니다.");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("online", true);
        body.put("storage", Map.of(
            "blobStoreName", "default",
            "strictContentTypeValidation", true,
            "writePolicy", "docker".equals(type) || ("maven".equals(type) && "SNAPSHOT".equals(policy))
                ? "ALLOW" : "ALLOW_ONCE"));

        if ("docker".equals(type)) {
            Map<String, Object> docker = new LinkedHashMap<>();
            docker.put("v1Enabled", false);
            docker.put("forceBasicAuth", true);
            docker.put("httpPort", dockerHttpPort);
            body.put("docker", docker);
        } else if ("maven".equals(type)) {
            // Maven 좌표 정책은 컨테이너 배포 태그와 별개로 정식/스냅샷 용도에 맞춘다.
            body.put("maven", Map.of("versionPolicy", policy, "layoutPolicy", "STRICT"));
        }

        String url = requireNexusUrl() + "/service/rest/v1/repositories/" + type + "/hosted";
        HttpResponse<String> response = HTTP_CLIENT.send(
            baseRequest(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalArgumentException(
                "저장소를 만들 권한이 없습니다. Nexus 계정에 nx-repository-admin-*-*-add 권한이 필요합니다.");
        }
        if (response.statusCode() == 400) {
            throw new IllegalArgumentException(
                "Nexus 가 요청을 거절했습니다. 같은 이름이 이미 있거나 포트가 사용 중일 수 있습니다.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("저장소 생성 실패: HTTP " + response.statusCode());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("format", "maven".equals(type) ? "maven2" : type);
        result.put("type", "hosted");
        result.put("dockerHttpPort", dockerHttpPort);
        if ("docker".equals(type)) {
            // 생성은 완료됐어도 컨테이너 포트 게시까지 수행한 것으로 표시하지 않는다.
            try {
                applyDockerEndpoint(result, repositoryDetails("docker", "hosted", name));
            } catch (Exception e) {
                result.put("unavailableReason", "저장소는 생성됐지만 커넥터 가용성을 확인하지 못했습니다. 포트 게시와 연결을 확인하세요.");
            }
        } else {
            result.put("versionPolicy", policy);
        }
        return result;
    }

    public String dependencyBaseUrl() { return requireNexusUrl(); }

    /** Only generated Runner settings use this route; management/upload API calls keep nexus.url. */
    public String runnerMavenBaseUrl() {
        return NexusMavenEndpoint.resolve(settingService.get("nexus.maven.base-url", ""),
            settingService.get("nexus.url", ""));
    }

    public void assertDependencyRepository(String repository, String format) throws Exception {
        NexusDependencyPolicy.repository(repository);
        NexusDependencyPolicy.format(format);
        boolean hosted = listRepositories(format).stream().anyMatch(r ->
            repository.equals(r.get("name")) && "hosted".equals(r.get("type")));
        if (!hosted) throw new IllegalArgumentException("선택한 형식의 hosted 저장소가 아닙니다.");
        Map<String,Object> details = repositoryDetails(format,"hosted",repository);
        if (!(details.get("storage") instanceof Map<?,?> storage)
            || !"ALLOW_ONCE".equalsIgnoreCase(String.valueOf(storage.get("writePolicy")))) {
            throw new IllegalArgumentException("파일 반입에는 재배포 금지(Disable redeploy / ALLOW_ONCE) 저장소가 필요합니다. 기존 파일의 덮어쓰기를 허용하는 저장소는 연결할 수 없습니다.");
        }
        if ("maven2".equals(format) && (!(details.get("maven") instanceof Map<?,?> maven)
            || !"RELEASE".equalsIgnoreCase(String.valueOf(maven.get("versionPolicy"))))) {
            throw new IllegalArgumentException("이 파일 반입 경로는 Maven RELEASE 전용입니다. 변경 가능한 SNAPSHOT·MIXED 저장소는 별도 배포 절차를 사용하세요.");
        }
    }

    /** Uses a file publisher, never a byte[] containing the entire dependency. */
    public void publishDependency(String expectedBase, String repository, String path,
                                  java.nio.file.Path file, java.util.function.LongConsumer progress) throws Exception {
        try (var deadline = new NexusTransferDeadline(dependencyIdleTimeout())) {
            var input = java.nio.file.Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            deadline.watchUpload(input);
            var supplied = new java.util.concurrent.atomic.AtomicBoolean();
            var source = HttpRequest.BodyPublishers.ofInputStream(() -> {
                if (!supplied.compareAndSet(false, true))
                    throw new IllegalStateException("Nexus 파일 등록은 자동 재전송하지 않습니다.");
                return input;
            });
            var counting = HttpRequest.BodyPublishers.fromPublisher(
                deadline.publisher(source, progress), java.nio.file.Files.size(file));
            var response = deadline.await(HTTP_CLIENT.sendAsync(dependencyRequest(expectedBase, repository, path)
                .header("Content-Type", "application/octet-stream").PUT(counting).build(),
                HttpResponse.BodyHandlers.discarding()));
            if (response.statusCode() / 100 != 2)
                throw new IllegalStateException("Nexus 등록 응답 HTTP " + response.statusCode() + ". 원격 결과를 확인하세요.");
        }
    }

    public boolean dependencyExists(String expectedBase, String repository, String path) throws Exception {
        var response = HTTP_CLIENT.send(dependencyRequest(expectedBase, repository, path)
            .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() != 200) throw new IllegalStateException("Nexus 파일 확인 HTTP " + response.statusCode());
        return true;
    }

    /** Re-read the registered asset as a stream: accepted bytes and verified registration are distinct states. */
    public String dependencyDigest(String expectedBase, String repository, String path,
                                   Runnable heartbeat) throws Exception {
        var checksums = dependencyChecksums(expectedBase, repository, path, heartbeat);
        return checksums == null ? null : checksums.get("sha256");
    }

    /** Hash the actual remote payload once, without buffering the dependency in heap. */
    public Map<String,String> dependencyChecksums(String expectedBase, String repository, String path,
                                                  Runnable heartbeat) throws Exception {
        try (var deadline = new NexusTransferDeadline(dependencyIdleTimeout())) {
            var response = deadline.await(HTTP_CLIENT.sendAsync(dependencyRequest(expectedBase, repository, path).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()));
            var input = response.body(); // await registers and closes the body even on a deadline/headers race.
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) throw new IllegalStateException("Nexus 등록 검증 HTTP " + response.statusCode());
            var sha256 = java.security.MessageDigest.getInstance("SHA-256");
            var sha1 = java.security.MessageDigest.getInstance("SHA-1");
            var md5 = java.security.MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = input.read(buffer)) != -1;) {
                deadline.check();
                deadline.progress();
                sha256.update(buffer, 0, n);
                sha1.update(buffer, 0, n);
                md5.update(buffer, 0, n);
                heartbeat.run();
            }
            deadline.check();
            return Map.of("sha256", java.util.HexFormat.of().formatHex(sha256.digest()),
                "sha1", java.util.HexFormat.of().formatHex(sha1.digest()),
                "md5", java.util.HexFormat.of().formatHex(md5.digest()));
        }
    }

    /** publish=false is strictly read-only; missing sidecars are never repaired by verification. */
    public boolean dependencyChecksumFiles(String base, String repository, String path,
            Map<String,String> checksums, boolean publish, Runnable heartbeat) throws Exception {
        var missing = new java.util.ArrayList<String>();
        for (String algorithm : java.util.List.of("sha1", "md5", "sha256")) {
            String expected = checksums.get(algorithm);
            int digits = "sha1".equals(algorithm) ? 40 : "md5".equals(algorithm) ? 32 : 64;
            if (expected == null || !expected.matches("[0-9a-f]{" + digits + "}"))
                throw new IllegalArgumentException("Checksum 계산 결과가 올바르지 않습니다.");
            String actual = readDependencyChecksum(base, repository, path + "." + algorithm);
            heartbeat.run();
            if (actual == null) missing.add(algorithm);
            else if (!expected.equalsIgnoreCase(actual))
                throw new IllegalStateException("기존 Maven checksum이 본문과 다릅니다. 자동 덮어쓰기하지 않습니다.");
        }
        if (!publish) return missing.isEmpty();
        for (String algorithm : missing) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Checksum 등록 취소");
            // Nexus ALLOW_ONCE is the write-side race guard; a lost response is reconciled by GET.
            try (var deadline = new NexusTransferDeadline(dependencyIdleTimeout())) {
                var response = deadline.await(HTTP_CLIENT.sendAsync(
                    dependencyRequest(base, repository, path + "." + algorithm)
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofString(checksums.get(algorithm) + "\n",
                            java.nio.charset.StandardCharsets.US_ASCII)).build(),
                    HttpResponse.BodyHandlers.discarding()));
                if (response.statusCode() / 100 != 2)
                    throw new IllegalStateException("Checksum 등록 응답 HTTP " + response.statusCode() + ". 결과를 확인하세요.");
            }
            heartbeat.run();
            if (!checksums.get(algorithm).equalsIgnoreCase(readDependencyChecksum(base, repository, path + "." + algorithm)))
                throw new IllegalStateException("Maven checksum 등록 결과를 확인하지 못했습니다.");
        }
        return true;
    }

    private String readDependencyChecksum(String base, String repository, String path) throws Exception {
        try (var deadline = new NexusTransferDeadline(dependencyIdleTimeout())) {
            var response = deadline.await(HTTP_CLIENT.sendAsync(dependencyRequest(base, repository, path).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()));
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) throw new IllegalStateException("Checksum 확인 HTTP " + response.statusCode());
            byte[] bytes = response.body().readNBytes(257);
            deadline.check();
            if (bytes.length > 256) throw new IllegalStateException("Checksum 응답 크기가 올바르지 않습니다.");
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        }
    }

    private Duration dependencyIdleTimeout() {
        try {
            long seconds = Long.parseLong(settingService.get("nexus.transfer.idle-seconds", "120"));
            // Must remain below the 10-minute publishing lease. Large active transfers have no total deadline.
            if (seconds < 1 || seconds > 300) throw new NumberFormatException();
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Nexus 파일 전송 대기 시간은 1~300초여야 합니다.");
        }
    }

    private HttpRequest.Builder dependencyRequest(String expectedBase, String repository, String path) {
        String base = requireNexusUrl();
        if (!base.equals(expectedBase)) throw new IllegalStateException("Nexus 접속 설정이 변경됐습니다. 원래 저장소 결과를 확인하세요.");
        NexusDependencyPolicy.repository(repository);
        NexusDependencyPolicy.path(path);
        String suffix = java.util.Arrays.stream(path.split("/"))
            .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
            .collect(java.util.stream.Collectors.joining("/"));
        var builder = HttpRequest.newBuilder(URI.create(base + "/repository/" + repository + "/" + suffix));
        String user = settingService.get("nexus.user", "").trim();
        String pass = settingService.get("nexus.pass", "");
        if (!user.isBlank() && !pass.isBlank()) builder.header("Authorization", "Basic "
            + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
        return builder;
    }

    /** Creates only a new project-specific group; never rewrites a shared repository. */
    public void ensureProjectMavenGroup(String groupName, String hosted, String common) throws Exception {
        NexusDependencyPolicy.repository(groupName);
        NexusDependencyPolicy.repository(hosted);
        NexusDependencyPolicy.repository(common);
        if (groupName.equals(hosted) || groupName.equals(common))
            throw new IllegalArgumentException("프로젝트 group 이름은 구성원 저장소와 달라야 합니다.");
        assertDependencyRepository(hosted, "maven2");
        var repositories = listRepositories("maven2");
        var base = repositories.stream().filter(row -> common.equals(row.get("name"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("기존 Maven 저장소를 찾을 수 없습니다."));
        if (!Set.of("hosted", "group").contains(String.valueOf(base.get("type"))))
            throw new IllegalArgumentException("기존 Maven 저장소는 내부 hosted 또는 group을 선택하세요.");
        List<String> members = hosted.equals(common) ? List.of(hosted) : List.of(hosted, common);
        var existing = repositories.stream().filter(row -> groupName.equals(row.get("name"))).findFirst();
        if (existing.isPresent()) {
            if (!"group".equals(existing.get().get("type")))
                throw new IllegalStateException("동일 이름의 다른 저장소가 있습니다. 기존 저장소를 변경하지 않았습니다.");
            assertProjectMavenGroup(groupName, members);
            return;
        }
        var body = Map.of("name", groupName, "online", true,
            "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", true),
            "group", Map.of("memberNames", members));
        var response = HTTP_CLIENT.send(baseRequest(requireNexusUrl() + "/service/rest/v1/repositories/maven/group")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("프로젝트 Maven group 생성 결과를 확인하세요. HTTP " + response.statusCode()
                + ". 기존 group은 수정하지 않았습니다.");
        assertProjectMavenGroup(groupName, members);
    }

    public void assertProjectMavenGroup(String name, List<String> members) throws Exception {
        NexusDependencyPolicy.repository(name);
        var detail = repositoryDetails("maven2", "group", name);
        if (!Boolean.TRUE.equals(detail.get("online")) || !(detail.get("group") instanceof Map<?, ?> group)
            || !members.equals(group.get("memberNames")))
            throw new IllegalStateException("프로젝트 Maven group 구성원이 예상과 다릅니다. 자동 덮어쓰기를 중단했습니다.");
    }

    private Map<String, Object> repositoryDetails(String format, String type, String name) throws Exception {
        if (!Set.of("hosted", "proxy", "group").contains(type)) {
            throw new IllegalArgumentException("지원하지 않는 저장소 유형입니다.");
        }
        String apiFormat = "maven2".equals(format) ? "maven" : format;
        String url = requireNexusUrl() + "/service/rest/v1/repositories/" + apiFormat + "/" + type + "/"
            + URLEncoder.encode(name, StandardCharsets.UTF_8);
        HttpResponse<String> response = HTTP_CLIENT.send(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Nexus 저장소 상세 조회 실패: HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private void applyDockerEndpoint(Map<String, Object> result, Map<String, Object> detail) throws Exception {
        if (!"hosted".equals(result.get("type"))) {
            result.put("unavailableReason", "이미지 업로드에는 hosted 저장소가 필요합니다.");
            return;
        }
        if (Boolean.FALSE.equals(detail.get("online"))) {
            result.put("unavailableReason", "이 저장소는 오프라인 상태입니다.");
            return;
        }
        URI connector = configuredConnector();
        String portKey = "https".equals(connector.getScheme()) ? "httpsPort" : "httpPort";
        if (!(detail.get("docker") instanceof Map<?, ?> docker)
                || asLong(docker.get(portKey)) != effectivePort(connector)) {
            result.put("unavailableReason", "이 저장소의 커넥터와 연동 설정의 레지스트리 주소가 일치하지 않습니다.");
            return;
        }
        HttpResponse<String> probe = HTTP_CLIENT.send(baseRequest(connector + "/v2/").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (probe.statusCode() == 403 && probe.body().toLowerCase(Locale.ROOT)
                .contains("end user license agreement")) {
            result.put("unavailableReason", "Nexus 사용 조건 동의가 완료되지 않았습니다. Nexus 초기 설정에서 내용을 확인한 뒤 다시 검사해 주세요.");
            return;
        }
        boolean dockerRegistry = probe.headers().firstValue("Docker-Distribution-API-Version")
            .orElse("").equalsIgnoreCase("registry/2.0");
        boolean anonymousAccess = probe.statusCode() == 200;
        boolean bearerChallenge = probe.statusCode() == 401 && probe.headers()
            .firstValue("WWW-Authenticate")
            .map(value -> value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()))
            .orElse(false);
        if (!dockerRegistry || !(anonymousAccess || bearerChallenge)) {
            result.put("unavailableReason", "Docker Registry 연결·인증을 확인하지 못했습니다. 커넥터 포트 게시와 연동 계정을 확인하세요.");
            return;
        }
        result.put("registry", registryHostPort());
        // This probe proves only that the Registry v2 endpoint answers. A Bearer
        // challenge does not prove that the configured credentials can obtain a
        // token or that they have repository-specific push/pull permissions.
        result.put("endpointReachable", true);
    }

    /** 현재 명시된 public registry ↔ 내부 API 주소 쌍만 사용한다. NAT/프록시 포트는 추측하지 않는다. */
    private URI configuredConnector() {
        String registry = settingService.get("nexus.registry", "").trim();
        if (registry.isBlank()) {
            throw new IllegalArgumentException("연동 설정에 Nexus Registry 주소가 없습니다.");
        }
        URI management = URI.create(requireNexusUrl());
        String override = settingService.get("nexus.registry.api-url", "").trim();
        URI publicRegistry = registryUri(registry, management.getScheme());
        URI connector = override.isBlank() ? publicRegistry : registryUri(override, management.getScheme());
        if (!management.getHost().equalsIgnoreCase(connector.getHost())) {
            throw new IllegalArgumentException("Nexus 관리 호스트와 커넥터 호스트가 다릅니다. "
                + "nexus.registry.api-url에 관리 호스트의 실제 커넥터 주소를 지정해야 합니다.");
        }
        return connector;
    }

    private URI registryUri(String value, String defaultScheme) {
        URI uri = URI.create(value.contains("://") ? value : defaultScheme + "://" + value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Nexus Registry 주소는 http(s)://호스트:포트 형식이어야 합니다.");
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    private int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && right.getHost() != null
            && left.getHost().equalsIgnoreCase(right.getHost()) && effectivePort(left) == effectivePort(right);
    }

    /** 이미지 저장소 값을 만들 때 앞에 붙는 레지스트리 주소({@code host:port}). */
    public String registryHostPort() {
        String registry = settingService.get("nexus.registry", "").trim();
        return registry.replaceAll("^https?://", "").replaceAll("/$", "");
    }

    private String requireNexusUrl() {
        String nexusUrl = settingService.get("nexus.url", "").trim();
        if (nexusUrl.isBlank()) {
            throw new IllegalArgumentException("통합설정에 Nexus URL 이 설정되지 않았습니다.");
        }
        return nexusUrl.replaceAll("/$", "");
    }

    /** 숫자/문자 어느 쪽으로 와도 long 으로. 알 수 없으면 -1. */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    private HttpRequest.Builder baseRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10));

        String user = settingService.get("nexus.user", "").trim();
        String pass = settingService.get("nexus.pass", "").trim();
        if (!user.isBlank() && !pass.isBlank()) {
            String encoded = Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
        return builder;
    }

    private String resolveRegistryBase(Project project) {
        // 메타데이터 조회 전용 내부 주소(옵션) — 라우터가 공인IP:5000 헤어핀을 막는 환경에서
        // 이미지 명명/CI 가 쓰는 nexus.registry 는 그대로 두고 API 호출만 내부망으로 우회한다.
        String registry = projectSetting(project, "nexus.registry", "").trim();
        String imageRepository = requireImageRepository(project);
        int slashIndex = imageRepository.indexOf('/');
        if (registry.isBlank()) {
            throw new IllegalArgumentException("연동 설정에 Nexus Registry 주소가 없습니다.");
        }

        String nexusUrl = projectSetting(project, "nexus.url", "").trim();
        String scheme = nexusUrl.startsWith("http://") ? "http" : "https";
        URI configured = registryUri(registry, scheme);
        if (slashIndex <= 0 || !imageRepository.substring(0, slashIndex).equalsIgnoreCase(configured.getRawAuthority())) {
            throw new IllegalArgumentException("프로젝트 이미지 주소와 연동 설정의 Nexus Registry가 다릅니다. 레지스트리 연결을 확인해 주세요.");
        }
        String apiOverride = projectSetting(project, "nexus.registry.api-url", "").trim();
        return apiOverride.isBlank() ? configured.toString() : registryUri(apiOverride, scheme).toString();
    }

    private String resolveRepositoryPath(Project project) {
        String imageRepository = requireImageRepository(project);
        int slashIndex = imageRepository.indexOf('/');
        if (slashIndex <= 0 || slashIndex >= imageRepository.length() - 1) {
            throw new IllegalArgumentException("프로젝트 이미지 저장소 형식이 올바르지 않습니다.");
        }
        return imageRepository.substring(slashIndex + 1);
    }

    private String requireImageRepository(Project project) {
        if (project == null || project.getImageRepository() == null || project.getImageRepository().isBlank()) {
            throw new IllegalArgumentException("프로젝트 이미지 저장소가 설정되지 않았습니다.");
        }
        return project.getImageRepository().trim();
    }

    private String projectSetting(Project project, String key, String defaultValue) {
        DeploymentRuntimeSettings snapshot = project != null ? project.getDeploymentRuntimeSettings() : null;
        if (snapshot != null) {
            String value = switch (key) {
                case "nexus.url" -> snapshot.nexusUrl();
                case "nexus.registry" -> snapshot.nexusRegistry();
                case "nexus.registry.api-url" -> snapshot.nexusRegistryApiUrl();
                default -> defaultValue;
            };
            return value == null ? defaultValue : value;
        }
        String value = settingService.get(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    private String encodeRepositoryPath(String repositoryPath) {
        return String.join("/",
            List.of(repositoryPath.split("/")).stream()
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8))
                .toList()
        );
    }
}
