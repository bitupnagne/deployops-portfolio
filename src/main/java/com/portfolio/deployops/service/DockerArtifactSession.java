package com.portfolio.deployops.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** One artifact operation owns one private Docker configuration; never uses the account's ~/.docker. */
final class DockerArtifactSession implements AutoCloseable {
    private final Path directory;
    private final String registry;
    private final String imageReference;
    private final String username;
    private final String password;
    private final List<String> redactions;

    private DockerArtifactSession(Path directory, String registry, String imageReference, String username, String password) {
        this.directory = directory;
        this.registry = registry;
        this.imageReference = imageReference;
        this.username = username;
        this.password = password;
        this.redactions = List.of(
            Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)),
            Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)),
            URLEncoder.encode(password, StandardCharsets.UTF_8), password, username
        ).stream().filter(value -> !value.isEmpty()).sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    static DockerArtifactSession open(String imageRef, SystemSettingService settings) {
        validateImageReference(imageRef);
        String configured = value(settings, "nexus.registry").trim();
        String username = value(settings, "nexus.user").trim();
        String password = value(settings, "nexus.pass");
        String registry = configured.isEmpty() ? "" : registryAuthority(configured);
        if (!registry.isEmpty()) {
            int slash = imageRef.indexOf('/');
            if (slash < 1 || !registry.equalsIgnoreCase(imageRef.substring(0, slash))) {
                throw new RemoteExecutionException("이미지 주소와 설정된 Nexus Registry가 다릅니다. 인증정보를 전달하지 않았습니다.");
            }
        }
        if (username.isBlank() != password.isBlank() || (!username.isBlank() && registry.isEmpty())) {
            throw new RemoteExecutionException("Nexus Registry 주소와 인증 계정·비밀번호 설정을 함께 확인하세요.");
        }
        if (username.length() > 4096 || password.length() > 4096
                || username.matches("(?s).*[\\r\\n\\x00].*") || password.matches("(?s).*[\\r\\n\\x00].*")) {
            throw new RemoteExecutionException("Nexus 인증정보의 길이 또는 줄바꿈 문자를 확인하세요.");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("deploy-ops-docker-auth-");
            restrict(directory, true);
            Path config = Files.createFile(directory.resolve("config.json"));
            restrict(config, false);
            // Docker talks to an attached engine over the socket. Its registry host may be
            // reachable from the engine (localhost) but not from this CLI container, so
            // docker login cannot reliably probe it. Supply the short-lived auth config
            // directly; the engine receives it on pull/save requests.
            String key = registry.isEmpty() ? "deployops.invalid" : registry;
            String auth = username.isBlank() ? "" : Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            String authEntry = auth.isEmpty() ? "{}" : "{\"auth\":\"" + auth + "\"}";
            Files.writeString(config, "{\"auths\":{\"" + key + "\":" + authEntry + "}}", StandardCharsets.UTF_8);
            return new DockerArtifactSession(directory, registry, imageRef, username, password);
        } catch (IOException | UnsupportedOperationException ex) {
            if (directory != null) deleteDirectory(directory);
            throw new RemoteExecutionException("임시 Docker 인증 설정을 안전하게 준비하지 못했습니다.");
        }
    }

    private static String value(SystemSettingService settings, String key) {
        String value = settings.get(key, "");
        return value == null ? "" : value;
    }

    private static String registryAuthority(String registry) {
        try {
            URI uri = URI.create(registry.contains("://") ? registry : "https://" + registry);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String authority = uri.getRawAuthority().toLowerCase(Locale.ROOT);
            // Docker interprets a bare single-label name as a Docker Hub namespace, not a registry.
            if (!authority.contains(".") && !authority.contains(":") && !authority.equals("localhost")) {
                throw new IllegalArgumentException();
            }
            return authority;
        } catch (IllegalArgumentException ex) {
            throw new RemoteExecutionException("Nexus Registry는 사용자정보·경로가 없는 호스트[:포트] 주소여야 합니다.");
        }
    }

    private static void validateImageReference(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9\\[][A-Za-z0-9._:/@\\[\\]-]*")
                || reference.contains("://")) {
            throw new RemoteExecutionException("반출할 이미지 참조 형식이 올바르지 않습니다.");
        }
        int at = reference.indexOf('@');
        if (at >= 0 && (!reference.substring(at).matches("@sha256:[0-9a-f]{64}") || at == 0)) {
            throw new RemoteExecutionException("이미지 digest 참조 형식이 올바르지 않습니다.");
        }
    }

    static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("No private filesystem permission support");
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }

    Path directory() { return directory; }
    String registry() { return registry; }
    String imageReference() { return imageReference; }
    String username() { return username; }
    String password() { return password; }
    boolean authenticated() { return !username.isBlank(); }

    void configure(ProcessBuilder builder) {
        // Never inherit a remote Docker endpoint/context (including ssh://) or TLS
        // settings. This service exports through its own local attached engine.
        builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("DOCKER_"));
        builder.environment().put("DOCKER_HOST", "unix:///var/run/docker.sock");
        builder.environment().put("DOCKER_CONFIG", directory.toString());
    }

    String redact(String text) {
        String safe = text == null ? "" : text;
        if (authenticated()) {
            for (String secret : redactions) safe = safe.replace(secret, "[REDACTED]");
        }
        return safe.replaceAll("(?i)(authorization\\s*[:=]\\s*(?:basic|bearer)\\s+)[^\\s]+", "$1[REDACTED]");
    }

    @Override public void close() { deleteDirectory(directory); }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new RemoteExecutionException("임시 Docker 인증 설정을 삭제하지 못했습니다. 앱 서버 임시파일 권한을 확인하세요.");
        }
    }
}
