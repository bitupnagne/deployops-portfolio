package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NexusCleanupRequest {
    private Long id;
    private Project project;
    private User requestedBy;
    private User approvedBy;
    private NexusCleanupStatus status;
    private String repositoryPath;
    private String imageRepository;
    private String imageTag;
    private String imageDigest;
    private String reason;
    private String resultMessage;
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime completedAt;
}
