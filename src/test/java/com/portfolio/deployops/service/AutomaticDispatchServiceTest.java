package com.portfolio.deployops.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.DeployStrategy;
import com.portfolio.deployops.domain.DeploymentReadyEvent;
import com.portfolio.deployops.domain.ExecutionTrigger;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.repository.DeployRequestRepository;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AutomaticDispatchServiceTest {

    @Test
    void actualCommonPolicyZeroTargetPermanentlyBlocksAutoWithoutLaunchOrCas() {
        var settings = mock(SystemSettingService.class);
        var guard = mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class);
        var history = mock(com.portfolio.deployops.repository.StatusHistoryRepository.class);
        var snapshots = mock(DeploymentPlanSnapshotService.class);
        var policy = new ExecutionPolicyService(settings, repository, guard, history, snapshots);
        var dispatcher = new AutomaticDispatchService(repository, deployRequestService,
            policy, operatorExecutionService, auditLogService);
        DeployRequest request = request(DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED, DeployStrategy.SAFE);
        request.getProject().setServerGroups(List.of());
        when(settings.get("auto-dispatch-prod", "false")).thenReturn("true");
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(guard.lockSingleton()).thenReturn(1);
        when(repository.lockById(1L)).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(request));
        when(snapshots.applySnapshot(request)).thenReturn(true);
        when(repository.blockAutomaticDispatch(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(DeployStatus.APPROVAL_COMPLETED), anyString())).thenReturn(1);
        dispatcher.dispatchIfEligible(1L);
        verify(snapshots).assertCurrentPlanMatches(request);
        verify(repository).blockAutomaticDispatch(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(DeployStatus.APPROVAL_COMPLETED),
            org.mockito.ArgumentMatchers.contains("대상 서버가 없습니다"));
        verify(repository, never()).compareAndSetExecutionStart(any(), any(), any(), any());
        verify(history, never()).save(any());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    private final DeployRequestRepository repository = mock(DeployRequestRepository.class);
    private final DeployRequestService deployRequestService = mock(DeployRequestService.class);
    private final ExecutionPolicyService executionPolicyService = mock(ExecutionPolicyService.class);
    private final OperatorExecutionService operatorExecutionService = mock(OperatorExecutionService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AutomaticDispatchService service = new AutomaticDispatchService(
        repository,
        deployRequestService,
        executionPolicyService,
        operatorExecutionService,
        auditLogService
    );

    @Test
    void scanDoesNothingWhenBothOptionsAreOff() {
        when(executionPolicyService.autoDispatchTest()).thenReturn(false);
        when(executionPolicyService.autoDispatchProd()).thenReturn(false);

        service.scanMissedReadyEvents();

        verify(repository, never()).findByStatusInWithProject(any());
    }

    @Test
    void afterCommitEventDoesNotReadRequestWhenAutomationIsOff() {
        when(executionPolicyService.autoDispatchTest()).thenReturn(false);
        when(executionPolicyService.autoDispatchProd()).thenReturn(false);

        service.onDeploymentReady(new DeploymentReadyEvent(1L));

        verify(deployRequestService, never()).findById(any());
    }

    @Test
    void afterCommitEventDispatchesEligibleSafeTestWithSystemActor() {
        DeployRequest request = request(DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED, DeployStrategy.SAFE);
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchTest()).thenReturn(true);
        when(executionPolicyService.tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.AUTO)).thenReturn(true);

        service.onDeploymentReady(new DeploymentReadyEvent(1L));

        verify(executionPolicyService).tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.AUTO);
        verify(operatorExecutionService).executeRemoteDeployment(
            1L,
            AutomaticDispatchService.SYSTEM_ACTOR,
            AutomaticDispatchService.SYSTEM_IP,
            AutomaticDispatchService.SYSTEM_ACTOR
        );
        verify(auditLogService).record(
            anyString(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void manualStrategyIsNeverAutoDispatched() {
        DeployRequest request = request(DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED, DeployStrategy.MANUAL);
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchTest()).thenReturn(true);

        service.dispatchIfEligible(1L);

        verify(deployRequestService, never()).assertPlanUnchanged(any());
        verify(executionPolicyService, never()).tryStart(any(), any(), any(), any());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void losingStartCasDoesNotLaunchExecution() {
        DeployRequest request = request(DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED, DeployStrategy.WAVE);
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchProd()).thenReturn(true);
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING, ExecutionTrigger.AUTO)).thenReturn(false);

        service.dispatchIfEligible(1L);

        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void unverifiedProdRollbackIsNotAutoDispatched() {
        DeployRequest request = request(DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED, DeployStrategy.SAFE);
        request.setReason("[ROLLBACK][history:77] restore");
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchProd()).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CI 출처 미검증"))
            .when(deployRequestService).assertProductionRollbackProvenance(request);
        when(repository.blockAutomaticDispatch(1L, DeployStatus.APPROVAL_COMPLETED, "CI 출처 미검증"))
            .thenReturn(1);

        service.dispatchIfEligible(1L);

        verify(executionPolicyService, never()).tryStart(any(), any(), any(), any());
        verify(repository).blockAutomaticDispatch(1L, DeployStatus.APPROVAL_COMPLETED, "CI 출처 미검증");
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void recoveryScanUsesTheSameDispatchGate() {
        DeployRequest request = request(DeployEnvironment.PROD, DeployStatus.APPROVAL_COMPLETED, DeployStrategy.SAFE);
        when(executionPolicyService.autoDispatchTest()).thenReturn(false);
        when(executionPolicyService.autoDispatchProd()).thenReturn(true);
        when(repository.findAutoDispatchCandidatesAfterId(
            List.of(DeployStatus.TEST_REQUESTED, DeployStatus.APPROVAL_COMPLETED), 0L, 100))
            .thenReturn(List.of(request));
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.tryStart(
            request, DeployStatus.APPROVAL_COMPLETED, DeployStatus.PROD_DEPLOYING, ExecutionTrigger.AUTO)).thenReturn(true);

        service.scanMissedReadyEvents();

        verify(operatorExecutionService).executeRemoteDeployment(
            1L,
            AutomaticDispatchService.SYSTEM_ACTOR,
            AutomaticDispatchService.SYSTEM_IP,
            AutomaticDispatchService.SYSTEM_ACTOR
        );
    }

    @Test
    void launchFailureUsesStartedStatusAsCasExpectation() {
        DeployRequest request = request(DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED, DeployStrategy.SAFE);
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchTest()).thenReturn(true);
        doAnswer(invocation -> {
            request.setStatus(DeployStatus.TEST_BUILDING);
            return true;
        }).when(executionPolicyService).tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.AUTO);
        doThrow(new IllegalStateException("executor unavailable"))
            .when(operatorExecutionService).executeRemoteDeployment(any(), any(), any(), any());
        when(deployRequestService.tryTransition(
            1L, DeployStatus.TEST_BUILDING, DeployStatus.TEST_FAILED)).thenReturn(true);

        service.dispatchIfEligible(1L);

        verify(deployRequestService).tryTransition(
            1L, DeployStatus.TEST_BUILDING, DeployStatus.TEST_FAILED);
    }

    @Test
    void reusableTestArtifactSkipsBuildState() {
        DeployRequest request = request(DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED, DeployStrategy.SAFE);
        request.setImageTag("release-01234567-77");
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchTest()).thenReturn(true);
        when(executionPolicyService.tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_DEPLOYING, ExecutionTrigger.AUTO)).thenReturn(true);

        service.dispatchIfEligible(1L);

        verify(executionPolicyService).tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_DEPLOYING, ExecutionTrigger.AUTO);
    }

    @Test
    void permanentPlanFailureIsBlockedAndAuditedOnlyAfterCas() {
        DeployRequest request = request(DeployEnvironment.TEST, DeployStatus.TEST_REQUESTED, DeployStrategy.SAFE);
        when(deployRequestService.findById(1L)).thenReturn(request);
        when(executionPolicyService.autoDispatchTest()).thenReturn(true);
        when(executionPolicyService.tryStart(
            request, DeployStatus.TEST_REQUESTED, DeployStatus.TEST_BUILDING, ExecutionTrigger.AUTO))
            .thenThrow(new AutomaticDispatchPlanBlockedException("배포 계획이 변경되었습니다.", null));
        when(repository.blockAutomaticDispatch(
            1L, DeployStatus.TEST_REQUESTED, "배포 계획이 변경되었습니다.")).thenReturn(1);

        service.dispatchIfEligible(1L);

        verify(repository).blockAutomaticDispatch(
            1L, DeployStatus.TEST_REQUESTED, "배포 계획이 변경되었습니다.");
        verify(auditLogService).record(
            anyString(), org.mockito.ArgumentMatchers.contains("계획 검증 차단"),
            anyString(), anyString(), any(), any(), anyString());
        verify(operatorExecutionService, never()).executeRemoteDeployment(any(), any(), any(), any());
    }

    @Test
    void recoveryScanAdvancesAfterFullPageAndResetsAfterShortPage() {
        when(executionPolicyService.autoDispatchTest()).thenReturn(true);
        List<DeployRequest> firstPage = LongStream.rangeClosed(1, 100)
            .mapToObj(id -> requestWithId(id, DeployStrategy.MANUAL))
            .toList();
        DeployRequest last = requestWithId(101L, DeployStrategy.MANUAL);
        when(repository.findAutoDispatchCandidatesAfterId(READY_STATUSES(), 0L, 100))
            .thenReturn(firstPage);
        when(repository.findAutoDispatchCandidatesAfterId(READY_STATUSES(), 100L, 100))
            .thenReturn(List.of(last));
        when(deployRequestService.findById(any())).thenAnswer(invocation ->
            requestWithId(invocation.getArgument(0), DeployStrategy.MANUAL));

        service.scanMissedReadyEvents();
        service.scanMissedReadyEvents();
        service.scanMissedReadyEvents();

        verify(repository, times(2)).findAutoDispatchCandidatesAfterId(READY_STATUSES(), 0L, 100);
        verify(repository).findAutoDispatchCandidatesAfterId(READY_STATUSES(), 100L, 100);
    }

    private List<DeployStatus> READY_STATUSES() {
        return List.of(DeployStatus.TEST_REQUESTED, DeployStatus.APPROVAL_COMPLETED);
    }

    private DeployRequest requestWithId(Long id, DeployStrategy strategy) {
        return DeployRequest.builder()
            .id(id)
            .reqNo("REQ-" + id)
            .project(Project.builder().id(10L).name("app").build())
            .environment(DeployEnvironment.TEST)
            .status(DeployStatus.TEST_REQUESTED)
            .deployStrategy(strategy)
            .build();
    }

    private DeployRequest request(
        DeployEnvironment environment,
        DeployStatus status,
        DeployStrategy strategy
    ) {
        return DeployRequest.builder()
            .id(1L)
            .reqNo("REQ-1")
            .project(Project.builder().id(10L).name("app").build())
            .environment(environment)
            .status(status)
            .deployStrategy(strategy)
            .build();
    }
}
