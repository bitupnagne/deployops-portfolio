package com.portfolio.deployops.service;

import java.time.LocalDateTime;

public record RemoteCommandResult(
    int exitCode,
    String stdout,
    String stderr,
    String commandDisplay,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    long durationMs
) {
    public String output() {
        if (stdout == null || stdout.isBlank()) {
            return stderr == null ? "" : stderr;
        }
        if (stderr == null || stderr.isBlank()) {
            return stdout;
        }
        return stdout + System.lineSeparator() + stderr;
    }
}
