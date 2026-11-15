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
public class Server {

    private Long id;
    private String name;
    private String ipAddress;
    private ServerEnvironment environment;
    private ServerType serverType;
    private ServerGroup serverGroup;

    @Builder.Default
    private ServerControlMode controlMode = ServerControlMode.AGENT;
    private String agentBindingId;

    public boolean isAgentControlled() { return controlMode == ServerControlMode.AGENT; }

    @Builder.Default
    // 예제 환경의 SSH 포트 기본값. 대상 서버의 설정에 맞게 변경할 수 있다.
    private int sshPort = 2022;

    private String deployAccount;

    @Builder.Default
    private AuthType authType = AuthType.SSH_KEY;

    private String deployPath;
    private String memo;
    private LocalDateTime lastCheckedAt;

    @Builder.Default
    private ServerStatus status = ServerStatus.UNKNOWN;

    @Builder.Default
    private int orderIndex = 0;

    /** 등록일. 이 컬럼이 생기기 전에 등록된 서버는 비어 있다 — 없는 날짜를 지어내지 않는다. */
    private java.time.LocalDateTime createdAt;

    @Builder.Default
    private boolean autoStart = false;
    private String startupService;
}
