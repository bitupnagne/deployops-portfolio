package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.PolicyType;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.DeployRequestItemRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class DeploymentPlanSnapshotServiceTest {
    @Test
    void agentTransportAndRegistrationGenerationAreFrozenAndMaterialized() {
        server.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        server.setAgentBindingId("registration-1");
        var request = request();
        service.capture(request);
        assertThat(request.getPlanSnapshotJson())
            .contains("\"controlMode\":\"AGENT\"", "\"agentBindingId\":\"registration-1\"");
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
        service.applySnapshot(request);
        var frozen = request.getProject().getServerGroups().getFirst().getServers().getFirst();
        assertThat(frozen.isAgentControlled()).isTrue();
        assertThat(frozen.getAgentBindingId()).isEqualTo("registration-1");

        server.setAgentBindingId("registration-2");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("변경");
        assertThat(frozen.getAgentBindingId()).isEqualTo("registration-1");
    }

    @Test
    void changingSshToAgentInvalidatesPendingApprovalAndFreshRollbackPinsCurrentAgent() {
        server.setControlMode(com.portfolio.deployops.domain.ServerControlMode.SSH);
        var original = request();
        service.capture(original);
        assertThat(original.getPlanSnapshotJson()).doesNotContain("\"controlMode\"", "\"agentBindingId\"");
        server.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        server.setAgentBindingId("registration-1");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(original))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("변경");
        var rollback = request();
        service.captureRollback(rollback, original);
        service.applySnapshot(rollback);
        var frozen = rollback.getProject().getServerGroups().getFirst().getServers().getFirst();
        assertThat(frozen.isAgentControlled()).isTrue();
        assertThat(frozen.getAgentBindingId()).isEqualTo("registration-1");
        assertThatCode(() -> service.assertCurrentPlanMatches(rollback)).doesNotThrowAnyException();
    }

    @Test
    void legacySshSnapshotOmitsNewConnectionFieldsAndNormalizesMissingControlMode() {
        server.setControlMode(com.portfolio.deployops.domain.ServerControlMode.SSH);
        var request = request();
        service.capture(request);
        assertThat(request.getPlanSnapshotJson()).doesNotContain("\"controlMode\"", "\"agentBindingId\"");
        service.applySnapshot(request);
        var restored = request.getProject().getServerGroups().getFirst().getServers().getFirst();
        assertThat(restored.getControlMode()).isEqualTo(com.portfolio.deployops.domain.ServerControlMode.SSH);
        assertThat(restored.getAgentBindingId()).isNull();
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
    }

    @Test
    void newlyProtectedExistingAliasInvalidatesPreviouslyCapturedUnprotectedPlan() {
        service.configureServerApprovalBoundary(new ServerApprovalBoundaryService(serverRepository));
        var aliasGroup=ServerGroup.builder().id(99L).environment(ServerEnvironment.TEST).build();
        var alias=Server.builder().id(98L).ipAddress("::ffff:198.18.23.21").serverGroup(aliasGroup).build();
        when(serverRepository.findAll()).thenReturn(List.of(alias));
        var request=request();request.setSelectedServerGroupIds(List.of(20L));
        service.capture(request);
        String approved=request.getPlanSnapshotJson();
        aliasGroup.setApprovalPolicyId(61L);
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("선택하지 않은 추가 승인 그룹");
        var fresh=request();fresh.setSelectedServerGroupIds(List.of(20L));
        assertThatThrownBy(() -> service.capture(fresh))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("승인 정책을 생략");
        assertThat(request.getPlanSnapshotJson()).isEqualTo(approved);
        assertThat(fresh.getPlanSnapshotJson()).isNull();
    }
    @Test
    void protectedHostBoundaryRunsOnCaptureAndAgainBeforeExecutionWithoutChangingFrozenPolicy() {
        var boundary=mock(ServerApprovalBoundaryService.class);
        service.configureServerApprovalBoundary(boundary);
        var request=request();request.setSelectedServerGroupIds(List.of(20L));
        service.capture(request);
        String frozen=request.getPlanSnapshotJson();
        org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"미선택 보호 그룹의 같은 호스트"))
            .when(boundary).assertSelectedTargetsCovered(List.of(server),List.of(20L));
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("보호 그룹");
        assertThat(request.getPlanSnapshotJson()).isEqualTo(frozen);
        var fresh=request();fresh.setSelectedServerGroupIds(List.of(20L));
        assertThatThrownBy(() -> service.capture(fresh)).isInstanceOf(ResponseStatusException.class);
        assertThat(fresh.getPlanSnapshotJson()).isNull();
        org.mockito.Mockito.verify(boundary,org.mockito.Mockito.times(3))
            .assertSelectedTargetsCovered(List.of(server),List.of(20L));
    }

    @Test
    void protectedHostBoundaryReceivesOnlyActualEnvironmentAndScopeTargets() {
        var boundary=mock(ServerApprovalBoundaryService.class);
        service.configureServerApprovalBoundary(boundary);
        var excludedWeb=Server.builder().id(23L).serverType(ServerType.WEB).ipAddress("198.18.23.23").build();
        var testGroup=ServerGroup.builder().id(30L).environment(ServerEnvironment.TEST).build();
        when(projectRepository.findServerGroupsByProjectId(11L)).thenReturn(List.of(group,testGroup));
        when(serverRepository.findByServerGroupId(20L)).thenReturn(List.of(server,excludedWeb));
        when(serverRepository.findByServerGroupId(30L)).thenReturn(List.of(
            Server.builder().id(31L).serverType(ServerType.WAS).ipAddress("198.18.23.31").build()));
        var request=request();request.setDeployScope(com.portfolio.deployops.domain.DeployScope.WAS_ONLY);
        service.capture(request);
        org.mockito.Mockito.verify(boundary).assertSelectedTargetsCovered(List.of(server),List.of(20L));
    }
    @Test
    void readOnlyApprovalPreviewUsesTheSameGroupCompositionAndRequesterRulesAsSnapshot() {
        var policies=mock(com.portfolio.deployops.repository.ApprovalPolicyRepository.class);
        service.configureGroupApproval(policies);project.setApprovalPolicy(null);
        var added=ApprovalPolicy.builder().id(61L).name("현재 그룹 정책").policyType(PolicyType.STEP).build();
        var step=ApprovalStep.builder().id(62L).stepOrder(1).requiredCount(1).build();
        when(policies.findById(61L)).thenReturn(Optional.of(added));
        when(stepRepository.findByPolicyId(61L)).thenReturn(List.of(step));
        when(stepRepository.findApproversByStepId(62L)).thenReturn(List.of(approver));
        group.setApprovalPolicyId(61L);
        var preview=service.currentApprovalPolicy(project,List.of(group),User.builder().id(999L).build());
        assertThat(preview.isGroupComposed()).isTrue();
        assertThat(preview.getRequiredPolicies()).extracting(ApprovalPolicy::getId).containsExactly(61L);
        assertThatThrownBy(() -> service.currentApprovalPolicy(project,List.of(group),approver))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("요청자를 제외");
        assertThat(project.getApprovalPolicy()).isNull();
    }
    @Test
    void groupApprovalFreezesOriginalPoliciesMappingsAndGlobalStepOrigins() {
        var policies=mock(com.portfolio.deployops.repository.ApprovalPolicyRepository.class);
        service.configureGroupApproval(policies);
        var added=ApprovalPolicy.builder().id(61L).name("그룹 운영 책임자").policyType(PolicyType.ALL).allowSelfApproval(true).build();
        var groupStep=ApprovalStep.builder().id(62L).stepOrder(1).requiredCount(1).build();
        when(policies.findById(61L)).thenReturn(Optional.of(added));
        when(stepRepository.findByPolicyId(61L)).thenReturn(List.of(groupStep));
        when(stepRepository.findApproversByStepId(62L)).thenReturn(List.of(approver));
        group.setApprovalPolicyId(61L);
        var request=request();request.setSelectedServerGroupIds(List.of(20L));
        service.capture(request);
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
        service.applySnapshot(request);
        var frozen=request.getProject().getApprovalPolicy();
        assertThat(frozen.isGroupComposed()).isTrue();
        assertThat(frozen.getRequiredPolicies()).extracting(ApprovalPolicy::getId).containsExactly(31L,61L);
        assertThat(frozen.getSourceProjectPolicyId()).isEqualTo(31L);
        assertThat(frozen.getSteps()).extracting(ApprovalStep::getSourcePolicyId).containsExactly(31L,61L);
        assertThat(request.getProject().getServerGroups().getFirst().getApprovalPolicyId()).isEqualTo(61L);
        group.setApprovalPolicyId(null);
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(request.getProject().getApprovalPolicy().getRequiredPolicies()).hasSize(2);
    }

    @Test
    void groupPolicyContentChangeInvalidatesPendingPlanAndRollbackUsesCurrentGroupPolicy() {
        var policies=mock(com.portfolio.deployops.repository.ApprovalPolicyRepository.class);
        service.configureGroupApproval(policies);
        var added=ApprovalPolicy.builder().id(61L).name("원래 그룹 정책").policyType(PolicyType.ANY_ONE).build();
        var groupStep=ApprovalStep.builder().id(62L).stepOrder(1).requiredCount(1).build();
        when(policies.findById(61L)).thenReturn(Optional.of(added));
        when(stepRepository.findByPolicyId(61L)).thenReturn(List.of(groupStep));
        when(stepRepository.findApproversByStepId(62L)).thenReturn(List.of(approver));
        group.setApprovalPolicyId(61L);
        var source=request();source.setSelectedServerGroupIds(List.of(20L));
        service.capture(source);
        added.setName("현재 그룹 정책");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(source)).isInstanceOf(ResponseStatusException.class);
        var rollback=request();
        service.captureRollback(rollback,source);
        service.applySnapshot(rollback);
        assertThat(rollback.getProject().getApprovalPolicy().getRequiredPolicies().get(1).getName()).isEqualTo("현재 그룹 정책");
        assertThat(rollback.getPlanSnapshotJson()).contains("\"sourcePolicyId\":61");
        assertThatCode(() -> service.assertCurrentPlanMatches(rollback)).doesNotThrowAnyException();
    }
    @Test
    void containerIdentityIsFrozenAndMaterializedWithoutChangingLegacySnapshots() {
        var legacy=request();
        service.capture(legacy);
        assertThat(legacy.getPlanSnapshotJson()).doesNotContain("\"containerName\"");
        assertThat(service.applySnapshot(legacy)).isTrue();
        assertThat(legacy.getProject().getContainerName()).isNull();
        project.setContainerName("deploy-project-11");
        var named=request();
        service.capture(named);
        assertThat(named.getPlanSnapshotJson()).contains("\"containerName\":\"deploy-project-11\"");
        project.setContainerName("unexpected-current-name");
        assertThat(service.applySnapshot(named)).isTrue();
        assertThat(named.getProject().getContainerName()).isEqualTo("deploy-project-11");
    }

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ServerRepository serverRepository = mock(ServerRepository.class);
    private final DeployComponentRepository componentRepository = mock(DeployComponentRepository.class);
    private final ApprovalStepRepository stepRepository = mock(ApprovalStepRepository.class);
    private final DeployRequestItemRepository requestItemRepository = mock(DeployRequestItemRepository.class);
    private final SystemSettingService systemSettingService = mock(SystemSettingService.class);
    private final RuntimeConfigurationService runtimeConfigurationService = mock(RuntimeConfigurationService.class);
    private final DeploymentPlanSnapshotService service = new DeploymentPlanSnapshotService(
        JsonMapper.builder().findAndAddModules().build(),
        projectRepository,
        serverRepository,
        componentRepository,
        stepRepository,
        requestItemRepository,
        systemSettingService, runtimeConfigurationService
    );

    private final User approver = User.builder()
        .id(41L)
        .loginId("release-approver")
        .name("운영 승인자")
        .role(UserRole.APPROVER)
        .password("never-store-password")
        .gitlabToken("never-store-token")
        .active(true)
        .build();
    private final ApprovalPolicy policy = ApprovalPolicy.builder()
        .id(31L)
        .name("운영 1단계 승인")
        .policyType(PolicyType.STEP)
        .build();
    private final ApprovalStep step = ApprovalStep.builder()
        .id(32L)
        .policy(policy)
        .stepOrder(1)
        .requiredCount(1)
        .build();
    private final Project project = Project.builder()
        .id(11L)
        .name("결제 API")
        .gitlabProjectId(101L)
        .defaultBranch("main")
        .imageRepository("nexus.local:5000/payment/api")
        .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL)
        .healthCheckPath("/actuator/health")
        .healthCheckPort(8080)
        .approvalPolicy(policy)
        .active(true)
        .build();
    private final Server server = Server.builder()
        .id(21L)
        .name("prdwas1")
        .ipAddress("198.18.23.21")
        .environment(ServerEnvironment.PROD)
        .serverType(ServerType.WAS)
        .sshPort(2022)
        .deployAccount("deploy")
        .orderIndex(1)
        .build();
    private final ServerGroup group = ServerGroup.builder()
        .id(20L)
        .name("운영 WAS")
        .environment(ServerEnvironment.PROD)
        .rollingIntervalSec(30)
        .build();
    private final DeployComponent component = DeployComponent.builder()
        .id(51L)
        .projectId(11L)
        .code("api")
        .displayName("API")
        .role(ServerType.WAS)
        .artifactType(ArtifactType.CONTAINER)
        .imageRepository("nexus.local:5000/payment/api")
        .healthCheckMode(HealthCheckMode.SSH_LOCAL_CURL)
        .healthCheckPort(8080)
        .healthCheckPath("/actuator/health")
        .active(true)
        .build();

    @BeforeEach
    void setUp() {
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        when(projectRepository.findServerGroupsByProjectId(11L)).thenReturn(List.of(group));
        when(serverRepository.findByServerGroupId(20L)).thenReturn(List.of(server));
        when(componentRepository.findActiveByProjectId(11L)).thenReturn(List.of(component));
        when(stepRepository.findByPolicyId(31L)).thenReturn(List.of(step));
        when(stepRepository.findApproversByStepId(32L)).thenReturn(List.of(approver));
    }

    @Test
    void captureStoresDeterministicPlanWithoutUserSecrets() {
        DeployRequest request = request();

        service.capture(request);

        assertThat(request.getPlanSnapshotVersion()).isEqualTo(2);
        assertThat(request.getPlanSnapshotHash()).hasSize(64);
        assertThat(request.getPlanSnapshotJson())
            .contains("prdwas1", "nexus.local:5000/payment/api", "release-approver")
            .doesNotContain("never-store-password", "never-store-token");
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
    }

    @Test
    void storageMountsAreFrozenMaterializedAndChangingBindingInvalidatesApproval() {
        var mounts=mock(StorageMountService.class); service.configureStorageMounts(mounts);
        var original=new com.portfolio.deployops.domain.StorageMountSettings(server.getId(),component.getId(),"shared","NAS","/mnt/shared","orders","/app/data",true,"/mnt/shared","nas:/orders","nfs4");
        when(mounts.describe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenReturn(List.of(original));
        var request=request(); service.capture(request);
        assertThat(service.decode(request.getPlanSnapshotJson()).storageMounts()).containsExactly(original);
        service.applySnapshot(request); assertThat(request.getProject().getStorageMounts()).containsExactly(original);
        var changed=new com.portfolio.deployops.domain.StorageMountSettings(server.getId(),component.getId(),"shared","NAS","/mnt/shared","changed","/app/data",true,"/mnt/shared","nas:/orders","nfs4");
        when(mounts.describe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenReturn(List.of(changed));
        assertThatThrownBy(()->service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("변경");
    }

    @Test
    void originalRollbackRetainsOriginalMountEvenAfterCurrentBindingChanges() {
        var mounts=mock(StorageMountService.class); service.configureStorageMounts(mounts);
        var original=new com.portfolio.deployops.domain.StorageMountSettings(server.getId(),component.getId(),"shared","NAS","/mnt/shared","v1","/app/data",true,"/mnt/shared","nas:/data","nfs4");
        when(mounts.describe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenReturn(List.of(original));
        var source=request(); service.capture(source);
        var changed=new com.portfolio.deployops.domain.StorageMountSettings(server.getId(),component.getId(),"shared","NAS","/mnt/shared","v2","/app/data",false,"/mnt/shared","nas:/data","nfs4");
        when(mounts.describe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenReturn(List.of(changed));
        var rollback=request(); rollback.setId(62L); service.captureRollback(rollback,source);
        assertThat(service.decode(rollback.getPlanSnapshotJson()).storageMounts()).containsExactly(original);
        service.assertCurrentPlanMatches(rollback);
        org.mockito.Mockito.verify(mounts,org.mockito.Mockito.atLeastOnce()).assertPinnedAllowed(List.of(original));
    }

    @Test
    void ciBindingIgnoresOnlyPendingReleaseAndPreservesApprovedConfiguration() {
        var request = request();
        request.setEnvironment(DeployEnvironment.TEST);
        request.setStatus(com.portfolio.deployops.domain.DeployStatus.TEST_BUILDING);
        service.capture(request);
        String pending = request.getPlanSnapshotJson();
        request.setGitlabPipelineId(77L);
        assertThatCode(() -> service.assertBuildConfigurationMatches(request)).doesNotThrowAnyException();
        component.setDockerfilePath("changed-after-approval/Dockerfile");
        assertThatThrownBy(() -> service.assertBuildConfigurationMatches(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(request.getPlanSnapshotJson()).isEqualTo(pending);
        component.setDockerfilePath(null);
        request.setCommitSha("a".repeat(40)); request.setImageTag("v1");
        request.setImageDigest("sha256:"+"b".repeat(64)); request.setReleaseManifestSha256("c".repeat(64));
        service.bindVerifiedRelease(request);
        String bound = request.getPlanSnapshotJson();
        assertThat(bound).contains("\"manifestSha256\":\""+"c".repeat(64)+"\"");
        assertThatCode(() -> service.bindVerifiedRelease(request)).doesNotThrowAnyException();
        request.setReleaseManifestSha256("d".repeat(64));
        assertThatThrownBy(() -> service.bindVerifiedRelease(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(request.getPlanSnapshotJson()).isEqualTo(bound);
    }

    @Test void selectedGroupPolicyIsPinnedAndChangesInvalidateApproval() {
        var request = request();
        request.setSelectedServerGroupIds(List.of(20L));
        service.capture(request);
        assertThat(service.applySnapshot(request)).isTrue();
        assertThat(request.getProject().getServerGroups()).hasSize(1);
        group.setMinAvailable(0);
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void changingUnselectedGroupDoesNotInvalidateSelectedDeployment() {
        var other = ServerGroup.builder().id(99L).name("다른 운영").environment(ServerEnvironment.PROD).build();
        when(projectRepository.findServerGroupsByProjectId(11L)).thenReturn(List.of(group,other));
        var request = request();
        request.setSelectedServerGroupIds(List.of(20L));
        service.capture(request);
        other.setMinAvailable(100);
        other.setName("변경");
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
        request.setSelectedServerGroupIds(List.of(99L));
        assertThatThrownBy(() -> service.applySnapshot(request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rollbackKeepsHistoricalRuntimeAndRequiresStableCurrentConfiguration() {
        var source = request();
        source.setSelectedServerGroupIds(List.of(20L));
        service.capture(source);
        project.setHealthCheckPath("/new-health");
        component.setHealthCheckPath("/new-component-health");
        var rollback = request();
        service.captureRollback(rollback,source);
        assertThat(rollback.getSelectedServerGroupIds()).containsExactly(20L);
        assertThatCode(() -> service.assertCurrentPlanMatches(rollback)).doesNotThrowAnyException();
        service.applySnapshot(rollback);
        assertThat(rollback.getProject().getHealthCheckPath()).isEqualTo("/actuator/health");
        assertThat(rollback.getProject().getComponents().getFirst().getHealthCheckPath()).isEqualTo("/actuator/health");
        project.setHealthCheckPort(9999);
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(rollback)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void oldSnapshotCanonicalHashRemainsStableAndNewRevisionInvalidatesApproval() throws Exception {
        DeployRequest request=request();
        service.capture(request);
        String oldJson=request.getPlanSnapshotJson(),oldHash=request.getPlanSnapshotHash();
        assertThat(oldJson).doesNotContain("managedRuntime");
        var mapper=JsonMapper.builder().findAndAddModules().build();
        String roundtrip=mapper.writeValueAsString(mapper.readValue(oldJson,com.portfolio.deployops.domain.DeploymentPlanSnapshot.class));
        assertThat(roundtrip).isEqualTo(oldJson);
        assertThat(RuntimeConfigurationService.fingerprint(roundtrip)).isEqualTo(oldHash);
        service.applySnapshot(request);
        assertThat(request.getPlanSnapshotHash()).isEqualTo(oldHash);
        var selected=new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L,"MANAGED_REVISION",9L,"f".repeat(64),false,0);
        when(runtimeConfigurationService.bindings(11L,DeployEnvironment.PROD)).thenReturn(List.of(selected));
        assertThatThrownBy(()->service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        service.capture(request);
        assertThat(request.getPlanSnapshotJson()).contains("managedRuntime", "recordFingerprint").doesNotContain("encrypted_payload");
        String configuredHash=request.getPlanSnapshotHash();
        when(runtimeConfigurationService.bindings(11L,DeployEnvironment.PROD)).thenReturn(List.of(new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L,"MANAGED_REVISION",10L,"e".repeat(64),false,0)));
        assertThatThrownBy(()->service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(request.getPlanSnapshotHash()).isEqualTo(configuredHash);
    }

    @Test
    void changedTargetInvalidatesPreviousApprovalPlan() {
        DeployRequest request = request();
        service.capture(request);

        server.setIpAddress("198.18.23.99");

        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("새 배포 요청");
    }

    @Test
    void changedGlobalRuntimeSettingInvalidatesPreviousApprovalPlan() {
        when(systemSettingService.get(RemoteDeployService.IMAGE_TRANSFER_SETTING, "registry"))
            .thenReturn("registry", "push");
        DeployRequest request = request();
        service.capture(request);

        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("새 배포 요청");
    }

    @Test
    void changedRunnerMavenEndpointInvalidatesPreviousApprovalPlan() {
        when(systemSettingService.get("nexus.maven.base-url", ""))
            .thenReturn("http://runner-nexus:8081", "http://changed-runner:8081");
        DeployRequest request = request();
        service.capture(request);
        assertThat(request.getPlanSnapshotJson()).contains("http://runner-nexus:8081");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("새 배포 요청");
    }

    @Test
    void changedProdReleaseDigestInvalidatesPreviousApprovalPlan() {
        DeployRequest request = request();
        request.setImageTag("release-101");
        request.setImageDigest("sha256:" + "a".repeat(64));
        service.capture(request);

        request.setImageDigest("sha256:" + "b".repeat(64));

        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("새 배포 요청");
    }

    @Test
    void verifiedTestBuildReleaseIsBoundWithoutRecapturingChangedConfiguration() {
        DeployRequest request = request();
        request.setEnvironment(DeployEnvironment.TEST);
        service.capture(request);

        server.setIpAddress("198.18.23.99");
        request.setGitlabPipelineId(901L);
        request.setCommitSha("a".repeat(40));
        request.setImageTag("main-aaaaaaaa-901");
        request.setImageDigest("sha256:" + "b".repeat(64));
        service.bindVerifiedRelease(request);

        assertThat(request.getPlanSnapshotJson())
            .contains("main-aaaaaaaa-901", "198.18.23.21")
            .doesNotContain("198.18.23.99");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("새 배포 요청");
    }

    @Test
    void pipelineIdBoundDuringTestBuildCanBeReadButUnverifiedReleaseCannotChange() {
        DeployRequest request = request();
        request.setEnvironment(DeployEnvironment.TEST);
        service.capture(request);
        request.setStatus(DeployStatus.TEST_BUILDING);
        request.setGitlabPipelineId(901L);

        assertThat(service.applySnapshot(request)).isTrue();

        request.setStatus(DeployStatus.TEST_FAILED);
        assertThat(service.applySnapshot(request)).isTrue();
        request.setStatus(DeployStatus.TEST_BUILDING);

        request.setImageDigest("sha256:" + "a".repeat(64));
        assertThatThrownBy(() -> service.applySnapshot(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("기본 정보");

        request.setImageDigest(null);
        request.setCommitSha("b".repeat(40));
        request.setImageTag("deploy-mr-9-bbbbbbbb-901");
        request.setImageDigest("sha256:" + "c".repeat(64));
        service.bindVerifiedRelease(request);
        assertThat(service.applySnapshot(request)).isTrue();
    }

    @Test
    void applySnapshotMaterializesTheApprovedTargetInsteadOfCurrentMutableObjects() {
        when(systemSettingService.get("nexus.registry", "")).thenReturn("nexus.local:5000");
        DeployRequest request = request();
        service.capture(request);
        server.setIpAddress("198.18.23.99");
        project.setImageRepository("untrusted.local:5000/changed/image");

        assertThat(service.applySnapshot(request)).isTrue();

        assertThat(request.getProject().getImageRepository()).isEqualTo("nexus.local:5000/payment/api");
        assertThat(request.getProject().getServerGroups().get(0).getServers().get(0).getIpAddress())
            .isEqualTo("198.18.23.21");
        assertThat(request.getProject().getApprovalPolicy().getSteps().get(0).getApprovers().get(0).getPassword())
            .isNull();
        assertThat(request.getProject().getDeploymentRuntimeSettings().imageTransfer()).isEqualTo("registry");
        assertThat(request.getProject().getDeploymentRuntimeSettings().nexusRegistry()).isEqualTo("nexus.local:5000");
    }

    @Test
    void tamperedSnapshotIsRejectedBeforeComparison() {
        DeployRequest request = request();
        service.capture(request);
        request.setPlanSnapshotJson(request.getPlanSnapshotJson().replace("prdwas1", "attacker-host"));

        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("무결성");
    }

    @Test
    void legacyRequestWithoutSnapshotCannotBeApprovedOrExecuted() {
        DeployRequest request = request();

        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("새 배포 요청");
    }

    @Test
    void stopGraceIsFrozenAndChangedNonDefaultRequiresNewApprovalWhileOldMissingMeans30() throws Exception {
        when(runtimeConfigurationService.bindings(11L,DeployEnvironment.PROD)).thenReturn(List.of(new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L,"NONE",null,null,false,0,30)));
        var request=request();service.capture(request);String json=request.getPlanSnapshotJson();
        String old=json.replace("\"stopGraceSeconds\":30,", "").replace(",\"stopGraceSeconds\":30", "");
        assertThat(old).doesNotContain("stopGraceSeconds");request.setPlanSnapshotJson(old);
        request.setPlanSnapshotHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(old.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        service.assertCurrentPlanMatches(request);assertThat(request.getPlanSnapshotJson()).isEqualTo(old);
        when(runtimeConfigurationService.bindings(11L,DeployEnvironment.PROD)).thenReturn(List.of(new com.portfolio.deployops.domain.ManagedRuntimeSettings(0L,"NONE",null,null,false,0,60)));
        assertThatThrownBy(()->service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        service.applySnapshot(request);assertThat(request.getProject().getManagedRuntimeSettings().getFirst().stopGraceSeconds()).isEqualTo(30);
    }

    private DeployRequest request() {
        return DeployRequest.builder()
            .id(61L)
            .reqNo("REQ-20260914-001")
            .project(project)
            .environment(DeployEnvironment.PROD)
            .build();
    }

    @Test
    void legacyComponentJsonRetainsExactCanonicalBytesAndHashWithAbsentHost() throws Exception {
        // Recorded v2 component shape before optional proxyHealthHost was introduced.
        String legacy = "{\"id\":51,\"code\":\"api\",\"displayName\":\"API\",\"role\":\"WAS\",\"artifactType\":\"CONTAINER\",\"imageRepository\":\"nexus.local:5000/payment/api\",\"webRoot\":null,\"buildTool\":null,\"dockerfilePath\":null,\"dockerContextPath\":null,\"buildCommand\":null,\"artifactPath\":null,\"healthCheckMode\":\"SSH_LOCAL_CURL\",\"healthCheckPath\":\"/actuator/health\",\"healthCheckPort\":8080,\"containerUser\":null,\"containerPrivileged\":false,\"containerCapAdd\":null,\"containerReadOnlyRootFs\":false,\"deployOrder\":0}";
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var decoded = mapper.readValue(legacy, com.portfolio.deployops.domain.DeploymentPlanSnapshot.ComponentSettings.class);
        assertThat(decoded.proxyHealthHost()).isNull();
        String roundTrip = mapper.writeValueAsString(decoded);
        assertThat(roundTrip).isEqualTo(legacy);
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(roundTrip.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .isEqualTo("1337d7858eedc375814c6ec9a9342c993689006434e96aa2f59e33c6380074ad");
        DeployRequest request = request();
        service.capture(request);
        String original = request.getPlanSnapshotJson();
        String hash = request.getPlanSnapshotHash();
        assertThat(original).contains(legacy).doesNotContain("proxyHealthHost");
        assertThat(mapper.writeValueAsString(service.decode(original))).isEqualTo(original);
        assertThat(service.applySnapshot(request)).isTrue();
        assertThat(request.getProject().getComponents().getFirst().getProxyHealthHost()).isNull();
        service.assertCurrentPlanMatches(request);
        assertThat(request.getPlanSnapshotHash()).isEqualTo(hash);
    }

    @Test
    void proxyHostIsBoundToApprovalPlanAndAppliedFromSnapshot() {
        component.setRole(ServerType.WEB);
        component.setArtifactType(ArtifactType.PROXY_RELOAD);
        component.setProxyHealthHost("portal.internal");
        DeployRequest request = request();
        service.capture(request);
        assertThat(request.getPlanSnapshotJson()).contains("\"proxyHealthHost\":\"portal.internal\"");
        component.setProxyHealthHost("other.internal");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("새 배포 요청");
        assertThat(service.applySnapshot(request)).isTrue();
        assertThat(request.getProject().getComponents().getFirst().getProxyHealthHost()).isEqualTo("portal.internal");
    }

    @Test
    void existingSchemaTwoWithoutPortKeyRetainsItsHashAndCanBeMaterialized() throws Exception {
        project.setContainerPorts("9080:9080,9090:9090");
        DeployRequest request = request();
        service.capture(request);
        // Reproduce the persisted schema-2 representation written before this optional field existed.
        String legacyJson = request.getPlanSnapshotJson().replace(",\"containerPorts\":\"9080:9080,9090:9090\"", "");
        assertThat(legacyJson).doesNotContain("containerPorts");
        request.setPlanSnapshotJson(legacyJson);
        request.setPlanSnapshotHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        project.setContainerPorts(null);
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
        assertThat(service.applySnapshot(request)).isTrue();
        assertThat(request.getProject().getContainerPorts()).isNull();
        assertThat(request.getPlanSnapshotJson()).isEqualTo(legacyJson);
    }

    @Test
    void changedPortsInvalidateApprovalButSnapshotExecutionKeepsTheApprovedPorts() {
        // This test checks the immutable port plan, with an explicitly permitted
        // single-host deployment and no timed observation delay.
        group.setMinAvailable(0);
        group.setObservationSeconds(0);
        project.setContainerPorts("9080:9080,9090:9090");
        project.setHealthCheckPort(9090);
        DeployRequest request = request();
        request.setImageDigest("sha256:" + "a".repeat(64));
        service.capture(request);
        project.setContainerPorts("9999:9090");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.applySnapshot(request)).isTrue();
        assertThat(request.getProject().getContainerPorts()).isEqualTo("9080:9080,9090:9090");

        SshCommandService ssh = mock(SshCommandService.class);
        var now = java.time.LocalDateTime.now();
        when(ssh.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                List<String> args = invocation.getArgument(1);
                if (args.equals(List.of("/usr/local/deploy/bin/deploy-agent-probe"))) {
                    return new RemoteCommandResult(0,
                        """
                        {"ready":true,"role":"WAS","checks":{"helpers":{"ready":true},"docker":{"ready":true}}}
                        """, "", "probe", now, now, 1L);
                }
                if (args.equals(List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-was-app.sh", "capabilities"))) {
                    return new RemoteCommandResult(0, "STOP_GRACE_VERSION=1\nROLLBACK_VERSION=1\nQUIESCE_VERSION=1\nPROJECT_OWNERSHIP_VERSION=1",
                        "", "capabilities", now, now, 1L);
                }
                if (args.equals(List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-was-app.sh", "probe",
                    "--container", "api", "--managed-project", project.getId().toString(), "--health-mode", "http", "--stop-grace-seconds", "30",
                    "--health-port", "8080", "--health-path", "/actuator/health"))) {
                    return new RemoteCommandResult(0, "Health check passed", "", "probe", now, now, 1L);
                }
                if (args.size() > 4 && args.subList(0, 4).equals(
                    List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-was-app.sh", "deploy"))) {
                    return new RemoteCommandResult(0, "Deployment completed", "", "deploy", now, now, 1L);
                }
                throw new AssertionError("Unexpected native command: " + args);
            });
        new RemoteDeployService(ssh, mock(RemoteCommandLogService.class), systemSettingService, mock(ImageArtifactService.class))
            .execute(request, ignored -> { });
        org.mockito.ArgumentCaptor<List<String>> command = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(ssh,org.mockito.Mockito.times(6)).execute(org.mockito.ArgumentMatchers.any(), command.capture());
        var deploy=command.getAllValues().stream().filter(args->args.contains("deploy")).findFirst().orElseThrow();
        assertThat(deploy).containsSubsequence("--stop-grace-seconds","30");
        assertThat(deploy).containsSubsequence("--publish","9080:9080","--publish","9090:9090").doesNotContain("9999:9090");

    }

    @Test
    void prodSnapshotIgnoresTestOnlyChangesButRejectsItsEffectiveHostChange() {
        component.setRole(ServerType.WEB);
        component.setArtifactType(ArtifactType.PROXY_RELOAD);
        component.setProxyHealthHost("common.internal");
        component.setProxyHealthHostTest("test.internal");
        component.setProxyHealthHostProd("prod.internal");
        DeployRequest request = request();
        service.capture(request);
        String original = request.getPlanSnapshotJson();
        String hash = request.getPlanSnapshotHash();
        assertThat(original).contains("\"proxyHealthHost\":\"prod.internal\"")
            .doesNotContain("proxyHealthHostTest", "proxyHealthHostProd", "test.internal");
        component.setProxyHealthHostTest("other-test.internal");
        component.setProxyHealthHost("other-common.internal");
        assertThatCode(() -> service.assertCurrentPlanMatches(request)).doesNotThrowAnyException();
        component.setProxyHealthHostProd("other-prod.internal");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.applySnapshot(request)).isTrue();
        DeployComponent frozen = request.getProject().getComponents().getFirst();
        assertThat(frozen.resolveProxyHealthHost(DeployEnvironment.PROD)).isEqualTo("prod.internal");
        assertThat(frozen.getProxyHealthHostProd()).isNull();
        assertThat(request.getPlanSnapshotJson()).isEqualTo(original);
        assertThat(request.getPlanSnapshotHash()).isEqualTo(hash);
    }

    @Test
    void testSnapshotResolvesItsHostAndLegacyCommonFallback() {
        component.setProxyHealthHost("common.internal");
        component.setProxyHealthHostTest("test.internal");
        component.setProxyHealthHostProd("prod.internal");
        DeployRequest request = request();
        request.setEnvironment(DeployEnvironment.TEST);
        service.capture(request);
        assertThat(request.getPlanSnapshotJson()).contains("\"proxyHealthHost\":\"test.internal\"");
        component.setProxyHealthHostTest(null);
        DeployRequest fresh = request();
        fresh.setEnvironment(DeployEnvironment.TEST);
        service.capture(fresh);
        assertThat(fresh.getPlanSnapshotJson()).contains("\"proxyHealthHost\":\"common.internal\"");
    }
    @Test void vipConfigurationIsFrozenAndModeOrAddressDriftInvalidatesApproval() {
        group.setWasVipEnabled(true);group.setWasVipAddress("198.18.23.100");project.setWasVipPort(8443);
        var request=request();service.capture(request);
        assertThat(request.getPlanSnapshotJson()).contains("\"wasVipPort\":8443", "\"wasVip\":{\"enabled\":true", "198.18.23.100");
        service.applySnapshot(request);
        assertThat(request.getProject().getWasVipPort()).isEqualTo(8443);
        assertThat(request.getProject().getServerGroups().getFirst().getWasVipEnabled()).isTrue();
        group.setWasVipAddress("198.18.23.101");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(request.getProject().getServerGroups().getFirst().getWasVipAddress()).isEqualTo("198.18.23.100");
    }

    @Test void vipRollbackRejectsRuntimeEndpointDriftButKeepsCurrentVipAddressOnCompatibleRollback() {
        group.setWasVipEnabled(true);group.setWasVipAddress("198.18.23.100");project.setWasVipPort(8443);
        var source=request();source.setSelectedServerGroupIds(List.of(20L));service.capture(source);
        project.setWasVipPort(9443);
        assertThatThrownBy(() -> service.captureRollback(request(),source)).hasMessageContaining("VIP 관련 롤백");
        project.setWasVipPort(8443);component.setHealthCheckPath("/different");
        assertThatThrownBy(() -> service.captureRollback(request(),source)).hasMessageContaining("개별 헬스체크");
        component.setHealthCheckPath("/actuator/health");group.setWasVipAddress("198.18.23.101");
        var rollback=request();service.captureRollback(rollback,source);service.applySnapshot(rollback);
        assertThat(rollback.getProject().getWasVipPort()).isEqualTo(8443);
        assertThat(rollback.getProject().getServerGroups().getFirst().getWasVipAddress()).isEqualTo("198.18.23.101");
        assertThatCode(() -> service.assertCurrentPlanMatches(rollback)).doesNotThrowAnyException();
    }

    @Test void webOnlyRollbackDoesNotValidateUnusedVipWasRuntime() {
        group.setWasVipEnabled(true);group.setWasVipAddress("198.18.23.100");project.setWasVipPort(8443);
        server.setServerType(ServerType.WEB);component.setRole(ServerType.WEB);component.setArtifactType(ArtifactType.STATIC_BUNDLE);
        var source=request();source.setDeployScope(com.portfolio.deployops.domain.DeployScope.WEB_ONLY);source.setSelectedServerGroupIds(List.of(20L));service.capture(source);
        project.setWasVipPort(9443);project.setContainerPorts("9443:8080");project.setHealthCheckPort(9090);
        var rollback=request();rollback.setDeployScope(com.portfolio.deployops.domain.DeployScope.WEB_ONLY);
        assertThatCode(() -> service.captureRollback(rollback,source)).doesNotThrowAnyException();
        service.applySnapshot(rollback);
        assertThat(rollback.getProject().getWasVipPort()).isEqualTo(8443);
        assertThatCode(() -> service.assertCurrentPlanMatches(rollback)).doesNotThrowAnyException();
    }

    @Test void portlessProcRetainsVipApprovalDriftButRollbackIgnoresUnusedHttpPortChange() {
        group.setWasVipEnabled(true);group.setWasVipAddress("198.18.23.100");
        project.setWasVipPort(null);project.setContainerPorts(null);project.setHealthCheckMode(HealthCheckMode.CONTAINER_RUNNING);
        component.setHealthCheckMode(HealthCheckMode.CONTAINER_RUNNING);
        var source=request();service.capture(source);
        group.setWasVipAddress("198.18.23.101");
        assertThatThrownBy(() -> service.assertCurrentPlanMatches(source)).isInstanceOf(ResponseStatusException.class);
        project.setHealthCheckPort(9090);component.setHealthCheckPort(9091);
        var rollback=request();assertThatCode(() -> service.captureRollback(rollback,source)).doesNotThrowAnyException();
        service.applySnapshot(rollback);
        assertThat(rollback.getProject().getComponents().getFirst().getHealthCheckPort()).isEqualTo(8080);
        assertThat(rollback.getProject().getServerGroups().getFirst().getWasVipAddress()).isEqualTo("198.18.23.101");
        assertThatCode(() -> service.assertCurrentPlanMatches(rollback)).doesNotThrowAnyException();
        project.setContainerPorts("18080:8080");
        assertThatThrownBy(() -> service.captureRollback(request(),source)).hasMessageContaining("VIP 관련 롤백");
        project.setContainerPorts(null);project.setHealthCheckPort(8080);component.setHealthCheckPort(8080);
        for (String network : new String[]{"host","nas-macvlan"}) {
            project.setDockerNetwork(network);
            assertThatThrownBy(() -> service.captureRollback(request(),source)).hasMessageContaining("VIP 관련 롤백");
        }
    }

}
