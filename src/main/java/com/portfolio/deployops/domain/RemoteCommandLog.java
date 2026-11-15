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
public class RemoteCommandLog {

    private Long id;
    private Long deployRequestId;
    private Long targetAttemptId;
    private Long serverId;
    private String serverName;
    private String commandDisplay;
    private String stdoutText;
    private String stderrText;
    private Integer exitCode;
    private boolean success;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private LocalDateTime createdAt;
}
