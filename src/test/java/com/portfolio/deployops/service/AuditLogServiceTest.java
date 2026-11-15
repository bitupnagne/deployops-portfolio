package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.AuditLog;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class AuditLogServiceTest {

    private final AuditLogRepository repository = Mockito.mock(AuditLogRepository.class);
    private final AuditLogService service = new AuditLogService(repository, new SecretMaskingService());

    @Test
    void masksSecretsBeforeSavingAuditLog() {
        when(repository.findLegacyEntries()).thenReturn(java.util.List.of());
        when(repository.findChainedEntries()).thenReturn(java.util.List.of());
        service.record(
            "admin",
            "GitLab token updated",
            "SETTING",
            "gitlab.token=glpat-secret-token",
            "Authorization: Bearer abcdefghijklmnop",
            AuditResult.SUCCESS,
            "127.0.0.1"
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getTargetId()).contains("gitlab.token=***");
        assertThat(saved.getTargetDesc()).contains("Authorization: Bearer ***");
        assertThat(saved.getTargetId()).doesNotContain("glpat-secret-token");
        assertThat(saved.getTargetDesc()).doesNotContain("abcdefghijklmnop");
        assertThat(saved.getPreviousHash()).hasSize(64);
        assertThat(saved.getEntryHash()).hasSize(64);
    }

    @Test
    void detectsModifiedChainedEntry() {
        when(repository.findLegacyEntries()).thenReturn(java.util.List.of());
        when(repository.findChainedEntries()).thenReturn(java.util.List.of());
        service.record("operator", "PROD 배포", "DeployRequest", "1", "REQ-1", AuditResult.SUCCESS, "198.18.8.1");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        when(repository.findChainedEntries()).thenReturn(java.util.List.of(saved));
        assertThat(service.verifyIntegrity().valid()).isTrue();

        saved.setTargetDesc("변조된 내용");
        assertThat(service.verifyIntegrity().valid()).isFalse();
        assertThat(service.verifyIntegrity().failedEntryId()).isEqualTo(saved.getId());
    }

    @Test
    void unknownOutcomeIsPersistedAsUnknownAndParticipatesInHashChain() {
        when(repository.findLegacyEntries()).thenReturn(List.of());
        when(repository.findChainedEntries()).thenReturn(List.of());
        service.record("operator", "CI_VARIABLE_UPSERT", "GitlabCiVariable", "3:CI_BUILD_IMAGE", "변경 결과 미확인",
            AuditResult.UNKNOWN, "127.0.0.1");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getResult()).isEqualTo(AuditResult.UNKNOWN);
        when(repository.findChainedEntries()).thenReturn(List.of(saved));
        assertThat(service.verifyIntegrity().valid()).isTrue();
        saved.setResult(AuditResult.FAIL);
        assertThat(service.verifyIntegrity().valid()).isFalse();
    }

    @Test
    void requiredAuditRecordingPropagatesPersistenceFailure() {
        when(repository.findLegacyEntries()).thenThrow(
            new DataAccessResourceFailureException("audit database unavailable"));

        assertThatThrownBy(() -> service.recordCurrentRequired(
            "사용자 역할 변경", "User", "7", "DEVELOPER → APPROVER", AuditResult.SUCCESS))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("audit database unavailable");
    }

    @Test
    void bestEffortRecordingIsExplicitlyAvailableForNonControlEvents() {
        when(repository.findLegacyEntries()).thenThrow(
            new DataAccessResourceFailureException("audit database unavailable"));

        service.recordCurrent("상태 조회", "Server", "srv-1", "health probe", AuditResult.SUCCESS);
    }

    @Test
    void serializesAuditChainUntilEnclosingTransactionCompletes() throws Exception {
        when(repository.findLegacyEntries()).thenReturn(List.of());
        when(repository.findChainedEntries()).thenReturn(List.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstRecorded = new CountDownLatch(1);
        CountDownLatch finishFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                TransactionSynchronizationManager.initSynchronization();
                try {
                    service.record("first", "action", "type", "1", "first", AuditResult.SUCCESS, "127.0.0.1");
                    firstRecorded.countDown();
                    try {
                        if (!finishFirstTransaction.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not complete the first transaction");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("test thread interrupted", ex);
                    }
                    TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(0));
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                }
            });
            assertThat(firstRecorded.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> {
                service.record("second", "action", "type", "2", "second", AuditResult.SUCCESS, "127.0.0.1");
                secondFinished.countDown();
            });
            assertThat(secondFinished.await(150, TimeUnit.MILLISECONDS))
                .as("a second chain writer must wait until the first transaction commits or rolls back")
                .isFalse();

            finishFirstTransaction.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(secondFinished.getCount()).isZero();
            verify(repository, Mockito.times(2)).save(Mockito.any(AuditLog.class));
        } finally {
            finishFirstTransaction.countDown();
            executor.shutdownNow();
        }
    }
}
