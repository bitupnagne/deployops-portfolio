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
public class ProjectImageHistory {

    private Long id;
    private Project project;
    private DeployRequest deployRequest;
    private DeployEnvironment environment;
    private String branch;
    private String imageRepository;
    private String imageTag;
    private String imageDigest;
    private Long gitlabPipelineId;
    private String commitSha;
    private ProjectImageHistoryStatus historyStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
