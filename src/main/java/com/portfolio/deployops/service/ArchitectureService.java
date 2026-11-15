package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 배포 파이프라인 전체 구조도 — 소스(GitLab) → 산출물(Nexus) → 배포시스템 → 대상 서버.
 *
 * <p>이 화면이 답해야 할 질문은 하나다: <b>지금 어디가 끊겨 있나.</b>
 * 그래서 구조와 연결 상태를 분리해서 낸다 —
 * 구조는 DB·설정만 읽어 즉시 나오고, 연결 상태는 실제로 두드려 봐야 알 수 있어 느리다.
 * 한 번에 묶으면 끊긴 구간이 있을 때 <b>타임아웃 때문에 화면이 가장 늦게 뜬다</b>.
 * 정작 급할 때 제일 안 보이는 셈이라 두 단계로 나눈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectureService {

    /** 연결 확인 타임아웃. 끊긴 곳을 빨리 "끊겼다"고 말하는 것이 오래 기다리는 것보다 낫다. */
    private static final int PROBE_TIMEOUT_MS = 2500;

    /** "가끔 오류" 를 판정할 때 돌아볼 최근 실행 횟수. */
    private static final int RECENT_WINDOW = 20;

    private final SystemSettingService settingService;
    private final ProjectService projectService;
    private final DeployComponentRepository deployComponentRepository;
    private final RemoteCommandLogRepository remoteCommandLogRepository;
    private final NexusMetadataService nexusMetadataService;
    private final AgentQueueService agentQueueService;

    /* ─────────────────────────────────────────
       ① 구조 — DB·설정만. 즉시 응답한다.
    ───────────────────────────────────────── */
    public Map<String, Object> structure(Long projectId, SecurityUserDetails currentUser) {
        Project project = projectService.findByIdWithDetails(projectId, currentUser);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getId());
        out.put("projectName", project.getName());
        // 기술 스택 — 무엇으로 빌드해 무엇으로 나가는지. 화면이 칩으로 보여 준다.
        out.put("buildTool", project.getBuildTool() != null ? project.getBuildTool().name() : null);
        out.put("pipelineType", project.getPipelineType() != null ? project.getPipelineType().name() : null);
        // 정렬 기준 — 가장 먼저 등록된 서버(등록일, 없으면 id).
        out.put("firstServer", firstServer(project));
        out.put("build", buildZone(project));
        out.put("environments", environmentZones(project));
        out.put("components", components(project));
        out.put("collisions", detectCollisions(project));
        return out;
    }

    /**
     * <b>같은 컨테이너를 가리키는 서버들</b>을 찾는다.
     *
     * <p>컨테이너 이름은 {@code RemoteDeployService#resolveContainerName} 이 <b>프로젝트 이름만으로</b>
     * 만든다 — 환경도 서버도 반영하지 않는다. 그래서 서로 다른 환경의 서버 두 대가
     * <b>같은 호스트·같은 계정</b>을 가리키면, 그 둘은 물리적으로 완전히 같은 컨테이너다.
     *
     * <p>결과는 정지/기동만의 문제가 아니다 — <b>TEST 배포가 PROD 를 덮어쓴다.</b>
     * 화면에는 두 대로 보이는데 실제로는 한 대라, 눌러 보기 전에는 알 수 없다.
     *
     * <p>운영 3망 구성에서는 환경마다 호스트가 달라 생기지 않는다. 개발 편의로 한 대에 몰아넣은
     * 구성에서만 나타나는데, 바로 그런 곳에서 사고가 난다.
     */
    private List<Map<String, Object>> detectCollisions(Project project) {
        // key = 호스트:포트:계정 — 이 셋이 같으면 같은 기계의 같은 도커다.
        Map<String, List<Map<String, String>>> byTarget = new LinkedHashMap<>();

        for (ServerGroup group : project.getServerGroups() == null ? List.<ServerGroup>of() : project.getServerGroups()) {
            String env = group.getEnvironment() != null ? group.getEnvironment().name() : "UNKNOWN";
            for (Server s : group.getServers() == null ? List.<Server>of() : group.getServers()) {
                String key = s.getIpAddress() + ":" + s.getSshPort() + ":" + s.getDeployAccount();
                byTarget.computeIfAbsent(key, k -> new ArrayList<>()).add(Map.of(
                    "serverId", String.valueOf(s.getId()),
                    "serverName", s.getName() == null ? "" : s.getName(),
                    "environment", env,
                    "groupName", group.getName() == null ? "" : group.getName()
                ));
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        byTarget.forEach((key, members) -> {
            if (members.size() < 2) {
                return;
            }
            // 환경이 갈리는 충돌이 가장 위험하다(운영/테스트가 한 몸).
            boolean crossEnv = members.stream().map(m -> m.get("environment")).distinct().count() > 1;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("target", key);
            c.put("crossEnvironment", crossEnv);
            c.put("members", members);
            c.put("serverIds", members.stream().map(m -> Long.valueOf(m.get("serverId"))).toList());
            out.add(c);
        });
        return out;
    }

    /**
     * 이 프로젝트에서 <b>가장 먼저 등록된 서버</b> — "전체" 목록의 정렬 기준.
     *
     * <p>등록일({@code created_at})이 있으면 그걸 쓰고, 없으면 id 로 대신한다.
     * 그 컬럼은 2026-07-31 에 추가되어 <b>그 전에 등록된 서버는 비어 있다</b> —
     * 마이그레이션 시각으로 백필하면 전부 같은(그리고 틀린) 날짜가 되므로 채우지 않았다.
     * id 는 auto_increment 라 그 자체가 등록 순서다.
     *
     * <p>서버가 하나도 없는 프로젝트는 맨 뒤로 보낸다.
     */
    private Map<String, Object> firstServer(Project project) {
        Server first = null;
        for (ServerGroup group : project.getServerGroups() == null ? List.<ServerGroup>of() : project.getServerGroups()) {
            for (Server s : group.getServers() == null ? List.<Server>of() : group.getServers()) {
                if (s.getId() == null) {
                    continue;
                }
                if (first == null || s.getId() < first.getId()) {
                    first = s;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", first == null ? null : first.getId());
        m.put("createdAt", first == null ? null : first.getCreatedAt());
        // 정렬 키 — 등록일이 없는 서버는 id 로 줄을 세운다(둘 다 등록 순서를 뜻한다).
        m.put("sortKey", first == null ? Long.MAX_VALUE : first.getId());
        return m;
    }

    /** 배포망 구성요소 — GitLab / Nexus / 배포시스템 자신. */
    private List<Map<String, Object>> buildZone(Project project) {
        List<Map<String, Object>> nodes = new ArrayList<>();

        String gitlabUrl = settingService.get("gitlab.url", "");
        nodes.add(node("gitlab", "GitLab", "소스 · CI 파이프라인", gitlabUrl,
            project.getGitlabRepoUrl()));

        String nexusUrl = settingService.get("nexus.url", "");
        String registry = settingService.get("nexus.registry", "");
        Map<String, Object> nexus = node("nexus", "Nexus", "이미지 저장소", nexusUrl, registry);
        // 레지스트리는 웹 UI 와 포트가 다르다(8081 vs 5000). 배포가 실제로 붙는 곳은 레지스트리다.
        nexus.put("registry", registry);
        nexus.put("registryPort", portOfHostPort(registry));
        nodes.add(nexus);

        // 이 시스템 자신 — 화면이 떠 있다는 것이 곧 살아 있다는 증거다.
        //   주소가 비었다고 "미설정" 으로 표시하면 멀쩡한 것을 문제로 오해하게 만든다.
        Map<String, Object> self = node("deployops", "배포시스템", "이 시스템 — 승인 · 실행", "", null);
        self.put("configured", true);
        self.put("self", true);
        nodes.add(self);
        return nodes;
    }

    private Map<String, Object> node(String id, String label, String role, String url, String extra) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("label", label);
        n.put("role", role);
        n.put("url", url);
        n.put("host", hostOf(url));
        n.put("port", portOf(url));
        n.put("extra", extra);
        // 주소가 비어 있으면 연결을 확인할 수도 없다 — 화면이 "미설정" 으로 구분해 보여준다.
        n.put("configured", url != null && !url.isBlank());
        return n;
    }

    /** 환경(TEST/PROD) → 서버 그룹 → 호기별 서버. */
    private List<Map<String, Object>> environmentZones(Project project) {
        Map<String, List<Map<String, Object>>> byEnv = new LinkedHashMap<>();

        List<ServerGroup> groups = project.getServerGroups() == null ? List.of() : project.getServerGroups();
        for (ServerGroup group : groups) {
            String env = group.getEnvironment() != null ? group.getEnvironment().name() : "UNKNOWN";

            Map<String, Object> g = new LinkedHashMap<>();
            g.put("groupId", group.getId());
            g.put("groupName", group.getName());
            g.put("environment", env);
            g.put("rollingIntervalSec", group.getRollingIntervalSec());

            List<Map<String, Object>> servers = new ArrayList<>();
            List<Server> members = group.getServers() == null ? List.of() : group.getServers();
            members.stream()
                .sorted(Comparator.comparingInt(Server::getOrderIndex)
                    .thenComparing(Server::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(s -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("serverId", s.getId());
                    n.put("serverName", s.getName());
                    n.put("ipAddress", s.getIpAddress());
                    n.put("sshPort", s.getSshPort());
                    n.put("controlMode", s.isAgentControlled() ? "AGENT" : "SSH");
                    n.put("serverType", s.getServerType() != null ? s.getServerType().name() : null);
                    n.put("unit", s.getOrderIndex());
                    n.put("deployAccount", s.getDeployAccount());
                    // 서비스가 열리는 포트 — 컴포넌트별 값이 있으면 그쪽이 우선이다.
                    n.put("servicePort", servicePortFor(project, s));
                    n.put("reachable", s.isAgentControlled() ? "UNKNOWN"
                        : s.getStatus() != null ? s.getStatus().name() : "UNKNOWN");
                    n.put("lastCheckedAt", s.getLastCheckedAt());
                    // 등록일. 컬럼이 생기기 전에 등록된 서버는 비어 있다 — 화면이 "등록일 미상" 이라고 쓴다.
                    n.put("createdAt", s.getCreatedAt());
                    servers.add(n);
                });
            g.put("servers", servers);

            byEnv.computeIfAbsent(env, k -> new ArrayList<>()).add(g);
        }

        List<Map<String, Object>> zones = new ArrayList<>();
        // TEST 를 먼저, PROD 를 뒤에 — 배포가 흐르는 순서가 곧 읽는 순서다.
        for (String env : List.of("TEST", "PROD", "DEV", "UNKNOWN")) {
            List<Map<String, Object>> gs = byEnv.remove(env);
            if (gs != null) {
                zones.add(Map.of("environment", env, "groups", gs));
            }
        }
        byEnv.forEach((env, gs) -> zones.add(Map.of("environment", env, "groups", gs)));
        return zones;
    }

    /**
     * 그 서버에 배포되는 컴포넌트의 서비스 포트.
     *
     * <p>{@code healthCheckPort} 하나가 host·container·health 포트를 다 정한다
     * ({@code RemoteDeployService#buildDeployArgs}). 그래서 이 값이 곧 "이 서버가 여는 포트" 다.
     */
    private Integer servicePortFor(Project project, Server server) {
        List<DeployComponent> comps = deployComponentRepository.findByProjectId(project.getId());
        for (DeployComponent c : comps) {
            if (c.isActive() && c.getRole() == server.getServerType() && c.getHealthCheckPort() != null) {
                return c.getHealthCheckPort();
            }
        }
        return project.getHealthCheckPort();
    }

    private List<Map<String, Object>> components(Project project) {
        List<Map<String, Object>> out = new ArrayList<>();
        deployComponentRepository.findByProjectId(project.getId()).stream()
            .filter(DeployComponent::isActive)
            .sorted(Comparator.comparingInt(DeployComponent::getDeployOrder))
            .forEach(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", c.getCode());
                m.put("displayName", c.getDisplayName());
                m.put("role", c.getRole() != null ? c.getRole().name() : null);
                m.put("artifactType", c.getArtifactType() != null ? c.getArtifactType().name() : null);
                m.put("imageRepository", c.getImageRepository() != null && !c.getImageRepository().isBlank()
                    ? c.getImageRepository() : project.getImageRepository());
                m.put("healthCheckPort", c.getHealthCheckPort() != null
                    ? c.getHealthCheckPort() : project.getHealthCheckPort());
                m.put("healthCheckPath", c.getHealthCheckPath() != null && !c.getHealthCheckPath().isBlank()
                    ? c.getHealthCheckPath() : project.getHealthCheckPath());
                out.add(m);
            });
        return out;
    }

    /* ─────────────────────────────────────────
       ② 연결 상태 — 실제로 두드려 본다. 느리므로 따로.
    ───────────────────────────────────────── */

    /**
     * 구간별 연결 확인.
     *
     * <p>여러 곳을 <b>동시에</b> 두드린다. 순서대로 하면 끊긴 곳마다 타임아웃이 쌓여
     * 대상이 늘수록 화면이 기하급수로 느려진다.
     */
    public Map<String, Object> health(Long projectId, SecurityUserDetails currentUser) {
        Project project = projectService.findByIdWithDetails(projectId, currentUser);

        Map<String, CompletableFuture<Map<String, Object>>> tasks = new LinkedHashMap<>();
        tasks.put("gitlab", probeAsync(settingService.get("gitlab.url", "")));
        tasks.put("nexus", probeAsync(settingService.get("nexus.url", "")));
        tasks.put("registry", CompletableFuture.supplyAsync(nexusMetadataService::probeRegistryEndpoint));

        List<Map<String, Object>> serverResults = new ArrayList<>();
        Map<Long, CompletableFuture<Map<String, Object>>> serverTasks = new LinkedHashMap<>();
        for (ServerGroup group : project.getServerGroups() == null ? List.<ServerGroup>of() : project.getServerGroups()) {
            for (Server s : group.getServers() == null ? List.<Server>of() : group.getServers()) {
                serverTasks.put(s.getId(), CompletableFuture.supplyAsync(
                    () -> probeServer(s)));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        tasks.forEach((k, f) -> out.put(k, join(f)));
        serverTasks.forEach((id, f) -> {
            Map<String, Object> r = join(f);
            r.put("serverId", id);
            // 붙기는 붙는데 최근 실패가 섞여 있으면 초록이 아니다 — 노랑으로 구분한다.
            if ("OK".equals(r.get("state"))) {
                applyRecentFailureWarning(project.getId(), id, r);
            }
            serverResults.add(r);
        });
        out.put("servers", serverResults);
        return out;
    }

    private Map<String, Object> probeServer(Server server) {
        if (!server.isAgentControlled()) {
            Map<String, Object> response = result("UNCONFIGURED", "에이전트 등록과 승인 후 연결을 확인해 주세요.", null);
            response.put("controlMode", "REGISTRATION_REQUIRED");
            return response;
        }
        Map<String, Object> response;
        try {
            Map<String, Object> registration = agentQueueService.status(server.getId());
            String state = String.valueOf(registration.get("registrationState"));
            if (!"ACTIVE".equals(state)) {
                response = result("UNCONFIGURED", "에이전트 등록과 관리자 승인을 확인해 주세요.", null);
            } else if (!Boolean.TRUE.equals(registration.get("connected"))) {
                response = result("BROKEN", "에이전트의 최근 heartbeat가 없습니다. 대상 서버의 에이전트와 연결을 확인해 주세요.", null);
            } else {
                boolean ready = registration.get("readiness") instanceof Map<?, ?> readiness
                    && Boolean.TRUE.equals(readiness.get("ready"));
                response = result(ready ? "OK" : "WARN", ready
                    ? "에이전트 heartbeat와 필수 실행 준비가 확인되었습니다."
                    : "에이전트가 연결됐습니다. Docker/nginx와 필수 실행 준비를 확인해 주세요.", null);
            }
            response.put("registrationState", state);
            response.put("lastSeenAt", registration.get("lastSeenAt"));
        } catch (RuntimeException e) {
            log.warn("구조도 에이전트 상태 조회 실패 serverId={} type={}", server.getId(), e.getClass().getSimpleName());
            response = result("UNKNOWN", "에이전트 상태를 조회하지 못했습니다. 연결 상태를 다시 확인해 주세요.", null);
        }
        response.put("controlMode", "AGENT");
        return response;
    }

    /** 최근 실행 이력에 실패가 섞여 있으면 {@code WARN} 으로 낮춘다. */
    private void applyRecentFailureWarning(Long projectId, Long serverId, Map<String, Object> result) {
        try {
            List<Boolean> recent = remoteCommandLogRepository
                .findRecentOutcomesByProjectIdAndServerId(projectId, serverId, RECENT_WINDOW);
            if (recent == null || recent.isEmpty()) {
                return;
            }
            long failures = recent.stream().filter(ok -> !Boolean.TRUE.equals(ok)).count();
            if (failures == 0) {
                return;
            }
            // 전부 실패면 "가끔" 이 아니다 — 그건 지금 고장난 것이라 BROKEN 으로 올린다.
            boolean allFailed = failures == recent.size();
            result.put("state", allFailed ? "BROKEN" : "WARN");
            result.put("recentFailures", failures);
            result.put("recentTotal", recent.size());
            result.put("detail", result.get("detail") + " · 최근 "
                + recent.size() + "회 중 " + failures + "회 실패");
        } catch (Exception e) {
            // 판정 보조 정보다 — 못 구했다고 연결 결과를 버리지 않는다.
            log.warn("최근 실행 이력 조회 실패 serverId={} : {}", serverId, e.toString());
        }
    }

    private CompletableFuture<Map<String, Object>> probeAsync(String url) {
        return CompletableFuture.supplyAsync(() -> {
            if (url == null || url.isBlank()) {
                return result("UNCONFIGURED", "주소가 설정되어 있지 않습니다.", null);
            }
            String host = hostOf(url);
            Integer port = portOf(url);
            if (host == null || port == null) {
                return result("UNCONFIGURED", "주소를 해석할 수 없습니다: " + url, null);
            }
            return probeHostPort(host, port);
        });
    }

    private Map<String, Object> probeHostPort(String host, Integer port) {
        if (host == null || host.isBlank() || port == null || port <= 0) {
            return result("UNCONFIGURED", "주소가 없습니다.", null);
        }
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            long ms = (System.nanoTime() - started) / 1_000_000;
            return result("OK", host + ":" + port + " 연결됨", ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            return result("BROKEN", host + ":" + port + " 연결 실패 — " + e.getClass().getSimpleName(), ms);
        }
    }

    private Map<String, Object> join(CompletableFuture<Map<String, Object>> f) {
        try {
            return f.get(PROBE_TIMEOUT_MS + 1500L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return result("BROKEN", "확인 중 오류: " + e.getClass().getSimpleName(), null);
        }
    }

    private Map<String, Object> result(String state, String detail, Long latencyMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);          // OK · BROKEN · UNCONFIGURED
        m.put("detail", detail);
        m.put("latencyMs", latencyMs);
        return m;
    }

    /* ── 주소 해석 ── */

    /** {@code http://h:8081} · {@code h:5000} 둘 다 받는다. 레지스트리는 스킴 없이 적는 것이 관례다. */
    private String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String v = url.trim();
        try {
            if (v.contains("://")) {
                return URI.create(v).getHost();
            }
        } catch (Exception ignored) {
            // 아래 host:port 파싱으로 넘어간다
        }
        String hostPort = v.replaceAll("/.*$", "");
        int idx = hostPort.lastIndexOf(':');
        return idx > 0 ? hostPort.substring(0, idx) : hostPort;
    }

    private Integer portOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String v = url.trim();
        try {
            if (v.contains("://")) {
                URI uri = URI.create(v);
                if (uri.getPort() > 0) {
                    return uri.getPort();
                }
                return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
        } catch (Exception ignored) {
            // 아래 host:port 파싱으로 넘어간다
        }
        return portOfHostPort(v);
    }

    private Integer portOfHostPort(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return null;
        }
        String v = hostPort.trim().replaceAll("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
        int idx = v.lastIndexOf(':');
        if (idx < 0) {
            return null;
        }
        try {
            return Integer.parseInt(v.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
