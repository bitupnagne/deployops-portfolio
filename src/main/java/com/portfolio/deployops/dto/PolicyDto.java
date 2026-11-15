package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalStep;
import lombok.Getter;

import java.util.List;

@Getter
public class PolicyDto {
    private final Long id;
    private final String name;
    private final String policyType;
    private final boolean allowSelfApproval;
    private final String description;
    private final List<StepDto> steps;

    public PolicyDto(ApprovalPolicy p) {
        this.id = p.getId();
        this.name = p.getName();
        this.policyType = p.getPolicyType().name();
        this.allowSelfApproval = p.isAllowSelfApproval();
        this.description = p.getDescription();
        this.steps = p.getSteps().stream().map(StepDto::new).toList();
    }

    @Getter
    public static class StepDto {
        private final Long id;
        private final int stepOrder;
        private final int requiredCount;
        private final List<ApproverDto> approvers;

        public StepDto(ApprovalStep s) {
            this.id = s.getId();
            this.stepOrder = s.getStepOrder();
            this.requiredCount = s.getRequiredCount();
            this.approvers = s.getApprovers().stream().map(ApproverDto::new).toList();
        }
    }

    @Getter
    public static class ApproverDto {
        private final Long id;
        private final String name;

        public ApproverDto(com.portfolio.deployops.domain.User u) {
            this.id = u.getId();
            this.name = u.getName();
        }
    }
}
