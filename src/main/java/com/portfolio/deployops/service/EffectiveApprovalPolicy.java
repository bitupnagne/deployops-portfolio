package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Preserve each policy's requirement, then require every policy sequentially. */
public final class EffectiveApprovalPolicy {
    private EffectiveApprovalPolicy() { }

    public static boolean hasGroupPolicy(List<ServerGroup> groups) {
        return groups != null && groups.stream().anyMatch(group -> group.getApprovalPolicyId() != null);
    }

    public static ApprovalPolicy compose(ApprovalPolicy projectPolicy, List<ServerGroup> selectedGroups,
                                         Function<Long, ApprovalPolicy> resolver) {
        if (!hasGroupPolicy(selectedGroups)) return projectPolicy;
        LinkedHashMap<Long, ApprovalPolicy> policies = new LinkedHashMap<>();
        if (projectPolicy != null) {
            if (projectPolicy.getId() == null) throw invalid("프로젝트 승인 정책 식별자가 없습니다.");
            policies.put(projectPolicy.getId(),projectPolicy);
        }
        selectedGroups.stream().sorted(Comparator.comparing(ServerGroup::getId)).forEach(group -> {
            Long id = group.getApprovalPolicyId();
            if (id != null) {
                ApprovalPolicy policy = resolver.apply(id);
                if (policy == null || !Objects.equals(policy.getId(),id)) throw invalid("그룹 승인 정책을 찾을 수 없습니다.");
                if (policies.containsKey(id) && !shape(policies.get(id)).equals(shape(policy)))
                    throw invalid("같은 승인 정책의 내용이 조회 도중 변경되었습니다. 다시 요청해 주세요.");
                policies.putIfAbsent(id,policy);
            }
        });
        List<ApprovalPolicy> originals = List.copyOf(policies.values());
        ApprovalPolicy combined = ApprovalPolicy.builder()
            .name("프로젝트·서버 그룹 통합 승인")
            .policyType(PolicyType.STEP)
            .allowSelfApproval(originals.stream().allMatch(ApprovalPolicy::isAllowSelfApproval))
            .requiredPolicies(originals)
            .sourceProjectPolicyId(projectPolicy == null ? null : projectPolicy.getId())
            .build();
        List<ApprovalStep> steps = new ArrayList<>();
        for (ApprovalPolicy policy : originals) {
            if (policy.getPolicyType() == null || policy.getSteps() == null || policy.getSteps().isEmpty())
                throw invalid("승인 단계가 비어 있는 정책은 그룹 승인에 사용할 수 없습니다.");
            List<ApprovalStep> ordered = policy.getSteps().stream()
                .sorted(Comparator.comparingInt(ApprovalStep::getStepOrder)).toList();
            if (policy.getPolicyType() == PolicyType.STEP) {
                for (ApprovalStep step : ordered) addStep(combined,steps,step.getApprovers(),step.getRequiredCount(),
                    policy.getId(),step.getId(),step.getStepOrder());
            } else {
                LinkedHashMap<Long,User> users = new LinkedHashMap<>();
                ordered.forEach(step -> {
                    if (step.getApprovers() != null) step.getApprovers().forEach(user -> {
                        if (user != null && user.getId() != null) users.putIfAbsent(user.getId(),user);
                    });
                });
                int required = policy.getPolicyType() == PolicyType.ANY_ONE ? 1 :
                    (int) users.values().stream().filter(User::isActive).count();
                addStep(combined,steps,new ArrayList<>(users.values()),required,policy.getId(),null,null);
            }
        }
        combined.setSteps(steps);
        return combined;
    }

    public static void assertRequesterFeasible(ApprovalPolicy policy,Long requesterId) {
        if (policy == null || !policy.isGroupComposed() || policy.isAllowSelfApproval() || requesterId == null) return;
        for (ApprovalStep step : policy.getSteps()) {
            long eligible = step.getApprovers().stream().filter(User::isActive)
                .map(User::getId).filter(id -> !requesterId.equals(id)).distinct().count();
            if (eligible < step.getRequiredCount()) throw invalid("자기 승인 금지에 따라 요청자를 제외하면 필수 승인 인원을 충족할 수 없습니다.");
        }
    }

    private static Object shape(ApprovalPolicy policy) {
        return Arrays.asList(policy.getId(),policy.getName(),policy.getPolicyType(),policy.isAllowSelfApproval(),
            policy.getSteps() == null ? null : policy.getSteps().stream()
                .sorted(Comparator.comparingInt(ApprovalStep::getStepOrder))
                .map(step -> Arrays.asList(step.getId(),step.getStepOrder(),step.getRequiredCount(),
                    step.getApprovers() == null ? null : step.getApprovers().stream()
                        .sorted(Comparator.comparing(User::getId))
                        .map(user -> Arrays.asList(user.getId(),user.getLoginId(),user.getName(),user.getRole(),user.isActive())).toList())).toList());
    }

    private static void addStep(ApprovalPolicy policy,List<ApprovalStep> steps,List<User> users,int required,
                                Long sourcePolicyId,Long sourceStepId,Integer sourceStepOrder) {
        List<User> assigned = users == null ? List.of() : users.stream().filter(Objects::nonNull)
            .filter(user -> user.getId() != null).sorted(Comparator.comparing(User::getId)).toList();
        long active = assigned.stream().filter(User::isActive).map(User::getId).distinct().count();
        if (required < 1 || required > active) throw invalid("그룹 승인 정책의 활성 승인자 수가 필수 승인 수보다 적습니다.");
        steps.add(ApprovalStep.builder().policy(policy).stepOrder(steps.size()+1).requiredCount(required)
            .sourcePolicyId(sourcePolicyId).sourceStepId(sourceStepId).sourceStepOrder(sourceStepOrder)
            .approvers(assigned).build());
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}
