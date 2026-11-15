package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.DashboardChartsDto;
import com.portfolio.deployops.dto.DashboardSummaryDto;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.DeployStatBucket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DeployRequestRepository deployRequestRepository;
    private final ProjectPermissionService projectPermissionService;
    private final ApprovalService approvalService;

    @Transactional(readOnly = true)
    public DashboardSummaryDto getSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        long todayRequests = deployRequestRepository.countByCreatedAtBetween(todayStart, todayEnd);
        long pendingApprovals = deployRequestRepository.countByStatusIn(
            List.of(DeployStatus.APPROVAL_PENDING));
        long deploying = deployRequestRepository.countByStatusIn(
            List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING, DeployStatus.PROD_DEPLOYING));
        long todayFailed = deployRequestRepository.countByStatusAndCreatedAtBetween(
            DeployStatus.TEST_FAILED, todayStart, todayEnd)
            + deployRequestRepository.countByStatusAndCreatedAtBetween(
                DeployStatus.PROD_FAILED, todayStart, todayEnd);

        return DashboardSummaryDto.builder()
            .todayRequestCount(todayRequests)
            .pendingApprovalCount(pendingApprovals)
            .deployingCount(deploying)
            .todayFailureCount(todayFailed)
            .build();
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDto getSummary(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return getSummary();
        }

        List<Long> projectIds = projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL
        );
        if (projectIds.isEmpty()) {
            return DashboardSummaryDto.builder()
                .todayRequestCount(0)
                .pendingApprovalCount(0)
                .deployingCount(0)
                .todayFailureCount(0)
                .build();
        }

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        return DashboardSummaryDto.builder()
            .todayRequestCount(deployRequestRepository.countByCreatedAtBetweenForProjectIds(
                todayStart, todayEnd, projectIds
            ))
            .pendingApprovalCount(deployRequestRepository.countByStatusInForProjectIds(
                List.of(DeployStatus.APPROVAL_PENDING), projectIds
            ))
            .deployingCount(deployRequestRepository.countByStatusInForProjectIds(
                List.of(DeployStatus.TEST_BUILDING, DeployStatus.TEST_DEPLOYING, DeployStatus.PROD_DEPLOYING),
                projectIds
            ))
            .todayFailureCount(deployRequestRepository.countByStatusAndCreatedAtBetweenForProjectIds(
                DeployStatus.TEST_FAILED, todayStart, todayEnd, projectIds
            ) + deployRequestRepository.countByStatusAndCreatedAtBetweenForProjectIds(
                DeployStatus.PROD_FAILED, todayStart, todayEnd, projectIds
            ))
            .build();
    }

    /**
     * 배포 결과 축 — 상태 13종을 차트가 쓸 4가지로 접는다.
     *
     * <p>상태를 그대로 차트에 뿌리면 13색 범례가 되어 아무것도 읽히지 않는다.
     * 대시보드에서 답해야 할 질문은 "잘 나갔나 / 깨졌나 / 되돌렸나 / 아직인가" 넷이다.
     */
    private enum Outcome { SUCCEEDED, FAILED, ROLLED_BACK, IN_PROGRESS, CANCELLED }

    private static Outcome outcomeOf(DeployStatus status) {
        if (status == null) {
            return Outcome.IN_PROGRESS;
        }
        return switch (status) {
            // 끝까지 간 것. TEST_DEPLOYED/TEST_CONFIRMED 도 그 환경 기준으로는 성공이다.
            case COMPLETED, TEST_DEPLOYED, TEST_CONFIRMED -> Outcome.SUCCEEDED;
            case TEST_FAILED, PROD_FAILED -> Outcome.FAILED;
            case ROLLED_BACK -> Outcome.ROLLED_BACK;
            case CANCELLED -> Outcome.CANCELLED;
            // REJECTED(반려)는 실패가 아니다 — 배포가 깨진 게 아니라 사람이 안 내보낸 것이다.
            // 실패율에 섞으면 승인 절차를 제대로 쓸수록 지표가 나빠진다.
            case REJECTED -> Outcome.IN_PROGRESS;
            default -> Outcome.IN_PROGRESS;
        };
    }

    /**
     * 대시보드 차트 데이터.
     *
     * @param days 오늘을 포함해 거슬러 올라갈 일수
     */
    @Transactional(readOnly = true)
    public DashboardChartsDto getCharts(SecurityUserDetails currentUser, int days) {
        int span = Math.min(Math.max(days, 1), 90);
        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.minusDays(span - 1L);
        LocalDateTime from = firstDay.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();

        List<Long> projectIds = scopeProjectIds(currentUser);
        if (projectIds != null && projectIds.isEmpty()) {
            // 볼 수 있는 프로젝트가 하나도 없다 — 빈 차트를 그린다(전체 조회로 새면 안 된다).
            return emptyCharts(firstDay, span);
        }

        List<DeployStatBucket> buckets = deployRequestRepository.findDailyStats(from, to, projectIds);

        // 날짜별 0 채우기 — GROUP BY 는 배포가 없던 날의 행을 만들어 주지 않는다.
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < span; i++) {
            byDay.put(firstDay.plusDays(i), new long[Outcome.values().length]);
        }
        Map<DeployEnvironment, long[]> byEnv = new LinkedHashMap<>();
        long[] totals = new long[Outcome.values().length];

        for (DeployStatBucket b : buckets) {
            int slot = outcomeOf(b.getStatus()).ordinal();
            long[] day = byDay.get(b.getDay());
            if (day != null) {
                day[slot] += b.getCount();
            }
            byEnv.computeIfAbsent(b.getEnvironment(), k -> new long[Outcome.values().length])[slot] += b.getCount();
            totals[slot] += b.getCount();
        }

        List<DashboardChartsDto.DailyPoint> daily = byDay.entrySet().stream()
            .map(e -> DashboardChartsDto.DailyPoint.builder()
                .date(e.getKey().toString())
                .succeeded(e.getValue()[0])
                .failed(e.getValue()[1])
                .rolledBack(e.getValue()[2])
                .inProgress(e.getValue()[3])
                .cancelled(e.getValue()[4])
                .build())
            .toList();

        List<DashboardChartsDto.EnvSlice> envSlices = byEnv.entrySet().stream()
            .map(e -> DashboardChartsDto.EnvSlice.builder()
                .environment(e.getKey() == null ? "UNKNOWN" : e.getKey().name())
                .succeeded(e.getValue()[0])
                .failed(e.getValue()[1])
                .rolledBack(e.getValue()[2])
                .inProgress(e.getValue()[3])
                .cancelled(e.getValue()[4])
                .build())
            .sorted(Comparator.comparing(DashboardChartsDto.EnvSlice::getEnvironment))
            .toList();

        return DashboardChartsDto.builder()
            .daily(daily)
            .byEnvironment(envSlices)
            .days(span)
            .total(DashboardChartsDto.Outcome.builder()
                .succeeded(totals[0]).failed(totals[1])
                .rolledBack(totals[2]).inProgress(totals[3])
                .cancelled(totals[4])
                .build())
            .build();
    }

    /**
     * 조회 범위 프로젝트.
     *
     * @return {@code null} 이면 전체(SUPER_ADMIN), 빈 목록이면 볼 수 있는 프로젝트가 없음.
     *     이 둘을 같은 값으로 표현하면 권한 없는 사용자에게 전체가 보인다.
     */
    private List<Long> scopeProjectIds(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return null;
        }
        return projectPermissionService.findPermittedProjectIds(
            currentUser, ProjectPermissionType.PROJECT_VIEW, ProjectPermissionEnvironment.ALL);
    }

    private DashboardChartsDto emptyCharts(LocalDate firstDay, int span) {
        List<DashboardChartsDto.DailyPoint> daily = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            daily.add(DashboardChartsDto.DailyPoint.builder()
                .date(firstDay.plusDays(i).toString()).build());
        }
        return DashboardChartsDto.builder()
            .daily(daily)
            .byEnvironment(List.of())
            .days(span)
            .total(DashboardChartsDto.Outcome.builder().build())
            .build();
    }

    @Transactional(readOnly = true)
    public List<DeployRequest> getRecentDeployments() {
        return deployRequestRepository.findTop10WithProjectByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<DeployRequest> getRecentDeployments(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return getRecentDeployments();
        }
        return getRecentDeployments().stream()
            .filter(request -> projectPermissionService.hasPermission(
                currentUser,
                request.getProject().getId(),
                ProjectPermissionType.PROJECT_VIEW
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<DeployRequest> getPendingApprovals() {
        return deployRequestRepository.findByStatusInWithProject(List.of(DeployStatus.APPROVAL_PENDING));
    }

    /*
      승인 자격 판정은 ApprovalService 한 곳에만 둔다. 여기서 따로 구현하고 있었는데
      요청 환경과 무관하게 PROD_APPROVE 만 보고 있어서, TEST_APPROVE 만 가진 사용자는
      자기가 승인할 수 있는 TEST 건을 대시보드에서 보지 못했다. 목록과 실제 승인
      가능 여부가 어긋나면 사용자는 큐를 믿을 수 없게 된다.
    */
    @Transactional(readOnly = true)
    public List<DeployRequest> getPendingApprovals(SecurityUserDetails currentUser) {
        return getPendingApprovals().stream()
            .filter(request -> approvalService.canApprove(request, currentUser))
            .toList();
    }
}
