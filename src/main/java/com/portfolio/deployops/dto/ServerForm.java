package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.AuthType;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerForm {
    private String name;
    private String ipAddress;
    private ServerEnvironment environment;
    private ServerType serverType;
    private com.portfolio.deployops.domain.ServerControlMode controlMode;
    private Long serverGroupId;
    private int sshPort; // Historical field: omitted agent-only forms must preserve the stored value.
    private String deployAccount;
    private AuthType authType;
    private String deployPath;
    private String memo;
    private int orderIndex;
    private boolean autoStart;
    private String startupService;
}
