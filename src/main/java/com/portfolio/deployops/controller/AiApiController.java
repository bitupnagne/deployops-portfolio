package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.service.RemoteCommandLogService;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditLog;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.RemoteCommandLog;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.DashboardSummaryDto;
import com.portfolio.deployops.repository.AssistantChatRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.service.AiChatService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DashboardService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.LogReadService;
import com.portfolio.deployops.service.ProjectImageHistoryService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.ServerService;
import com.portfolio.deployops.service.UserService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assistant AI 어시스턴트의 실제 동작 엔드포인트.
 *
 * <p>대상 요청은 {@link DeployRequestService#findById(Long, SecurityUserDetails)} 로 조회해 사용자의 열람 권한을 그대로 적용한다.
 * 각 기능은 전역 활성화(ai.enabled) 와 개별 기능 토글(feat.*) 을 모두 확인한 뒤 실제 모델을 호출한다.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiApiController {

    private final RemoteCommandLogService remoteCommandLogService;

    private final AiChatService ai;
    private final DeployRequestService deployRequestService;
    private final RemoteCommandLogRepository remoteCommandLogRepository;
    private final UserService userService;
    private final GitlabService gitlabService;
    private final DashboardService dashboardService;
    private final ServerService serverService;
    private final ProjectService projectService;
    private final ProjectImageHistoryService projectImageHistoryService;
    private final AuditLogService auditLogService;
    private final LogReadService logReadService;
    private final AssistantChatRepository assistantChatRepository;
    private final ProjectPermissionService projectPermissionService;

    private static final String KOREAN_ONLY =
        "\n\n[언어 규칙] 모든 답변은 반드시 한국어로만 작성하세요. 중국어·영어 등 다른 언어를 섞지 마세요.";
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    // 사용자별 요청 속도 제한(슬라이딩 윈도우) + 대시보드 브리핑 캐시(모델 부하 절감)
    private static final int RATE_MAX_PER_MIN = 20;
    private static final long INSIGHT_TTL_MS = 5 * 60 * 1000L;
    private final java.util.Map<String, java.util.Deque<Long>> rateWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, long[]> insightCacheAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> insightCacheText = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/risk/{id}")
    public ApiResponse<?> analyzeRisk(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        ApiResponse<?> guard = guard("feat.risk", true);
        if (guard != null) {
            return guard;
        }
        DeployRequest request = deployRequestService.findById(id, currentUser);
        String prompt = requestContext(request)
            + "\n\n위 배포 요청의 위험도를 '낮음', '보통', '높음' 중 하나로 판정하세요."
            + " 운영(PROD) 환경, 전체 범위 배포, 롤백 여부, 사유의 모호함을 종합적으로 고려하세요."
            + " 형식을 지키세요:\n위험도: <낮음|보통|높음>\n근거: (3줄 이내, 한국어)";
        return run(
            currentUser,
            "당신은 금융 결제 시스템의 배포 위험도를 보수적으로 평가하는 DevOps 어시스턴트입니다.",
            prompt
        );
    }

    @PostMapping("/recommend-approver/{id}")
    public ApiResponse<?> recommendApprover(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        ApiResponse<?> guard = guard("feat.approver", true);
        if (guard != null) {
            return guard;
        }
        DeployRequest request = deployRequestService.findById(id, currentUser);
        List<User> approvers = userService.findApprovers();
        if (approvers.isEmpty()) {
            return ApiResponse.error("NO_APPROVER", "등록된 승인자가 없습니다.");
        }
        String candidates = approvers.stream()
            .map(u -> "- " + u.getName() + " (" + u.getRole().getLabel() + ")")
            .collect(Collectors.joining("\n"));
        String prompt = requestContext(request)
            + "\n\n승인자 후보:\n" + candidates
            + "\n\n위 배포 내용에 가장 적합한 승인자 1명을 후보 중에서 골라 추천하고, 이유를 2줄 이내로 한국어로 설명하세요."
            + " 형식:\n추천 승인자: <이름>\n이유: ...";
        return run(
            currentUser,
            "당신은 배포 요청에 알맞은 승인자를 배정하는 DevOps 어시스턴트입니다. 반드시 제시된 후보 중에서만 고르세요.",
            prompt
        );
    }

    @PostMapping("/summarize/{id}")
    public ApiResponse<?> summarizeCommits(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        ApiResponse<?> guard = guard("feat.summary", true);
        if (guard != null) {
            return guard;
        }
        DeployRequest request = deployRequestService.findById(id, currentUser);
        String commitText = fetchCommitText(request, currentUser);
        String prompt = requestContext(request)
            + "\n\n커밋/변경 정보:\n" + (commitText.isBlank() ? "(커밋 메시지를 가져오지 못했습니다. 사유와 태그 정보로 요약하세요.)" : commitText)
            + "\n\n승인자가 변경 내용을 빠르게 파악할 수 있도록 핵심을 3~5개 불릿(- )으로 한국어 요약하세요.";
        return run(
            currentUser,
            "당신은 배포 변경 내용을 간결하게 요약하는 DevOps 어시스턴트입니다.",
            prompt
        );
    }

    @PostMapping("/diagnose/{id}")
    public ApiResponse<?> diagnoseFailure(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        ApiResponse<?> guard = guard("feat.diag", true);
        if (guard != null) {
            return guard;
        }
        DeployRequest request = deployRequestService.findById(id, currentUser);
        List<RemoteCommandLog> logs = remoteCommandLogRepository.findByDeployRequestId(id);
        String failureLog = buildFailureLog(logs);
        if (failureLog.isBlank()) {
            return ApiResponse.error("NO_LOG", "진단할 배포 실행 로그가 없습니다.");
        }
        String prompt = requestContext(request)
            + "\n\n실패 관련 실행 로그(민감정보 마스킹됨):\n" + failureLog
            + "\n\n위 로그를 근거로 배포 실패의 가장 가능성 높은 원인과 조치 방향을 제시하세요."
            + " 형식:\n원인: ...\n조치: (단계별, 한국어)";
        return run(
            currentUser,
            "당신은 배포 실패 로그를 분석해 근본 원인과 조치를 제시하는 DevOps SRE 어시스턴트입니다.",
            prompt
        );
    }

    /** 실패한 배포의 자동 진단 + 롤백 추천(상세 화면에서 실패 상태면 자동 호출). */
    @PostMapping("/failure-report/{id}")
    public ApiResponse<?> failureReport(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        ApiResponse<?> guard = guard("feat.diag", true);
        if (guard != null) {
            return guard;
        }
        DeployRequest request = deployRequestService.findById(id, currentUser);
        String failureLog = buildFailureLog(remoteCommandLogRepository.findByDeployRequestId(id));
        String rollback = projectImageHistoryService.findLatestRollbackCandidate(request)
            .map(h -> "직전 성공 이미지: " + nullSafe(h.getImageTag())
                + (h.getCreatedAt() != null ? " (" + h.getCreatedAt().format(AUDIT_TIME) + ")" : ""))
            .orElse("직전 성공 이미지 정보 없음");
        String prompt = requestContext(request)
            + "\n\n" + rollback
            + (failureLog.isBlank()
                ? "\n\n실행 로그가 없어 정밀 원인 파악은 어렵습니다. 위 정보로 점검 항목과 롤백 필요 여부를 제시하세요."
                : "\n\n실패 관련 실행 로그(민감정보 마스킹됨):\n" + failureLog)
            + "\n\n다음 형식으로 한국어로 답하세요:\n원인: ...\n조치: (단계별)\n롤백 권고: (롤백 필요 여부, 필요하면 위 직전 성공 이미지로 롤백을 권장하는지)";
        return run(
            currentUser,
            "당신은 배포 실패를 신속히 진단하고 롤백까지 판단하는 DevOps SRE 어시스턴트입니다.",
            prompt
        );
    }

    /** PROD 승인 대기 요청에 대한 승인자용 자동 브리핑(위험/변경/과거이력/권고). */
    @PostMapping("/approver-brief/{id}")
    public ApiResponse<?> approverBrief(@PathVariable Long id, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        ApiResponse<?> guard = guard("feat.risk", true);
        if (guard != null) {
            return guard;
        }
        DeployRequest request = deployRequestService.findById(id, currentUser);
        String commits = fetchCommitText(request, currentUser);
        String prompt = requestContext(request)
            + "\n\n변경/커밋 정보:\n" + (commits.isBlank() ? "(커밋 정보를 가져오지 못함)" : commits)
            + "\n\n이 프로젝트 최근 배포 이력:\n" + projectDeployHistory(request, currentUser)
            + "\n\n위 정보를 근거로 승인자에게 브리핑하세요. 형식(한국어, 간결):\n"
            + "핵심 변경: (1~2줄)\n위험 요인: (있으면, 없으면 '특이사항 없음')\n"
            + "과거 이력: (실패/롤백이 잦은지)\n권고: (승인 권장 | 신중 검토 | 보류 권장 중 하나 + 짧은 이유)";
        return run(
            currentUser,
            "당신은 PROD 배포 승인자를 돕는 신중한 DevOps 어시스턴트입니다. 승인 판단에 꼭 필요한 핵심만 간결히 제공하세요.",
            prompt
        );
    }

    @GetMapping("/dashboard-insight")
    public ApiResponse<?> dashboardInsight(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        if (!ai.isEnabled()) {
            return ApiResponse.error("AI_DISABLED", "AI 기능이 비활성화되어 있습니다.");
        }
        String cacheKey = currentUser != null ? currentUser.getUsername() : "anon";
        long[] cachedAt = insightCacheAt.get(cacheKey);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt[0] < INSIGHT_TTL_MS) {
            String cached = insightCacheText.get(cacheKey);
            if (cached != null) {
                return ApiResponse.ok(cached);
            }
        }
        DashboardSummaryDto summary = dashboardService.getSummary(currentUser);
        String prompt = "오늘 배포 현황:\n"
            + "- 오늘 배포 요청: " + summary.getTodayRequestCount() + "건\n"
            + "- 승인 대기: " + summary.getPendingApprovalCount() + "건\n"
            + "- 배포 진행 중: " + summary.getDeployingCount() + "건\n"
            + "- 오늘 실패: " + summary.getTodayFailureCount() + "건\n\n"
            + "위 현황을 한국어 한두 문장으로 브리핑하고, 담당자가 가장 먼저 처리해야 할 일을 한 가지 제안하세요."
            + " 존댓말로, 60자 내외 두 문장 이하로 답하세요.";
        ApiResponse<?> resp = run(
            currentUser,
            "당신은 배포 담당자를 돕는 친근한 AI 어시스턴트 'Assistant' 입니다.",
            prompt
        );
        if (resp.isSuccess() && resp.getData() != null) {
            insightCacheAt.put(cacheKey, new long[]{ System.currentTimeMillis() });
            insightCacheText.put(cacheKey, String.valueOf(resp.getData()));
        }
        return resp;
    }

    /**
     * Assistant 챗봇. 사용자 질문을 현재 시스템의 <b>읽기 전용</b> 데이터(서버/프로젝트/최근 배포)와 사용 안내에 근거해 답한다.
     * 모델에는 조회한 데이터만 전달하며, 어떤 쓰기/변경도 수행하지 않는다(read-only).
     */
    @PostMapping("/chat")
    public ApiResponse<?> chat(@RequestBody Map<String, String> body, @AuthenticationPrincipal SecurityUserDetails currentUser) {
        if (!ai.isEnabled()) {
            return ApiResponse.error("AI_DISABLED", "AI 기능이 비활성화되어 있습니다.");
        }
        String message = body.getOrDefault("message", "").trim();
        if (message.isEmpty()) {
            return ApiResponse.error("EMPTY", "질문을 입력하세요.");
        }
        String history = body.getOrDefault("history", "");
        String system = "당신은 사내 배포 시스템(DeployOps)의 도우미 챗봇 'Assistant' 입니다."
            + " 아래 [시스템 데이터]와 [사용 안내]만 근거로 사용자 질문에 간결하고 정확하게 답하세요."
            + " 데이터에 없는 내용은 모른다고 답하고 사실을 지어내지 마세요."
            + " 당신은 읽기 전용이라 시스템을 변경할 수 없으며, 변경 요청에는 화면에서 처리하는 방법만 안내하세요.\n\n"
            + buildChatContext(currentUser)
            + logSection(message, currentUser);
        String user = (history.isBlank() ? "" : "이전 대화:\n" + history + "\n\n") + "질문: " + message;
        ApiResponse<?> resp = run(currentUser, system, user);
        saveChat(currentUser, message, resp);
        return resp;
    }

    /** 사용자별 챗봇 대화 이력(최근 40건, 시간 오름차순). */
    @GetMapping("/chat/history")
    public ApiResponse<?> chatHistory(@AuthenticationPrincipal SecurityUserDetails currentUser) {
        if (currentUser == null) {
            return ApiResponse.ok(java.util.List.of());
        }
        return ApiResponse.ok(assistantChatRepository.findRecent(currentUser.getUsername(), 40));
    }

    private void saveChat(SecurityUserDetails currentUser, String message, ApiResponse<?> resp) {
        if (currentUser == null || resp == null || !resp.isSuccess() || resp.getData() == null) {
            return;
        }
        try {
            assistantChatRepository.insert(currentUser.getUsername(), "user", message);
            assistantChatRepository.insert(currentUser.getUsername(), "bot", String.valueOf(resp.getData()));
        } catch (Exception ignored) {
            // 대화 저장 실패는 응답에 영향을 주지 않는다
        }
    }

    /**
     * 사용자가 로그 분석을 요청하면(키워드 '로그') 화이트리스트에 등록된 해당 프로젝트 로그를 tail·마스킹해 컨텍스트에 덧붙인다.
     * 프로젝트를 특정하지 못하면 되묻도록 안내만 넣는다. (경로는 관리자 설정값만 사용 — 임의 경로 접근 불가)
     */
    private String logSection(String message, SecurityUserDetails currentUser) {
        if (message == null || !message.contains("로그")) {
            return "";
        }
        if (!ai.isFeatureEnabled("feat.logread", true)) {
            return "";
        }
        List<String> projects = logReadService.projectsWithLogs(currentUser);
        if (projects.isEmpty()) {
            return "";
        }
        String matched = projects.stream()
            .filter(p -> message.toLowerCase().contains(p.toLowerCase()))
            .findFirst()
            .orElse(null);
        if (matched == null) {
            return "\n\n[로그 안내] 로그 분석이 가능한 프로젝트: " + String.join(", ", projects)
                + ". 사용자가 프로젝트를 명시하지 않았으니 어느 프로젝트 로그인지 되물어보세요.";
        }
        String excerpt = logReadService.readForProject(matched, currentUser);
        if (excerpt.isBlank()) {
            return "";
        }
        return "\n\n[" + matched + " 로그 발췌 — 최근 기록, 민감정보 마스킹됨]\n" + excerpt
            + "\n\n위 로그를 분석해 오류/이상 징후, 추정 원인, 조치 방향을 한국어로 요약해 답하세요.";
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────

    private static final String CHAT_HOWTO = """
        [사용 안내]
        - 서버 등록: 좌측 메뉴 '서버' → '서버 추가'에서 이름/IP/환경(TEST·PROD)/그룹을 입력하고 서버 에이전트의 등록과 승인을 진행합니다.
        - 서버 그룹: '서버 그룹' 메뉴에서 배포 대상 묶음을 관리합니다.
        - 프로젝트 등록: '프로젝트' → '프로젝트 추가'에서 GitLab 저장소/기본 브랜치/이미지 저장소 등을 설정합니다.
        - 배포 요청: 대시보드의 '새 배포 요청'에서 프로젝트·환경·이미지 태그(빌드)를 선택해 생성합니다. PROD는 승인 후 실행됩니다.
        - 배포 실행/확인: 요청 상세 화면에서 실행 권한자가 실행하고 로그를 확인하며, 실패 시 AI 원인 진단을 사용할 수 있습니다.
        - 상태 흐름: 요청 → (PROD는 승인) → 배포 실행 → 완료. 이력은 '배포 요청' 목록에서 확인합니다.
        - 로그 분석: "○○ 프로젝트 로그 분석해줘"처럼 물으면 설정된 최근 로그를 읽어 분석해 드립니다(관리자가 허용한 프로젝트만).
        """;

    /** 챗봇 근거용 읽기 전용 컨텍스트(서버/프로젝트/최근 배포 + 사용 안내)를 사용자 가시 범위로 구성. */
    private String buildChatContext(SecurityUserDetails currentUser) {
        StringBuilder sb = new StringBuilder("[시스템 데이터]\n");

        // 서버 목록(+IP)은 서버 관리 권한(OPERATOR/SUPER_ADMIN)이 있는 사용자에게만 노출
        if (isOperatorOrAdmin(currentUser)) {
            List<Server> servers = serverService.findAll();
            sb.append("● 서버 (").append(servers.size()).append("대):\n");
            for (Server s : servers) {
                sb.append("- ").append(nullSafe(s.getName()))
                    .append(" | 환경=").append(s.getEnvironment() != null ? s.getEnvironment().name() : "-")
                    .append(" | IP=").append(nullSafe(s.getIpAddress()))
                    .append(" | 상태=").append(s.getStatus() != null ? s.getStatus().name() : "-")
                    .append(" | 그룹=").append(s.getServerGroup() != null ? nullSafe(s.getServerGroup().getName()) : "-")
                    .append('\n');
            }
        } else {
            sb.append("● 서버: (이 사용자는 서버 목록 열람 권한이 없습니다)\n");
        }

        List<Project> projects = projectService.findVisibleAll(currentUser);
        sb.append("● 프로젝트 (").append(projects.size()).append("개):\n");
        for (Project p : projects) {
            sb.append("- ").append(nullSafe(p.getName()))
                .append(" | 기본브랜치=").append(nullSafe(p.getDefaultBranch()))
                .append(" | 이미지저장소=").append(nullSafe(p.getImageRepository()))
                .append(" | 활성=").append(p.isActive() ? "예" : "아니오");
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                sb.append(" | 설명=").append(p.getDescription());
            }
            sb.append('\n');
        }

        List<DeployRequest> recent = dashboardService.getRecentDeployments(currentUser);
        sb.append("● 최근 배포/빌드 (최신순):\n");
        for (DeployRequest r : recent) {
            sb.append("- ").append(nullSafe(r.getReqNo()))
                .append(" | 프로젝트=").append(r.getProject() != null ? nullSafe(r.getProject().getName()) : "-")
                .append(" | 환경=").append(r.getEnvironment() != null ? r.getEnvironment().name() : "-")
                .append(" | 태그/브랜치=").append(nullSafe(r.getImageTag() != null ? r.getImageTag() : r.getBranch()))
                .append(" | 상태=").append(r.getStatus() != null ? r.getStatus().name() : "-")
                .append('\n');
        }

        List<DeployRequest> pending = dashboardService.getPendingApprovals(currentUser);
        sb.append("● 승인 대기 (").append(pending.size()).append("건):\n");
        for (DeployRequest r : pending) {
            sb.append("- ").append(nullSafe(r.getReqNo()))
                .append(" | 프로젝트=").append(r.getProject() != null ? nullSafe(r.getProject().getName()) : "-")
                .append(" | 환경=").append(r.getEnvironment() != null ? r.getEnvironment().name() : "-")
                .append(" | 요청자=").append(r.getRequestedBy() != null ? nullSafe(r.getRequestedBy().getName()) : "-")
                .append('\n');
        }

        // 감사 로그(누가 무엇을 했는지)는 SUPER_ADMIN 에게만 노출
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            sb.append("● 최근 활동/감사 로그 (최근 60일, 최신 일부):\n");
            for (AuditLog a : auditLogService.findWithFilter(null,
                    LocalDateTime.now().minusDays(60), LocalDateTime.now(), PageRequest.of(0, 12)).getContent()) {
                sb.append("- ").append(a.getCreatedAt() != null ? a.getCreatedAt().format(AUDIT_TIME) : "-")
                    .append(" | ").append(nullSafe(a.getActor()))
                    .append(" | ").append(nullSafe(a.getAction()))
                    .append(a.getTargetType() != null ? " " + a.getTargetType() : "")
                    .append(a.getTargetDesc() != null && !a.getTargetDesc().isBlank() ? "(" + a.getTargetDesc() + ")" : "")
                    .append(" | ").append(a.getResult() != null ? a.getResult().name() : "-")
                    .append('\n');
            }
        }

        // 데이터가 길면 앞부분만 남기고 잘라내되, [사용 안내]는 항상 보존
        String data = sb.length() > 8000 ? sb.substring(0, 8000) + "\n...(데이터 일부 생략)\n" : sb.toString();
        return data + "\n" + CHAT_HOWTO;
    }

    /** 전역/기능 토글 확인. 통과면 null, 막히면 오류 응답을 반환한다. */
    private ApiResponse<?> guard(String featureKey, boolean defaultOn) {
        if (!ai.isEnabled()) {
            return ApiResponse.error("AI_DISABLED", "AI 기능이 비활성화되어 있습니다. AI 설정에서 활성화하세요.");
        }
        if (!ai.isFeatureEnabled(featureKey, defaultOn)) {
            return ApiResponse.error("FEATURE_DISABLED", "이 AI 기능은 꺼져 있습니다. AI 설정에서 켤 수 있습니다.");
        }
        return null;
    }

    private ApiResponse<?> run(SecurityUserDetails currentUser, String systemPrompt, String userPrompt) {
        ApiResponse<?> limited = rateLimit(currentUser);
        if (limited != null) {
            return limited;
        }
        return run(currentUser, systemPrompt, userPrompt, 0);
    }

    /**
     * @param minTokens 이 답변에 최소한 보장할 출력 토큰. 0 이면 저장된 설정 그대로.
     *                  기본값 2048 은 한국어로 여러 절을 쓰는 분석에는 모자라 늘 마지막이 잘렸다.
     */
    private ApiResponse<?> run(SecurityUserDetails currentUser, String systemPrompt,
                               String userPrompt, int minTokens) {
        ApiResponse<?> limited = rateLimit(currentUser);
        if (limited != null) {
            return limited;
        }
        try {
            AiChatService.AiAnswer answer = minTokens > 0
                ? ai.answerLong(systemPrompt + KOREAN_ONLY, userPrompt, minTokens)
                : ai.answer(systemPrompt + KOREAN_ONLY, userPrompt);
            return ApiResponse.okAi(answer.text(), answer.truncated());
        } catch (AiChatService.AiException e) {
            return ApiResponse.error("AI_ERROR", e.getMessage());
        }
    }

    /** 사용자별 분당 요청 수 제한. 초과 시 오류 응답, 통과 시 null. */
    private ApiResponse<?> rateLimit(SecurityUserDetails currentUser) {
        String key = currentUser != null ? currentUser.getUsername() : "anon";
        java.util.Deque<Long> window = rateWindows.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= RATE_MAX_PER_MIN) {
                return ApiResponse.error("RATE_LIMITED", "AI 요청이 많습니다. 잠시 후 다시 시도하세요.");
            }
            window.addLast(now);
        }
        return null;
    }

    private String requestContext(DeployRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("배포 요청 정보:\n");
        sb.append("- 요청 번호: ").append(nullSafe(request.getReqNo())).append('\n');
        sb.append("- 프로젝트: ").append(request.getProject() != null ? nullSafe(request.getProject().getName()) : "-").append('\n');
        sb.append("- 환경: ").append(request.getEnvironment() != null ? request.getEnvironment().name() : "-").append('\n');
        sb.append("- 배포 범위: ").append(request.getDeployScope() != null ? request.getDeployScope().name() : "-").append('\n');
        sb.append("- 배포 전략: ").append(request.getDeployStrategy() != null ? request.getDeployStrategy().name() : "-").append('\n');
        sb.append("- 브랜치: ").append(nullSafe(request.getBranch())).append('\n');
        sb.append("- 이미지 태그: ").append(nullSafe(request.getImageTag())).append('\n');
        sb.append("- 현재 상태: ").append(request.getStatus() != null ? request.getStatus().name() : "-").append('\n');
        sb.append("- 요청자: ").append(request.getRequestedBy() != null ? nullSafe(request.getRequestedBy().getName()) : "-").append('\n');
        sb.append("- 사유: ").append(nullSafe(request.getReason()));
        return sb.toString();
    }

    /** 실패(또는 마지막) 실행 로그를 진단용 텍스트로 요약. stderr 우선, 각 로그 tail 위주. */
    private String buildFailureLog(List<RemoteCommandLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return "";
        }
        List<RemoteCommandLog> failed = logs.stream().filter(l -> !l.isSuccess()).collect(Collectors.toList());
        List<RemoteCommandLog> target = failed.isEmpty()
            ? logs.subList(Math.max(0, logs.size() - 3), logs.size())
            : failed;

        StringBuilder sb = new StringBuilder();
        for (RemoteCommandLog log : target) {
            sb.append("[서버 ").append(nullSafe(log.getServerName()))
                .append(" · exit=").append(log.getExitCode()).append("]\n");
            sb.append("$ ").append(tail(log.getCommandDisplay(), 500)).append('\n');
            if (log.getStderrText() != null && !log.getStderrText().isBlank()) {
                sb.append("stderr:\n").append(tail(log.getStderrText(), 1500)).append('\n');
            } else if (log.getStdoutText() != null && !log.getStdoutText().isBlank()) {
                sb.append("stdout(tail):\n").append(tail(log.getStdoutText(), 1500)).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** GitLab 시스템 토큰으로 커밋 메시지를 best-effort 조회. 실패해도 요약은 진행. */
    private String fetchCommitText(DeployRequest request, SecurityUserDetails currentUser) {
        try {
            if (request.getProject() == null
                || request.getProject().getGitlabProjectId() == null
                || request.getCommitSha() == null || request.getCommitSha().isBlank()) {
                return "";
            }
            String url = gitlabService.getSystemGitlabUrl();
            String role = currentUser != null ? currentUser.getRole() : null;
            String loginId = currentUser != null ? currentUser.getUsername() : null;
            // preferredToken=null → GitLab 시스템(봇) 토큰 폴백 사용
            String token = gitlabService.resolveAccessToken(null, role, loginId);
            if (token == null || token.isBlank() || url == null || url.isBlank()) {
                return "";
            }
            Map<String, Object> commit = gitlabService.getCommit(
                token, url, request.getProject().getGitlabProjectId(), request.getCommitSha());
            if (commit == null) {
                return "";
            }
            Object message = commit.getOrDefault("message", commit.get("title"));
            return message != null ? tail(message.toString(), 2000) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() > max ? trimmed.substring(trimmed.length() - max) : trimmed;
    }

    /** 승인자 브리핑용: 이 프로젝트의 최근 90일 배포 결과 요약. */
    private String projectDeployHistory(DeployRequest request, SecurityUserDetails currentUser) {
        if (request.getProject() == null || request.getProject().getId() == null) {
            return "정보 없음";
        }
        try {
            List<DeployRequest> recent = deployRequestService.findWithFilter(
                null, request.getProject().getId(),
                LocalDateTime.now().minusDays(90), LocalDateTime.now(),
                PageRequest.of(0, 20), currentUser).getContent();
            long done = recent.stream().filter(r -> statusIn(r, "COMPLETED", "TEST_CONFIRMED")).count();
            long failed = recent.stream().filter(r -> statusIn(r, "PROD_FAILED", "TEST_FAILED", "REJECTED")).count();
            long rolled = recent.stream().filter(r -> statusIn(r, "ROLLED_BACK")).count();
            return "최근 90일 " + recent.size() + "건 (완료 " + done + ", 실패/반려 " + failed + ", 롤백 " + rolled + ")";
        } catch (Exception e) {
            return "이력 조회 실패";
        }
    }

    private boolean statusIn(DeployRequest request, String... names) {
        if (request.getStatus() == null) {
            return false;
        }
        String status = request.getStatus().name();
        for (String name : names) {
            if (name.equals(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOperatorOrAdmin(SecurityUserDetails currentUser) {
        if (currentUser == null) {
            return false;
        }
        return projectPermissionService.isSuperAdmin(currentUser) || "OPERATOR".equals(currentUser.getRole());
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * 배포 로그 분석 — 화면의 "배포 로그" 패널에서 부른다.
     *
     * <p><b>동작 방식</b>: 클라이언트는 <b>프로젝트 ID 만</b> 보낸다. 로그 본문은 보내지 않는다.
     * 서버가 권한을 검사하고 <b>패널과 같은 메서드로 같은 데이터를 다시 읽어</b> 프롬프트를 만든다.
     *
     * <p>본문을 클라이언트가 보내게 하면 두 가지가 무너진다 —
     * ① 사내 LLM 을 아무 텍스트나 넣는 챗봇으로 쓸 수 있게 되고
     * ② 서버가 무엇을 모델에 보냈는지 보장할 수 없다.
     * ID 만 받으면 결과 내용은 화면과 똑같으면서 그 두 가지가 유지된다.
     *
     * <p><b>AI 는 이 데이터 말고는 아무것도 접근하지 못한다.</b> 서버에 접속하지 않고,
     * 무엇을 더 읽을지 스스로 고르지도 않는다. 즉 AI 의 권한 = 이 화면을 보는 사용자의 권한이다.
     */
    @PostMapping("/analyze-deploy-log/{projectId}")
    public ApiResponse<?> analyzeDeployLog(
        @PathVariable Long projectId,
        @RequestBody(required = false) Map<String, Object> body,
        @AuthenticationPrincipal SecurityUserDetails currentUser
    ) {
        ApiResponse<?> guard = guard("feat.log-analysis", true);
        if (guard != null) {
            return guard;
        }
        if (currentUser == null
            || !projectPermissionService.hasPermission(
                   currentUser, projectId, ProjectPermissionType.PROJECT_VIEW,
                   (com.portfolio.deployops.domain.DeployEnvironment) null)) {
            return ApiResponse.error("FORBIDDEN", "이 프로젝트를 조회할 권한이 없습니다.");
        }

        int limit = body != null && body.get("limit") instanceof Number n ? n.intValue() : 20;
        boolean failuresOnly = body == null || !Boolean.FALSE.equals(body.get("failuresOnly"));

        List<RemoteCommandLog> logs = remoteCommandLogService.findRecentByProject(projectId, limit).stream()
            .filter(l -> !failuresOnly || !l.isSuccess())
            .toList();

        if (logs.isEmpty()) {
            return ApiResponse.ok(failuresOnly
                ? "최근 기록에 실패한 배포가 없습니다. 분석할 오류가 없습니다."
                : "이 프로젝트에는 아직 배포 기록이 없습니다.");
        }

        // 4개 절 + 명령어 예시까지 쓰게 하므로 기본 2048 로는 늘 마지막 절이 잘린다.
        return run(currentUser, LOG_ANALYSIS_SYSTEM_PROMPT, buildLogPrompt(logs), ANALYSIS_MIN_TOKENS);
    }

    /** 분석 계열 답변에 보장할 최소 출력 토큰. */
    private static final int ANALYSIS_MIN_TOKENS = 4096;

    private static final String LOG_ANALYSIS_SYSTEM_PROMPT = """
        당신은 폐쇄망 금융 시스템의 배포 로그를 읽고 원인을 짚어 주는 DevOps 어시스턴트입니다.
        주어진 로그 밖의 사실을 지어내지 마세요. 로그에 근거가 없으면 '로그만으로는 알 수 없음'이라고 하세요.
        당신에게는 읽기 권한만 있습니다. 서버를 고치거나 명령을 실행할 수 없으니,
        '무엇을 확인하라' 또는 '무엇을 바꾸라'는 제안까지만 하세요.
        """;

    /** 패널에 보이는 것과 같은 내용으로 프롬프트를 만든다. */
    private String buildLogPrompt(List<RemoteCommandLog> logs) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래는 이 프로젝트의 최근 배포 실행 기록입니다.\n\n");
        for (RemoteCommandLog log : logs) {
            sb.append("─".repeat(50)).append('\n')
              .append("서버: ").append(nullToDash(log.getServerName()))
              .append("  |  종료코드: ").append(log.getExitCode())
              .append("  |  시각: ").append(nullToDash(log.getCreatedAt())).append('\n')
              .append("명령: ").append(nullToDash(log.getCommandDisplay())).append('\n');
            appendSection(sb, "표준출력", log.getStdoutText());
            appendSection(sb, "표준오류", log.getStderrText());
        }
        sb.append("""

            위 기록을 근거로 아래 형식에 맞춰 한국어로 답하세요.

            1) 무슨 일이 일어났는가 — 2줄 이내
            2) 원인 — 로그의 어느 줄을 근거로 그렇게 판단했는지 인용
            3) 확인할 것 — 운영자가 직접 볼 항목을 순서대로 (명령 또는 화면 위치)
            4) 수정 제안 — 무엇을 어떻게 바꾸면 되는지. 확실하지 않으면 그렇다고 쓰세요

            근거가 없는 추측은 하지 마세요.""");
        return sb.toString();
    }

    /** 로그 한 덩이가 프롬프트를 다 잡아먹지 않도록 자른다. */
    private void appendSection(StringBuilder sb, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String t = text.length() > LOG_SECTION_LIMIT
            ? text.substring(text.length() - LOG_SECTION_LIMIT) + "\n(앞부분 생략)"
            : text;
        sb.append('[').append(title).append("]\n").append(t).append('\n');
    }

    private static final int LOG_SECTION_LIMIT = 4000;

    private static String nullToDash(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }
}
