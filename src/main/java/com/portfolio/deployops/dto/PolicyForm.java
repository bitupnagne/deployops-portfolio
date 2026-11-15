package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.PolicyType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PolicyForm {
    private String name;
    private PolicyType policyType;
    private boolean allowSelfApproval;
    private String description;
    private List<StepForm> steps;

    @Getter
    @Setter
    public static class StepForm {
        private int stepOrder;
        private int requiredCount;
        private List<Long> approverIds;
    }
}
