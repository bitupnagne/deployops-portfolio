package com.portfolio.deployops.domain;

public enum HealthCheckMode {
    NONE,
    HTTP_DIRECT,
    SSH_LOCAL_CURL,
    CONTAINER_RUNNING
}
