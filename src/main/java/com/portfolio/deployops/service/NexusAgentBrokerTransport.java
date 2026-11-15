package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.repository.ServerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Fixed Nexus broker file protocol transported through the registered host agent. */
@Service
@RequiredArgsConstructor
public class NexusAgentBrokerTransport {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ServerRepository servers;
    private final AgentDispatchService dispatch;
    private final AgentQueueService queue;
    private final com.portfolio.deployops.repository.ExecutionGuardRepository guard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object exchangeLock = new Object();
    private ReadinessSnapshot readinessSnapshot;
    private record ReadinessSnapshot(long serverId, String bindingId, long completedAt, byte[] bytes) { }

    public boolean configured() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM nexus_agent_broker_binding WHERE id=1 AND server_id IS NOT NULL", Integer.class)>0;
    }

    public Map<String,Object> binding() {
        return jdbc.queryForMap("SELECT server_id,agent_binding_id FROM nexus_agent_broker_binding WHERE id=1");
    }

    @org.springframework.transaction.annotation.Transactional
    public void bind(long serverId) {
        guard.lockSingleton();
        guard.assertNoNexusMaintenance();
        var existing=jdbc.queryForMap("SELECT server_id,agent_binding_id FROM nexus_agent_broker_binding WHERE id=1 FOR UPDATE");
        Server server=servers.findById(serverId).orElseThrow();
        if(!server.isAgentControlled()||server.getAgentBindingId()==null||
                (server.getServerType()!=ServerType.NEXUS&&server.getServerType()!=ServerType.DEPLOY))
            throw new IllegalArgumentException("Nexus가 설치된 서버의 에이전트를 먼저 등록해 주세요.");
        boolean same=existing.get("server_id") instanceof Number n && n.longValue()==serverId
            && java.util.Objects.equals(existing.get("agent_binding_id"),server.getAgentBindingId());
        if(same)return;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM nexus_backup_request",Long.class)>0 && !sameLocalBroker(server, existing))
            throw new IllegalArgumentException("기존 Nexus 백업 이력이 있어 실행 호스트를 변경할 수 없습니다. 백업 데이터 이관과 연결 검증이 필요합니다.");
        jdbc.update("UPDATE nexus_agent_broker_binding SET server_id=?,agent_binding_id=? WHERE id=1",serverId,server.getAgentBindingId());
    }

    private boolean sameLocalBroker(Server server, Map<String,Object> existing) {
        // Convert the existing co-located broker only after host identity and the
        // exact protected readiness file match. A remote host migration is not inferred.
        if(existing.get("server_id")!=null && ((Number)existing.get("server_id")).longValue()!=server.getId())return false;
        try {
            var registration=queue.status(server.getId());
            String localMachine=java.nio.file.Files.readString(java.nio.file.Path.of("/etc/machine-id")).trim();
            if(!"ACTIVE".equals(registration.get("registrationState"))||!localMachine.equals(registration.get("machineId")))return false;
            var ready=java.nio.file.Path.of("/var/lib/deployops/nexus-backup/results/ready.json");
            if(java.nio.file.Files.isSymbolicLink(ready)||java.nio.file.Files.size(ready)>8192
                ||!Integer.valueOf(0).equals(java.nio.file.Files.getAttribute(ready,"unix:uid",java.nio.file.LinkOption.NOFOLLOW_LINKS)))return false;
            return java.util.Arrays.equals(java.nio.file.Files.readAllBytes(ready),send(server,"read","results/ready.json",null));
        }catch(Exception unavailable){return false;}
    }

    byte[] exchange(String operation, String relativePath, Map<String,Object> payload) throws IOException {
        long requestedAt = System.nanoTime();
        synchronized (exchangeLock) {
        try {
            Map<String,Object> binding=binding();
            long id = ((Number)binding.get("server_id")).longValue();
            Server server = servers.findById(id).orElseThrow();
            if (!server.isAgentControlled() || !java.util.Objects.equals(binding.get("agent_binding_id"),server.getAgentBindingId())
                    || (server.getServerType()!=ServerType.NEXUS && server.getServerType()!=ServerType.DEPLOY))
                throw new IOException("NEXUS_AGENT_BINDING_REQUIRED");
            boolean readinessRead = "read".equals(operation) && "results/ready.json".equals(relativePath);
            // The host agent executes one job at a time. Parallel backup/restore/
            // promotion probes must not reject each other as an unavailable host.
            // Share only a read that completed after this caller started waiting;
            // a later request always performs a fresh read, including approvals.
            if (readinessRead && readinessSnapshot != null && readinessSnapshot.serverId() == id
                    && java.util.Objects.equals(readinessSnapshot.bindingId(), server.getAgentBindingId())
                    && readinessSnapshot.completedAt() - requestedAt >= 0)
                return readinessSnapshot.bytes().clone();
            readinessSnapshot = null;
            byte[] response = send(server,operation,relativePath,payload);
            if (readinessRead)
                readinessSnapshot = new ReadinessSnapshot(id, server.getAgentBindingId(), System.nanoTime(), response.clone());
            return response;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("NEXUS_AGENT_BROKER_UNAVAILABLE", e); }
        }
    }

    private byte[] send(Server server,String operation,String relativePath,Map<String,Object> payload)throws IOException {
        synchronized (exchangeLock) {
        try {
            var request = new java.util.LinkedHashMap<String,Object>();
            request.put("operation", operation);
            request.put("path", relativePath);
            if (payload != null) request.put("payload", payload);
            var result = dispatch.execute(server, List.of("/usr/local/deploy/bin/deploy-infrastructure.sh", "nexus-broker"),
                mapper.writeValueAsBytes(request), false, 30);
            if (result.exitCode()!=0) throw new IOException("NEXUS_AGENT_BROKER_FAILED");
            var response = mapper.readTree(result.stdout());
            if (!response.path("ok").asBoolean(false)) throw new IOException("NEXUS_AGENT_BROKER_INVALID");
            return Base64.getDecoder().decode(response.path("dataBase64").asText(""));
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("NEXUS_AGENT_BROKER_UNAVAILABLE", e); }
        }
    }
}
