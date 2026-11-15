package com.portfolio.deployops.domain;

import java.util.ArrayList;
import java.util.List;
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
public class Project {

    private Long id;
    private String name;
    /** Immutable runtime identity. Null preserves the pre-V48 name for existing installations. */
    private String containerName;
    private String description;
    private String gitlabRepoUrl;
    private Long gitlabProjectId;
    private String defaultBranch;
    private String mrTargetBranch;
    private String mrAssigneeGitlabUsername;
    private String mrReviewerGitlabUsernames;

    @Builder.Default
    private boolean autoMergeOnApproval = false;

    @Builder.Default
    private boolean deleteSourceBranchOnMerge = true;

    @Builder.Default
    private PipelineType pipelineType = PipelineType.DOCKER;

    @Builder.Default
    private RenderingType renderingType = RenderingType.SSR;

    @Builder.Default
    private BuildToolType buildTool = BuildToolType.NONE;

    @Builder.Default
    private BuildConfigMode buildConfigMode = BuildConfigMode.AUTO;

    private String buildFilePath;
    private String buildCommand;
    private String sourceEntryPath;
    private String compilerOptions;
    private String runtimeBaseImage;
    private String artifactPath;
    private String dockerfilePath;
    private String dockerContextPath;
    private String imageRepository;
    private String imageTagPattern;
    private String nexusMavenReleaseRepository;
    private String nexusMavenSnapshotRepository;
    private String nexusDockerRepository;

    @Builder.Default
    private HealthCheckMode healthCheckMode = HealthCheckMode.SSH_LOCAL_CURL;

    private String healthCheckPath;
    private Integer healthCheckPort;
    /** Service port on the group external L4 VIP, independent of the WAS health port. */
    private Integer wasVipPort;
    private String remoteDeployScriptPath;
    private String dockerNetwork;
    private String envFilePath;
    /** 정규화한 TCP 호스트:컨테이너 목록. null이면 기존 healthCheckPort 매핑을 사용한다. */
    private String containerPorts;
    /** Container hardening defaults. Empty user means image-defined user. */
    private String containerUser;
    private boolean containerPrivileged = false;
    private String containerCapAdd;
    private boolean containerReadOnlyRootFs = false;

    /** 요청 snapshot에서 복원한 시크릿 없는 전역 실행값. 일반 프로젝트 조회에서는 null이다. */
    private DeploymentRuntimeSettings deploymentRuntimeSettings;
    private List<ManagedRuntimeSettings> managedRuntimeSettings;
    /** Resolved per-server bind mounts from the approved request; absent for legacy requests. */
    private List<StorageMountSettings> storageMounts;
    /** Approved project-specific nginx routes. Populated only from the deployment snapshot. */
    private List<RolloutTrafficRoute> rolloutTrafficRoutes;

    @Builder.Default
    private List<ServerGroup> serverGroups = new ArrayList<>();

    @Builder.Default
    private List<DeployComponent> components = new ArrayList<>();

    @Builder.Default
    private List<ProjectImageHistory> imageHistories = new ArrayList<>();

    private ApprovalPolicy approvalPolicy;

    @Builder.Default
    private boolean active = true;
}
