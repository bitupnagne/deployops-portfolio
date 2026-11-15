package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.repository.ServerRepository;
import com.portfolio.deployops.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/infrastructure")
@PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
@RequiredArgsConstructor
public class InfrastructureApiController {
    private final ServerRepository servers;
    private final AgentQueueService queue;
    private final ObjectMapper mapper;
    private final SecretMaskingService masking;
    private final AuditLogService audit;
    private final SecretCryptoService crypto;
    private final NexusAgentBrokerTransport nexus;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final Set<String> ROLES = Set.of("DEPLOY","NEXUS","GITLAB","RUNNER");

    @GetMapping("/servers")
    public ApiResponse<?> servers() {
        return ApiResponse.ok(servers.findAll().stream().filter(s -> ROLES.contains(s.getServerType().name()))
            .map(s -> Map.of("id",s.getId(),"name",s.getName(),"ip",s.getIpAddress(),"role",s.getServerType().name(),
                "agent",s.isAgentControlled(),"registration",queue.status(s.getId()))).toList());
    }

    public record JobInput(String action, String service, String backupId, Integer lines, boolean confirmed) { }

    @GetMapping("/nexus-binding")
    public ApiResponse<?> nexusBinding() { return ApiResponse.ok(nexus.binding()); }

    @PostMapping("/nexus-binding")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> bindNexus(@RequestBody Map<String,Long> input) {
        Long serverId=input.get("serverId");
        if(serverId==null)throw bad();
        nexus.bind(serverId);
        audit.recordCurrentRequired("NEXUS_AGENT_BINDING","Server",serverId.toString(),"Nexus 실행 호스트 연결",AuditResult.SUCCESS);
        return ApiResponse.ok(nexus.binding());
    }

    @PostMapping("/{serverId}/jobs")
    public ApiResponse<?> submit(@PathVariable long serverId, @RequestBody JobInput input) {
        Server server = requireServer(serverId);
        if (!Set.of("status","logs","start","stop","restart","backup","restore").contains(input.action())) throw bad();
        boolean read = Set.of("status","logs").contains(input.action());
        if (!read && !input.confirmed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"작업 대상과 영향 확인이 필요합니다.");
        Map<String,Object> request = new LinkedHashMap<>();
        request.put("role",server.getServerType().name());
        if (!input.action().equals("status")) {
            Set<String> services = switch(server.getServerType().name()) {
                case "GITLAB" -> Set.of("gitlab","runner");
                case "RUNNER" -> Set.of("runner");
                default -> Set.of("nexus");
            };
            if (input.service()==null || !services.contains(input.service())) throw bad();
            request.put("service",input.service());
        }
        if (input.action().equals("logs")) {
            int lines=input.lines()==null?200:input.lines();
            if(lines<1||lines>1000)throw bad();
            request.put("lines",lines);
        }
        if (Set.of("backup","restore").contains(input.action())) {
            if (!"gitlab".equals(input.service())) throw bad();
            String backupId = input.action().equals("backup") ? UUID.randomUUID().toString().replace("-","") : input.backupId();
            if(backupId==null||!backupId.matches("[0-9a-f]{32}"))throw bad();
            request.put("backupId",backupId);
            if(input.action().equals("restore"))request.put("restoreId",UUID.randomUUID().toString().replace("-",""));
        }
        if ("nexus".equals(input.service()) && !read)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nexus 변경은 승인된 백업·복구 화면에서 진행해 주세요.");
        byte[] body=mapper.writeValueAsBytes(request);
        Map<String,Object> payload=Map.of("argv",List.of("/usr/local/deploy/bin/deploy-infrastructure.sh",input.action()),
            "stdinBase64",Base64.getEncoder().encodeToString(body));
        audit.recordCurrentRequired("INFRASTRUCTURE_JOB_REQUEST","Server",Long.toString(serverId),"action="+input.action()+",service="+input.service(),AuditResult.SUCCESS);
        var task=queue.enqueue(server,"EXEC",payload,false,Set.of("backup","restore").contains(input.action())?7200:240,null,null,null);
        return ApiResponse.ok(Map.of("taskId",task.id(),"state",task.state(),"request",request));
    }

    @GetMapping("/jobs/{taskId}")
    public ApiResponse<?> result(@PathVariable String taskId) {
        var task=queue.task(taskId,false);
        if(task==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        requireServer(task.serverId());
        // Only infrastructure helper tasks may be read through this endpoint.
        var payload=mapper.readTree(crypto.decryptIfEncrypted(task.payload()));
        if(!"/usr/local/deploy/bin/deploy-infrastructure.sh".equals(payload.path("argv").path(0).asText()))throw bad();
        return ApiResponse.ok(Map.of("taskId",task.id(),"state",task.state(),"stdout",masking.mask(task.stdout()==null?"":task.stdout()),
            "exitCode",task.exitCode()==null?-1:task.exitCode()));
    }

    @GetMapping("/{serverId}/jobs")
    public ApiResponse<?> history(@PathVariable long serverId) {
        requireServer(serverId);
        List<Map<String,Object>> result=new ArrayList<>();
        for(String id:jdbc.queryForList("SELECT id FROM server_agent_task WHERE server_id=? ORDER BY created_at DESC LIMIT 100",String.class,serverId)) {
            var task=queue.task(id,false);
            var payload=mapper.readTree(crypto.decryptIfEncrypted(task.payload()));
            if(!"/usr/local/deploy/bin/deploy-infrastructure.sh".equals(payload.path("argv").path(0).asText())
                ||"nexus-broker".equals(payload.path("argv").path(1).asText()))continue;
            result.add(Map.of("taskId",task.id(),"state",task.state(),"action",payload.path("argv").path(1).asText(),"createdAt",task.createdAt().toString()));
        }
        return ApiResponse.ok(result);
    }

    private Server requireServer(long id) {
        Server server=servers.findById(id).orElseThrow(InfrastructureApiController::bad);
        if(!server.isAgentControlled()||!ROLES.contains(server.getServerType().name()))throw bad();
        return server;
    }
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"등록된 인프라 서버와 작업 내용을 확인해 주세요.");}
}
