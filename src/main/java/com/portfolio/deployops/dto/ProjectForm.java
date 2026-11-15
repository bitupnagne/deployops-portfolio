package com.portfolio.deployops.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.BuildConfigMode;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.PipelineType;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import java.util.List;

@Getter
@Setter
public class ProjectForm {
    private String name;
    private String description;
    private String gitlabRepoUrl;
    private Long gitlabProjectId;
    private String defaultBranch;
    private String mrTargetBranch;
    private String mrAssigneeGitlabUsername;
    private String mrReviewerGitlabUsernames;
    private Boolean autoMergeOnApproval;
    private Boolean deleteSourceBranchOnMerge;
    private PipelineType pipelineType;
    private BuildToolType buildTool;
    private BuildConfigMode buildConfigMode;
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
    private HealthCheckMode healthCheckMode;
    private String healthCheckPath;
    private Integer healthCheckPort;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(65535)
    private Integer wasVipPort;
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean wasVipPortProvided;
    @JsonSetter(nulls = Nulls.SET)
    public void setWasVipPort(Integer value) { wasVipPort = value; wasVipPortProvided = true; }
    public boolean hasWasVipPort() { return wasVipPortProvided; }
    private String remoteDeployScriptPath;
    private String dockerNetwork;
    private String envFilePath;
    private String containerPorts;
    private String containerUser;
    private Boolean containerPrivileged;
    private String containerCapAdd;
    private Boolean containerReadOnlyRootFs;
    private boolean active = true;
    private Long approvalPolicyId;
    private List<Long> serverGroupIds;

    // 런타임 설정은 요청에서 빠지면 유지하고, 명시적 null/빈 문자열이면 해제한다.
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean dockerNetworkProvided;
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean envFilePathProvided;
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean containerPortsProvided;
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean containerUserProvided;
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean containerCapAddProvided;

    @JsonSetter(nulls = Nulls.SET)
    public void setDockerNetwork(String dockerNetwork) {
        this.dockerNetwork = dockerNetwork;
        this.dockerNetworkProvided = true;
    }

    @JsonSetter(nulls = Nulls.SET)
    public void setEnvFilePath(String envFilePath) {
        this.envFilePath = envFilePath;
        this.envFilePathProvided = true;
    }

    @JsonSetter(nulls = Nulls.SET)
    public void setContainerPorts(String containerPorts) {
        this.containerPorts = containerPorts;
        this.containerPortsProvided = true;
    }

    @JsonSetter(nulls = Nulls.SET)
    public void setContainerUser(String containerUser) {
        this.containerUser = containerUser;
        this.containerUserProvided = true;
    }

    @JsonSetter(nulls = Nulls.SET)
    public void setContainerCapAdd(String containerCapAdd) {
        this.containerCapAdd = containerCapAdd;
        this.containerCapAddProvided = true;
    }

    public boolean hasDockerNetwork() { return dockerNetworkProvided; }
    public boolean hasEnvFilePath() { return envFilePathProvided; }
    public boolean hasContainerPorts() { return containerPortsProvided; }
    public boolean hasContainerUser() { return containerUserProvided; }
    public boolean hasContainerCapAdd() { return containerCapAddProvided; }
}
