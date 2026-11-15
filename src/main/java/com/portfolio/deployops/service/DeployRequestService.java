package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalDecisionSource;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployRequestMode;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeploymentReadyEvent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ProjectImageHistoryStatus;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ReleaseVerificationEvidence;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.StatusHistory;
import com.portfolio.deployops.dto.DeployRequestCreateForm;
import com.portfolio.deployops.dto.RollbackRequestForm;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import com.portfolio.deployops.domain.DeployRequestItem;
import com.portfolio.deployops.dto.ComponentImageForm;
import com.portfolio.deployops.repository.DeployRequestItemRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerRepository;
import com.portfolio.deployops.repository.StatusHistoryRepository;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DeployRequestService {

    public static final String ROLLBACK_REASON_PREFIX = "[ROLLBACK]";
    private static final java.util.regex.Pattern ROLLBACK_HISTORY_PATTERN =
        java.util.regex.Pattern.compile("^\\[ROLLBACK\\]\\[history:([1-9][0-9]*)\\](?:\\s|$)");

    private final DeployRequestRepository deployRequestRepository;
    private final ProjectRepository projectRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final ServerRepository serverRepository;
    private final ProjectImageHistoryService projectImageHistoryService;
    private final OfficeNotificationService officeNotificationService;
    private final ProjectPermissionService projectPermissionService;
    private final NexusMetadataService nexusMetadataService;
    private final DeployComponentRepository deployComponentRepository;
    private final DeployRequestItemRepository deployRequestItemRepository;
    private final ExecutionPolicyService executionPolicyService;
    private final DeploymentPlanSnapshotService deploymentPlanSnapshotService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final MergeRequestEvidenceRepository mergeRequestEvidenceRepository;

    private final AtomicInteger reqSeq = new AtomicInteger(0);


    @Transactional(readOnly = true)
    public Page<DeployRequest> findWithFilter(
        DeployStatus status,
        Long projectId,
        LocalDateTime from,
        LocalDateTime to,
        Pageable pageable
    ) {
        return deployRequestRepository.findWithFilter(status, projectId, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public Page<DeployRequest> findWithFilter(
        DeployStatus status,
        Long projectId,
        LocalDateTime from,
        LocalDateTime to,
        Pageable pageable,
        SecurityUserDetails currentUser
    ) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return findWithFilter(status, projectId, from, to, pageable);
        }
        if (projectId != null && projectPermissionService.hasPermission(
            currentUser, projectId, ProjectPermissionType.PROJECT_VIEW)) {
            projectPermissionService.assertPermission(currentUser, projectId, ProjectPermissionType.PROJECT_VIEW);
            return findWithFilter(status, projectId, from, to, pageable);
        }
        if (currentUser == null || currentUser.getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "요청 목록을 조회할 권한이 없습니다.");
        }
        if (projectId != null && !projectPermissionService.hasPermission(currentUser, projectId,
                ProjectPermissionType.DEPLOY_REQUEST, DeployEnvironment.TEST)
            && !projectPermissionService.hasPermission(currentUser, projectId,
                ProjectPermissionType.DEPLOY_REQUEST, DeployEnvironment.PROD)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 프로젝트의 요청 목록을 조회할 권한이 없습니다.");
        }
        List<Long> projectIds = projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL
        );
        return deployRequestRepository.findVisibleOrOwnedWithFilter(
            status, projectId, projectIds, currentUser.getId(), from, to, pageable);
    }

    @Transactional(readOnly = true)
    public List<DeployRequest> findOperatorQueue() {
        return deployRequestRepository.findByStatusInWithProject(
            List.of(DeployStatus.TEST_REQUESTED, DeployStatus.APPROVAL_COMPLETED)
        );
    }

    @Transactional(readOnly = true)
    public List<DeployRequest> findOperatorQueue(SecurityUserDetails currentUser) {
        if (projectPermissionService.isSuperAdmin(currentUser)) {
            return findOperatorQueue();
        }
        List<Long> projectIds = new java.util.ArrayList<>(projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.TEST_EXECUTE,
            ProjectPermissionEnvironment.TEST
        ));
        projectPermissionService.findPermittedProjectIds(
            currentUser,
            ProjectPermissionType.PROD_EXECUTE,
            ProjectPermissionEnvironment.PROD
        ).forEach(projectId -> {
            if (!projectIds.contains(projectId)) {
                projectIds.add(projectId);
            }
        });
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return deployRequestRepository.findByStatusInAndProjectIdsWithProject(
            List.of(DeployStatus.TEST_REQUESTED, DeployStatus.APPROVAL_COMPLETED),
            projectIds
        ).stream()
            .filter(request -> projectPermissionService.hasPermission(
                currentUser,
                request.getProject().getId(),
                request.getEnvironment() == DeployEnvironment.TEST
                    ? ProjectPermissionType.TEST_EXECUTE
                    : ProjectPermissionType.PROD_EXECUTE,
                request.getEnvironment()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<DeployRequest> findPromotionSources(Long projectId, SecurityUserDetails currentUser) {
        projectPermissionService.assertPermission(
            currentUser,
            projectId,
            ProjectPermissionType.DEPLOY_REQUEST,
            DeployEnvironment.PROD
        );
        List<DeployRequest> sources = deployRequestRepository.findConfirmedTestSources(projectId);
        sources.forEach(source -> source.setItems(deployRequestItemRepository.findByDeployRequestId(source.getId())));
        return sources;
    }

    @Transactional(readOnly = true)
    public DeployRequest findById(Long id) {
        DeployRequest request = deployRequestRepository.findByIdWithProject(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "배포 요청을 찾을 수 없습니다."));
        hydrateProjectTargets(request.getProject());
        request.setApprovalRecords(approvalRecordRepository.findByDeployRequestId(request.getId()));
        request.setStatusHistories(statusHistoryRepository.findByDeployRequestId(request.getId()));
        request.setItems(deployRequestItemRepository.findByDeployRequestId(request.getId()));
        deploymentPlanSnapshotService.applySnapshot(request);
        return request;
    }

    @Transactional(readOnly = true)
    public DeployRequest findById(Long id, SecurityUserDetails currentUser) {
        DeployRequest request = findById(id);
        // 요청자는 해당 환경의 요청 권한이 유지되는 동안 자기 요청의 상태를 확인할 수 있다.
        // 이 예외는 증적 전체·다른 사람의 요청·프로젝트 설정 조회 권한을 부여하지 않는다.
        if (!canViewEvidence(currentUser, request) && !isRequestOwner(currentUser, request)) {
            projectPermissionService.assertPermission(
                currentUser,
                request.getProject().getId(),
                ProjectPermissionType.PROJECT_VIEW
            );
        }
        return request;
    }

    public boolean canViewEvidence(SecurityUserDetails currentUser, DeployRequest request) {
        return request != null && request.getProject() != null
            && (isGlobalApprover(currentUser) || projectPermissionService.hasPermission(
                currentUser, request.getProject().getId(), ProjectPermissionType.PROJECT_VIEW));
    }

    public void assertEvidencePermission(SecurityUserDetails currentUser, DeployRequest request) {
        if (!canViewEvidence(currentUser, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트 조회 권한이 있어야 전체 증적을 조회할 수 있습니다.");
        }
    }

    private boolean isRequestOwner(SecurityUserDetails user, DeployRequest request) {
        return user != null && user.getId() != null && request != null && request.getProject() != null
            && request.getEnvironment() != null && request.getRequestedBy() != null
            && java.util.Objects.equals(request.getRequestedBy().getId(), user.getId())
            && projectPermissionService.hasPermission(user, request.getProject().getId(),
                ProjectPermissionType.DEPLOY_REQUEST, request.getEnvironment());
    }

    private boolean isGlobalApprover(SecurityUserDetails currentUser) {
        return currentUser != null
            && (projectPermissionService.isSuperAdmin(currentUser) || "APPROVER".equals(currentUser.getRole()));
    }

    @Transactional
    public DeployRequest create(DeployRequestCreateForm form, SecurityUserDetails currentUser) {
        Project project = projectRepository.findById(form.getProjectId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트를 찾을 수 없습니다."));

        DeployEnvironment environment = parseEnvironment(form.getEnvironment());
        projectPermissionService.assertPermission(
            currentUser,
            project.getId(),
            ProjectPermissionType.DEPLOY_REQUEST,
            environment
        );
        DeployRequestMode deployMode = parseDeployMode(form.getDeployMode());
        String reason = normalize(form.getReason());
        DeployRequest promotionSource = null;
        MergeRequestEvidence mergeEvidence = resolveMergeEvidence(form, project, environment, deployMode);

        if (environment == DeployEnvironment.PROD) {
            if (form.getPromotionSourceRequestId() != null) {
                promotionSource = deployRequestRepository.findById(form.getPromotionSourceRequestId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 TEST 확인 요청을 찾을 수 없습니다."));
                if (promotionSource.getProject() == null || !project.getId().equals(promotionSource.getProject().getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "다른 프로젝트의 TEST 요청은 승격할 수 없습니다.");
                }
            } else if (executionPolicyService.requireTestFirst()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PROD로 승격할 TEST 확인 완료 요청을 선택해 주세요.");
            }
        }

        if (promotionSource != null) {
            validatePromotionSource(promotionSource);
            DeployScope verifiedScope = promotionSource.getDeployScope() == null
                ? DeployScope.ALL : promotionSource.getDeployScope();
            if (verifiedScope != DeployScope.ALL && verifiedScope != parseDeployScope(form.getDeployScope())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TEST에서 확인하지 않은 배포 범위로 확대할 수 없습니다. 동일 범위를 선택하거나 전체 TEST를 확인해 주세요.");
            }
        }

        // Fresh mapping is checked before image lookup, request persistence or approval.
        hydrateProjectTargets(project);
        List<ServerGroup> selectedGroups = EffectiveDeployTargets.selectGroups(project, environment, form.getServerGroupIds());
        DeploymentPlanPolicy.assertComponentRoles(project, HttpStatus.BAD_REQUEST);
        DeploymentPlanPolicy.assertStrategy(selectedGroups, environment, parseDeployStrategy(form.getDeployStrategy()), HttpStatus.BAD_REQUEST);
        assertEffectiveTargets(Project.builder().serverGroups(selectedGroups).components(project.getComponents()).build(),
            environment, parseDeployScope(form.getDeployScope()), HttpStatus.BAD_REQUEST);
        validateCreateRequest(form, project, environment, deployMode);
        if (environment == DeployEnvironment.TEST || promotionSource == null) {
            resolveImageDigestForImageOnly(form, project, deployMode);
        }
        List<DeployRequestItem> releaseItems = prepareReleaseItems(project, promotionSource, form.getComponentImages(),
            parseDeployScope(form.getDeployScope()), environment, deployMode, mergeEvidence);

        lockProjectForRequestCreation(project.getId());
        if (deployRequestRepository.existsByProjectIdAndStatusIn(
            project.getId(),
            List.of(
                DeployStatus.TEST_REQUESTED,
                DeployStatus.TEST_BUILDING,
                DeployStatus.TEST_DEPLOYING,
                DeployStatus.PROD_DEPLOYING,
                DeployStatus.APPROVAL_PENDING,
                DeployStatus.APPROVAL_COMPLETED
            )
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 진행 중인 배포 또는 승인 대기 건이 있습니다.");
        }

        boolean groupApprovalRequired = EffectiveApprovalPolicy.hasGroupPolicy(selectedGroups);
        boolean approvalRequired = groupApprovalRequired || (environment == DeployEnvironment.PROD
            && executionPolicyService.requireApproval());
        String reqNo = generateReqNo();
        DeployRequest request = DeployRequest.builder()
            .reqNo(reqNo)
            .project(project)
            .environment(environment)
            .selectedServerGroupIds(selectedGroups.stream().map(ServerGroup::getId).toList())
            .deployScope(parseDeployScope(form.getDeployScope()))
            .deployStrategy(parseDeployStrategy(form.getDeployStrategy()))
            .branch(resolveRequestBranch(form, project, promotionSource, deployMode))
            .reason(reason)
            .status(approvalRequired ? DeployStatus.APPROVAL_PENDING :
                environment == DeployEnvironment.TEST ? DeployStatus.TEST_REQUESTED : DeployStatus.APPROVAL_COMPLETED)
            .approvalDecisionSource(environment == DeployEnvironment.PROD && !approvalRequired
                ? ApprovalDecisionSource.NOT_REQUIRED : null)
            .requestedBy(currentUser.getUser())
            .promotedFromRequest(promotionSource)
            .build();

        applyBuildMetadata(request, form, promotionSource, deployMode);
        applyMergeEvidence(request, mergeEvidence);
        applyKnownImageProvenance(request, deployMode);
        request.setItems(releaseItems);
        assertReleaseManifest(request);
        boolean hasComponentOverrides = form.getComponentImages() != null && !form.getComponentImages().isEmpty();
        if (!hasComponentOverrides && promotionSource != null) {
            // 다중 산출물 승격은 기존 수동 승인 정책을 유지한다.
            hasComponentOverrides = !deployRequestItemRepository.findByDeployRequestId(
                promotionSource.getId()).isEmpty();
        }
        if (approvalRequired && !groupApprovalRequired && canAutoApproveVerifiedPromotion(
            request, promotionSource, hasComponentOverrides)) {
            request.setStatus(DeployStatus.APPROVAL_COMPLETED);
            request.setApprovalDecisionSource(ApprovalDecisionSource.AUTO_VERIFIED_PROMOTION);
        }

        DeployRequest saved = deployRequestRepository.save(request);
        persistReleaseItems(saved, releaseItems);
        deploymentPlanSnapshotService.capture(saved);
        deploymentPlanSnapshotService.applySnapshot(saved);
        enforceFrozenGroupApproval(saved);
        saved = deployRequestRepository.save(saved);
        addStatusHistory(saved, saved.getStatus());
        if (saved.getApprovalDecisionSource() == ApprovalDecisionSource.AUTO_VERIFIED_PROMOTION) {
            auditLogService.record(
                AutomaticDispatchService.SYSTEM_ACTOR,
                "검증된 TEST 승격 자동 승인",
                "DeployRequest",
                String.valueOf(saved.getId()),
                auditLogService.describeDeployRequest(saved),
                AuditResult.SUCCESS,
                AutomaticDispatchService.SYSTEM_IP
            );
        }
        officeNotificationService.notifyCreated(saved);
        publishDeploymentReady(saved);
        return saved;
    }

    /** 요청 생성 시 컴포넌트별 산출물(CSR 등)을 deploy_request_item으로 저장한다. 산출물이 비면 건너뛴다. */
    private List<DeployRequestItem> prepareReleaseItems(Project project, DeployRequest source,
                                                       List<ComponentImageForm> supplied, DeployScope scope,
                                                       DeployEnvironment environment, DeployRequestMode mode,
                                                       MergeRequestEvidence mergeEvidence) {
        if (source == null) {
            if (environment == DeployEnvironment.TEST && mode == DeployRequestMode.BUILD_AND_DEPLOY
                    && GitlabReleaseManifest.required(project)) {
                if (supplied != null && !supplied.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "새 CI 묶음 빌드에는 수동 이미지 산출물을 섞을 수 없습니다.");
                if (mergeEvidence == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "다중 산출물 빌드는 승인·병합한 MR과 고정 commit이 필요합니다.");
                GitlabReleaseManifest.validateConfiguration(project);
                return List.of();
            }
            List<DeployRequestItem> items = ComponentReleaseManifest.fromForms(project, supplied);
            ComponentReleaseManifest.requireComplete(project, items, scope);
            return items;
        }
        List<DeployRequestItem> sourceItems = deployRequestItemRepository.findByDeployRequestId(source.getId());
        source.setItems(sourceItems == null ? List.of() : sourceItems);
        boolean frozen = deploymentPlanSnapshotService.applySnapshot(source);
        if (!frozen && (!source.getItems().isEmpty()
                || project.getComponents().stream().filter(c -> c.getArtifactType()
                    != com.portfolio.deployops.domain.ArtifactType.PROXY_RELOAD).count() > 1)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "원본 TEST의 배포 단위 설정 증적이 없습니다. 전체 산출물로 새 TEST 배포를 확인해 주세요.");
        }
        if (frozen) {
            ComponentReleaseManifest.requireSameComponents(source.getProject(), project);
            assertComponentTargetCoverage(source);
        }
        List<DeployRequestItem> items = ComponentReleaseManifest.canonical(project, source.getItems());
        ComponentReleaseManifest.requireComplete(project, items, scope);
        if (supplied != null && !supplied.isEmpty()) {
            ComponentReleaseManifest.requireSameArtifacts(items, ComponentReleaseManifest.fromForms(project, supplied));
        }
        return ComponentReleaseManifest.copies(items);
    }

    private void persistReleaseItems(DeployRequest request, List<DeployRequestItem> items) {
        List<DeployRequestItem> copies = ComponentReleaseManifest.copies(items);
        for (DeployRequestItem item : copies) {
            item.setDeployRequestId(request.getId());
            deployRequestItemRepository.insert(item);
        }
        request.setItems(copies);
    }

    @Transactional
    public DeployRequest createRollbackRequest(RollbackRequestForm form, SecurityUserDetails currentUser) {
        ProjectImageHistory history = projectImageHistoryService.findById(form.getHistoryId());
        Project project = projectRepository.findById(history.getProject().getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rollback target project was not found."));

        DeployEnvironment environment = parseEnvironment(form.getEnvironment());
        validateRollbackSource(history, project, environment);
        lockProjectForRequestCreation(project.getId());
        validateRollbackConflict(project.getId());

        projectPermissionService.assertPermission(
            currentUser,
            project.getId(),
            ProjectPermissionType.ROLLBACK_EXECUTE,
            environment
        );
        String branch = normalize(history.getBranch()) != null
            ? normalize(history.getBranch())
            : normalize(project.getDefaultBranch());
        // A PROD rollback always requires a human approval, even when ordinary
        // PROD promotions have global approval disabled.
        boolean approvalRequired = environment == DeployEnvironment.PROD;
        DeployRequest sourceRequest = history.getDeployRequest();
        if (sourceRequest != null && sourceRequest.getId() != null) {
            sourceRequest = deployRequestRepository.findById(sourceRequest.getId()).orElse(sourceRequest);
        }
        List<DeployRequestItem> rollbackItems = List.of();
        boolean frozenSource = false;
        if (sourceRequest != null && sourceRequest.getId() != null) {
            sourceRequest.setItems(deployRequestItemRepository.findByDeployRequestId(sourceRequest.getId()));
            frozenSource = deploymentPlanSnapshotService.applySnapshot(sourceRequest);
            if (!frozenSource && sourceRequest.getItems() != null && !sourceRequest.getItems().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "다중 산출물의 원본 설정 증적이 없어 전체 롤백할 수 없습니다.");
            }
            if (!frozenSource) hydrateProjectTargets(project);
            Project originalProject = frozenSource ? sourceRequest.getProject() : project;
            DeploymentPlanPolicy.assertComponentRoles(originalProject, HttpStatus.BAD_REQUEST);
            rollbackItems = ComponentReleaseManifest.canonical(originalProject, sourceRequest.getItems());
            ComponentReleaseManifest.requireComplete(originalProject, rollbackItems, sourceRequest.getDeployScope());
        } else {
            hydrateProjectTargets(project);
            DeploymentPlanPolicy.assertComponentRoles(project, HttpStatus.BAD_REQUEST);
        }

        DeployRequest request = DeployRequest.builder()
            .reqNo(generateReqNo())
            .project(project)
            .environment(environment)
            .selectedServerGroupIds(sourceRequest != null ? sourceRequest.getSelectedServerGroupIds() : null)
            // A scoped WEB-only or WAS-only deployment must roll back the same slice.
            // Older history rows without their source request retain the safe ALL default.
            .deployScope(sourceRequest != null && sourceRequest.getDeployScope() != null
                ? sourceRequest.getDeployScope() : DeployScope.ALL)
            .deployStrategy(sourceRequest != null && sourceRequest.getDeployStrategy() != null
                ? sourceRequest.getDeployStrategy() : DeployStrategy.SAFE)
            .branch(branch != null ? branch : "rollback")
            .reason(buildRollbackReason(form.getReason(), history.getId()))
            .status(environment == DeployEnvironment.TEST
                ? DeployStatus.TEST_REQUESTED
                : approvalRequired ? DeployStatus.APPROVAL_PENDING : DeployStatus.APPROVAL_COMPLETED)
            .approvalDecisionSource(environment == DeployEnvironment.PROD && !approvalRequired
                ? ApprovalDecisionSource.NOT_REQUIRED : null)
            .requestedBy(currentUser.getUser())
            .gitlabPipelineId(history.getGitlabPipelineId())
            .commitSha(normalize(history.getCommitSha()))
            .imageTag(normalize(history.getImageTag()))
            .imageDigest(normalize(history.getImageDigest()))
            .build();

        List<ServerGroup> selectedGroups = selectRollbackTargetGroups(request, sourceRequest, frozenSource);
        if (EffectiveApprovalPolicy.hasGroupPolicy(selectedGroups)) {
            request.setStatus(DeployStatus.APPROVAL_PENDING);
            request.setApprovalDecisionSource(null);
        }
        if (!frozenSource) {
            ComponentReleaseManifest.requireComplete(project, rollbackItems, request.getDeployScope());
        }

        inheritRollbackReleaseVerification(history, request);
        // A group-only policy is composed during capture below; provenance must still
        // be checked before persistence, and the complete policy is checked afterwards.
        assertProductionRollbackProvenance(request, !EffectiveApprovalPolicy.hasGroupPolicy(selectedGroups));

        DeployRequest saved = deployRequestRepository.save(request);
        persistReleaseItems(saved, rollbackItems);
        if (sourceRequest != null && normalize(sourceRequest.getPlanSnapshotJson()) != null) {
            deploymentPlanSnapshotService.captureRollback(saved, sourceRequest);
        } else {
            // Legacy single-image histories have no historical configuration to restore.
            deploymentPlanSnapshotService.capture(saved);
        }
        deploymentPlanSnapshotService.applySnapshot(saved);
        enforceFrozenGroupApproval(saved);
        assertProductionRollbackProvenance(saved);
        saved = deployRequestRepository.save(saved);
        addStatusHistory(saved, saved.getStatus());
        officeNotificationService.notifyCreated(saved);
        publishDeploymentReady(saved);
        return saved;
    }

    private List<ServerGroup> selectRollbackTargetGroups(DeployRequest request, DeployRequest source, boolean frozenSource) {
        // Validate current group policy without hydrating over the historical runtime/components.
        // Old snapshots predate explicit selection: their frozen groups are the original target boundary.
        List<Long> selectedIds = request.getSelectedServerGroupIds();
        if (frozenSource && (selectedIds == null || selectedIds.isEmpty())) {
            var expectedEnvironment = request.getEnvironment() == DeployEnvironment.PROD
                ? com.portfolio.deployops.domain.ServerEnvironment.PROD
                : com.portfolio.deployops.domain.ServerEnvironment.TEST;
            List<ServerGroup> originalGroups = source.getProject().getServerGroups();
            selectedIds = originalGroups == null ? List.of() : originalGroups.stream()
                .filter(group -> group.getEnvironment() == expectedEnvironment)
                .map(ServerGroup::getId).sorted().toList();
            if (selectedIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "롤백 원본에 해당 환경의 서버 그룹이 없습니다.");
            }
        }
        Project currentTargets = Project.builder()
            .serverGroups(projectRepository.findServerGroupsByProjectId(request.getProject().getId())).build();
        List<ServerGroup> selectedGroups = EffectiveDeployTargets.selectGroups(currentTargets, request.getEnvironment(), selectedIds);
        DeploymentPlanPolicy.assertStrategy(selectedGroups, request.getEnvironment(), request.getDeployStrategy(), HttpStatus.BAD_REQUEST);
        request.setSelectedServerGroupIds(selectedGroups.stream().map(ServerGroup::getId).toList());
        return selectedGroups;
    }

    /** Read-only recommendation gate using the same current target boundary and policy composition as creation. */
    @Transactional(readOnly = true)
    public void assertRollbackSuggestionAvailable(Long historyId, Long expectedProjectId, SecurityUserDetails currentUser) {
        ProjectImageHistory history = projectImageHistoryService.findById(historyId);
        if (history.getProject() == null || !java.util.Objects.equals(history.getProject().getId(),expectedProjectId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"롤백 이력의 프로젝트가 일치하지 않습니다.");
        }
        Project current = projectRepository.findById(expectedProjectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"롤백 대상 프로젝트를 찾을 수 없습니다."));
        validateRollbackSource(history,current,DeployEnvironment.PROD);
        projectPermissionService.assertPermission(currentUser,expectedProjectId,ProjectPermissionType.ROLLBACK_EXECUTE,DeployEnvironment.PROD);
        if (history.getDeployRequest() == null || history.getDeployRequest().getId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"운영 롤백 원본 배포 요청을 확인할 수 없습니다.");
        DeployRequest source=deployRequestRepository.findById(history.getDeployRequest().getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"운영 롤백 원본 배포 요청을 확인할 수 없습니다."));
        boolean frozen = deploymentPlanSnapshotService.applySnapshot(source);
        DeployRequest preview=DeployRequest.builder().project(current).environment(DeployEnvironment.PROD)
            .requestedBy(currentUser.getUser()).selectedServerGroupIds(source.getSelectedServerGroupIds())
            .deployScope(source.getDeployScope() == null ? DeployScope.ALL : source.getDeployScope())
            .deployStrategy(source.getDeployStrategy() == null ? DeployStrategy.SAFE : source.getDeployStrategy())
            .reason(buildRollbackReason("추천 롤백 확인",history.getId()))
            .gitlabPipelineId(history.getGitlabPipelineId()).commitSha(history.getCommitSha())
            .imageTag(history.getImageTag()).imageDigest(history.getImageDigest()).build();
        List<ServerGroup> groups=selectRollbackTargetGroups(preview,source,frozen);
        groups.forEach(group -> group.setServers(serverRepository.findByServerGroupId(group.getId())));
        deploymentPlanSnapshotService.assertServerApprovalBoundary(groups,preview.getEnvironment(),preview.getDeployScope());
        current.setApprovalPolicy(deploymentPlanSnapshotService.currentApprovalPolicy(current,groups,preview.getRequestedBy()));
        inheritRollbackReleaseVerification(history,preview);
        assertProductionRollbackProvenance(preview);
    }

    private void enforceFrozenGroupApproval(DeployRequest request) {
        var policy = request.getProject() == null ? null : request.getProject().getApprovalPolicy();
        if (policy != null && policy.isGroupComposed()) {
            request.setStatus(DeployStatus.APPROVAL_PENDING);
            request.setApprovalDecisionSource(null);
        }
    }

    /**
     * A rollback does not create new CI evidence. It may reuse the original request's evidence only when
     * the history still identifies exactly the same pipeline, commit, tag and immutable digest. The
     * evidence hash performs that binding check; missing, legacy or altered source data remains unverified.
     */
    private void inheritRollbackReleaseVerification(ProjectImageHistory history, DeployRequest rollbackRequest) {
        if (history.getDeployRequest() == null
            || history.getDeployRequest().getId() == null
            || history.getProject() == null
            || history.getProject().getId() == null
            || rollbackRequest.getProject() == null
            || !java.util.Objects.equals(history.getProject().getId(), rollbackRequest.getProject().getId())) {
            return;
        }

        String historyRepository = normalize(history.getImageRepository());
        String currentRepository = normalize(rollbackRequest.getProject().getImageRepository());
        if (historyRepository == null || !java.util.Objects.equals(historyRepository, currentRepository)) {
            return;
        }

        deployRequestRepository.findById(history.getDeployRequest().getId())
            .filter(source -> source.getProject() != null
                && java.util.Objects.equals(source.getProject().getId(), rollbackRequest.getProject().getId())
                && java.util.Objects.equals(
                    normalize(source.getReleaseVerificationRepository()), currentRepository))
            .ifPresent(source -> ReleaseVerificationEvidence.copyVerifiedFrom(source, rollbackRequest));
    }

    /** Recheck the source on every PROD rollback boundary, including approval and execution. */
    public void assertProductionRollbackProvenance(DeployRequest request) {
        assertProductionRollbackProvenance(request, true);
    }

    private void assertProductionRollbackProvenance(DeployRequest request, boolean requireMaterializedPolicy) {
        if (request == null || request.getEnvironment() != DeployEnvironment.PROD || !isRollbackRequest(request)) {
            return;
        }
        Project project = request.getProject();
        if (project == null || project.getId() == null || (requireMaterializedPolicy && project.getApprovalPolicy() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "운영 롤백에는 프로젝트 또는 선택한 서버 그룹의 승인 정책이 필요합니다.");
        }
        var matcher = ROLLBACK_HISTORY_PATTERN.matcher(request.getReason());
        if (!matcher.find()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "운영 롤백의 원본 이미지 이력을 확인할 수 없습니다.");
        }
        Long historyId;
        try {
            historyId = Long.valueOf(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "운영 롤백의 원본 이미지 이력이 올바르지 않습니다.");
        }
        ProjectImageHistory history = projectImageHistoryService.findById(historyId);
        String repository = normalize(project.getImageRepository());
        if (repository == null
            || history.getProject() == null
            || !java.util.Objects.equals(history.getProject().getId(), project.getId())
            || history.getEnvironment() != DeployEnvironment.PROD
            || !isRollbackStableHistory(history, DeployEnvironment.PROD)
            || !java.util.Objects.equals(normalize(history.getImageRepository()), repository)
            || history.getDeployRequest() == null
            || history.getDeployRequest().getId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "운영 롤백 이미지 이력이 프로젝트·환경·저장소와 일치하지 않습니다.");
        }
        DeployRequest source = deployRequestRepository.findById(history.getDeployRequest().getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "운영 롤백 원본 배포 요청을 확인할 수 없습니다."));
        if (source.getProject() == null
            || !java.util.Objects.equals(source.getProject().getId(), project.getId())
            || source.getEnvironment() != DeployEnvironment.PROD
            || !java.util.Objects.equals(normalize(source.getReleaseVerificationRepository()), repository)
            || !ReleaseVerificationEvidence.isVerifiedCiArtifact(source)
            || !ReleaseVerificationEvidence.isVerifiedCiArtifact(request)
            || !java.util.Objects.equals(normalize(request.getReleaseVerificationRepository()), repository)
            || !java.util.Objects.equals(history.getGitlabPipelineId(), source.getGitlabPipelineId())
            || !java.util.Objects.equals(history.getGitlabPipelineId(), request.getGitlabPipelineId())
            || !java.util.Objects.equals(normalize(history.getCommitSha()), normalize(source.getCommitSha()))
            || !java.util.Objects.equals(normalize(history.getCommitSha()), normalize(request.getCommitSha()))
            || !java.util.Objects.equals(normalize(history.getImageTag()), normalize(source.getImageTag()))
            || !java.util.Objects.equals(normalize(history.getImageTag()), normalize(request.getImageTag()))
            || !java.util.Objects.equals(normalize(history.getImageDigest()), normalize(source.getImageDigest()))
            || !java.util.Objects.equals(normalize(history.getImageDigest()), normalize(request.getImageDigest()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "운영 롤백 이미지의 CI 출처와 프로젝트·저장소·다이제스트가 검증되지 않았습니다.");
        }
    }

    @Transactional(readOnly = true)
    public boolean canWithdraw(SecurityUserDetails user, DeployRequest request) {
        if (user == null || request == null || request.getProject() == null
            || !isWithdrawable(request.getStatus())) {
            return false;
        }
        return projectPermissionService.isSuperAdmin(user) || isRequestOwner(user, request);
    }

    private boolean isWithdrawable(DeployStatus status) {
        return status == DeployStatus.TEST_REQUESTED || status == DeployStatus.APPROVAL_PENDING
            || status == DeployStatus.APPROVAL_COMPLETED;
    }

    @Transactional
    public DeployRequest withdraw(Long id, String reason, SecurityUserDetails user, String ip) {
        if (reason == null || reason.isBlank() || reason.length() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "철회 사유를 400자 이하로 입력해 주세요.");
        }
        if (deployRequestRepository.lockById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "배포 요청을 찾을 수 없습니다.");
        }
        DeployRequest request = deployRequestRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 상태를 포함한 UI 게이트와 달리, API는 권한 오류와 이미 실행된 요청을 구별한다.
        boolean owner = isRequestOwner(user, request);
        if (!projectPermissionService.isSuperAdmin(user) && !owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "요청자 또는 시스템 관리자만 철회할 수 있습니다.");
        }
        if (!isWithdrawable(request.getStatus())
            || deployRequestRepository.withdrawPending(id, request.getStatus(), reason.trim()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "실행 전 대기 요청만 철회할 수 있습니다. 새로고침 후 확인해 주세요.");
        }
        DeployStatus previous = request.getStatus();
        request.setStatus(DeployStatus.CANCELLED);
        request.setWithdrawalReason(reason.trim());
        addStatusHistory(request, DeployStatus.CANCELLED);
        auditLogService.record(user.getName(), "배포 요청 철회", "DeployRequest", String.valueOf(id),
            auditLogService.describeDeployRequest(request) + " | 이전 상태: " + previous
                + " | 철회 사유: " + reason.trim(), AuditResult.SUCCESS, ip);
        return request;
    }

    @Transactional
    public DeployRequest confirmTest(Long id, SecurityUserDetails currentUser) {
        DeployRequest request = findById(id);
        projectPermissionService.assertPermission(
            currentUser,
            request.getProject().getId(),
            ProjectPermissionType.TEST_APPROVE,
            DeployEnvironment.TEST
        );
        if (request.getStatus() != DeployStatus.TEST_DEPLOYED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TEST 배포 완료 상태에서만 확인할 수 있습니다.");
        }
        assertReleaseManifest(request);
        assertComponentTargetCoverage(request);
        request.setStatus(DeployStatus.TEST_CONFIRMED);
        request.setTestConfirmedBy(currentUser.getUser());
        request.setTestConfirmedAt(LocalDateTime.now());
        addStatusHistory(request, DeployStatus.TEST_CONFIRMED);
        DeployRequest saved = deployRequestRepository.save(request);
        officeNotificationService.notifyStatusChanged(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public boolean canConfirmTest(SecurityUserDetails currentUser, DeployRequest request) {
        if (currentUser == null
            || request == null
            || request.getStatus() != DeployStatus.TEST_DEPLOYED
            || request.getProject() == null
            || request.getProject().getId() == null) {
            return false;
        }
        return projectPermissionService.hasPermission(
            currentUser,
            request.getProject().getId(),
            ProjectPermissionType.TEST_APPROVE,
            DeployEnvironment.TEST
        );
    }

    /**
     * 기대 상태일 때만 전이시킨다. 이미 남이 가져갔으면 {@code false} — 아무것도 바꾸지 않는다.
     *
     * <p>배포 실행처럼 "두 번 일어나면 안 되는" 전이의 관문으로 쓴다. 상태를 읽어서 검사한 뒤
     * 갱신하면 동시 요청 둘이 모두 통과하지만, 이 메서드는 DB 한 문장으로 검사와 갱신을 묶어
     * 정확히 한 쪽만 통과시킨다.
     */
    @Transactional
    public boolean tryTransition(Long id, DeployStatus expected, DeployStatus newStatus) {
        if (deployRequestRepository.compareAndSetStatus(id, expected, newStatus) == 0) {
            return false;
        }
        // 전이에 성공한 쪽만 이력·알림을 남긴다.
        DeployRequest saved = findById(id);
        addStatusHistory(saved, newStatus);
        projectImageHistoryService.recordDeploymentStatus(saved);
        officeNotificationService.notifyStatusChanged(saved);
        return true;
    }

    /** 실패 상태와 원인을 원자적으로 기록해 스트리밍 종료 후에도 상세 화면에서 진단할 수 있게 한다. */
    @Transactional
    public boolean tryTransitionToFailure(Long id, DeployStatus expected, DeployStatus failedStatus, String safeReason) {
        if (deployRequestRepository.compareAndSetFailureStatus(id, expected, failedStatus, safeReason) == 0) {
            return false;
        }
        DeployRequest saved = findById(id);
        addStatusHistory(saved, failedStatus);
        projectImageHistoryService.recordDeploymentStatus(saved);
        officeNotificationService.notifyStatusChanged(saved);
        return true;
    }

    @Transactional
    public void updateStatus(Long id, DeployStatus newStatus) {
        DeployRequest request = findById(id);
        request.setStatus(newStatus);
        addStatusHistory(request, newStatus);
        DeployRequest saved = deployRequestRepository.save(request);
        projectImageHistoryService.recordDeploymentStatus(saved);
        officeNotificationService.notifyStatusChanged(saved);
    }

    @Transactional
    public DeployRequest updateVerifiedBuildMetadata(Long id, GitlabBuildArtifactMetadata artifact) {
        if (deployRequestRepository.lockById(id) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "산출물을 결속할 배포 요청이 없습니다.");
        }
        DeployRequest request = findById(id);
        if (request.getEnvironment() != DeployEnvironment.TEST
            || request.getStatus() != DeployStatus.TEST_BUILDING) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "TEST 빌드 실행 상태에서만 검증된 산출물을 저장할 수 있습니다."
            );
        }
        if (artifact == null
            || request.getProject() == null
            || !java.util.Objects.equals(
                normalize(request.getProject().getImageRepository()), normalize(artifact.repository()))) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "검증된 GitLab 산출물의 이미지 저장소가 프로젝트 설정과 일치하지 않습니다."
            );
        }
        if (normalize(request.getExpectedCommitSha()) != null
            && (normalize(artifact.commitSha()) == null
                || !request.getExpectedCommitSha().equalsIgnoreCase(normalize(artifact.commitSha())))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "MR 병합 결과 SHA와 GitLab pipeline artifact SHA가 일치하지 않습니다. 산출물을 저장하지 않았습니다."
            );
        }
        boolean multi = GitlabReleaseManifest.required(request.getProject());
        List<DeployRequestItem> verifiedItems = List.of();
        if (multi) {
            GitlabReleaseManifest.validateMetadata(request.getProject(), artifact);
            verifiedItems = ComponentReleaseManifest.canonical(request.getProject(), artifact.components().stream()
                .map(component -> DeployRequestItem.builder().componentCode(component.code())
                    .imageTag(component.tag()).imageDigest(component.digest()).build()).toList());
            ComponentReleaseManifest.requireComplete(request.getProject(), verifiedItems, DeployScope.ALL);
        } else if (!artifact.components().isEmpty() || artifact.releaseManifestSha256() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "단일 이미지 요청에 예상하지 않은 배포 묶음이 있습니다.");
        }
        deploymentPlanSnapshotService.assertBuildConfigurationMatches(request);
        if (ReleaseVerificationEvidence.isVerifiedCiArtifact(request)) {
            if (!java.util.Objects.equals(request.getGitlabPipelineId(), artifact.pipelineId())
                    || !java.util.Objects.equals(request.getReleaseVerificationJobId(), artifact.jobId())
                    || !java.util.Objects.equals(request.getImageTag(), artifact.tag())
                    || !java.util.Objects.equals(request.getImageDigest(), artifact.digest())
                    || !java.util.Objects.equals(request.getCommitSha(), artifact.commitSha())
                    || !java.util.Objects.equals(request.getReleaseVerificationRepository(), artifact.repository())
                    || !java.util.Objects.equals(request.getReleaseManifestSha256(), artifact.releaseManifestSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 결속한 CI 산출물을 바꿀 수 없습니다.");
            if (multi) ComponentReleaseManifest.requireSameArtifacts(
                ComponentReleaseManifest.canonical(request.getProject(), request.getItems()), verifiedItems);
            return request;
        }
        if (multi && !request.getItems().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "미확정 CI 요청에 기존 산출물이 있어 결속을 중단했습니다.");
        // Row lock + this transaction protect the complete set and evidence from
        // concurrent workers/cancellation. Any later failure rolls back every item.
        if (multi) persistReleaseItems(request, verifiedItems);
        request.setGitlabPipelineId(artifact.pipelineId());
        request.setCommitSha(normalize(artifact.commitSha()));
        request.setImageTag(normalize(artifact.tag()));
        request.setImageDigest(normalize(artifact.digest()));
        request.setReleaseManifestSha256(artifact.releaseManifestSha256());
        assertReleaseManifest(request);
        try {
            ReleaseVerificationEvidence.markVerifiedCiArtifact(
                request,
                artifact.jobId(),
                artifact.repository(),
                LocalDateTime.now()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        deploymentPlanSnapshotService.bindVerifiedRelease(request);
        DeployRequest saved = deployRequestRepository.save(request);
        projectImageHistoryService.recordBuildCompletion(saved);
        return saved;
    }

    /** 진행 흐름에 "실행자"를 표시하기 위해 배포를 실행한 운영자를 기록한다. */
    @Transactional
    public void markExecuted(Long requestId, Long userId) {
        deployRequestRepository.updateExecutedBy(requestId, userId);
    }

    /** 승인·실행 직전에 요청 당시 배포 계획이 그대로인지 fail-closed로 재검증한다. */
    public void assertPlanUnchanged(DeployRequest request) {
        deploymentPlanSnapshotService.assertCurrentPlanMatches(request);
        assertReleaseManifest(request);
        // findById applies the verified immutable snapshot; all operator entry points call this before tryStart.
        List<ServerGroup> groups = request.getSelectedServerGroupIds() == null || request.getSelectedServerGroupIds().isEmpty()
            ? request.getProject().getServerGroups()
            : EffectiveDeployTargets.selectGroups(request.getProject(), request.getEnvironment(), request.getSelectedServerGroupIds());
        DeploymentPlanPolicy.assertStrategy(groups, request.getEnvironment(), request.getDeployStrategy(), HttpStatus.CONFLICT);
        assertEffectiveTargets(Project.builder().serverGroups(groups).components(request.getProject().getComponents()).build(),
            request.getEnvironment(), request.getDeployScope(), HttpStatus.CONFLICT);
    }

    private void assertReleaseManifest(DeployRequest request) {
        DeploymentPlanPolicy.assertComponentRoles(request.getProject(), HttpStatus.CONFLICT);
        if (pendingMultiCiRelease(request)) return;
        List<DeployRequestItem> items = ComponentReleaseManifest.canonical(request.getProject(), request.getItems());
        ComponentReleaseManifest.requireComplete(request.getProject(), items, request.getDeployScope());
        ComponentReleaseManifest.requirePrimaryImage(request.getProject(), items, request.getImageDigest());
        if (request.getReleaseManifestSha256() != null) {
            ComponentReleaseManifest.requireComplete(request.getProject(), items, DeployScope.ALL);
            var artifacts = items.stream().map(item -> {
                var component = request.getProject().getComponents().stream()
                    .filter(candidate -> java.util.Objects.equals(candidate.getId(), item.getComponentId())).findFirst().orElseThrow();
                return new GitlabBuildArtifactMetadata.ComponentArtifact(item.getComponentCode(),
                    GitlabReleaseManifest.repository(request.getProject(), component), item.getImageTag(), item.getImageDigest());
            }).toList();
            String actual = GitlabReleaseManifest.canonicalHash(request.getGitlabPipelineId(), request.getCommitSha(), artifacts);
            if (!actual.equals(request.getReleaseManifestSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "전체 CI 산출물 증적과 배포 묶음이 다릅니다.");
        }
    }

    private boolean pendingMultiCiRelease(DeployRequest request) {
        return request.getEnvironment() == DeployEnvironment.TEST
            && List.of(DeployStatus.APPROVAL_PENDING, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING).contains(request.getStatus())
            && GitlabReleaseManifest.required(request.getProject())
            && request.getSourceMrIid() != null && request.getMrEvidenceId() != null
            && normalize(request.getExpectedCommitSha()) != null && normalize(request.getImmutableBuildRef()) != null
            && request.getReleaseVerificationStatus() == com.portfolio.deployops.domain.ReleaseVerificationStatus.UNVERIFIED
            && request.getReleaseManifestSha256() == null && request.getImageTag() == null && request.getImageDigest() == null
            && (request.getItems() == null || request.getItems().isEmpty());
    }

    private void assertComponentTargetCoverage(DeployRequest request) {
        var missing = EffectiveDeployTargets.missingTargetRoles(request.getProject(),
            EffectiveDeployTargets.resolve(request), request.getDeployScope());
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, EffectiveDeployTargets.missingTargetMessage(missing));
        }
    }

    private void assertEffectiveTargets(Project project, DeployEnvironment environment,
                                        com.portfolio.deployops.domain.DeployScope scope, HttpStatus status) {
        var targets = EffectiveDeployTargets.resolve(project, environment, scope);
        if (targets.isEmpty()) {
            throw new ResponseStatusException(status,
                environment + " 환경의 선택한 배포 범위에 대상 서버가 없습니다. 프로젝트 서버 그룹 매핑을 확인해 주세요.");
        }
        var missingRoles = EffectiveDeployTargets.missingComponentRoles(project, targets);
        if (!missingRoles.isEmpty()) {
            throw new ResponseStatusException(status, EffectiveDeployTargets.missingComponentMessage(missingRoles));
        }
        var missingTargets = EffectiveDeployTargets.missingTargetRoles(project, targets, scope);
        if (!missingTargets.isEmpty()) {
            throw new ResponseStatusException(status, EffectiveDeployTargets.missingTargetMessage(missingTargets));
        }
    }

    private boolean canAutoApproveVerifiedPromotion(
        DeployRequest request,
        DeployRequest testSource,
        boolean hasComponentOverrides
    ) {
        if (!executionPolicyService.shouldAutoApproveVerifiedPromotion(
            request, testSource, hasComponentOverrides)) {
            return false;
        }
        try {
            // TEST 확인 이후 산출물·배포 대상·런타임 설정이 바뀐 요청은 자동 승인 근거로 쓰지 않는다.
            deploymentPlanSnapshotService.assertCurrentPlanMatches(testSource);
            return true;
        } catch (ResponseStatusException ignored) {
            // 요청 자체는 수동 승인으로 진행하되 자동 승인만 fail-closed로 닫는다.
            return false;
        }
    }

    private void addStatusHistory(DeployRequest request, DeployStatus status) {
        StatusHistory history = StatusHistory.builder()
            .deployRequest(request)
            .status(status)
            .build();
        statusHistoryRepository.save(history);
    }

    private void publishDeploymentReady(DeployRequest request) {
        if (request.getStatus() == DeployStatus.TEST_REQUESTED
            || request.getStatus() == DeployStatus.APPROVAL_COMPLETED) {
            eventPublisher.publishEvent(new DeploymentReadyEvent(request.getId()));
        }
    }

    private void hydrateProjectTargets(Project project) {
        if (project == null || project.getId() == null) {
            return;
        }
        List<ServerGroup> serverGroups = projectRepository.findServerGroupsByProjectId(project.getId());
        serverGroups.forEach(group -> group.setServers(serverRepository.findByServerGroupId(group.getId())));
        project.setServerGroups(serverGroups);
        // 배포 단위(SSR/CSR)를 로드해 엔진이 artifactType별로 분기할 수 있게 한다.
        // 배포 단위가 없으면 RemoteDeployService가 기존 컨테이너 배포로 폴백한다.
        project.setComponents(deployComponentRepository.findActiveByProjectId(project.getId()));
    }

    private DeployEnvironment parseEnvironment(String rawEnvironment) {
        try {
            return DeployEnvironment.valueOf(normalize(rawEnvironment));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 배포 환경입니다.");
        }
    }

    private DeployRequestMode parseDeployMode(String rawMode) {
        try {
            return DeployRequestMode.valueOf(normalize(rawMode));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 배포 방식입니다.");
        }
    }

    private void applyBuildMetadata(
        DeployRequest request,
        DeployRequestCreateForm form,
        DeployRequest latestTestRequest,
        DeployRequestMode deployMode
    ) {
        if (request.getEnvironment() == DeployEnvironment.PROD && latestTestRequest != null) {
            request.setGitlabPipelineId(latestTestRequest.getGitlabPipelineId());
            request.setCommitSha(normalize(latestTestRequest.getCommitSha()));
            request.setImageTag(normalize(latestTestRequest.getImageTag()));
            request.setImageDigest(normalize(latestTestRequest.getImageDigest()));
            request.setExpectedCommitSha(normalize(latestTestRequest.getExpectedCommitSha()));
            request.setSourceMrIid(latestTestRequest.getSourceMrIid());
            request.setMrEvidenceId(latestTestRequest.getMrEvidenceId());
            request.setImmutableBuildRef(normalize(latestTestRequest.getImmutableBuildRef()));
            ReleaseVerificationEvidence.copyVerifiedFrom(latestTestRequest, request);
            return;
        }

        if (deployMode == DeployRequestMode.BUILD_AND_DEPLOY) {
            request.setGitlabPipelineId(null);
            request.setCommitSha(null);
            request.setImageTag(null);
            request.setImageDigest(null);
            return;
        }

        request.setGitlabPipelineId(coalesce(form.getGitlabPipelineId(), latestTestRequest != null ? latestTestRequest.getGitlabPipelineId() : null));
        request.setCommitSha(coalesce(normalize(form.getCommitSha()), latestTestRequest != null ? latestTestRequest.getCommitSha() : null));
        request.setImageTag(coalesce(normalize(form.getImageTag()), latestTestRequest != null ? latestTestRequest.getImageTag() : null));
        request.setImageDigest(coalesce(normalize(form.getImageDigest()), latestTestRequest != null ? latestTestRequest.getImageDigest() : null));
    }

    private void applyKnownImageProvenance(DeployRequest request, DeployRequestMode deployMode) {
        if (deployMode != DeployRequestMode.IMAGE_ONLY) return;
        projectImageHistoryService.findExactImageProvenance(request.getProject(), request.getImageTag(), request.getImageDigest())
            .ifPresent(provenance -> {
                if ((request.getGitlabPipelineId() != null && !request.getGitlabPipelineId().equals(provenance.pipelineId()))
                    || (normalize(request.getCommitSha()) != null
                        && !request.getCommitSha().trim().equalsIgnoreCase(provenance.commitSha()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "선택한 이미지의 Pipeline·Commit 증적과 요청 정보가 일치하지 않습니다.");
                }
                request.setGitlabPipelineId(provenance.pipelineId());
                request.setCommitSha(provenance.commitSha());
            });
    }

    /** project 행 잠금으로 동일 프로젝트의 활성 요청 확인과 insert를 직렬화한다. */
    private void lockProjectForRequestCreation(Long projectId) {
        if (projectId == null || projectRepository.lockById(projectId) == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "프로젝트가 변경되었거나 삭제되었습니다. 배포 요청을 다시 작성해 주세요."
            );
        }
    }

    public boolean isRollbackRequest(DeployRequest request) {
        return request != null
            && request.getReason() != null
            && request.getReason().startsWith(ROLLBACK_REASON_PREFIX);
    }

    private void validateRollbackConflict(Long projectId) {
        if (deployRequestRepository.existsByProjectIdAndStatusIn(
            projectId,
            List.of(
                DeployStatus.TEST_REQUESTED,
                DeployStatus.TEST_BUILDING,
                DeployStatus.TEST_DEPLOYING,
                DeployStatus.PROD_DEPLOYING,
                DeployStatus.APPROVAL_PENDING,
                DeployStatus.APPROVAL_COMPLETED
            )
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another deployment or approval is already in progress for this project.");
        }
    }

    private void validateRollbackSource(
        ProjectImageHistory history,
        Project project,
        DeployEnvironment environment
    ) {
        if (history.getProject() == null
            || history.getProject().getId() == null
            || !history.getProject().getId().equals(project.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rollback image history does not belong to the target project.");
        }
        if (history.getEnvironment() != environment) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rollback image history environment does not match the requested environment.");
        }
        if (!isRollbackStableHistory(history, environment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a stable deployed image history can be used for rollback.");
        }
        if (normalize(history.getImageTag()) == null || normalize(history.getImageDigest()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rollback image history must have immutable image tag/digest metadata.");
        }
        if ("latest".equalsIgnoreCase(normalize(history.getImageTag()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutable image tag 'latest' cannot be used for rollback.");
        }
    }

    private boolean isRollbackStableHistory(ProjectImageHistory history, DeployEnvironment environment) {
        ProjectImageHistoryStatus status = history.getHistoryStatus();
        if (status == ProjectImageHistoryStatus.ROLLED_BACK) {
            return true;
        }
        return environment == DeployEnvironment.TEST
            ? status == ProjectImageHistoryStatus.TEST_DEPLOYED
            : status == ProjectImageHistoryStatus.PROD_DEPLOYED;
    }

    private String buildRollbackReason(String reason, Long historyId) {
        String normalizedReason = normalize(reason);
        StringBuilder builder = new StringBuilder(ROLLBACK_REASON_PREFIX)
            .append("[history:")
            .append(historyId)
            .append("]");
        if (normalizedReason != null) {
            builder.append(' ').append(normalizedReason);
        }
        return builder.toString();
    }

    private void validateCreateRequest(
        DeployRequestCreateForm form,
        Project project,
        DeployEnvironment environment,
        DeployRequestMode deployMode
    ) {
        if (deployMode == DeployRequestMode.BUILD_AND_DEPLOY && environment != DeployEnvironment.TEST) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "새로 빌드 후 배포는 현재 TEST 환경에서만 지원합니다. PROD는 기존 이미지로 배포해 주세요."
            );
        }
        if (deployMode == DeployRequestMode.BUILD_AND_DEPLOY
            && normalize(form.getBranch()) == null && form.getSourceMergeRequestIid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "새로 빌드 후 배포에는 GitLab 브랜치 또는 태그가 필요합니다.");
        }
        if (deployMode == DeployRequestMode.IMAGE_ONLY
            && normalize(form.getImageTag()) == null
            && !(environment == DeployEnvironment.PROD && form.getPromotionSourceRequestId() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기존 이미지로 배포하려면 이미지 태그를 선택해 주세요.");
        }
        if (deployMode == DeployRequestMode.IMAGE_ONLY
            && normalize(form.getImageTag()) != null
            && "latest".equalsIgnoreCase(normalize(form.getImageTag()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutable image tag 'latest' cannot be deployed. Select an immutable tag.");
        }
        if (project == null || project.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트 정보가 올바르지 않습니다.");
        }
        if (environment == DeployEnvironment.PROD) {
            boolean noHealth = project.getHealthCheckMode() == null
                || project.getHealthCheckMode() == com.portfolio.deployops.domain.HealthCheckMode.NONE;
            var components = deployComponentRepository.findActiveByProjectId(project.getId());
            if (!components.isEmpty()) {
                noHealth = components.stream()
                    .anyMatch(c -> c.getHealthCheckMode() == null
                        || c.getHealthCheckMode() == com.portfolio.deployops.domain.HealthCheckMode.NONE);
            }
            if (noHealth) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PROD 배포는 Health Check(NONE 불가)를 설정해야 합니다.");
            }
            if (project.isContainerPrivileged()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PROD 배포에서 privileged 컨테이너는 허용되지 않습니다.");
            }
            if (components.stream().anyMatch(c -> c.isContainerPrivileged())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PROD 배포에서 privileged 배포 단위는 허용되지 않습니다.");
            }
        }
    }

    private MergeRequestEvidence resolveMergeEvidence(
        DeployRequestCreateForm form,
        Project project,
        DeployEnvironment environment,
        DeployRequestMode deployMode
    ) {
        Long iid = form.getSourceMergeRequestIid();
        if (deployMode != DeployRequestMode.BUILD_AND_DEPLOY) {
            if (iid != null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "MR 병합 증거는 새로 빌드 후 배포 요청에서만 사용할 수 있습니다."
                );
            }
            return null;
        }
        if (environment != DeployEnvironment.TEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MR 병합 결과 빌드는 TEST 환경에서만 시작할 수 있습니다.");
        }
        if (iid == null) {
            if (executionPolicyService.requireMrEvidenceForBuild()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "새 이미지 빌드는 DeployOps에서 승인·병합한 MR을 선택해야 합니다."
                );
            }
            return null;
        }
        if (project.getGitlabProjectId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트에 GitLab 저장소가 연결되어 있지 않습니다.");
        }
        MergeRequestEvidence evidence = mergeRequestEvidenceRepository.findMerged(
            project.getId(), project.getGitlabProjectId(), iid)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "DeployOps에서 승인·병합까지 완료된 MR 증거를 찾을 수 없습니다."
            ));
        if (!java.util.Objects.equals(evidence.getGitlabProjectId(), project.getGitlabProjectId())
            || normalize(evidence.getMergedResultSha()) == null
            || !evidence.getMergedResultSha().matches("^[0-9a-fA-F]{40,64}$")) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "MR 병합 증거가 현재 프로젝트 또는 확정 commit SHA와 일치하지 않습니다."
            );
        }
        return evidence;
    }

    private void applyMergeEvidence(DeployRequest request, MergeRequestEvidence evidence) {
        if (evidence == null) {
            return;
        }
        String expectedSha = evidence.getMergedResultSha().toLowerCase(Locale.ROOT);
        request.setBranch(normalize(evidence.getTargetBranch()));
        request.setExpectedCommitSha(expectedSha);
        request.setSourceMrIid(evidence.getMergeRequestIid());
        request.setMrEvidenceId(evidence.getId());
        request.setImmutableBuildRef("deploy/mr-" + evidence.getMergeRequestIid()
            + "-" + expectedSha.substring(0, 12));
    }

    private void validatePromotionSource(DeployRequest latestTestRequest) {
        if (latestTestRequest.getEnvironment() != DeployEnvironment.TEST
            || latestTestRequest.getStatus() != DeployStatus.TEST_CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a TEST_CONFIRMED request can be promoted to PROD.");
        }
        if (normalize(latestTestRequest.getImageTag()) == null || normalize(latestTestRequest.getImageDigest()) == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "TEST confirmed request does not have immutable image tag/digest metadata."
            );
        }
        if ("latest".equalsIgnoreCase(normalize(latestTestRequest.getImageTag()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutable image tag 'latest' cannot be promoted to PROD.");
        }
    }

    private void resolveImageDigestForImageOnly(
        DeployRequestCreateForm form,
        Project project,
        DeployRequestMode deployMode
    ) {
        if (deployMode != DeployRequestMode.IMAGE_ONLY || normalize(form.getImageDigest()) != null) {
            return;
        }

        try {
            Map<String, Object> manifest = nexusMetadataService.resolveManifest(project, normalize(form.getImageTag()));
            String digest = normalize(String.valueOf(manifest.getOrDefault("digest", "")));
            if (digest == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nexus manifest digest was not returned.");
            }
            form.setImageDigest(digest);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Nexus manifest digest could not be resolved: " + simplify(e.getMessage())
            );
        }
    }

    private String resolveRequestBranch(
        DeployRequestCreateForm form,
        Project project,
        DeployRequest latestTestRequest,
        DeployRequestMode deployMode
    ) {
        String explicitBranch = normalize(form.getBranch());
        if (explicitBranch != null) {
            return explicitBranch;
        }
        if (latestTestRequest != null && normalize(latestTestRequest.getBranch()) != null) {
            return normalize(latestTestRequest.getBranch());
        }
        if (normalize(project.getDefaultBranch()) != null) {
            return normalize(project.getDefaultBranch());
        }
        return deployMode == DeployRequestMode.IMAGE_ONLY ? "image-only" : null;
    }

    private <T> T coalesce(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 미지정은 ALL이지만, 오타를 전체 배포로 확대하지는 않는다. */
    private DeployScope parseDeployScope(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return DeployScope.ALL;
        }
        try {
            return DeployScope.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 배포 범위입니다.");
        }
    }

    /** 배포 전략 파싱. 미지정/비정상 값이면 SAFE(안전)로 안전하게 폴백. */
    private DeployStrategy parseDeployStrategy(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return DeployStrategy.SAFE;
        }
        try {
            return DeployStrategy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DeployStrategy.SAFE;
        }
    }

    private String simplify(String value) {
        if (value == null || value.isBlank()) {
            return "unknown error";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 160) + "..." : compact;
    }

    private String generateReqNo() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = Math.max(reqSeq.get(), 0);

        String reqNo;
        do {
            seq += 1;
            reqNo = String.format("REQ-%s-%03d", date, seq);
        } while (deployRequestRepository.existsByReqNo(reqNo));

        reqSeq.set(seq);
        return reqNo;
    }
}
