package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 대시보드 승인 큐가 <b>실제 승인 자격</b>과 같은 기준으로 추려지는지 지킨다.
 *
 * <p>ApprovalService 는 진짜를 쓴다. 여기서 보려는 것이 "대시보드가 승인 판정을 자기
 * 마음대로 다시 구현하지 않는가" 이므로, mock 으로 바꾸면 정작 확인하려던 것이 사라진다.
 *
 * <p>실제로 겪은 문제 — 대시보드가 요청 환경과 무관하게 {@code PROD_APPROVE} 만 보고
 * 걸러서, TEST 승인 권한만 가진 사용자는 자기가 승인할 수 있는 TEST 건을 큐에서 보지
 * 못했다. 목록과 권한이 어긋나면 사용자는 큐를 믿지 않게 된다.
 */
class DashboardServiceTest {

    private final DeployRequestRepository deployRequestRepository = mock(DeployRequestRepository.class);
    private final ProjectPermissionService projectPermissionService = mock(ProjectPermissionService.class);

    private final ApprovalService approvalService = new ApprovalService(
        deployRequestRepository,
        mock(ApprovalRecordRepository.class),
        mock(ApprovalStepRepository.class),
        mock(AuditLogService.class),
        mock(OfficeNotificationService.class),
        projectPermissionService,
        mock(StatusHistoryRepository.class),
        mock(DeploymentPlanSnapshotService.class),
        mock(ApplicationEventPublisher.class),
        mock(DeployRequestService.class)
    );

    private final DashboardService service = new DashboardService(
        deployRequestRepository, projectPermissionService, approvalService);

    private final SecurityUserDetails developer = new SecurityUserDetails(
        User.builder().id(30L).loginId("dev").name("dev").password("x").role(UserRole.DEVELOPER).active(true).build());

    @Test
    void pendingQueueShowsTestRequestsToAUserHoldingOnlyTheTestApprovePermission() {
        DeployRequest testRequest = request(1L, 50L, DeployEnvironment.TEST);
        DeployRequest prodRequest = request(2L, 50L, DeployEnvironment.PROD);
        when(deployRequestRepository.findByStatusInWithProject(List.of(DeployStatus.APPROVAL_PENDING)))
            .thenReturn(List.of(testRequest, prodRequest));
        when(projectPermissionService.hasPermission(
            developer, 50L, ProjectPermissionType.TEST_APPROVE, DeployEnvironment.TEST)).thenReturn(true);

        // PROD 는 PROD_APPROVE 를 요구하므로 빠지고, TEST 는 보여야 한다.
        assertThat(service.getPendingApprovals(developer)).containsExactly(testRequest);
    }

    @Test
    void pendingQueueHidesProjectsTheUserHasNoApprovalPermissionFor() {
        DeployRequest mine = request(1L, 50L, DeployEnvironment.PROD);
        DeployRequest others = request(2L, 51L, DeployEnvironment.PROD);
        when(deployRequestRepository.findByStatusInWithProject(List.of(DeployStatus.APPROVAL_PENDING)))
            .thenReturn(List.of(mine, others));
        when(projectPermissionService.hasPermission(
            developer, 50L, ProjectPermissionType.PROD_APPROVE, DeployEnvironment.PROD)).thenReturn(true);

        assertThat(service.getPendingApprovals(developer)).containsExactly(mine);
    }

    private DeployRequest request(Long id, Long projectId, DeployEnvironment environment) {
        return DeployRequest.builder()
            .id(id)
            .reqNo("REQ-%03d".formatted(id))
            .environment(environment)
            .status(DeployStatus.APPROVAL_PENDING)
            .project(Project.builder().id(projectId).name("project-" + projectId).build())
            .build();
    }

    @Test
    void summaryCountsBothFailureEnvironmentsButNotPastDaysOrSuccessfulRetries() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        var rows = failureCountRows(start);
        when(deployRequestRepository.countByStatusAndCreatedAtBetween(any(), any(), any()))
            .thenAnswer(call -> countMatching(rows, call.getArgument(0), call.getArgument(1),
                call.getArgument(2), null));
        var admin = new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).build());
        when(projectPermissionService.isSuperAdmin(admin)).thenReturn(true);

        assertThat(service.getSummary(admin).getTodayFailureCount()).isEqualTo(4);

        verify(deployRequestRepository).countByStatusAndCreatedAtBetween(
            DeployStatus.TEST_FAILED, start, start.plusDays(1));
        verify(deployRequestRepository).countByStatusAndCreatedAtBetween(
            DeployStatus.PROD_FAILED, start, start.plusDays(1));
        verify(deployRequestRepository, never()).countByStatusAndCreatedAtBetweenForProjectIds(
            any(), any(), any(), anyList());
    }

    @Test
    void permittedSummaryCountsBothFailuresWithinTheOriginalProjectViewScope() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        var rows = failureCountRows(start);
        when(projectPermissionService.findPermittedProjectIds(
            developer, ProjectPermissionType.PROJECT_VIEW, ProjectPermissionEnvironment.ALL))
            .thenReturn(List.of(50L));
        when(deployRequestRepository.countByStatusAndCreatedAtBetweenForProjectIds(
            any(), any(), any(), anyList()))
            .thenAnswer(call -> countMatching(rows, call.getArgument(0), call.getArgument(1),
                call.getArgument(2), call.getArgument(3)));

        assertThat(service.getSummary(developer).getTodayFailureCount()).isEqualTo(2);

        verify(deployRequestRepository).countByStatusAndCreatedAtBetweenForProjectIds(
            DeployStatus.TEST_FAILED, start, start.plusDays(1), List.of(50L));
        verify(deployRequestRepository).countByStatusAndCreatedAtBetweenForProjectIds(
            DeployStatus.PROD_FAILED, start, start.plusDays(1), List.of(50L));
        verify(deployRequestRepository, never()).countByStatusAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void summaryWithoutProjectViewPermissionDoesNotQueryGlobalOrScopedCounts() {
        when(projectPermissionService.findPermittedProjectIds(
            developer, ProjectPermissionType.PROJECT_VIEW, ProjectPermissionEnvironment.ALL))
            .thenReturn(List.of());

        assertThat(service.getSummary(developer).getTodayFailureCount()).isZero();
        verifyNoInteractions(deployRequestRepository);
    }

    private List<DeployRequest> failureCountRows(LocalDateTime start) {
        return List.of(
            summaryRow(50L, DeployStatus.TEST_FAILED, start.plusHours(1)),
            summaryRow(50L, DeployStatus.PROD_FAILED, start.plusHours(2)),
            summaryRow(51L, DeployStatus.TEST_FAILED, start.plusHours(3)),
            summaryRow(51L, DeployStatus.PROD_FAILED, start.plusHours(4)),
            summaryRow(50L, DeployStatus.TEST_FAILED, start.minusSeconds(1)),
            summaryRow(50L, DeployStatus.PROD_FAILED, start.minusDays(1)),
            summaryRow(50L, DeployStatus.TEST_DEPLOYED, start.plusHours(5)),
            summaryRow(50L, DeployStatus.TEST_CONFIRMED, start.plusHours(6)),
            summaryRow(50L, DeployStatus.COMPLETED, start.plusHours(7)),
            summaryRow(50L, DeployStatus.CANCELLED, start.plusHours(8))
        );
    }

    private DeployRequest summaryRow(Long projectId, DeployStatus status, LocalDateTime createdAt) {
        return DeployRequest.builder().status(status).createdAt(createdAt)
            .project(Project.builder().id(projectId).build()).build();
    }

    // 기존 repository 계약을 모사하며 SQL 통합 검증을 주장하지 않는다.
    private long countMatching(List<DeployRequest> rows, DeployStatus status, LocalDateTime from,
                               LocalDateTime to, List<Long> projectIds) {
        return rows.stream().filter(row -> row.getStatus() == status)
            .filter(row -> !row.getCreatedAt().isBefore(from) && !row.getCreatedAt().isAfter(to))
            .filter(row -> projectIds == null || projectIds.contains(row.getProject().getId())).count();
    }

    @Test
    void withdrawnRequestsHaveTheirOwnChartCategoryWithoutInflatingProgressOrFailureRate() {
        var admin = new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).build());
        when(projectPermissionService.isSuperAdmin(admin)).thenReturn(true);
        var today = java.time.LocalDate.now();
        when(deployRequestRepository.findDailyStats(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(List.of(
                new com.portfolio.deployops.repository.DeployStatBucket(today, DeployEnvironment.TEST, DeployStatus.CANCELLED, 3),
                new com.portfolio.deployops.repository.DeployStatBucket(today, DeployEnvironment.PROD, DeployStatus.CANCELLED, 2),
                new com.portfolio.deployops.repository.DeployStatBucket(today, DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED, 4),
                new com.portfolio.deployops.repository.DeployStatBucket(today, DeployEnvironment.TEST, DeployStatus.TEST_DEPLOYED, 1)
            ));
        var chart = service.getCharts(admin, 1);
        assertThat(chart.getTotal().getCancelled()).isEqualTo(5);
        assertThat(chart.getTotal().getInProgress()).isEqualTo(4);
        assertThat(chart.getTotal().getFailed()).isZero();
        assertThat(chart.getTotal().getFinished()).isEqualTo(1);
        assertThat(chart.getTotal().getSuccessRate()).isEqualTo(100);
        assertThat(chart.getDaily().getFirst().getCancelled()).isEqualTo(5);
        assertThat(chart.getByEnvironment()).anySatisfy(slice -> {
            assertThat(slice.getEnvironment()).isEqualTo("TEST");
            assertThat(slice.getCancelled()).isEqualTo(3);
            assertThat(slice.getInProgress()).isEqualTo(4);
        }).anySatisfy(slice -> {
            assertThat(slice.getEnvironment()).isEqualTo("PROD");
            assertThat(slice.getCancelled()).isEqualTo(2);
            assertThat(slice.getInProgress()).isZero();
        });
    }
}
