package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalDecisionSource;
import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.DeploymentReadyEvent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ProjectImageHistoryStatus;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ReleaseVerificationEvidence;
import com.portfolio.deployops.domain.ReleaseVerificationStatus;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.MergeRequestEvidenceStatus;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.DeployRequestCreateForm;
import com.portfolio.deployops.dto.ComponentImageForm;
import com.portfolio.deployops.dto.RollbackRequestForm;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.DeployRequestItemRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DeployRequestServiceTest {
    @Test
    void rollbackSuggestionUsesCurrentGroupOnlyPolicyWithoutPersistingOrDispatching() {
        verifiedProdHistory();project.setApprovalPolicy(null);
        withGroupApproval(DeployEnvironment.PROD);
        var actor=currentUser();
        var original=ApprovalPolicy.builder().id(91L).build();
        var effective=ApprovalPolicy.builder().requiredPolicies(List.of(original)).build();
        when(deploymentPlanSnapshotService.currentApprovalPolicy(org.mockito.ArgumentMatchers.eq(project),anyList(),org.mockito.ArgumentMatchers.eq(actor.getUser())))
            .thenAnswer(inv -> {
                List<com.portfolio.deployops.domain.ServerGroup> groups=inv.getArgument(1);
                assertThat(groups).extracting(com.portfolio.deployops.domain.ServerGroup::getId).containsExactly(802L);
                assertThat(groups).extracting(com.portfolio.deployops.domain.ServerGroup::getApprovalPolicyId).containsExactly(91L);
                return effective;
            });
        service.assertRollbackSuggestionAvailable(77L,1L,actor);
        verify(projectPermissionService).assertPermission(actor,1L,ProjectPermissionType.ROLLBACK_EXECUTE,DeployEnvironment.PROD);
        verify(deployRequestRepository,never()).save(any());
        verify(deploymentPlanSnapshotService,never()).capture(any());
        verify(deploymentPlanSnapshotService,never()).captureRollback(any(),any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher,officeNotificationService,statusHistoryRepository);
    }

    @Test
    void rollbackSuggestionStillRejectsUnverifiedHistoryAndChangedRegistry() {
        var history=verifiedProdHistory();
        withGroupApproval(DeployEnvironment.PROD);
        when(deploymentPlanSnapshotService.currentApprovalPolicy(any(),anyList(),any()))
            .thenReturn(ApprovalPolicy.builder().id(91L).build());
        var source=deployRequestRepository.findById(66L).orElseThrow();
        source.setReleaseVerificationStatus(ReleaseVerificationStatus.UNVERIFIED);
        assertThatThrownBy(() -> service.assertRollbackSuggestionAvailable(77L,1L,currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("CI 출처");
        history.setImageRepository("registry/another-service");
        assertThatThrownBy(() -> service.assertRollbackSuggestionAvailable(77L,1L,currentUser()))
            .isInstanceOf(ResponseStatusException.class);
        verify(deployRequestRepository,never()).save(any());
    }

    @Test
    void rollbackSuggestionCannotReplaceRemovedOriginalGroupsOrUseAnotherProjectHistory() {
        verifiedProdHistory();
        var source=deployRequestRepository.findById(66L).orElseThrow();
        source.setSelectedServerGroupIds(List.of(999L));
        assertThatThrownBy(() -> service.assertRollbackSuggestionAvailable(77L,1L,currentUser()))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.assertRollbackSuggestionAvailable(77L,2L,currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("프로젝트");
        verify(deploymentPlanSnapshotService,never()).currentApprovalPolicy(any(),anyList(),any());
        verify(deployRequestRepository,never()).save(any());
    }

    private void withGroupApproval(DeployEnvironment environment) {
        var group = com.portfolio.deployops.domain.ServerGroup.builder()
            .id(environment == DeployEnvironment.TEST ? 801L : 802L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.valueOf(environment.name()))
            .approvalPolicyId(91L).build();
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(group));
    }

    @Test
    void testGroupApprovalBlocksReadyDispatchEvenWhenGlobalApprovalIsOff() {
        withGroupApproval(DeployEnvironment.TEST);
        when(executionPolicyService.requireApproval()).thenReturn(false);
        var form = prodForm("release-1","sha256:"+"a".repeat(64));
        form.setEnvironment("TEST"); form.setPromotionSourceRequestId(null);
        var saved = service.create(form,currentUser());
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getApprovalDecisionSource()).isNull();
        verify(eventPublisher,never()).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void prodGroupApprovalCannotBeDisabledOrAutomaticallyApproved() {
        withGroupApproval(DeployEnvironment.PROD);
        when(executionPolicyService.requireApproval()).thenReturn(false);
        when(executionPolicyService.shouldAutoApproveVerifiedPromotion(any(),any(),any(Boolean.class))).thenReturn(true);
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(
            confirmedTestRequest("release-1","sha256:"+"a".repeat(64),10L,"abc123")));
        var saved=service.create(prodForm(null,null),currentUser());
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getApprovalDecisionSource()).isNull();
        verify(eventPublisher,never()).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void testRollbackAlsoRequiresSelectedGroupApproval() {
        withGroupApproval(DeployEnvironment.TEST);
        when(projectImageHistoryService.findById(77L)).thenReturn(
            history(DeployEnvironment.TEST,ProjectImageHistoryStatus.TEST_DEPLOYED,"release-1","sha256:"+"a".repeat(64)));
        var saved=service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST),currentUser());
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        verify(eventPublisher,never()).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void groupOnlyProdRollbackChecksProvenanceBeforeCaptureAndRequiresMaterializedPolicyAfterwards() {
        verifiedProdHistory();
        project.setApprovalPolicy(null);
        withGroupApproval(DeployEnvironment.PROD);
        var original=ApprovalPolicy.builder().id(91L).build();
        var effective=ApprovalPolicy.builder().requiredPolicies(List.of(original)).build();
        when(deploymentPlanSnapshotService.applySnapshot(any())).thenAnswer(inv -> {
            DeployRequest request=inv.getArgument(0);
            if (Long.valueOf(100L).equals(request.getId())) request.getProject().setApprovalPolicy(effective);
            return false;
        });
        var saved=service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD),currentUser());
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getProject().getApprovalPolicy()).isSameAs(effective);
        verify(eventPublisher,never()).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void groupOnlyRollbackCannotPassWhenSnapshotDidNotMaterializePolicy() {
        verifiedProdHistory();project.setApprovalPolicy(null);
        withGroupApproval(DeployEnvironment.PROD);
        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD),currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("승인 정책");
        verify(eventPublisher,never()).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void manualRollbackGroupIsRejectedBeforeArtifactsOrPersistence() {
        var group=com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST)
            .failurePolicy(com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK).build();
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(group));
        var form=new DeployRequestCreateForm();
        form.setProjectId(1L);form.setEnvironment("TEST");form.setDeployMode("IMAGE_ONLY");form.setDeployStrategy("MANUAL");
        assertThatThrownBy(() -> service.create(form,currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("수동 전략").hasMessageContaining("ROLLBACK");
        verify(deployRequestRepository,never()).save(any());
        verify(deploymentPlanSnapshotService,never()).capture(any());
        org.mockito.Mockito.verifyNoInteractions(nexusMetadataService,eventPublisher);
    }

    @Test
    void multipleActiveComponentsOfOneRoleAreRejectedBeforeArtifactsOrPersistence() {
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().id(11L).role(com.portfolio.deployops.domain.ServerType.WAS).active(true).build(),
            com.portfolio.deployops.domain.DeployComponent.builder().id(12L).role(com.portfolio.deployops.domain.ServerType.WAS).active(true).build()));
        var form=new DeployRequestCreateForm();
        form.setProjectId(1L);form.setEnvironment("TEST");form.setDeployMode("IMAGE_ONLY");
        assertThatThrownBy(() -> service.create(form,currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("같은 역할(WAS)").hasMessageContaining("프로젝트로 분리");
        verify(deployRequestRepository,never()).save(any());
        verify(deploymentPlanSnapshotService,never()).capture(any());
        org.mockito.Mockito.verifyNoInteractions(nexusMetadataService,eventPublisher);
    }

    @Test
    void historicalPlanWithDuplicateActiveRoleIsRejectedBeforeManifestExecution() {
        var frozen=Project.builder().components(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().id(11L).role(com.portfolio.deployops.domain.ServerType.WAS).active(true).build(),
            com.portfolio.deployops.domain.DeployComponent.builder().id(12L).role(com.portfolio.deployops.domain.ServerType.WAS).active(true).build())).build();
        var request=DeployRequest.builder().project(frozen).environment(DeployEnvironment.TEST).build();
        assertThatThrownBy(() -> service.assertPlanUnchanged(request))
            .isInstanceOfSatisfying(ResponseStatusException.class,error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getReason()).contains("같은 역할(WAS)");
            });
        verify(deployRequestRepository,never()).updateExecutedBy(any(),any());
    }

    @Test
    void explicitWasWithMappedWebRejectsBuildRequestBeforePersistenceOrDispatch() {
        var was = com.portfolio.deployops.domain.Server.builder().serverType(com.portfolio.deployops.domain.ServerType.WAS).build();
        var web = com.portfolio.deployops.domain.Server.builder().serverType(com.portfolio.deployops.domain.ServerType.WEB).build();
        when(serverRepository.findByServerGroupId(801L)).thenReturn(List.of(was, web));
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().role(com.portfolio.deployops.domain.ServerType.WAS).active(true).build()));
        DeployRequestCreateForm form = new DeployRequestCreateForm();
        form.setProjectId(1L); form.setEnvironment("TEST"); form.setDeployMode("BUILD_AND_DEPLOY"); form.setBranch("main");
        assertThatThrownBy(() -> service.create(form, currentUser()))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getReason()).contains("WEB", "활성 배포 단위");
            });
        verify(deployRequestRepository, never()).save(any());
        verify(deploymentPlanSnapshotService, never()).capture(any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher, nexusMetadataService);
    }

    @Test
    void frozenPlanMissingRoleRefusesBeforeExecution() {
        var group = com.portfolio.deployops.domain.ServerGroup.builder().environment(com.portfolio.deployops.domain.ServerEnvironment.TEST)
            .servers(List.of(com.portfolio.deployops.domain.Server.builder().serverType(com.portfolio.deployops.domain.ServerType.WEB).build())).build();
        var frozenProject = Project.builder().serverGroups(List.of(group)).components(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().role(com.portfolio.deployops.domain.ServerType.WAS).active(true).build())).build();
        var request = DeployRequest.builder().project(frozenProject).environment(DeployEnvironment.TEST).build();
        assertThatThrownBy(() -> service.assertPlanUnchanged(request))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getReason()).contains("WEB", "활성 배포 단위");
            });
        verify(deploymentPlanSnapshotService).assertCurrentPlanMatches(request);
        verify(deployRequestRepository, never()).updateExecutedBy(any(), any());
    }

    @Test
    void existingTestImageCopiesAuthoritativeHistoryScmBeforeSnapshotWithoutStartingBuild() throws Exception {
        DeployRequestCreateForm form = prodForm("release-1", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        form.setEnvironment("TEST");
        form.setPromotionSourceRequestId(null);
        String sha = "0123456789abcdef0123456789abcdef01234567";
        when(projectImageHistoryService.findExactImageProvenance(project, form.getImageTag(), form.getImageDigest()))
            .thenReturn(Optional.of(new ProjectImageHistoryService.ImageProvenance(4L, sha)));
        DeployRequest saved = service.create(form, currentUser());
        assertThat(saved.getGitlabPipelineId()).isEqualTo(4L);
        assertThat(saved.getCommitSha()).isEqualTo(sha);
        assertThat(saved.getSourceMrIid()).isNull();
        assertThat(saved.getImmutableBuildRef()).isNull();
        verify(deploymentPlanSnapshotService).capture(saved);
    }

    @Test
    void promotionOfHistoricalBlankTestScmUsesExactHistoryWithoutChangingSource() throws Exception {
        DeployRequest source = confirmedTestRequest("release-1", "sha256:test", null, null);
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(source));
        String sha = "0123456789abcdef0123456789abcdef01234567";
        when(projectImageHistoryService.findExactImageProvenance(project, "release-1", "sha256:test"))
            .thenReturn(Optional.of(new ProjectImageHistoryService.ImageProvenance(4L, sha)));
        DeployRequest saved = service.create(prodForm("ignored", "sha256:ignored"), currentUser());
        assertThat(saved.getGitlabPipelineId()).isEqualTo(4L);
        assertThat(saved.getCommitSha()).isEqualTo(sha);
        assertThat(source.getGitlabPipelineId()).isNull();
        assertThat(source.getCommitSha()).isNull();
    }

    @Test
    void conflictingImageScmIsRejectedBeforeRequestOrSnapshotPersistence() throws Exception {
        DeployRequestCreateForm form = prodForm("release-1", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        form.setEnvironment("TEST");
        form.setPromotionSourceRequestId(null);
        form.setGitlabPipelineId(999L);
        when(projectImageHistoryService.findExactImageProvenance(project, form.getImageTag(), form.getImageDigest()))
            .thenReturn(Optional.of(new ProjectImageHistoryService.ImageProvenance(4L, "0123456789abcdef0123456789abcdef01234567")));
        assertThatThrownBy(() -> service.create(form, currentUser())).isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(deployRequestRepository, never()).save(any());
        verify(deploymentPlanSnapshotService, never()).capture(any());
    }

    private final DeployRequestRepository deployRequestRepository = mock(DeployRequestRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ApprovalRecordRepository approvalRecordRepository = mock(ApprovalRecordRepository.class);
    private final StatusHistoryRepository statusHistoryRepository = mock(StatusHistoryRepository.class);
    private final ServerRepository serverRepository = mock(ServerRepository.class);
    private final ProjectImageHistoryService projectImageHistoryService = mock(ProjectImageHistoryService.class);
    private final OfficeNotificationService officeNotificationService = mock(OfficeNotificationService.class);
    private final ProjectPermissionService projectPermissionService = mock(ProjectPermissionService.class);
    private final NexusMetadataService nexusMetadataService = mock(NexusMetadataService.class);
    private final DeployComponentRepository deployComponentRepository = mock(DeployComponentRepository.class);
    private final DeployRequestItemRepository deployRequestItemRepository = mock(DeployRequestItemRepository.class);
    private final ExecutionPolicyService executionPolicyService = mock(ExecutionPolicyService.class);
    private final DeploymentPlanSnapshotService deploymentPlanSnapshotService = mock(DeploymentPlanSnapshotService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MergeRequestEvidenceRepository mergeRequestEvidenceRepository = mock(MergeRequestEvidenceRepository.class);

    private final DeployRequestService service = new DeployRequestService(
        deployRequestRepository,
        projectRepository,
        approvalRecordRepository,
        statusHistoryRepository,
        serverRepository,
        projectImageHistoryService,
        officeNotificationService,
        projectPermissionService,
        nexusMetadataService,
        deployComponentRepository,
        deployRequestItemRepository,
        executionPolicyService,
        deploymentPlanSnapshotService,
        auditLogService,
        eventPublisher,
        mergeRequestEvidenceRepository
    );

    private final Project project = Project.builder()
        .id(1L)
        .name("payment-api")
        .defaultBranch("main")
        .build();

    @BeforeEach
    void setUp() {
        project.setApprovalPolicy(ApprovalPolicy.builder().id(90L).name("production").build());
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        // Existing happy paths now use actual hydrated TEST and PROD WAS targets.
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
                .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST).build(),
            com.portfolio.deployops.domain.ServerGroup.builder().id(802L)
                .environment(com.portfolio.deployops.domain.ServerEnvironment.PROD).build()));
        when(serverRepository.findByServerGroupId(801L)).thenReturn(List.of(
            com.portfolio.deployops.domain.Server.builder().id(901L)
                .serverType(com.portfolio.deployops.domain.ServerType.WAS).build()));
        when(serverRepository.findByServerGroupId(802L)).thenReturn(List.of(
            com.portfolio.deployops.domain.Server.builder().id(902L)
                .serverType(com.portfolio.deployops.domain.ServerType.WAS).build()));
        when(projectRepository.lockById(1L)).thenReturn(1L);
        when(deployRequestRepository.lockById(100L)).thenReturn(100L);
        when(deployRequestRepository.existsByReqNo(any())).thenReturn(false);
        when(deployRequestRepository.existsByProjectIdAndStatusIn(any(), anyList())).thenReturn(false);
        when(executionPolicyService.requireTestFirst()).thenReturn(true);
        when(executionPolicyService.requireApproval()).thenReturn(true);
        when(deployRequestRepository.save(any())).thenAnswer(invocation -> {
            DeployRequest request = invocation.getArgument(0);
            if (request.getId() == null) {
                request.setId(100L);
            }
            return request;
        });
    }

    @Test
    void createProdPromotionUsesOnlyLatestConfirmedTestImageMetadata() throws Exception {
        DeployRequest confirmedTest = confirmedTestRequest("main-abc-10", "sha256:test", 10L, "abc123");
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(confirmedTest));

        DeployRequest saved = service.create(prodForm("malicious", "sha256:malicious"), currentUser());

        assertThat(saved.getEnvironment()).isEqualTo(DeployEnvironment.PROD);
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getBranch()).isEqualTo("main");
        assertThat(saved.getImageTag()).isEqualTo("main-abc-10");
        assertThat(saved.getImageDigest()).isEqualTo("sha256:test");
        assertThat(saved.getGitlabPipelineId()).isEqualTo(10L);
        assertThat(saved.getCommitSha()).isEqualTo("abc123");
        verify(nexusMetadataService, never()).resolveManifest(any(), any());
    }

    @Test
    void partialTestCannotBePromotedAsWholeRelease() {
        DeployRequest source = confirmedTestRequest("release-1", "sha256:" + "a".repeat(64), 10L, "abc");
        source.setDeployScope(DeployScope.WAS_ONLY);
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(source));
        assertThatThrownBy(() -> service.create(prodForm(null, null), currentUser()))
            .hasMessageContaining("확인하지 않은 배포 범위");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void verifiedPromotionStoresAutomaticDecisionWithoutForgingManualApproval() throws Exception {
        DeployRequest confirmedTest = confirmedTestRequest(
            "release-01234567-77",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            77L,
            "0123456789abcdef0123456789abcdef01234567");
        confirmedTest.setTestConfirmedBy(User.builder().id(9L).active(true).build());
        confirmedTest.setTestConfirmedAt(LocalDateTime.of(2026, 9, 14, 10, 0));
        ReleaseVerificationEvidence.markVerifiedCiArtifact(
            confirmedTest, 701L, "registry/payment-api", LocalDateTime.of(2026, 9, 14, 9, 0));
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(confirmedTest));
        when(executionPolicyService.shouldAutoApproveVerifiedPromotion(any(), any(), any(Boolean.class)))
            .thenReturn(true);

        DeployRequest saved = service.create(prodForm(null, null), currentUser());

        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_COMPLETED);
        assertThat(saved.getApprovalDecisionSource())
            .isEqualTo(ApprovalDecisionSource.AUTO_VERIFIED_PROMOTION);
        verify(deploymentPlanSnapshotService).assertCurrentPlanMatches(confirmedTest);
        verify(approvalRecordRepository, never()).save(any());
        verify(auditLogService).record(
            org.mockito.ArgumentMatchers.eq(AutomaticDispatchService.SYSTEM_ACTOR),
            org.mockito.ArgumentMatchers.contains("자동 승인"),
            org.mockito.ArgumentMatchers.eq("DeployRequest"),
            org.mockito.ArgumentMatchers.eq("100"),
            any(), any(),
            org.mockito.ArgumentMatchers.eq(AutomaticDispatchService.SYSTEM_IP));
        verify(eventPublisher).publishEvent(any(DeploymentReadyEvent.class));
    }

    @Test
    void changedTestPlanFallsBackToManualApprovalInsteadOfAutoApproving() throws Exception {
        DeployRequest confirmedTest = confirmedTestRequest(
            "release-01234567-77",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            77L,
            "0123456789abcdef0123456789abcdef01234567");
        confirmedTest.setTestConfirmedBy(User.builder().id(9L).active(true).build());
        confirmedTest.setTestConfirmedAt(LocalDateTime.of(2026, 9, 14, 10, 0));
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(confirmedTest));
        when(executionPolicyService.shouldAutoApproveVerifiedPromotion(any(), any(), any(Boolean.class)))
            .thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "계획 변경"))
            .when(deploymentPlanSnapshotService).assertCurrentPlanMatches(confirmedTest);

        DeployRequest saved = service.create(prodForm(null, null), currentUser());

        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getApprovalDecisionSource()).isNull();
        verify(auditLogService, never()).record(
            org.mockito.ArgumentMatchers.eq(AutomaticDispatchService.SYSTEM_ACTOR),
            org.mockito.ArgumentMatchers.contains("자동 승인"),
            any(), any(), any(), any(), any());
    }

    @Test
    void componentItemsArePersistedBeforeThePlanSnapshotIsCaptured() {
        DeployRequestCreateForm form = new DeployRequestCreateForm();
        form.setProjectId(1L);
        form.setEnvironment("TEST");
        form.setDeployMode("IMAGE_ONLY");
        form.setImageTag("release-1");
        form.setImageDigest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        ComponentImageForm item = new ComponentImageForm();
        item.setComponentId(11L);
        item.setComponentCode("api");
        item.setImageTag("release-1");
        item.setImageDigest(form.getImageDigest());
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().id(11L).projectId(1L).code("api")
                .role(com.portfolio.deployops.domain.ServerType.WAS).artifactType(com.portfolio.deployops.domain.ArtifactType.CONTAINER)
                .imageRepository("registry/api").active(true).build()));
        form.setComponentImages(List.of(item));

        service.create(form, currentUser());

        InOrder order = inOrder(
            projectRepository, deployRequestRepository, deployRequestItemRepository,
            deploymentPlanSnapshotService);
        order.verify(projectRepository).lockById(1L);
        order.verify(deployRequestRepository).existsByProjectIdAndStatusIn(any(), anyList());
        order.verify(deployRequestRepository).save(any());
        order.verify(deployRequestItemRepository).insert(any());
        order.verify(deploymentPlanSnapshotService).capture(any());
        order.verify(deployRequestRepository).save(any());
    }

    @Test
    void promotionCopiesEntireVerifiedComponentReleaseWithoutTrustingClientOverrides() {
        withWebTargets();
        project.setImageRepository("registry/api");
        var components = ComponentReleaseManifestTest.project().getComponents();
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(components);
        DeployRequest source = confirmedTestRequest("release-1", "sha256:" + "a".repeat(64), 10L, "abc");
        source.getProject().setComponents(components);
        var items = ComponentReleaseManifest.fromForms(source.getProject(), List.of(
            ComponentReleaseManifestTest.form(1L, "api", "sha256:" + "a".repeat(64)),
            ComponentReleaseManifestTest.form(2L, "web", "sha256:" + "b".repeat(64))));
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(source));
        when(deployRequestItemRepository.findByDeployRequestId(50L)).thenReturn(items);
        when(deploymentPlanSnapshotService.applySnapshot(source)).thenReturn(true);
        DeployRequest saved = service.create(prodForm(null, null), currentUser());
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getItems()).extracting(com.portfolio.deployops.domain.DeployRequestItem::getImageDigest)
            .containsExactly("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64));
        assertThat(saved.getItems()).allSatisfy(item -> assertThat(item.getDeployRequestId()).isEqualTo(saved.getId()));
        assertThat(items).allSatisfy(item -> assertThat(item.getDeployRequestId()).isNull());
        assertThat(saved.getSelectedServerGroupIds()).containsExactly(802L);
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
    }

    @Test
    void promotionRejectsAlteredComponentDigestBeforeRequestPersistence() {
        withWebTargets();
        project.setImageRepository("registry/api");
        var components = ComponentReleaseManifestTest.project().getComponents();
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(components);
        DeployRequest source = confirmedTestRequest("release-1", "sha256:" + "a".repeat(64), 10L, "abc");
        source.getProject().setComponents(components);
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(source));
        when(deployRequestItemRepository.findByDeployRequestId(50L)).thenReturn(
            ComponentReleaseManifest.fromForms(source.getProject(), List.of(
                ComponentReleaseManifestTest.form(1L, "api", "sha256:" + "a".repeat(64)),
                ComponentReleaseManifestTest.form(2L, "web", "sha256:" + "b".repeat(64)))));
        when(deploymentPlanSnapshotService.applySnapshot(source)).thenReturn(true);
        DeployRequestCreateForm form = prodForm(null, null);
        form.setComponentImages(List.of(ComponentReleaseManifestTest.form(1L, "api", "sha256:" + "a".repeat(64)),
            ComponentReleaseManifestTest.form(2L, "web", "sha256:" + "c".repeat(64))));
        assertThatThrownBy(() -> service.create(form, currentUser())).hasMessageContaining("다른 배포 단위 산출물");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void promotionRejectsManifestWhoseTestNeverIncludedTheWebTarget() {
        withWebTargets();
        project.setImageRepository("registry/api");
        var components = ComponentReleaseManifestTest.project().getComponents();
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(components);
        DeployRequest source = confirmedTestRequest("release-1", "sha256:" + "a".repeat(64), 10L, "abc");
        source.setProject(ComponentReleaseManifestTest.project());
        source.getProject().setServerGroups(List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
                .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST)
                .servers(List.of(com.portfolio.deployops.domain.Server.builder().id(901L)
                    .serverType(com.portfolio.deployops.domain.ServerType.WAS).build())).build()));
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(source));
        when(deploymentPlanSnapshotService.applySnapshot(source)).thenReturn(true);
        when(deployRequestItemRepository.findByDeployRequestId(50L)).thenReturn(
            ComponentReleaseManifest.fromForms(source.getProject(), List.of(
                ComponentReleaseManifestTest.form(1L, "api", "sha256:" + "a".repeat(64)),
                ComponentReleaseManifestTest.form(2L, "web", "sha256:" + "b".repeat(64)))));
        assertThatThrownBy(() -> service.create(prodForm(null, null), currentUser()))
            .hasMessageContaining("WEB").hasMessageContaining("실제 대상 서버");
        verify(deployRequestRepository, never()).save(any());
    }

    private void withWebTargets() {
        for (long group : new long[]{801L, 802L}) {
            when(serverRepository.findByServerGroupId(group)).thenReturn(List.of(
                com.portfolio.deployops.domain.Server.builder().id(group + 100L)
                    .serverType(com.portfolio.deployops.domain.ServerType.WAS).build(),
                com.portfolio.deployops.domain.Server.builder().id(group + 200L)
                    .serverType(com.portfolio.deployops.domain.ServerType.WEB).build()));
        }
    }

    @Test
    void rollbackManualStrategyChecksCurrentSelectedGroupPolicyBeforePersistence() {
        DeployRequest source = frozenTestRollbackSource(DeployStrategy.MANUAL);
        var currentGroup = com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST)
            .failurePolicy(com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK).build();
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(currentGroup));

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST), currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("수동 전략").hasMessageContaining("ROLLBACK");

        verify(deployRequestRepository, never()).save(any());
        verify(deployRequestItemRepository, never()).insert(any());
        verify(deploymentPlanSnapshotService, never()).captureRollback(any(), any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher, officeNotificationService);
        assertThat(source.getProject().getServerGroups().getFirst().getFailurePolicy())
            .isEqualTo(com.portfolio.deployops.domain.GroupFailurePolicy.STOP);
    }

    @Test
    void rollbackInfersOnlyFrozenGroupsAndKeepsHistoricalComponentsInsteadOfCurrentOnes() {
        DeployRequest source = frozenTestRollbackSource(DeployStrategy.MANUAL);
        source.setSelectedServerGroupIds(null); // Snapshot predates explicit group selection.
        var originalProject = source.getProject();
        var selected = com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST).build();
        var unrelated = com.portfolio.deployops.domain.ServerGroup.builder().id(803L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST)
            .failurePolicy(com.portfolio.deployops.domain.GroupFailurePolicy.ROLLBACK).build();
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(selected, unrelated));
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().id(11L).role(com.portfolio.deployops.domain.ServerType.WAS).build(),
            com.portfolio.deployops.domain.DeployComponent.builder().id(12L).role(com.portfolio.deployops.domain.ServerType.WAS).build()));

        DeployRequest saved = service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST), currentUser());

        assertThat(saved.getSelectedServerGroupIds()).containsExactly(801L);
        assertThat(saved.getDeployScope()).isEqualTo(DeployScope.WAS_ONLY);
        assertThat(saved.getDeployStrategy()).isEqualTo(DeployStrategy.MANUAL);
        assertThat(source.getProject()).isSameAs(originalProject);
        assertThat(source.getProject().getComponents()).isEmpty();
        verify(deployComponentRepository, never()).findActiveByProjectId(any());
        verify(deploymentPlanSnapshotService).captureRollback(saved, source);
    }

    @Test
    void rollbackRejectsAmbiguousFrozenComponentsBeforeCanonicalManifestOrPersistence() {
        DeployRequest source = frozenTestRollbackSource(DeployStrategy.SAFE);
        source.getProject().setComponents(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().id(11L).role(com.portfolio.deployops.domain.ServerType.WAS).build(),
            com.portfolio.deployops.domain.DeployComponent.builder().id(12L).role(com.portfolio.deployops.domain.ServerType.WAS).build()));

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST), currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("같은 역할(WAS)");

        verify(deployRequestRepository, never()).save(any());
        verify(deployRequestItemRepository, never()).insert(any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher, officeNotificationService);
    }

    @Test
    void rollbackRejectsRemovedOriginalGroupRatherThanExpandingToNewMappedGroups() {
        DeployRequest source = frozenTestRollbackSource(DeployStrategy.SAFE);
        source.setSelectedServerGroupIds(null);
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(803L)
                .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST).build()));

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST), currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이 프로젝트에 연결되어 있지 않거나");

        verify(deployRequestRepository, never()).save(any());
        verify(deploymentPlanSnapshotService, never()).captureRollback(any(), any());
    }

    @Test
    void legacyRollbackRejectsAmbiguousCurrentComponentsBeforePersistence() {
        var history = history(DeployEnvironment.TEST, ProjectImageHistoryStatus.TEST_DEPLOYED,
            "legacy-stable", "sha256:" + "a".repeat(64));
        when(projectImageHistoryService.findById(77L)).thenReturn(history);
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.DeployComponent.builder().id(11L).role(com.portfolio.deployops.domain.ServerType.WAS).build(),
            com.portfolio.deployops.domain.DeployComponent.builder().id(12L).role(com.portfolio.deployops.domain.ServerType.WAS).build()));

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST), currentUser()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("같은 역할(WAS)");

        verify(deployRequestRepository, never()).save(any());
        verify(deploymentPlanSnapshotService, never()).capture(any());
    }

    private DeployRequest frozenTestRollbackSource(DeployStrategy strategy) {
        var originalGroup = com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
            .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST).build();
        var originalProject = Project.builder().id(1L).name("original-api")
            .serverGroups(List.of(originalGroup)).components(List.of()).build();
        var source = DeployRequest.builder().id(66L).project(originalProject)
            .environment(DeployEnvironment.TEST).selectedServerGroupIds(List.of(801L))
            .deployScope(DeployScope.WAS_ONLY).deployStrategy(strategy)
            .planSnapshotJson("{\"original\":true}").build();
        var history = history(DeployEnvironment.TEST, ProjectImageHistoryStatus.TEST_DEPLOYED,
            "original-stable", "sha256:" + "a".repeat(64));
        history.setDeployRequest(source);
        when(projectImageHistoryService.findById(77L)).thenReturn(history);
        when(deployRequestRepository.findById(66L)).thenReturn(Optional.of(source));
        when(deploymentPlanSnapshotService.applySnapshot(source)).thenReturn(true);
        return source;
    }

    @Test
    void rollbackCopiesAllOriginalComponentsAndUsesOriginalConfigurationSnapshot() {
        ProjectImageHistory history = verifiedProdHistory();
        DeployRequest source = deployRequestRepository.findById(66L).orElseThrow();
        source.setPlanSnapshotJson("{\"original\":true}");
        source.setSelectedServerGroupIds(List.of(802L));
        var components = ComponentReleaseManifestTest.project().getComponents();
        source.getProject().setComponents(components);
        var items = ComponentReleaseManifest.fromForms(source.getProject(), List.of(
            ComponentReleaseManifestTest.form(1L, "api", "sha256:" + "a".repeat(64)),
            ComponentReleaseManifestTest.form(2L, "web", "sha256:" + "b".repeat(64))));
        when(deployRequestItemRepository.findByDeployRequestId(66L)).thenReturn(items);
        when(deploymentPlanSnapshotService.applySnapshot(source)).thenReturn(true);
        DeployRequest saved = service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser());
        assertThat(saved.getItems()).extracting(com.portfolio.deployops.domain.DeployRequestItem::getComponentCode)
            .containsExactly("api", "web");
        assertThat(saved.getSelectedServerGroupIds()).containsExactly(802L);
        verify(deploymentPlanSnapshotService).captureRollback(saved, source);
        verify(deploymentPlanSnapshotService, never()).capture(any());
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
    }

    @Test
    void buildRequestIsRejectedWhenMrEvidencePolicyIsEnabledAndIidIsMissing() {
        when(executionPolicyService.requireMrEvidenceForBuild()).thenReturn(true);
        DeployRequestCreateForm form = new DeployRequestCreateForm();
        form.setProjectId(1L);
        form.setEnvironment("TEST");
        form.setDeployMode("BUILD_AND_DEPLOY");
        form.setBranch("main");

        assertThatThrownBy(() -> service.create(form, currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("승인·병합한 MR");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void mergedMrEvidenceBindsExactShaAndImmutableRefIntoRequest() {
        project.setGitlabProjectId(101L);
        String mergedSha = "0123456789abcdef0123456789abcdef01234567";
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(1L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .targetBranch("main").mergedResultSha(mergedSha)
            .status(MergeRequestEvidenceStatus.MERGED).build();
        when(mergeRequestEvidenceRepository.findMerged(1L, 101L, 4L)).thenReturn(Optional.of(evidence));
        DeployRequestCreateForm form = new DeployRequestCreateForm();
        form.setProjectId(1L);
        form.setEnvironment("TEST");
        form.setDeployMode("BUILD_AND_DEPLOY");
        form.setSourceMergeRequestIid(4L);

        DeployRequest saved = service.create(form, currentUser());

        assertThat(saved.getExpectedCommitSha()).isEqualTo(mergedSha);
        assertThat(saved.getSourceMrIid()).isEqualTo(4L);
        assertThat(saved.getMrEvidenceId()).isEqualTo(91L);
        assertThat(saved.getImmutableBuildRef()).isEqualTo("deploy/mr-4-0123456789ab");
        assertThat(saved.getBranch()).isEqualTo("main");
        assertThat(saved.getCommitSha()).isNull();
    }

    @Test
    void verifiedBuildMetadataIsBoundIntoSnapshotBeforeSaving() {
        DeployRequest request = DeployRequest.builder()
            .id(100L)
            .reqNo("REQ-100")
            .project(project)
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_BUILDING)
            .build();
        when(deployRequestRepository.findByIdWithProject(100L)).thenReturn(Optional.of(request));

        project.setImageRepository("registry/payment-api");
        service.updateVerifiedBuildMetadata(100L, new GitlabBuildArtifactMetadata(
            701L,
            77L,
            "registry/payment-api",
            "release-01234567-77",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0123456789abcdef0123456789abcdef01234567"));

        InOrder order = inOrder(deploymentPlanSnapshotService, deployRequestRepository);
        order.verify(deploymentPlanSnapshotService).bindVerifiedRelease(request);
        order.verify(deployRequestRepository).save(request);
        assertThat(request.getGitlabPipelineId()).isEqualTo(77L);
        assertThat(request.getReleaseVerificationStatus())
            .isEqualTo(ReleaseVerificationStatus.VERIFIED_CI_ARTIFACT);
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(request)).isTrue();
    }

    private void multiComponents() {
        project.setImageRepository("registry/qa/api"); project.setGitlabProjectId(9L);
        when(deployComponentRepository.findActiveByProjectId(1L)).thenReturn(GitlabReleaseManifestTest.project().getComponents());
        for (long groupId : List.of(801L,802L)) {
            when(serverRepository.findByServerGroupId(groupId)).thenReturn(List.of(
                com.portfolio.deployops.domain.Server.builder().id(groupId+100).serverType(com.portfolio.deployops.domain.ServerType.WAS).build(),
                com.portfolio.deployops.domain.Server.builder().id(groupId+200).serverType(com.portfolio.deployops.domain.ServerType.WEB).build()));
        }
    }

    @Test
    void multiBuildAcceptsOnlyEmptyCiSlotFromFixedMergedMr() {
        multiComponents();
        when(mergeRequestEvidenceRepository.findMerged(1L,9L,4L)).thenReturn(Optional.of(
            MergeRequestEvidence.builder().id(91L).projectId(1L).gitlabProjectId(9L).mergeRequestIid(4L)
                .targetBranch("main").mergedResultSha(GitlabReleaseManifestTest.SHA)
                .status(MergeRequestEvidenceStatus.MERGED).build()));
        var form = new DeployRequestCreateForm();
        form.setProjectId(1L); form.setEnvironment("TEST"); form.setDeployMode("BUILD_AND_DEPLOY");
        form.setSourceMergeRequestIid(4L);
        var pending = service.create(form,currentUser());
        assertThat(pending.getItems()).isEmpty(); assertThat(pending.getImageDigest()).isNull();
        assertThat(pending.getExpectedCommitSha()).isEqualTo(GitlabReleaseManifestTest.SHA);
        assertThat(pending.getReleaseManifestSha256()).isNull();
        verify(deploymentPlanSnapshotService).capture(pending);
        verify(deployRequestItemRepository,never()).insert(any());
        var manual = new ComponentImageForm(); manual.setComponentCode("api"); manual.setImageDigest(GitlabReleaseManifestTest.API);
        form.setComponentImages(List.of(manual));
        assertThatThrownBy(() -> service.create(form,currentUser())).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("수동");
        form.setComponentImages(List.of()); form.setSourceMergeRequestIid(null); form.setBranch("main");
        assertThatThrownBy(() -> service.create(form,currentUser())).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("MR");
    }

    @Test
    void completeCiReleaseBindsItemsEvidenceAndSnapshotOnceUnderRequestLock() {
        multiComponents();
        var storedItems = new java.util.ArrayList<com.portfolio.deployops.domain.DeployRequestItem>();
        when(deployRequestItemRepository.findByDeployRequestId(100L)).thenAnswer(call -> List.copyOf(storedItems));
        when(deployRequestItemRepository.insert(any())).thenAnswer(call -> { storedItems.add(call.getArgument(0)); return 1; });
        var request = DeployRequest.builder().id(100L).project(project).environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_BUILDING).expectedCommitSha(GitlabReleaseManifestTest.SHA).build();
        when(deployRequestRepository.findByIdWithProject(100L)).thenReturn(Optional.of(request));
        var metadata = GitlabReleaseManifest.parse(GitlabReleaseManifestTest.body(),
            GitlabReleaseManifestTest.project(),GitlabReleaseManifestTest.primary());
        var result = service.updateVerifiedBuildMetadata(100L,metadata);
        assertThat(result.getItems()).extracting(com.portfolio.deployops.domain.DeployRequestItem::getComponentCode)
            .containsExactly("api","web");
        assertThat(result.getReleaseManifestSha256()).isEqualTo(metadata.releaseManifestSha256());
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(result)).isTrue();
        var order = inOrder(deployRequestRepository, deploymentPlanSnapshotService, deployRequestItemRepository);
        order.verify(deployRequestRepository).lockById(100L);
        order.verify(deploymentPlanSnapshotService).assertBuildConfigurationMatches(request);
        order.verify(deployRequestItemRepository,org.mockito.Mockito.times(2)).insert(any());
        order.verify(deploymentPlanSnapshotService).bindVerifiedRelease(request);
        order.verify(deployRequestRepository).save(request);
        service.updateVerifiedBuildMetadata(100L,metadata);
        assertThat(storedItems).hasSize(2);
        verify(deploymentPlanSnapshotService,org.mockito.Mockito.times(1)).bindVerifiedRelease(request);
        var changed = GitlabReleaseManifest.parse(GitlabReleaseManifestTest.body().replace(GitlabReleaseManifestTest.WEB,"sha256:"+"d".repeat(64)),
            GitlabReleaseManifestTest.project(),GitlabReleaseManifestTest.primary());
        assertThatThrownBy(() -> service.updateVerifiedBuildMetadata(100L,changed)).isInstanceOf(ResponseStatusException.class);
        assertThat(storedItems).hasSize(2);
    }

    @Test
    void missingMultiArtifactOrConfigurationDriftWritesNoPartialItems() {
        multiComponents();
        var request = DeployRequest.builder().id(100L).project(project).environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_BUILDING).expectedCommitSha(GitlabReleaseManifestTest.SHA).build();
        when(deployRequestRepository.findByIdWithProject(100L)).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.updateVerifiedBuildMetadata(100L,GitlabReleaseManifestTest.primary()))
            .isInstanceOf(RemoteExecutionException.class);
        var metadata = GitlabReleaseManifest.parse(GitlabReleaseManifestTest.body(),
            GitlabReleaseManifestTest.project(),GitlabReleaseManifestTest.primary());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"승인 구성 변경"))
            .when(deploymentPlanSnapshotService).assertBuildConfigurationMatches(request);
        assertThatThrownBy(() -> service.updateVerifiedBuildMetadata(100L,metadata)).isInstanceOf(ResponseStatusException.class);
        verify(deployRequestItemRepository,never()).insert(any());
        verify(deployRequestRepository,never()).save(any());
        assertThat(request.getImageDigest()).isNull();
    }

    @Test
    void verifiedBuildMetadataRejectsArtifactThatDoesNotMatchExpectedMrSha() {
        DeployRequest request = DeployRequest.builder()
            .id(100L).reqNo("REQ-100").project(project).environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_BUILDING)
            .expectedCommitSha("0123456789abcdef0123456789abcdef01234567")
            .build();
        when(deployRequestRepository.findByIdWithProject(100L)).thenReturn(Optional.of(request));
        project.setImageRepository("registry/payment-api");

        assertThatThrownBy(() -> service.updateVerifiedBuildMetadata(100L,
            new GitlabBuildArtifactMetadata(701L, 77L, "registry/payment-api", "release-tag",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "ffffffffffffffffffffffffffffffffffffffff")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("pipeline artifact SHA");
        verify(deploymentPlanSnapshotService, never()).bindVerifiedRelease(any());
    }

    @Test
    void createProdPromotionRejectsConfirmedTestWithoutDigest() {
        DeployRequest confirmedTest = confirmedTestRequest("main-abc-10", null, 10L, "abc123");
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(confirmedTest));

        assertThatThrownBy(() -> service.create(prodForm(null, null), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("immutable image tag/digest");
    }

    @Test
    void prodRequestWithoutTestFirstUsesValidatedFormArtifact() throws Exception {
        when(executionPolicyService.requireTestFirst()).thenReturn(false);
        DeployRequestCreateForm form = prodForm(
            "release-manual-1",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        form.setPromotionSourceRequestId(null);
        form.setGitlabPipelineId(77L);
        form.setCommitSha("0123456789abcdef0123456789abcdef01234567");

        DeployRequest saved = service.create(form, currentUser());

        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getImageTag()).isEqualTo("release-manual-1");
        assertThat(saved.getImageDigest())
            .isEqualTo("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(saved.getGitlabPipelineId()).isEqualTo(77L);
        assertThat(saved.getCommitSha()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(saved.getReleaseVerificationStatus()).isEqualTo(ReleaseVerificationStatus.UNVERIFIED);
        assertThat(saved.getPromotedFromRequest()).isNull();
    }

    @Test
    void createRollbackRequestUsesStableHistoryImageMetadata() {
        ProjectImageHistory history = verifiedProdHistory();
        SecurityUserDetails currentUser = currentUser();
        when(projectImageHistoryService.findById(77L)).thenReturn(history);

        DeployRequest saved = service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser);

        assertThat(saved.getEnvironment()).isEqualTo(DeployEnvironment.PROD);
        assertThat(saved.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(saved.getBranch()).isEqualTo("main");
        assertThat(saved.getImageTag()).isEqualTo(history.getImageTag());
        assertThat(saved.getImageDigest()).isEqualTo(history.getImageDigest());
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(saved)).isTrue();
        assertThat(saved.getReason()).startsWith(DeployRequestService.ROLLBACK_REASON_PREFIX + "[history:77]");
        verify(projectPermissionService).assertPermission(
            currentUser,
            1L,
            ProjectPermissionType.ROLLBACK_EXECUTE,
            DeployEnvironment.PROD
        );
    }

    @Test
    void createRollbackRequestPreservesTheSourceDeploymentScopeAndStrategy() {
        ProjectImageHistory history = history(
            DeployEnvironment.TEST,
            ProjectImageHistoryStatus.TEST_DEPLOYED,
            "main-stable-12",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        history.setDeployRequest(DeployRequest.builder()
            .id(66L)
            .deployScope(DeployScope.WEB_ONLY)
            .deployStrategy(DeployStrategy.WAVE)
            .build());
        when(projectImageHistoryService.findById(77L)).thenReturn(history);

        DeployRequest saved = service.createRollbackRequest(rollbackForm(DeployEnvironment.TEST), currentUser());

        assertThat(saved.getDeployScope()).isEqualTo(DeployScope.WEB_ONLY);
        assertThat(saved.getDeployStrategy()).isEqualTo(DeployStrategy.WAVE);
    }

    @Test
    void createRollbackRequestReusesOnlyMatchingVerifiedCiEvidence() {
        String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String commitSha = "0123456789abcdef0123456789abcdef01234567";
        ProjectImageHistory history = history(
            DeployEnvironment.PROD,
            ProjectImageHistoryStatus.PROD_DEPLOYED,
            "release-01234567-77",
            digest);
        history.setGitlabPipelineId(77L);
        history.setCommitSha(commitSha);
        history.setImageRepository("registry/payment-api");
        history.setDeployRequest(DeployRequest.builder().id(66L).build());
        project.setImageRepository("registry/payment-api");

        DeployRequest source = DeployRequest.builder()
            .id(66L)
            .project(project)
            .environment(DeployEnvironment.PROD)
            .imageTag(history.getImageTag())
            .imageDigest(digest)
            .gitlabPipelineId(77L)
            .commitSha(commitSha)
            .build();
        ReleaseVerificationEvidence.markVerifiedCiArtifact(
            source,
            701L,
            "registry/payment-api",
            LocalDateTime.of(2026, 9, 22, 9, 0));
        when(projectImageHistoryService.findById(77L)).thenReturn(history);
        when(deployRequestRepository.findById(66L)).thenReturn(Optional.of(source));

        DeployRequest saved = service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser());

        assertThat(saved.getReleaseVerificationStatus())
            .isEqualTo(ReleaseVerificationStatus.VERIFIED_CI_ARTIFACT);
        assertThat(saved.getReleaseVerificationJobId()).isEqualTo(701L);
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(saved)).isTrue();
    }

    @Test
    void createRollbackRequestRejectsEvidenceWhenHistoryArtifactWasAltered() {
        String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String commitSha = "0123456789abcdef0123456789abcdef01234567";
        ProjectImageHistory history = history(
            DeployEnvironment.PROD,
            ProjectImageHistoryStatus.PROD_DEPLOYED,
            "release-01234567-77",
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        history.setGitlabPipelineId(77L);
        history.setCommitSha(commitSha);
        history.setImageRepository("registry/payment-api");
        history.setDeployRequest(DeployRequest.builder().id(66L).build());
        project.setImageRepository("registry/payment-api");

        DeployRequest source = DeployRequest.builder()
            .id(66L)
            .project(project)
            .environment(DeployEnvironment.PROD)
            .imageTag(history.getImageTag())
            .imageDigest(digest)
            .gitlabPipelineId(77L)
            .commitSha(commitSha)
            .build();
        ReleaseVerificationEvidence.markVerifiedCiArtifact(
            source,
            701L,
            "registry/payment-api",
            LocalDateTime.of(2026, 9, 22, 9, 0));
        when(projectImageHistoryService.findById(77L)).thenReturn(history);
        when(deployRequestRepository.findById(66L)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void createRollbackRequestRejectsEvidenceAfterProjectRepositoryChanged() {
        String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String commitSha = "0123456789abcdef0123456789abcdef01234567";
        ProjectImageHistory history = history(
            DeployEnvironment.PROD,
            ProjectImageHistoryStatus.PROD_DEPLOYED,
            "release-01234567-77",
            digest);
        history.setGitlabPipelineId(77L);
        history.setCommitSha(commitSha);
        history.setImageRepository("registry/old-payment-api");
        history.setDeployRequest(DeployRequest.builder().id(66L).build());
        project.setImageRepository("registry/new-payment-api");

        DeployRequest source = DeployRequest.builder()
            .id(66L)
            .project(project)
            .environment(DeployEnvironment.PROD)
            .imageTag(history.getImageTag())
            .imageDigest(digest)
            .gitlabPipelineId(77L)
            .commitSha(commitSha)
            .build();
        ReleaseVerificationEvidence.markVerifiedCiArtifact(
            source,
            701L,
            "registry/old-payment-api",
            LocalDateTime.of(2026, 9, 22, 9, 0));
        when(projectImageHistoryService.findById(77L)).thenReturn(history);
        when(deployRequestRepository.findById(66L)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("저장소");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void rollbackRequestCapturesCurrentPortsAndPreservesThemForItsApprovedExecution() {
        project.setContainerPorts("9080:9080,9090:9090");
        ProjectImageHistory original = verifiedProdHistory();
        original.setImageTag("old-release");
        DeployRequest source = deployRequestRepository.findById(66L).orElseThrow();
        source.setImageTag("old-release");
        ReleaseVerificationEvidence.markVerifiedCiArtifact(source, 701L, project.getImageRepository(),
            LocalDateTime.of(2026, 9, 22, 9, 0));
        DeploymentPlanSnapshotService snapshots = new DeploymentPlanSnapshotService(
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(), projectRepository,
            serverRepository, deployComponentRepository, mock(com.portfolio.deployops.repository.ApprovalStepRepository.class),
            deployRequestItemRepository, mock(SystemSettingService.class), mock(RuntimeConfigurationService.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            snapshots.capture(invocation.getArgument(0));
            return null;
        }).when(deploymentPlanSnapshotService).capture(any());

        DeployRequest saved = service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser());
        assertThat(saved.getPlanSnapshotJson()).contains("\"containerPorts\":\"9080:9080,9090:9090\"");
        project.setContainerPorts("9999:9090");
        assertThatThrownBy(() -> snapshots.assertCurrentPlanMatches(saved)).isInstanceOf(ResponseStatusException.class);
        assertThat(snapshots.applySnapshot(saved)).isTrue();
        assertThat(saved.getProject().getContainerPorts()).isEqualTo("9080:9080,9090:9090");
        assertThat(saved.getImageTag()).isEqualTo("old-release");
    }

    @Test
    void createRollbackRequestRejectsEnvironmentMismatch() {
        ProjectImageHistory history = history(DeployEnvironment.TEST, ProjectImageHistoryStatus.TEST_DEPLOYED, "main-abc-10", "sha256:test");
        when(projectImageHistoryService.findById(77L)).thenReturn(history);

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("environment");
    }

    @Test
    void createRollbackRequestRejectsHistoryWithoutDigest() {
        ProjectImageHistory history = history(DeployEnvironment.PROD, ProjectImageHistoryStatus.PROD_DEPLOYED, "main-abc-10", null);
        when(projectImageHistoryService.findById(77L)).thenReturn(history);

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("immutable image tag/digest");
    }

    @Test
    void createProdRollbackRejectsUnverifiedSource() {
        ProjectImageHistory history = verifiedProdHistory();
        DeployRequest source = deployRequestRepository.findById(66L).orElseThrow();
        ReleaseVerificationEvidence.clear(source);

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void createProdRollbackRequiresProjectApprovalPolicy() {
        verifiedProdHistory();
        project.setApprovalPolicy(null);

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("승인 정책");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void prodRollbackStillRequiresApprovalWhenGlobalApprovalIsDisabled() {
        verifiedProdHistory();
        when(executionPolicyService.requireApproval()).thenReturn(false);

        DeployRequest rollback = service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser());

        assertThat(rollback.getStatus()).isEqualTo(DeployStatus.APPROVAL_PENDING);
        assertThat(rollback.getApprovalDecisionSource()).isNull();
    }

    @Test
    void prodRollbackRejectsVerifiedTestSourceEvenWhenDigestMatches() {
        verifiedProdHistory();
        DeployRequest source = deployRequestRepository.findById(66L).orElseThrow();
        source.setEnvironment(DeployEnvironment.TEST);

        assertThatThrownBy(() -> service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처");
        verify(deployRequestRepository, never()).save(any());
    }

    @Test
    void previouslyVerifiedRollbackIsRejectedIfSourceProjectChanges() {
        ProjectImageHistory history = verifiedProdHistory();
        DeployRequest rollback = service.createRollbackRequest(rollbackForm(DeployEnvironment.PROD), currentUser());
        DeployRequest source = deployRequestRepository.findById(66L).orElseThrow();
        source.setProject(Project.builder().id(2L).imageRepository(project.getImageRepository()).build());

        assertThatThrownBy(() -> service.assertProductionRollbackProvenance(rollback))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CI 출처");
    }

    @Test
    void canConfirmTestUsesProjectScopedTestApprovePermission() {
        SecurityUserDetails currentUser = currentUser();
        DeployRequest request = DeployRequest.builder()
            .id(50L)
            .project(project)
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_DEPLOYED)
            .build();
        when(projectPermissionService.hasPermission(
            currentUser,
            1L,
            ProjectPermissionType.TEST_APPROVE,
            DeployEnvironment.TEST
        )).thenReturn(true);

        assertThat(service.canConfirmTest(currentUser, request)).isTrue();
    }

    @Test
    void canConfirmTestRejectsMissingPermissionOrWrongStatus() {
        SecurityUserDetails currentUser = currentUser();
        DeployRequest deployed = DeployRequest.builder()
            .id(50L)
            .project(project)
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_DEPLOYED)
            .build();
        DeployRequest pending = DeployRequest.builder()
            .id(51L)
            .project(project)
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_REQUESTED)
            .build();

        assertThat(service.canConfirmTest(currentUser, deployed)).isFalse();
        assertThat(service.canConfirmTest(currentUser, pending)).isFalse();
    }

    private DeployRequestCreateForm prodForm(String imageTag, String imageDigest) {
        DeployRequestCreateForm form = new DeployRequestCreateForm();
        form.setProjectId(1L);
        form.setEnvironment("PROD");
        form.setDeployMode("IMAGE_ONLY");
        form.setReason("Promote confirmed TEST image to PROD");
        form.setImageTag(imageTag);
        form.setImageDigest(imageDigest);
        form.setPromotionSourceRequestId(50L);
        return form;
    }

    private RollbackRequestForm rollbackForm(DeployEnvironment environment) {
        RollbackRequestForm form = new RollbackRequestForm();
        form.setHistoryId(77L);
        form.setEnvironment(environment.name());
        form.setReason("Rollback to previous stable image");
        return form;
    }

    private ProjectImageHistory history(
        DeployEnvironment environment,
        ProjectImageHistoryStatus status,
        String imageTag,
        String imageDigest
    ) {
        return ProjectImageHistory.builder()
            .id(77L)
            .project(project)
            .environment(environment)
            .branch("main")
            .imageTag(imageTag)
            .imageDigest(imageDigest)
            .gitlabPipelineId(10L)
            .commitSha("abc123")
            .historyStatus(status)
            .build();
    }

    private ProjectImageHistory verifiedProdHistory() {
        String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String commit = "0123456789abcdef0123456789abcdef01234567";
        project.setImageRepository("registry/payment-api");
        ProjectImageHistory history = history(DeployEnvironment.PROD,
            ProjectImageHistoryStatus.PROD_DEPLOYED, "release-01234567-77", digest);
        history.setImageRepository(project.getImageRepository());
        history.setGitlabPipelineId(77L);
        history.setCommitSha(commit);
        history.setDeployRequest(DeployRequest.builder().id(66L).build());
        DeployRequest source = DeployRequest.builder()
            .id(66L).project(project).environment(DeployEnvironment.PROD)
            .status(DeployStatus.COMPLETED)
            .imageTag(history.getImageTag()).imageDigest(digest)
            .gitlabPipelineId(77L).commitSha(commit).build();
        ReleaseVerificationEvidence.markVerifiedCiArtifact(source, 701L, project.getImageRepository(),
            LocalDateTime.of(2026, 9, 22, 9, 0));
        when(projectImageHistoryService.findById(77L)).thenReturn(history);
        when(deployRequestRepository.findById(66L)).thenReturn(Optional.of(source));
        return history;
    }

    private DeployRequest confirmedTestRequest(String imageTag, String imageDigest, Long pipelineId, String commitSha) {
        return DeployRequest.builder()
            .id(50L)
            .reqNo("REQ-TEST")
            .project(project)
            .environment(DeployEnvironment.TEST)
            .branch("main")
            .status(DeployStatus.TEST_CONFIRMED)
            .imageTag(imageTag)
            .imageDigest(imageDigest)
            .gitlabPipelineId(pipelineId)
            .commitSha(commitSha)
            .build();
    }

    @Test
    void prodRequestWithOnlyTestTargetsRefusesBeforePersistenceOrImageLookup() throws Exception {
        when(projectRepository.findServerGroupsByProjectId(1L)).thenReturn(List.of(
            com.portfolio.deployops.domain.ServerGroup.builder().id(801L)
                .environment(com.portfolio.deployops.domain.ServerEnvironment.TEST).build()));
        when(deployRequestRepository.findById(50L)).thenReturn(Optional.of(
            confirmedTestRequest("main-abc-10", "sha256:test", 10L, "abc123")));
        assertThatThrownBy(() -> service.create(prodForm("ignored", "sha256:ignored"), currentUser()))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getReason()).contains("서버 그룹이 없습니다");
            });
        verify(deployRequestRepository, never()).save(any());
        verify(nexusMetadataService, never()).resolveManifest(any(), any());
    }

    @Test
    void approvedSnapshotWithoutEffectiveTargetsRefusesAtExecutionBoundary() {
        DeployRequest request = DeployRequest.builder().project(Project.builder()
            .serverGroups(List.of()).build()).environment(DeployEnvironment.PROD).build();
        assertThatThrownBy(() -> service.assertPlanUnchanged(request))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getReason()).contains("대상 서버가 없습니다");
            });
        verify(deploymentPlanSnapshotService).assertCurrentPlanMatches(request);
        verify(deployRequestRepository, never()).updateExecutedBy(any(), any());
    }

    private SecurityUserDetails currentUser() {
        return new SecurityUserDetails(User.builder()
            .id(7L)
            .loginId("developer")
            .password("secret")
            .name("Developer")
            .role(UserRole.DEVELOPER)
            .active(true)
            .build());
    }
}
