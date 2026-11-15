package com.portfolio.deployops.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 배포망에서 산출물을 파일로 뽑아낸다 — push 전달 방식의 핵심.
 *
 * <p>대상 서버는 레지스트리에 붙지 못하므로(3망 구조) 배포망이 자기 docker CLI 로
 * 이미지를 확보해 대상에 밀어 넣는다. 두 가지를 만든다.
 *
 * <ul>
 *   <li>{@link #exportImage} — pull → save 로 이미지 tar. 대상에서 {@code docker load} 한다</li>
 *   <li>{@link #extractStaticBundle} — 이미지에서 정적 파일만 꺼내 tar.gz.
 *     컨테이너를 <b>기동하지 않고</b> {@code docker create/cp} 로 꺼내므로 ENTRYPOINT 가 돌지 않는다.
 *     덕분에 DMZ(WEB)에 docker 가 필요 없다</li>
 * </ul>
 *
 * <p>둘 다 sha256 을 함께 돌려주고, 대상 서버가 풀기 전에 그 값으로 무결성을 확인한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageArtifactService {

    private static final long DOCKER_TIMEOUT_SEC = 600;
    private static final int MAX_DOCKER_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_DOCKER_CONFIG_BYTES = 32 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SystemSettingService settingService;

    /** Registry manifest digest와 구별되는 Docker config ID + 전송 파일 SHA-256. */
    public record ImageArchive(String sha256, String configId) {
        public ImageArchive {
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")
                    || configId == null || !configId.matches("sha256:[a-f0-9]{64}")) {
                throw new RemoteExecutionException("이미지 반출 무결성 메타데이터가 유효하지 않습니다.");
            }
        }
    }

    /** 승인 이미지를 Docker archive로 저장하고 archive 내부 config digest를 반환한다. */
    public ImageArchive exportImage(String imageRef, Path tarPath, Consumer<String> logSink) {
        try (DockerArtifactSession session = openDockerSession(imageRef)) {
            logSink.accept(session.redact("[artifact] 이미지 pull: " + imageRef));
            runDocker(List.of("docker", "pull", session.imageReference()), session, null, logSink, DOCKER_TIMEOUT_SEC);
            logSink.accept("[artifact] 이미지 tar 반출: " + tarPath.getFileName());
            // Docker 29 / containerd image store can report a manifest-list digest as .Id,
            // while Docker 26 on the offline target requires the selected platform's config ID.
            // Save the approved ref and derive the runnable config ID from the actual archive.
            runDocker(List.of("docker", "save", "-o", tarPath.toString(), session.imageReference()),
                session, null, logSink, DOCKER_TIMEOUT_SEC);
            String configId = configIdFromDockerArchive(tarPath, session);
            logSink.accept(session.redact("[artifact] 승인 참조=" + imageRef + " → archive config ID=" + configId));
            String sha256 = sha256Hex(tarPath);
            logSink.accept("[artifact] image.tar sha256=" + sha256);
            return new ImageArchive(sha256, configId);
        }
    }

    /**
     * 이미지에서 정적 산출물만 꺼내 tar.gz 로 묶고, 그 파일의 sha256(hex)을 돌려준다.
     *
     * <p><b>배포망에서 수행한다.</b> 컨테이너를 띄우지 않고 {@code create/cp/rm} 만 하므로
     * 이미지의 ENTRYPOINT 가 실행되지 않고, DMZ 에는 결과 tar.gz 만 보내므로
     * 공개 접점인 WEB 서버에 docker 런타임을 두지 않아도 된다.
     *
     * @param imagePath 이미지 안의 산출물 경로(예: {@code /dist})
     */
    public String extractStaticBundle(String imageRef, String imagePath, Path bundlePath, Consumer<String> logSink) {
        String container = "deployops-extract-" + System.nanoTime();
        try (DockerArtifactSession session = openDockerSession(imageRef)) {
            Path workDir = null;
            try {
                workDir = Files.createTempDirectory("deploy-ops-static-");
                logSink.accept(session.redact("[정적] 이미지 확보: " + imageRef));
                runDocker(List.of("docker", "pull", session.imageReference()), session, null, logSink, DOCKER_TIMEOUT_SEC);

                logSink.accept(session.redact("[정적] 산출물 추출: " + imagePath + " (컨테이너 미실행)"));
                runDocker(List.of("docker", "create", "--name", container, session.imageReference()), session, null, logSink, DOCKER_TIMEOUT_SEC);
                runDocker(List.of("docker", "cp", container + ":" + imagePath + "/.", workDir.toString()), session, null, logSink, DOCKER_TIMEOUT_SEC);

                runDocker(List.of("tar", "-czf", bundlePath.toString(), "-C", workDir.toString(), "."), session, null, logSink, DOCKER_TIMEOUT_SEC);
                String sha256 = sha256Hex(bundlePath);
                logSink.accept("[정적] 번들 sha256=" + sha256);
                return sha256;
            } catch (IOException e) {
                throw new RemoteExecutionException("정적 번들 작업 디렉터리 생성 실패: " + session.redact(e.getMessage()));
            } finally {
                runDockerQuietly(List.of("docker", "rm", "-f", container), session);
                deleteRecursivelyQuietly(workDir);
            }
        }
    }

    private DockerArtifactSession openDockerSession(String imageRef) {
        return DockerArtifactSession.open(imageRef, settingService);
    }

    /** 정리용 — 실패해도 배포 결과를 바꾸지 않는다(이미 없는 컨테이너 삭제 등). */
    private void runDockerQuietly(List<String> command, DockerArtifactSession session) {
        try {
            runDocker(command, session, null, ignored -> {}, 30);
        } catch (RemoteExecutionException e) {
            log.debug("정리 명령 실패(무시): {}", e.getMessage());
        }
    }

    private void deleteRecursivelyQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 임시 디렉터리 정리 실패는 배포 결과와 무관하다.
                }
            });
        } catch (IOException e) {
            log.debug("임시 디렉터리 정리 실패(무시): {}", dir, e);
        }
    }

    static String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RemoteExecutionException("image.tar 체크섬 계산 실패: " + e.getMessage(), e);
        }
    }

    /** Test seam: tests provide in-memory processes, never a Docker daemon. */
    Process startProcess(ProcessBuilder builder) throws IOException { return builder.start(); }

    private String runDocker(List<String> command, DockerArtifactSession session, String stdin,
                           Consumer<String> logSink, long timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command);
        session.configure(builder);
        builder.redirectErrorStream(true);
        String display = stdin == null ? session.redact(String.join(" ", command)) : "docker login " + session.registry();
        Process process = null;
        Thread readerThread = null;
        ArrayDeque<String> tail = new ArrayDeque<>();
        AtomicReference<Exception> readFailure = new AtomicReference<>();
        try {
            process = startProcess(builder);
            InputStream output = process.getInputStream();
            readerThread = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))) {
                    String line;
                    int emitted = 0;
                    while ((line = reader.readLine()) != null) {
                        String safe = session.redact(line);
                        if (safe.length() > 2048) safe = safe.substring(0, 2048) + "…";
                        if (!safe.isBlank()) {
                            synchronized (tail) {
                                if (tail.size() == 5) tail.removeFirst();
                                tail.addLast(safe);
                            }
                            if (stdin == null && emitted++ < 20) logSink.accept("[docker] " + safe);
                        }
                    }
                } catch (Exception ex) {
                    readFailure.set(ex);
                }
            });
            IOException inputFailure = null;
            byte[] input = stdin == null ? new byte[0] : (stdin + "\n").getBytes(StandardCharsets.UTF_8);
            try (var stream = process.getOutputStream()) {
                stream.write(input);
            } catch (IOException ex) {
                inputFailure = ex;
            } finally {
                Arrays.fill(input, (byte) 0);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new RemoteExecutionException("docker 명령 타임아웃: " + display);
            }
            readerThread.join(5000);
            if (readerThread.isAlive()) throw new RemoteExecutionException("docker 출력 읽기 타임아웃: " + display);
            if (process.exitValue() != 0) {
                String detail;
                synchronized (tail) { detail = String.join(" | ", tail); }
                throw new RemoteExecutionException("docker 명령 실패(exit=" + process.exitValue() + "): " + display
                    + (detail.isEmpty() ? "" : " — " + detail));
            }
            if (inputFailure != null) throw inputFailure;
            if (readFailure.get() != null) throw new IOException(session.redact(readFailure.get().getMessage()));
            synchronized (tail) { return String.join("\n", tail); }
        } catch (IOException e) {
            // Do not attach a raw process exception: its cause can contain unredacted output/arguments.
            throw new RemoteExecutionException("docker 실행 실패: " + display + " — " + session.redact(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteExecutionException("docker 명령이 인터럽트되었습니다.");
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
        }
    }

    private String configIdFromDockerArchive(Path archive, DockerArtifactSession session) {
        try {
            byte[] manifestBytes = readDockerArchiveEntry(archive, "manifest.json", session, MAX_DOCKER_MANIFEST_BYTES);
            JsonNode manifests = JSON.readTree(manifestBytes);
            if (manifests == null || !manifests.isArray() || manifests.size() != 1) {
                throw new RemoteExecutionException("이미지 tar에는 단일 플랫폼 이미지 하나만 포함되어야 합니다.");
            }
            JsonNode configNode = manifests.get(0).get("Config");
            if (configNode == null || !configNode.isTextual()) {
                throw new RemoteExecutionException("이미지 tar의 config 항목을 확인할 수 없습니다.");
            }
            String configPath = configNode.asText();
            if (!safeArchiveEntry(configPath)) {
                throw new RemoteExecutionException("이미지 tar의 config 경로가 올바르지 않습니다.");
            }
            String filename = configPath.substring(configPath.lastIndexOf('/') + 1);
            if (!filename.matches("[a-f0-9]{64}(?:\\.json)?")) {
                throw new RemoteExecutionException("이미지 tar의 config digest 경로가 올바르지 않습니다.");
            }
            byte[] configBytes = readDockerArchiveEntry(archive, configPath, session, MAX_DOCKER_CONFIG_BYTES);
            String actualDigest = sha256Hex(configBytes);
            String pathDigest = filename.endsWith(".json")
                ? filename.substring(0, filename.length() - ".json".length()) : filename;
            if (!actualDigest.equals(pathDigest)) {
                throw new RemoteExecutionException("이미지 tar config digest가 파일 내용과 일치하지 않습니다.");
            }
            return "sha256:" + actualDigest;
        } catch (RemoteExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RemoteExecutionException("이미지 tar의 config ID를 확인할 수 없습니다.");
        }
    }

    private static boolean safeArchiveEntry(String entry) {
        if (entry == null || entry.isBlank() || entry.startsWith("/") || entry.contains("\\")
                || !entry.matches("[A-Za-z0-9._/-]+")) return false;
        for (String part : entry.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private byte[] readDockerArchiveEntry(Path archive, String entry, DockerArtifactSession session, int maxBytes) {
        if (!safeArchiveEntry(entry)) throw new RemoteExecutionException("이미지 tar 항목 경로가 올바르지 않습니다.");
        Process process = null;
        Thread readerThread = null;
        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        try {
            ProcessBuilder builder = new ProcessBuilder("tar", "-xOf", archive.toString(), entry);
            session.configure(builder);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = startProcess(builder);
            process.getOutputStream().close();
            Process started = process;
            readerThread = Thread.ofVirtual().start(() -> {
                try (InputStream input = started.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        if (output.size() + count > maxBytes) {
                            started.destroyForcibly();
                            throw new IOException("Docker archive metadata exceeds limit");
                        }
                        output.write(buffer, 0, count);
                    }
                    result.set(output.toByteArray());
                } catch (IOException ex) {
                    readFailure.set(ex);
                }
            });
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RemoteExecutionException("이미지 tar 메타데이터 추출 시간이 초과되었습니다.");
            }
            readerThread.join(5000);
            if (readerThread.isAlive()) throw new RemoteExecutionException("이미지 tar 메타데이터 읽기가 완료되지 않았습니다.");
            if (process.exitValue() != 0 || readFailure.get() != null || result.get() == null) {
                throw new RemoteExecutionException("이미지 tar 메타데이터 추출에 실패했습니다.");
            }
            return result.get();
        } catch (IOException ex) {
            throw new RemoteExecutionException("tar 실행에 실패했습니다.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RemoteExecutionException("이미지 tar 메타데이터 추출이 중단되었습니다.");
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new RemoteExecutionException("이미지 config 체크섬 계산에 실패했습니다.");
        }
    }
}
