package com.portfolio.deployops.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class TargetDeploymentAttempt {
    private Long id;
    private Long deployRequestId;
    private Long serverId;
    private String planHash;
    private String artifactHash;
}
