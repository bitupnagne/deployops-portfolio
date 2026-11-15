package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageArtifactServiceTest {

    private static final String REGISTRY = "nexus.example.test:5000";
    private static final String INTERNAL_REGISTRY = "nexus.internal.test:5000";
    private static final String IMAGE = REGISTRY + "/team/app@sha256:" + "a".repeat(64);
    private static final String CONFIG_JSON = "{\"architecture\":\"amd64\",\"os\":\"linux\",\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]}}";
    private static final String CONFIG_ID = "sha256:" + sha256(CONFIG_JSON.getBytes(StandardCharsets.UTF_8));
    private static final String USER = "deploy-reader";
    private static final String PASSWORD = "fixture-S3cret-7$!";

    @TempDir
    Path dir;

    @Test
    void sha256HexMatchesKnownVector() throws Exception {
        Path file = dir.resolve("sample.bin");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        // 대상 서버가 이 값으로 전송된 산출물의 무결성을 확인한다 — 계산이 틀리면
        // 정상 산출물이 거부되거나, 더 나쁘게는 깨진 산출물이 통과한다.
        assertThat(ImageArtifactService.sha256Hex(file))
            .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void authenticatedExportUsesPrivateEphemeralAuthConfigAndDeletesCredentials() throws Exception {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        List<String> logs = new ArrayList<>();
        Path result = dir.resolve("image.tar");

        ImageArtifactService.ImageArchive archive = service.exportImage(IMAGE, result, logs::add);
        assertThat(archive.sha256()).isEqualTo(ImageArtifactService.sha256Hex(result));
        assertThat(archive.configId()).isEqualTo(CONFIG_ID);
        assertThat(service.calls).extracting(call -> call.command.get(1))
            .containsExactly("pull", "save", "-xOf", "-xOf");
        assertThat(service.calls.get(1).command).containsExactly("docker", "save", "-o", result.toString(), IMAGE);
        assertThat(service.calls.get(2).command).containsExactly("tar", "-xOf", result.toString(), "manifest.json");
        assertThat(service.calls.get(3).command).containsExactly("tar", "-xOf", result.toString(),
            "blobs/sha256/" + CONFIG_ID.substring("sha256:".length()));
        Call pull = service.calls.getFirst();
        String basic = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        assertThat(pull.command).containsExactly("docker", "pull", IMAGE);
        assertThat(pull.initialConfig).isEqualTo("{\"auths\":{\"" + REGISTRY + "\":{\"auth\":\"" + basic + "\"}}}");
        assertThat(pull.privatePermissions).isTrue();
        for (Call call : service.calls) {
            assertThat(call.environment.get("DOCKER_CONFIG")).isEqualTo(pull.directory.toString());
            assertThat(call.environment).doesNotContainKey("DOCKER_AUTH_CONFIG");
            assertThat(String.join(" ", call.command)).doesNotContain(PASSWORD);
            assertThat(call.initialConfig).doesNotContain(PASSWORD);
            assertThat(call.environment.values()).noneMatch(value -> value.contains(PASSWORD));
        }
        assertThat(Files.exists(pull.directory)).isFalse();
        assertThat(String.join("\n", logs)).doesNotContain(USER, PASSWORD, basic);
    }

    @Test
    void staticExtractionSharesTheSessionThroughCleanupWithoutRunningTheContainer() throws Exception {
        RecordingService service = service("http://" + REGISTRY + "/", USER, PASSWORD);
        Path bundle = dir.resolve("static.tar.gz");
        service.extractStaticBundle(IMAGE, "/dist", bundle, ignored -> {});
        assertThat(service.calls).extracting(call -> call.command.get(1)).containsExactly("pull", "create", "cp", "-czf", "rm");
        assertThat(service.calls).allSatisfy(call -> assertThat(call.command).doesNotContain("run", PASSWORD));
        assertThat(service.calls.stream().map(call -> call.directory).distinct().count()).isEqualTo(1);
        assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
        assertThat(Files.exists(bundle)).isTrue();
    }

    @Test
    void endpointMismatchAndMalformedReferencesNeverStartAnyProcess() {
        for (String image : List.of("evil.test/team/app:latest", REGISTRY + ".evil.test/app:latest",
                "https://" + REGISTRY + "/app:latest", "user:password@" + REGISTRY + "/app:latest", "--help")) {
            RecordingService service = service(REGISTRY, USER, PASSWORD);
            assertThatThrownBy(() -> service.exportImage(image, dir.resolve("none.tar"), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class);
            assertThat(service.calls).isEmpty();
        }
    }

    @Test
    void malformedRegistryAndIncompleteCredentialsFailBeforeAProcessStarts() {
        for (String registry : List.of("https://user:pass@" + REGISTRY, REGISTRY + "/repository/hosted",
                REGISTRY + "?other=host", REGISTRY + "#frag", "nexus", "https://nexus.test:99999")) {
            RecordingService service = service(registry, USER, PASSWORD);
            assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("none.tar"), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class);
            assertThat(service.calls).isEmpty();
        }
        for (List<String> settings : List.of(List.of("", USER, PASSWORD), List.of(REGISTRY, USER, ""),
                List.of(REGISTRY, "", PASSWORD), List.of(REGISTRY, USER, "line1\nline2"))) {
            RecordingService service = service(settings.get(0), settings.get(1), settings.get(2));
            assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("none.tar"), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class);
            assertThat(service.calls).isEmpty();
        }
    }

    @Test
    void anonymousPullStillUsesAnIsolatedEmptyConfigAndNoLogin() {
        RecordingService service = service("", "", "");
        service.exportImage("alpine:3.22", dir.resolve("public.tar"), ignored -> {});
        assertThat(service.calls).extracting(call -> call.command.get(1)).containsExactly("pull", "save", "-xOf", "-xOf");
        assertThat(service.calls.getFirst().initialConfig).isEqualTo("{\"auths\":{\"deployops.invalid\":{}}}");
        assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
    }

    @Test
    void failedPullPreservesUnauthorizedCauseButRedactsPasswordAndEncodedAuth() {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        service.failureAction = "pull";
        String basic = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        service.failureOutput = "unauthorized: " + USER + " " + PASSWORD + " " + basic;
        List<String> logs = new ArrayList<>();
        assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("none.tar"), logs::add))
            .hasMessageContaining("unauthorized").hasMessageContaining("docker pull " + IMAGE)
            .hasMessageNotContaining(PASSWORD).hasMessageNotContaining(basic)
            .hasNoCause();
        assertThat(service.calls).hasSize(1);
        assertThat(String.join("\n", logs)).doesNotContain(USER, PASSWORD, basic);
        assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
    }

    @Test
    void pullFailureKeepsTheLastErrorBeyondProgressLimitAndRemovesCredentials() {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        service.failureAction = "pull";
        service.failureOutput = "download progress\n".repeat(25) + "denied: missing pull scope " + PASSWORD;
        List<String> logs = new ArrayList<>();
        assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("none.tar"), logs::add))
            .hasMessageContaining("denied: missing pull scope").hasMessageNotContaining(PASSWORD);
        assertThat(String.join("\n", logs)).doesNotContain(PASSWORD);
        assertThat(service.calls).extracting(call -> call.command.get(1)).containsExactly("pull");
        assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
    }

    @Test
    void processStartFailureIsRedactedWithoutRetainingTheUnsafeCauseAndCleansConfig() {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        service.startFailure = true;
        assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("none.tar"), ignored -> {}))
            .hasMessageContaining("docker 실행 실패").hasMessageNotContaining(PASSWORD).hasNoCause();
        assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
    }

    @Test
    void timeoutTerminatesProcessAndDeletesThePrivateConfig() {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        service.timeout = true;
        assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("none.tar"), ignored -> {}))
            .hasMessageContaining("타임아웃");
        assertThat(service.calls.getFirst().process.destroyed).isTrue();
        assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
    }

    @Test
    void inheritedRemoteDockerSettingsCannotSelectSshOrReplaceRegistryAuthentication() throws Exception {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get("nexus.registry", "")).thenReturn(REGISTRY);
        when(settings.get("nexus.user", "")).thenReturn(USER);
        when(settings.get("nexus.pass", "")).thenReturn(PASSWORD);
        try (DockerArtifactSession session = DockerArtifactSession.open(IMAGE, settings)) {
            ProcessBuilder builder = new ProcessBuilder("docker", "pull", IMAGE);
            for (String key : List.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_CERT_PATH",
                    "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_AUTH_CONFIG", "DOCKER_CUSTOM_HEADERS", "DOCKER_API_VERSION")) {
                builder.environment().put(key, "ssh://untrusted.example.invalid");
            }
            session.configure(builder);
            assertThat(builder.environment().get("DOCKER_HOST")).isEqualTo("unix:///var/run/docker.sock");
            assertThat(builder.environment().get("DOCKER_CONFIG")).isEqualTo(session.directory().toString());
            assertThat(builder.environment().keySet().stream().filter(key -> key.startsWith("DOCKER_")).toList())
                .containsExactlyInAnyOrder("DOCKER_HOST", "DOCKER_CONFIG");
            String auth = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            assertThat(Files.readString(session.directory().resolve("config.json")))
                .isEqualTo("{\"auths\":{\"" + REGISTRY + "\":{\"auth\":\"" + auth + "\"}}}");
            assertThat(builder.command()).containsExactly("docker", "pull", IMAGE);
        }
    }

    @Test
    void independentOperationsHaveDifferentCredentialDirectories() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get("nexus.registry", "")).thenReturn(REGISTRY);
        when(settings.get("nexus.user", "")).thenReturn(USER);
        when(settings.get("nexus.pass", "")).thenReturn(PASSWORD);
        try (DockerArtifactSession first = DockerArtifactSession.open(IMAGE, settings);
             DockerArtifactSession second = DockerArtifactSession.open(IMAGE, settings)) {
            assertThat(first.directory()).isNotEqualTo(second.directory());
        }
    }

    @Test
    void tagExportSavesTheApprovedReferenceAndUsesTheArchiveConfigDigest() {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        String tag = REGISTRY + "/team/app:release";
        ImageArtifactService.ImageArchive result = service.exportImage(tag, dir.resolve("tag.tar"), ignored -> {});
        assertThat(service.calls.get(1).command.getLast()).isEqualTo(tag);
        assertThat(result.configId()).isEqualTo(CONFIG_ID);
    }

    @Test
    void configuredInternalApiAddressDoesNotRewriteApprovedDockerRegistryReference() {
        RecordingService service = service(REGISTRY, INTERNAL_REGISTRY, USER, PASSWORD);
        String approved = REGISTRY + "/team/app@sha256:" + "b".repeat(64);
        String auth = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        List<String> logs = new ArrayList<>();

        service.exportImage(approved, dir.resolve("internal-registry.tar"), logs::add);

        assertThat(service.calls.get(0).command).containsExactly("docker", "pull", approved);
        assertThat(service.calls.get(0).initialConfig)
            .isEqualTo("{\"auths\":{\"" + REGISTRY + "\":{\"auth\":\"" + auth + "\"}}}");
        assertThat(service.calls.get(1).command)
            .containsExactly("docker", "save", "-o", dir.resolve("internal-registry.tar").toString(), approved);
        assertThat(String.join("\n", logs)).contains(approved).doesNotContain(PASSWORD);

        RecordingService mismatched = service(REGISTRY, INTERNAL_REGISTRY, USER, PASSWORD);
        assertThatThrownBy(() -> mismatched.exportImage("attacker.test/team/app:latest",
                dir.resolve("rejected.tar"), ignored -> {}))
            .isInstanceOf(RemoteExecutionException.class);
        assertThat(mismatched.calls).isEmpty();
    }

    @Test
    void unreadableOrMalformedArchiveConfigNeverProducesAnImageIdentity() {
        List<String> manifests = List.of("", "not-json", "[]", "null",
            "[{\"Config\":\"../../etc/passwd\",\"Layers\":[]}]",
            "[{\"Config\":\"blobs/sha256/not-a-digest\",\"Layers\":[]}]");
        for (String output : manifests) {
            RecordingService service = service(REGISTRY, USER, PASSWORD);
            service.archiveManifestOutput = output;
            assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("invalid.tar"), ignored -> {}))
                .isInstanceOf(RemoteExecutionException.class);
            assertThat(service.calls).anySatisfy(call -> assertThat(call.command).contains("save"));
            assertThat(service.calls).noneSatisfy(call -> assertThat(call.command).contains("blobs/sha256/"
                + CONFIG_ID.substring("sha256:".length())));
            assertThat(Files.exists(service.calls.getFirst().directory)).isFalse();
        }

        RecordingService mismatchedConfig = service(REGISTRY, USER, PASSWORD);
        mismatchedConfig.archiveConfigOutput = "{\"different\":\"content\"}";
        assertThatThrownBy(() -> mismatchedConfig.exportImage(IMAGE, dir.resolve("mismatch.tar"), ignored -> {}))
            .hasMessageContaining("digest");
        assertThat(Files.exists(mismatchedConfig.calls.getFirst().directory)).isFalse();
    }

    @Test
    void archiveManifestFailureFailsClosedAndRedactsItsDiagnostic() {
        RecordingService service = service(REGISTRY, USER, PASSWORD);
        service.failureAction = "tar-manifest";
        service.failureOutput = "No such image " + PASSWORD;
        assertThatThrownBy(() -> service.exportImage(IMAGE, dir.resolve("invalid.tar"), ignored -> {}))
            .hasMessageContaining("tar 메타데이터 추출").hasMessageNotContaining(PASSWORD);
        assertThat(service.calls).hasSize(3);
    }

    @Test
    void archiveContractRejectsMissingOrMalformedChecksumsAndConfigIds() {
        assertThatThrownBy(() -> new ImageArtifactService.ImageArchive(null, CONFIG_ID)).isInstanceOf(RemoteExecutionException.class);
        assertThatThrownBy(() -> new ImageArtifactService.ImageArchive("a".repeat(64), "latest")).isInstanceOf(RemoteExecutionException.class);
    }

    private RecordingService service(String registry, String user, String pass) {
        return service(registry, "", user, pass);
    }

    private RecordingService service(String registry, String internalRegistry, String user, String pass) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get("nexus.registry", "")).thenReturn(registry);
        when(settings.get("nexus.registry.api-url", "")).thenReturn(internalRegistry);
        when(settings.get("nexus.user", "")).thenReturn(user);
        when(settings.get("nexus.pass", "")).thenReturn(pass);
        return new RecordingService(settings);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class RecordingService extends ImageArtifactService {
        private final List<Call> calls = new ArrayList<>();
        private String failureAction = "";
        private String failureOutput = "";
        private String archiveManifestOutput = "[{\"Config\":\"blobs/sha256/"
            + CONFIG_ID.substring("sha256:".length()) + "\",\"RepoTags\":[\"" + IMAGE
            + "\"],\"Layers\":[]}]";
        private String archiveConfigOutput = CONFIG_JSON;
        private boolean startFailure;
        private boolean timeout;

        RecordingService(SystemSettingService settings) { super(settings); }

        @Override Process startProcess(ProcessBuilder builder) throws IOException {
            List<String> command = List.copyOf(builder.command());
            Path directory = Path.of(builder.environment().get("DOCKER_CONFIG"));
            Path config = directory.resolve("config.json");
            String action = command.get(0).equals("tar")
                ? command.get(1).equals("-czf") ? "-czf"
                    : command.getLast().equals("manifest.json") ? "tar-manifest" : "tar-config"
                : command.get(1);
            boolean failed = action.equals(failureAction);
            String output = action.equals("tar-manifest") ? archiveManifestOutput
                : action.equals("tar-config") ? archiveConfigOutput
                : action.equals("login") ? "Login Succeeded\nWARNING credentials stored\n" : "";
            FakeProcess process = new FakeProcess(failed ? failureOutput : output, failed ? 1 : 0, timeout);
            calls.add(new Call(command, Map.copyOf(builder.environment()), directory, Files.readString(config),
                privatePermissions(directory, true) && privatePermissions(config, false), process));
            if (startFailure) throw new IOException("process rejected " + PASSWORD);
            if (!failed && action.equals("save")) Files.writeString(Path.of(command.get(3)), "saved image");
            if (!failed && action.equals("-czf")) Files.writeString(Path.of(command.get(2)), "static bundle");
            return process;
        }

        private boolean privatePermissions(Path path, boolean directory) throws IOException {
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                return Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            }
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
            return acl.size() == 1 && acl.getFirst().principal().equals(Files.getOwner(path));
        }
    }

    private record Call(List<String> command, Map<String, String> environment, Path directory,
                        String initialConfig, boolean privatePermissions, FakeProcess process) { }

    private static final class FakeProcess extends Process {
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final InputStream stdout;
        private final int exit;
        private final boolean timeout;
        private boolean destroyed;
        FakeProcess(String output, int exit, boolean timeout) {
            this.stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            this.exit = exit;
            this.timeout = timeout;
        }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !this.timeout; }
        @Override public int exitValue() { return exit; }
        @Override public boolean isAlive() { return timeout && !destroyed; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
    }
}
