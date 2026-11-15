package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.dto.AgentProtocol;
import com.portfolio.deployops.repository.ServerRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AgentQueueService {
    private final JdbcTemplate jdbc;
    private final ServerRepository servers;
    private final SecretCryptoService crypto;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private static final Set<String> UNFINISHED = Set.of("QUEUED", "CLAIMED", "RUNNING", "UNKNOWN");
    private static final int OUTPUT_LIMIT = 2097152;
    public record Registration(long serverId, long generation, String state, String enrollmentHash,
        LocalDateTime enrollmentExpires, String credentialHash, String machineId, String hostname,
        String version, String role, LocalDateTime lastSeen, String readiness) { }
    public record Task(String id, long serverId, long generation, String kind, String state, String payload,
        boolean sensitive, int timeoutSec, LocalDateTime createdAt, LocalDateTime expiresAt,
        LocalDateTime startedAt, LocalDateTime finishedAt, Integer exitCode, String stdout, String stderr,
        Long durationMs, String resultHash, String artifactPath, String artifactSha256, Long artifactSize) { }

    @Transactional
    public Map<String, Object> issueCode(long serverId) {
        requireAgentServer(serverId);
        Registration previous = registration(serverId, true);
        if(previous != null && "ACTIVE".equals(previous.state()))
            throw conflict("사용 중인 에이전트는 먼저 등록을 해제한 뒤 재등록해 주세요.");
        if (previous != null && busy(serverId, previous.generation()) != null)
            throw conflict("확인 중인 에이전트 작업이 있습니다. 실행 결과를 확인한 뒤 재등록해 주세요.");
        String code = token();
        LocalDateTime now = LocalDateTime.now(), expires = now.plusMinutes(15);
        long generation = previous == null ? 1 : previous.generation() + 1;
        jdbc.update("""
            INSERT INTO server_agent(server_id,generation,state,enrollment_hash,enrollment_expires_at,created_at,updated_at)
            VALUES(?,?,'AWAITING_ENROLLMENT',?,?,?,?)
            ON DUPLICATE KEY UPDATE generation=VALUES(generation),state='AWAITING_ENROLLMENT',
            enrollment_hash=VALUES(enrollment_hash),enrollment_expires_at=VALUES(enrollment_expires_at),
            credential_hash=NULL,machine_id=NULL,hostname=NULL,agent_version=NULL,role=NULL,last_seen_at=NULL,
            readiness_json=NULL,approved_at=NULL,updated_at=VALUES(updated_at)
            """, serverId, generation, sha(code), expires, now, now);
        rotateBinding(serverId);
        return Map.of("serverId", serverId, "enrollmentCode", code, "expiresAt", expires, "state", "AWAITING_ENROLLMENT");
    }

    @Transactional
    public Map<String, Object> enroll(AgentProtocol.Enrollment input) {
        if (input == null || input.serverId() == null) throw bad("서버 등록 정보를 확인해 주세요.");
        Server server = requireAgentServer(input.serverId());
        requireText(input.enrollmentCode(), 256, "등록 코드");
        requireText(input.machineId(), 128, "서버 식별자");
        requireText(input.hostname(), 255, "호스트 이름");
        requireText(input.version(), 64, "에이전트 버전");
        if (!server.getServerType().name().equals(input.role())) throw conflict("등록한 서버 역할과 에이전트 역할이 다릅니다.");
        Registration row = registration(input.serverId(), true);
        if (row == null || !"AWAITING_ENROLLMENT".equals(row.state()) || row.enrollmentExpires() == null
            || row.enrollmentExpires().isBefore(LocalDateTime.now()) || !constantEquals(row.enrollmentHash(), sha(input.enrollmentCode())))
            throw unauthorized();
        Long duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM server_agent WHERE machine_id=? AND server_id<>? AND state IN ('PENDING','ACTIVE')",
            Long.class, input.machineId(), input.serverId());
        if (duplicate != null && duplicate > 0) throw conflict("같은 서버 식별자가 이미 등록되어 있습니다. 복제 서버는 새 식별자로 등록해 주세요.");
        String credential = token();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE server_agent SET state='PENDING',enrollment_hash=NULL,enrollment_expires_at=NULL,credential_hash=?,machine_id=?,hostname=?,agent_version=?,role=?,last_seen_at=?,updated_at=? WHERE server_id=?",
            sha(credential), input.machineId(), input.hostname(), input.version(), input.role(), now, now, input.serverId());
        return Map.of("serverId", input.serverId(), "credential", credential, "state", "PENDING");
    }

    @Transactional
    public void approve(long serverId) {
        approvePending(serverId,null,null,null,false);
    }

    @Transactional
    public void approve(long serverId,String expectedBindingId,String expectedMachineId,String expectedRole) {
        requireText(expectedBindingId,64,"확인한 등록 세대");
        requireText(expectedMachineId,128,"확인한 서버 식별자");
        requireText(expectedRole,8,"확인한 서버 역할");
        approvePending(serverId,expectedBindingId,expectedMachineId,expectedRole,true);
    }

    private void approvePending(long serverId,String expectedBindingId,String expectedMachineId,String expectedRole,boolean strict) {
        Server server = requireAgentServer(serverId);
        Registration row = registration(serverId, true);
        if (row == null || !"PENDING".equals(row.state())) throw conflict("승인 대기 중인 에이전트가 없습니다.");
        if(strict) {
            // Locking read observes the current binding even under MariaDB REPEATABLE READ.
            // Registration code replacement locks server_agent before changing this server row too.
            Map<String,Object> current=jdbc.queryForMap("SELECT control_mode,agent_binding_id,server_type FROM server WHERE id=? FOR UPDATE",serverId);
            if(!"AGENT".equals(current.get("control_mode")) || !Objects.equals(expectedBindingId,current.get("agent_binding_id"))
                || !Objects.equals(expectedMachineId,row.machineId()) || !Objects.equals(expectedRole,row.role())
                || !Objects.equals(expectedRole,current.get("server_type")))
                throw conflict("화면을 확인한 뒤 에이전트 등록 정보가 변경되었습니다. 현재 호스트 정보를 다시 확인해 주세요.");
        }
        if (!server.getServerType().name().equals(row.role())) throw conflict("서버 역할이 바뀌었습니다. 에이전트를 재등록해 주세요.");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE server_agent SET state='ACTIVE',approved_at=?,updated_at=? WHERE server_id=?", now, now, serverId);
        rotateBinding(serverId);
    }

    @Transactional
    public void revoke(long serverId) {
        Registration row = registration(serverId, true);
        if (row == null) throw conflict("등록된 에이전트가 없습니다.");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE server_agent SET state='REVOKED',credential_hash=NULL,enrollment_hash=NULL,enrollment_expires_at=NULL,machine_id=NULL,updated_at=? WHERE server_id=?", now, serverId);
        rotateBinding(serverId);
        jdbc.update("UPDATE server_agent_task SET state='CANCELLED',finished_at=? WHERE server_id=? AND generation=? AND state='QUEUED'", now, serverId, row.generation());
        jdbc.update("UPDATE server_agent_task SET state='UNKNOWN' WHERE server_id=? AND generation=? AND state IN ('CLAIMED','RUNNING')", serverId, row.generation());
    }

    @Transactional
    public void resolveUnknown(long serverId,String taskId,String reason,boolean executionStopped) {
        requireServer(serverId);
        requireText(reason,1000,"확인 사유");
        if(!executionStopped) throw bad("해당 서버에서 작업이 중지된 것을 확인해야 합니다.");
        Registration row=registration(serverId,true);
        if(row == null || !"REVOKED".equals(row.state())) throw conflict("먼저 에이전트 등록을 해제해 기존 인증을 폐기해 주세요.");
        Task task=task(taskId,true);
        if(task == null || task.serverId()!=serverId || task.generation()!=row.generation() || !"UNKNOWN".equals(task.state()))
            throw conflict("확인 대기 중인 에이전트 작업이 아닙니다.");
        jdbc.update("UPDATE server_agent_task SET state='RESOLVED_UNKNOWN',finished_at=? WHERE id=? AND state='UNKNOWN'",LocalDateTime.now(),task.id());
        if(task.artifactPath()!=null) {
            Path file=Path.of(task.artifactPath());
            Path pool=AgentDispatchService.artifactPool();
            if(file.toAbsolutePath().normalize().getParent().equals(pool) && !Files.isSymbolicLink(file)) {
                try { Files.deleteIfExists(file); } catch(java.io.IOException ignored) { }
            }
        }
    }

    @Transactional
    public void resolveReadOnlyQuery(long serverId, String taskId, String reason, boolean acknowledged) {
        Server server = requireAgentServer(serverId);
        requireText(reason, 1000, "조회 재개 사유");
        if (!acknowledged) throw bad("읽기 전용 조회만 종료하며 원래 작업 성공을 뜻하지 않음을 확인해 주세요.");
        Registration row = registration(serverId, true);
        if (row==null || !"ACTIVE".equals(row.state())) throw conflict("현재 승인된 에이전트 등록이 필요합니다.");
        Task task = task(taskId, true);
        if (task==null || task.serverId()!=serverId || task.generation()!=row.generation()
                || !readOnlyQueryRecoverable(server, task))
            throw conflict("이 작업은 안전한 읽기 전용 Nexus 결과 조회로 확인되지 않았습니다. 일반 작업의 실행 중지·등록 해제 절차를 사용하세요.");
        if (jdbc.update("UPDATE server_agent_task SET state='RESOLVED_UNKNOWN',finished_at=? WHERE id=? AND state='UNKNOWN'",
                LocalDateTime.now(), task.id())!=1) throw conflict("작업 상태가 변경되었습니다. 새로 조회해 주세요.");
    }

    private boolean readOnlyQueryRecoverable(Server server, Task task) {
        return "UNKNOWN".equals(task.state()) && readOnlyQueryPayload(server, task);
    }

    private boolean readOnlyQueryPayload(Server server, Task task) {
        if ((server.getServerType()!=ServerType.DEPLOY && server.getServerType()!=ServerType.NEXUS)
                || !"EXEC".equals(task.kind()) || task.sensitive()
                || task.artifactPath()!=null || task.artifactSha256()!=null || task.artifactSize()!=null) return false;
        try { return NexusReadOnlyAgentTask.allowed(crypto.decryptIfEncrypted(task.payload())); }
        catch (RuntimeException rejected) { return false; }
    }

    public Map<String, Object> status(long serverId) {
        requireServer(serverId);
        Registration row = registration(serverId, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverId", serverId);
        result.put("agentBindingId", requireServer(serverId).getAgentBindingId());
        result.put("registrationState", row == null ? "NOT_REGISTERED" : row.state());
        boolean connected = row != null && Set.of("PENDING","ACTIVE").contains(row.state()) && fresh(row.lastSeen());
        result.put("connected", connected);
        result.put("connectionState", row == null ? "NOT_REGISTERED" : connected ? "CONNECTED" : "DISCONNECTED");
        if (row != null) {
            result.put("hostname", row.hostname()); result.put("machineId", row.machineId());
            result.put("version", row.version()); result.put("role", row.role());
            result.put("lastSeenAt", row.lastSeen());
            result.put("readiness", jsonMap(row.readiness()));
            Task task = busy(serverId, row.generation());
            if (task != null) result.put("busyTask", Map.of("id", task.id(), "state", task.state(), "kind", task.kind(), "createdAt", task.createdAt(), "readOnlyQueryRecoverable", "ACTIVE".equals(row.state()) && readOnlyQueryRecoverable(requireServer(serverId), task)));
        }
        result.put("recentTasks",jdbc.query("SELECT id,kind,state,created_at,finished_at,exit_code FROM server_agent_task WHERE server_id=? ORDER BY created_at DESC,id DESC LIMIT 10",
            (r,n)-> {
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("id",r.getString("id")); item.put("kind",r.getString("kind")); item.put("state",r.getString("state"));
                item.put("createdAt",time(r,"created_at")); item.put("finishedAt",time(r,"finished_at"));
                item.put("exitCode",r.getObject("exit_code")); return item;
            },serverId));
        return result;
    }

    @Transactional
    public Map<String, Object> heartbeat(String authorization, AgentProtocol.Heartbeat body) {
        Registration row = authenticate(authorization, false);
        if (body == null) throw bad("에이전트 상태가 없습니다.");
        requireText(body.hostname(), 255, "호스트 이름"); requireText(body.version(), 64, "에이전트 버전");
        String readiness = serialize(body.readiness() == null ? Map.of() : body.readiness());
        if (readiness.length() > 16384) throw bad("에이전트 상태 정보가 너무 큽니다.");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE server_agent SET hostname=?,agent_version=?,readiness_json=?,last_seen_at=?,updated_at=? WHERE server_id=?",
            body.hostname(), body.version(), readiness, now, now, row.serverId());
        return Map.of("state", row.state(), "serverId", row.serverId());
    }

    @Transactional
    public Map<String, Object> poll(String authorization) {
        Registration identity = authenticate(authorization, true);
        registration(identity.serverId(), true);
        jdbc.update("UPDATE server_agent SET last_seen_at=?,updated_at=? WHERE server_id=?", LocalDateTime.now(), LocalDateTime.now(), identity.serverId());
        Task current = busy(identity.serverId(), identity.generation());
        if (current == null) return Collections.singletonMap("task", null);
        if (current.expiresAt().isBefore(LocalDateTime.now()) && !"UNKNOWN".equals(current.state())) {
            jdbc.update("UPDATE server_agent_task SET state='UNKNOWN' WHERE id=? AND state IN ('QUEUED','CLAIMED','RUNNING')", current.id());
            return Collections.singletonMap("task", null);
        }
        if ("UNKNOWN".equals(current.state())) return Collections.singletonMap("task", null);
        if ("QUEUED".equals(current.state())) jdbc.update("UPDATE server_agent_task SET state='CLAIMED' WHERE id=? AND state='QUEUED'", current.id());
        Map<String, Object> task = new LinkedHashMap<>(jsonMap(crypto.decryptIfEncrypted(current.payload())));
        task.put("id", current.id()); task.put("kind", current.kind());
        task.put("sensitive", current.sensitive()); task.put("timeoutSec", current.timeoutSec());
        return Map.of("task", task);
    }

    @Transactional
    public Map<String, Object> started(String authorization, String taskId) {
        Registration row = authenticate(authorization, true);
        Task task = ownTask(row, taskId, true);
        if ("RUNNING".equals(task.state()) || "SUCCEEDED".equals(task.state()) || "FAILED".equals(task.state())) return Map.of("state", task.state());
        if (!"CLAIMED".equals(task.state())) throw conflict("실행을 시작할 수 없는 작업입니다.");
        if (task.expiresAt().isBefore(LocalDateTime.now())) throw conflict("작업 유효 시간이 지났습니다. 같은 작업을 다시 실행하지 마세요.");
        jdbc.update("UPDATE server_agent_task SET state='RUNNING',started_at=? WHERE id=? AND state='CLAIMED'", LocalDateTime.now(), task.id());
        return Map.of("state", "RUNNING");
    }

    @Transactional
    public Map<String, Object> result(String authorization, String taskId, AgentProtocol.Result input) {
        Registration row = authenticate(authorization, true);
        // Serialize a late result with explicit read-only query retirement. The
        // task lookup uses an independent transaction, so its row lock alone
        // cannot protect the caller's following update.
        Registration locked = registration(row.serverId(), true);
        if (locked==null || !"ACTIVE".equals(locked.state()) || locked.generation()!=row.generation()
                || !constantEquals(locked.credentialHash(), row.credentialHash())) throw unauthorized();
        Task task = ownTask(locked, taskId, true);
        if (input == null || !Set.of("SUCCEEDED", "FAILED", "UNCERTAIN").contains(input.state() == null ? "" : input.state())) throw bad("실행 결과 상태를 확인해 주세요.");
        int exit = input.exitCode() == null ? -1 : input.exitCode();
        if ("SUCCEEDED".equals(input.state()) && exit != 0) throw bad("완료 작업의 종료 코드를 확인해 주세요.");
        if ("FAILED".equals(input.state()) && exit == 0) throw bad("실패 작업의 종료 코드를 확인해 주세요.");
        String state = "UNCERTAIN".equals(input.state()) ? "UNKNOWN" : input.state();
        String stdout = task.sensitive() ? "" : bounded(input.stdout()), stderr = task.sensitive() ? "" : bounded(input.stderr());
        long duration = input.durationMs() == null ? 0 : input.durationMs();
        if (duration < 0 || duration > 86400000L) throw bad("실행 시간을 확인해 주세요.");
        Map<String,Object> normalized = new LinkedHashMap<>();
        normalized.put("state",state); normalized.put("exitCode",exit); normalized.put("stdout",stdout);
        normalized.put("stderr",stderr); normalized.put("durationMs",duration);
        String hash = sha(serialize(normalized));
        // An explicitly retired file query may finish late. Acknowledge receipt
        // without accepting its stale contents or claiming operational success.
        if ("RESOLVED_UNKNOWN".equals(task.state()) && readOnlyQueryPayload(requireAgentServer(row.serverId()), task))
            return Map.of("state", task.state());
        if (task.resultHash() != null) {
            if (!constantEquals(task.resultHash(), hash)) throw conflict("이미 저장된 결과와 다릅니다. 작업을 다시 실행하지 마세요.");
            return Map.of("state", task.state());
        }
        if (!Set.of("CLAIMED", "RUNNING", "UNKNOWN").contains(task.state())) throw conflict("결과를 받을 수 없는 작업입니다.");
        jdbc.update("UPDATE server_agent_task SET state=?,finished_at=?,exit_code=?,stdout_text=?,stderr_text=?,duration_ms=?,result_hash=? WHERE id=?",
            state, LocalDateTime.now(), exit, encryptOutput(stdout), encryptOutput(stderr), duration, hash, task.id());
        jdbc.update("UPDATE server_agent SET last_seen_at=?,updated_at=? WHERE server_id=? AND generation=? AND state='ACTIVE'",
            LocalDateTime.now(),LocalDateTime.now(),row.serverId(),row.generation());
        return Map.of("state", state);
    }

    public Path artifact(String authorization, String taskId) {
        Registration row = authenticate(authorization, true);
        Task task = ownTask(row, taskId, false);
        if (!"COPY".equals(task.kind()) || task.artifactPath() == null || !Set.of("CLAIMED", "RUNNING").contains(task.state())) throw conflict("받을 수 있는 전송 파일이 없습니다.");
        Path file = Path.of(task.artifactPath());
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != task.artifactSize() || !sha(file).equals(task.artifactSha256())) throw conflict("전송 파일이 바뀌었습니다. 배포 상태를 확인해 주세요.");
        } catch (java.io.IOException e) { throw conflict("전송 파일을 읽을 수 없습니다."); }
        return file;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Task enqueue(Server server, String kind, Map<String, Object> payload, boolean sensitive, int timeoutSec,
                        Path artifact, String artifactHash, Long artifactSize) {
        Server currentServer = requireAgentServer(server.getId());
        if (server.getAgentBindingId() == null || !Objects.equals(server.getAgentBindingId(), currentServer.getAgentBindingId()))
            throw new RemoteExecutionException("서버 에이전트 등록 정보가 바뀌었습니다. 현재 서버 정보를 다시 확인해 주세요.");
        Registration row = registration(server.getId(), true);
        if (row == null) throw new RemoteExecutionException("에이전트가 등록되지 않았습니다. 서버의 연결 상태를 확인해 주세요.");
        Task current = busy(server.getId(), row.generation());
        if (current != null) throw new AgentOutcomeUnknownException("서버의 이전 작업 결과를 확인 중입니다. 중복 명령과 자동 복구는 중지했습니다. 작업 " + current.id());
        if (!"ACTIVE".equals(row.state()) || !fresh(row.lastSeen())) throw new RemoteExecutionException("에이전트가 연결·승인되지 않았습니다. 서버의 연결 상태를 확인해 주세요.");
        if (timeoutSec < 1 || timeoutSec > 7200) throw bad("작업 제한 시간을 확인해 주세요.");
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String json=serialize(payload);
        if(json.length()>2097152) throw bad("에이전트 작업 입력이 너무 큽니다.");
        String encrypted = crypto.encryptIfPlain(json);
        jdbc.update("""
            INSERT INTO server_agent_task(id,server_id,generation,kind,state,payload_encrypted,is_sensitive,timeout_sec,created_at,expires_at,artifact_path,artifact_sha256,artifact_size)
            VALUES(?,?,?,?,'QUEUED',?,?,?,?,?,?,?,?)
            """, id, server.getId(), row.generation(), kind, encrypted, sensitive, timeoutSec, now, now.plusSeconds(timeoutSec), artifact == null ? null : artifact.toString(), artifactHash, artifactSize);
        return task(id, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Task task(String id, boolean lock) {
        List<Task> rows = jdbc.query("SELECT * FROM server_agent_task WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::taskRow, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(String id) {
        jdbc.update("UPDATE server_agent_task SET state='UNKNOWN' WHERE id=? AND state IN ('QUEUED','CLAIMED','RUNNING')", id);
    }

    public Registration authenticate(String authorization, boolean active) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 512) throw unauthorized();
        String credential = authorization.substring(7);
        if (!credential.matches("[A-Za-z0-9_-]{40,128}")) throw unauthorized();
        List<Registration> rows = jdbc.query("SELECT * FROM server_agent WHERE credential_hash=?", this::registrationRow, sha(credential));
        if (rows.size() != 1) throw unauthorized();
        Registration row = rows.getFirst();
        if (!Set.of("PENDING", "ACTIVE").contains(row.state()) || active && !"ACTIVE".equals(row.state())) throw unauthorized();
        Server server = requireAgentServer(row.serverId());
        if (!server.getServerType().name().equals(row.role())) throw unauthorized();
        return row;
    }

    private Task ownTask(Registration row, String id, boolean lock) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw bad("작업 식별자를 확인해 주세요.");
        Task task = task(id, lock);
        if (task == null || task.serverId() != row.serverId() || task.generation() != row.generation()) throw unauthorized();
        return task;
    }

    private Task busy(long serverId, long generation) {
        List<Task> rows = jdbc.query("SELECT * FROM server_agent_task WHERE server_id=? AND generation=? AND state IN ('QUEUED','CLAIMED','RUNNING','UNKNOWN') ORDER BY created_at,id LIMIT 1", this::taskRow, serverId, generation);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Registration registration(long serverId, boolean lock) {
        List<Registration> rows = jdbc.query("SELECT * FROM server_agent WHERE server_id=?" + (lock ? " FOR UPDATE" : ""), this::registrationRow, serverId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Server requireServer(long id) {
        return servers.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "서버를 찾을 수 없습니다."));
    }
    private void rotateBinding(long id) { jdbc.update("UPDATE server SET agent_binding_id=? WHERE id=?", UUID.randomUUID().toString(), id); }
    private Server requireAgentServer(long id) {
        Server server = requireServer(id);
        if (!Set.of(ServerType.WAS, ServerType.WEB, ServerType.DEPLOY, ServerType.GITLAB, ServerType.NEXUS, ServerType.RUNNER).contains(server.getServerType())) throw bad("이 서버 역할은 에이전트 실행을 지원하지 않습니다.");
        if (!"AGENT".equals(String.valueOf(server.getControlMode()))) throw conflict("서버 제어 방식을 에이전트로 설정해 주세요.");
        return server;
    }
    private Registration registrationRow(ResultSet r, int n) throws SQLException {
        return new Registration(r.getLong("server_id"), r.getLong("generation"), r.getString("state"), r.getString("enrollment_hash"), time(r,"enrollment_expires_at"), r.getString("credential_hash"), r.getString("machine_id"), r.getString("hostname"), r.getString("agent_version"), r.getString("role"), time(r,"last_seen_at"), r.getString("readiness_json"));
    }
    private Task taskRow(ResultSet r, int n) throws SQLException {
        return new Task(r.getString("id"),r.getLong("server_id"),r.getLong("generation"),r.getString("kind"),r.getString("state"),r.getString("payload_encrypted"),r.getBoolean("is_sensitive"),r.getInt("timeout_sec"),time(r,"created_at"),time(r,"expires_at"),time(r,"started_at"),time(r,"finished_at"),(Integer)r.getObject("exit_code"),decryptOutput(r.getString("stdout_text")),decryptOutput(r.getString("stderr_text")), (Long)r.getObject("duration_ms"),r.getString("result_hash"),r.getString("artifact_path"),r.getString("artifact_sha256"),(Long)r.getObject("artifact_size"));
    }
    private static LocalDateTime time(ResultSet r,String key) throws SQLException { var t = r.getTimestamp(key); return t == null ? null : t.toLocalDateTime(); }
    private static final String OUTPUT_ENVELOPE="agent-output:v1:";
    private String encryptOutput(String value) {
        // The envelope ensures literal application output beginning with enc:v2: is still encrypted.
        return value == null || value.isEmpty() ? value : crypto.encryptIfPlain(OUTPUT_ENVELOPE+value);
    }
    private String decryptOutput(String value) {
        if(value == null || value.isEmpty() || !crypto.isEncrypted(value)) return value;
        String plaintext=crypto.decryptIfEncrypted(value);
        if(!plaintext.startsWith(OUTPUT_ENVELOPE)) throw new IllegalStateException("에이전트 출력 저장 형식이 올바르지 않습니다.");
        return plaintext.substring(OUTPUT_ENVELOPE.length());
    }
    private static boolean fresh(LocalDateTime time) { return time != null && time.isAfter(LocalDateTime.now().minusSeconds(60)); }
    private String serialize(Object value) { return mapper.writeValueAsString(value); }
    @SuppressWarnings("unchecked")
    private Map<String,Object> jsonMap(String json) { return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, Map.class); }
    private String token() { byte[] bytes = new byte[48]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static void requireText(String value, int max, String label) { if(value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(c -> c < 32)) throw bad(label + "을 확인해 주세요."); }
    private static String bounded(String value) { if (value == null) return ""; if (value.length() > OUTPUT_LIMIT) throw bad("작업 출력이 너무 큽니다."); return value; }
    public static String sha(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String sha(Path file) throws java.io.IOException { try { MessageDigest hash=MessageDigest.getInstance("SHA-256"); try(var in=Files.newInputStream(file)) { byte[] b=new byte[1048576]; for(int n;(n=in.read(b))>=0;) if(n>0) hash.update(b,0,n); } return HexFormat.of().formatHex(hash.digest()); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    static boolean constantEquals(String a,String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"에이전트 등록·인증 상태를 확인해 주세요."); }
    private static ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
    private static ResponseStatusException bad(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,text); }
}
