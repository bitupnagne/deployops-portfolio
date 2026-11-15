package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RolloutTrafficService {
    private static final String AGENT = "/usr/local/deploy/bin/deploy-nginx-policy.sh";
    private final ProjectRolloutRouteRepository routes;
    private final ProjectRepository projects;
    private final ServerRepository servers;
    private final NginxChangeRequestRepository policies;
    private final SshCommandService ssh;
    private final DeployComponentRepository components;

    public List<ProjectRolloutRouteRepository.Binding> bindings(Long projectId, DeployEnvironment environment) {
        return routes.find(projectId, environment);
    }

    public record Choice(Long webServerId, String webServerName, String policyName, Long nativeRequestId,
                         String configSha256, List<RolloutTrafficRoute.Endpoint> upstreams) { }

    /** Public metadata only. A policy owned by another project is not exposed as a selectable choice. */
    public List<Choice> choices(Long projectId, DeployEnvironment environment) {
        ServerEnvironment expected = environment == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
        List<Choice> result = new ArrayList<>();
        for (var group : projects.findServerGroupsByProjectId(projectId)) {
            if (group.getEnvironment() != expected) continue;
            for (var web : servers.findByServerGroupId(group.getId())) {
                if (web.getServerType() != ServerType.WEB || web.getEnvironment() != expected) continue;
                for (String name : policies.findCompletedPolicyNames(web.getId())) {
                    Long owner = routes.owner(environment,web.getId(),name);
                    if (owner != null && !owner.equals(projectId)) continue;
                    Long latest = policies.findLatestCompletedId(web.getId(),name);
                    if (latest == null) continue;
                    var policy = policies.findById(latest).orElse(null);
                    if (policy == null || policy.isRestoreAbsent() || policy.getNativeRequestId() == null
                        || policy.getAppliedConfigHash() == null || !policy.getAppliedConfigHash().matches("[0-9a-f]{64}")) continue;
                    List<RolloutTrafficRoute.Endpoint> upstreams = policy.getEffectiveUpstreams().stream()
                        .map(row -> new RolloutTrafficRoute.Endpoint(row.getHost(),row.getPort())).toList();
                    if (upstreams.isEmpty()) continue;
                    result.add(new Choice(web.getId(),web.getName(),name,policy.getNativeRequestId(),policy.getAppliedConfigHash(),upstreams));
                }
            }
        }
        return List.copyOf(result);
    }

    @Transactional
    public void save(Long projectId, DeployEnvironment environment, List<ProjectRolloutRouteRepository.Binding> selected) {
        if (environment == null || projects.lockById(projectId) == null) throw conflict("프로젝트와 환경을 확인해 주세요.");
        if (selected == null || selected.size() > 100) throw conflict("WEB 정책 연결은 0~100개로 지정하세요.");
        Set<String> seen = new HashSet<>();
        for (var binding : selected) {
            if (binding == null || binding.webServerId() == null || binding.policyName() == null
                || !binding.policyName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                || !seen.add(binding.webServerId() + ":" + binding.policyName())) throw conflict("WEB 정책 연결 값이 올바르지 않거나 중복됩니다.");
            var web = requireWeb(projectId, environment, binding.webServerId());
            Long owner = routes.owner(environment, web.getId(), binding.policyName());
            if (owner != null && !owner.equals(projectId)) throw conflict("다른 프로젝트가 사용하는 nginx 정책입니다. 프로젝트별 정책을 분리하세요.");
            var policy = currentPolicy(web, binding.policyName());
            if (policy.getNativeRequestId() == null || policy.getAppliedConfigHash() == null
                || !policy.getAppliedConfigHash().matches("[0-9a-f]{64}") || policy.getEffectiveUpstreams().isEmpty())
                throw conflict("최신 에이전트로 적용 완료된 프록시 정책만 연결할 수 있습니다.");
        }
        routes.delete(projectId, environment);
        try {
            for (var binding : selected) routes.insert(new ProjectRolloutRouteRepository.Binding(projectId, environment, binding.webServerId(), binding.policyName()));
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            throw conflict("다른 변경이 먼저 저장되었습니다. 정책 연결을 다시 조회한 후 저장하세요.");
        }
    }

    public List<RolloutTrafficRoute> describeRoutes(Long projectId, DeployEnvironment environment, List<ServerGroup> selectedGroups) {
        return describeRoutes(projectId, environment, selectedGroups, DeployScope.ALL);
    }

    public List<RolloutTrafficRoute> describeRoutes(Long projectId, DeployEnvironment environment, List<ServerGroup> selectedGroups, DeployScope scope) {
        ServerEnvironment expected = environment == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
        Set<String> targetHosts = new HashSet<>();
        selectedGroups.stream().filter(group -> group.getEnvironment() == expected).forEach(group ->
            group.getServers().stream().filter(server -> server.getServerType() == ServerType.WAS && server.getEnvironment() == expected)
                .forEach(server -> targetHosts.add(server.getIpAddress())));
        if (targetHosts.isEmpty()) return null;
        Project project = projects.findById(projectId).orElseThrow(() -> conflict("프로젝트를 찾을 수 없습니다."));
        List<DeployComponent> activeComponents = components.findActiveByProjectId(projectId);
        // CONTAINER_RUNNING ignores legacy HTTP health-port values and publishes no
        // port unless containerPorts is configured. Such a worker has no ingress to drain.
        if (WasVipPolicy.portlessWorker(project, activeComponents)) return null;
        List<DeployComponent> wasComponents = activeComponents.stream()
            .filter(DeployComponent::isActive).filter(component -> component.getRole() == ServerType.WAS).toList();
        Set<Integer> servicePorts = new HashSet<>();
        if (wasComponents.isEmpty()) {
            if (project.getHealthCheckPort() != null) servicePorts.add(project.getHealthCheckPort());
        } else {
            wasComponents.forEach(component -> {
                Integer port = component.getHealthCheckPort() != null ? component.getHealthCheckPort() : project.getHealthCheckPort();
                if (port != null) servicePorts.add(port);
            });
        }
        var vipGroups = selectedGroups.stream().filter(g -> scope != DeployScope.WEB_ONLY).filter(g -> g.getEnvironment() == expected)
            .filter(WasVipPolicy::enabled).filter(g -> g.getServers().stream().anyMatch(w -> w.getServerType() == ServerType.WAS && w.getEnvironment() == expected)).toList();
        if (!vipGroups.isEmpty()) {
            if (project.getWasVipPort() == null || project.getWasVipPort() < 1 || project.getWasVipPort() > 65535)
                throw conflict("VIP 그룹을 배포하려면 프로젝트의 WAS VIP 서비스 포트를 먼저 설정하세요.");
            if (wasComponents.isEmpty()) {
                if (project.getHealthCheckMode() == null || project.getHealthCheckMode() == HealthCheckMode.NONE)
                    throw conflict("VIP 모드는 개별 WAS의 HTTP 또는 컨테이너 헬스체크가 필요합니다. VIP 응답만으로 준비 상태를 대신할 수 없습니다.");
            } else if (wasComponents.stream().anyMatch(c -> (c.getHealthCheckMode() == null ? project.getHealthCheckMode() : c.getHealthCheckMode()) == null
                    || (c.getHealthCheckMode() == null ? project.getHealthCheckMode() : c.getHealthCheckMode()) == HealthCheckMode.NONE))
                throw conflict("VIP 모드의 활성 WAS 컴포넌트에 개별 HTTP 또는 컨테이너 헬스체크를 설정하세요.");
            vipGroups.forEach(g -> WasVipPolicy.normalizeAddress(g.getWasVipAddress()));
            // A shared VIP cannot prove which selected group receives requests: require a dedicated endpoint per group.
            for (var selected : vipGroups) for (var mapped : projects.findServerGroupsByProjectId(projectId))
                if (mapped.getEnvironment() == expected && WasVipPolicy.enabled(mapped) && !Objects.equals(mapped.getId(),selected.getId())
                    && WasVipPolicy.sameHost(mapped.getWasVipAddress(), selected.getWasVipAddress()))
                    throw conflict("같은 VIP를 여러 서버그룹이 공유합니다. 그룹별 전용 VIP로 분리한 뒤 요청하세요. L4의 실제 멤버 구성은 별도 확인이 필요합니다.");
        }
        if (servicePorts.isEmpty() && vipGroups.isEmpty()) return null;
        Set<Long> coveredVipGroups = new HashSet<>();
        Set<String> connected = new HashSet<>();
        routes.find(projectId, environment).forEach(binding -> connected.add(binding.webServerId()+":"+binding.policyName()));
        List<RolloutTrafficRoute> result = new ArrayList<>();
        // Traffic ingress is broader than the selected deployment slice. A WAS_ONLY request
        // must drain a referencing WEB even when that WEB's group is not being deployed.
        for (ServerGroup group : projects.findServerGroupsByProjectId(projectId)) {
            if (group.getEnvironment() != expected) continue;
            for (Server web : servers.findByServerGroupId(group.getId())) {
                if (web.getServerType() != ServerType.WEB || web.getEnvironment() != expected) continue;
                for (String name : policies.findCompletedPolicyNames(web.getId())) {
                    Long owner = routes.owner(environment,web.getId(),name);
                    Long latest = policies.findLatestCompletedId(web.getId(),name);
                    if (latest == null) continue;
                    NginxChangeRequest policy = policies.findById(latest)
                        .orElseThrow(() -> conflict("nginx 적용 정책 이력을 확인할 수 없습니다."));
                    if (policy.isRestoreAbsent()) continue;
                    var endpoints = policy.getEffectiveUpstreams().stream()
                        .map(row -> new RolloutTrafficRoute.Endpoint(row.getHost(),row.getPort())).toList();
                    boolean directMatch = endpoints.stream().anyMatch(row -> targetHosts.stream().anyMatch(host -> WasVipPolicy.sameHost(host,row.host())) && servicePorts.contains(row.port()));
                    var matchedVipGroups = vipGroups.stream().filter(g -> endpoints.stream().anyMatch(row -> WasVipPolicy.sameHost(g.getWasVipAddress(),row.host()) && row.port() == project.getWasVipPort())).toList();
                    if (!directMatch && matchedVipGroups.isEmpty()) continue;
                    boolean vipWasDirect = endpoints.stream().anyMatch(row -> servicePorts.contains(row.port()) && vipGroups.stream().anyMatch(g ->
                        g.getServers().stream().anyMatch(w -> w.getServerType() == ServerType.WAS && WasVipPolicy.sameHost(w.getIpAddress(),row.host()))));
                    if (vipWasDirect || (!matchedVipGroups.isEmpty() && (directMatch || matchedVipGroups.size() != 1
                            || endpoints.stream().anyMatch(row -> !WasVipPolicy.sameHost(row.host(),matchedVipGroups.getFirst().getWasVipAddress()) || row.port() != project.getWasVipPort()))))
                        throw conflict("WEB " + web.getName() + " / " + name + "에 VIP와 개별 WAS 또는 다른 endpoint가 혼재합니다. 프로젝트·VIP 그룹별 전용 nginx 정책으로 분리하고 승인 적용하세요. VIP 전체를 트래픽 제외하지 않습니다.");
                    matchedVipGroups.forEach(g -> coveredVipGroups.add(g.getId()));
                    if (owner != null && !owner.equals(projectId))
                        throw conflict("WEB " + web.getName() + "의 nginx 정책 '" + name
                            + "'은 다른 프로젝트(ID " + owner + ") 소유이지만 선택 WAS의 동일 서비스 포트로 요청을 보냅니다. "
                            + "다른 프로젝트 정책은 변경하지 않았습니다. 공유 게이트웨이 정책을 프로젝트별 전용 정책으로 분리한 뒤 요청하세요.");
                    if (!connected.contains(web.getId()+":"+name))
                        throw conflict("WEB " + web.getName() + "의 nginx 정책 '" + name
                            + "'이 선택 WAS의 서비스 포트로 요청을 보내지만 프로젝트 롤링 연결이 없습니다. 해당 WEB 정책을 연결하세요. "
                            + "이 검사는 배포시스템이 관리하는 nginx 정책에 한정되며 외부 L4·수동 nginx 설정은 별도 확인해야 합니다.");
                    if (policy.getAppliedConfigHash() == null || !policy.getAppliedConfigHash().matches("[0-9a-f]{64}") || policy.getNativeRequestId() == null)
                        throw conflict("nginx 정책의 실제 적용 세대를 확인할 수 없습니다. 최신 에이전트로 정책을 적용하세요.");
                    var snapshot = new DeploymentPlanSnapshot.ServerSettings(web.getId(), web.getName(), web.getIpAddress(),
                        web.getEnvironment(), web.getServerType(), web.getSshPort(), web.getDeployAccount(), web.getAuthType(),
                        web.getDeployPath(), web.getOrderIndex(), web.isAutoStart(), web.getStartupService(),
                        web.isAgentControlled() ? web.getControlMode() : null,
                        web.isAgentControlled() ? web.getAgentBindingId() : null);
                    result.add(new RolloutTrafficRoute(snapshot,name,policy.getNativeRequestId(),policy.getAppliedConfigHash(),endpoints));
                }
            }
        }
        if (vipGroups.stream().anyMatch(g -> !coveredVipGroups.contains(g.getId())))
            throw conflict("선택 VIP 그룹의 주소와 프로젝트 서비스 포트에 연결된 적용 완료 nginx 정책이 없습니다. WEB에서 VIP 설정을 승인 적용한 뒤 프로젝트 정책 연결을 저장하세요. 외부 L4 멤버와 수동 nginx는 자동 검증 범위가 아닙니다.");
        return result.isEmpty() ? null : List.copyOf(result);
    }

    public List<RolloutTrafficRoute> matching(Project project, Server was, Integer port) {
        if (WasVipPolicy.enabled(was.getServerGroup())) return List.of(); // Never drain the VIP or infer an individual L4 member.
        return project.getRolloutTrafficRoutes() == null || port == null ? List.of() : project.getRolloutTrafficRoutes().stream()
            .filter(route -> route.upstreams().stream().anyMatch(endpoint -> WasVipPolicy.sameHost(endpoint.host(), was.getIpAddress()) && endpoint.port() == port)).toList();
    }

    public void change(RolloutTrafficRoute route, String generation, Server was, int port, boolean drain, Consumer<String> log) {
        if (WasVipPolicy.enabled(was.getServerGroup())) throw conflict("외부 VIP 모드에서는 nginx 개별 WAS 트래픽 제어를 실행할 수 없습니다.");
        var settings = route.web();
        Server web = Server.builder().id(settings.id()).name(settings.name()).ipAddress(settings.ipAddress())
            .environment(settings.environment()).serverType(ServerType.WEB).sshPort(settings.sshPort())
            .deployAccount(settings.deployAccount()).authType(settings.authType())
            .controlMode(settings.effectiveControlMode()).agentBindingId(settings.agentBindingId()).build();
        List<String> args = new ArrayList<>();
        if (!"root".equals(web.getDeployAccount())) args.addAll(List.of("sudo", "-n"));
        args.addAll(List.of(AGENT, drain ? "traffic-drain" : "traffic-restore", "--policy-name", route.policyName(),
            "--rollout-generation", generation, "--rollout-config-sha", route.configSha256()));
        if (drain) {
            args.addAll(List.of("--rollout-host", was.getIpAddress(), "--rollout-port", Integer.toString(port)));
            if (was.getServerGroup() != null && was.getServerGroup().getMinAvailable() == 0) {
                args.addAll(List.of("--rollout-allow-empty", "true"));
            }
        }
        RemoteCommandResult response = ssh.execute(web, args);
        if (response.exitCode() != 0) throw new RemoteExecutionException("WEB " + web.getName() + "의 프로젝트 트래픽 " + (drain ? "제외" : "복귀") + " 실패: " + response.output());
        log.accept("[트래픽] " + web.getName() + " / " + route.policyName() + " / " + (drain ? "제외" : "복귀") + " 완료");
    }

    private Server requireWeb(Long projectId, DeployEnvironment environment, Long id) {
        Server web = servers.findById(id).orElseThrow(() -> conflict("등록된 WEB 서버가 없습니다."));
        ServerEnvironment expected = environment == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
        if (web.getServerType() != ServerType.WEB || web.getEnvironment() != expected || web.getServerGroup() == null
            || projects.findServerGroupsByProjectId(projectId).stream().noneMatch(group ->
                group.getEnvironment() == expected && group.getId().equals(web.getServerGroup().getId())))
            throw conflict("프로젝트에 연결된 같은 환경의 WEB만 지정할 수 있습니다.");
        return web;
    }

    private NginxChangeRequest currentPolicy(Server web, String name) {
        Long id = policies.findLatestCompletedId(web.getId(), name);
        if (id == null) throw conflict("WEB에 적용 완료된 nginx 정책이 없습니다: " + name);
        NginxChangeRequest policy = policies.findById(id).orElseThrow(() -> conflict("nginx 정책 이력이 없습니다."));
        if (policy.isRestoreAbsent()) throw conflict("삭제된 nginx 정책에는 롤링을 연결할 수 없습니다.");
        return policy;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
