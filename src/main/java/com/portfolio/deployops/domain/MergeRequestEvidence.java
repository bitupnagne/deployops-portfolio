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
public class MergeRequestEvidence {
    private Long id;
    private Long projectId;
    private Long gitlabProjectId;
    private Long mergeRequestIid;
    private String sourceSha;
    private String targetBranch;
    private String diffBaseSha;
    private String diffStartSha;
    private Long reviewerId;
    private Long reviewerGitlabUserId;
    private String reviewerGitlabUsername;
    private String tokenSource;
    private MergeRequestEvidenceStatus status;
    private String mergedResultSha;
    private Long mergerId;
    private LocalDateTime approvedAt;
    private LocalDateTime mergedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
