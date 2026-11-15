package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class NexusMaintenanceEntryPointsTest {
    ExecutionGuardRepository reserved(){var g=mock(ExecutionGuardRepository.class,CALLS_REAL_METHODS);when(g.lockSingleton()).thenReturn(1);when(g.nexusMaintenanceOwner()).thenReturn(4L);return g;}
    @Test void manualAutomaticRetryAndServiceControlBlockBeforeAnyRemoteAction(){var g=reserved();var requests=mock(DeployRequestRepository.class);var s=new ExecutionPolicyService(mock(SystemSettingService.class),requests,g,mock(StatusHistoryRepository.class),mock(DeploymentPlanSnapshotService.class));var r=DeployRequest.builder().id(2L).build();for(var trigger:ExecutionTrigger.values())assertThatThrownBy(()->s.tryStart(r,DeployStatus.TEST_FAILED,DeployStatus.TEST_DEPLOYING,trigger)).hasMessageContaining("백업 유지보수");var invoked=new AtomicBoolean();assertThatThrownBy(()->s.withExecutionGuard(()->{invoked.set(true);return null;})).hasMessageContaining("백업 유지보수");assertThat(invoked).isFalse();verifyNoInteractions(requests);}
    @Test void directBuildGuardPreventsPipelineAndManifestReads(){var git=mock(GitlabService.class);var nexus=mock(NexusMetadataService.class);var s=new BuildOrchestrationService(git,mock(GitlabPipelineArtifactService.class),mock(GitlabCiReadinessService.class),nexus,mock(SystemSettingService.class),mock(UserRepository.class),mock(DeployRequestRepository.class),mock(MergeRequestEvidenceRepository.class),reserved());assertThatThrownBy(()->s.ensureBuildArtifact(DeployRequest.builder().project(Project.builder().id(1L).build()).environment(DeployEnvironment.TEST).build(),"OPERATOR",line->{})).hasMessageContaining("백업 유지보수");verifyNoInteractions(git,nexus);}
    @Test void cleanupApprovalBlocksBeforeNexusResolution(){var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());var nexus=mock(NexusMetadataService.class);var s=new NexusCleanupService(mock(NexusCleanupRequestRepository.class),mock(ProjectRepository.class),mock(ProjectImageHistoryRepository.class),mock(DeployRequestRepository.class),reserved(),nexus,tx);assertThatThrownBy(()->s.approveAndDelete(1L,2L)).hasMessageContaining("백업 유지보수");verifyNoInteractions(nexus);}
}
