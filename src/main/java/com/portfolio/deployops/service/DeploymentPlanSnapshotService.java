package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployRequestItem;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeploymentPlanSnapshot;
import com.portfolio.deployops.domain.DeploymentRuntimeSettings;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ReleaseVerificationStatus;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.DeployRequestItemRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 요청 당시의 실행 계획을 만들고 이후 설정 변경을 차단한다. */
@Service
@RequiredArgsConstructor
public class DeploymentPlanSnapshotService {

    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;
    private final ServerRepository serverRepository;
    private final DeployComponentRepository deployComponentRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final DeployRequestItemRepository deployRequestItemRepository;
    private final SystemSettingService systemSettingService;
    private final RuntimeConfigurationService runtimeConfigurationService;
    private RolloutTrafficService rolloutTrafficService;
    private com.portfolio.deployops.repository.ApprovalPolicyRepository approvalPolicyRepository;
    private ServerApprovalBoundaryService serverApprovalBoundaryService;
    private StorageMountService storageMountService;

    @org.springframework.beans.factory.annotation.Autowired
    void configureStorageMounts(StorageMountService service) { this.storageMountService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureServerApprovalBoundary(ServerApprovalBoundaryService service) {
        this.serverApprovalBoundaryService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureGroupApproval(com.portfolio.deployops.repository.ApprovalPolicyRepository repository) {
        this.approvalPolicyRepository = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureRolloutTraffic(RolloutTrafficService service) {
        this.rolloutTrafficService = service;
    }

    public void capture(DeployRequest request) {
        if (request == null || request.getProject() == null || request.getProject().getId() == null) {
            throw new IllegalArgumentException("배포 계획을 고정할 프로젝트가 없습니다.");
        }
        EncodedSnapshot encoded = encode(buildCurrent(request));
        request.setPlanSnapshotVersion(DeploymentPlanSnapshot.CURRENT_SCHEMA_VERSION);
        request.setPlanSnapshotJson(encoded.json());
        request.setPlanSnapshotHash(encoded.sha256());
    }

    /** Restore the source release's runtime contract while requiring a fresh approval of current targets. */
    public void captureRollback(DeployRequest request, DeployRequest source) {
        DeploymentPlanSnapshot original = verifiedSnapshot(source, true);
        if (!Objects.equals(original.projectId(), request.getProject().getId())
            || original.environment() != request.getEnvironment()) {
            throw conflict("롤백 원본의 프로젝트와 환경이 요청과 다릅니다.");
        }
        if (request.getSelectedServerGroupIds() == null || request.getSelectedServerGroupIds().isEmpty()) {
            var environment = request.getEnvironment() == DeployEnvironment.PROD
                ? com.portfolio.deployops.domain.ServerEnvironment.PROD : com.portfolio.deployops.domain.ServerEnvironment.TEST;
            request.setSelectedServerGroupIds(original.serverGroups().stream()
                .filter(group -> group.environment() == environment)
                .map(DeploymentPlanSnapshot.ServerGroupSettings::id).sorted().toList());
            if (request.getSelectedServerGroupIds().isEmpty()) throw conflict("롤백 원본에 해당 환경의 서버 그룹이 없습니다.");
        }
        DeploymentPlanSnapshot current = buildCurrent(request);
        assertRollbackVipCompatible(original, current);
        List<Long> originalGroups = original.serverGroups().stream().map(DeploymentPlanSnapshot.ServerGroupSettings::id).toList();
        if (current.serverGroups().stream().anyMatch(group -> !originalGroups.contains(group.id()))) {
            throw conflict("롤백 원본에 없던 서버 그룹에는 롤백할 수 없습니다.");
        }
        DeploymentPlanSnapshot rollback = new DeploymentPlanSnapshot(
            DeploymentPlanSnapshot.CURRENT_SCHEMA_VERSION, original.projectId(), original.projectName(),
            current.projectActive(), request.getEnvironment(), request.getDeployScope(), request.getDeployStrategy(),
            original.project(), original.runtimeSettings(), sourceSettings(request), releaseSettings(request),
            current.serverGroups(), original.components(), current.approvalPolicy(), original.managedRuntime(),
            new DeploymentPlanSnapshot.RollbackSettings(source.getId(), encode(current).sha256()), current.trafficRoutes(),
            rollbackStorage(original,current));
        EncodedSnapshot encoded = encode(rollback);
        request.setPlanSnapshotVersion(rollback.schemaVersion());
        request.setPlanSnapshotJson(encoded.json());
        request.setPlanSnapshotHash(encoded.sha256());
    }

    /**
     * TEST 파이프라인에서 검증된 이미지가 확정된 순간, 최초 계획의 나머지는 그대로 둔 채
     * release 부분만 한 번 결속한다. 설정을 다시 캡처하면 빌드 중 바뀐 설정을 승인 없이
     * 끼워 넣을 수 있으므로 저장된 snapshot을 복사해 release만 교체한다.
     */
    public void bindVerifiedRelease(DeployRequest request) {
        DeploymentPlanSnapshot stored = verifiedSnapshot(request, true);
        DeploymentPlanSnapshot.ReleaseSettings release = releaseSettings(request);
        if (hasReleaseMetadata(stored.release()) && !Objects.equals(stored.release(), release)) {
            throw conflict("이미 고정된 배포 산출물과 새 빌드 메타데이터가 다릅니다. 요청을 중단했습니다.");
        }
        DeploymentPlanSnapshot updated = new DeploymentPlanSnapshot(
            stored.schemaVersion(), stored.projectId(), stored.projectName(), stored.projectActive(),
            stored.environment(), stored.deployScope(), stored.deployStrategy(), stored.project(),
            stored.runtimeSettings(), stored.source(), release, stored.serverGroups(), stored.components(), stored.approvalPolicy(), stored.managedRuntime(), stored.rollback(), stored.trafficRoutes(), stored.storageMounts()
        );
        EncodedSnapshot encoded = encode(updated);
        request.setPlanSnapshotVersion(updated.schemaVersion());
        request.setPlanSnapshotJson(encoded.json());
        request.setPlanSnapshotHash(encoded.sha256());
    }

    /** A running CI may fill only its release slot, never update an approved build/target contract. */
    public void assertBuildConfigurationMatches(DeployRequest request) {
        DeploymentPlanSnapshot stored = verifiedSnapshot(request, true);
        DeploymentPlanSnapshot current = buildCurrent(request);
        DeploymentPlanSnapshot withoutPendingRelease = new DeploymentPlanSnapshot(
            current.schemaVersion(), current.projectId(), current.projectName(), current.projectActive(),
            current.environment(), current.deployScope(), current.deployStrategy(), current.project(),
            current.runtimeSettings(), current.source(), stored.release(), current.serverGroups(),
            current.components(), current.approvalPolicy(), current.managedRuntime(), current.rollback(), current.trafficRoutes(), current.storageMounts());
        if (!MessageDigest.isEqual(encode(stored).sha256().getBytes(StandardCharsets.US_ASCII),
                encode(withoutPendingRelease).sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("CI 실행 중 승인된 빌드·대상·승인 설정이 변경되어 산출물을 결속하지 않았습니다.");
        }
    }

    /**
     * 저장본 자체의 무결성과 현재 프로젝트 구성이 요청 당시와 같은지를 모두 확인한다.
     * 다르면 기존 승인을 재사용하지 않고 새 요청으로 다시 승인받아야 한다.
     */
    public void assertCurrentPlanMatches(DeployRequest request) {
        DeploymentPlanSnapshot stored = verifiedSnapshot(request, true);
        if (storageMountService != null) storageMountService.assertPinnedAllowed(stored.storageMounts());

        String currentHash = encode(buildCurrent(request)).sha256();
        if (!MessageDigest.isEqual(
            currentHash.getBytes(StandardCharsets.US_ASCII),
            (stored.rollback() == null ? encode(stored).sha256() : stored.rollback().currentConfigurationHash())
                .getBytes(StandardCharsets.US_ASCII)
        )) {
            throw conflict("요청 이후 배포 대상·컴포넌트·승인 정책 또는 런타임 설정이 변경되었습니다. 새 배포 요청을 생성해 다시 승인받아 주세요.");
        }
    }

    /**
     * 조회한 요청의 현재 프로젝트 join 결과를 요청 당시 snapshot으로 교체한다.
     * 실행 스레드가 다시 요청을 읽거나 프로젝트 설정이 직후 변경돼도 승인된 값만 사용한다.
     *
     * @return snapshot을 적용했으면 {@code true}, 구 요청처럼 snapshot이 없으면 {@code false}
     */
    public boolean applySnapshot(DeployRequest request) {
        DeploymentPlanSnapshot snapshot = verifiedSnapshot(request, false);
        if (snapshot == null) {
            return false;
        }
        if (!Objects.equals(snapshot.projectId(), request.getProject() != null ? request.getProject().getId() : null)
            || snapshot.environment() != request.getEnvironment()
            || snapshot.deployScope() != request.getDeployScope()
            || snapshot.deployStrategy() != request.getDeployStrategy()
            || !sourceMatchesRequest(snapshot.source(), request)
            || !releaseMatchesRequest(snapshot.release(), request)) {
            throw conflict("배포 요청의 기본 정보가 저장된 배포 계획과 일치하지 않습니다. 요청을 실행하지 않았습니다.");
        }
        if (request.getSelectedServerGroupIds() != null && !request.getSelectedServerGroupIds().isEmpty()
            && !request.getSelectedServerGroupIds().stream().sorted().toList().equals(
                snapshot.serverGroups().stream().map(DeploymentPlanSnapshot.ServerGroupSettings::id).sorted().toList())) {
            throw conflict("선택한 서버 그룹이 승인된 배포 계획과 일치하지 않습니다.");
        }
        request.setProject(materializeProject(snapshot));
        return true;
    }

    public DeploymentPlanSnapshot decode(String json) {
        try {
            return objectMapper.readValue(json, DeploymentPlanSnapshot.class);
        } catch (Exception e) {
            throw conflict("저장된 배포 계획을 읽을 수 없습니다. 요청을 실행하지 않았습니다.");
        }
    }

    private DeploymentPlanSnapshot verifiedSnapshot(DeployRequest request, boolean required) {
        if (request == null || request.getPlanSnapshotJson() == null || request.getPlanSnapshotJson().isBlank()
            || request.getPlanSnapshotHash() == null || request.getPlanSnapshotHash().isBlank()) {
            if (required) {
                throw conflict("이 요청은 배포 계획 고정 기능 도입 전에 생성되어 실행할 수 없습니다. 새 배포 요청을 생성해 주세요.");
            }
            return null;
        }
        String storedHash = sha256(request.getPlanSnapshotJson());
        if (!MessageDigest.isEqual(
            storedHash.getBytes(StandardCharsets.US_ASCII),
            request.getPlanSnapshotHash().getBytes(StandardCharsets.US_ASCII)
        )) {
            throw conflict("저장된 배포 계획의 무결성 검증에 실패했습니다. 요청을 실행하지 않았습니다.");
        }

        DeploymentPlanSnapshot stored = decode(request.getPlanSnapshotJson());
        if (stored.schemaVersion() != DeploymentPlanSnapshot.CURRENT_SCHEMA_VERSION
            || request.getPlanSnapshotVersion() == null
            || stored.schemaVersion() != request.getPlanSnapshotVersion()) {
            throw conflict("지원하지 않는 배포 계획 버전입니다. 새 배포 요청을 생성해 주세요.");
        }
        if (stored.project() == null || stored.runtimeSettings() == null
            || stored.release() == null || stored.release().items() == null
            || stored.serverGroups() == null || stored.components() == null) {
            throw conflict("저장된 배포 계획에 필수 실행 정보가 없습니다. 새 배포 요청을 생성해 주세요.");
        }
        return stored;
    }

    private Project materializeProject(DeploymentPlanSnapshot snapshot) {
        DeploymentPlanSnapshot.ProjectSettings p = snapshot.project();
        Project project = Project.builder()
            .id(snapshot.projectId())
            .name(snapshot.projectName())
            .containerName(p.containerName())
            .wasVipPort(p.wasVipPort())
            .active(snapshot.projectActive())
            .gitlabProjectId(p.gitlabProjectId())
            .defaultBranch(p.defaultBranch())
            .pipelineType(p.pipelineType())
            .renderingType(p.renderingType())
            .buildTool(p.buildTool())
            .buildConfigMode(p.buildConfigMode())
            .buildFilePath(p.buildFilePath())
            .buildCommand(p.buildCommand())
            .sourceEntryPath(p.sourceEntryPath())
            .compilerOptions(p.compilerOptions())
            .runtimeBaseImage(p.runtimeBaseImage())
            .artifactPath(p.artifactPath())
            .dockerfilePath(p.dockerfilePath())
            .dockerContextPath(p.dockerContextPath())
            .imageRepository(p.imageRepository())
            .imageTagPattern(p.imageTagPattern())
            .nexusMavenReleaseRepository(p.nexusMavenReleaseRepository())
            .nexusMavenSnapshotRepository(p.nexusMavenSnapshotRepository())
            .nexusDockerRepository(p.nexusDockerRepository())
            .healthCheckMode(p.healthCheckMode())
            .healthCheckPath(p.healthCheckPath())
            .healthCheckPort(p.healthCheckPort())
            .remoteDeployScriptPath(p.remoteDeployScriptPath())
            .dockerNetwork(p.dockerNetwork())
            .envFilePath(p.envFilePath())
            .containerPorts(p.containerPorts())
            .containerUser(p.containerUser())
            .containerPrivileged(p.containerPrivileged())
            .containerCapAdd(p.containerCapAdd())
            .containerReadOnlyRootFs(p.containerReadOnlyRootFs())
            .deploymentRuntimeSettings(snapshot.runtimeSettings())
            .managedRuntimeSettings(snapshot.managedRuntime())
            .build();

        List<ServerGroup> groups = snapshot.serverGroups().stream().map(groupSnapshot -> {
            ServerGroup group = ServerGroup.builder()
                .approvalPolicyId(groupSnapshot.approvalPolicyId())
                .wasVipEnabled(groupSnapshot.wasVip() == null ? null : groupSnapshot.wasVip().enabled())
                .wasVipAddress(groupSnapshot.wasVip() == null ? null : groupSnapshot.wasVip().address())
                .id(groupSnapshot.id())
                .name(groupSnapshot.name())
                .environment(groupSnapshot.environment())
                .rollingIntervalSec(groupSnapshot.rollingIntervalSec())
                .minAvailable(groupSnapshot.rolloutPolicy() == null ? 0 : groupSnapshot.rolloutPolicy().minAvailable())
                .maxUnavailable(groupSnapshot.rolloutPolicy() == null ? 1 : groupSnapshot.rolloutPolicy().maxUnavailable())
                .observationSeconds(groupSnapshot.rolloutPolicy() == null ? 0 : groupSnapshot.rolloutPolicy().observationSeconds())
                .failurePolicy(groupSnapshot.rolloutPolicy() == null ? com.portfolio.deployops.domain.GroupFailurePolicy.STOP
                    : groupSnapshot.rolloutPolicy().failurePolicy())
                .build();
            group.setServers(groupSnapshot.servers().stream().map(server -> Server.builder()
                .id(server.id())
                .name(server.name())
                .ipAddress(server.ipAddress())
                .environment(server.environment())
                .serverType(server.serverType())
                .serverGroup(group)
                .sshPort(server.sshPort())
                .deployAccount(server.deployAccount())
                .authType(server.authType())
                .controlMode(server.effectiveControlMode())
                .agentBindingId(server.agentBindingId())
                .deployPath(server.deployPath())
                .orderIndex(server.orderIndex())
                .autoStart(server.autoStart())
                .startupService(server.startupService())
                .build()).toList());
            return group;
        }).toList();
        project.setServerGroups(groups);
        project.setComponents(snapshot.components().stream().map(component -> DeployComponent.builder()
            .id(component.id())
            .projectId(snapshot.projectId())
            .code(component.code())
            .displayName(component.displayName())
            .role(component.role())
            .artifactType(component.artifactType())
            .imageRepository(component.imageRepository())
            .webRoot(component.webRoot())
            .buildTool(component.buildTool())
            .dockerfilePath(component.dockerfilePath())
            .dockerContextPath(component.dockerContextPath())
            .buildCommand(component.buildCommand())
            .artifactPath(component.artifactPath())
            .healthCheckMode(component.healthCheckMode())
            .healthCheckPath(component.healthCheckPath())
            .healthCheckPort(component.healthCheckPort())
            .proxyHealthHost(component.proxyHealthHost())
            .containerUser(component.containerUser())
            .containerPrivileged(component.containerPrivileged())
            .containerCapAdd(component.containerCapAdd())
            .containerReadOnlyRootFs(component.containerReadOnlyRootFs())
            .deployOrder(component.deployOrder())
            .active(true)
            .build()).toList());
        project.setApprovalPolicy(materializeApprovalPolicy(snapshot.approvalPolicy()));
        project.setRolloutTrafficRoutes(snapshot.trafficRoutes());
        project.setStorageMounts(snapshot.storageMounts());
        return project;
    }

    private ApprovalPolicy materializeApprovalPolicy(DeploymentPlanSnapshot.ApprovalPolicySettings snapshot) {
        if (snapshot == null) {
            return null;
        }
        ApprovalPolicy policy = ApprovalPolicy.builder()
            .id(snapshot.id())
            .name(snapshot.name())
            .policyType(snapshot.policyType())
            .allowSelfApproval(snapshot.allowSelfApproval())
            .sourceProjectPolicyId(snapshot.sourceProjectPolicyId())
            .requiredPolicies(snapshot.requiredPolicies() == null ? null :
                snapshot.requiredPolicies().stream().map(this::materializeApprovalPolicy).toList())
            .build();
        policy.setSteps(snapshot.steps().stream().map(step -> ApprovalStep.builder()
            .id(step.id())
            .policy(policy)
            .stepOrder(step.stepOrder())
            .requiredCount(step.requiredCount())
            .sourcePolicyId(step.sourcePolicyId()).sourceStepId(step.sourceStepId()).sourceStepOrder(step.sourceStepOrder())
            .approvers(step.approvers().stream().map(user -> User.builder()
                .id(user.id())
                .loginId(user.loginId())
                .name(user.name())
                .role(user.role())
                .active(user.active())
                .build()).toList())
            .build()).toList());
        return policy;
    }

    private DeploymentPlanSnapshot buildCurrent(DeployRequest request) {
        Long projectId = request.getProject().getId();
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> conflict("배포 요청의 프로젝트를 찾을 수 없습니다."));

        List<ServerGroup> groups = projectRepository.findServerGroupsByProjectId(projectId);
        if (request.getSelectedServerGroupIds() != null && !request.getSelectedServerGroupIds().isEmpty()) {
            Project mapped = Project.builder().serverGroups(groups).build();
            groups = EffectiveDeployTargets.selectGroups(mapped, request.getEnvironment(), request.getSelectedServerGroupIds());
        }
        groups.forEach(group -> group.setServers(serverRepository.findByServerGroupId(group.getId())));
        List<DeployComponent> components = deployComponentRepository.findActiveByProjectId(projectId);
        var expectedEnvironment = request.getEnvironment() == DeployEnvironment.PROD
            ? com.portfolio.deployops.domain.ServerEnvironment.PROD : com.portfolio.deployops.domain.ServerEnvironment.TEST;
        assertServerApprovalBoundary(groups,request.getEnvironment(),request.getDeployScope());
        ApprovalPolicy policy = currentApprovalPolicy(project,
            groups.stream().filter(group -> group.getEnvironment() == expectedEnvironment).toList(),request.getRequestedBy());

        return new DeploymentPlanSnapshot(
            DeploymentPlanSnapshot.CURRENT_SCHEMA_VERSION,
            project.getId(),
            project.getName(),
            project.isActive(),
            request.getEnvironment(),
            request.getDeployScope(),
            request.getDeployStrategy(),
            projectSettings(project),
            runtimeSettings(),
            sourceSettings(request),
            releaseSettings(request),
            groups.stream().map(this::serverGroupSettings).toList(),
            components.stream().map(component -> componentSettings(component, request.getEnvironment())).toList(),
            approvalPolicySettings(policy),
            managedRuntimeSettings(project, request.getEnvironment(), components), null,
            rolloutTrafficService == null ? null : rolloutTrafficService.describeRoutes(projectId, request.getEnvironment(), groups, request.getDeployScope()),
            storageMountService == null ? null : storageMountService.describe(project,request.getEnvironment(),groups,components,request.getDeployScope())
        );
    }

    private List<com.portfolio.deployops.domain.StorageMountSettings> rollbackStorage(DeploymentPlanSnapshot original,DeploymentPlanSnapshot current) {
        if(original.storageMounts()==null || original.storageMounts().isEmpty()) return null;
        Project targets=materializeProject(current);
        var was=EffectiveDeployTargets.resolve(targets,current.environment(),current.deployScope()).stream()
            .filter(s->s.getServerType()==com.portfolio.deployops.domain.ServerType.WAS).map(Server::getId).toList();
        var expectedComponents=original.storageMounts().stream().map(com.portfolio.deployops.domain.StorageMountSettings::componentId).distinct().toList();
        for(Long server:was) for(Long component:expectedComponents) {
            if(original.storageMounts().stream().noneMatch(m->Objects.equals(m.serverId(),server)&&Objects.equals(m.componentId(),component)))
                throw conflict("롤백 원본에 저장소 설정이 없는 WAS 서버에는 복원할 수 없습니다.");
        }
        var mounts=original.storageMounts().stream().filter(m->was.contains(m.serverId())).toList();
        if(storageMountService!=null) storageMountService.assertPinnedAllowed(mounts);
        return mounts.isEmpty()?null:mounts;
    }

    private List<com.portfolio.deployops.domain.ManagedRuntimeSettings> managedRuntimeSettings(Project project, DeployEnvironment env, List<DeployComponent> components) {
        List<com.portfolio.deployops.domain.ManagedRuntimeSettings> settings = runtimeConfigurationService.bindings(project.getId(),env);
        if (settings.isEmpty()) return null;
        if (project.getEnvFilePath()!=null && !project.getEnvFilePath().isBlank()
            && settings.stream().anyMatch(s -> "MANAGED_REVISION".equals(s.mode()))) throw conflict("관리 설정과 기존 환경 파일 경로를 함께 사용할 수 없습니다.");
        for(var component:components) {
            var selected=RuntimeConfigurationService.resolve(settings,component.getId());
            if (selected != null && (selected.persistentLogs() || selected.tmpMiB()>0) && component.getArtifactType()==com.portfolio.deployops.domain.ArtifactType.CONTAINER) {
                String user=component.getContainerUser()!=null?component.getContainerUser():project.getContainerUser();
                String caps=component.getContainerCapAdd()!=null?component.getContainerCapAdd():project.getContainerCapAdd();
                if(user==null || !user.matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,9}") || component.isContainerPrivileged()
                    || caps!=null && !caps.isBlank()) throw conflict("관리 저장소에는 숫자 UID:GID와 제한된 컨테이너 권한이 필요합니다.");
            }
            if (selected != null && ("MANAGED_REVISION".equals(selected.mode()) || selected.persistentLogs() || selected.tmpMiB()>0)
                && component.getArtifactType()==com.portfolio.deployops.domain.ArtifactType.CONTAINER
                && components.stream().filter(c->c.getRole()==component.getRole()).count()>1) throw conflict("같은 서버 역할의 배포 단위를 하나로 정리한 뒤 관리 설정을 적용해 주세요.");
        }
        if(components.isEmpty()) {
            var selected=RuntimeConfigurationService.resolve(settings,0L);
            if(selected!=null && (selected.persistentLogs() || selected.tmpMiB()>0)
                && (project.getContainerUser()==null || !project.getContainerUser().matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,9}")
                || project.isContainerPrivileged() || project.getContainerCapAdd()!=null && !project.getContainerCapAdd().isBlank())) throw conflict("관리 저장소에는 숫자 UID:GID와 제한된 컨테이너 권한이 필요합니다.");
        }
        return settings;
    }

    private DeploymentPlanSnapshot.SourceSettings sourceSettings(DeployRequest request) {
        if (request.getSourceMrIid() == null && request.getMrEvidenceId() == null
            && request.getExpectedCommitSha() == null && request.getImmutableBuildRef() == null) {
            return null;
        }
        return new DeploymentPlanSnapshot.SourceSettings(
            request.getSourceMrIid(), request.getMrEvidenceId(), request.getExpectedCommitSha(),
            request.getImmutableBuildRef());
    }

    private DeploymentRuntimeSettings runtimeSettings() {
        return new DeploymentRuntimeSettings(
            setting(RemoteDeployService.IMAGE_TRANSFER_SETTING, "registry"),
            setting(RemoteDeployService.STAGING_DIR_SETTING, "/var/tmp/deploy-ops"),
            setting(RemoteDeployService.STATIC_IMAGE_PATH_SETTING, "/dist"),
            setting(RemoteDeployService.CONTAINER_MEMORY_SETTING, "2g"),
            setting("nexus.url", ""),
            setting("nexus.registry", ""),
            setting("nexus.registry.api-url", ""),
            Boolean.parseBoolean(setting("nexus.pipeline-credentials", "false")),
            optionalMavenBaseUrl()
        );
    }

    private String optionalMavenBaseUrl() {
        String value = NexusMavenEndpoint.normalizeOptional(setting("nexus.maven.base-url", ""));
        return value.isBlank() ? null : value;
    }

    private DeploymentPlanSnapshot.ReleaseSettings releaseSettings(DeployRequest request) {
        List<DeployRequestItem> items = request.getId() == null
            ? safeItems(request.getItems())
            : safeItems(deployRequestItemRepository.findByDeployRequestId(request.getId()));
        List<DeploymentPlanSnapshot.ArtifactItemSettings> itemSnapshots = items.stream()
            .sorted(Comparator
                .comparing(DeployRequestItem::getComponentId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(DeployRequestItem::getComponentCode, Comparator.nullsLast(String::compareTo)))
            .map(item -> new DeploymentPlanSnapshot.ArtifactItemSettings(
                item.getComponentId(), item.getComponentCode(), item.getImageTag(),
                item.getImageDigest(), item.getBundleRef()
            ))
            .toList();
        return new DeploymentPlanSnapshot.ReleaseSettings(
            request.getGitlabPipelineId(), request.getCommitSha(), request.getImageTag(),
            request.getImageDigest(), itemSnapshots, request.getReleaseManifestSha256()
        );
    }

    private boolean releaseMatchesRequest(DeploymentPlanSnapshot.ReleaseSettings stored, DeployRequest request) {
        if (stored == null) {
            return false;
        }
        DeploymentPlanSnapshot.ReleaseSettings current = releaseSettings(request);
        boolean metadataMatches = Objects.equals(stored.gitlabPipelineId(), current.gitlabPipelineId())
            && Objects.equals(stored.commitSha(), current.commitSha())
            && Objects.equals(stored.imageTag(), current.imageTag())
            && Objects.equals(stored.imageDigest(), current.imageDigest())
            && Objects.equals(stored.manifestSha256(), current.manifestSha256());
        if (metadataMatches && Objects.equals(stored.items(), current.items())) {
            return true;
        }
        // GitLab creates and binds its pipeline ID before the artifact can be verified. During
        // that interval only this ID may differ; the verified release is bound atomically later.
        return request.getEnvironment() == DeployEnvironment.TEST
            && (request.getStatus() == DeployStatus.TEST_BUILDING
                || request.getStatus() == DeployStatus.TEST_FAILED)
            && request.getReleaseVerificationStatus() == ReleaseVerificationStatus.UNVERIFIED
            && stored.gitlabPipelineId() == null
            && current.gitlabPipelineId() != null
            && stored.commitSha() == null && current.commitSha() == null
            && stored.imageTag() == null && current.imageTag() == null
            && stored.imageDigest() == null && current.imageDigest() == null
            && Objects.equals(stored.items(), current.items())
            && stored.manifestSha256() == null && current.manifestSha256() == null;
    }

    private boolean sourceMatchesRequest(DeploymentPlanSnapshot.SourceSettings stored, DeployRequest request) {
        DeploymentPlanSnapshot.SourceSettings current = sourceSettings(request);
        return Objects.equals(stored, current);
    }

    private boolean hasReleaseMetadata(DeploymentPlanSnapshot.ReleaseSettings release) {
        return release != null && (release.gitlabPipelineId() != null
            || release.commitSha() != null || release.imageTag() != null || release.imageDigest() != null
            || release.manifestSha256() != null || (release.items() != null && !release.items().isEmpty()));
    }

    private List<DeployRequestItem> safeItems(List<DeployRequestItem> items) {
        return items == null ? List.of() : items;
    }

    private String setting(String key, String defaultValue) {
        String value = systemSettingService.get(key, defaultValue);
        return value == null ? defaultValue : value.trim();
    }

    /** Reject omitted protected host aliases using only the effective environment and deployment scope. */
    public void assertServerApprovalBoundary(List<ServerGroup> hydratedGroups, DeployEnvironment environment,
                                            com.portfolio.deployops.domain.DeployScope scope) {
        if (serverApprovalBoundaryService == null) return; // Required Spring injection; supports isolated legacy constructor fixtures.
        var targetEnvironment = environment == DeployEnvironment.PROD
            ? com.portfolio.deployops.domain.ServerEnvironment.PROD : com.portfolio.deployops.domain.ServerEnvironment.TEST;
        serverApprovalBoundaryService.assertSelectedTargetsCovered(
            EffectiveDeployTargets.resolve(Project.builder().serverGroups(hydratedGroups).build(),environment,scope),
            hydratedGroups.stream().filter(group -> group.getEnvironment() == targetEnvironment)
                .map(ServerGroup::getId).toList());
    }

    /** Resolve current project and selected-group approval without persisting or changing a request snapshot. */
    public ApprovalPolicy currentApprovalPolicy(Project project, List<ServerGroup> selectedGroups, User requester) {
        ApprovalPolicy policy = EffectiveApprovalPolicy.compose(hydratePolicy(project.getApprovalPolicy()),selectedGroups,
            id -> {
                if (approvalPolicyRepository == null) throw conflict("그룹 승인 정책 저장소를 사용할 수 없습니다.");
                return hydratePolicy(approvalPolicyRepository.findById(id)
                    .orElseThrow(() -> conflict("그룹 승인 정책을 찾을 수 없습니다.")));
            });
        EffectiveApprovalPolicy.assertRequesterFeasible(policy,requester == null ? null : requester.getId());
        return policy;
    }

    private ApprovalPolicy hydratePolicy(ApprovalPolicy policy) {
        if (policy == null || policy.getId() == null) {
            return policy;
        }
        List<ApprovalStep> steps = approvalStepRepository.findByPolicyId(policy.getId());
        steps.forEach(step -> step.setApprovers(approvalStepRepository.findApproversByStepId(step.getId())));
        policy.setSteps(steps);
        return policy;
    }

    private void assertRollbackVipCompatible(DeploymentPlanSnapshot original, DeploymentPlanSnapshot current) {
        if (current.deployScope() == com.portfolio.deployops.domain.DeployScope.WEB_ONLY) return;
        if (WasVipPolicy.portlessWorker(materializeProject(original)) && WasVipPolicy.portlessWorker(materializeProject(current))) return;
        boolean vipInvolved = java.util.stream.Stream.concat(original.serverGroups().stream(), current.serverGroups().stream())
            .anyMatch(g -> g.wasVip() != null && g.wasVip().enabled());
        if (!vipInvolved) return;
        var oldProject = original.project(); var nowProject = current.project();
        if (!Objects.equals(oldProject.wasVipPort(), nowProject.wasVipPort())
            || !Objects.equals(oldProject.dockerNetwork(), nowProject.dockerNetwork())
            || !Objects.equals(oldProject.containerPorts(), nowProject.containerPorts())
            || !rollbackWasEndpoints(original).equals(rollbackWasEndpoints(current)))
            throw conflict("VIP 관련 롤백의 원본·현재 서비스 VIP 포트, WAS 공개 포트 또는 개별 헬스체크 설정이 다릅니다. 원본과 정합한 설정·연결 방식으로 맞춘 뒤 새 롤백을 요청하세요. 외부 L4의 멤버·포트 매핑은 운영 담당자가 별도로 확인해야 합니다.");
    }

    private java.util.Map<Long, java.util.List<Object>> rollbackWasEndpoints(DeploymentPlanSnapshot plan) {
        var result = new java.util.TreeMap<Long, java.util.List<Object>>();
        var p = plan.project();
        for (var component : plan.components()) {
            if (component.role() != com.portfolio.deployops.domain.ServerType.WAS) continue;
            result.put(component.id(), java.util.Arrays.asList(
                component.healthCheckPort() == null ? p.healthCheckPort() : component.healthCheckPort(),
                component.healthCheckMode() == null ? p.healthCheckMode() : component.healthCheckMode(),
                component.healthCheckPath() == null ? p.healthCheckPath() : component.healthCheckPath()));
        }
        if (result.isEmpty()) result.put(0L, java.util.Arrays.asList(p.healthCheckPort(), p.healthCheckMode(), p.healthCheckPath()));
        return result;
    }

    private DeploymentPlanSnapshot.ProjectSettings projectSettings(Project p) {
        return new DeploymentPlanSnapshot.ProjectSettings(
            p.getGitlabProjectId(), p.getDefaultBranch(), p.getPipelineType(), p.getRenderingType(),
            p.getBuildTool(), p.getBuildConfigMode(), p.getBuildFilePath(), p.getBuildCommand(),
            p.getSourceEntryPath(), p.getCompilerOptions(), p.getRuntimeBaseImage(), p.getArtifactPath(),
            p.getDockerfilePath(), p.getDockerContextPath(), p.getImageRepository(), p.getImageTagPattern(),
            p.getNexusMavenReleaseRepository(), p.getNexusMavenSnapshotRepository(), p.getNexusDockerRepository(),
            p.getHealthCheckMode(), p.getHealthCheckPath(), p.getHealthCheckPort(), p.getRemoteDeployScriptPath(),
            p.getDockerNetwork(), p.getEnvFilePath(), p.getContainerUser(), p.isContainerPrivileged(),
            p.getContainerCapAdd(), p.isContainerReadOnlyRootFs(), p.getContainerPorts(), p.getContainerName(), p.getWasVipPort()
        );
    }

    private DeploymentPlanSnapshot.ServerGroupSettings serverGroupSettings(ServerGroup group) {
        List<Server> servers = group.getServers() == null ? List.of() : group.getServers();
        return new DeploymentPlanSnapshot.ServerGroupSettings(
            group.getId(), group.getName(), group.getEnvironment(), group.getRollingIntervalSec(),
            servers.stream().map(server -> new DeploymentPlanSnapshot.ServerSettings(
                server.getId(), server.getName(), server.getIpAddress(), server.getEnvironment(),
                server.getServerType(), server.getSshPort(), server.getDeployAccount(), server.getAuthType(),
                server.getDeployPath(), server.getOrderIndex(), server.isAutoStart(), server.getStartupService(),
                server.isAgentControlled() ? server.getControlMode() : null,
                server.isAgentControlled() ? server.getAgentBindingId() : null
            )).toList(),
            new DeploymentPlanSnapshot.GroupRolloutPolicySettings(group.getMinAvailable(), group.getMaxUnavailable(),
                group.getObservationSeconds(), group.getFailurePolicy()), group.getApprovalPolicyId(),
            Boolean.TRUE.equals(group.getWasVipEnabled()) ? new DeploymentPlanSnapshot.WasVipSettings(true, group.getWasVipAddress()) : null
        );
    }

    private DeploymentPlanSnapshot.ComponentSettings componentSettings(DeployComponent component, DeployEnvironment environment) {
        return new DeploymentPlanSnapshot.ComponentSettings(
            component.getId(), component.getCode(), component.getDisplayName(), component.getRole(),
            component.getArtifactType(), component.getImageRepository(), component.getWebRoot(),
            component.getBuildTool(), component.getDockerfilePath(), component.getDockerContextPath(),
            component.getBuildCommand(), component.getArtifactPath(), component.getHealthCheckMode(),
            component.getHealthCheckPath(), component.getHealthCheckPort(), component.getContainerUser(),
            component.isContainerPrivileged(), component.getContainerCapAdd(), component.isContainerReadOnlyRootFs(),
            component.getDeployOrder(), component.resolveProxyHealthHost(environment)
        );
    }

    private DeploymentPlanSnapshot.ApprovalPolicySettings approvalPolicySettings(ApprovalPolicy policy) {
        if (policy == null) {
            return null;
        }
        List<ApprovalStep> steps = policy.getSteps() == null ? List.of() : policy.getSteps();
        return new DeploymentPlanSnapshot.ApprovalPolicySettings(
            policy.getId(), policy.getName(), policy.getPolicyType(), policy.isAllowSelfApproval(),
            steps.stream().map(step -> {
                List<User> approvers = step.getApprovers() == null ? List.of() : step.getApprovers();
                return new DeploymentPlanSnapshot.ApprovalStepSettings(
                    step.getId(), step.getStepOrder(), step.getRequiredCount(),
                    approvers.stream().map(user -> new DeploymentPlanSnapshot.ApproverSettings(
                        user.getId(), user.getLoginId(), user.getName(), user.getRole(), user.isActive()
                    )).toList(), step.getSourcePolicyId(),step.getSourceStepId(),step.getSourceStepOrder()
                );
            }).toList(), policy.getSourceProjectPolicyId(),
            policy.getRequiredPolicies() == null ? null : policy.getRequiredPolicies().stream().map(this::approvalPolicySettings).toList()
        );
    }

    private EncodedSnapshot encode(DeploymentPlanSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            return new EncodedSnapshot(json, sha256(json));
        } catch (Exception e) {
            throw new IllegalStateException("배포 계획을 저장할 수 없습니다.", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record EncodedSnapshot(String json, String sha256) { }
}
