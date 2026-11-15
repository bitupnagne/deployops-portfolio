package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.ContainerPortBindings;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ServerGroupRepository serverGroupRepository;
    private final ServerRepository serverRepository;
    private final ApprovalPolicyRepository approvalPolicyRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final ProjectImageHistoryService projectImageHistoryService;
    private final ProjectPermissionService projectPermissionService;
    private final DeployComponentRepository deployComponentRepository;

    @Transactional(readOnly = true)
    public List<Project> findAllActive() {
        return projectRepository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public List<Project> findVisibleActive(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser) || projectPermissionService.isProjectManager(currentUser)) {
            return findAllActive();
        }
        List<Long> projectIds = projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL
        );
        return projectIds.isEmpty() ? List.of() : projectRepository.findActiveByIds(projectIds);
    }

    /** 배포 요청 생성용 — 배포 요청(DEPLOY_REQUEST) 권한이 있는 활성 프로젝트만. 권한 없는 프로젝트는 목록에서 제외. */
    @Transactional(readOnly = true)
    public List<Project> findDeployRequestable(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return findAllActive();
        }
        LinkedHashSet<Long> projectIds = new LinkedHashSet<>();
        for (ProjectPermissionEnvironment environment : List.of(ProjectPermissionEnvironment.TEST, ProjectPermissionEnvironment.PROD)) {
            projectIds.addAll(projectPermissionService.findPermittedProjectIds(currentUser, ProjectPermissionType.DEPLOY_REQUEST, environment));
        }
        return projectIds.isEmpty() ? List.of() : projectRepository.findActiveByIds(new ArrayList<>(projectIds));
    }

    @Transactional(readOnly = true)
    public Map<Long, List<String>> requestEnvironments(List<Project> projects, SecurityUserDetails currentUser) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (Project project : projects) {
            result.put(project.getId(), List.of(ProjectPermissionEnvironment.TEST, ProjectPermissionEnvironment.PROD).stream()
                .filter(environment -> projectPermissionService.hasPermission(currentUser, project.getId(), ProjectPermissionType.DEPLOY_REQUEST, environment))
                .map(Enum::name).toList());
        }
        return result;
    }

    /** Only already permission-filtered requestable projects are exposed to the form. */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Integer>> requestTargetCounts(List<Project> projects) {
        Map<Long, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Project project : projects) {
            List<ServerGroup> groups = projectRepository.findServerGroupsByProjectId(project.getId());
            groups.forEach(group -> group.setServers(serverRepository.findByServerGroupId(group.getId())));
            Project targets = Project.builder().serverGroups(groups).build();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (DeployEnvironment env : DeployEnvironment.values()) {
                for (com.portfolio.deployops.domain.DeployScope scope : com.portfolio.deployops.domain.DeployScope.values()) {
                    counts.put(env.name() + "_" + scope.name(), EffectiveDeployTargets.resolve(targets, env, scope).size());
                }
            }
            result.put(project.getId(), counts);
        }
        return result;
    }

    /** MR 승인 대기 목록용 — MR 승인(MR_APPROVE) 권한이 있는 활성 프로젝트만. */
    @Transactional(readOnly = true)
    public List<Project> findMrApprovable(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return findAllActive();
        }
        List<Long> projectIds = projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.MR_APPROVE,
            ProjectPermissionEnvironment.ALL
        );
        return projectIds.isEmpty() ? List.of() : projectRepository.findActiveByIds(projectIds);
    }

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Project> findVisibleAll(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser) || projectPermissionService.isProjectManager(currentUser)) {
            return findAll();
        }
        List<Long> projectIds = projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL
        );
        return projectIds.isEmpty() ? List.of() : projectRepository.findAllByIds(projectIds);
    }

    @Transactional(readOnly = true)
    public Project findById(Long id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));
    }

    /** Replace a project's complete server-group target set after validating every ID. */
    @Transactional
    public List<Long> replaceServerGroupMappings(Long projectId, List<Long> serverGroupIds) {
        findById(projectId);
        if (serverGroupIds == null || serverGroupIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "서버 그룹 목록을 확인해 주세요.");
        }
        if (serverGroupIds.size() != new LinkedHashSet<>(serverGroupIds).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 서버 그룹을 중복 선택할 수 없습니다.");
        }
        List<Long> validatedIds = serverGroupIds.stream().sorted().toList();
        if (!validatedIds.isEmpty()) {
            List<Long> existingIds = serverGroupRepository.findAllById(validatedIds).stream()
                .map(ServerGroup::getId).sorted().toList();
            if (!existingIds.equals(validatedIds)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 서버 그룹이 포함되어 있습니다.");
            }
        }
        projectRepository.replaceServerGroupMappings(projectId, validatedIds);
        return validatedIds;
    }

    @Transactional(readOnly = true)
    public List<String> describeServerGroupMappings(List<Long> serverGroupIds) {
        if (serverGroupIds == null || serverGroupIds.isEmpty()) return List.of();
        return serverGroupRepository.findAllById(serverGroupIds).stream()
            .map(group -> group.getId() + ":" + group.getName() + ":" + group.getEnvironment())
            .toList();
    }

    @Transactional(readOnly = true)
    public Project findByIdWithDetails(Long id) {
        Project project = projectRepository.findByIdWithDetails(id)
            .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));
        attachProjectDetails(project);
        return project;
    }

    @Transactional(readOnly = true)
    public Project findByIdWithDetails(Long id, SecurityUserDetails currentUser) {
        projectPermissionService.assertPermission(currentUser, id, ProjectPermissionType.PROJECT_VIEW);
        return findByIdWithDetails(id);
    }

    @Transactional
    public Project create(ProjectForm form) {
        Project project = Project.builder()
            .name(form.getName())
            .description(form.getDescription())
            .gitlabRepoUrl(form.getGitlabRepoUrl())
            .gitlabProjectId(form.getGitlabProjectId())
            .defaultBranch(form.getDefaultBranch() != null ? form.getDefaultBranch() : "main")
            .mrTargetBranch(normalize(form.getMrTargetBranch()))
            .mrAssigneeGitlabUsername(normalize(form.getMrAssigneeGitlabUsername()))
            .mrReviewerGitlabUsernames(normalize(form.getMrReviewerGitlabUsernames()))
            .autoMergeOnApproval(Boolean.TRUE.equals(form.getAutoMergeOnApproval()))
            .deleteSourceBranchOnMerge(form.getDeleteSourceBranchOnMerge() == null
                || Boolean.TRUE.equals(form.getDeleteSourceBranchOnMerge()))
            .pipelineType(form.getPipelineType() != null ? form.getPipelineType()
                : com.portfolio.deployops.domain.PipelineType.DOCKER)
            .buildTool(form.getBuildTool() != null ? form.getBuildTool()
                : com.portfolio.deployops.domain.BuildToolType.NONE)
            .buildConfigMode(form.getBuildConfigMode() != null ? form.getBuildConfigMode()
                : com.portfolio.deployops.domain.BuildConfigMode.AUTO)
            .buildFilePath(form.getBuildFilePath())
            .buildCommand(form.getBuildCommand())
            .sourceEntryPath(form.getSourceEntryPath())
            .compilerOptions(form.getCompilerOptions())
            .runtimeBaseImage(form.getRuntimeBaseImage())
            .artifactPath(form.getArtifactPath())
            .dockerfilePath(form.getDockerfilePath())
            .dockerContextPath(form.getDockerContextPath())
            .imageRepository(form.getImageRepository())
            .imageTagPattern(form.getImageTagPattern())
            .nexusMavenReleaseRepository(form.getNexusMavenReleaseRepository())
            .nexusMavenSnapshotRepository(form.getNexusMavenSnapshotRepository())
            .nexusDockerRepository(form.getNexusDockerRepository())
            .healthCheckMode(form.getHealthCheckMode() != null ? form.getHealthCheckMode()
                : com.portfolio.deployops.domain.HealthCheckMode.SSH_LOCAL_CURL)
            .healthCheckPath(form.getHealthCheckPath())
            .healthCheckPort(form.getHealthCheckPort())
            .wasVipPort(validateWasVipPort(form.getWasVipPort()))
            .remoteDeployScriptPath(form.getRemoteDeployScriptPath())
            .dockerNetwork(normalize(form.getDockerNetwork()))
            .envFilePath(normalize(form.getEnvFilePath()))
            .containerPorts(normalizeContainerPorts(form.getContainerPorts()))
            .containerUser(normalize(form.getContainerUser()))
            .containerPrivileged(Boolean.TRUE.equals(form.getContainerPrivileged()))
            .containerCapAdd(normalize(form.getContainerCapAdd()))
            .containerReadOnlyRootFs(Boolean.TRUE.equals(form.getContainerReadOnlyRootFs()))
            .active(form.isActive())
            .build();

        assignApprovalPolicy(project, form.getApprovalPolicyId());
        Project saved = projectRepository.save(project);
        String containerName = com.portfolio.deployops.domain.ProjectContainerIdentity.canonical(saved.getId());
        if (projectRepository.initializeContainerName(saved.getId(), containerName) != 1) {
            throw new IllegalStateException("프로젝트 컨테이너 식별자를 고정하지 못했습니다.");
        }
        saved.setContainerName(containerName);
        if (form.getServerGroupIds() != null) {
            replaceServerGroupMappings(saved.getId(), form.getServerGroupIds());
        }
        attachProjectDetails(saved);
        return saved;
    }

    @Transactional
    public void deactivate(Long id) {
        Project project = findById(id);
        project.setActive(false);
        projectRepository.save(project);
    }

    @Transactional
    public Project update(Long id, ProjectForm form) {
        projectRepository.lockById(id);
        Project project = findById(id);
        String legacyNameToPreserve = project.getContainerName() == null
            && !java.util.Objects.equals(project.getName(), form.getName())
            ? com.portfolio.deployops.domain.ProjectContainerIdentity.resolve(project) : null;
        project.setName(form.getName());
        project.setDescription(form.getDescription());
        project.setGitlabRepoUrl(form.getGitlabRepoUrl());
        project.setGitlabProjectId(form.getGitlabProjectId());
        if (form.getDefaultBranch() != null) {
            project.setDefaultBranch(form.getDefaultBranch());
        }
        project.setMrTargetBranch(normalize(form.getMrTargetBranch()));
        project.setMrAssigneeGitlabUsername(normalize(form.getMrAssigneeGitlabUsername()));
        project.setMrReviewerGitlabUsernames(normalize(form.getMrReviewerGitlabUsernames()));
        if (form.getAutoMergeOnApproval() != null) {
            project.setAutoMergeOnApproval(Boolean.TRUE.equals(form.getAutoMergeOnApproval()));
        }
        if (form.getDeleteSourceBranchOnMerge() != null) {
            project.setDeleteSourceBranchOnMerge(Boolean.TRUE.equals(form.getDeleteSourceBranchOnMerge()));
        }
        if (form.getPipelineType() != null) {
            project.setPipelineType(form.getPipelineType());
        }
        if (form.getBuildTool() != null) {
            project.setBuildTool(form.getBuildTool());
        }
        if (form.getBuildConfigMode() != null) {
            project.setBuildConfigMode(form.getBuildConfigMode());
        }
        project.setBuildFilePath(form.getBuildFilePath());
        project.setBuildCommand(form.getBuildCommand());
        project.setSourceEntryPath(form.getSourceEntryPath());
        project.setCompilerOptions(form.getCompilerOptions());
        project.setRuntimeBaseImage(form.getRuntimeBaseImage());
        project.setArtifactPath(form.getArtifactPath());
        project.setDockerfilePath(form.getDockerfilePath());
        project.setDockerContextPath(form.getDockerContextPath());
        project.setImageRepository(form.getImageRepository());
        project.setImageTagPattern(form.getImageTagPattern());
        project.setNexusMavenReleaseRepository(form.getNexusMavenReleaseRepository());
        project.setNexusMavenSnapshotRepository(form.getNexusMavenSnapshotRepository());
        project.setNexusDockerRepository(form.getNexusDockerRepository());
        if (form.getHealthCheckMode() != null) {
            project.setHealthCheckMode(form.getHealthCheckMode());
        }
        project.setHealthCheckPath(form.getHealthCheckPath());
        project.setHealthCheckPort(form.getHealthCheckPort());
        if (form.hasWasVipPort()) project.setWasVipPort(validateWasVipPort(form.getWasVipPort()));
        project.setRemoteDeployScriptPath(form.getRemoteDeployScriptPath());
        if (form.hasDockerNetwork()) project.setDockerNetwork(normalize(form.getDockerNetwork()));
        if (form.hasEnvFilePath()) project.setEnvFilePath(normalize(form.getEnvFilePath()));
        if (form.hasContainerPorts()) project.setContainerPorts(normalizeContainerPorts(form.getContainerPorts()));
        if (form.hasContainerUser()) project.setContainerUser(normalize(form.getContainerUser()));
        if (form.getContainerPrivileged() != null) project.setContainerPrivileged(form.getContainerPrivileged());
        if (form.hasContainerCapAdd()) project.setContainerCapAdd(normalize(form.getContainerCapAdd()));
        if (form.getContainerReadOnlyRootFs() != null) project.setContainerReadOnlyRootFs(form.getContainerReadOnlyRootFs());
        project.setActive(form.isActive());

        assignApprovalPolicy(project, form.getApprovalPolicyId());
        if (legacyNameToPreserve != null) {
            if (projectRepository.initializeContainerName(id, legacyNameToPreserve) != 1) {
                throw new IllegalStateException("기존 컨테이너 이름을 보존하지 못했습니다. 프로젝트를 다시 조회하세요.");
            }
            project.setContainerName(legacyNameToPreserve);
        }
        Project saved = projectRepository.save(project);
        attachProjectDetails(saved);
        return saved;
    }

    private Integer validateWasVipPort(Integer value) {
        if (value != null && (value < 1 || value > 65535))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WAS VIP 서비스 포트는 1~65535로 입력하세요.");
        return value;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void assignApprovalPolicy(Project project, Long policyId) {
        if (policyId == null) {
            return;
        }
        ApprovalPolicy policy = approvalPolicyRepository.findById(policyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "승인 정책을 찾을 수 없습니다."));
        List<ApprovalStep> steps = approvalStepRepository.findByPolicyId(policyId);
        if (steps.isEmpty() || steps.stream().anyMatch(step ->
            step.getRequiredCount() < 1 || approvalStepRepository.findApproversByStepId(step.getId())
                .stream().filter(user -> canApproveProject(user, project.getId()))
                .count() < step.getRequiredCount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "각 승인 단계에 TEST·PROD 승인 권한을 가진 활성 승인자를 지정해 주세요.");
        }
        project.setApprovalPolicy(policy);
    }

    private boolean canApproveProject(User user, Long projectId) {
        if (!user.isActive()) {
            return false;
        }
        if (user.getRole() == UserRole.SUPER_ADMIN || user.getRole() == UserRole.APPROVER) {
            return true;
        }
        if (projectId == null) {
            return false;
        }
        SecurityUserDetails details = new SecurityUserDetails(user);
        return projectPermissionService.hasPermission(details, projectId,
                ProjectPermissionType.TEST_APPROVE, DeployEnvironment.TEST)
            && projectPermissionService.hasPermission(details, projectId,
                ProjectPermissionType.PROD_APPROVE, DeployEnvironment.PROD);
    }

    private String normalizeContainerPorts(String value) {
        try {
            return ContainerPortBindings.normalize(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private void attachProjectDetails(Project project) {
        // 서비스 상태·제어·로그도 배포 실행과 같은 산출물 종류를 판단할 수 있어야 한다.
        project.setComponents(deployComponentRepository.findByProjectId(project.getId()));
        List<ServerGroup> groups = projectRepository.findServerGroupsByProjectId(project.getId());
        groups.forEach(group -> group.setServers(serverRepository.findByServerGroupId(group.getId())));
        project.setServerGroups(groups);
        project.setImageHistories(projectImageHistoryService.findRecentByProjectId(project.getId()));

        if (project.getApprovalPolicy() != null && project.getApprovalPolicy().getId() != null) {
            ApprovalPolicy policy = project.getApprovalPolicy();
            List<ApprovalStep> steps = approvalStepRepository.findByPolicyId(policy.getId());
            for (ApprovalStep step : steps) {
                List<User> approvers = new ArrayList<>(approvalStepRepository.findApproversByStepId(step.getId()));
                step.setApprovers(approvers);
                step.setPolicy(policy);
            }
            policy.setSteps(steps);
            project.setApprovalPolicy(policy);
        }
    }
}
