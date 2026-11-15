package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.ContainerPortBindings;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployRequestItem;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class RemoteDeployService {

    private static final String DEFAULT_WAS_SCRIPT = "/usr/local/deploy/bin/deploy-was-app.sh";
    private static final String DEFAULT_WEB_SCRIPT = "/usr/local/deploy/bin/deploy-web-app.sh";
    private static final String DEFAULT_WEB_STATIC_SCRIPT = "/usr/local/deploy/bin/deploy-web-static.sh";
    private static final String DEFAULT_WEB_PROXY_SCRIPT = "/usr/local/deploy/bin/deploy-web-proxy.sh";

    /**
     * 이미지 전달 방식. {@code registry}(기본) 는 대상 서버가 레지스트리에서 pull 하고,
     * {@code push} 는 배포시스템이 tar 로 저장해 scp 로 밀어 넣는다.
     *
     * <p>망분리로 대상 서버가 레지스트리에 붙을 수 없는 구성(개발망·운영망 → 배포망 inbound 차단)에서
     * {@code push} 를 쓴다. 기존 구성은 설정을 건드리지 않으면 종전대로 동작한다.
     */
    public static final String IMAGE_TRANSFER_SETTING = "deploy.image.transfer";
    public static final String STAGING_DIR_SETTING = "deploy.image.staging-dir";
    /** 이미지 안에서 정적 산출물이 들어 있는 경로. 프론트엔드 빌드 관례상 /dist 가 기본. */
    public static final String STATIC_IMAGE_PATH_SETTING = "deploy.static.image-path";
    /**
     * 컨테이너 메모리 상한(docker --memory 표기, 예: 2g / 1536m).
     *
     * <p>상한이 없으면 컨테이너 안 JVM 이 cgroup 이 아니라 호스트 전체 메모리를 보고 힙을 잡는다
     * (MaxRAMPercentage 기본 25%). 한 노드에 서비스를 여러 개 올리면 합계가 물리 메모리를 넘겨
     * 커널 OOM killer 가 엉뚱한 컨테이너를 죽인다. 전역 기본값으로 최소한의 방벽을 세운다.
     */
    public static final String CONTAINER_MEMORY_SETTING = "deploy.container.memory";
    private static final String DEFAULT_STAGING_DIR = "/var/tmp/deploy-ops";
    private static final String DEFAULT_CONTAINER_MEMORY = "2g";
    private static final String DEFAULT_STATIC_IMAGE_PATH = "/dist";

    private final SshCommandService sshCommandService;
    private final RemoteCommandLogService remoteCommandLogService;
    private final SystemSettingService settingService;
    private final ImageArtifactService imageExportService;
    private final RuntimeConfigurationService runtimeConfigurationService;
    private RolloutTrafficService rolloutTrafficService;
    private StorageMountService storageMountService;
    private ProjectRuntimeIsolationService runtimeIsolationService;
    private SecretMaskingService secretMaskingService = new SecretMaskingService();

    @org.springframework.beans.factory.annotation.Autowired
    void configureSecretMasking(SecretMaskingService service) { this.secretMaskingService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureRuntimeIsolation(ProjectRuntimeIsolationService service) { this.runtimeIsolationService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureRolloutTraffic(RolloutTrafficService service) {
        this.rolloutTrafficService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureStorageMounts(StorageMountService service) { this.storageMountService = service; }

    public RemoteDeployService(SshCommandService sshCommandService,
                               RemoteCommandLogService remoteCommandLogService,
                               SystemSettingService settingService,
                               ImageArtifactService imageExportService) {
        this(sshCommandService,remoteCommandLogService,settingService,imageExportService,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteDeployService(SshCommandService sshCommandService, RemoteCommandLogService remoteCommandLogService,
                               SystemSettingService settingService, ImageArtifactService imageExportService,
                               RuntimeConfigurationService runtimeConfigurationService) {
        this.runtimeConfigurationService=runtimeConfigurationService;
        this.settingService = settingService;
        this.imageExportService = imageExportService;
        this.sshCommandService = sshCommandService;
        this.remoteCommandLogService = remoteCommandLogService;
    }

    public DeployStatus execute(DeployRequest request, Consumer<String> logSink) {
        return executeTargets(request, resolveTargetServers(request), logSink);
    }

    public List<Server> resolveRetryTargets(DeployRequest request) {
        List<Long> successful = remoteCommandLogService.latestSuccessfulServerIds(request.getId());
        return resolveTargetServers(request).stream()
            .filter(server -> !successful.contains(server.getId())).toList();
    }

    public DeployStatus executeRetry(DeployRequest request, Consumer<String> logSink) {
        List<Server> targets = resolveRetryTargets(request);
        logSink.accept("[재시도] 같은 요청의 최신 성공 대상은 유지하고 실패·미실행 대상만 실행합니다.");
        return executeTargets(request, targets, logSink);
    }

    private DeployStatus executeTargets(DeployRequest request, List<Server> targets, Consumer<String> logSink) {
        Project project = requireProject(request);
        if (targets.isEmpty()) {
            throw new RemoteExecutionException("배포 대상 서버가 없습니다.");
        }
        // 재시도에서 성공한 서버를 제외하더라도 승인된 전체 대상의 역할 계약은 유지한다.
        var missingRoles = EffectiveDeployTargets.missingComponentRoles(project, resolveTargetServers(request));
        if (!missingRoles.isEmpty()) {
            throw new RemoteExecutionException(EffectiveDeployTargets.missingComponentMessage(missingRoles));
        }
        preflightAgentCapabilities(project, resolveTargetServers(request));

        logSink.accept("[배포] 요청: " + request.getReqNo());
        logSink.accept("[배포] 환경: " + request.getEnvironment());
        logSink.accept("[배포] 대상 서버 수: " + targets.size());

        // push 모드에서 만든 로컬 tar 는 이미지 참조별로 한 번만 만들어 재사용하고, 끝나면 지운다.
        Map<String, LocalArtifact> tarCache = new LinkedHashMap<>();
        List<TargetRecovery> changed = new ArrayList<>();
        try {
            for (int i = 0; i < targets.size(); i++) {
                Server server = targets.get(i);
                String imageRef = resolveImageRefForServer(project, request, server);
                try {
                    changed.add(executeRollingTarget(project, request, server, imageRef, tarCache, logSink));
                } catch (RuntimeException failure) {
                    if (failure instanceof AgentOutcomeUnknownException) {
                        logSink.accept("[결과 확인 필요] 에이전트 작업 결과가 확정되지 않아 다음 서버 배포와 자동 복구를 중단했습니다. 서버의 작업 결과를 확인하세요.");
                        throw failure;
                    }
                    compensateGroup(project, request, server, changed, logSink, failure);
                    throw failure;
                }
                applyRollingInterval(server.getServerGroup(), i, targets.size(), logSink);
            }
        } finally {
            cleanupTars(tarCache, logSink);
        }

        return request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_DEPLOYED
            : DeployStatus.COMPLETED;
    }

    /** Validate every target in the approved plan before dispatching the first agent command. */
    private void preflightAgentCapabilities(Project project, List<Server> targets) {
        for (Server server : targets) {
            if (!server.isAgentControlled()) continue;
            String runtime = artifactTypeOf(project, server) == ArtifactType.CONTAINER ? "docker" : "nginx";
            RemoteCommandResult probe = sshCommandService.execute(server,
                List.of("/usr/local/deploy/bin/deploy-agent-probe"));
            boolean ready = false;
            try {
                if (probe != null && probe.exitCode() == 0 && probe.stdout() != null && probe.stdout().length() <= 16384) {
                    var state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(probe.stdout());
                    ready = state != null && state.path("ready").isBoolean() && state.path("ready").booleanValue()
                        && server.getServerType().name().equals(state.path("role").asText())
                        && state.path("checks").path("helpers").path("ready").isBoolean()
                        && state.path("checks").path("helpers").path("ready").booleanValue()
                        && state.path("checks").path(runtime).path("ready").isBoolean()
                        && state.path("checks").path(runtime).path("ready").booleanValue();
                }
            } catch (java.io.IOException ignored) {
                // Never expose remote probe output; malformed readiness fails closed.
            }
            if (!ready) {
                throw new RemoteExecutionException("배포 전 실행 준비 확인 실패: " + server.getName()
                    + " — " + runtime + " 및 에이전트 준비 상태를 확인하세요. "
                    + "WEB이 nginx 프록시 전용이면 WEB 배포 단위를 PROXY_RELOAD로 설정하세요. 대상 서버 변경은 시작하지 않았습니다.");
            }
        }
    }

    /** MANUAL(수동) 전략용 — 요청의 대상 서버 목록(정렬됨)을 노출한다. */
    public List<Server> resolveTargets(DeployRequest request) {
        return resolveTargetServers(request);
    }

    /** Restore only the routing of a failed request; images and containers are unchanged. */
    public void recoverTraffic(DeployRequest request, Consumer<String> log) {
        if (rolloutTrafficService == null) throw new RemoteExecutionException("트래픽 복구 서비스를 사용할 수 없습니다.");
        Project project = requireProject(request);
        Map<String, com.portfolio.deployops.domain.RolloutTrafficRoute> routesToRestore = new LinkedHashMap<>();
        Map<String, Server> routeServers = new LinkedHashMap<>();
        for (Server server : resolveTargetServers(request)) {
            if (server.getServerType() != ServerType.WAS || artifactTypeOf(project, server) != ArtifactType.CONTAINER) continue;
            Integer port = effectiveHealthPort(project, resolveComponent(project, server));
            var routes = rolloutTrafficService.matching(project, server, port);
            if (routes.isEmpty()) continue;
            if (!nativeHealthy(project, request, server)) throw new RemoteExecutionException("서비스 상태 확인 실패로 트래픽을 복귀하지 않았습니다: " + server.getName());
            for (var route : routes) {
                String key = route.web().id() + ":" + route.policyName();
                routesToRestore.putIfAbsent(key, route); routeServers.putIfAbsent(key, server);
            }
        }
        // A policy restoration re-enables its currently drained endpoint. Check every
        // participating service first, then restore each policy exactly once.
        for (var entry : routesToRestore.entrySet()) {
            Server server = routeServers.get(entry.getKey());
            rolloutTrafficService.change(entry.getValue(), generation(request), server,
                effectiveHealthPort(project, resolveComponent(project, server)), false, log);
        }
        log.accept("[트래픽 복구] " + routesToRestore.size() + "개 연결을 확인했습니다. 전체 배포 완료 여부는 실패 대상 재시도에서 확인하세요.");
    }

    /**
     * 이미 배포되어 있는 서비스를 껐다 켠다 — <b>재배포가 아니다</b>.
     *
     * <p>이미지도 태그도 건드리지 않고 그 서버에 이미 만들어져 있는 컨테이너에만
     * {@code docker start/stop/restart} 를 건다. 그래서 이 동작으로는 <b>버전이 바뀌지 않고</b>,
     * 다시 켜면 직전에 배포된 그 버전이 그대로 올라온다. 승인 절차가 붙어 있는 "배포"와
     * 운영자가 즉시 해야 하는 "정지/기동"을 분리하기 위한 통로다.
     *
     * @param action {@code start} · {@code stop} · {@code restart} · {@code status} 중 하나
     */
    public RemoteCommandResult controlService(Project project, Server server, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!List.of("start", "stop", "restart", "status").contains(normalized)) {
            throw new RemoteExecutionException("지원하지 않는 동작입니다: " + action);
        }

        ArtifactType artifactType = artifactTypeOf(project, server);
        if (!"status".equals(normalized) && artifactType == ArtifactType.CONTAINER && runtimeIsolationService != null)
            runtimeIsolationService.assertIsolated(project, server);
        if (artifactType != ArtifactType.CONTAINER) {
            if (artifactType == ArtifactType.PROXY_RELOAD && "status".equals(normalized)) {
                return sshCommandService.execute(server,
                    buildProxyArgs(project, resolveComponent(project, server), server, "status",
                        server.getEnvironment() == ServerEnvironment.PROD ? DeployEnvironment.PROD
                            : server.getEnvironment() == ServerEnvironment.TEST ? DeployEnvironment.TEST : null));
            }
            if (artifactType == ArtifactType.STATIC_BUNDLE && "status".equals(normalized)) {
                DeployComponent component = resolveComponent(project, server);
                String webRoot = component.getWebRoot();
                if (webRoot == null || webRoot.isBlank()) {
                    throw new RemoteExecutionException("정적 배포의 WebRoot가 설정되지 않았습니다.");
                }
                List<String> statusArgs = new ArrayList<>();
                addSudoPrefix(statusArgs, server);
                statusArgs.addAll(List.of(DEFAULT_WEB_STATIC_SCRIPT, "status", "--web-root", webRoot.trim()));
                return sshCommandService.execute(server, statusArgs);
            }
            throw new RemoteExecutionException("정적 파일·프록시는 프로젝트 컨테이너의 기동·정지·재기동 대상이 아닙니다.");
        }

        int stopGrace = controlStopGrace(project,server);
        if (!"status".equals(normalized)) { requireStopGraceAgent(project,server); requireRecoveryAgent(project,server); }
        List<String> args = new ArrayList<>();
        addSudoPrefix(args, server);
        args.add(resolveRemoteScriptPath(project, server));
        args.add(normalized);
        if (!"status".equals(normalized)) args.addAll(List.of("--stop-grace-seconds",Integer.toString(stopGrace)));
        args.add("--container");
        args.add(resolveContainerName(project));
        if (project.getId() != null) args.addAll(List.of("--managed-project", project.getId().toString()));

        // start/restart 는 헬스체크까지 하므로 포트·경로를 넘긴다. stop/status 에는 필요 없다.
        if ("start".equals(normalized) || "restart".equals(normalized)) {
            DeployComponent component = resolveComponent(project, server);
            HealthCheckMode healthMode = effectiveHealthMode(project, component);
            args.add("--health-mode");
            args.add(agentHealthMode(healthMode));
            if (usesHttpHealth(healthMode)) {
                Integer healthPort = effectiveHealthPort(project, component);
                if (healthPort != null && healthPort > 0) {
                    args.add("--health-port");
                    args.add(String.valueOf(healthPort));
                }
                String healthPath = effectiveHealthPath(project, component);
                if (healthPath != null && !healthPath.isBlank()) {
                    args.add("--health-path");
                    args.add(healthPath);
                }
            }
        }

        // "누가 언제 운영 서비스를 내렸는가"는 호출한 컨트롤러가 감사 로그에 남긴다
        // (배포 로그 테이블은 배포 요청에 매여 있어 요청 없는 운영 동작을 넣을 수 없다).
        return sshCommandService.execute(server, args);
    }

    /** 프로젝트가 이 서버에서 쓰는 컨테이너 이름 — 화면에 무엇을 껐다 켜는지 그대로 보여주기 위한 것. */
    public String containerNameOf(Project project) {
        return resolveContainerName(project);
    }

    public ArtifactType artifactTypeOf(Project project, Server server) {
        DeployComponent component = resolveComponent(project, server);
        return component != null && component.getArtifactType() != null
            ? component.getArtifactType() : ArtifactType.CONTAINER;
    }

    public String serviceTargetOf(Project project, Server server) {
        DeployComponent component = resolveComponent(project, server);
        if (component != null && component.getArtifactType() == ArtifactType.STATIC_BUNDLE) {
            return component.getWebRoot();
        }
        return artifactTypeOf(project, server) == ArtifactType.CONTAINER ? resolveContainerName(project) : "Nginx 프록시";
    }

    /**
     * 컨테이너 표준출력 로그를 <b>지금 시점 기준 마지막 N줄</b> 가져온다.
     *
     * <p>{@code --follow} 를 쓰지 않는다. SSH 세션을 계속 물고 있으면 브라우저 탭 수만큼
     * 원격 프로세스가 남고, 클라이언트가 조용히 사라지면 회수되지 않는다.
     * 화면이 주기적으로 이걸 부르고 새로 늘어난 줄만 이어 붙이는 편이
     * 끊겨도 스스로 복구되고 서버에 아무것도 남기지 않는다.
     *
     * <p>읽기만 하는 동작이라 상태를 바꾸지 않는다 — 그래서 실행 권한이 아니라
     * 프로젝트 조회 권한으로 통제한다(호출부 담당).
     */
    public RemoteCommandResult containerLogs(Project project, Server server, int lines) {
        if (artifactTypeOf(project, server) != ArtifactType.CONTAINER) {
            throw new RemoteExecutionException("정적 파일·프록시에는 프로젝트 컨테이너 로그가 없습니다. 배포 실행 기록을 확인하세요.");
        }
        int n = Math.min(Math.max(lines, 1), 1000);

        List<String> args = new ArrayList<>();
        addSudoPrefix(args, server);
        args.add(resolveRemoteScriptPath(project, server));
        args.add("logs");
        args.add("--container");
        args.add(resolveContainerName(project));
        if (project.getId() != null) args.addAll(List.of("--managed-project", project.getId().toString()));
        args.add("--lines");
        args.add(String.valueOf(n));

        return sshCommandService.execute(server, args);
    }

    /** MANUAL(수동) 전략용 — 대상 중 한 서버만 배포한다. 실패 시 예외를 던진다. */
    public void executeServer(DeployRequest request, Long serverId, Consumer<String> logSink) {
        Project project = requireProject(request);
        Server server = resolveTargetServers(request).stream()
            .filter(target -> serverId.equals(target.getId()))
            .findFirst()
            .orElseThrow(() -> new RemoteExecutionException("배포 대상 서버를 찾을 수 없습니다."));
        if (server.getServerGroup() != null && server.getServerGroup().getFailurePolicy() == com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK)
            throw new RemoteExecutionException("그룹 전체 자동 복원은 SAFE/WAVE 전략에서 사용합니다. MANUAL은 STOP 정책으로 새 요청을 생성하세요.");
        preflightAgentCapabilities(project, resolveTargetServers(request));
        String imageRef = resolveImageRefForServer(project, request, server);
        logSink.accept("[배포] 단일 서버 실행: " + server.getName() + " / 이미지: " + imageRef);
        Map<String, LocalArtifact> tarCache = new LinkedHashMap<>();
        try {
            executeRollingTarget(project, request, server, imageRef, tarCache, logSink);
        } finally {
            cleanupTars(tarCache, logSink);
        }
    }

    /** 한 서버에 대한 원격 배포 + 로그 저장 + 결과 검증. 실패하면 RemoteExecutionException. */
    private RemoteCommandResult deployOneServer(Project project, DeployRequest request, Server server, String imageRef,
                                 Map<String, LocalArtifact> tarCache, Consumer<String> logSink, Long attemptId, PreparedMount mount) {
        String displayName = server.getName() + " (" + server.getIpAddress() + ")";
        logSink.accept("[" + displayName + "] " + (server.isAgentControlled() ? "에이전트" : "SSH") + " 배포 시작");
        if (artifactTypeOf(project,server)==ArtifactType.CONTAINER) requireStopGraceAgent(project,server);
        prepareRuntimeEnvironment(project,request,server);
        RemoteArtifact artifact = prepareArtifact(project, request, server, imageRef, tarCache, displayName, logSink);
        List<String> remoteArgs = buildDeployArgs(project, request, server, imageRef, artifact);
        if (mount != null) {
            remoteArgs.addAll(List.of("--mount-spec", mount.path(), "--mount-spec-sha256", mount.sha256()));
            if (!remoteArgs.contains("--managed-environment")) {
                DeployComponent component = resolveComponent(project, server);
                remoteArgs.addAll(List.of("--managed-environment", request.getEnvironment().name(),
                    "--managed-component", component == null ? "0" : component.getId().toString()));
            }
        }
        RemoteCommandResult result = sshCommandService.execute(server, remoteArgs);
        String safeOutput = secretMaskingService.mask(result.output());
        if (!safeOutput.isBlank()) {
            for (String line : safeOutput.split("\\R")) {
                if (!line.isBlank()) {
                    logSink.accept("[" + displayName + "] " + line);
                }
            }
        }

        if (result.exitCode() != 0) {
            throw new RemoteDeploymentFailure(
                "[" + displayName + "] 원격 배포 명령이 실패했습니다. exit=" + result.exitCode(), result);
        }

        logSink.accept("[" + displayName + "] 배포 완료");
        return result;
    }

    private Project requireProject(DeployRequest request) {
        if (request == null || request.getProject() == null) {
            throw new RemoteExecutionException("배포 요청 또는 프로젝트 정보가 없습니다.");
        }
        return request.getProject();
    }

    private record TargetRecovery(Server server, String previousStatic, String currentStatic) { }
    /** Carries the actual failed command to the single attempt-result persistence boundary. */
    private static final class RemoteDeploymentFailure extends RemoteExecutionException {
        private final RemoteCommandResult result;
        RemoteDeploymentFailure(String message, RemoteCommandResult result) {
            super(message);
            this.result = result;
        }
    }
    private static final class TrafficRecoveryPending extends RemoteExecutionException {
        TrafficRecoveryPending(String message, Throwable cause) { super(message, cause); }
    }

    private String generation(DeployRequest request) {
        return "r" + (request.getId() == null ? "0" : request.getId()) + "-" + safeReqNo(request);
    }

    private record PreparedMount(String path, String sha256) { }

    /** Storage validation precedes traffic removal. The agent validates again immediately before replacement. */
    private PreparedMount prepareStorageMounts(Project project, DeployRequest request, Server server, DeployComponent component) {
        Long componentId = component == null ? 0L : component.getId();
        var mounts = StorageMountService.pinnedFor(project, server, componentId);
        if (mounts.isEmpty()) return null;
        if (server.getServerType() != ServerType.WAS || storageMountService == null)
            throw new RemoteExecutionException("WAS 저장소 검증 서비스를 확인할 수 없습니다.");
        storageMountService.assertPinnedAllowed(mounts);
        StorageMountService.validateContainer(project, component);
        String user = component != null && component.getContainerUser() != null ? component.getContainerUser() : project.getContainerUser();
        String container = resolveContainerName(project);
        var spec = StorageMountService.spec(project.getId(), request.getEnvironment(), componentId, server,
            request.getId(), container, user, mounts);
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            byte[] payload = mapper.writeValueAsBytes(spec);
            var result = sshCommandService.executeWithInput(server, List.of("sudo", "-n", StorageMountService.HELPER, "prepare"), payload);
            if (result.exitCode() != 0) throw new RemoteExecutionException("WAS 저장소 준비 검사에 실패했습니다. 경로·NAS 연결·컨테이너 UID:GID 권한을 확인하세요.");
            var receipt = mapper.readTree(result.stdout());
            String path = receipt.path("path").asText();
            String hash = receipt.path("sha256").asText();
            String expected = "/var/lib/deploy-system/storage/specs/" + project.getId() + "-" + request.getEnvironment()
                + "-" + componentId + "-" + request.getId() + "-" + container + ".json";
            if (!expected.equals(path) || !hash.matches("[a-f0-9]{64}"))
                throw new RemoteExecutionException("WAS 저장소 준비 결과가 승인된 배포 대상과 일치하지 않습니다.");
            return new PreparedMount(path, hash);
        } catch (RemoteExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw new RemoteExecutionException("WAS 저장소 준비 결과를 확인할 수 없습니다."); }
    }

    private TargetRecovery executeRollingTarget(Project project, DeployRequest request, Server server, String imageRef,
                                                 Map<String, LocalArtifact> cache, Consumer<String> log) {
        ArtifactType type = artifactTypeOf(project, server);
        if (type == ArtifactType.CONTAINER && runtimeIsolationService != null) runtimeIsolationService.assertIsolated(project, server);
        if (type != ArtifactType.CONTAINER) {
            if (type == ArtifactType.STATIC_BUNDLE && server.getServerGroup() != null
                && server.getServerGroup().getFailurePolicy() == com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK)
                requireStaticRecoveryAgent(server);
            assertNonContainerAvailability(project, request, server);
            String previous = type == ArtifactType.STATIC_BUNDLE ? staticRelease(project, server) : null;
            Long attempt = remoteCommandLogService.beginTargetAttempt(request, server, imageRef);
            try {
                var result = deployOneServer(project, request, server, imageRef, cache, log, attempt, null);
                String current = type == ArtifactType.STATIC_BUNDLE ? staticRelease(project, server) : null;
                remoteCommandLogService.saveAttemptResult(request, server, result, attempt);
                return new TargetRecovery(server, previous, current);
            } catch (RuntimeException failure) { recordTargetFailure(request, server, attempt, failure, log); throw failure; }
        }
        requireStopGraceAgent(project, server);
        requireRecoveryAgent(project, server);
        assertMinimumAvailable(project, request, server, log);
        DeployComponent component = resolveComponent(project, server);
        Integer port = effectiveHealthPort(project, component);
        boolean externalVip = externalVip(project, server);
        List<com.portfolio.deployops.domain.RolloutTrafficRoute> routes = rolloutTrafficService == null
            ? List.of() : rolloutTrafficService.matching(project, server, port);
        if (externalVip) {
            if (!routes.isEmpty()) throw new RemoteExecutionException("WAS VIP 경유와 직접 nginx 트래픽 제어가 혼합되어 있습니다. 배포 경로를 확인하세요.");
            log.accept("[외부 WAS VIP] " + server.getServerGroup().getWasVipAddress()
                + " 경유: L4 멤버 제외·복귀 없이 순차 교체합니다. 개별 WAS 건강 상태를 확인하며 무중단은 보장하지 않습니다.");
        }
        boolean proxyRequired = server.getServerType() == ServerType.WAS && project.getComponents() != null && project.getComponents().stream()
            .anyMatch(item -> item.isActive() && item.getRole() == ServerType.WEB);
        if (proxyRequired && !externalVip) {
            var expectedEnvironment = request.getEnvironment() == DeployEnvironment.PROD ? ServerEnvironment.PROD : ServerEnvironment.TEST;
            var webIds = project.getServerGroups().stream().filter(group -> group.getEnvironment() == expectedEnvironment)
                .filter(group -> request.getSelectedServerGroupIds() == null || request.getSelectedServerGroupIds().isEmpty() || request.getSelectedServerGroupIds().contains(group.getId()))
                .flatMap(group -> group.getServers().stream()).filter(item -> item.getServerType() == ServerType.WEB).map(Server::getId).toList();
            if (routes.isEmpty() || webIds.stream().anyMatch(id -> routes.stream().noneMatch(route -> java.util.Objects.equals(route.web().id(), id))))
                throw new RemoteExecutionException("프로젝트의 각 WEB 프록시와 WAS 서비스 포트에 롤링 연결을 먼저 설정하세요.");
        }
        PreparedMount mount = prepareStorageMounts(project, request, server, component);
        Long attempt = remoteCommandLogService.beginTargetAttempt(request, server, imageRef);
        List<com.portfolio.deployops.domain.RolloutTrafficRoute> drained = new ArrayList<>();
        boolean replaced = false;
        boolean returningTraffic = false;
        try {
            for (var route : routes) {
                rolloutTrafficService.change(route, generation(request), server, port, true, log);
                drained.add(route);
            }
            if (!routes.isEmpty()) runNativeAction(project, request, server, "quiesce");
            RemoteCommandResult result = deployOneServer(project, request, server, imageRef, cache, log, attempt, mount);
            replaced = true;
            observe(project, request, server, log);
            returningTraffic = true;
            restoreTraffic(drained, request, server, port, log);
            remoteCommandLogService.saveAttemptResult(request, server, result, attempt);
            return new TargetRecovery(server, null, null);
        } catch (RuntimeException failure) {
            recordTargetFailure(request, server, attempt, failure, log);
            if (failure instanceof AgentOutcomeUnknownException) {
                log.accept("[결과 확인 필요] 실행 중일 수 있는 작업과의 충돌을 막기 위해 자동 롤백과 트래픽 변경을 보류했습니다.");
                throw failure;
            }
            if (returningTraffic) {
                // Some WEBs may already serve the healthy new version. Do not stop it underneath them.
                throw new TrafficRecoveryPending("새 서비스는 유지하며 트래픽 복귀를 보류했습니다. 요청의 트래픽 복구를 수행하세요.", failure);
            }
            // The native deploy command restores its own previous instance on startup/health failure.
            // If the later observation fails, restore the preserved generation explicitly.
            if (replaced) {
                try { runNativeAction(project, request, server, "restore"); }
                catch (AgentOutcomeUnknownException recovery) { throw recovery; }
                catch (RuntimeException recovery) { failure.addSuppressed(recovery); log.accept("[복구 실패] " + server.getName() + ": " + recovery.getMessage()); }
            }
            boolean healthy = nativeHealthy(project, request, server);
            if (healthy) {
                try { restoreTraffic(drained, request, server, port, log); }
                catch (AgentOutcomeUnknownException recovery) { throw recovery; }
                catch (RuntimeException recovery) { failure.addSuppressed(recovery); }
            } else if (!drained.isEmpty()) {
                log.accept("[트래픽 제외 유지] 복구된 서비스 상태를 확인할 수 없습니다. 해당 요청의 트래픽 복구가 필요합니다.");
            }
            throw failure;
        }
    }

    private void recordTargetFailure(DeployRequest request, Server server, Long attempt, RuntimeException failure, Consumer<String> log) {
        try {
            var now = java.time.LocalDateTime.now();
            RemoteCommandResult result = failure instanceof RemoteDeploymentFailure remote
                ? remote.result
                : new RemoteCommandResult(1, "", failure.getMessage(), "rolling-target-incomplete", now, now, 0);
            // This service masks command/stdout/stderr before durable storage. Do not save
            // separately in deployOneServer: recovery paths must keep one result per attempt.
            remoteCommandLogService.saveAttemptResult(request, server, result, attempt);
        } catch (RuntimeException persistence) {
            // The latest durable attempt exists without a success log, so retry does not skip this target.
            if (failure != persistence) failure.addSuppressed(persistence);
            log.accept("[기록 오류] 대상 완료 실패 기록을 저장하지 못했습니다.");
        }
    }

    private boolean externalVip(Project project, Server server) {
        return server.getServerType() == ServerType.WAS && WasVipPolicy.applies(project, server.getServerGroup());
    }

    private void assertMinimumAvailable(Project project, DeployRequest request, Server target, Consumer<String> log) {
        ServerGroup group = target.getServerGroup();
        if (group == null || group.getMinAvailable() <= 0) return;
        long healthy = resolveTargetServers(request).stream()
            .filter(server -> server != target && !java.util.Objects.equals(server.getId(), target.getId()) && server.getServerType() == target.getServerType())
            .filter(server -> server.getServerGroup() != null && java.util.Objects.equals(server.getServerGroup().getId(), group.getId()))
            .filter(server -> nativeHealthy(project, request, server)).count();
        if (healthy < group.getMinAvailable()) throw new RemoteExecutionException("그룹 " + group.getName() + "의 최소 가용 "
            + group.getMinAvailable() + "대를 유지할 수 없습니다. 교체 대상 외 정상 " + target.getServerType() + "=" + healthy
            + ". 최초 설치·계획 중단은 최소 가용 정책을 명시적으로 조정한 후 새 요청으로 진행하세요.");
        log.accept("[가용성] " + group.getName() + ": 교체 대상 외 정상 " + healthy + "대 확인");
    }

    private void assertNonContainerAvailability(Project project, DeployRequest request, Server target) {
        assertNonContainerAvailability(project, request, target, false);
    }

    private void assertNonContainerAvailability(Project project, DeployRequest request, Server target, boolean excludingTarget) {
        ServerGroup group = target.getServerGroup();
        if (group == null || group.getMinAvailable() <= 0) return;
        // Native WEB deployments keep nginx running and switch files atomically.
        // Their current instance remains available during validation and reload.
        long healthy = resolveTargetServers(request).stream().filter(server -> server.getServerType() == target.getServerType())
            .filter(server -> !excludingTarget || !java.util.Objects.equals(server.getId(), target.getId()))
            .filter(server -> server.getServerGroup() != null && java.util.Objects.equals(server.getServerGroup().getId(), group.getId()))
            .filter(server -> {
                try {
                    var response = controlService(project, server, "status");
                    ArtifactType peerType = artifactTypeOf(project, server);
                    return response.exitCode() == 0 && response.output().lines().anyMatch("NGINX_ACTIVE=true"::equals)
                        && (peerType != ArtifactType.STATIC_BUNDLE || response.output().lines().anyMatch("RELEASE_EXISTS=true"::equals))
                        && (peerType != ArtifactType.PROXY_RELOAD || response.output().lines().anyMatch("HTTP_HEALTHY=true"::equals));
                } catch (AgentOutcomeUnknownException unknown) { throw unknown; }
                catch (RuntimeException ignored) { return false; }
            }).count();
        if (healthy < group.getMinAvailable()) throw new RemoteExecutionException("그룹 " + group.getName() + "의 정상 WEB 최소 "
            + group.getMinAvailable() + "대를 확인할 수 없습니다. 최초 설치는 최소 가용 정책을 조정한 새 요청으로 진행하세요.");
    }

    private void requireRecoveryAgent(Project project, Server server) {
        List<String> args = new ArrayList<>(); addSudoPrefix(args, server);
        args.addAll(List.of(resolveRemoteScriptPath(project, server), "capabilities"));
        var response = sshCommandService.execute(server, args);
        if (response == null || response.exitCode() != 0 || !response.output().lines().anyMatch("ROLLBACK_VERSION=1"::equals))
            throw new RemoteExecutionException("컨테이너 에이전트의 직전 컨테이너 복구 지원이 필요합니다. 최신 설치기로 갱신하세요.");
        if (project.getId() != null && !response.output().lines().anyMatch("PROJECT_OWNERSHIP_VERSION=1"::equals))
            throw new RemoteExecutionException("컨테이너 에이전트의 프로젝트 소유권 확인 지원이 필요합니다. 최신 설치기로 갱신하세요.");
    }

    private void requireStaticRecoveryAgent(Server server) {
        List<String> args = new ArrayList<>(); addSudoPrefix(args, server);
        args.addAll(List.of(DEFAULT_WEB_STATIC_SCRIPT, "capabilities"));
        var response = sshCommandService.execute(server, args);
        if (response == null || response.exitCode() != 0 || !response.output().lines().anyMatch("EXACT_RESTORE_VERSION=1"::equals))
            throw new RemoteExecutionException("그룹 자동 복구를 위해 WEB 정적 에이전트의 정확한 릴리스 복원이 필요합니다. 최신 설치기로 갱신하세요.");
    }

    private List<String> nativeActionArgs(Project project, DeployRequest request, Server server, String action) {
        List<String> args = new ArrayList<>(); addSudoPrefix(args, server);
        args.addAll(List.of(resolveRemoteScriptPath(project, server), action, "--container", resolveContainerName(project)));
        if (project.getId() != null) args.addAll(List.of("--managed-project", project.getId().toString()));
        if ("restore".equals(action)) args.addAll(List.of("--deployment-id", generation(request)));
        DeployComponent component = resolveComponent(project, server);
        HealthCheckMode mode = effectiveHealthMode(project, component);
        args.addAll(List.of("--health-mode", agentHealthMode(mode), "--stop-grace-seconds", Integer.toString(controlStopGrace(project, server))));
        Integer port = effectiveHealthPort(project, component);
        if (port != null) args.addAll(List.of("--health-port", port.toString()));
        String path = effectiveHealthPath(project, component);
        if (path != null && !path.isBlank()) args.addAll(List.of("--health-path", path));
        return args;
    }

    private boolean nativeHealthy(Project project, DeployRequest request, Server server) {
        try {
            var response = sshCommandService.execute(server, nativeActionArgs(project, request, server, "probe"));
            return response != null && response.exitCode() == 0;
        } catch (AgentOutcomeUnknownException unknown) { throw unknown; }
        catch (RuntimeException ignored) { return false; }
    }

    private void runNativeAction(Project project, DeployRequest request, Server server, String action) {
        var response = sshCommandService.execute(server, nativeActionArgs(project, request, server, action));
        if (response == null || response.exitCode() != 0) throw new RemoteExecutionException(server.getName() + "의 " + action
            + " 실패: " + (response == null ? "원격 응답 없음" : response.output()));
    }

    private void observe(Project project, DeployRequest request, Server server, Consumer<String> log) {
        int seconds = server.getServerGroup() == null ? 0 : server.getServerGroup().getObservationSeconds();
        if (seconds > 0) {
            log.accept("[관찰] " + server.getName() + " 새 버전 " + seconds + "초");
            try { Thread.sleep(seconds * 1000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RemoteExecutionException("관찰이 중단됐습니다.", interrupted); }
        }
        if (!nativeHealthy(project, request, server)) throw new RemoteExecutionException("새 버전 관찰 후 서비스 상태 확인에 실패했습니다: " + server.getName());
    }

    private void restoreTraffic(List<com.portfolio.deployops.domain.RolloutTrafficRoute> drained, DeployRequest request,
                                Server server, Integer port, Consumer<String> log) {
        for (int index = drained.size() - 1; index >= 0; index--) {
            rolloutTrafficService.change(drained.get(index), generation(request), server, port, false, log);
            drained.remove(index);
        }
    }

    private String staticRelease(Project project, Server server) {
        var response = controlService(project, server, "status");
        if (response == null || response.exitCode() != 0) throw new RemoteExecutionException("WEB 정적 릴리스 상태를 확인할 수 없습니다.");
        return response.output().lines().filter(line -> line.startsWith("CURRENT_RELEASE="))
            .map(line -> line.substring("CURRENT_RELEASE=".length())).findFirst()
            .orElseThrow(() -> new RemoteExecutionException("WEB 정적 에이전트가 릴리스 상태를 제공하지 않습니다."));
    }

    private void compensateGroup(Project project, DeployRequest request, Server failed, List<TargetRecovery> changed,
                                 Consumer<String> log, RuntimeException cause) {
        ServerGroup group = failed.getServerGroup();
        if (cause instanceof TrafficRecoveryPending || group == null || group.getFailurePolicy() != com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK) return;
        for (int index = changed.size() - 1; index >= 0; index--) {
            TargetRecovery recovery = changed.get(index);
            Server server = recovery.server();
            if (server.getServerGroup() == null || !java.util.Objects.equals(group.getId(), server.getServerGroup().getId())) continue;
            try {
                // Invalidate prior success before any compensation changes the deployed version.
                Long compensation = remoteCommandLogService.beginTargetAttempt(request, server, resolveImageRefForServer(project, request, server));
                recordTargetFailure(request, server, compensation, new RemoteExecutionException("그룹 실패 정책에 따라 직전 상태로 복구합니다."), log);
                if (artifactTypeOf(project, server) == ArtifactType.CONTAINER) {
                    assertMinimumAvailable(project, request, server, log);
                    Integer port = effectiveHealthPort(project, resolveComponent(project, server));
                    List<com.portfolio.deployops.domain.RolloutTrafficRoute> drained = new ArrayList<>();
                    try {
                        var recoveryRoutes = rolloutTrafficService == null ? List.<com.portfolio.deployops.domain.RolloutTrafficRoute>of()
                            : rolloutTrafficService.matching(project, server, port);
                        if (externalVip(project, server) && !recoveryRoutes.isEmpty())
                            throw new RemoteExecutionException("WAS VIP 복구에 직접 nginx 트래픽 제어 경로가 혼합되어 있습니다.");
                        if (externalVip(project, server)) log.accept("[외부 WAS VIP 복구] L4를 제어하지 않고 해당 서비스의 직전 컨테이너를 복원합니다.");
                        for (var route : recoveryRoutes) {
                            rolloutTrafficService.change(route, generation(request), server, port, true, log); drained.add(route);
                        }
                        if (!drained.isEmpty()) runNativeAction(project, request, server, "quiesce");
                        runNativeAction(project, request, server, "restore");
                        if (!nativeHealthy(project, request, server)) throw new RemoteExecutionException("직전 서비스 복원 확인 실패");
                    } finally {
                        if (nativeHealthy(project, request, server)) restoreTraffic(drained, request, server, port, log);
                    }
                } else if (artifactTypeOf(project, server) == ArtifactType.STATIC_BUNDLE) {
                    // Restoring ABSENT removes the current release. It cannot count toward availability.
                    assertNonContainerAvailability(project, request, server, recovery.previousStatic().isBlank());
                    var component = resolveComponent(project, server);
                    List<String> args = new ArrayList<>(); addSudoPrefix(args, server);
                    args.addAll(List.of(DEFAULT_WEB_STATIC_SCRIPT, "restore-exact", "--web-root", component.getWebRoot(),
                        "--expected-release", recovery.currentStatic().isBlank() ? "ABSENT" : recovery.currentStatic(),
                        "--restore-release", recovery.previousStatic().isBlank() ? "ABSENT" : recovery.previousStatic()));
                    Integer port = effectiveHealthPort(project, component);
                    if (port != null) args.addAll(List.of("--health-port", port.toString()));
                    String path = effectiveHealthPath(project, component);
                    if (path != null && !path.isBlank()) args.addAll(List.of("--health-path", path));
                    var response = sshCommandService.execute(server, args);
                    if (response.exitCode() != 0) throw new RemoteExecutionException(response.output());
                }
                log.accept("[그룹 복구] " + server.getName() + " 직전 상태 복원 완료");
            } catch (AgentOutcomeUnknownException unknown) {
                log.accept("[그룹 복구 결과 확인 필요] " + server.getName() + ": 다른 서버의 자동 복구를 중단했습니다.");
                throw unknown;
            } catch (RuntimeException failure) {
                cause.addSuppressed(failure); log.accept("[그룹 복구 실패] " + server.getName() + ": " + failure.getMessage());
            }
        }
    }

    private List<Server> resolveTargetServers(DeployRequest request) {
        List<Server> servers = new ArrayList<>(EffectiveDeployTargets.resolve(request));

        servers.sort(strategyComparator(request.getDeployStrategy()));
        return servers;
    }

    /**
     * 배포 전략에 따른 대상 서버 정렬 순서.
     * SAFE(기본)/MANUAL: WAS 전부 → WEB 전부. WAVE: 같은 호기(orderIndex)끼리 묶어 WAS→WEB (was1,web1 → was2,web2).
     * (MANUAL의 서버 단위 스텝 실행은 Phase 3-B에서 별도 엔드포인트로 처리)
     */
    private Comparator<Server> strategyComparator(DeployStrategy strategy) {
        Comparator<Server> byType = Comparator.comparingInt(server -> serverTypeOrder(server.getServerType()));
        Comparator<Server> byGroup = Comparator.comparing(
            server -> server.getServerGroup() != null && server.getServerGroup().getId() != null ? server.getServerGroup().getId() : 0L);
        Comparator<Server> byOrder = Comparator.comparingInt(Server::getOrderIndex);
        Comparator<Server> byName = Comparator.comparing(Server::getName);

        if (strategy == DeployStrategy.WAVE) {
            return byGroup.thenComparing(byOrder).thenComparing(byType).thenComparing(byName);
        }
        return byGroup.thenComparing(byType).thenComparing(byOrder).thenComparing(byName);
    }

    private int serverTypeOrder(ServerType serverType) {
        if (serverType == ServerType.WAS) {
            return 10;
        }
        if (serverType == ServerType.WEB) {
            return 20;
        }
        return 99;
    }

    /**
     * 대상 서버의 컴포넌트에 해당하는 산출물 참조를 구한다. 요청에 컴포넌트별 항목(deploy_request_item)이
     * 있으면 그 이미지/번들을 쓰고, 없으면 요청의 단일 이미지로 폴백한다(하위호환).
     */
    private String resolveImageRefForServer(Project project, DeployRequest request, Server server) {
        DeployComponent component = resolveComponent(project, server);
        if (component != null && component.getArtifactType() == ArtifactType.PROXY_RELOAD) return "proxy-reload";
        if (component != null && request.getItems() != null && !request.getItems().isEmpty()) {
            DeployRequestItem item = request.getItems().stream()
                .filter(it -> (component.getId() != null && component.getId().equals(it.getComponentId()))
                    || (it.getComponentCode() != null && it.getComponentCode().equals(component.getCode())))
                .findFirst()
                .orElse(null);
            if (item != null) {
                String repository = (component.getImageRepository() != null && !component.getImageRepository().isBlank())
                    ? component.getImageRepository()
                    : project.getImageRepository();
                if (item.getImageDigest() != null && !item.getImageDigest().isBlank() && repository != null) {
                    return repository + "@" + item.getImageDigest();
                }
                if (item.getImageTag() != null && !item.getImageTag().isBlank() && repository != null) {
                    return repository + ":" + item.getImageTag();
                }
                if (item.getBundleRef() != null && !item.getBundleRef().isBlank()) {
                    return item.getBundleRef();
                }
            }
        }
        return resolveImageReference(request);
    }

    private String resolveImageReference(DeployRequest request) {
        String repository = requireProject(request).getImageRepository();
        if (repository == null || repository.isBlank()) {
            throw new RemoteExecutionException("프로젝트 이미지 저장소가 비어 있습니다.");
        }
        if (request.getImageDigest() != null && !request.getImageDigest().isBlank()) {
            return repository + "@" + request.getImageDigest();
        }
        String tag = request.getImageTag();
        if (tag == null || tag.isBlank()) {
            tag = sanitizeTag(request.getBranch());
        }
        if (tag == null || tag.isBlank()) {
            throw new RemoteExecutionException("배포할 이미지 태그를 결정할 수 없습니다.");
        }
        return repository + ":" + tag;
    }

    /**
     * 대상 서버에 보낼 원격 배포 명령을 구성한다. 프로젝트에 배포 단위가 있으면 컴포넌트의
     * artifactType(CONTAINER/STATIC_BUNDLE/PROXY_RELOAD)에 따라 분기하고, 없으면 기존 컨테이너 배포로 폴백한다.
     */
    private List<String> buildDeployArgs(Project project, DeployRequest request, Server server,
                                         String imageRef, RemoteArtifact artifact) {
        DeployComponent component = resolveComponent(project, server);
        if (component != null) {
            if (component.getArtifactType() == ArtifactType.STATIC_BUNDLE) {
                return buildStaticArgs(project, component, server, artifact);
            }
            if (component.getArtifactType() == ArtifactType.PROXY_RELOAD) {
                return buildProxyReloadArgs(project, component, server, request.getEnvironment());
            }
        }
        return buildContainerArgs(project, request, server, imageRef, artifact);
    }

    /** 대상 서버로 전송한 산출물(원격 경로 + 무결성 해시). 전송이 필요 없으면 null 을 쓴다. */
    private record RemoteArtifact(String remotePath, String sha256, String configId) { }

    /** 배포망에 만들어 둔 로컬 산출물. 같은 이미지에 대해 한 번만 만들고 서버마다 재사용한다. */
    private record LocalArtifact(Path file, String sha256, String configId) { }

    /**
     * 대상 서버에 필요한 산출물을 준비해 전송한다.
     *
     * <ul>
     *   <li><b>STATIC_BUNDLE(WEB)</b> — 배포망에서 이미지의 정적 산출물만 꺼내 tar.gz 로 만들어 보낸다.
     *       DMZ 는 tar 를 풀기만 하므로 <b>공개 접점에 docker 를 두지 않는다.</b> 전송량도 이미지 전체보다 작다.</li>
     *   <li><b>CONTAINER(WAS)</b> — push 모드일 때만 이미지 tar 를 보낸다. registry 모드면 대상이 직접 pull 한다.</li>
     *   <li><b>PROXY_RELOAD</b> — nginx 리로드만 하므로 전송할 산출물이 없다.</li>
     * </ul>
     */
    private RemoteArtifact prepareArtifact(Project project, DeployRequest request, Server server, String imageRef,
                                           Map<String, LocalArtifact> cache, String displayName,
                                           Consumer<String> logSink) {
        DeployComponent component = resolveComponent(project, server);
        ArtifactType type = component != null ? component.getArtifactType() : ArtifactType.CONTAINER;

        if (type == ArtifactType.PROXY_RELOAD) {
            return null;
        }

        if (type == ArtifactType.STATIC_BUNDLE) {
            // 정적은 전달 방식과 무관하게 항상 배포망에서 꺼내 보낸다(대상 서버는 registry 에 붙지 않는다).
            LocalArtifact bundle = cache.computeIfAbsent(
                "bundle:" + imageRef, key -> extractStaticBundle(project, request, imageRef, logSink));
            String remotePath = pushArtifact(server, bundle.file(), request, ".tar.gz", displayName, logSink);
            return new RemoteArtifact(remotePath, bundle.sha256(), null);
        }

        if (!isPushMode(project)) {
            return null;
        }
        LocalArtifact tar = cache.computeIfAbsent(
            "image:" + imageRef, key -> exportImage(request, imageRef, logSink));
        String remotePath = pushArtifact(server, tar.file(), request, ".tar", displayName, logSink);
        return new RemoteArtifact(remotePath, tar.sha256(), tar.configId());
    }

    private boolean isPushMode(Project project) {
        String mode = executionSetting(project, IMAGE_TRANSFER_SETTING, "registry");
        return mode != null && "push".equalsIgnoreCase(mode.trim());
    }

    /** snapshot 프로젝트면 승인 당시 값을, 일반 운영 화면 호출이면 현재 전역 값을 사용한다. */
    private String executionSetting(Project project, String key, String defaultValue) {
        DeploymentRuntimeSettings snapshot = project != null ? project.getDeploymentRuntimeSettings() : null;
        if (snapshot != null) {
            return switch (key) {
                case IMAGE_TRANSFER_SETTING -> snapshot.imageTransfer();
                case STAGING_DIR_SETTING -> snapshot.imageStagingDirectory();
                case STATIC_IMAGE_PATH_SETTING -> snapshot.staticImagePath();
                case CONTAINER_MEMORY_SETTING -> snapshot.containerMemory();
                default -> defaultValue;
            };
        }
        return settingService.get(key, defaultValue);
    }

    /** 이미지를 로컬 tar 로 저장한다(이미지 참조별 1회). */
    private LocalArtifact exportImage(DeployRequest request, String imageRef, Consumer<String> logSink) {
        try {
            Path tar = Files.createTempFile("deploy-ops-" + safeReqNo(request) + "-", ".tar");
            logSink.accept("[이미지] " + imageRef + " → tar 저장");
            try {
                ImageArtifactService.ImageArchive archive = imageExportService.exportImage(imageRef, tar, logSink);
                if (archive == null) {
                    throw new RemoteExecutionException("이미지 반출 무결성 메타데이터가 없습니다.");
                }
                return new LocalArtifact(tar, archive.sha256(), archive.configId());
            } catch (RuntimeException ex) {
                // 실패한 산출물은 cache에 들어가지 않으므로 여기서 회수한다.
                try { Files.deleteIfExists(tar); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
                throw ex;
            }
        } catch (IOException e) {
            throw new RemoteExecutionException("이미지 tar 임시 파일 생성 실패: " + e.getMessage(), e);
        }
    }

    /** 이미지에서 정적 산출물만 꺼내 tar.gz 로 만든다(배포망에서 수행, 이미지 참조별 1회). */
    private LocalArtifact extractStaticBundle(Project project, DeployRequest request, String imageRef,
                                              Consumer<String> logSink) {
        try {
            Path bundle = Files.createTempFile("deploy-ops-static-" + safeReqNo(request) + "-", ".tar.gz");
            String configured = executionSetting(project, STATIC_IMAGE_PATH_SETTING, DEFAULT_STATIC_IMAGE_PATH);
            String imagePath = configured == null || configured.isBlank()
                ? DEFAULT_STATIC_IMAGE_PATH : configured.trim();
            String sha = imageExportService.extractStaticBundle(imageRef, imagePath, bundle, logSink);
            return new LocalArtifact(bundle, sha, null);
        } catch (IOException e) {
            throw new RemoteExecutionException("정적 번들 임시 파일 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 컨테이너 메모리 상한을 돌려준다. 빈 값으로 설정하면 상한 없이 띄운다(권장하지 않음).
     *
     * <p>형식이 이상하면 기본값으로 되돌린다 — 잘못된 값으로 배포가 실패하는 것보다
     * 안전한 값으로 뜨는 편이 낫다.
     */
    private String resolveContainerMemory(Project project) {
        String configured = executionSetting(project, CONTAINER_MEMORY_SETTING, DEFAULT_CONTAINER_MEMORY);
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.matches("[0-9]+[bkmgBKMG]?") ? value : DEFAULT_CONTAINER_MEMORY;
    }

    /** 로컬 산출물을 대상 서버 스테이징 경로로 전송하고, 그 원격 경로를 돌려준다. */
    private String pushArtifact(Server server, Path localFile, DeployRequest request, String suffix,
                                String displayName, Consumer<String> logSink) {
        String configured = executionSetting(
            request != null ? request.getProject() : null,
            STAGING_DIR_SETTING,
            DEFAULT_STAGING_DIR
        );
        String stagingDir = configured == null ? "" : configured.trim();
        if (stagingDir.isBlank() || !stagingDir.startsWith("/")) {
            stagingDir = DEFAULT_STAGING_DIR;
        }
        String remotePath = stagingDir + "/" + safeReqNo(request) + "-" + server.getId() + suffix;

        // 스테이징 디렉터리는 배포 계정 소유로 만든다(sudo 불필요).
        RemoteCommandResult mkdir = sshCommandService.execute(server, List.of("mkdir", "-p", stagingDir));
        if (mkdir.exitCode() != 0) {
            throw new RemoteExecutionException(
                "[" + displayName + "] 스테이징 디렉터리 생성 실패(" + stagingDir + "): " + mkdir.stderr());
        }

        long sizeMb = localFile.toFile().length() / (1024 * 1024);
        logSink.accept("[" + displayName + "] 산출물 전송 시작 (" + sizeMb + "MB) → " + remotePath);
        RemoteCommandResult scp = sshCommandService.copyFile(server, localFile, remotePath);
        if (scp.exitCode() != 0) {
            throw new RemoteExecutionException(
                "[" + displayName + "] 산출물 전송 실패: " + scp.stderr());
        }
        logSink.accept("[" + displayName + "] 산출물 전송 완료");
        return remotePath;
    }

    /** 배포망에 만든 로컬 임시 산출물 정리. 실패해도 배포 결과를 바꾸지 않는다. */
    private void cleanupTars(Map<String, LocalArtifact> tarCache, Consumer<String> logSink) {
        for (LocalArtifact artifact : tarCache.values()) {
            Path tar = artifact.file();
            try {
                Files.deleteIfExists(tar);
            } catch (IOException e) {
                logSink.accept("[경고] 임시 산출물 삭제 실패: " + tar + " — " + e.getMessage());
            }
        }
        tarCache.clear();
    }

    private String safeReqNo(DeployRequest request) {
        String reqNo = request != null && request.getReqNo() != null ? request.getReqNo() : "req";
        return reqNo.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 대상 서버 역할(WAS/WEB)에 해당하는 활성 배포 단위. 없으면 null → 레거시 컨테이너 배포로 폴백. */
    private DeployComponent resolveComponent(Project project, Server server) {
        if (project.getComponents() == null || project.getComponents().isEmpty()) {
            return null;
        }
        List<DeployComponent> matching=project.getComponents().stream()
            .filter(DeployComponent::isActive)
            .filter(component -> component.getRole() == server.getServerType())
            .toList();
        if(matching.size()>1)
            throw new RemoteExecutionException("같은 서버 역할에 활성 배포 단위가 여러 개입니다. 역할별 배포 단위를 하나로 지정하거나 프로젝트를 분리하세요.");
        return matching.isEmpty()?null:matching.getFirst();
    }

    private void prepareRuntimeEnvironment(Project project, DeployRequest request, Server server) {
        DeployComponent component=resolveComponent(project,server);
        if(component!=null && component.getArtifactType()!=ArtifactType.CONTAINER) return;
        var selected=RuntimeConfigurationService.resolve(project.getManagedRuntimeSettings(),component==null?Long.valueOf(0L):component.getId());
        if(selected==null || !"MANAGED_REVISION".equals(selected.mode())) return;
        if(runtimeConfigurationService==null) throw new RemoteExecutionException("런타임 설정 전달을 준비할 수 없습니다.");
        String content=runtimeConfigurationService.decryptPinned(project.getId(),request.getEnvironment(),selected);
        List<String> args=new ArrayList<>(); addSudoPrefix(args,server);
        args.addAll(List.of("/usr/local/deploy/bin/deploy-runtime.sh","env","--project",project.getId().toString(),"--environment",request.getEnvironment().name(),"--revision",selected.revisionId().toString()));
        byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            var result=sshCommandService.executeSensitive(server,args,bytes);
            if(result.exitCode()!=0) throw new RemoteExecutionException("런타임 설정 전달에 실패했습니다.");
        } finally { java.util.Arrays.fill(bytes,(byte)0); }
    }

    private int controlStopGrace(Project project, Server server) {
        var settings = project.getManagedRuntimeSettings();
        if (runtimeConfigurationService != null) {
            DeployEnvironment env = server.getEnvironment()==ServerEnvironment.PROD ? DeployEnvironment.PROD : DeployEnvironment.TEST;
            settings = runtimeConfigurationService.bindings(project.getId(),env);
        }
        var component=resolveComponent(project,server);
        var selected=RuntimeConfigurationService.resolve(settings,component==null?0L:component.getId());
        return com.portfolio.deployops.domain.StopGrace.seconds(selected==null?null:selected.stopGraceSeconds());
    }

    private void requireStopGraceAgent(Project project, Server server) {
        List<String> args=new ArrayList<>(); addSudoPrefix(args,server);
        args.addAll(List.of(resolveRemoteScriptPath(project,server),"capabilities"));
        var capability=sshCommandService.execute(server,args);
        if(capability==null || capability.exitCode()!=0 || !capability.output().lines().anyMatch("STOP_GRACE_VERSION=1"::equals))
            throw new RemoteExecutionException("이 서버의 배포 에이전트는 종료 유예 설정을 지원하지 않습니다. 정상 설치 절차로 에이전트를 업데이트한 뒤 다시 실행해 주세요.");
    }

    private HealthCheckMode effectiveHealthMode(Project project, DeployComponent component) {
        if (component != null && component.getHealthCheckMode() != null) {
            return component.getHealthCheckMode();
        }
        return project.getHealthCheckMode() != null
            ? project.getHealthCheckMode() : HealthCheckMode.SSH_LOCAL_CURL;
    }

    private Integer effectiveHealthPort(Project project, DeployComponent component) {
        return component != null && component.getHealthCheckPort() != null
            ? component.getHealthCheckPort() : project.getHealthCheckPort();
    }

    private String effectiveHealthPath(Project project, DeployComponent component) {
        return component != null && component.getHealthCheckPath() != null
            && !component.getHealthCheckPath().isBlank()
            ? component.getHealthCheckPath() : project.getHealthCheckPath();
    }

    private boolean usesHttpHealth(HealthCheckMode mode) {
        return mode == HealthCheckMode.SSH_LOCAL_CURL || mode == HealthCheckMode.HTTP_DIRECT;
    }

    private String agentHealthMode(HealthCheckMode mode) {
        return switch (mode) {
            case CONTAINER_RUNNING -> "container";
            case NONE -> "none";
            case HTTP_DIRECT, SSH_LOCAL_CURL -> "http";
        };
    }

    private void addSudoPrefix(List<String> args, Server server) {
        if (!"root".equalsIgnoreCase(server.getDeployAccount())) {
            args.add("sudo");
            args.add("-n");
        }
    }

    /** STATIC_BUNDLE: 정적 산출물(번들)을 nginx webroot에 원자 교체 + reload. */
    /**
     * STATIC_BUNDLE: 이미지에서 정적 산출물만 꺼내 릴리스 디렉터리에 원자 교체한다.
     *
     * <p>컨테이너를 띄우지 않으므로 순단이 없고 nginx 설정은 호스트에 남는다. 배포 단위가
     * 이미지(digest)라 WAS 와 롤백·추적 방식이 같아지고 registry 에 보관되므로 별도 백업이 필요 없다.
     */
    private List<String> buildStaticArgs(Project project, DeployComponent component, Server server,
                                         RemoteArtifact artifact) {
        if (artifact == null) {
            throw new RemoteExecutionException("정적 배포 번들이 준비되지 않았습니다.");
        }
        List<String> args = new ArrayList<>();
        addSudoPrefix(args, server);
        args.add(DEFAULT_WEB_STATIC_SCRIPT);
        args.add("deploy");
        args.add("--bundle");
        args.add(artifact.remotePath());
        if (artifact.sha256() != null && !artifact.sha256().isBlank()) {
            args.add("--bundle-sha256");
            args.add(artifact.sha256());
        }
        if (component.getWebRoot() != null && !component.getWebRoot().isBlank()) {
            args.add("--web-root");
            args.add(component.getWebRoot().trim());
        }
        Integer healthPort = component.getHealthCheckPort() != null
            ? component.getHealthCheckPort() : project.getHealthCheckPort();
        if (healthPort != null && healthPort > 0) {
            args.add("--health-port");
            args.add(String.valueOf(healthPort));
        }
        String healthPath = component.getHealthCheckPath() != null
            ? component.getHealthCheckPath() : project.getHealthCheckPath();
        if (healthPath != null && !healthPath.isBlank()) {
            args.add("--health-path");
            args.add(healthPath);
        }
        return args;
    }

    /** PROXY_RELOAD: nginx 설정 검증·리로드 후 실제 HTTP 응답을 확인한다. */
    private List<String> buildProxyReloadArgs(Project project, DeployComponent component, Server server, DeployEnvironment environment) {
        return buildProxyArgs(project, component, server, "reload", environment);
    }

    private List<String> buildProxyArgs(Project project, DeployComponent component, Server server, String action, DeployEnvironment environment) {
        if (!usesHttpHealth(effectiveHealthMode(project, component))) {
            throw new RemoteExecutionException("WEB 프록시 배포에는 HTTP 헬스체크가 필요합니다.");
        }
        Integer configuredPort = effectiveHealthPort(project, component);
        int port = configuredPort != null ? configuredPort : 80;
        String path = effectiveHealthPath(project, component);
        path = path == null || path.isBlank() ? "/" : path;
        if (port < 1 || port > 65535 || path.length() > 2048
            || !path.startsWith("/") || path.startsWith("//")
            || path.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
            || path.contains("#")) {
            throw new RemoteExecutionException("WEB 프록시 헬스체크 포트 또는 경로가 올바르지 않습니다.");
        }
        List<String> args = new ArrayList<>();
        addSudoPrefix(args, server);
        args.add(DEFAULT_WEB_PROXY_SCRIPT);
        args.add(action);
        args.add("--health-port");
        args.add(String.valueOf(port));
        args.add("--health-path");
        args.add(path);
        try {
            String host = com.portfolio.deployops.domain.ProxyHealthHost.normalize(component.resolveProxyHealthHost(environment));
            if (host != null) {
                if (component.getRole() != ServerType.WEB) {
                    throw new IllegalArgumentException("HTTP Host는 WEB 프록시 배포에만 사용할 수 있습니다.");
                }
                args.add("--health-host");
                args.add(host);
            }
        } catch (IllegalArgumentException error) {
            throw new RemoteExecutionException(error.getMessage());
        }
        return args;
    }

    /** CONTAINER: docker run 기반(기존 동작). remoteTarPath 가 있으면 pull 대신 load 한다. */
    private List<String> buildContainerArgs(Project project, DeployRequest request, Server server, String imageRef, RemoteArtifact artifact) {
        List<String> args = new ArrayList<>();
        addSudoPrefix(args, server);
        args.add(resolveRemoteScriptPath(project, server));
        args.add("deploy");
        args.add("--image");
        args.add(imageRef);
        if (artifact != null) {
            args.add("--image-tar");
            args.add(artifact.remotePath());
            args.add("--image-id");
            args.add(artifact.configId());
            args.add("--image-sha256");
            args.add(artifact.sha256());
        }
        args.add("--container");
        args.add(resolveContainerName(project));

        args.addAll(List.of("--deployment-id", generation(request)));
        if (project.getId() != null) args.addAll(List.of("--managed-project", project.getId().toString()));

        String memoryLimit = resolveContainerMemory(project);
        if (!memoryLimit.isBlank()) {
            args.add("--memory");
            args.add(memoryLimit);
        }

        // 프로젝트에 도커 네트워크/런타임 env-file이 지정되면 컨테이너에 주입한다.
        // (DB 등 런타임 의존성 접속 정보는 대상 서버에 미리 둔 env-file 로 관리 — 폐쇄망 원칙)
        String dockerNetwork = project.getDockerNetwork();
        if (dockerNetwork != null && !dockerNetwork.isBlank()) {
            args.add("--network");
            args.add(dockerNetwork.trim());
        }
        DeployComponent runtimeComponent = resolveComponent(project, server);
        var managed = RuntimeConfigurationService.resolve(project.getManagedRuntimeSettings(),runtimeComponent==null?Long.valueOf(0L):runtimeComponent.getId());
        args.addAll(List.of("--stop-grace-seconds",Integer.toString(com.portfolio.deployops.domain.StopGrace.seconds(managed==null?null:managed.stopGraceSeconds()))));
        String envFilePath = managed==null ? project.getEnvFilePath() : "MANAGED_REVISION".equals(managed.mode())
            ? "/etc/deploy/runtime/"+project.getId()+"/"+request.getEnvironment()+"/"+managed.revisionId()+".env" : null;
        if (envFilePath != null && !envFilePath.isBlank()) {
            args.add("--env-file");
            args.add(envFilePath.trim());
        }

        DeployComponent component = resolveComponent(project, server);
        String user = component != null && component.getContainerUser() != null
            ? component.getContainerUser() : project.getContainerUser();
        if (user != null && !user.isBlank()) { args.add("--user"); args.add(user.trim()); }
        if(managed!=null && (managed.persistentLogs() || managed.tmpMiB()>0)) {
            if(user==null || !user.matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,9}")) throw new RemoteExecutionException("관리 저장소를 사용하려면 숫자 UID:GID를 지정해 주세요.");
            args.addAll(List.of("--managed-project",project.getId().toString(),"--managed-environment",request.getEnvironment().name(),"--managed-component",runtimeComponent==null?"0":runtimeComponent.getId().toString()));
            if(managed.persistentLogs()) args.add("--persistent-logs");
            if(managed.tmpMiB()>0) args.addAll(List.of("--tmp-mib",Integer.toString(managed.tmpMiB())));
        }
        boolean privileged = component != null ? component.isContainerPrivileged() : project.isContainerPrivileged();
        if (privileged) { args.add("--privileged"); args.add("true"); }
        String caps = component != null && component.getContainerCapAdd() != null
            ? component.getContainerCapAdd() : project.getContainerCapAdd();
        if (caps != null && !caps.isBlank()) { args.add("--cap-add"); args.add(caps.trim()); }
        boolean readOnly = component != null ? component.isContainerReadOnlyRootFs() : project.isContainerReadOnlyRootFs();
        if (readOnly) { args.add("--read-only"); args.add("true"); }

        List<String> portBindings = ContainerPortBindings.bindings(project.getContainerPorts());
        for (String binding : portBindings) {
            args.add("--publish");
            args.add(binding);
        }
        HealthCheckMode healthMode = effectiveHealthMode(project, component);
        args.add("--health-mode");
        args.add(agentHealthMode(healthMode));

        Integer healthPort = effectiveHealthPort(project, component);
        if (usesHttpHealth(healthMode) && healthPort != null && healthPort > 0 && portBindings.isEmpty()) {
            args.add("--host-port");
            args.add(String.valueOf(healthPort));
            args.add("--container-port");
            args.add(String.valueOf(healthPort));
        }
        if (usesHttpHealth(healthMode) && healthPort != null && healthPort > 0) {
            args.add("--health-port");
            args.add(String.valueOf(healthPort));
        }

        String healthPath = effectiveHealthPath(project, component);
        if (usesHttpHealth(healthMode) && healthPath != null && !healthPath.isBlank()) {
            args.add("--health-path");
            args.add(healthPath);
        }

        if (server.getServerType() == ServerType.WEB) {
            args.add("--reload-nginx");
            args.add("true");
        }

        return args;
    }

    private String resolveRemoteScriptPath(Project project, Server server) {
        if (project.getRemoteDeployScriptPath() != null && !project.getRemoteDeployScriptPath().isBlank()) {
            return project.getRemoteDeployScriptPath();
        }
        return server.getServerType() == ServerType.WEB ? DEFAULT_WEB_SCRIPT : DEFAULT_WAS_SCRIPT;
    }

    private String resolveContainerName(Project project) {
        return com.portfolio.deployops.domain.ProjectContainerIdentity.resolve(project);
    }

    private String sanitizeTag(String rawTag) {
        if (rawTag == null || rawTag.isBlank()) {
            return null;
        }
        return rawTag.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
    }

    private void applyRollingInterval(ServerGroup group, int index, int total, Consumer<String> logSink) {
        if (group == null || index >= total - 1) {
            return;
        }
        int intervalSec = Math.max(group.getRollingIntervalSec(), 0);
        if (intervalSec == 0) {
            return;
        }
        logSink.accept("[배포] 롤링 대기 " + intervalSec + "초");
        try {
            Thread.sleep(intervalSec * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteExecutionException("롤링 대기 중 인터럽트가 발생했습니다.", e);
        }
    }
}
