package com.portfolio.deployops.domain;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalPolicy {

    private Long id;
    private String name;
    private PolicyType policyType;

    @Builder.Default
    private boolean allowSelfApproval = false;

    private String description;

    @Builder.Default
    private List<ApprovalStep> steps = new ArrayList<>();

    /** Original policies, present only for an immutable project + group composition. */
    private List<ApprovalPolicy> requiredPolicies;
    private Long sourceProjectPolicyId;

    public boolean isGroupComposed() {
        return requiredPolicies != null && !requiredPolicies.isEmpty();
    }
}
