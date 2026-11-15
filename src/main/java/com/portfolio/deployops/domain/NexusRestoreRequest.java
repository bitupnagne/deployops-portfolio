package com.portfolio.deployops.domain;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class NexusRestoreRequest {
    private Long id,backupRequestId,requestedById,approvedById,confirmedById,activeCheckId;
    private String lastCheckStatus,lastCheckReason;
    private String artifactId,manifestSha256,reason,rejectionReason,status,stage,errorCode;
    private String cloneContainerId,cloneImageId,cloneName,cloneVolume,cloneNetwork,expectedArtifacts;
    private String cloneAccess;
    private String operatorChecklist,artifactConfirmations,requestedBy,approvedBy;
    private boolean submitted,originalProjectionPreserved,mutationUncertain;
    private LocalDateTime createdAt,approvedAt,completedAt,confirmedAt;
}
