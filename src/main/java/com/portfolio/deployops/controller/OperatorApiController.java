package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.ManualServerExecution;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.RemoteCommandLog;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.service.ArchitectureService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DeployLogService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.DeploymentDutySeparationService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.ManualServerExecutionService;
import com.portfolio.deployops.service.OperatorExecutionService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.RemoteCommandResult;
import com.portfolio.deployops.service.RemoteDeployService;
import com.portfolio.deployops.domain.ArtifactType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/operator")
@RequiredArgsConstructor
@Slf4j
public class OperatorApiController {

    private final DeployRequestService deployRequestService;
    private final AuditLogService auditLogService;
    private final DeployLogService deployLogService;
    private final OperatorExecutionService operatorExecutionService;
    private final RemoteDeployService remoteDeployService;
    private final ProjectPermissionService projectPermissionService;
    private final ProjectService projectService;
    private final RemoteCommandLogRepository remoteCommandLogRepository;
    private final DeployRequestRepository deployRequestRepository;
    private final ArchitectureService architectureService;
    private final DeploymentDutySeparationService dutySeparationService;
    private final ExecutionPolicyService executionPolicyService;
    private final ManualServerExecutionService manualServerExecutionService;

    @GetMapping(value = "/logs/{requestId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(
        @PathVariable Long requestId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        DeployRequest request = deployRequestService.findById(requestId);
        assertExecutePermission(currentUser, request);
        return deployLogService.subscribe(requestId);
    }

    @PostMapping("/execute/{requestId}")
    public ApiResponse<?> execute(
        @PathVariable Long requestId,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest request = deployRequestService.findById(requestId);
        assertExecutePermission(currentUser, request);
        dutySeparationService.assertCanOperate(request, currentUser);
        deployRequestService.assertProductionRollbackProvenance(request);
        try {
            deployRequestService.assertPlanUnchanged(request);
        } catch (ResponseStatusException rejected) {
            if (rejected.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                auditLogService.record(currentUser.getName(), "배포 실행 거부", "DeployRequest",
                    String.valueOf(requestId), "code=DEPLOYMENT_PLAN_REJECTED",
                    AuditResult.FAIL, httpRequest.getRemoteAddr());
            }
            throw rejected;
        }
        DeployStatus startStatus = startStatus(request);
        // 상태 검사와 선점을 한 문장으로 묶는다. 더블클릭·새로고침·두 사람 동시 실행에서
        // 먼저 도착한 요청만 통과해야 한다 — 읽고 검사한 뒤 갱신하면 둘 다 통과해
        // 같은 서버에 배포가 두 번 뜬다.
        if (!executionPolicyService.tryStart(request, requiredReadyStatus(request), startStatus)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "배포 가능한 상태가 아니거나 이미 실행 중입니다. 현재 상태: "
                    + deployRequestService.findById(requestId).getStatus()
            );
        }
        try {
            deployRequestService.markExecuted(requestId, currentUser.getId());
            auditLogService.record(
                currentUser.getName(),
                request.getEnvironment() == DeployEnvironment.PROD ? "PROD 배포 시작" : "TEST 배포 시작",
                "DeployRequest",
                String.valueOf(requestId),
                request.getReqNo() + " / " + request.getProject().getName(),
                AuditResult.SUCCESS,
                httpRequest.getRemoteAddr()
            );

            operatorExecutionService.executeRemoteDeployment(
                requestId,
                currentUser.getName(),
                httpRequest.getRemoteAddr(),
                currentUser.getRole()
            );
        } catch (RuntimeException ex) {
            // @Async executor가 작업을 받지 못한 경우 실행 중 상태에 고착시키지 않는다.
            DeployStatus failed = request.getEnvironment() == DeployEnvironment.TEST
                ? DeployStatus.TEST_FAILED : DeployStatus.PROD_FAILED;
            deployRequestService.tryTransition(requestId, startStatus, failed);
            throw ex;
        }

        return ApiResponse.ok("배포가 시작되었습니다.", Map.of(
            "requestId", requestId,
            "reqNo", request.getReqNo(),
            "status", startStatus
        ));
    }

    @PostMapping("/retry/{requestId}")
    public ApiResponse<?> retry(
        @PathVariable Long requestId,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest request = deployRequestService.findById(requestId);
        assertExecutePermission(currentUser, request);
        dutySeparationService.assertCanOperate(request, currentUser);
        deployRequestService.assertProductionRollbackProvenance(request);
        if (!com.portfolio.deployops.service.DeploymentRetryPolicy.eligible(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "고정된 이미지가 있는 실패한 SAFE 요청만 재시도할 수 있습니다. 빌드 실패는 새 TEST 요청으로 처리해 주세요.");
        }
        deployRequestService.assertPlanUnchanged(request);
        if (remoteDeployService.resolveRetryTargets(request).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "실패하거나 아직 실행하지 않은 대상이 없습니다.");
        }
        DeployStatus failed = request.getStatus();
        DeployStatus deploying = request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_DEPLOYING : DeployStatus.PROD_DEPLOYING;
        if (!executionPolicyService.tryStart(request, failed, deploying)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 작업이 먼저 실행되었거나 재시도 가능한 상태가 아닙니다.");
        }
        try {
            deployRequestService.markExecuted(requestId, currentUser.getId());
            auditLogService.record(currentUser.getName(), "배포 실패 대상 재시도 시작", "DeployRequest",
                String.valueOf(requestId), request.getReqNo() + " / " + request.getProject().getName(),
                AuditResult.SUCCESS, httpRequest.getRemoteAddr());
            operatorExecutionService.retryRemoteDeployment(requestId, currentUser.getName(),
                httpRequest.getRemoteAddr(), currentUser.getRole());
        } catch (RuntimeException ex) {
            deployRequestService.tryTransition(requestId, deploying, failed);
            throw ex;
        }
        return ApiResponse.ok("실패·미실행 대상의 재시도가 시작되었습니다.", Map.of(
            "requestId", requestId, "reqNo", request.getReqNo(), "status", deploying));
    }

    @PostMapping("/complete/{requestId}")
    public ApiResponse<?> complete(
        @PathVariable Long requestId,
        @RequestBody Map<String, Object> body,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest request = deployRequestService.findById(requestId);
        assertExecutePermission(currentUser, request);
        dutySeparationService.assertCanOperate(request, currentUser);
        if (isTerminalStatus(request.getStatus())) {
            return ApiResponse.ok("이미 최종 처리된 배포입니다.", Map.of("status", request.getStatus()));
        }
        deployRequestService.assertProductionRollbackProvenance(request);
        if (request.getDeployStrategy() != DeployStrategy.MANUAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수동 완료 처리는 MANUAL 배포에서만 사용할 수 있습니다.");
        }
        DeployStatus deploying = request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_DEPLOYING
            : DeployStatus.PROD_DEPLOYING;
        if (request.getStatus() != deploying) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수동 배포가 실행 중인 상태가 아닙니다.");
        }

        // 수동 완료/실패 처리는 엔진 판정을 사람이 덮어쓰는 행위 — 사유를 강제해 감사 추적성을 확보한다.
        String reason = body.get("reason") instanceof String text ? text.trim() : "";
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수동 완료/실패 처리 사유는 필수입니다.");
        }

        boolean success = Boolean.TRUE.equals(body.get("success"));
        List<Long> targetIds = remoteDeployService.resolveTargets(request).stream().map(Server::getId).toList();
        if (success && !manualServerExecutionService.allSucceeded(requestId, targetIds)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "모든 대상 서버의 배포가 성공해야 완료 처리할 수 있습니다.");
        }
        DeployStatus newStatus = success
            ? operatorExecutionService.resolveSuccessfulStatus(request, request.getEnvironment() == DeployEnvironment.TEST
                ? DeployStatus.TEST_DEPLOYED
                : DeployStatus.COMPLETED)
            : (request.getEnvironment() == DeployEnvironment.TEST ? DeployStatus.TEST_FAILED : DeployStatus.PROD_FAILED);

        if (!deployRequestService.tryTransition(requestId, deploying, newStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 작업이 먼저 배포 상태를 변경했습니다.");
        }
        auditLogService.record(
            currentUser.getName(),
            (request.getEnvironment() == DeployEnvironment.PROD ? "PROD 배포 " : "TEST 배포 ")
                + (success ? "수동 완료" : "수동 실패 처리"),
            "DeployRequest",
            String.valueOf(requestId),
            request.getReqNo() + " / " + request.getProject().getName() + " / 사유: " + reason,
            success ? AuditResult.SUCCESS : AuditResult.FAIL,
            httpRequest.getRemoteAddr()
        );

        return ApiResponse.ok(
            success ? "배포가 완료되었습니다." : "배포가 실패 처리되었습니다.",
            Map.of("status", newStatus)
        );
    }

    @GetMapping("/targets/{requestId}")
    public ApiResponse<?> targets(
        @PathVariable Long requestId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        DeployRequest request = deployRequestService.findById(requestId);
        assertExecutePermission(currentUser, request);
        Map<Long, ManualServerExecution> states = manualServerExecutionService.statuses(requestId);
        List<Map<String, Object>> targets = remoteDeployService.resolveTargets(request).stream()
            .map(server -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", server.getId());
                entry.put("name", server.getName());
                entry.put("type", server.getServerType() != null ? server.getServerType().name() : "");
                entry.put("ip", server.getIpAddress());
                entry.put("order", server.getOrderIndex());
                ManualServerExecution state = states.get(server.getId());
                entry.put("status", state != null ? state.getStatus() : "PENDING");
                entry.put("errorMessage", state != null && state.getErrorMessage() != null ? state.getErrorMessage() : "");
                return entry;
            })
            .toList();
        return ApiResponse.ok(Map.of(
            "strategy", request.getDeployStrategy() != null ? request.getDeployStrategy().name() : "SAFE",
            "scope", request.getDeployScope() != null ? request.getDeployScope().name() : "ALL",
            "status", request.getStatus(),
            "targets", targets
        ));
    }

    @PostMapping("/execute-server/{requestId}/{serverId}")
    public ApiResponse<?> executeServer(
        @PathVariable Long requestId,
        @PathVariable Long serverId,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        DeployRequest request = deployRequestService.findById(requestId);
        assertExecutePermission(currentUser, request);
        dutySeparationService.assertCanOperate(request, currentUser);
        deployRequestService.assertProductionRollbackProvenance(request);
        deployRequestService.assertPlanUnchanged(request);
        if (request.getDeployStrategy() != DeployStrategy.MANUAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "서버별 실행은 MANUAL 배포에서만 사용할 수 있습니다.");
        }
        boolean targetExists = remoteDeployService.resolveTargets(request).stream()
            .anyMatch(server -> serverId.equals(server.getId()));
        if (!targetExists) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 배포 요청의 대상 서버가 아닙니다.");
        }
        DeployStatus ready = requiredReadyStatus(request);
        DeployStatus deploying = request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_DEPLOYING
            : DeployStatus.PROD_DEPLOYING;
        if (request.getStatus() != ready && request.getStatus() != deploying) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "배포 가능한 상태가 아닙니다. 현재 상태: " + request.getStatus()
            );
        }
        boolean startedHere = request.getStatus() == ready;
        if (startedHere) {
            if (!executionPolicyService.tryStart(request, ready, deploying)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 작업이 먼저 배포를 시작했습니다.");
            }
        }
        boolean acquired = false;
        try {
            manualServerExecutionService.acquire(requestId, serverId, currentUser.getId());
            acquired = true;
            deployRequestService.markExecuted(requestId, currentUser.getId());
            operatorExecutionService.executeSingleServerDeployment(
                requestId,
                serverId,
                currentUser.getName(),
                httpRequest.getRemoteAddr()
            );
        } catch (RuntimeException ex) {
            if (acquired) {
                manualServerExecutionService.fail(requestId, serverId, ex.getMessage());
            }
            if (startedHere) {
                DeployStatus failed = request.getEnvironment() == DeployEnvironment.TEST
                    ? DeployStatus.TEST_FAILED : DeployStatus.PROD_FAILED;
                deployRequestService.tryTransition(requestId, deploying, failed);
            }
            throw ex;
        }
        return ApiResponse.ok("서버 배포를 시작했습니다.", Map.of(
            "requestId", requestId, "serverId", serverId, "status", deploying));
    }

    /**
     * 이 배포 요청이 <b>서버마다</b> 어떻게 끝났는지 — 흐름도의 "어느 구간에서 깨졌나" 용.
     *
     * <p>단계 흐름도({@code deploy-progress})는 요청 전체가 성공인지 실패인지만 말한다.
     * 대상이 여러 대일 때 정작 알아야 할 것은 <b>어느 호기에서 멈췄는가</b>다 —
     * 롤링 배포는 실패한 서버에서 중단되므로, 그 지점이 곧 "여기까지는 새 버전, 그 뒤는 옛 버전"의 경계다.
     *
     * <p>{@code remote_command_log} 는 저장 시점에 시크릿이 마스킹돼 있다.
     * 여기서는 그중에서도 stdout/stderr 본문을 빼고 <b>결과만</b> 내보낸다 —
     * 흐름도에 필요한 것은 성공 여부지 로그 본문이 아니고, 본문은 이미 별도 패널이 담당한다.
     */
    @GetMapping("/logs/{requestId}/servers")
    public ApiResponse<?> serverOutcomes(
        @PathVariable Long requestId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        DeployRequest request = deployRequestService.findById(requestId, currentUser);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RemoteCommandLog log : remoteCommandLogRepository.findByDeployRequestId(requestId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attemptId", log.getId());
            row.put("serverId", log.getServerId());
            row.put("serverName", log.getServerName());
            row.put("success", log.isSuccess());
            row.put("exitCode", log.getExitCode());
            row.put("durationMs", log.getDurationMs());
            row.put("createdAt", log.getCreatedAt());
            rows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("environment", request.getEnvironment() != null ? request.getEnvironment().name() : null);
        body.put("status", request.getStatus());
        body.put("strategy", request.getDeployStrategy() != null ? request.getDeployStrategy().name() : "SAFE");
        body.put("targets", remoteDeployService.resolveTargets(request).stream().map(server -> Map.of(
            "serverId", server.getId(), "serverName", server.getName())).toList());
        body.put("servers", rows);
        return ApiResponse.ok(body);
    }

    /**
     * 한 서버의 최근 실행 기록 — 구조도의 [로그] 버튼.
     *
     * <p>서버는 여러 프로젝트에 걸칠 수 있으므로 <b>프로젝트 조회 권한으로 막는다</b>.
     * 그 프로젝트를 볼 수 있는 사람만, 그 프로젝트의 대상 서버 로그를 본다.
     * SUPER_ADMIN은 프로젝트 조회 권한을 기본으로 갖지만 서버 매핑과 로그의 프로젝트 범위는 동일하게 적용한다.
     */
    @GetMapping("/servers/{serverId}/logs")
    public ApiResponse<?> serverLogs(
        @PathVariable Long serverId,
        @RequestParam Long projectId,
        @RequestParam(defaultValue = "20") int limit,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        Project project = projectService.findByIdWithDetails(projectId, currentUser);
        boolean belongs = serversOf(project).stream().anyMatch(s -> serverId.equals(s.getId()));
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이 프로젝트의 배포 대상 서버가 아닙니다.");
        }

        int capped = Math.min(Math.max(limit, 1), 50);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RemoteCommandLog log : remoteCommandLogRepository.findRecentByProjectIdAndServerId(projectId, serverId, capped)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", log.getId());
            row.put("deployRequestId", log.getDeployRequestId());
            row.put("command", log.getCommandDisplay());
            row.put("exitCode", log.getExitCode());
            row.put("success", log.isSuccess());
            row.put("durationMs", log.getDurationMs());
            row.put("createdAt", log.getCreatedAt());
            row.put("stdout", log.getStdoutText());
            row.put("stderr", log.getStderrText());
            rows.add(row);
        }
        return ApiResponse.ok(rows);
    }

    /**
     * 컨테이너 실행 로그 — 화면의 [실시간 로그] 우측 패널이 주기적으로 부른다.
     *
     * <p>읽기만 하는 동작이라 <b>실행 권한이 아니라 조회 권한</b>으로 막는다.
     * 정지/기동과 달리 상태를 바꾸지 않으므로 같은 무게로 다룰 이유가 없다.
     */
    @GetMapping("/servers/{serverId}/container-log")
    public ApiResponse<?> containerLog(
        @PathVariable Long serverId,
        @RequestParam Long projectId,
        @RequestParam(defaultValue = "300") int lines,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        Project project = projectService.findByIdWithDetails(projectId, currentUser);
        Server server = serversOf(project).stream()
            .filter(s -> serverId.equals(s.getId()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "이 프로젝트의 배포 대상 서버가 아닙니다."));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverId", serverId);
        ArtifactType artifactType = remoteDeployService.artifactTypeOf(project, server);
        body.put("artifactType", artifactType.name());
        body.put("supported", artifactType == ArtifactType.CONTAINER);
        if (artifactType != ArtifactType.CONTAINER) {
            body.put("ok", false);
            body.put("text", "정적 파일·프록시에는 프로젝트 컨테이너 로그가 없습니다. 아래 배포 실행 기록을 확인하세요.");
            return ApiResponse.ok(body);
        }
        body.put("container", remoteDeployService.containerNameOf(project));
        try {
            RemoteCommandResult result = remoteDeployService.containerLogs(project, server, lines);
            boolean ok = result.exitCode() == 0;
            body.put("ok", ok);
            // SSH/로그인 셸의 잘못된 locale 경고가 실제 원인을 가리지 않도록 정리한다.
            // 원격 스크립트 진단은 stderr에, 구버전 에이전트 진단은 stdout에 올 수 있다.
            body.put("text", ok ? result.stdout()
                : firstNonBlank(withoutLocaleWarnings(result.stderr()), withoutLocaleWarnings(result.stdout()),
                    "로그를 가져오지 못했습니다."));
        } catch (Exception ex) {
            body.put("ok", false);
            body.put("text", "로그를 가져오지 못했습니다: " + shortMsg(ex));
        }
        return ApiResponse.ok(body);
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private String withoutLocaleWarnings(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        return output.lines()
            .filter(line -> !line.matches("^.*warning: setlocale: LC_ALL: cannot change locale \\(.*\\)$"))
            .collect(java.util.stream.Collectors.joining("\n"))
            .strip();
    }

    /**
     * 배포 파이프라인 전체 구조 — GitLab → Nexus → 배포시스템 → 대상 서버.
     *
     * <p>DB·설정만 읽어 즉시 응답한다. 연결 상태는 {@link #architectureHealth} 가 따로 확인한다 —
     * 한 번에 묶으면 <b>끊긴 구간이 있을 때 화면이 가장 늦게 뜬다</b>. 정작 급할 때 안 보이는 셈이다.
     */
    @GetMapping("/architecture/{projectId}")
    public ApiResponse<?> architecture(
        @PathVariable Long projectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        return ApiResponse.ok(architectureService.structure(projectId, currentUser));
    }

    /** 구간별 연결 확인 — 실제로 두드려 본다. 여러 곳을 동시에 확인해 느려지지 않게 한다. */
    @GetMapping("/architecture/{projectId}/health")
    public ApiResponse<?> architectureHealth(
        @PathVariable Long projectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        return ApiResponse.ok(architectureService.health(projectId, currentUser));
    }

    /**
     * 서버 구조도 — 그룹 → 호기 → 서버.
     *
     * <p><b>SSH 를 걸지 않는다.</b> DB 에 있는 것만 돌려주므로 즉시 응답한다.
     * 컨테이너가 도는지는 {@link #serviceStatus} 가 서버마다 SSH 를 걸어 확인하는데,
     * 8대면 10초가 넘는다. 그 둘을 한 번에 묶으면 구조도가 그때까지 안 뜬다 —
     * 화면은 구조를 먼저 그리고 상태는 나중에 채운다.
     *
     * <p>여기서 주는 {@code status} 는 <b>서버가 살아있나</b>(SSH 포트 TCP 체크 결과)이지
     * 서비스가 도는지가 아니다. 둘은 다른 축이고, 서버는 멀쩡한데 컨테이너만 죽은 상황이
     * 실제로 가장 흔하다. 화면에서 두 축을 따로 보여 줘야 한다.
     */
    @GetMapping("/topology/{projectId}")
    public ApiResponse<?> topology(
        @PathVariable Long projectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        Project project = projectService.findByIdWithDetails(projectId, currentUser);

        List<Map<String, Object>> groups = new ArrayList<>();
        if (project.getServerGroups() != null) {
            for (ServerGroup group : project.getServerGroups()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("groupId", group.getId());
                g.put("groupName", group.getName());
                g.put("environment", group.getEnvironment() != null ? group.getEnvironment().name() : null);
                g.put("rollingIntervalSec", group.getRollingIntervalSec());

                List<Map<String, Object>> nodes = new ArrayList<>();
                List<Server> members = group.getServers() == null ? List.of() : group.getServers();
                // 호기(orderIndex) 오름차순 — 화면의 1호기/2호기 순서가 곧 배포 순서다.
                members.stream()
                    .sorted(Comparator.comparingInt(Server::getOrderIndex)
                        .thenComparing(Server::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(s -> {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("serverId", s.getId());
                        n.put("serverName", s.getName());
                        n.put("ipAddress", s.getIpAddress());
                        n.put("serverType", s.getServerType() != null ? s.getServerType().name() : null);
                        n.put("environment", s.getEnvironment() != null ? s.getEnvironment().name() : null);
                        // 호기 번호. orderIndex 가 0(미지정)이면 화면에서 "-" 로 둔다.
                        n.put("unit", s.getOrderIndex());
                        // 서버 도달성(TCP). NORMAL / ERROR / UNKNOWN
                        n.put("reachable", s.getStatus() != null ? s.getStatus().name() : "UNKNOWN");
                        n.put("lastCheckedAt", s.getLastCheckedAt());
                        nodes.add(n);
                    });
                g.put("servers", nodes);
                groups.add(g);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", project.getId());
        body.put("projectName", project.getName());
        boolean hasContainer = serversOf(project).stream()
            .anyMatch(server -> remoteDeployService.artifactTypeOf(project, server) == ArtifactType.CONTAINER);
        body.put("container", hasContainer ? remoteDeployService.containerNameOf(project) : null);
        body.put("groups", groups);

        // 지금 이 프로젝트에 손대면 안 되는 상태인가 —
        //   진행 중인 요청이 있으면 화면이 경고를 띄우고 정지/기동 버튼을 잠근다.
        //   배포가 서버를 갈아 끼우는 중에 사람이 컨테이너를 내리면 그 서버만 어중간하게 남는다.
        deployRequestRepository.findLatestByProjectId(project.getId()).ifPresent(latest -> {
            DeployStatus st = latest.getStatus();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", latest.getId());
            r.put("reqNo", latest.getReqNo());
            r.put("status", st != null ? st.name() : null);
            r.put("statusLabel", st != null ? st.getLabel() : null);
            r.put("environment", latest.getEnvironment() != null ? latest.getEnvironment().name() : null);
            r.put("inFlight", st != null && st.isInFlight());
            r.put("createdAt", latest.getCreatedAt());
            body.put("latestRequest", r);
        });

        return ApiResponse.ok(body);
    }

    /**
     * 프로젝트가 배포되어 있는 서버들의 <b>현재 서비스 상태</b>.
     *
     * <p>버전을 바꾸지 않는 조회라 승인 절차가 없다. 각 서버에서 컨테이너가 도는지만 본다.
     *
     * <p>⚠ 서버마다 SSH 를 <b>직렬로</b> 건다. 대상이 많으면 그만큼 느리다 —
     * 화면은 이걸 기다리지 말고 구조도({@link #topology})를 먼저 그린 뒤 채워야 한다.
     */
    @GetMapping("/services/{projectId}")
    public ApiResponse<?> serviceStatus(
        @PathVariable Long projectId,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        Project project = projectService.findByIdWithDetails(projectId, currentUser);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Server server : serversOf(project)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", server.getId());
            row.put("serverName", server.getName());
            row.put("ipAddress", server.getIpAddress());
            row.put("environment", server.getEnvironment() != null ? server.getEnvironment().name() : null);
            ArtifactType artifactType = remoteDeployService.artifactTypeOf(project, server);
            boolean container = artifactType == ArtifactType.CONTAINER;
            row.put("artifactType", artifactType.name());
            row.put("controlSupported", container);
            row.put("logsSupported", container);
            row.put("serviceTarget", remoteDeployService.serviceTargetOf(project, server));
            row.put("container", container ? remoteDeployService.containerNameOf(project) : null);
            if (!container) {
                row.put("controlReason", "정적 파일·프록시는 기동·정지 대상이 아닙니다. 공용 Nginx 제어는 제공하지 않습니다.");
            }
            try {
                RemoteCommandResult result = remoteDeployService.controlService(project, server, "status");
                if (result.exitCode() != 0) {
                    throw new IllegalStateException(firstNonBlank(result.stderr(), result.stdout(), "상태 조회 실패"));
                }
                row.put("statusKnown", true);
                if (artifactType == ArtifactType.PROXY_RELOAD) {
                    applyProxyServiceStatus(row, result.stdout());
                    rows.add(row);
                    continue;
                }
                if (artifactType == ArtifactType.STATIC_BUNDLE) {
                    applyStaticServiceStatus(row, result.stdout());
                    rows.add(row);
                    continue;
                }
                // docker ps -a 출력의 STATUS 열만 본다. 헤더 다음 줄이 없으면 컨테이너가 아예 없는 것.
                String[] lines = result.stdout() == null ? new String[0] : result.stdout().split("\\r?\\n");
                String statusLine = lines.length > 1 ? lines[1].trim() : "";
                row.put("exists", !statusLine.isEmpty());
                row.put("running", statusLine.contains("Up "));
                row.put("detail", statusLine.isEmpty() ? "아직 배포되지 않았습니다" : statusLine);
            } catch (Exception ex) {
                row.put("statusKnown", false);
                row.put("exists", null);
                row.put("running", null);
                row.put("statusLabel", "확인 불가");
                row.put("detail", "상태를 확인하지 못했습니다: " + shortMsg(ex));
            }
            rows.add(row);
        }
        return ApiResponse.ok(rows);
    }

    private void applyProxyServiceStatus(Map<String, Object> row, String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0 && values.putIfAbsent(line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw new IllegalStateException("프록시 상태 응답이 올바르지 않습니다.");
            }
        }
        if (!"PROXY_RELOAD".equals(values.get("ARTIFACT_TYPE"))
            || !List.of("true", "false").contains(values.getOrDefault("NGINX_ACTIVE", ""))
            || !List.of("true", "false").contains(values.getOrDefault("HTTP_HEALTHY", ""))) {
            throw new IllegalStateException("프록시 상태 응답을 확인할 수 없습니다. WEB 배포 에이전트를 갱신하세요.");
        }
        boolean nginx = "true".equals(values.get("NGINX_ACTIVE"));
        boolean healthy = "true".equals(values.get("HTTP_HEALTHY"));
        row.put("exists", true);
        row.put("running", nginx && healthy);
        row.put("nginxRunning", nginx);
        row.put("httpHealthy", healthy);
        row.put("statusLabel", !nginx ? "Nginx 정지" : healthy ? "Nginx 실행 · HTTP 정상" : "Nginx 실행 · HTTP 실패");
        row.put("detail", "Nginx " + (nginx ? "실행 중" : "정지됨")
            + " · 설정된 HTTP 헬스체크 " + (healthy ? "정상" : "실패"));
    }

    private void applyStaticServiceStatus(Map<String, Object> row, String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        if (!"STATIC_BUNDLE".equals(values.get("ARTIFACT_TYPE"))
                || !List.of("true", "false").contains(values.getOrDefault("RELEASE_EXISTS", ""))) {
            throw new IllegalStateException("정적 상태 응답을 확인할 수 없습니다. WEB 배포 에이전트를 갱신하세요.");
        }
        boolean exists = "true".equals(values.get("RELEASE_EXISTS"));
        String nginx = values.getOrDefault("NGINX_ACTIVE", "unknown");
        row.put("exists", exists);
        row.put("running", exists && "true".equals(nginx));
        row.put("currentRelease", values.get("CURRENT_RELEASE"));
        row.put("statusLabel", !exists ? "정적 릴리스 없음"
            : "false".equals(nginx) ? "릴리스 있음 · Nginx 정지" : "릴리스 확인");
        row.put("detail", (exists ? "현재 릴리스: " + values.getOrDefault("CURRENT_RELEASE", "-") : "WebRoot/current 릴리스가 없습니다")
            + " · Nginx " + ("true".equals(nginx) ? "실행 중" : "false".equals(nginx) ? "정지됨" : "확인 불가")
            + " · 실제 HTTP 응답은 별도 확인이 필요합니다.");
    }

    /**
     * 이미 배포된 서비스를 즉시 정지/기동한다 — <b>재배포가 아니라</b> 있는 컨테이너를 껐다 켜는 것이라
     * 버전이 바뀌지 않는다. 그래서 배포 승인 절차 대신 그 환경의 실행 권한만 확인한다.
     */
    @PostMapping("/services/{projectId}/{serverId}/{action}")
    public ApiResponse<?> controlService(
        @PathVariable Long projectId,
        @PathVariable Long serverId,
        @PathVariable String action,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        if (!List.of("start", "stop", "restart").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 동작입니다: " + action);
        }

        Project project = projectService.findByIdWithDetails(projectId, currentUser);
        Server server = serversOf(project).stream()
            .filter(s -> serverId.equals(s.getId()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "이 프로젝트의 배포 대상 서버가 아닙니다."));

        // 운영 서비스를 내리는 것은 운영 배포와 같은 무게다 — 같은 실행 권한을 요구한다.
        DeployEnvironment env = server.getEnvironment() == ServerEnvironment.PROD
            ? DeployEnvironment.PROD
            : DeployEnvironment.TEST;
        projectPermissionService.assertPermission(
            currentUser,
            projectId,
            env == DeployEnvironment.TEST
                ? ProjectPermissionType.TEST_EXECUTE
                : ProjectPermissionType.PROD_EXECUTE,
            env
        );

        if (remoteDeployService.artifactTypeOf(project, server) != ArtifactType.CONTAINER) {
            return ApiResponse.error("SERVICE_CONTROL_UNSUPPORTED",
                "정적 파일·프록시는 프로젝트 컨테이너의 기동·정지·재기동 대상이 아닙니다. 공용 Nginx는 이 작업으로 제어하지 않습니다.");
        }

        return executionPolicyService.withExecutionGuard(() -> {
            List<DeployStatus> activeStatuses = env == DeployEnvironment.TEST
                ? List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING)
                : List.of(DeployStatus.PROD_DEPLOYING);
            boolean targetIsDeploying = deployRequestRepository
                .findByProjectIdAndEnvironmentAndStatusInWithProject(projectId, env, activeStatuses)
                .stream()
                .anyMatch(request -> {
                    // Repository result includes the project row but not its server-group collection.
                    // Resolve against the already-authorized, fully hydrated project used by this API.
                    request.setProject(project);
                    return remoteDeployService.resolveTargets(request).stream()
                        .anyMatch(target -> serverId.equals(target.getId()));
                });
            if (targetIsDeploying) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "같은 프로젝트·환경·서버에서 배포가 진행 중입니다. 배포가 끝난 뒤 서비스를 제어해 주세요.");
            }

            String label = switch (action) {
            case "start" -> "서비스 기동";
            case "stop" -> "서비스 정지";
            default -> "서비스 재기동";
            };

            RemoteCommandResult result;
            try {
                result = remoteDeployService.controlService(project, server, action);
            } catch (Exception ex) {
                auditLogService.record(currentUser.getName(), env.name() + " " + label, "Service",
                    String.valueOf(serverId), project.getName() + " @ " + server.getName(),
                    AuditResult.FAIL, httpRequest.getRemoteAddr());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    label + "에 실패했습니다: " + shortMsg(ex));
            }

            boolean ok = result.exitCode() == 0;
            auditLogService.record(currentUser.getName(), env.name() + " " + label, "Service",
                String.valueOf(serverId), project.getName() + " @ " + server.getName(),
                ok ? AuditResult.SUCCESS : AuditResult.FAIL, httpRequest.getRemoteAddr());

            if (!ok) {
                String err = result.stderr() == null || result.stderr().isBlank()
                    ? result.stdout()
                    : result.stderr();
                // 구버전 에이전트는 start/stop/restart 를 모른다(2026-07-29 에 추가된 action).
                //   스크립트가 "Unsupported action: stop" 을 뱉고 끝나는데, 그대로 보여 주면
                //   운영자는 자기가 뭘 잘못했는지 찾다가 시간을 버린다. 해야 할 일을 알려 준다.
                String combined = (result.stdout() == null ? "" : result.stdout())
                    + (result.stderr() == null ? "" : result.stderr());
                if (combined.contains("Unsupported action")) {
                    return ApiResponse.error("AGENT_TOO_OLD",
                        "이 서버의 배포 에이전트가 구버전이라 " + label + objectParticle(label) + " 지원하지 않습니다. "
                        + "대상 서버에서 배포 에이전트를 다시 설치해 주세요 "
                        + "(installer/deploy-agent/install-deploy-agent.sh). "
                        + "조회는 되지만 정지/기동만 막히는 것이 이 증상입니다.");
                }
                return ApiResponse.error("SERVICE_CONTROL_FAILED",
                    label + "에 실패했습니다. " + tail(err, 300));
            }
            return ApiResponse.ok(label + "했습니다.", Map.of(
                "serverId", serverId,
                "action", action
            ));
        });
    }

    /**
     * 목적격 조사 — 받침이 있으면 "을", 없으면 "를".
     *
     * <p>동작 이름을 문장에 끼워 넣는데 "서비스 정지을 지원하지 않습니다" 처럼 나오면
     * 기계가 쓴 티가 난다. 한글 음절의 받침은 유니코드 값으로 계산할 수 있다 —
     * 가(0xAC00) 부터 28개 단위로 종성이 돌아가므로 나머지가 0이면 받침이 없다.
     */
    private String objectParticle(String word) {
        if (word == null || word.isBlank()) {
            return "을";
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "을"; // 한글이 아니면 판단할 수 없다 — 흔한 쪽으로 둔다
        }
        return (last - 0xAC00) % 28 == 0 ? "를" : "을";
    }

    /** 프로젝트에 매핑된 서버 그룹의 서버들 — 중복 없이, 그룹·배포 순서대로. */
    private List<Server> serversOf(Project project) {
        List<Server> servers = new ArrayList<>();
        if (project.getServerGroups() == null) {
            return servers;
        }
        for (ServerGroup group : project.getServerGroups()) {
            if (group.getServers() == null) {
                continue;
            }
            for (Server s : group.getServers()) {
                if (servers.stream().noneMatch(existing -> existing.getId().equals(s.getId()))) {
                    servers.add(s);
                }
            }
        }
        return servers;
    }

    private String shortMsg(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    private String tail(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > max ? "..." + trimmed.substring(trimmed.length() - max) : trimmed;
    }

    private DeployStatus requiredReadyStatus(DeployRequest request) {
        return request.getEnvironment() == DeployEnvironment.TEST
            ? DeployStatus.TEST_REQUESTED
            : DeployStatus.APPROVAL_COMPLETED;
    }

    private void assertExecutePermission(SecurityUserDetails currentUser, DeployRequest request) {
        projectPermissionService.assertPermission(
            currentUser,
            request.getProject().getId(),
            request.getEnvironment() == DeployEnvironment.TEST
                ? ProjectPermissionType.TEST_EXECUTE
                : ProjectPermissionType.PROD_EXECUTE,
            request.getEnvironment()
        );
    }

    private DeployStatus startStatus(DeployRequest request) {
        return request.getEnvironment() == DeployEnvironment.TEST
            ? (deployRequestService.isRollbackRequest(request) || hasReusableImage(request)
                ? DeployStatus.TEST_DEPLOYING
                : DeployStatus.TEST_BUILDING)
            : DeployStatus.PROD_DEPLOYING;
    }

    private boolean hasReusableImage(DeployRequest request) {
        return request.getImageTag() != null && !request.getImageTag().isBlank();
    }

    private boolean isTerminalStatus(DeployStatus status) {
        return status == DeployStatus.TEST_DEPLOYED
            || status == DeployStatus.TEST_FAILED
            || status == DeployStatus.COMPLETED
            || status == DeployStatus.PROD_FAILED
            || status == DeployStatus.ROLLED_BACK
            || status == DeployStatus.CANCELLED;
    }
}
