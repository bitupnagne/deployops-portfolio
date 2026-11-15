package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NexusMetadataServiceTest {

    private final Map<String, String> settings = new ConcurrentHashMap<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final Map<String, List<Response>> responseSequences = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> responseSequenceIndexes = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private String base;
    private int port;
    private NexusMetadataService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        settings.put("nexus.url", base);
        settings.put("nexus.registry", "127.0.0.1:" + port);
        settings.put("nexus.user", "deploy-reader");
        settings.put("nexus.pass", "test-secret");
        SystemSettingService settingService = mock(SystemSettingService.class);
        when(settingService.get(anyString(), anyString())).thenAnswer(invocation ->
            settings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        service = new NexusMetadataService(mapper, settingService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void mapsOnlyTheSelectedHostedRepositoryWhoseConnectorMatchesTheConfiguredEndpoint() throws Exception {
        stub("GET", "/service/rest/v1/repositories", 200, """
            [{"name":"matching","format":"docker","type":"hosted"},
             {"name":"other-port","format":"docker","type":"hosted"},
             {"name":"pull-group","format":"docker","type":"group"}]
            """);
        dockerDetail("hosted", "matching", port);
        dockerDetail("hosted", "other-port", port == 65535 ? port - 1 : port + 1);
        dockerDetail("group", "pull-group", port);
        registryProbe(200, true);

        List<Map<String, Object>> repositories = service.listRepositories("docker");

        assertThat(repositories.get(0)).containsEntry("name", "matching")
            .containsEntry("registry", "127.0.0.1:" + port).containsEntry("endpointReachable", true);
        assertThat(repositories.get(1)).containsKey("unavailableReason").doesNotContainKey("registry");
        assertThat(repositories.get(2)).containsKey("unavailableReason").doesNotContainKey("registry");
        assertThat(requests.stream().filter(r -> r.path().equals("/v2/"))).hasSize(1);
    }

    @Test
    void explicitInternalApiAddressMapsToThePublicImageAuthority() throws Exception {
        settings.put("nexus.registry", "registry.example.internal:5000");
        settings.put("nexus.registry.api-url", base);
        oneDockerRepository("hosted");
        registryProbe(200, true);

        assertThat(service.listRepositories("docker").getFirst())
            .containsEntry("registry", "registry.example.internal:5000").containsEntry("endpointReachable", true);
    }

    @Test
    void authenticatedDockerRegistryBearerChallengeCountsAsAReachableEndpoint() throws Exception {
        oneDockerRepository("hosted");
        registryProbe(401, Map.of(
            "Docker-Distribution-API-Version", "registry/2.0",
            "WWW-Authenticate", "Bearer realm=\"" + base + "/service/rest/v1/security/token\""));

        assertThat(service.listRepositories("docker").getFirst())
            .containsEntry("registry", "127.0.0.1:" + port).containsEntry("endpointReachable", true)
            .doesNotContainKey("unavailableReason");
    }

    @Test
    void reportsNexusEulaPrerequisiteWithoutTreatingForbiddenAsAValidRegistry() throws Exception {
        oneDockerRepository("hosted");
        responses.put("GET /v2/", new Response(403,
            "You must accept the End User License Agreement (EULA) before proceeding.",
            Map.of("Docker-Distribution-API-Version", "registry/2.0")));

        assertThat(service.listRepositories("docker").getFirst())
            .containsKey("unavailableReason").doesNotContainKey("registry")
            .extractingByKey("unavailableReason").asString().contains("Nexus 사용 조건 동의");
    }

    @Test
    void registryHealthDoesNotTreatAnUnacceptedEulaAsAHealthyTcpEndpoint() throws Exception {
        responses.put("GET /v2/", new Response(403,
            "You must accept the End User License Agreement (EULA) before proceeding.",
            Map.of("Docker-Distribution-API-Version", "registry/2.0")));

        assertThat(service.probeRegistryEndpoint())
            .containsEntry("state", "BROKEN")
            .extractingByKey("detail").asString().contains("사용 조건 동의");
    }

    @Test
    void registryHealthExplainsRateLimitingAndHonorsRetryAfter() throws Exception {
        registryProbe(429, Map.of(
            "Docker-Distribution-API-Version", "registry/2.0",
            "Retry-After", "30"));

        assertThat(service.probeRegistryEndpoint())
            .containsEntry("state", "BROKEN")
            .extractingByKey("detail").asString()
            .contains("HTTP 429", "반복 확인을 멈추고", "Nexus 계정·요청 제한", "프록시·게이트웨이 로그", "30초 후 다시 확인");
    }

    @Test
    void registryHealthUsesGenericRetryGuidanceForHttpDateRetryAfter() throws Exception {
        registryProbe(429, Map.of(
            "Docker-Distribution-API-Version", "registry/2.0",
            "Retry-After", "Wed, 21 Oct 2030 07:28:00 GMT"));

        assertThat(service.probeRegistryEndpoint())
            .containsEntry("state", "BROKEN")
            .extractingByKey("detail").asString()
            .contains("HTTP 429", "프록시·게이트웨이 로그", "Retry-After 응답 헤더가 있으면 그 시간 이후");
    }

    @Test
    void registryHealthReportsMissingConfigurationAsUnconfigured() {
        settings.remove("nexus.registry");

        assertThat(service.probeRegistryEndpoint())
            .containsEntry("state", "UNCONFIGURED")
            .extractingByKey("detail").asString().contains("주소를 먼저 설정");
        assertThat(requests).isEmpty();
    }

    @Test
    void registryHealthWarnsWhenOnlyTheBearerChallengeWasVerified() throws Exception {
        registryProbe(401, Map.of(
            "Docker-Distribution-API-Version", "registry/2.0",
            "WWW-Authenticate", "Bearer realm=\"" + base + "/service/rest/v1/security/token\""));

        assertThat(service.probeRegistryEndpoint())
            .containsEntry("state", "WARN")
            .extractingByKey("detail").asString().contains("프로젝트별 pull 권한");
    }

    @Test
    void differentHostWithoutExplicitApiMappingIsNotGuessed() throws Exception {
        settings.put("nexus.registry", "unknown.example.internal:" + port);
        oneDockerRepository("hosted");

        assertThat(service.listRepositories("docker").getFirst())
            .containsKey("unavailableReason").doesNotContainKey("registry");
        assertThat(requests).noneMatch(r -> r.path().equals("/v2/"));
    }

    @Test
    void deniedOrNonRegistryEndpointCannotBeSelected() throws Exception {
        oneDockerRepository("hosted");
        registryProbe(401, true);
        assertThat(service.listRepositories("docker").getFirst()).containsKey("unavailableReason");
        registryProbe(200, false);
        assertThat(service.listRepositories("docker").getFirst()).containsKey("unavailableReason");
    }

    @Test
    void deniedRepositoryDetailsDoNotFallbackToTheGlobalRegistry() throws Exception {
        oneDockerRepository("hosted");
        stub("GET", "/service/rest/v1/repositories/docker/hosted/images", 403, "{}");
        assertThat(service.listRepositories("docker").getFirst())
            .containsKey("unavailableReason").doesNotContainKey("registry");
    }

    @Test
    void mavenListingIncludesTheActualVersionPolicy() throws Exception {
        stub("GET", "/service/rest/v1/repositories", 200,
            "[{\"name\":\"maven-snapshots\",\"format\":\"maven2\",\"type\":\"hosted\"}]");
        stub("GET", "/service/rest/v1/repositories/maven/hosted/maven-snapshots", 200,
            "{\"name\":\"maven-snapshots\",\"maven\":{\"versionPolicy\":\"SNAPSHOT\"}}");
        assertThat(service.listRepositories("maven2").getFirst()).containsEntry("versionPolicy", "SNAPSHOT");
    }

    @Test
    void createsMavenRepositoriesWithTheRequestedPolicy() throws Exception {
        stub("POST", "/service/rest/v1/repositories/maven/hosted", 201, "{}");
        service.createHostedRepository("maven2", "snapshots", null, "SNAPSHOT");
        service.createHostedRepository("maven2", "releases", null, "RELEASE");
        assertThat(mapper.readTree(requests.get(0).body()).path("maven").path("versionPolicy").asString())
            .isEqualTo("SNAPSHOT");
        assertThat(mapper.readTree(requests.get(1).body()).path("maven").path("versionPolicy").asString())
            .isEqualTo("RELEASE");
    }

    @Test
    void rejectsUnknownMavenPolicyBeforeCreatingAnything() {
        assertThatThrownBy(() -> service.createHostedRepository("maven2", "mixed", null, "MIXED"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RELEASE 또는 SNAPSHOT");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsUnpublishedOrMissingDockerPortsBeforeCreatingAnything() {
        for (Integer unknownPort : new Integer[] { null, 0, -1, 65536, port == 65535 ? port - 1 : port + 1 }) {
            assertThatThrownBy(() -> service.createHostedRepository("docker", "new-images", unknownPort, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("커넥터 포트");
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void createdDockerRepositoryRemainsUnavailableUntilItsConnectorResponds() throws Exception {
        stub("POST", "/service/rest/v1/repositories/docker/hosted", 201, "{}");
        dockerDetail("hosted", "new-images", port);
        registryProbe(404, false);
        Map<String, Object> created = service.createHostedRepository("docker", "new-images", port, null);
        assertThat(created).containsEntry("name", "new-images").containsKey("unavailableReason")
            .doesNotContainKey("registry");
        assertThat(mapper.readTree(requests.getFirst().body()).path("docker").path("httpPort").asInt()).isEqualTo(port);
    }

    @Test
    void followsTagPaginationAndIncludesTheFiftyFirstTag() throws Exception {
        List<String> firstTags = IntStream.range(0, 50).mapToObj(i -> "tag%03d".formatted(i)).toList();
        responses.put("GET /v2/team/app/tags/list?n=50", new Response(200,
            mapper.writeValueAsString(Map.of("tags", firstTags)),
            Map.of("Link", "</v2/team/app/tags/list?n=50&last=tag049>; rel=\"next\"")));
        stub("GET", "/v2/team/app/tags/list?n=50&last=tag049", 200, "{\"tags\":[\"tag050\"]}");

        List<Map<String, Object>> tags = service.getDockerTags(project());

        assertThat(tags).hasSize(51);
        assertThat(tags).anySatisfy(tag -> assertThat(tag).containsEntry("tag", "tag050"));
        assertThat(requests).anyMatch(r -> r.path().contains("last=tag049"));
    }

    @Test
    void reportsNexusEulaPrerequisiteWhenDockerTagListingIsForbidden() {
        responses.put("GET /v2/team/app/tags/list?n=50", new Response(403,
            "You must accept the End User License Agreement (EULA) before proceeding.", Map.of()));

        assertThatThrownBy(() -> service.getDockerTags(project()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nexus 사용 조건 동의");
    }

    @Test
    void keepsCredentialMessageForRegistryForbiddenWithoutEulaResponse() {
        responses.put("GET /v2/team/app/tags/list?n=50", new Response(403, "Forbidden", Map.of()));

        assertThatThrownBy(() -> service.getDockerTags(project()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("연동 계정");
    }

    @Test
    void rejectsCrossOriginAndWrongPathPaginationBeforeSendingCredentials() {
        for (String link : List.of("http://other.invalid/v2/team/app/tags/list?n=50", base + "/service/rest/v1/users")) {
            requests.clear();
            responses.put("GET /v2/team/app/tags/list?n=50", new Response(200, "{\"tags\":[\"first\"]}",
                Map.of("Link", "<" + link + ">; rel=\"next\"")));
            assertThatThrownBy(() -> service.getDockerTags(project())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다음 페이지 주소");
            assertThat(requests).hasSize(1);
        }
    }

    @Test
    void rejectsRepeatedPaginationInsteadOfReturningAnIncompleteList() {
        responses.put("GET /v2/team/app/tags/list?n=50", new Response(200, "{\"tags\":[\"first\"]}",
            Map.of("Link", "</v2/team/app/tags/list?n=50>; rel=\"next\"")));
        assertThatThrownBy(() -> service.getDockerTags(project())).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("반복");
        assertThat(requests).hasSize(1);
    }

    @Test
    void differentProjectImageRegistryNeverQueriesTheGlobalOrOverriddenRegistry() {
        settings.put("nexus.registry.api-url", base);
        Project project = project();
        project.setImageRepository("different.example.internal:5000/team/app");
        assertThatThrownBy(() -> service.getDockerTags(project)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Registry가 다릅니다");
        assertThatThrownBy(() -> service.resolveManifest(project, "v1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void deletesOnlyAnExactSha256ManifestDigest() throws Exception {
        String digest = "sha256:" + "a".repeat(64);
        stub("DELETE", "/v2/team/app/manifests/" + digest, 202, "");
        stub("HEAD", "/v2/team/app/manifests/" + digest, 404, "");

        service.deleteManifest(project(), digest);

        assertThat(requests).anyMatch(request -> request.path().equals("/v2/team/app/manifests/" + digest));
    }

    @Test
    void acceptedDeleteIsRetriedOnceAndFailsIfDigestStillExists() {
        String digest = "sha256:" + "b".repeat(64);
        stub("DELETE", "/v2/team/app/manifests/" + digest, 202, "");
        stub("HEAD", "/v2/team/app/manifests/" + digest, 200, "");

        assertThatThrownBy(() -> service.deleteManifest(project(), digest))
            .isInstanceOf(IOException.class).hasMessageContaining("계속 조회");
        assertThat(requests.stream().filter(request ->
            request.path().equals("/v2/team/app/manifests/" + digest))).hasSize(4);
    }

    @Test
    void rejectsInvalidDeleteReferenceBeforeCallingNexus() {
        assertThatThrownBy(() -> service.deleteManifest(project(), "latest"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
        assertThat(requests).isEmpty();
    }

    @Test
    void deletesTagOnlyWhenItStillPointsToTheReviewedDigest() throws Exception {
        String digest = "sha256:" + "c".repeat(64);
        String path = "/v2/team/app/manifests/delete-me";
        sequence("HEAD", path,
            response(200, "", Map.of("Docker-Content-Digest", digest)),
            response(404, "", Map.of()));
        stub("DELETE", path, 202, "");

        assertThat(service.deleteManifestTag(project(), "delete-me", digest))
            .isEqualTo(NexusMetadataService.TagDeleteResult.DELETED);
        assertThat(requests.stream().filter(request -> request.path().equals(path))).hasSize(3);
        assertThat(requests.stream().filter(request -> request.path().equals(path)
            && "DELETE".equals(request.method()))).hasSize(1);
    }

    @Test
    void preservesTagThatMovedToAnotherDigest() throws Exception {
        String reviewed = "sha256:" + "d".repeat(64);
        String moved = "sha256:" + "e".repeat(64);
        String path = "/v2/team/app/manifests/delete-me";
        responses.put("HEAD " + path, response(200, "", Map.of("Docker-Content-Digest", moved)));

        assertThat(service.deleteManifestTag(project(), "delete-me", reviewed))
            .isEqualTo(NexusMetadataService.TagDeleteResult.MOVED);
        assertThat(requests).noneMatch(request -> "DELETE".equals(request.method()));
    }

    @Test
    void failsWhenReviewedTagStillResolvesAfterAcceptedDelete() {
        String digest = "sha256:" + "f".repeat(64);
        String path = "/v2/team/app/manifests/delete-me";
        responses.put("HEAD " + path, response(200, "", Map.of("Docker-Content-Digest", digest)));
        stub("DELETE", path, 202, "");

        assertThatThrownBy(() -> service.deleteManifestTag(project(), "delete-me", digest))
            .isInstanceOf(IOException.class).hasMessageContaining("계속 같은 digest");
        assertThat(requests.stream().filter(request -> "DELETE".equals(request.method()))).hasSize(1);
    }

    private Project project() {
        return Project.builder().imageRepository("127.0.0.1:" + port + "/team/app").build();
    }

    private void oneDockerRepository(String type) {
        stub("GET", "/service/rest/v1/repositories", 200,
            "[{\"name\":\"images\",\"format\":\"docker\",\"type\":\"" + type + "\"}]");
        dockerDetail(type, "images", port);
    }

    private void dockerDetail(String type, String name, int connectorPort) {
        // Format-specific Nexus responses need not repeat the list's format/type fields.
        stub("GET", "/service/rest/v1/repositories/docker/" + type + "/" + name, 200,
            "{\"name\":\"" + name + "\",\"online\":true,\"docker\":{\"httpPort\":" + connectorPort + "}}");
    }

    private void registryProbe(int status, boolean registryHeader) {
        registryProbe(status, registryHeader
            ? Map.of("Docker-Distribution-API-Version", "registry/2.0") : Map.of());
    }

    private void registryProbe(int status, Map<String, String> headers) {
        responses.put("GET /v2/", new Response(status, "{}", headers));
    }

    private void stub(String method, String path, int status, String body) {
        responses.put(method + " " + path, new Response(status, body, Map.of()));
    }

    private Response response(int status, String body, Map<String, String> headers) {
        return new Response(status, body, headers);
    }

    private void sequence(String method, String path, Response... sequence) {
        String key = method + " " + path;
        responseSequences.put(key, List.of(sequence));
        responseSequenceIndexes.put(key, new AtomicInteger());
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        requests.add(new Request(exchange.getRequestMethod(), path,
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        String key = exchange.getRequestMethod() + " " + path;
        List<Response> sequence = responseSequences.get(key);
        Response response;
        if (sequence != null) {
            int index = responseSequenceIndexes.get(key).getAndIncrement();
            response = sequence.get(Math.min(index, sequence.size() - 1));
        } else {
            response = responses.getOrDefault(key, new Response(404, "{}", Map.of()));
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, String body, Map<String, String> headers) { }
    private record Request(String method, String path, String body) { }
}
