package com.portfolio.deployops.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 배포 요청을 승인할 때 보았던 실행 계획의 불변 사본.
 *
 * <p>프로젝트 설정은 요청 뒤에도 수정될 수 있으므로 요청 테이블이 현재 프로젝트만 참조해서는
 * "승인한 대상"과 "실제로 실행한 대상"이 달라질 수 있다. 이 값에는 시크릿의 내용은 넣지 않고,
 * 배포 결과를 바꾸는 서버·컴포넌트·런타임·승인 정책만 고정한다.
 */
public record DeploymentPlanSnapshot(
    int schemaVersion,
    Long projectId,
    String projectName,
    boolean projectActive,
    DeployEnvironment environment,
    DeployScope deployScope,
    DeployStrategy deployStrategy,
    ProjectSettings project,
    DeploymentRuntimeSettings runtimeSettings,
    @JsonInclude(JsonInclude.Include.NON_NULL) SourceSettings source,
    ReleaseSettings release,
    List<ServerGroupSettings> serverGroups,
    List<ComponentSettings> components,
    ApprovalPolicySettings approvalPolicy,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<ManagedRuntimeSettings> managedRuntime,
    @JsonInclude(JsonInclude.Include.NON_NULL) RollbackSettings rollback,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<RolloutTrafficRoute> trafficRoutes,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<StorageMountSettings> storageMounts
) {
    public DeploymentPlanSnapshot(int schemaVersion, Long projectId, String projectName, boolean projectActive,
        DeployEnvironment environment, DeployScope deployScope, DeployStrategy deployStrategy,
        ProjectSettings project, DeploymentRuntimeSettings runtimeSettings, SourceSettings source,
        ReleaseSettings release, List<ServerGroupSettings> serverGroups, List<ComponentSettings> components,
        ApprovalPolicySettings approvalPolicy, List<ManagedRuntimeSettings> managedRuntime, RollbackSettings rollback,
        List<RolloutTrafficRoute> trafficRoutes) {
        this(schemaVersion, projectId, projectName, projectActive, environment, deployScope, deployStrategy,
            project, runtimeSettings, source, release, serverGroups, components, approvalPolicy, managedRuntime, rollback, trafficRoutes, null);
    }
    public DeploymentPlanSnapshot(int schemaVersion, Long projectId, String projectName, boolean projectActive,
        DeployEnvironment environment, DeployScope deployScope, DeployStrategy deployStrategy,
        ProjectSettings project, DeploymentRuntimeSettings runtimeSettings, SourceSettings source,
        ReleaseSettings release, List<ServerGroupSettings> serverGroups, List<ComponentSettings> components,
        ApprovalPolicySettings approvalPolicy, List<ManagedRuntimeSettings> managedRuntime, RollbackSettings rollback) {
        this(schemaVersion, projectId, projectName, projectActive, environment, deployScope, deployStrategy,
            project, runtimeSettings, source, release, serverGroups, components, approvalPolicy, managedRuntime, rollback, null);
    }
    public DeploymentPlanSnapshot(int schemaVersion, Long projectId, String projectName, boolean projectActive,
        DeployEnvironment environment, DeployScope deployScope, DeployStrategy deployStrategy,
        ProjectSettings project, DeploymentRuntimeSettings runtimeSettings, SourceSettings source,
        ReleaseSettings release, List<ServerGroupSettings> serverGroups, List<ComponentSettings> components,
        ApprovalPolicySettings approvalPolicy, List<ManagedRuntimeSettings> managedRuntime) {
        this(schemaVersion, projectId, projectName, projectActive, environment, deployScope, deployStrategy,
            project, runtimeSettings, source, release, serverGroups, components, approvalPolicy, managedRuntime, null, null);
    }

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public record RollbackSettings(Long sourceRequestId, String currentConfigurationHash) { }

    public record ProjectSettings(
        Long gitlabProjectId,
        String defaultBranch,
        PipelineType pipelineType,
        RenderingType renderingType,
        BuildToolType buildTool,
        BuildConfigMode buildConfigMode,
        String buildFilePath,
        String buildCommand,
        String sourceEntryPath,
        String compilerOptions,
        String runtimeBaseImage,
        String artifactPath,
        String dockerfilePath,
        String dockerContextPath,
        String imageRepository,
        String imageTagPattern,
        String nexusMavenReleaseRepository,
        String nexusMavenSnapshotRepository,
        String nexusDockerRepository,
        HealthCheckMode healthCheckMode,
        String healthCheckPath,
        Integer healthCheckPort,
        String remoteDeployScriptPath,
        String dockerNetwork,
        String envFilePath,
        String containerUser,
        boolean containerPrivileged,
        String containerCapAdd,
        boolean containerReadOnlyRootFs,
        // 기존 schema 2 JSON은 이 키가 없었다. null을 생략해 기존 승인 해시를 유지한다.
        @JsonInclude(JsonInclude.Include.NON_NULL) String containerPorts,
        @JsonInclude(JsonInclude.Include.NON_NULL) String containerName,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer wasVipPort
    ) {
        public ProjectSettings(Long gitlabProjectId, String defaultBranch, PipelineType pipelineType,
            RenderingType renderingType, BuildToolType buildTool, BuildConfigMode buildConfigMode,
            String buildFilePath, String buildCommand, String sourceEntryPath, String compilerOptions,
            String runtimeBaseImage, String artifactPath, String dockerfilePath, String dockerContextPath,
            String imageRepository, String imageTagPattern, String nexusMavenReleaseRepository,
            String nexusMavenSnapshotRepository, String nexusDockerRepository, HealthCheckMode healthCheckMode,
            String healthCheckPath, Integer healthCheckPort, String remoteDeployScriptPath, String dockerNetwork,
            String envFilePath, String containerUser, boolean containerPrivileged, String containerCapAdd,
            boolean containerReadOnlyRootFs, String containerPorts, String containerName) {
            this(gitlabProjectId, defaultBranch, pipelineType, renderingType, buildTool, buildConfigMode,
                buildFilePath, buildCommand, sourceEntryPath, compilerOptions, runtimeBaseImage, artifactPath,
                dockerfilePath, dockerContextPath, imageRepository, imageTagPattern, nexusMavenReleaseRepository,
                nexusMavenSnapshotRepository, nexusDockerRepository, healthCheckMode, healthCheckPath,
                healthCheckPort, remoteDeployScriptPath, dockerNetwork, envFilePath, containerUser,
                containerPrivileged, containerCapAdd, containerReadOnlyRootFs, containerPorts, containerName, null);
        }
        public ProjectSettings(Long gitlabProjectId, String defaultBranch, PipelineType pipelineType,
            RenderingType renderingType, BuildToolType buildTool, BuildConfigMode buildConfigMode,
            String buildFilePath, String buildCommand, String sourceEntryPath, String compilerOptions,
            String runtimeBaseImage, String artifactPath, String dockerfilePath, String dockerContextPath,
            String imageRepository, String imageTagPattern, String nexusMavenReleaseRepository,
            String nexusMavenSnapshotRepository, String nexusDockerRepository, HealthCheckMode healthCheckMode,
            String healthCheckPath, Integer healthCheckPort, String remoteDeployScriptPath, String dockerNetwork,
            String envFilePath, String containerUser, boolean containerPrivileged, String containerCapAdd,
            boolean containerReadOnlyRootFs, String containerPorts) {
            this(gitlabProjectId, defaultBranch, pipelineType, renderingType, buildTool, buildConfigMode,
                buildFilePath, buildCommand, sourceEntryPath, compilerOptions, runtimeBaseImage, artifactPath,
                dockerfilePath, dockerContextPath, imageRepository, imageTagPattern, nexusMavenReleaseRepository,
                nexusMavenSnapshotRepository, nexusDockerRepository, healthCheckMode, healthCheckPath,
                healthCheckPort, remoteDeployScriptPath, dockerNetwork, envFilePath, containerUser,
                containerPrivileged, containerCapAdd, containerReadOnlyRootFs, containerPorts, null);
        }
    }

    public record ServerGroupSettings(
        Long id,
        String name,
        ServerEnvironment environment,
        int rollingIntervalSec,
        List<ServerSettings> servers,
        @JsonInclude(JsonInclude.Include.NON_NULL) GroupRolloutPolicySettings rolloutPolicy,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long approvalPolicyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) WasVipSettings wasVip
    ) {
        public ServerGroupSettings(Long id, String name, ServerEnvironment environment,
                                   int rollingIntervalSec, List<ServerSettings> servers, GroupRolloutPolicySettings rolloutPolicy, Long approvalPolicyId) {
            this(id, name, environment, rollingIntervalSec, servers, rolloutPolicy, approvalPolicyId, null);
        }
        public ServerGroupSettings(Long id, String name, ServerEnvironment environment,
                                   int rollingIntervalSec, List<ServerSettings> servers, GroupRolloutPolicySettings rolloutPolicy) {
            this(id, name, environment, rollingIntervalSec, servers, rolloutPolicy, null);
        }
        public ServerGroupSettings(Long id, String name, ServerEnvironment environment,
                                   int rollingIntervalSec, List<ServerSettings> servers) {
            this(id, name, environment, rollingIntervalSec, servers, null, null);
        }
    }

    public record WasVipSettings(boolean enabled, String address) { }

    public record GroupRolloutPolicySettings(int minAvailable, int maxUnavailable,
                                             int observationSeconds, GroupFailurePolicy failurePolicy) { }

    public record ServerSettings(
        Long id,
        String name,
        String ipAddress,
        ServerEnvironment environment,
        ServerType serverType,
        int sshPort,
        String deployAccount,
        AuthType authType,
        String deployPath,
        int orderIndex,
        boolean autoStart,
        String startupService,
        @JsonInclude(JsonInclude.Include.NON_NULL) ServerControlMode controlMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String agentBindingId
    ) {
        public ServerSettings(Long id, String name, String ipAddress, ServerEnvironment environment,
            ServerType serverType, int sshPort, String deployAccount, AuthType authType,
            String deployPath, int orderIndex, boolean autoStart, String startupService) {
            this(id, name, ipAddress, environment, serverType, sshPort, deployAccount, authType,
                deployPath, orderIndex, autoStart, startupService, null, null);
        }

        public ServerControlMode effectiveControlMode() {
            return controlMode == null ? ServerControlMode.SSH : controlMode;
        }
    }

    public record ComponentSettings(
        Long id,
        String code,
        String displayName,
        ServerType role,
        ArtifactType artifactType,
        String imageRepository,
        String webRoot,
        BuildToolType buildTool,
        String dockerfilePath,
        String dockerContextPath,
        String buildCommand,
        String artifactPath,
        HealthCheckMode healthCheckMode,
        String healthCheckPath,
        Integer healthCheckPort,
        String containerUser,
        boolean containerPrivileged,
        String containerCapAdd,
        boolean containerReadOnlyRootFs,
        int deployOrder,
        @JsonInclude(JsonInclude.Include.NON_NULL) String proxyHealthHost
    ) { }

    public record ReleaseSettings(
        Long gitlabPipelineId,
        String commitSha,
        String imageTag,
        String imageDigest,
        List<ArtifactItemSettings> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String manifestSha256
    ) {
        public ReleaseSettings(Long gitlabPipelineId, String commitSha, String imageTag,
                String imageDigest, List<ArtifactItemSettings> items) {
            this(gitlabPipelineId, commitSha, imageTag, imageDigest, items, null);
        }
    }

    public record SourceSettings(
        Long mergeRequestIid,
        Long evidenceId,
        String expectedCommitSha,
        String immutableBuildRef
    ) { }

    public record ArtifactItemSettings(
        Long componentId,
        String componentCode,
        String imageTag,
        String imageDigest,
        String bundleRef
    ) { }

    public record ApprovalPolicySettings(
        Long id,
        String name,
        PolicyType policyType,
        boolean allowSelfApproval,
        List<ApprovalStepSettings> steps,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long sourceProjectPolicyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ApprovalPolicySettings> requiredPolicies
    ) {
        public ApprovalPolicySettings(Long id,String name,PolicyType policyType,boolean allowSelfApproval,List<ApprovalStepSettings> steps) {
            this(id,name,policyType,allowSelfApproval,steps,null,null);
        }
    }

    public record ApprovalStepSettings(
        Long id,
        int stepOrder,
        int requiredCount,
        List<ApproverSettings> approvers,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long sourcePolicyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long sourceStepId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer sourceStepOrder
    ) {
        public ApprovalStepSettings(Long id,int stepOrder,int requiredCount,List<ApproverSettings> approvers) {
            this(id,stepOrder,requiredCount,approvers,null,null,null);
        }
    }

    public record ApproverSettings(
        Long id,
        String loginId,
        String name,
        UserRole role,
        boolean active
    ) { }
}
