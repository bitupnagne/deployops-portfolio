package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
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
public class ApprovalRecord {

    private Long id;
    private DeployRequest deployRequest;
    private int stepOrder;
    private User approver;
    private ApprovalAction action;
    private String comment;
    private LocalDateTime processedAt;
}
