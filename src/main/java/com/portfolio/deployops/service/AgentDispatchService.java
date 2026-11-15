package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Synchronous adapter used by the existing rollout state machine; transport is persistent outbound jobs. */
@Service
@RequiredArgsConstructor
public class AgentDispatchService {
    private final AgentQueueService queue;
    private final ObjectMapper mapper;

    public RemoteCommandResult execute(Server server, List<String> args, byte[] stdin, boolean sensitive, int timeoutSec) {
        if (args == null || args.isEmpty() || args.size() > 4096 || args.stream().anyMatch(a -> a == null || a.indexOf('\0') >= 0 || a.length() > 65536))
            throw new RemoteExecutionException("에이전트 명령 인자를 확인해 주세요.");
        if (stdin != null && stdin.length > 65536) throw new RemoteExecutionException("에이전트 입력 크기를 확인해 주세요.");
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("argv", List.copyOf(args));
        payload.put("stdinBase64", stdin == null ? null : Base64.getEncoder().encodeToString(stdin));
        return await(queue.enqueue(server,"EXEC",payload,sensitive,timeoutSec,null,null,null), args.isEmpty() ? "agent" : "agent " + args.getFirst());
    }

    public RemoteCommandResult copyFile(Server server, Path localFile, String remotePath) {
        if (remotePath == null || !remotePath.startsWith("/") || remotePath.indexOf('\0') >= 0 || Arrays.asList(remotePath.split("/")).contains(".."))
            throw new RemoteExecutionException("전송 대상 경로를 확인해 주세요.");
        Path staged = null;
        AgentQueueService.Task submitted = null;
        try {
            if (localFile == null || !Files.isRegularFile(localFile,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            Path pool = artifactPool();
            Files.createDirectories(pool);
            if (Files.isSymbolicLink(pool)) throw new IOException();
            try { Files.setPosixFilePermissions(pool,PosixFilePermissions.fromString("rwx------")); } catch(UnsupportedOperationException ignored) { }
            staged = Files.createTempFile(pool,"artifact-",".bin");
            try { Files.setPosixFilePermissions(staged,PosixFilePermissions.fromString("rw-------")); } catch(UnsupportedOperationException ignored) { }
            Files.copy(localFile,staged,StandardCopyOption.REPLACE_EXISTING);
            long size=Files.size(staged); String hash=AgentQueueService.sha(staged);
            Map<String,Object> file=Map.of("path",remotePath,"sha256",hash,"size",size);
            submitted=queue.enqueue(server,"COPY",Map.of("argv",List.of(),"file",file),false,1800,staged,hash,size);
            return await(submitted,"agent file transfer");
        } catch(IOException e) { throw new RemoteExecutionException("에이전트 전송 파일을 준비하지 못했습니다."); }
        finally {
            // UNKNOWN may still be downloading. Durable jobs retain their immutable source until reconciliation.
            if(staged != null) {
                var current=submitted == null ? null : queue.task(submitted.id(),false);
                if(submitted == null || current != null && Set.of("SUCCEEDED","FAILED","CANCELLED","RESOLVED_UNKNOWN").contains(current.state())) {
                    try { Files.deleteIfExists(staged); } catch(IOException ignored) { }
                }
            }
        }
    }

    public Map<String,Object> checkServer(Server server) {
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("id",server.getId()); response.put("name",server.getName());
        Map<String,Object> registration=queue.status(server.getId());
        boolean reachable=Boolean.TRUE.equals(registration.get("connected")) && "ACTIVE".equals(registration.get("registrationState"));
        response.put("reachable",reachable); response.put("ready",false);
        response.put("status",reachable ? "ERROR" : "UNKNOWN");
        response.put("checks",registration);
        if(!reachable) { response.put("message","에이전트 연결과 등록 승인을 확인해 주세요."); return response; }
        try {
            var result=execute(server,List.of("/usr/local/deploy/bin/deploy-agent-probe"),null,false,30);
            Map<String,Object> checks=mapper.readValue(result.stdout(),Map.class);
            boolean ready=result.exitCode()==0 && Boolean.TRUE.equals(checks.get("ready"));
            response.put("checks",checks); response.put("ready",ready); response.put("status",ready ? "NORMAL" : "ERROR");
            response.put("message",ready ? "에이전트 응답과 필수 실행 준비를 확인했습니다." : "에이전트의 Docker/nginx 준비 상태를 확인해 주세요.");
        } catch(AgentOutcomeUnknownException e) {
            response.put("message",e.getMessage()); response.put("status","ERROR");
        } catch(RuntimeException e) {
            response.put("message","연결 테스트를 완료하지 못했습니다. 서버의 에이전트 작업 상태를 확인해 주세요.");
        }
        return response;
    }

    private RemoteCommandResult await(AgentQueueService.Task initial,String display) {
        long deadline=System.nanoTime()+Duration.ofSeconds(initial.timeoutSec()+5L).toNanos();
        while(true) {
            var task=queue.task(initial.id(),false);
            if(task == null) throw unknown(initial.id());
            if(Set.of("SUCCEEDED","FAILED").contains(task.state())) {
                LocalDateTime started=task.startedAt()==null ? task.createdAt() : task.startedAt();
                return new RemoteCommandResult(task.exitCode()==null ? -1 : task.exitCode(),task.sensitive()?"":task.stdout(),task.sensitive()?"":task.stderr(),
                    display+" [task="+task.id()+"]",started,task.finishedAt(),task.durationMs()==null ? 0 : task.durationMs());
            }
            if(Set.of("UNKNOWN","CANCELLED","RESOLVED_UNKNOWN").contains(task.state())) throw unknown(task.id());
            if(System.nanoTime()>=deadline) { queue.markUnknown(task.id()); throw unknown(task.id()); }
            try { Thread.sleep(250); } catch(InterruptedException e) { Thread.currentThread().interrupt(); queue.markUnknown(task.id()); throw unknown(task.id()); }
        }
    }
    private static AgentOutcomeUnknownException unknown(String id) {
        return new AgentOutcomeUnknownException("에이전트 작업의 실행 결과를 확인 중입니다. 자동 재실행·보상은 중지했습니다. 작업 " + id);
    }
    static Path artifactPool() { return Path.of(System.getProperty("user.dir"),"data","agent-artifacts").toAbsolutePath().normalize(); }
}
