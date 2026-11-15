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
public class ApprovalStep {

    private Long id;
    private ApprovalPolicy policy;
    private int stepOrder;
    private int requiredCount;
    private Long sourcePolicyId;
    private Long sourceStepId;
    private Integer sourceStepOrder;

    @Builder.Default
    private List<User> approvers = new ArrayList<>();
}
