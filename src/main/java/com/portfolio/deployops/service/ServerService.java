package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerStatus;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.repository.ManualServerExecutionRepository;
import com.portfolio.deployops.repository.ServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ServerService {

    private final ServerRepository serverRepository;
    private final RemoteCommandLogRepository remoteCommandLogRepository;
    private final ManualServerExecutionRepository manualServerExecutionRepository;

    private AgentDispatchService agentDispatchService;
    private AgentQueueService agentQueueService;

    @org.springframework.beans.factory.annotation.Autowired
    void configureAgentDispatch(AgentDispatchService service) { this.agentDispatchService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureAgentQueue(AgentQueueService service) { this.agentQueueService = service; }

    @Transactional(readOnly = true)
    public List<Server> findAll() {
        return serverRepository.findAll();
    }

    @Transactional
    public Server create(com.portfolio.deployops.dto.ServerForm form,
                         com.portfolio.deployops.repository.ServerGroupRepository sgRepo) {
        serverRepository.lockGroupsForServerMutation();
        var group = validatedGroup(form, sgRepo);
        var mode = validatedControlMode(form, null);
        assertHostRegistrationAllowed(null, form.getIpAddress());
        Server s = Server.builder()
            .name(form.getName())
            .ipAddress(form.getIpAddress())
            .environment(form.getEnvironment())
            .serverType(form.getServerType())
            .controlMode(mode)
            .sshPort(form.getSshPort() > 0 ? form.getSshPort() : 22)
            .deployAccount(form.getDeployAccount())
            .authType(form.getAuthType() != null ? form.getAuthType()
                : com.portfolio.deployops.domain.AuthType.SSH_KEY)
            .deployPath(form.getDeployPath())
            .memo(form.getMemo())
            .orderIndex(form.getOrderIndex())
            .autoStart(form.isAutoStart())
            .startupService(normalizeStartupService(form.getStartupService()))
            .status(com.portfolio.deployops.domain.ServerStatus.UNKNOWN)
            .build();
        s.setServerGroup(group);
        return serverRepository.save(s);
    }

    @Transactional
    public Server update(Long id, com.portfolio.deployops.dto.ServerForm form,
                         com.portfolio.deployops.repository.ServerGroupRepository sgRepo) {
        serverRepository.lockGroupsForServerMutation();
        Server s = serverRepository.findByIdForMutation(id)
            .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다."));
        var group = validatedGroup(form, sgRepo);
        var mode = validatedControlMode(form, s);
        boolean hostChanged = !sameHost(s.getIpAddress(), form.getIpAddress());
        boolean endpointChanged = hostChanged
            || s.getControlMode() != mode
            || (form.getSshPort() > 0 && s.getSshPort() != form.getSshPort())
            || (form.getDeployAccount() != null && !Objects.equals(s.getDeployAccount(), form.getDeployAccount()))
            || (form.getAuthType() != null && s.getAuthType() != form.getAuthType());
        boolean boundaryChanged = !Objects.equals(s.getServerGroup() == null ? null : s.getServerGroup().getId(),
                group == null ? null : group.getId())
            || s.getEnvironment() != form.getEnvironment() || s.getServerType() != form.getServerType();
        if (s.isAgentControlled() && (hostChanged || s.getControlMode() != mode
                || s.getEnvironment() != form.getEnvironment() || s.getServerType() != form.getServerType())) {
            if (agentQueueService == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "에이전트 등록 상태를 확인할 수 없습니다.");
            Map<String,Object> agentState = agentQueueService.status(s.getId());
            Object registration = agentState.get("registrationState");
            if (agentState.get("busyTask") != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이전 에이전트 작업의 결과를 확인한 뒤 서버 접속 정보를 변경해 주세요. 등록 해제만으로 실행 중인 작업이 중지되지는 않습니다.");
            }
            if (!"NOT_REGISTERED".equals(registration) && !"REVOKED".equals(registration)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "에이전트 등록을 먼저 해제하고 실행 중인 작업을 확인한 뒤 서버 IP·유형·환경·제어 방식을 변경해 주세요. 변경 후에는 새 등록이 필요합니다.");
            }
        }
        if (protectedByApproval(s) && (endpointChanged || boundaryChanged)) {
            requireApprovalAdministrator();
        }
        if (endpointChanged || boundaryChanged) assertHostRegistrationAllowed(s.getId(), form.getIpAddress());
        s.setName(form.getName());
        s.setIpAddress(form.getIpAddress());
        s.setEnvironment(form.getEnvironment());
        s.setServerType(form.getServerType());
        s.setControlMode(mode);
        if (form.getSshPort() > 0) s.setSshPort(form.getSshPort());
        if (form.getDeployAccount() != null) s.setDeployAccount(form.getDeployAccount());
        if (form.getAuthType() != null) s.setAuthType(form.getAuthType());
        s.setDeployPath(form.getDeployPath());
        s.setMemo(form.getMemo());
        s.setOrderIndex(form.getOrderIndex());
        s.setAutoStart(form.isAutoStart());
        s.setStartupService(normalizeStartupService(form.getStartupService()));
        s.setServerGroup(group);
        return serverRepository.save(s);
    }

    private com.portfolio.deployops.domain.ServerGroup validatedGroup(com.portfolio.deployops.dto.ServerForm form,
            com.portfolio.deployops.repository.ServerGroupRepository groups) {
        if (form.getServerGroupId() == null) return null;
        if (form.getEnvironment() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "서버 환경을 선택해 주세요.");
        var group = groups.findByIdForUpdate(form.getServerGroupId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 서버 그룹을 찾을 수 없습니다."));
        if (group.getEnvironment() != form.getEnvironment()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "서버 환경(" + form.getEnvironment() + ")과 그룹 환경(" + group.getEnvironment() + ")이 다릅니다. 같은 환경의 그룹을 선택해 주세요.");
        }
        return group;
    }

    /** 에이전트 연결과 실행 준비 상태를 확인한다. */
    public Map<String, Object> checkServer(Long id) {
        Server s = serverRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다."));
        if (s.isAgentControlled()) {
            if (agentDispatchService == null) throw new RemoteExecutionException("에이전트 실행 서비스를 사용할 수 없습니다.");
            Map<String,Object> result = agentDispatchService.checkServer(s);
            s.setStatus(Boolean.TRUE.equals(result.get("ready")) ? ServerStatus.NORMAL : ServerStatus.ERROR);
            s.setLastCheckedAt(LocalDateTime.now());
            serverRepository.updateConnectivityStatus(s);
            return result;
        }
        s.setStatus(ServerStatus.UNKNOWN);
        s.setLastCheckedAt(LocalDateTime.now());
        serverRepository.updateConnectivityStatus(s);
        return Map.of("id", s.getId(), "name", s.getName(), "status", "UNKNOWN",
            "reachable", false, "ready", false, "message", "에이전트 전환이 필요합니다. 서버 설정을 저장하고 에이전트를 등록·승인해 주세요.");
    }

    public List<Map<String, Object>> checkAll() {
        return serverRepository.findAll().stream().map(s -> checkServer(s.getId())).toList();
    }

    private com.portfolio.deployops.domain.ServerControlMode validatedControlMode(
            com.portfolio.deployops.dto.ServerForm form, Server existing) {
        if (form.getControlMode() == com.portfolio.deployops.domain.ServerControlMode.SSH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "서버 제어는 에이전트만 지원합니다. 에이전트 방식으로 등록해 주세요.");
        return com.portfolio.deployops.domain.ServerControlMode.AGENT;
    }

    /**
     * 서버를 <b>물리 삭제</b>한다 — 참조가 남아 있으면 삭제하지 않고 이유를 돌려준다.
     *
     * <p>{@code remote_command_log.server_id} 가 이 서버를 가리키고 있으면
     * {@code fk_remote_command_log_server} 때문에 DELETE 자체가 실패한다. 그 실패를 그냥 두면
     * {@code DataIntegrityViolationException} 이 전역 핸들러까지 올라가 <b>500 + log.error</b> 가 된다.
     * 전사 유일 배포 경로에서 "500 발생률"은 장애 신호인데, 운영자가 삭제 버튼을 잘못 누른 것까지
     * 거기 섞이면 그 신호가 죽는다(2026-07-28 에 같은 이유로 405/400/415 를 걷어냈다).
     *
     * <p>그래서 먼저 세어 보고 409 로 안내한다. 배포 이력이 있는 서버를 지우지 못하는 것은
     * 제약이 아니라 의도다 — 그 이력이 어느 장비에서 일어났는지가 감사 자료다.
     */
    @Transactional
    public void delete(Long id) {
        serverRepository.lockGroupsForServerMutation();
        Server s = serverRepository.findByIdForMutation(id)
            .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다."));
        if (protectedByApproval(s)) requireApprovalAdministrator();

        if (agentQueueService != null && !"NOT_REGISTERED".equals(agentQueueService.status(id).get("registrationState"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "에이전트 등록 이력이 있는 서버는 삭제할 수 없습니다. 등록을 해제해 새 작업을 중단하고 감사 이력을 보존하세요.");
        }

        long logs = remoteCommandLogRepository.countByServerId(id);
        long manualExecutions = manualServerExecutionRepository.countByServerId(id);
        if (logs > 0 || manualExecutions > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "'" + s.getName() + "' 서버에는 배포 실행 기록 " + (logs + manualExecutions) + "건이 남아 있어 삭제할 수 없습니다. "
                + "이력이 어느 장비에서 일어났는지가 감사 자료라 함께 지우지 않습니다.");
        }
        serverRepository.delete(s);
    }

    private boolean protectedByApproval(Server server) {
        return server.getServerGroup() != null && server.getServerGroup().getApprovalPolicyId() != null;
    }

    private boolean isApprovalAdministrator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
            && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private void requireApprovalAdministrator() {
        if (!isApprovalAdministrator()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "추가 승인 그룹 서버의 이동·접속 정보 변경·삭제와 같은 호스트의 별도 등록은 최고 관리자만 할 수 있습니다.");
    }

    private void assertHostRegistrationAllowed(Long existingId, String address) {
        if (isApprovalAdministrator()) return;
        String key = ServerHostIdentity.canonical(address);
        if (key == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "서버 IP는 IPv4·IPv6 주소 또는 localhost로 입력해 주세요. DNS 이름·축약 IPv4·선행 0·IPv6 영역 ID는 최고 관리자 확인이 필요합니다.");
        for (Server other : serverRepository.findAllForMutation()) {
            if (Objects.equals(existingId, other.getId()) || !protectedByApproval(other)) continue;
            String protectedKey = ServerHostIdentity.canonical(other.getIpAddress());
            if (protectedKey == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "추가 승인 그룹에 IP 동일성을 확인할 수 없는 기존 호스트가 있습니다. 최고 관리자가 접속 정보를 확인한 뒤 서버를 등록·변경해 주세요.");
            if (key.equals(protectedKey)) requireApprovalAdministrator();
        }
    }

    private boolean sameHost(String previous, String requested) {
        if (Objects.equals(previous, requested)) return true;
        String key = ServerHostIdentity.canonical(previous);
        return key != null && key.equals(ServerHostIdentity.canonical(requested));
    }


    /**
     * 부팅 복구가 켜면 이 값이 그대로 대상 서버의 root NOPASSWD 스크립트로 넘어간다
     * ({@code deploy-service-control.sh start &lt;unit&gt;}).
     *
     * <p>에이전트도 같은 정규식과 접두 allowlist 로 한 번 더 막지만, 여기서도 같이 막는다.
     * 앱이 형식만 보고 통과시키면 화면에서 {@code sshd.service} 같은 값을 <b>저장할 수 있고</b>,
     * 정작 부팅 때 에이전트가 exit 3 으로 거절해 로그에만 남는다. 사용자는 자기가 설정한
     * 서비스가 왜 안 올라오는지 알 수 없다 — 막을 거면 저장 시점에 막아야 한다.
     */
    private String normalizeStartupService(String value) {
        if (value == null || value.isBlank()) return null;
        String unit = value.trim();
        if (!unit.matches("[A-Za-z0-9][A-Za-z0-9_.@:-]{0,127}\\.service")) {
            throw new IllegalArgumentException("startupService must be a valid systemd .service unit");
        }
        // deploy-service-control.sh 의 case 문과 같은 목록이다. 한쪽만 넓히면 어긋난다.
        if (!unit.startsWith("deploy-") && !unit.startsWith("worksystem-") && !unit.startsWith("office-")) {
            throw new IllegalArgumentException(
                "startupService must be one of deploy-*/worksystem-*/office-*.service");
        }
        return unit;
    }
}
