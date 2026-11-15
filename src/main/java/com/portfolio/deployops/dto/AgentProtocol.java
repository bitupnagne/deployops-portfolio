package com.portfolio.deployops.dto;

import java.util.Map;

/** Worker API deliberately uses a small versioned protocol, separate from user sessions. */
public final class AgentProtocol {
    private AgentProtocol() { }
    public record Enrollment(Long serverId, String enrollmentCode, String role, String machineId,
                             String hostname, String version) { }
    public record Heartbeat(String hostname, String version, Map<String, Object> readiness) { }
    public record Result(String state, Integer exitCode, String stdout, String stderr, Long durationMs) { }
}
