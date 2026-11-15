package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.NexusDependencyUpload;
import com.portfolio.deployops.repository.ExecutionGuardRepository;
import com.portfolio.deployops.repository.NexusDependencyUploadRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;

@Service
public class NexusDependencyUploadService {
    public static final long CHUNK_BYTES = 8L * 1024 * 1024;
    private final NexusDependencyUploadRepository uploads;
    private final ExecutionGuardRepository guard;
    private final ProjectRepository projects;
    private final NexusMetadataService nexus;
    private final SystemSettingService settings;
    private final TransactionTemplate transaction;
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexus-dependency-publisher"); t.setDaemon(true); return t;
    });
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Thread> runningThreads = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();

    public NexusDependencyUploadService(NexusDependencyUploadRepository uploads, ExecutionGuardRepository guard,
            ProjectRepository projects, NexusMetadataService nexus, SystemSettingService settings,
            TransactionTemplate transaction,
            @Value("${deploy.nexus.upload-dir:${user.home}/.deployops/nexus-uploads}") String directory) {
        this.uploads = uploads; this.guard = guard; this.projects = projects; this.nexus = nexus;
        this.settings = settings; this.transaction = transaction; this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public record Binding(String repository, String format, String namespace) {}

    public Binding binding(Long projectId) {
        requireProject(projectId);
        String prefix = "nexus.dependency." + projectId + ".";
        String repository = settings.get(prefix + "repository", "");
        String format = settings.get(prefix + "format", "maven2");
        return new Binding(repository, format, settings.get(prefix + "namespace", ""));
    }

    public void bind(Long projectId, Binding binding) throws Exception {
        requireProject(projectId);
        NexusDependencyPolicy.repository(binding.repository());
        NexusDependencyPolicy.format(binding.format());
        String namespace = binding.namespace() == null ? "" : binding.namespace().trim();
        if (!namespace.isEmpty()) NexusDependencyPolicy.path(namespace);
        nexus.assertDependencyRepository(binding.repository(), binding.format());
        String prefix = "nexus.dependency." + projectId + ".";
        settings.saveAll("nexus-project-repositories", Map.of(prefix + "repository", binding.repository(),
            prefix + "format", binding.format(), prefix + "namespace", namespace));
    }

    public List<NexusDependencyUpload> recent(Long projectId, Long owner) {
        uploads.expireLeases();
        return uploads.recent(projectId, owner);
    }

    public NexusDependencyUpload require(String id, Long projectId, Long owner) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw error("전송 식별자가 올바르지 않습니다.");
        NexusDependencyUpload row = uploads.find(id);
        if (row == null || !projectId.equals(row.getProjectId()) || (owner != null && !owner.equals(row.getOwnerId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "전송 세션을 찾을 수 없습니다.");
        return row;
    }

    public NexusDependencyUpload create(Long projectId, Long owner, String relativePath, long size) throws Exception {
        if (size <= 0) throw error("비어 있지 않은 파일을 선택하세요.");
        Binding binding = binding(projectId);
        String target = NexusDependencyPolicy.target(binding.format(), binding.namespace(), relativePath);
        nexus.assertDependencyRepository(binding.repository(), binding.format());
        ensureDirectory();
        NexusDependencyUpload row = new NexusDependencyUpload();
        row.setId(UUID.randomUUID().toString()); row.setProjectId(projectId); row.setOwnerId(owner);
        row.setRepositoryName(binding.repository()); row.setRepositoryFormat(binding.format());
        row.setNexusBaseUrl(nexus.dependencyBaseUrl()); row.setTargetPath(target);
        row.setTotalBytes(size); row.setStatus("RECEIVING"); row.setMessage("브라우저 파일 전송 대기");
        Path file = file(row);
        Files.createFile(file);
        privatePermissions(file, false);
        try { uploads.insert(row); }
        catch (RuntimeException e) { Files.deleteIfExists(file); throw e; }
        return row;
    }

    public NexusDependencyUpload chunk(String id, Long projectId, Long owner, long offset, long length,
                                      InputStream input) throws Exception {
        NexusDependencyUpload row = require(id, projectId, owner);
        try (FileChannel channel = FileChannel.open(file(row), StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = exclusive(channel)) {
            row = require(id, projectId, owner);
            if (!"RECEIVING".equals(row.getStatus())) throw error("파일을 받을 수 있는 상태가 아닙니다.");
            if (offset >= 0 && offset < row.getReceivedBytes()) {
                NexusDependencyPolicy.chunk(row.getTotalBytes(), offset, offset, length, CHUNK_BYTES);
                if (length > row.getReceivedBytes() - offset) throw error("재개 청크 경계가 기존 파일과 다릅니다.");
                channel.position(offset);
                byte[] incoming = new byte[64 * 1024];
                byte[] existing = new byte[64 * 1024];
                for (long remaining = length; remaining > 0;) {
                    int n = input.read(incoming, 0, (int) Math.min(incoming.length, remaining));
                    if (n < 0) throw error("재개 파일 확인이 중단됐습니다.");
                    ByteBuffer comparison = ByteBuffer.wrap(existing, 0, n);
                    while (comparison.hasRemaining()) if (channel.read(comparison) < 0) throw error("저장 파일이 손상됐습니다.");
                    if (!Arrays.equals(incoming, 0, n, existing, 0, n)) throw error("이전에 선택한 파일과 내용이 다릅니다. 기존 전송을 취소하고 새로 시작하세요.");
                    remaining -= n;
                }
                if (input.read() != -1) throw error("청크 길이가 다릅니다.");
                return row;
            }
            NexusDependencyPolicy.chunk(row.getTotalBytes(), offset, row.getReceivedBytes(), length, CHUNK_BYTES);
            if (Files.getFileStore(directory).getUsableSpace() < length + 16L * 1024 * 1024)
                throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "임시 저장 공간이 부족합니다. 파일은 유지되며 공간 확보 후 재개할 수 있습니다.");
            channel.truncate(offset); channel.position(offset);
            try {
                byte[] bytes = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int n = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
                    if (n < 0) throw error("청크 전송이 끊겼습니다. 같은 파일을 다시 선택해 재개하세요.");
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, n);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    remaining -= n;
                }
                if (input.read() != -1) throw error("선언한 청크 길이보다 데이터가 많습니다.");
                channel.force(true);
                row.setReceivedBytes(offset + length);
                if (row.getReceivedBytes() == row.getTotalBytes()) {
                    row.setStatus("READY"); row.setMessage("파일 전송 완료 · Nexus 등록 전");
                }
                uploads.save(row);
                return row;
            } catch (Exception e) { channel.truncate(offset); throw e; }
        }
    }

    public NexusDependencyUpload publish(String id, Long projectId, Long owner) {
        NexusDependencyUpload row = require(id, projectId, owner);
        FutureTask<Void> task = new FutureTask<>(() -> { runPublish(row); return null; });
        // Claims follow session lock -> payload lock -> database guard. A cancel cannot pass
        // a stale READY check while publish is claiming and registering its worker.
        try (FileChannel coordination = coordination(row); var sessionLock = exclusive(coordination);
             FileChannel channel = FileChannel.open(file(row), StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = exclusive(channel)) {
            transaction.executeWithoutResult(tx -> {
                if (guard.lockSingleton() == null) throw error("실행 잠금이 없습니다.");
                guard.assertNoNexusMaintenance();
                NexusDependencyUpload current = require(id, projectId, owner);
                if (!"READY".equals(current.getStatus()) || current.getReceivedBytes() != current.getTotalBytes())
                    throw error("파일 전송을 먼저 완료하세요.");
                if (uploads.active() != 0) throw error("다른 Nexus 등록 또는 미확정 결과 확인이 진행 중입니다.");
                if (uploads.transition(id,"READY","PUBLISHING","Nexus 등록 준비 중") != 1)
                    throw error("전송 상태가 변경되었습니다. 다시 조회하세요.");
            });
            tasks.put(id, task);
        } catch (java.io.IOException e) {
            throw error("임시 파일을 열 수 없습니다. 전송 상태를 확인하고 같은 파일을 다시 전송하세요.");
        }
        try { worker.execute(task); }
        catch (RuntimeException e) {
            tasks.remove(id); cancelRequested.remove(id);
            uploads.transition(id,"PUBLISHING","READY","등록 작업을 시작하지 못했습니다.");
            throw e;
        }
        return require(id, projectId, owner);
    }

    private void runPublish(NexusDependencyUpload row) {
        final String id = row.getId();
        runningThreads.put(id, Thread.currentThread());
        boolean remoteStarted = false;
        AtomicLong lastHeartbeat = new AtomicLong();
        java.util.function.LongConsumer progress = bytes -> {
            long now = System.nanoTime();
            if (now - lastHeartbeat.get() > TimeUnit.SECONDS.toNanos(2)) {
                uploads.progress(id, bytes); lastHeartbeat.set(now);
            }
        };
        try {
            row = uploads.find(row.getId());
            if (cancelRequested.contains(id)) throw new InterruptedException("취소됨");
            final NexusDependencyUpload active = row;
            nexus.assertDependencyRepository(row.getRepositoryName(), row.getRepositoryFormat());
            if (nexus.dependencyExists(row.getNexusBaseUrl(), row.getRepositoryName(), row.getTargetPath()))
                throw error("같은 경로의 파일이 이미 있습니다. 덮어쓰지 않습니다. 다른 버전 또는 경로를 사용하세요.");
            row.setContentSha256(hash(file(row), () -> progress.accept(0)));
            uploads.save(row);
            remoteStarted = true;
            nexus.publishDependency(row.getNexusBaseUrl(), row.getRepositoryName(), row.getTargetPath(), file(row), progress);
            row.setPublishedBytes(row.getTotalBytes()); row.setMessage("Nexus 등록 응답 수신 · 원격 파일 검증 중"); uploads.save(row);
            if (!"COMPLETE".equals(verifyRemote(row, true, () -> progress.accept(active.getTotalBytes()))))
                throw error("Nexus 원격 파일 또는 checksum 등록이 완료되지 않았습니다.");
            row.setStatus("COMPLETED"); row.setMessage("Nexus 등록 및 파일·checksum 확인 완료"); uploads.save(row);
            Files.deleteIfExists(file(row));
        } catch (Exception e) {
            Thread.interrupted();
            boolean cancelled = !remoteStarted && cancelRequested.contains(id);
            row.setStatus(remoteStarted ? "VERIFY_REQUIRED" : cancelled ? "CANCELLED" : "READY");
            row.setMessage(remoteStarted ? "등록 결과 미확정 · 확인 버튼으로 Nexus 파일을 확인하세요."
                : cancelled ? "전송 취소 · Nexus 등록 전" : safeMessage(e));
            uploads.save(row);
            if (cancelled) try { Files.deleteIfExists(file(row)); } catch (Exception ignored) { /* cleanup may be retried by the operator */ }
        } finally {
            tasks.remove(id); runningThreads.remove(id); cancelRequested.remove(id); Thread.interrupted();
        }
    }

    public NexusDependencyUpload verify(String id, Long projectId, Long owner) throws Exception {
        NexusDependencyUpload row = require(id, projectId, owner);
        try (FileChannel channel = coordination(row); var lock = exclusive(channel)) {
            row = require(id, projectId, owner);
            if (tasks.containsKey(id)) throw error("현재 등록 작업이 끝난 뒤 확인하세요.");
            if (!"VERIFY_REQUIRED".equals(row.getStatus())) throw error("미확정 전송 결과만 확인할 수 있습니다.");
            String result = verifyRemote(row, false, () -> {});
            if ("ABSENT".equals(result) && Files.isRegularFile(file(row), LinkOption.NOFOLLOW_LINKS)
                    && Files.size(file(row)) == row.getTotalBytes()) {
                row.setStatus("READY"); row.setMessage("Nexus에 파일이 없습니다. 다시 등록하거나 취소할 수 있습니다.");
            } else if ("COMPLETE".equals(result)) {
                row.setPublishedBytes(row.getTotalBytes()); row.setStatus("COMPLETED");
                row.setMessage("Nexus 등록 파일·checksum 내용 확인 완료");
            } else {
                row.setStatus("VERIFY_REQUIRED");
                row.setMessage("ABSENT".equals(result) ? "원격 원본이 없고 임시 파일도 없습니다. 관리자 확인이 필요합니다."
                    : "본문은 일치하나 Maven checksum이 누락됐습니다. 누락 checksum 등록 재개를 선택하세요.");
            }
            uploads.save(row);
        }
        if ("COMPLETED".equals(row.getStatus())) Files.deleteIfExists(file(row));
        return row;
    }

    public NexusDependencyUpload cancel(String id, Long projectId, Long owner) throws Exception {
        NexusDependencyUpload row = require(id, projectId, owner);
        if ("COMPLETED".equals(row.getStatus())) throw error("Nexus 등록 완료 파일은 이 화면에서 삭제하지 않습니다.");
        if ("CANCELLED".equals(row.getStatus())) return row;
        boolean[] cancelled = {false};
        try (FileChannel coordination = coordination(row); var sessionLock = exclusive(coordination)) {
            if (tasks.containsKey(id)) {
                cancelRequested.add(id);
                Thread thread = runningThreads.get(id);
                if (thread != null) thread.interrupt();
                return require(id, projectId, owner);
            }
            try (FileChannel channel = FileChannel.open(file(row), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = exclusive(channel)) {
            transaction.executeWithoutResult(tx -> {
                if (guard.lockSingleton() == null) throw error("실행 잠금이 없습니다.");
                NexusDependencyUpload current = require(id,projectId,owner);
                if (tasks.containsKey(id)) {
                    cancelRequested.add(id);
                    Thread thread = runningThreads.get(id);
                    if (thread != null) thread.interrupt();
                    return;
                }
                if (!Set.of("RECEIVING","READY").contains(current.getStatus()))
                    throw error("등록 상태가 변경되었거나 결과가 미확정입니다. 원격 결과를 먼저 확인하세요.");
                if (uploads.transition(id,current.getStatus(),"CANCELLED","전송 취소 · 임시 파일 정리") != 1)
                    throw error("전송 상태가 변경되었습니다. 다시 조회하세요.");
                cancelled[0] = true;
            });
            }
        }
        if (cancelled[0]) Files.deleteIfExists(file(row));
        return require(id,projectId,owner);
    }

    public NexusDependencyUpload repairChecksums(String id, Long projectId, Long owner) throws Exception {
        NexusDependencyUpload row = require(id, projectId, owner);
        if (!mavenPayload(row)) throw error("Maven 본문 파일만 checksum을 보완할 수 있습니다.");
        String previous;
        FutureTask<Void> task = new FutureTask<>(() -> { runChecksumRepair(id); return null; });
        try (FileChannel channel = coordination(row); var lock = exclusive(channel)) {
            row = require(id, projectId, owner);
            previous = row.getStatus();
            if (!Set.of("COMPLETED", "VERIFY_REQUIRED").contains(previous) || tasks.containsKey(id))
                throw error("완료 또는 미확정 Maven 파일만 checksum을 보완할 수 있습니다.");
            if (row.getContentSha256() == null || !row.getContentSha256().matches("[0-9a-f]{64}"))
                throw error("원본 SHA-256 증적이 없습니다. 자동 보완하지 않습니다.");
            final String before = previous;
            transaction.executeWithoutResult(tx -> {
                if (guard.lockSingleton() == null) throw error("실행 잠금이 없습니다.");
                guard.assertNoNexusMaintenance();
                long ownUncertain = "VERIFY_REQUIRED".equals(before) ? 1 : 0;
                if (uploads.active() > ownUncertain) throw error("다른 Nexus 등록 또는 미확정 결과 확인이 진행 중입니다.");
                if (uploads.transition(id, before, "PUBLISHING", "Maven checksum 보완 준비 중") != 1)
                    throw error("전송 상태가 변경되었습니다. 다시 조회하세요.");
            });
            tasks.put(id, task);
        }
        try { worker.execute(task); }
        catch (RuntimeException e) {
            tasks.remove(id); cancelRequested.remove(id);
            uploads.transition(id, "PUBLISHING", previous, "Checksum 보완 작업을 시작하지 못했습니다.");
            throw e;
        }
        return require(id, projectId, owner);
    }

    private void runChecksumRepair(String id) {
        try {
            runningThreads.put(id, Thread.currentThread());
            NexusDependencyUpload row = uploads.find(id);
            if (row == null) throw error("Checksum 보완 세션을 찾을 수 없습니다.");
            AtomicLong last = new AtomicLong();
            Runnable heartbeat = () -> {
                if (cancelRequested.contains(id) || Thread.currentThread().isInterrupted())
                    throw error("Checksum 보완을 취소했습니다. 원격 결과를 확인하세요.");
                long now = System.nanoTime();
                if (now - last.get() > TimeUnit.SECONDS.toNanos(2)) {
                    uploads.progress(id, row.getTotalBytes()); last.set(now);
                }
            };
            heartbeat.run();
            nexus.assertDependencyRepository(row.getRepositoryName(), row.getRepositoryFormat());
            if (!"COMPLETE".equals(verifyRemote(row, true, heartbeat)))
                throw error("원격 원본이 없습니다. 본문을 자동 재등록하지 않습니다.");
            row.setStatus("COMPLETED"); row.setPublishedBytes(row.getTotalBytes());
            row.setMessage("Maven 본문·표준 checksum 확인 완료"); uploads.save(row);
            Files.deleteIfExists(file(row));
        } catch (Exception e) {
            Thread.interrupted();
            try {
                uploads.transition(id, "PUBLISHING", "VERIFY_REQUIRED",
                    "Checksum 보완 결과 미확정 · 등록 결과 확인 후 누락분만 재개하세요.");
            } catch (RuntimeException unavailable) {
                // DB recovery + lease expiry will expose VERIFY_REQUIRED; never retain a dead worker handle.
            }
        } finally {
            tasks.remove(id); runningThreads.remove(id); cancelRequested.remove(id); Thread.interrupted();
        }
    }

    private String verifyRemote(NexusDependencyUpload row, boolean publishChecksums, Runnable heartbeat) throws Exception {
        if (!mavenPayload(row)) {
            String remote = nexus.dependencyDigest(row.getNexusBaseUrl(), row.getRepositoryName(), row.getTargetPath(), heartbeat);
            if (remote == null) return "ABSENT";
            if (!remote.equals(row.getContentSha256())) throw error("원격 파일이 전송 파일과 다릅니다. 자동 덮어쓰기·삭제하지 않습니다.");
            return "COMPLETE";
        }
        var hashes = nexus.dependencyChecksums(row.getNexusBaseUrl(), row.getRepositoryName(), row.getTargetPath(), heartbeat);
        if (hashes == null) return "ABSENT";
        if (!Objects.equals(row.getContentSha256(), hashes.get("sha256")))
            throw error("원격 원본 SHA-256이 다릅니다. Checksum을 등록하지 않습니다.");
        heartbeat.run();
        return nexus.dependencyChecksumFiles(row.getNexusBaseUrl(), row.getRepositoryName(), row.getTargetPath(),
            hashes, publishChecksums, heartbeat) ? "COMPLETE" : "MISSING_CHECKSUMS";
    }

    static boolean mavenPayload(NexusDependencyUpload row) {
        return "maven2".equals(row.getRepositoryFormat())
            && !row.getTargetPath().toLowerCase(Locale.ROOT).matches(".*\\.(sha1|sha256|sha512|md5|asc)$");
    }

    private FileChannel coordination(NexusDependencyUpload row) throws java.io.IOException {
        Path path = directory.resolve(row.getId() + ".lock");
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static java.nio.channels.FileLock exclusive(FileChannel channel) throws java.io.IOException {
        try {
            var lock = channel.tryLock();
            if (lock == null) throw error("파일 전송 요청이 진행 중입니다. 일시정지한 뒤 다시 시도하세요.");
            return lock;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw error("파일 전송 요청이 진행 중입니다. 일시정지한 뒤 다시 시도하세요.");
        }
    }

    private void requireProject(Long id) {
        if (id == null || projects.findById(id).isEmpty()) throw error("프로젝트를 찾을 수 없습니다.");
    }

    private void ensureDirectory() throws Exception {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw error("임시 저장 경로가 올바르지 않습니다.");
        privatePermissions(directory, true);
    }

    private static void privatePermissions(Path path, boolean directory) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    private Path file(NexusDependencyUpload row) {
        if (!row.getId().matches("[0-9a-f-]{36}")) throw error("잘못된 파일 식별자입니다.");
        Path path = directory.resolve(row.getId() + ".part").normalize();
        if (!path.getParent().equals(directory) || Files.isSymbolicLink(path)) throw error("안전하지 않은 임시 파일 경로입니다.");
        return path;
    }

    static String hash(Path file, Runnable heartbeat) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("취소됨");
                digest.update(buffer, 0, n); heartbeat.run();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String safeMessage(Exception e) {
        if (e instanceof ResponseStatusException response && response.getReason() != null) return response.getReason();
        return "등록을 시작하지 못했습니다. Nexus 연결·권한·저장 공간을 확인한 뒤 다시 시도하세요.";
    }
    private static ResponseStatusException error(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
