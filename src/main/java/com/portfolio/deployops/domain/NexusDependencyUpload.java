package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class NexusDependencyUpload {
    private String id;
    private Long projectId;
    private Long ownerId;
    private String repositoryName;
    private String repositoryFormat;
    private String nexusBaseUrl;
    private String targetPath;
    private long totalBytes;
    private long receivedBytes;
    private long publishedBytes;
    private String contentSha256;
    private String status;
    private String message;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
