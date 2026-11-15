package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalAction;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.ApprovalDecisionSource;
import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalRecord;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeploymentReadyEvent;
import com.portfolio.deployops.domain.PolicyType;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

class ApprovalServiceTest {
    @Test
    void pendingQueueUsesFrozenGroupPolicyInsteadOfCurrentProjectPolicy() {
        var raw=request(requiredOnePolicy());raw.setPlanSnapshotJson("frozen");
        var member=user(11L,"group-approver",UserRole.APPROVER);
        var effective=EffectiveApprovalPolicy.compose(null,List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(2L).approvalPolicyId(101L).build()),
            id -> { var p=policy(PolicyType.STEP,step(201L,1,1,member));p.setId(id);return p; });
        when(deployRequestRepository.findByStatusInWithProject(List.of(DeployStatus.APPROVAL_PENDING))).thenReturn(List.of(raw));
        when(deploymentPlanSnapshotService.applySnapshot(raw)).thenAnswer(inv -> {
            raw.getProject().setApprovalPolicy(effective);return true;
        });
        assertThat(service.getMyPending(new SecurityUserDetails(member))).containsExactly(raw);
        assertThat(service.getMyPending(currentUser)).isEmpty();
        verify(approvalStepRepository,never()).findByPolicyId(100L);
    }

    @Test
    void repeatedApproverMustExplicitlyApproveEachFrozenGroupStepAndStalePostCannotAdvance() {
        var base=requiredOnePolicy();
        var second=policy(PolicyType.STEP,step(201L,1,1,approver));second.setId(101L);
        var combined=EffectiveApprovalPolicy.compose(base,List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(2L).approvalPolicyId(101L).build()),id -> second);
        var request=request(combined);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        var records=new java.util.ArrayList<ApprovalRecord>();
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenAnswer(inv -> List.copyOf(records));
        when(approvalRecordRepository.save(any())).thenAnswer(inv -> { ApprovalRecord row=inv.getArgument(0);records.add(row);return row; });
        when(approvalRecordRepository.existsByDeployRequestIdAndStepOrderAndApproverId(any(),org.mockito.ArgumentMatchers.anyInt(),any()))
            .thenAnswer(inv -> records.stream().anyMatch(row -> row.getStepOrder() == (Integer)inv.getArgument(1)
                && row.getApprover().getId().equals(inv.getArgument(2))));
        service.approve(1L,"project",currentUser,"127.0.0.1",1);
        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(service.currentStepOrder(request)).isEqualTo(2);
        assertThat(service.canApproveNow(request,currentUser)).isTrue();
        assertThatThrownBy(() -> service.approve(1L,"replayed",currentUser,"127.0.0.1",1))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("승인 단계가 변경");
        assertThatThrownBy(() -> service.reject(1L,"stale reject",currentUser,"127.0.0.1",1))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(records).hasSize(1);
        service.approve(1L,"group",currentUser,"127.0.0.1",2);
        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
        assertThat(records).extracting(ApprovalRecord::getStepOrder).containsExactly(1,2);
        verify(eventPublisher).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void testGroupApprovalCompletesToExistingTestReadyStateOnlyAfterExplicitStep() {
        var combined=EffectiveApprovalPolicy.compose(null,List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(2L).approvalPolicyId(100L).build()),id -> requiredOnePolicy());
        var request=request(1L,50L,combined,DeployEnvironment.TEST);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of(),List.of(record(1,approver)));
        assertThatThrownBy(() -> service.approve(1L,"missing expected",currentUser))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("지정되지");
        // Reset evidence reads because the rejected attempt already read the current stage.
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of(),List.of(record(1,approver)));
        service.approve(1L,"test group",currentUser,"127.0.0.1",1);
        assertThat(request.getStatus()).isEqualTo(DeployStatus.TEST_REQUESTED);
        assertThat(request.getApprovalDecisionSource()).isEqualTo(ApprovalDecisionSource.MANUAL);
        verify(deployRequestRepository).compareAndSetApprovalStatus(1L,DeployStatus.APPROVAL_PENDING,
            DeployStatus.TEST_REQUESTED,ApprovalDecisionSource.MANUAL);
        verify(eventPublisher).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void legacyPolicyStillRejectsRequestWideDuplicateAfterDatabaseIndexBroadens() {
        var request=request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.existsByDeployRequestIdAndApproverId(1L,10L)).thenReturn(true);
        assertThatThrownBy(() -> service.approve(1L,"again",currentUser)).isInstanceOf(ResponseStatusException.class);
        verify(approvalRecordRepository,never()).save(any());
        assertThat(service.canApproveNow(request,currentUser)).isFalse();
    }

    private final DeployRequestRepository deployRequestRepository = mock(DeployRequestRepository.class);
    private final ApprovalRecordRepository approvalRecordRepository = mock(ApprovalRecordRepository.class);
    private final ApprovalStepRepository approvalStepRepository = mock(ApprovalStepRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OfficeNotificationService officeNotificationService = mock(OfficeNotificationService.class);
    private final ProjectPermissionService projectPermissionService = mock(ProjectPermissionService.class);
    private final StatusHistoryRepository statusHistoryRepository = mock(StatusHistoryRepository.class);
    private final DeploymentPlanSnapshotService deploymentPlanSnapshotService = mock(DeploymentPlanSnapshotService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DeployRequestService deployRequestService = mock(DeployRequestService.class);

    private final ApprovalService service = new ApprovalService(
        deployRequestRepository,
        approvalRecordRepository,
        approvalStepRepository,
        auditLogService,
        officeNotificationService,
        projectPermissionService,
        statusHistoryRepository,
        deploymentPlanSnapshotService,
        eventPublisher,
        deployRequestService
    );

    private final User approver = user(10L, "approver", UserRole.APPROVER);
    private final SecurityUserDetails currentUser = new SecurityUserDetails(approver);

    @BeforeEach
    void setUp() {
        when(deployRequestRepository.lockById(1L)).thenReturn(1L);
        when(deployRequestRepository.compareAndSetStatus(any(), any(), any())).thenReturn(1);
        when(deployRequestRepository.compareAndSetApprovalStatus(any(), any(), any(), any())).thenReturn(1);
        when(approvalRecordRepository.existsByDeployRequestIdAndApproverId(1L, 10L)).thenReturn(false);
    }

    @Test
    void approveKeepsPendingUntilCurrentStepRequiredCountIsMet() {
        DeployRequest request = request(requiredTwoPolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalStepRepository.findByPolicyId(100L)).thenReturn(request.getProject().getApprovalPolicy().getSteps());
        when(approvalStepRepository.findApproversByStepId(200L)).thenReturn(List.of(approver, user(11L, "approver2", UserRole.APPROVER)));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of())
            .thenReturn(List.of(record(1, approver)));

        service.approve(1L, "ok", currentUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
    }

    @Test
    void approveCompletesWhenStepRequiredCountIsMet() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalStepRepository.findByPolicyId(100L)).thenReturn(request.getProject().getApprovalPolicy().getSteps());
        when(approvalStepRepository.findApproversByStepId(200L)).thenReturn(List.of(approver));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of())
            .thenReturn(List.of(record(1, approver)));

        service.approve(1L, "ok", currentUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
        assertThat(request.getApprovalDecisionSource()).isEqualTo(ApprovalDecisionSource.MANUAL);
        verify(deployRequestRepository).compareAndSetApprovalStatus(
            1L, DeployStatus.APPROVAL_PENDING, DeployStatus.APPROVAL_COMPLETED,
            ApprovalDecisionSource.MANUAL);
        verify(statusHistoryRepository).save(any());
        verify(deploymentPlanSnapshotService).assertCurrentPlanMatches(request);
        verify(deploymentPlanSnapshotService).applySnapshot(request);
        verify(deployRequestRepository).lockById(1L);
        verify(eventPublisher).publishEvent(any(DeploymentReadyEvent.class));

        InOrder snapshotBeforeRecord = inOrder(deploymentPlanSnapshotService, approvalRecordRepository);
        snapshotBeforeRecord.verify(deploymentPlanSnapshotService).assertCurrentPlanMatches(request);
        snapshotBeforeRecord.verify(deploymentPlanSnapshotService).applySnapshot(request);
        snapshotBeforeRecord.verify(approvalRecordRepository).save(any());
    }

    @Test
    void approveRejectsUnverifiedProdRollbackBeforeRecordingApproval() {
        DeployRequest request = request(requiredOnePolicy());
        request.setReason("[ROLLBACK][history:77] restore");
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "CI 출처 미검증"))
            .when(deployRequestService).assertProductionRollbackProvenance(request);

        assertThatThrownBy(() -> service.approve(1L, "ok", currentUser))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처 미검증");
        verify(approvalRecordRepository, never()).save(any());
    }

    @Test
    void approveChecksProdRollbackBeforeRecordingVerifiedApproval() {
        DeployRequest request = request(requiredOnePolicy());
        request.setReason("[ROLLBACK][history:77] restore");
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalStepRepository.findByPolicyId(100L)).thenReturn(request.getProject().getApprovalPolicy().getSteps());
        when(approvalStepRepository.findApproversByStepId(200L)).thenReturn(List.of(approver));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of(), List.of(record(1, approver)));

        service.approve(1L, "ok", currentUser);

        InOrder order = inOrder(deployRequestService, approvalRecordRepository);
        order.verify(deployRequestService).assertProductionRollbackProvenance(request);
        order.verify(approvalRecordRepository).save(any());
    }

    /*
      승인 API 의 인가는 SecurityConfig 의 URL 매처가 아니라 여기서 갈린다
      (필터 체인은 /api/v1/approvals/** 를 authenticated() 로만 본다).
      아래 네 건이 그 경계 전체다 — 이게 무너지면 HTTP 레벨에는 막을 것이 없다.
    */

    @Test
    void approveRejectsUserWithNeitherGlobalRoleNorProjectPermission() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        // 전역 APPROVER 도 아니고, 이 프로젝트의 승인 권한도 없다(mock 기본값 false).
        SecurityUserDetails developer = new SecurityUserDetails(user(30L, "dev", UserRole.DEVELOPER));

        assertThatThrownBy(() -> service.approve(1L, "ok", developer))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    /*
      전역 APPROVER 역할이 없어도 그 프로젝트의 PROD_APPROVE 를 받았으면 승인할 수 있다.
      이 테스트가 빨개지면 프로젝트 스코프 승인이 죽은 것이다 — 화면에는 승인 버튼이
      보이는데 누르면 403 이 나는 상태가 된다.
    */
    @Test
    void approveAllowsProjectScopedApproverWithoutTheGlobalApproverRole() {
        User dev = user(30L, "dev", UserRole.DEVELOPER);
        SecurityUserDetails developer = new SecurityUserDetails(dev);
        DeployRequest request = request(requiredOnePolicy());
        request.getProject().getApprovalPolicy().getSteps().get(0).setApprovers(List.of(dev));
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalStepRepository.findApproversByStepId(200L)).thenReturn(List.of(dev));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of())
            .thenReturn(List.of(record(1, dev)));
        when(projectPermissionService.hasPermission(
            developer, 50L, ProjectPermissionType.PROD_APPROVE, DeployEnvironment.PROD)).thenReturn(true);

        service.approve(1L, "ok", developer);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
    }

    /*
      환경 분리의 핵심 — TEST 승인 권한으로 PROD 를 승인할 수 있으면 개발망에서 받은
      권한이 운영망으로 새어 나간다. PROD 요청은 반드시 PROD_APPROVE 를 봐야 한다.
    */
    @Test
    void prodApprovalDoesNotAcceptTheTestApprovePermission() {
        User dev = user(30L, "dev", UserRole.DEVELOPER);
        SecurityUserDetails developer = new SecurityUserDetails(dev);
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        // TEST 승인 권한만 있다.
        when(projectPermissionService.hasPermission(
            developer, 50L, ProjectPermissionType.TEST_APPROVE, DeployEnvironment.TEST)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(1L, "ok", developer))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    /*
      목록과 실제 권한이 어긋나면 승인자는 누를 수 없는 건을 계속 보게 된다.
      getMyPending 은 approve 와 같은 판정을 써야 한다.
    */
    @Test
    void myPendingHidesRequestsTheUserCannotApprove() {
        User dev = user(30L, "dev", UserRole.DEVELOPER);
        SecurityUserDetails developer = new SecurityUserDetails(dev);
        DeployRequest mine = request(1L, 50L, null, DeployEnvironment.PROD);
        DeployRequest others = request(2L, 51L, null, DeployEnvironment.PROD);
        when(deployRequestRepository.findByStatusInWithProject(List.of(DeployStatus.APPROVAL_PENDING)))
            .thenReturn(List.of(mine, others));
        when(projectPermissionService.hasPermission(
            developer, 50L, ProjectPermissionType.PROD_APPROVE, DeployEnvironment.PROD)).thenReturn(true);

        assertThat(service.getMyPending(developer)).containsExactly(mine);
    }

    @Test
    void approveRejectsGlobalApproverNotAssignedToCurrentStep() {
        DeployRequest request = request(requiredOnePolicy());
        request.getProject().getApprovalPolicy().getSteps().get(0)
            .setApprovers(List.of(user(99L, "other", UserRole.APPROVER)));
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalStepRepository.findByPolicyId(100L)).thenReturn(request.getProject().getApprovalPolicy().getSteps());
        when(approvalStepRepository.findApproversByStepId(200L)).thenReturn(List.of(user(99L, "other", UserRole.APPROVER)));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.approve(1L, "ok", currentUser))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    /*
      화면은 두 가지를 구분해서 그린다 — 큐에 보일 것(canApprove)과 버튼을 그릴 것
      (canApproveNow). 둘이 같아지면 "자격은 있지만 아직 내 단계가 아닌" 건이 큐에서
      통째로 사라지거나, 반대로 눌러도 403 이 나는 버튼이 그려진다.
    */
    @Test
    void canApproveShowsTheQueueButCanApproveNowGatesTheButton() {
        User dev = user(30L, "dev", UserRole.DEVELOPER);
        SecurityUserDetails developer = new SecurityUserDetails(dev);
        DeployRequest request = request(requiredOnePolicy());
        // 이 단계의 지정 승인자는 다른 사람이다.
        request.getProject().getApprovalPolicy().getSteps().get(0)
            .setApprovers(List.of(user(99L, "other", UserRole.APPROVER)));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());
        when(projectPermissionService.hasPermission(
            developer, 50L, ProjectPermissionType.PROD_APPROVE, DeployEnvironment.PROD)).thenReturn(true);

        assertThat(service.canApprove(request, developer)).isTrue();
        assertThat(service.canApproveNow(request, developer)).isFalse();
    }

    @Test
    void anyOneCompletesWhenOneAllowedApproverApproves() {
        User secondApprover = user(11L, "approver2", UserRole.APPROVER);
        SecurityUserDetails secondUser = new SecurityUserDetails(secondApprover);
        ApprovalPolicy policy = policy(PolicyType.ANY_ONE,
            step(200L, 1, 1, approver),
            step(201L, 2, 1, secondApprover));
        DeployRequest request = request(policy);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.existsByDeployRequestIdAndApproverId(1L, 11L)).thenReturn(false);
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of(record(2, secondApprover)));

        service.approve(1L, "ok", secondUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
    }

    @Test
    void allWaitsUntilEveryActiveAllowedApproverHasApproved() {
        User secondApprover = user(11L, "approver2", UserRole.APPROVER);
        ApprovalPolicy policy = policy(PolicyType.ALL,
            step(200L, 1, 1, approver),
            step(201L, 2, 1, secondApprover));
        DeployRequest request = request(policy);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of(record(1, approver)));

        service.approve(1L, "ok", currentUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        verify(deployRequestRepository, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void allCompletesAfterEveryActiveAllowedApproverHasApproved() {
        User secondApprover = user(11L, "approver2", UserRole.APPROVER);
        SecurityUserDetails secondUser = new SecurityUserDetails(secondApprover);
        ApprovalPolicy policy = policy(PolicyType.ALL,
            step(200L, 1, 1, approver),
            step(201L, 2, 1, secondApprover));
        DeployRequest request = request(policy);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.existsByDeployRequestIdAndApproverId(1L, 11L)).thenReturn(false);
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of(record(1, approver), record(2, secondApprover)));

        service.approve(1L, "ok", secondUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
    }

    @Test
    void allRequiresOnlyActiveApproversFromThePolicy() {
        User inactiveApprover = user(11L, "inactive", UserRole.APPROVER);
        inactiveApprover.setActive(false);
        ApprovalPolicy policy = policy(PolicyType.ALL,
            step(200L, 1, 1, approver),
            step(201L, 2, 1, inactiveApprover));
        DeployRequest request = request(policy);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of(record(1, approver)));

        service.approve(1L, "ok", currentUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
    }

    @Test
    void stepRejectsLaterStageApproverBeforePreviousStageIsSatisfied() {
        User secondApprover = user(11L, "approver2", UserRole.APPROVER);
        SecurityUserDetails secondUser = new SecurityUserDetails(secondApprover);
        ApprovalPolicy policy = policy(PolicyType.STEP,
            step(200L, 1, 1, approver),
            step(201L, 2, 1, secondApprover));
        DeployRequest request = request(policy);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.existsByDeployRequestIdAndApproverId(1L, 11L)).thenReturn(false);
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.approve(1L, "ok", secondUser))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
        verify(approvalRecordRepository, never()).save(any());
    }

    @Test
    void stepCompletesAfterEveryStageMeetsItsRequiredCountInOrder() {
        User secondApprover = user(11L, "approver2", UserRole.APPROVER);
        SecurityUserDetails secondUser = new SecurityUserDetails(secondApprover);
        ApprovalPolicy policy = policy(PolicyType.STEP,
            step(200L, 1, 1, approver),
            step(201L, 2, 1, secondApprover));
        DeployRequest request = request(policy);
        ApprovalRecord firstApproval = record(1, approver);
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.existsByDeployRequestIdAndApproverId(1L, 11L)).thenReturn(false);
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of(firstApproval))
            .thenReturn(List.of(firstApproval, record(2, secondApprover)));

        service.approve(1L, "ok", secondUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
    }

    @Test
    void casConflictDoesNotWriteStatusHistory() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of())
            .thenReturn(List.of(record(1, approver)));
        when(deployRequestRepository.compareAndSetApprovalStatus(
            1L, DeployStatus.APPROVAL_PENDING, DeployStatus.APPROVAL_COMPLETED,
            ApprovalDecisionSource.MANUAL)).thenReturn(0);

        assertThatThrownBy(() -> service.approve(1L, "ok", currentUser))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409");
        verify(statusHistoryRepository, never()).save(any());
    }

    @Test
    void rejectUsesCasAndRecordsStatusHistory() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());

        service.reject(1L, "승인할 수 없음", currentUser);

        assertThat(request.getStatus()).isEqualTo(DeployStatus.REJECTED);
        verify(deployRequestRepository).compareAndSetStatus(
            1L, DeployStatus.APPROVAL_PENDING, DeployStatus.REJECTED);
        verify(statusHistoryRepository).save(any());
    }

    @Test
    void httpApprovalRecordsActorAndSocketClientIpBeforeCompletion() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L))
            .thenReturn(List.of(), List.of(record(1, approver)));
        service.approve(1L, "reviewed", currentUser, "198.18.17.1");
        InOrder order = inOrder(auditLogService, deployRequestRepository, eventPublisher);
        order.verify(auditLogService).record(eq(currentUser.getName()), eq("배포 승인"),
            eq("DeployRequest"), eq("1"), any(), eq(AuditResult.SUCCESS), eq("198.18.17.1"));
        order.verify(deployRequestRepository).compareAndSetApprovalStatus(
            1L, DeployStatus.APPROVAL_PENDING, DeployStatus.APPROVAL_COMPLETED, ApprovalDecisionSource.MANUAL);
        order.verify(eventPublisher).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void httpRejectionRecordsActorAndSocketClientIpBeforeStatusTransition() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());
        service.reject(1L, "review rejected", currentUser, "198.18.17.1");
        InOrder order = inOrder(auditLogService, deployRequestRepository);
        order.verify(auditLogService).record(eq(currentUser.getName()), eq("배포 반려"),
            eq("DeployRequest"), eq("1"), any(), eq(AuditResult.SUCCESS), eq("198.18.17.1"));
        order.verify(deployRequestRepository).compareAndSetStatus(
            1L, DeployStatus.APPROVAL_PENDING, DeployStatus.REJECTED);
    }

    @Test
    void httpApprovalAuditFailurePreventsCompletionAndNotification() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());
        doThrow(new IllegalStateException("AUDIT_UNAVAILABLE")).when(auditLogService).record(
            eq(currentUser.getName()), eq("배포 승인"), eq("DeployRequest"), eq("1"),
            any(), eq(AuditResult.SUCCESS), eq("198.18.17.1"));
        assertThatThrownBy(() -> service.approve(1L, "reviewed", currentUser, "198.18.17.1"))
            .isInstanceOf(IllegalStateException.class).hasMessage("AUDIT_UNAVAILABLE");
        verify(deployRequestRepository, never()).compareAndSetApprovalStatus(any(), any(), any(), any());
        verify(statusHistoryRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(DeploymentReadyEvent.class));
        verify(officeNotificationService, never()).notifyApprovalCompleted(request);
    }

    @Test
    void httpRejectionAuditFailurePreventsRejectionAndNotification() {
        DeployRequest request = request(requiredOnePolicy());
        when(deployRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(approvalRecordRepository.findByDeployRequestId(1L)).thenReturn(List.of());
        doThrow(new IllegalStateException("AUDIT_UNAVAILABLE")).when(auditLogService).record(
            eq(currentUser.getName()), eq("배포 반려"), eq("DeployRequest"), eq("1"),
            any(), eq(AuditResult.SUCCESS), eq("198.18.17.1"));
        assertThatThrownBy(() -> service.reject(1L, "review rejected", currentUser, "198.18.17.1"))
            .isInstanceOf(IllegalStateException.class).hasMessage("AUDIT_UNAVAILABLE");
        verify(deployRequestRepository, never()).compareAndSetStatus(any(), any(), any());
        verify(statusHistoryRepository, never()).save(any());
        verify(officeNotificationService, never()).notifyRejected(request);
    }

    private DeployRequest request(ApprovalPolicy policy) {
        return request(1L, 50L, policy, DeployEnvironment.PROD);
    }

    private DeployRequest request(Long id, Long projectId, ApprovalPolicy policy, DeployEnvironment environment) {
        return DeployRequest.builder()
            .id(id)
            .reqNo("REQ-%03d".formatted(id))
            .environment(environment)
            .status(DeployStatus.APPROVAL_PENDING)
            .requestedBy(user(20L, "requester", UserRole.DEVELOPER))
            .project(Project.builder()
                .id(projectId)
                .name("worksystem-server")
                .approvalPolicy(policy)
                .build())
            .build();
    }

    private ApprovalPolicy requiredOnePolicy() {
        return policy(PolicyType.STEP, step(200L, 1, 1, approver));
    }

    private ApprovalPolicy requiredTwoPolicy() {
        return policy(PolicyType.STEP,
            step(200L, 1, 2, approver, user(11L, "approver2", UserRole.APPROVER)));
    }

    private ApprovalPolicy policy(PolicyType type, ApprovalStep... steps) {
        return ApprovalPolicy.builder()
            .id(100L)
            .name("prod")
            .policyType(type)
            .steps(List.of(steps))
            .build();
    }

    private ApprovalStep step(Long id, int order, int requiredCount, User... approvers) {
        return ApprovalStep.builder()
            .id(id)
            .stepOrder(order)
            .requiredCount(requiredCount)
            .approvers(List.of(approvers))
            .build();
    }

    private ApprovalRecord record(int stepOrder, User approver) {
        return ApprovalRecord.builder()
            .deployRequest(DeployRequest.builder().id(1L).build())
            .stepOrder(stepOrder)
            .approver(approver)
            .action(ApprovalAction.APPROVED)
            .build();
    }

    private User user(Long id, String loginId, UserRole role) {
        return User.builder()
            .id(id)
            .loginId(loginId)
            .name(loginId)
            .password("secret")
            .role(role)
            .active(true)
            .build();
    }
}
