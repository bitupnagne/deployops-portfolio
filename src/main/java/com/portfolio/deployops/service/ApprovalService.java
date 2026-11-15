package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalDecisionSource;
import com.portfolio.deployops.domain.ApprovalAction;
import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalRecord;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeploymentReadyEvent;
import com.portfolio.deployops.domain.PolicyType;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.StatusHistory;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final DeployRequestRepository deployRequestRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final AuditLogService auditLogService;
    private final OfficeNotificationService officeNotificationService;
    private final ProjectPermissionService projectPermissionService;
    private final StatusHistoryRepository statusHistoryRepository;
    private final DeploymentPlanSnapshotService deploymentPlanSnapshotService;
    private final ApplicationEventPublisher eventPublisher;
    private final DeployRequestService deployRequestService;

    @Transactional(readOnly = true)
    public List<DeployRequest> getMyPending(SecurityUserDetails currentUser) {
        // 전역 승인권자(APPROVER/SUPER_ADMIN)는 모든 승인 대기 건을, 프로젝트 스코프
        // 승인자는 자기가 권한을 가진 프로젝트 건만 본다. 목록에 보이는 것과 실제로
        // 승인할 수 있는 것이 어긋나면 안 되므로, 같은 hasApprovalPermission 을 쓴다.
        return deployRequestRepository.findByStatusInWithProject(List.of(DeployStatus.APPROVAL_PENDING)).stream()
            .filter(request -> canApproveNow(request, currentUser))
            .toList();
    }

    /** 승인권자 = 전역 APPROVER 역할(또는 SUPER_ADMIN). 프로젝트 귀속이 아니다. */
    private boolean isGlobalApprover(SecurityUserDetails currentUser) {
        return projectPermissionService.isSuperAdmin(currentUser)
            || "APPROVER".equals(currentUser.getRole());
    }

    /**
     * 이 요청을 승인할 <b>자격</b>이 있는가 — 전역 승인권자이거나, 그 요청이 속한
     * 프로젝트에서 해당 환경의 승인 권한을 가진 사용자여야 한다.
     *
     * <p>PROD 요청은 반드시 {@code PROD_APPROVE} 를 본다. TEST 권한으로 운영 승인이
     * 통과하면 환경 분리가 무의미해진다.
     *
     * <p>승인 <b>목록</b>을 추리는 기준이다. 자격은 있지만 지금 내 차례가 아닌 건도
     * 큐에는 보여야 하므로, 버튼 노출에는 {@link #canApproveNow} 를 쓴다.
     */
    public boolean canApprove(DeployRequest request, SecurityUserDetails currentUser) {
        return hasApprovalPermission(request, currentUser);
    }

    /**
     * 지금 이 사용자가 이 요청을 실제로 승인/반려할 수 있는가 — 자격에 더해
     * <b>현재 단계의 지정 승인자</b>이고, 이미 처리하지 않았고, 자기 승인 금지에도
     * 걸리지 않아야 한다.
     *
     * <p>화면의 승인 버튼은 이 값으로 그린다. 자격만 보고 버튼을 그리면 눌렀을 때
     * {@code validatePolicyEligibility} 가 403/409 로 돌려보내, 사용자는 왜 안 되는지
     * 모른 채 같은 버튼을 다시 누른다.
     */
    public boolean canApproveNow(DeployRequest request, SecurityUserDetails currentUser) {
        try {
            if (request != null && request.getPlanSnapshotJson() != null) deploymentPlanSnapshotService.applySnapshot(request);
            return hasApprovalPermission(request, currentUser) && isEligibleForCurrentStep(request, currentUser);
        } catch (ResponseStatusException invalidSnapshot) {
            return false;
        }
    }

    public Integer currentStepOrder(DeployRequest request) {
        if (request == null || request.getStatus() != DeployStatus.APPROVAL_PENDING) return null;
        ApprovalStep step = resolveApprovalStep(request,null);
        return step == null ? null : step.getStepOrder();
    }

    private boolean hasApprovalPermission(DeployRequest request, SecurityUserDetails currentUser) {
        if (isGlobalApprover(currentUser)) return true;
        if (request == null || request.getProject() == null || request.getProject().getId() == null) return false;
        ProjectPermissionType permission = request.getEnvironment() == DeployEnvironment.PROD
            ? ProjectPermissionType.PROD_APPROVE : ProjectPermissionType.TEST_APPROVE;
        return projectPermissionService.hasPermission(currentUser, request.getProject().getId(), permission, request.getEnvironment());
    }

    private void assertApprovalPermission(DeployRequest request, SecurityUserDetails currentUser) {
        if (!hasApprovalPermission(request, currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 프로젝트의 승인 권한이 없습니다.");
        }
    }

    @Transactional
    public void approve(Long deployRequestId, String comment, SecurityUserDetails currentUser) {
        approve(deployRequestId, comment, currentUser, null);
    }

    @Transactional
    public void approve(Long deployRequestId, String comment, SecurityUserDetails currentUser, String clientIp) {
        approve(deployRequestId,comment,currentUser,clientIp,null);
    }

    @Transactional
    public void approve(Long deployRequestId, String comment, SecurityUserDetails currentUser, String clientIp,Integer expectedStepOrder) {
        DeployRequest request = findLockedRequest(deployRequestId);

        assertApprovalPermission(request, currentUser);
        validateApprovalState(request, currentUser);
        deploymentPlanSnapshotService.assertCurrentPlanMatches(request);
        deploymentPlanSnapshotService.applySnapshot(request);
        deployRequestService.assertProductionRollbackProvenance(request);
        ApprovalStep approvalStep = resolveApprovalStep(request, currentUser.getId());
        validateStepDecision(request,approvalStep,currentUser,expectedStepOrder);
        validatePolicyEligibility(request, approvalStep, currentUser);

        ApprovalRecord record = ApprovalRecord.builder()
            .deployRequest(request)
            .stepOrder(approvalStep != null ? approvalStep.getStepOrder() : 1)
            .approver(currentUser.getUser())
            .action(ApprovalAction.APPROVED)
            .comment(comment)
            .build();
        approvalRecordRepository.save(record);
        auditLogService.record(
            currentUser.getName(),
            "배포 승인",
            "DeployRequest",
            String.valueOf(deployRequestId),
            auditLogService.describeDeployRequest(request),
            AuditResult.SUCCESS,
            clientIp
        );

        completeApprovalWhenSatisfied(request);
        request.setApprovalRecords(approvalRecordRepository.findByDeployRequestId(request.getId()));
        if (request.getStatus() == DeployStatus.APPROVAL_PENDING) officeNotificationService.notifyApprovalPending(request);
        officeNotificationService.notifyApprovalCompleted(request);
    }

    @Transactional
    public void reject(Long deployRequestId, String comment, SecurityUserDetails currentUser) {
        reject(deployRequestId, comment, currentUser, null);
    }

    @Transactional
    public void reject(Long deployRequestId, String comment, SecurityUserDetails currentUser, String clientIp) {
        reject(deployRequestId,comment,currentUser,clientIp,null);
    }

    @Transactional
    public void reject(Long deployRequestId, String comment, SecurityUserDetails currentUser, String clientIp,Integer expectedStepOrder) {
        if (comment == null || comment.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "반려 사유는 필수입니다.");
        }

        DeployRequest request = findLockedRequest(deployRequestId);

        assertApprovalPermission(request, currentUser);
        validateApprovalState(request, currentUser);
        deploymentPlanSnapshotService.assertCurrentPlanMatches(request);
        deploymentPlanSnapshotService.applySnapshot(request);
        ApprovalStep approvalStep = resolveApprovalStep(request, currentUser.getId());
        validateStepDecision(request,approvalStep,currentUser,expectedStepOrder);
        validatePolicyEligibility(request, approvalStep, currentUser);

        ApprovalRecord record = ApprovalRecord.builder()
            .deployRequest(request)
            .stepOrder(approvalStep != null ? approvalStep.getStepOrder() : 1)
            .approver(currentUser.getUser())
            .action(ApprovalAction.REJECTED)
            .comment(comment)
            .build();
        approvalRecordRepository.save(record);
        auditLogService.record(
            currentUser.getName(),
            "배포 반려",
            "DeployRequest",
            String.valueOf(deployRequestId),
            auditLogService.describeDeployRequest(request),
            AuditResult.SUCCESS,
            clientIp
        );

        transitionStatus(request, DeployStatus.REJECTED);
        officeNotificationService.notifyRejected(request);
    }

    private DeployRequest findLockedRequest(Long deployRequestId) {
        if (deployRequestRepository.lockById(deployRequestId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return deployRequestRepository.findById(deployRequestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void validateApprovalState(DeployRequest request, SecurityUserDetails currentUser) {
        if (request.getStatus() != DeployStatus.APPROVAL_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 승인 대기 상태가 아닙니다.");
        }
    }

    private boolean alreadyDecided(DeployRequest request,ApprovalStep step,Long userId) {
        ApprovalPolicy policy = request.getProject().getApprovalPolicy();
        return policy != null && policy.isGroupComposed()
            ? step == null || approvalRecordRepository.existsByDeployRequestIdAndStepOrderAndApproverId(request.getId(),step.getStepOrder(),userId)
            : approvalRecordRepository.existsByDeployRequestIdAndApproverId(request.getId(),userId);
    }

    private void validateStepDecision(DeployRequest request,ApprovalStep step,SecurityUserDetails user,Integer expected) {
        ApprovalPolicy policy = request.getProject().getApprovalPolicy();
        if (policy != null && policy.isGroupComposed() && (step == null || !java.util.Objects.equals(expected,step.getStepOrder())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"승인 단계가 변경되었거나 지정되지 않았습니다. 현재 화면을 다시 조회한 뒤 해당 단계를 승인해 주세요.");
        if (alreadyDecided(request,step,user.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 처리한 승인 단계입니다.");
    }

    private void validatePolicyEligibility(
        DeployRequest request,
        ApprovalStep currentStep,
        SecurityUserDetails currentUser
    ) {
        ApprovalPolicy policy = request.getProject().getApprovalPolicy();
        if (!isAssigned(policy, currentStep, currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 승인 정책에서 처리 가능한 지정 승인자가 아닙니다.");
        }

        if (policy != null && !policy.isAllowSelfApproval()) {
            if (request.getRequestedBy() != null && request.getRequestedBy().getId().equals(currentUser.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "자기 승인 정책이 허용되지 않습니다.");
            }
        }
    }

    private boolean isEligibleForCurrentStep(DeployRequest request, SecurityUserDetails currentUser) {
        if (request == null || request.getStatus() != DeployStatus.APPROVAL_PENDING) {
            return false;
        }
        ApprovalPolicy policy = request.getProject().getApprovalPolicy();
        if (policy != null && !policy.isAllowSelfApproval()
            && request.getRequestedBy() != null
            && request.getRequestedBy().getId().equals(currentUser.getId())) {
            return false;
        }
        ApprovalStep approvalStep = resolveApprovalStep(request, currentUser.getId());
        return !alreadyDecided(request,approvalStep,currentUser.getId()) && isAssigned(policy, approvalStep, currentUser.getId());
    }

    private boolean isAssigned(ApprovalPolicy policy, ApprovalStep step, Long userId) {
        if (policy == null) {
            // 정책이 없던 기존 요청은 과거 동작을 유지한다. 신규 정책은 빈 승인자를 저장할 수 없다.
            return true;
        }
        if (policy.getSteps() == null || policy.getSteps().isEmpty()) {
            return false;
        }
        if (policy.getPolicyType() == PolicyType.STEP) {
            return hasActiveApprover(step, userId);
        }
        return policy.getSteps().stream().anyMatch(candidate -> hasActiveApprover(candidate, userId));
    }

    private void completeApprovalWhenSatisfied(DeployRequest request) {
        ApprovalPolicy policy = request.getProject().getApprovalPolicy();
        hydratePolicySteps(policy);
        if (policy == null || isPolicyCompleted(request, policy)) {
            transitionStatus(request, request.getEnvironment() == DeployEnvironment.TEST
                ? DeployStatus.TEST_REQUESTED : DeployStatus.APPROVAL_COMPLETED);
        }
    }

    private ApprovalStep resolveApprovalStep(DeployRequest request, Long userId) {
        ApprovalPolicy policy = request.getProject().getApprovalPolicy();
        hydratePolicySteps(policy);
        if (policy == null || policy.getSteps().isEmpty()) {
            return null;
        }
        if (policy.getPolicyType() != PolicyType.STEP) {
            return policy.getSteps().stream()
                .filter(step -> hasActiveApprover(step, userId))
                .findFirst()
                .orElse(null);
        }

        List<ApprovalRecord> records = approvalRecordRepository.findByDeployRequestId(request.getId());
        return policy.getSteps().stream()
            .filter(step -> approvedCount(records, step) < requiredCount(step))
            .findFirst()
            .orElse(policy.getSteps().get(policy.getSteps().size() - 1));
    }

    private boolean isPolicyCompleted(DeployRequest request, ApprovalPolicy policy) {
        List<ApprovalRecord> records = approvalRecordRepository.findByDeployRequestId(request.getId());
        Set<Long> approvedApproverIds = approvedApproverIds(records);
        Set<Long> activeApproverIds = activeApproverIds(policy);

        if (activeApproverIds.isEmpty()) {
            return false;
        }
        return switch (policy.getPolicyType()) {
            case ANY_ONE -> activeApproverIds.stream().anyMatch(approvedApproverIds::contains);
            case ALL -> approvedApproverIds.containsAll(activeApproverIds);
            case STEP -> policy.getSteps().stream()
                .allMatch(step -> approvedCount(records, step) >= requiredCount(step));
        };
    }

    private long approvedCount(List<ApprovalRecord> records, ApprovalStep step) {
        Set<Long> assignedApproverIds = activeApproverIds(step);
        return records.stream()
            .filter(record -> record.getStepOrder() == step.getStepOrder())
            .filter(record -> record.getAction() == ApprovalAction.APPROVED)
            .map(record -> record.getApprover() != null ? record.getApprover().getId() : null)
            .filter(assignedApproverIds::contains)
            .distinct()
            .count();
    }

    private Set<Long> approvedApproverIds(List<ApprovalRecord> records) {
        Set<Long> ids = new HashSet<>();
        records.stream()
            .filter(record -> record.getAction() == ApprovalAction.APPROVED)
            .filter(record -> record.getApprover() != null && record.getApprover().getId() != null)
            .forEach(record -> ids.add(record.getApprover().getId()));
        return ids;
    }

    private Set<Long> activeApproverIds(ApprovalPolicy policy) {
        Set<Long> ids = new HashSet<>();
        if (policy.getSteps() != null) {
            policy.getSteps().forEach(step -> ids.addAll(activeApproverIds(step)));
        }
        return ids;
    }

    private Set<Long> activeApproverIds(ApprovalStep step) {
        Set<Long> ids = new HashSet<>();
        if (step != null && step.getApprovers() != null) {
            step.getApprovers().stream()
                .filter(user -> user != null && user.getId() != null && user.isActive())
                .forEach(user -> ids.add(user.getId()));
        }
        return ids;
    }

    private boolean hasActiveApprover(ApprovalStep step, Long userId) {
        return userId != null && activeApproverIds(step).contains(userId);
    }

    private int requiredCount(ApprovalStep step) {
        return Math.max(step.getRequiredCount(), 1);
    }

    private void transitionStatus(DeployRequest request, DeployStatus target) {
        int updated;
        boolean approved = target == DeployStatus.APPROVAL_COMPLETED || target == DeployStatus.TEST_REQUESTED;
        if (approved) {
            updated = deployRequestRepository.compareAndSetApprovalStatus(
                request.getId(), DeployStatus.APPROVAL_PENDING, target, ApprovalDecisionSource.MANUAL);
        } else {
            updated = deployRequestRepository.compareAndSetStatus(
                request.getId(), DeployStatus.APPROVAL_PENDING, target);
        }
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "다른 처리로 배포 요청 상태가 변경되었습니다. 새로고침 후 다시 확인해 주세요.");
        }
        request.setStatus(target);
        if (approved) {
            request.setApprovalDecisionSource(ApprovalDecisionSource.MANUAL);
        }
        statusHistoryRepository.save(StatusHistory.builder()
            .deployRequest(request)
            .status(target)
            .build());
        if (approved) {
            eventPublisher.publishEvent(new DeploymentReadyEvent(request.getId()));
        }
    }

    private void hydratePolicySteps(ApprovalPolicy policy) {
        if (policy == null || policy.getId() == null || (policy.getSteps() != null && !policy.getSteps().isEmpty())) {
            return;
        }
        List<ApprovalStep> steps = approvalStepRepository.findByPolicyId(policy.getId());
        steps.forEach(step -> step.setApprovers(approvalStepRepository.findApproversByStepId(step.getId())));
        policy.setSteps(steps);
    }
}
