package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.PolicyType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.PolicyForm;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.ReferenceRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PolicyService {

    private final ApprovalPolicyRepository approvalPolicyRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final UserRepository userRepository;
    private final ReferenceRepository referenceRepository;

    @Transactional(readOnly = true)
    public List<ApprovalPolicy> findAll() {
        List<ApprovalPolicy> policies = approvalPolicyRepository.findAll();
        policies.forEach(this::attachSteps);
        return policies;
    }

    @Transactional(readOnly = true)
    public ApprovalPolicy findById(Long id) {
        ApprovalPolicy policy = approvalPolicyRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("승인 정책을 찾을 수 없습니다."));
        attachSteps(policy);
        return policy;
    }

    @Transactional
    public ApprovalPolicy create(PolicyForm form) {
        validatePolicyForm(form);
        ApprovalPolicy policy = ApprovalPolicy.builder()
            .name(form.getName())
            .policyType(form.getPolicyType())
            .allowSelfApproval(form.isAllowSelfApproval())
            .description(form.getDescription())
            .build();

        ApprovalPolicy saved = approvalPolicyRepository.save(policy);
        replaceSteps(saved, form.getSteps());
        attachSteps(saved);
        return saved;
    }

    @Transactional
    public ApprovalPolicy update(Long id, PolicyForm form) {
        validatePolicyForm(form);
        ApprovalPolicy policy = findById(id);
        policy.setName(form.getName());
        policy.setPolicyType(form.getPolicyType());
        policy.setAllowSelfApproval(form.isAllowSelfApproval());
        policy.setDescription(form.getDescription());

        ApprovalPolicy saved = approvalPolicyRepository.save(policy);
        replaceSteps(saved, form.getSteps());
        attachSteps(saved);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        ApprovalPolicy policy = findById(id);

        List<String> groups = referenceRepository.findServerGroupNamesByPolicyId(id);
        if (!groups.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "'" + policy.getName() + "' 정책을 사용하는 서버 그룹이 있습니다: " + String.join(", ",groups)
            + ". 최고 관리자가 해당 그룹의 추가 승인 정책을 먼저 변경해 주세요.");

        /*
          쓰는 프로젝트가 있으면 먼저 막는다.

          막지 않으면 단계와 승인자를 먼저 지운 뒤 마지막 DELETE 에서
          project.approval_policy_id FK 에 걸린다. 트랜잭션이라 되돌아가긴 하지만
          사용자에게는 DataIntegrityViolation 한 줄만 남아 무엇이 걸렸는지 알 수 없다.
          어느 프로젝트인지는 화면이 /api/v1/references/policy/{id} 로 펼쳐 준다.
        */
        List<String> projects = referenceRepository.findProjectsByPolicyId(id).stream()
            .map(com.portfolio.deployops.dto.ReferenceReport.Item::getName)
            .toList();
        if (!projects.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "'" + policy.getName() + "' 정책을 쓰는 프로젝트가 있어 삭제할 수 없습니다: "
                + String.join(", ", projects) + ". 해당 프로젝트에서 먼저 다른 정책을 지정해 주세요.");
        }

        approvalStepRepository.deleteApproversByPolicyId(policy.getId());
        approvalStepRepository.deleteByPolicyId(policy.getId());
        approvalPolicyRepository.delete(policy);
    }

    private void replaceSteps(ApprovalPolicy policy, List<PolicyForm.StepForm> stepForms) {
        approvalStepRepository.deleteApproversByPolicyId(policy.getId());
        approvalStepRepository.deleteByPolicyId(policy.getId());
        if (stepForms == null || stepForms.isEmpty()) {
            return;
        }

        for (PolicyForm.StepForm sf : stepForms) {
            ApprovalStep step = ApprovalStep.builder()
                .policy(policy)
                .stepOrder(sf.getStepOrder())
                .requiredCount(sf.getRequiredCount() > 0 ? sf.getRequiredCount() : 1)
                .build();
            approvalStepRepository.insert(step);

            if (sf.getApproverIds() != null) {
                for (Long uid : sf.getApproverIds()) {
                    approvalStepRepository.insertApprover(step.getId(), uid);
                }
            }
        }
    }

    private void validatePolicyForm(PolicyForm form) {
        if (form == null) {
            throw badRequest("승인 정책 입력값이 없습니다.");
        }
        if (form.getName() == null || form.getName().isBlank()) {
            throw badRequest("승인 정책명은 필수입니다.");
        }
        if (form.getPolicyType() == null) {
            throw badRequest("승인 방식을 선택해 주세요.");
        }
        if (form.getSteps() == null || form.getSteps().isEmpty()) {
            throw badRequest("승인 단계와 승인자를 한 명 이상 지정해 주세요.");
        }

        Set<Integer> stepOrders = new HashSet<>();
        Set<Long> allApproverIds = new LinkedHashSet<>();
        for (PolicyForm.StepForm step : form.getSteps()) {
            if (step == null || step.getStepOrder() <= 0) {
                throw badRequest("승인 단계 순서는 1 이상의 값이어야 합니다.");
            }
            if (!stepOrders.add(step.getStepOrder())) {
                throw badRequest("승인 단계 순서가 중복되었습니다: " + step.getStepOrder() + "단계");
            }
            if (step.getApproverIds() == null || step.getApproverIds().isEmpty()) {
                throw badRequest(step.getStepOrder() + "단계 승인자를 한 명 이상 지정해 주세요.");
            }

            Set<Long> stepApproverIds = new LinkedHashSet<>();
            for (Long approverId : step.getApproverIds()) {
                if (approverId == null) {
                    throw badRequest(step.getStepOrder() + "단계 승인자 정보가 올바르지 않습니다.");
                }
                if (!stepApproverIds.add(approverId)) {
                    throw badRequest(step.getStepOrder() + "단계에 같은 승인자가 중복 지정되었습니다.");
                }
                if (!allApproverIds.add(approverId)) {
                    throw badRequest("같은 승인자는 여러 단계에 중복 지정할 수 없습니다.");
                }
            }

            if (step.getRequiredCount() <= 0 || step.getRequiredCount() > stepApproverIds.size()) {
                throw badRequest(step.getStepOrder() + "단계 필수 승인 수는 1명 이상, 지정 승인자 수 이하여야 합니다.");
            }
            if (form.getPolicyType() == PolicyType.ANY_ONE && step.getRequiredCount() != 1) {
                throw badRequest("1명 승인 방식의 단계별 필수 승인 수는 1명이어야 합니다.");
            }
        }

        List<User> approvers = userRepository.findAllByIds(new ArrayList<>(allApproverIds));
        Map<Long, User> approverById = new HashMap<>();
        approvers.forEach(user -> approverById.put(user.getId(), user));
        for (Long approverId : allApproverIds) {
            User approver = approverById.get(approverId);
            if (approver == null) {
                throw badRequest("존재하지 않는 승인자가 지정되었습니다: 사용자 ID " + approverId);
            }
            if (!approver.isActive()) {
                throw badRequest("비활성 사용자는 승인자로 지정할 수 없습니다: " + approver.getName());
            }
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void attachSteps(ApprovalPolicy policy) {
        List<ApprovalStep> steps = approvalStepRepository.findByPolicyId(policy.getId());
        for (ApprovalStep step : steps) {
            List<User> approvers = new ArrayList<>(approvalStepRepository.findApproversByStepId(step.getId()));
            step.setApprovers(approvers);
            step.setPolicy(policy);
        }
        policy.setSteps(steps);
    }
}
