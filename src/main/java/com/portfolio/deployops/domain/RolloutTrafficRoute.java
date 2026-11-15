package com.portfolio.deployops.domain;

import java.util.List;

/** An approved managed nginx generation and its non-secret WEB connection settings. */
public record RolloutTrafficRoute(DeploymentPlanSnapshot.ServerSettings web, String policyName,
                                 Long nativeRequestId, String configSha256, List<Endpoint> upstreams) {
    public record Endpoint(String host, int port) { }
}
