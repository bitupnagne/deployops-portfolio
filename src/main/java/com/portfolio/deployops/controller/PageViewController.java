package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalRecord;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ReleaseVerificationEvidence;
import com.portfolio.deployops.domain.StatusHistory;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.service.AiChatService;
import com.portfolio.deployops.service.ApprovalService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DashboardService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.DeploymentDutySeparationService;
import com.portfolio.deployops.service.PolicyService;
import com.portfolio.deployops.service.ProjectImageHistoryService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.ServerGroupService;
import com.portfolio.deployops.service.ServerService;
import com.portfolio.deployops.service.SystemSettingService;
import com.portfolio.deployops.service.TeamService;
import com.portfolio.deployops.service.UserService;
import com.portfolio.deployops.service.NginxChangeService;
import com.portfolio.deployops.view.NginxFailurePresentation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class PageViewController {

    private static final String PROD_ROLLBACK_BLOCK_REASON =
        "운영 롤백을 승인·실행하려면 프로젝트 승인 정책과 같은 프로젝트·저장소·이미지 다이제스트에 연결된 PROD 원본 요청의 검증된 CI 출처 증적이 필요합니다.";

    private static final Set<String> ALLOWED_PAGES = Set.of(
        "dashboard",
        "requests",
        "request-create",
        "mr-approvals",
        "request-detail",
        "projects",
        "project-detail",
        "server-groups",
        "servers",
        "server-map",
        "infrastructure",
        "nginx-policies",
        "nexus-operations",
        "users",
        "teams",
        "policies",
        "integration",
        "exec-policy",
        "audit",
        "profile",
        "ai-settings",
        "security"
    );

    /**
     * 브레드크럼 문구. {@code app.js} 의 {@code PAGE_META} 와 <b>같은 값을 갖는다</b>.
     *
     * <p>왜 서버에도 두는가 — 브레드크럼을 JS 의 {@code navigate()} 만 갱신하고 있었다.
     * 그래서 주소로 직접 들어오거나 새로고침하면 레이아웃에 하드코딩된 "대시보드" 가 그대로 남았다.
     * 사이드바 active 는 {@code pageId} 로 서버가 칠하고 있어서 <b>둘이 서로 다른 화면을 가리켰다.</b>
     *
     * <p>사이드바 항목에 실제 {@code href} 가 생기면서(2026-07-30 접근성 수정) 새 탭 열기로도
     * 직접 진입할 수 있게 되어 더 눈에 띄게 됐다. 서버가 먼저 그리고, SPA 전환 때만 JS 가 갱신한다.
     *
     * <p>{@code ALLOWED_PAGES} 에 있는데 여기 없으면 pageId 가 그대로 노출된다 —
     * {@code mr-approvals} 가 실제로 그 상태였다. 테스트로 짝을 맞춘다.
     */
    private static final Map<String, String> PAGE_LABELS = Map.ofEntries(
        Map.entry("dashboard", "대시보드"),
        Map.entry("requests", "배포 관리 > 배포 요청 목록"),
        Map.entry("request-create", "배포 관리 > 새 배포 요청"),
        Map.entry("request-detail", "배포 관리 > 배포 요청 상세"),
        Map.entry("mr-approvals", "배포 관리 > MR 승인 대기"),
        Map.entry("projects", "인프라 관리 > 프로젝트"),
        Map.entry("project-detail", "인프라 관리 > 프로젝트 상세"),
        Map.entry("server-groups", "인프라 관리 > 서버 그룹"),
        Map.entry("servers", "인프라 관리 > 서버 관리"),
        Map.entry("server-map", "인프라 관리 > 서버 구조 · 제어"),
        Map.entry("infrastructure", "인프라 관리 > 인프라 운영"),
        Map.entry("nginx-policies", "인프라 관리 > Nginx 정책"),
        Map.entry("nexus-operations", "인프라 관리 > Nexus 운영"),
        Map.entry("users", "시스템 > 사용자 / 역할"),
        Map.entry("teams", "시스템 > 팀 관리"),
        Map.entry("policies", "시스템 > 승인 정책"),
        Map.entry("integration", "시스템 > 연동 설정"),
        Map.entry("exec-policy", "시스템 > 실행 정책"),
        Map.entry("audit", "시스템 > 감사 로그"),
        Map.entry("ai-settings", "시스템 > AI 설정"),
        Map.entry("security", "시스템 > 보안 설정"),
        Map.entry("profile", "내 프로필")
    );

    /** 화면 라벨 — 목록에 없으면 pageId 를 그대로 쓴다(빈 브레드크럼보다는 낫다). */
    static String pageLabel(String pageId) {
        return PAGE_LABELS.getOrDefault(pageId, pageId);
    }

    /** 브레드크럼 상위 구간("시스템 > 감사 로그" 의 "시스템"). 1단계 화면이면 null. */
    static String pageParent(String pageId) {
        String label = pageLabel(pageId);
        int i = label.indexOf(" > ");
        return i < 0 ? null : label.substring(0, i);
    }

    /** 브레드크럼 마지막 구간. */
    static String pageLeaf(String pageId) {
        String label = pageLabel(pageId);
        int i = label.indexOf(" > ");
        return i < 0 ? label : label.substring(i + 3);
    }

    private final DashboardService dashboardService;
    private final DeployRequestService deployRequestService;
    private final ApprovalService approvalService;
    private final ProjectService projectService;
    private final ProjectPermissionService projectPermissionService;
    private final ServerGroupService serverGroupService;
    private final DeployComponentRepository deployComponentRepository;
    private final ServerService serverService;
    private final NginxChangeService nginxChangeService;
    private final UserService userService;
    private final TeamService teamService;
    private final PolicyService policyService;
    private final AuditLogService auditLogService;
    private final SystemSettingService systemSettingService;
    private final ProjectImageHistoryService projectImageHistoryService;
    private final DeploymentDutySeparationService dutySeparationService;

    @GetMapping("/")
    public String root() {
        return "redirect:/deploy/dashboard";
    }

    @GetMapping("/deploy")
    public String deployRoot() {
        return "redirect:/deploy/dashboard";
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        // form login과 SSO의 principal 구현체가 달라도 인증 세션이면
        // 로그인 화면을 다시 렌더링하지 않고 업무 화면으로 보낸다.
        if (authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/deploy/dashboard";
        }
        return "login";
    }

    @GetMapping("/deploy/{pageId}")
    public String page(
        @PathVariable String pageId,
        @RequestParam(required = false) Long id,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) String actor,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model
    ) {
        if ("nginx-policies".equals(pageId)) {
            String server = request.getParameter("nginxServerId");
            return "redirect:/deploy/server-groups" +
                (server != null && server.matches("[1-9][0-9]*") ? "?nginxServerId=" + server : "?nginx=1");
        }
        boolean fragment = isFragmentRequest(request);
        populateCommonModel(model, currentUser, pageId);

        if (!ALLOWED_PAGES.contains(pageId)) {
            return renderError(
                model,
                response,
                fragment,
                HttpStatus.NOT_FOUND,
                "페이지를 찾을 수 없습니다.",
                "요청한 메뉴가 존재하지 않거나 접근할 수 없습니다."
            );
        }

        try {
            switch (pageId) {
                case "dashboard" -> loadDashboard(model, currentUser);
                case "requests" -> loadRequests(model, status, projectId, from, to, page, currentUser);
                case "request-create" -> {
                    var requestable = projectService.findDeployRequestable(currentUser);
                    model.addAttribute("projects", requestable);
                    var environments = projectService.requestEnvironments(requestable, currentUser);
                    model.addAttribute("requestEnvironments", environments);
                    model.addAttribute("requestTargetCounts", projectService.requestTargetCounts(requestable));
                    var requestGroups = new java.util.LinkedHashMap<Long, java.util.List<com.portfolio.deployops.domain.ServerGroup>>();
                    for (var project : requestable) {
                        // requestable은 DEPLOY_REQUEST 권한으로 필터된 목록이다.
                        // 기본 조회에는 그룹/서버가 없으므로 실제 대상이 포함된 상세 조회를 사용한다.
                        var detail = projectService.findByIdWithDetails(project.getId());
                        requestGroups.put(project.getId(), detail.getServerGroups().stream()
                            .filter(group -> environments.get(project.getId()).contains(group.getEnvironment().name()))
                            .toList());
                    }
                    model.addAttribute("requestGroups", requestGroups);
                }
                case "mr-approvals" -> { /* 목록은 클라이언트가 /api/v1/gitlab/my-merge-requests로 로드 */ }
                case "request-detail" -> loadRequestDetail(model, id, currentUser);
                // 위자드가 서버 그룹·승인 정책까지 물으므로 목록을 함께 내린다 —
                // 프로젝트를 만든 직후 바로 배포 가능한 상태가 되게 하려는 것이다.
                case "projects" -> {
                    model.addAttribute("projects", projectService.findVisibleAll(currentUser));
                    model.addAttribute("serverGroups", serverGroupService.findAll());
                    model.addAttribute("policies", policyService.findAll());
                }
                case "project-detail" -> loadProjectDetail(model, id, currentUser);
                // 그룹에 속하지 않은 서버도 함께 센다.
                //   이 화면은 그룹 안에 든 서버만 그리므로, 그룹이 없는 서버는 아예 보이지 않는다.
                //   그런 서버는 어느 프로젝트의 배포 대상도 될 수 없어 정작 찾아내야 하는 것인데
                //   화면이 침묵해서 있는 줄도 몰랐다.
                case "server-groups" -> {
                    loadServerGroupWorkspace(model, request);
                }
                case "servers" -> loadServers(model);
                case "infrastructure" -> model.addAttribute("servers", serverService.findAll());
                case "nginx-policies" -> {
                    model.addAttribute("webServers", serverService.findAll().stream()
                        .filter(server -> server.getServerType() == com.portfolio.deployops.domain.ServerType.WEB)
                        .toList());
                    model.addAttribute("wasServers", serverService.findAll().stream()
                        .filter(server -> server.getServerType() == com.portfolio.deployops.domain.ServerType.WAS).toList());
                    var changes = nginxChangeService.findAll();
                    model.addAttribute("nginxChanges", changes);
                    model.addAttribute("nginxDisplayErrors", NginxFailurePresentation.displayErrors(changes));
                }
                // 구조도는 프로젝트를 골라야 그릴 수 있다(컨테이너가 프로젝트에 매인다).
                //   목록만 서버가 주고, 구조·상태는 화면이 API 로 가져온다.
                case "server-map" -> model.addAttribute("projects", projectService.findVisibleActive(currentUser));
                case "users" -> loadUsers(model);
                case "teams" -> loadTeams(model);
                case "policies" -> loadPolicies(model, id);
                case "audit" -> loadAudit(model, actor, from, to, page);
                case "ai-settings" -> loadAiSettings(model);
                case "integration" -> model.addAttribute("projects", projectService.findAllActive());
                case "nexus-operations" -> model.addAttribute("projects", projectService.findAllActive());
                default -> {
                    // no-op for client-side settings/profile pages
                }
            }
        } catch (ResponseStatusException ex) {
            HttpStatus statusCode = HttpStatus.valueOf(ex.getStatusCode().value());
            return renderError(
                model,
                response,
                fragment,
                statusCode,
                "페이지를 불러올 수 없습니다.",
                defaultMessage(ex.getReason(), statusCode)
            );
        } catch (IllegalArgumentException ex) {
            return renderError(
                model,
                response,
                fragment,
                HttpStatus.NOT_FOUND,
                "대상을 찾을 수 없습니다.",
                defaultMessage(ex.getMessage(), HttpStatus.NOT_FOUND)
            );
        } catch (Exception ex) {
            return renderError(
                model,
                response,
                fragment,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "페이지를 불러올 수 없습니다.",
                "요청 처리 중 오류가 발생했습니다."
            );
        }

        model.addAttribute("pageId", pageId);
        return fragment ? fragmentView(pageId) : "layout/main";
    }

    private void loadServerGroupWorkspace(Model model, HttpServletRequest request) {
        var groups = serverGroupService.findAll();
        var servers = serverService.findAll();
        var changes = nginxChangeService.findAll();
        model.addAttribute("serverGroups", groups);
        var groupActor = model.getAttribute("currentUser");
        boolean canManageGroupApproval = groupActor instanceof SecurityUserDetails actor
            && projectPermissionService.isSuperAdmin(actor);
        model.addAttribute("canManageGroupApproval", canManageGroupApproval);
        model.addAttribute("groupApprovalPolicyNames", serverGroupService.approvalPolicyNames(groups));
        model.addAttribute("groupApprovalPolicies",
            canManageGroupApproval ? serverGroupService.approvalPolicyChoices() : List.of());
        model.addAttribute("ungroupedCount", servers.stream().filter(s -> s.getServerGroup() == null).count());
        model.addAttribute("ungroupedWebServers", servers.stream()
            .filter(s -> s.getServerGroup() == null && s.getServerType() == com.portfolio.deployops.domain.ServerType.WEB).toList());
        Map<Long, List<Map<String, Object>>> sections = new HashMap<>();
        for (var group : groups) {
            var members = group.getServers() == null ? List.<com.portfolio.deployops.domain.Server>of() : group.getServers();
            sections.put(group.getId(), List.of(
                Map.of("key", "WEB", "label", "WEB 서버", "servers", members.stream()
                    .filter(s -> s.getServerType() == com.portfolio.deployops.domain.ServerType.WEB).toList()),
                Map.of("key", "WAS", "label", "WAS 서버", "servers", members.stream()
                    .filter(s -> s.getServerType() == com.portfolio.deployops.domain.ServerType.WAS).toList()),
                Map.of("key", "OTHER", "label", "기타 서버", "servers", members.stream()
                    .filter(s -> s.getServerType() != com.portfolio.deployops.domain.ServerType.WEB
                        && s.getServerType() != com.portfolio.deployops.domain.ServerType.WAS).toList())
            ));
        }
        model.addAttribute("groupServerSections", sections);
        Map<Long, Long> changeCounts = new HashMap<>();
        for (var change : changes) {
            if (change.getServer() != null && change.getServer().getId() != null) {
                changeCounts.merge(change.getServer().getId(), 1L, Long::sum);
            }
        }
        model.addAttribute("nginxChangeCounts", changeCounts);
        model.addAttribute("nginxLanding", "1".equals(request.getParameter("nginx")));
        String selected = request.getParameter("nginxServerId");
        if (selected == null || selected.isBlank()) return;
        Long serverId;
        try { serverId = Long.valueOf(selected); }
        catch (NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WEB 서버를 다시 선택해 주세요."); }
        var server = servers.stream().filter(s -> serverId.equals(s.getId())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WEB 서버를 찾을 수 없습니다."));
        if (server.getServerType() != com.portfolio.deployops.domain.ServerType.WEB) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nginx 설정은 WEB 서버에서 관리합니다.");
        }
        var selectedChanges = changes.stream().filter(c -> c.getServer() != null
            && serverId.equals(c.getServer().getId())).toList();
        model.addAttribute("nginxContextServer", server);
        model.addAttribute("nginxWasVipChoices", nginxChangeService.wasVipChoices(server));
        model.addAttribute("nginxContextGroupId", server.getServerGroup() == null ? null : server.getServerGroup().getId());
        model.addAttribute("nginxContextGroupName", server.getServerGroup() == null ? "그룹 미지정" : server.getServerGroup().getName());
        model.addAttribute("webServers", List.of(server));
        model.addAttribute("wasServers", servers.stream()
            .filter(s -> s.getServerType() == com.portfolio.deployops.domain.ServerType.WAS).toList());
        model.addAttribute("nginxChanges", selectedChanges);
        model.addAttribute("nginxDisplayErrors", NginxFailurePresentation.displayErrors(selectedChanges));
    }

    private void populateCommonModel(Model model, SecurityUserDetails currentUser, String pageId) {
        model.addAttribute("pageId", pageId);
        model.addAttribute("pageParent", pageParent(pageId));
        model.addAttribute("pageLeaf", pageLeaf(pageId));
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("aiEnabled", Boolean.parseBoolean(systemSettingService.get("ai.enabled", "false")));
        model.addAttribute("canOperate", canOperate(currentUser));
    }

    /*
      전역 역할만 보던 페이지 단위 canApprove 는 지웠다. 어떤 템플릿도 쓰지 않았고,
      승인 가능 여부는 <요청 건별>로만 답할 수 있는 물음이라(프로젝트·환경·승인 단계)
      페이지 단위 플래그로 두면 반드시 실제 권한과 어긋난다.
      화면이 쓰는 값: 상세는 canApproveRequest, 대시보드는 approvableRequestIds.
    */

    /** 배포 요청 진행 단계(프로그래스바)용 데이터. 환경별 흐름 + 현재 상태 색상. */
    private static final DateTimeFormatter PROGRESS_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    /**
     * 진행 흐름 단계를 세분화하고, 각 단계에 수행자(요청자/승인자/실행자/확인자) 이름과 시각을 매핑한다.
     * 템플릿은 단계 막대 위에 ▲ 마커로 수행자 이름을 표시한다.
     */
    private List<Map<String, Object>> buildProgress(DeployRequest request) {
        String env = request.getEnvironment() != null ? request.getEnvironment().name() : "TEST";
        String status = request.getStatus() != null ? request.getStatus().name() : "";

        String requester = request.getRequestedBy() != null ? request.getRequestedBy().getName() : null;
        String approver = lastApproverName(request);
        String executor = request.getExecutedBy() != null ? request.getExecutedBy().getName() : null;
        String confirmer = request.getTestConfirmedBy() != null ? request.getTestConfirmedBy().getName() : null;

        String reqTime = fmt(request.getCreatedAt());
        String apprTime = lastApprovedTime(request);
        String execTime = historyTime(request, "PROD_DEPLOYING", "TEST_DEPLOYING", "TEST_BUILDING");
        String doneTime = historyTime(request, "COMPLETED", "TEST_DEPLOYED", "ROLLED_BACK");
        String confTime = fmt(request.getTestConfirmedAt());

        String[] labels;
        String[] roles;
        String[] actors;
        String[] times;
        boolean groupApproval = request.getProject() != null && request.getProject().getApprovalPolicy() != null
            && request.getProject().getApprovalPolicy().isGroupComposed();
        if ("PROD".equals(env)) {
            labels = new String[]{"요청 접수", "승인", "배포 실행", "배포 완료"};
            roles = new String[]{"요청자", "승인자", "실행자", null};
            actors = new String[]{requester, approver, executor, null};
            times = new String[]{reqTime, apprTime, execTime, doneTime};
        } else if (groupApproval) {
            labels = new String[]{"요청 접수", "그룹 승인", "배포 실행", "배포 완료", "테스트 확인"};
            roles = new String[]{"요청자", "승인자", "실행자", null, "확인자"};
            actors = new String[]{requester, approver, executor, null, confirmer};
            times = new String[]{reqTime, apprTime, execTime, doneTime, confTime};
        } else {
            labels = new String[]{"요청 접수", "배포 실행", "배포 완료", "테스트 확인"};
            roles = new String[]{"요청자", "실행자", null, "확인자"};
            actors = new String[]{requester, executor, null, confirmer};
            times = new String[]{reqTime, execTime, doneTime, confTime};
        }

        if ("CANCELLED".equals(status)) {
            String cancelledAt = historyTime(request, "CANCELLED");
            return List.of(Map.of("label", "요청 철회", "state", "done",
                "role", "", "actor", "", "time", cancelledAt != null ? cancelledAt : ""));
        }
        int current = currentStepIndex(env, status);
        if ("TEST".equals(env) && groupApproval) {
            current = ("APPROVAL_PENDING".equals(status) || "REJECTED".equals(status)) ? 1
                : "TEST_REQUESTED".equals(status) ? 2 : current + 1;
        }
        boolean failed = status.equals("REJECTED") || status.equals("TEST_FAILED") || status.equals("PROD_FAILED");
        boolean finished = status.equals("COMPLETED") || status.equals("TEST_CONFIRMED") || status.equals("ROLLED_BACK");

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            String state;
            if (i < current) {
                state = "done";
            } else if (i == current) {
                state = failed ? "failed" : (finished ? "done" : "active");
            } else {
                state = "todo";
            }
            boolean reached = !"todo".equals(state);
            Map<String, Object> step = new HashMap<>();
            step.put("label", labels[i]);
            step.put("state", state);
            step.put("role", roles[i] != null ? roles[i] : "");
            step.put("actor", reached && actors[i] != null ? actors[i] : "");
            step.put("time", reached && times[i] != null ? times[i] : "");
            steps.add(step);
        }
        return steps;
    }

    private int currentStepIndex(String env, String status) {
        if ("PROD".equals(env)) {
            // 0:요청 접수 1:승인 2:배포 실행 3:배포 완료
            return switch (status) {
                case "APPROVAL_PENDING", "REJECTED" -> 1;
                case "APPROVAL_COMPLETED", "PROD_DEPLOYING", "PROD_FAILED" -> 2;
                case "COMPLETED", "ROLLED_BACK" -> 3;
                default -> 1;
            };
        }
        // 0:요청 접수 1:배포 실행 2:배포 완료 3:테스트 확인
        return switch (status) {
            case "TEST_REQUESTED", "TEST_BUILDING", "TEST_DEPLOYING", "TEST_FAILED" -> 1;
            case "TEST_DEPLOYED", "TEST_CONFIRMED", "ROLLED_BACK" -> 3;
            default -> 0;
        };
    }

    private String fmt(LocalDateTime time) {
        return time != null ? time.format(PROGRESS_TIME) : null;
    }

    private String lastApproverName(DeployRequest request) {
        if (request.getApprovalRecords() == null) {
            return null;
        }
        return request.getApprovalRecords().stream()
            .filter(a -> a.getAction() != null && "APPROVED".equals(a.getAction().name()))
            .map(a -> a.getApprover() != null ? a.getApprover().getName() : null)
            .filter(Objects::nonNull)
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private String lastApprovedTime(DeployRequest request) {
        if (request.getApprovalRecords() == null) {
            return null;
        }
        return request.getApprovalRecords().stream()
            .filter(a -> a.getAction() != null && "APPROVED".equals(a.getAction().name()))
            .map(ApprovalRecord::getProcessedAt)
            .filter(Objects::nonNull)
            .reduce((first, second) -> second)
            .map(this::fmt)
            .orElse(null);
    }

    private String historyTime(DeployRequest request, String... statuses) {
        if (request.getStatusHistories() == null) {
            return null;
        }
        Set<String> wanted = Set.of(statuses);
        return request.getStatusHistories().stream()
            .filter(h -> h.getStatus() != null && wanted.contains(h.getStatus().name()))
            .map(StatusHistory::getCreatedAt)
            .filter(Objects::nonNull)
            .findFirst()
            .map(this::fmt)
            .orElse(null);
    }

    /**
     * '운영 실행' 메뉴 노출 여부. 글로벌 OPERATOR/SUPER_ADMIN 역할이거나,
     * 어떤 프로젝트든 실행 권한(TEST/PROD/ROLLBACK_EXECUTE)을 가지면 true.
     * (운영실행자는 역할이 아니라 프로젝트 귀속 권한으로 정해지므로 권한 기준으로 노출)
     */
    private boolean canOperate(SecurityUserDetails currentUser) {
        if (currentUser == null) {
            return false;
        }
        if (projectPermissionService.isSuperAdmin(currentUser) || "OPERATOR".equals(currentUser.getRole())) {
            return true;
        }
        return !projectPermissionService.findPermittedProjectIds(
                currentUser, ProjectPermissionType.TEST_EXECUTE, ProjectPermissionEnvironment.TEST).isEmpty()
            || !projectPermissionService.findPermittedProjectIds(
                currentUser, ProjectPermissionType.PROD_EXECUTE, ProjectPermissionEnvironment.PROD).isEmpty()
            || !projectPermissionService.findPermittedProjectIds(
                currentUser, ProjectPermissionType.ROLLBACK_EXECUTE, ProjectPermissionEnvironment.PROD).isEmpty();
    }

    private void loadDashboard(Model model, SecurityUserDetails currentUser) {
        List<DeployRequest> pendingApprovals = dashboardService.getPendingApprovals(currentUser);
        Map<Long, String> rollbackBlockReasons = new HashMap<>();
        for (DeployRequest pending : pendingApprovals) {
            String reason = productionRollbackBlockReason(pending);
            if (reason != null) {
                rollbackBlockReasons.put(pending.getId(), reason);
            }
        }
        model.addAttribute("summary", dashboardService.getSummary(currentUser));
        model.addAttribute("pendingApprovals", pendingApprovals);
        model.addAttribute("rollbackBlockReasons", rollbackBlockReasons);
        model.addAttribute("recentDeployments", dashboardService.getRecentDeployments(currentUser));
        // 큐에는 승인 자격이 있는 건이 다 뜨지만, 지금 내 차례가 아닌 건도 섞여 있다.
        // 버튼은 실제로 누를 수 있는 행에만 그린다 — 행마다 판정이 달라지므로 id 집합으로 내린다.
        model.addAttribute("approvableRequestIds", pendingApprovals.stream()
            .filter(request -> approvalService.canApproveNow(request, currentUser))
            .map(DeployRequest::getId)
            .collect(java.util.stream.Collectors.toSet()));
    }

    private void loadAiSettings(Model model) {
        Map<String, String> settings = systemSettingService.getCategory("ai");
        String aiModel = settings.getOrDefault("ai.model", "claude-sonnet-4-6");
        String aiProvider = settings.get("ai.provider");
        if (aiProvider == null || aiProvider.isBlank()) {
            aiProvider = AiChatService.inferProvider(aiModel);
        }

        model.addAttribute("aiProvider", aiProvider);
        model.addAttribute("aiModel", aiModel);
        model.addAttribute("aiBaseUrl", settings.getOrDefault("ai.base-url", ""));
        model.addAttribute("aiMaxTokens", settings.getOrDefault("ai.max-tokens", "2048"));
        model.addAttribute("aiTemperature", settings.getOrDefault("ai.temperature", "0.3"));
        model.addAttribute("aiSystemPrompt", settings.getOrDefault("ai.system-prompt", ""));
        model.addAttribute("featRisk", parseBooleanSetting(settings, "feat.risk", true));
        model.addAttribute("featApprover", parseBooleanSetting(settings, "feat.approver", true));
        model.addAttribute("featSummary", parseBooleanSetting(settings, "feat.summary", true));
        model.addAttribute("featDiag", parseBooleanSetting(settings, "feat.diag", true));
        model.addAttribute("featLogread", parseBooleanSetting(settings, "feat.logread", true));
    }

    private boolean parseBooleanSetting(Map<String, String> settings, String key, boolean defaultValue) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    private void loadRequests(
        Model model,
        String status,
        Long projectId,
        LocalDate from,
        LocalDate to,
        int page,
        SecurityUserDetails currentUser
    ) {
        DeployStatus statusFilter = parseDeployStatus(status);
        LocalDate fromDate = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate toDate = to != null ? to : LocalDate.now();

        model.addAttribute("statusList", Arrays.asList(DeployStatus.values()));
        Map<Long, Project> requestListProjects = new java.util.LinkedHashMap<>();
        projectService.findVisibleActive(currentUser).forEach(project -> requestListProjects.put(project.getId(), project));
        projectService.findDeployRequestable(currentUser).forEach(project -> requestListProjects.putIfAbsent(project.getId(), project));
        model.addAttribute("projects", List.copyOf(requestListProjects.values()));
        model.addAttribute("requests", deployRequestService.findWithFilter(
            statusFilter,
            projectId,
            fromDate.atStartOfDay(),
            toDate.plusDays(1).atStartOfDay(),
            PageRequest.of(Math.max(page, 0), 20),
            currentUser
        ));
        model.addAttribute("filterStatus", statusFilter != null ? statusFilter.name() : "");
        model.addAttribute("filterProjectId", projectId);
        model.addAttribute("filterFrom", fromDate);
        model.addAttribute("filterTo", toDate);
    }

    private void loadRequestDetail(Model model, Long id, SecurityUserDetails currentUser) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "배포 요청 ID가 없습니다.");
        }

        DeployRequest deployRequest = deployRequestService.findById(id, currentUser);
        model.addAttribute("canViewRequestEvidence", deployRequestService.canViewEvidence(currentUser, deployRequest));
        String prodRollbackBlockReason = productionRollbackBlockReason(deployRequest);
        model.addAttribute("prodRollbackBlockReason", prodRollbackBlockReason);
        model.addAttribute("request", deployRequest);
        model.addAttribute("requestUsesWasVip", deployRequest.getDeployScope() != com.portfolio.deployops.domain.DeployScope.WEB_ONLY
            && !com.portfolio.deployops.service.WasVipPolicy.portlessWorker(deployRequest.getProject()));
        model.addAttribute("progress", buildProgress(deployRequest));
        model.addAttribute("canConfirmTest", canConfirmTest(currentUser, deployRequest));
        model.addAttribute("canWithdrawRequest", deployRequestService.canWithdraw(currentUser, deployRequest));
        // 승인 패널은 역할이 아니라 <이 요청을> 지금 승인할 수 있는가로 그린다.
        model.addAttribute("canApproveRequest", approvalService.canApproveNow(deployRequest, currentUser));
        model.addAttribute("approvalCurrentStepOrder", approvalService.currentStepOrder(deployRequest));
        ProjectImageHistory rollbackSuggestion = projectImageHistoryService
            .findLatestRollbackCandidate(deployRequest).orElse(null);
        model.addAttribute("rollbackSuggestion", rollbackSuggestion);
        model.addAttribute("rollbackSuggestionBlockReason",
            rollbackSuggestionBlockReason(deployRequest, rollbackSuggestion, currentUser));

        // 운영 실행 메뉴를 상세 화면으로 통합: 실행 권한/상태에 따라 실행 패널을 노출한다.
        DeployEnvironment env = deployRequest.getEnvironment();
        boolean hasExecutePermission = projectPermissionService.hasPermission(
            currentUser,
            deployRequest.getProject() != null ? deployRequest.getProject().getId() : null,
            env == DeployEnvironment.TEST
                ? ProjectPermissionType.TEST_EXECUTE
                : ProjectPermissionType.PROD_EXECUTE,
            env);
        String st = deployRequest.getStatus() != null ? deployRequest.getStatus().name() : "";
        boolean execReady = env == DeployEnvironment.TEST
            ? "TEST_REQUESTED".equals(st)
            : "APPROVAL_COMPLETED".equals(st);
        boolean execLive = "TEST_BUILDING".equals(st) || "TEST_DEPLOYING".equals(st) || "PROD_DEPLOYING".equals(st);
        String executionActorBlockReason = hasExecutePermission
            ? executionActorBlockReason(deployRequest, currentUser) : null;
        model.addAttribute("executionActorBlockReason", executionActorBlockReason);
        model.addAttribute("canExecute",
            hasExecutePermission && prodRollbackBlockReason == null && executionActorBlockReason == null);
        model.addAttribute("canViewExecutionLogs", hasExecutePermission);
        model.addAttribute("execReady", execReady);
        model.addAttribute("retryLegacyBlocked", deployRequest.getTargetAttemptTrackingStartedAt() == null
            && (deployRequest.getStatus() == DeployStatus.TEST_FAILED || deployRequest.getStatus() == DeployStatus.PROD_FAILED));
        model.addAttribute("retryReady", com.portfolio.deployops.service.DeploymentRetryPolicy.eligible(deployRequest));
        model.addAttribute("execLive", execLive);
    }

    private String executionActorBlockReason(DeployRequest request, SecurityUserDetails currentUser) {
        try {
            dutySeparationService.assertCanOperate(request, currentUser);
            return null;
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() != HttpStatus.FORBIDDEN.value()) {
                throw ex;
            }
            return ex.getReason();
        }
    }

    private String productionRollbackBlockReason(DeployRequest request) {
        if (request == null || request.getEnvironment() != DeployEnvironment.PROD
            || !deployRequestService.isRollbackRequest(request)) {
            return null;
        }
        try {
            deployRequestService.assertProductionRollbackProvenance(request);
            return null;
        } catch (ResponseStatusException ex) {
            return PROD_ROLLBACK_BLOCK_REASON;
        }
    }

    private String rollbackSuggestionBlockReason(DeployRequest failedRequest, ProjectImageHistory history, SecurityUserDetails currentUser) {
        if (history == null || failedRequest.getEnvironment() != DeployEnvironment.PROD) {
            return null;
        }
        if (history.getDeployRequest() == null || history.getDeployRequest().getId() == null) {
            return PROD_ROLLBACK_BLOCK_REASON;
        }
        try {
            deployRequestService.assertRollbackSuggestionAvailable(history.getId(), failedRequest.getProject().getId(), currentUser);
            return null;
        } catch (ResponseStatusException ex) {
            return PROD_ROLLBACK_BLOCK_REASON;
        }
    }

    private void loadProjectDetail(Model model, Long id, SecurityUserDetails currentUser) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트 ID가 없습니다.");
        }

        model.addAttribute("projectId", id);
        var project = projectService.findByIdWithDetails(id, currentUser);
        model.addAttribute("project", project);
        model.addAttribute("availableServerGroups", serverGroupService.findAll());
        model.addAttribute("mappedServerGroupIds", project.getServerGroups().stream()
            .map(group -> group.getId()).toList());
        model.addAttribute("canOperateCiPipelines",
            projectPermissionService.hasPermission(currentUser, id, ProjectPermissionType.DEPLOY_REQUEST));
        model.addAttribute("requireMrEvidenceForBuild", Boolean.parseBoolean(
            systemSettingService.get("require-mr-evidence-for-build", "true")));
        model.addAttribute("canReviewMergeRequests",
            projectPermissionService.hasPermission(currentUser, id, ProjectPermissionType.MR_REVIEW));
        model.addAttribute("canRollbackTest",
            projectPermissionService.hasPermission(currentUser, id, ProjectPermissionType.ROLLBACK_EXECUTE, DeployEnvironment.TEST));
        model.addAttribute("canRollbackProd",
            projectPermissionService.hasPermission(currentUser, id, ProjectPermissionType.ROLLBACK_EXECUTE, DeployEnvironment.PROD));
        if (currentUser != null && "SUPER_ADMIN".equals(currentUser.getRole())) {
            model.addAttribute("approvalPolicies", policyService.findAll());
        }
        model.addAttribute("components", deployComponentRepository.findByProjectId(id));
        model.addAttribute("projLogPaths", systemSettingService.get("ai.logpath." + id, ""));
        // AI 로그 경로 탐색기의 "어느 서버를 볼지" 선택지
        model.addAttribute("servers", serverService.findAll());
    }

    private void loadServers(Model model) {
        model.addAttribute("servers", serverService.findAll());
        model.addAttribute("serverGroups", serverGroupService.findAll());
        model.addAttribute("environments", Arrays.asList(ServerEnvironment.values()));
    }

    private void loadUsers(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("teams", teamService.findAll());
    }

    private void loadTeams(Model model) {
        model.addAttribute("teams", teamService.findAll());
        model.addAttribute("allUsers", userService.findAll());
    }

    private void loadPolicies(Model model, Long id) {
        List<?> approvers = userService.findApprovers();
        model.addAttribute("policies", policyService.findAll());
        model.addAttribute("approvers", approvers);
        model.addAttribute("approverOptions", approvers);
        model.addAttribute("currentPolicyId", id);
    }

    private void loadAudit(
        Model model,
        String actor,
        LocalDate from,
        LocalDate to,
        int page
    ) {
        LocalDate fromDate = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate toDate = to != null ? to : LocalDate.now();

        model.addAttribute("auditLogs", auditLogService.findWithFilter(
            blankToNull(actor),
            fromDate.atStartOfDay(),
            toDate.plusDays(1).atStartOfDay(),
            PageRequest.of(Math.max(page, 0), 20)
        ));
        model.addAttribute("auditResults", Arrays.asList(AuditResult.values()));
        model.addAttribute("filterActor", actor);
        model.addAttribute("filterFrom", fromDate);
        model.addAttribute("filterTo", toDate);
    }

    private DeployStatus parseDeployStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DeployStatus.valueOf(status);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean canConfirmTest(SecurityUserDetails currentUser, DeployRequest deployRequest) {
        return deployRequestService.canConfirmTest(currentUser, deployRequest);
    }

    private String renderError(
        Model model,
        HttpServletResponse response,
        boolean fragment,
        HttpStatus status,
        String title,
        String message
    ) {
        model.addAttribute("pageId", "error404");
        model.addAttribute("errorTitle", title);
        model.addAttribute("errorMessage", message);
        model.addAttribute("errorStatus", status.value());

        if (!fragment) {
            response.setStatus(status.value());
        }

        return fragment ? fragmentView("error404") : "layout/main";
    }

    private boolean isFragmentRequest(HttpServletRequest request) {
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }

    private String fragmentView(String pageId) {
        return "pages/" + pageId + " :: content";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String defaultMessage(String message, HttpStatus status) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        return status == HttpStatus.NOT_FOUND
            ? "요청한 페이지 또는 데이터를 찾을 수 없습니다."
            : "요청 처리 중 오류가 발생했습니다.";
    }
}
