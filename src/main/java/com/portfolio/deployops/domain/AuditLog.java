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
public class AuditLog {

    private Long id;
    private String actor;
    private String action;
    private String targetType;
    private String targetId;
    private String targetDesc;
    private AuditResult result;
    private String ipAddress;
    private LocalDateTime createdAt;
    private String previousHash;
    private String entryHash;
}
