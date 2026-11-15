package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.ManualServerExecution;
import com.portfolio.deployops.repository.ManualServerExecutionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManualServerExecutionServiceTest {

    private final ManualServerExecutionRepository repository = mock(ManualServerExecutionRepository.class);
    private final ManualServerExecutionService service = new ManualServerExecutionService(repository);

    @Test
    void acquireRejectsAlreadyRunningOrSuccessfulServer() {
        assertThatThrownBy(() -> service.acquire(1L, 2L, 3L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409");
    }

    @Test
    void failedServerCanBeAcquiredForRetry() {
        when(repository.retryFailed(1L, 2L, 3L)).thenReturn(1);

        service.acquire(1L, 2L, 3L);

        verify(repository).retryFailed(1L, 2L, 3L);
    }

    @Test
    void completionRequiresEveryTargetToHaveSucceeded() {
        when(repository.findByRequestId(1L)).thenReturn(List.of(
            ManualServerExecution.builder().serverId(10L).status("SUCCESS").build(),
            ManualServerExecution.builder().serverId(11L).status("FAILED").build()
        ));

        assertThat(service.allSucceeded(1L, List.of(10L, 11L))).isFalse();
        assertThat(service.allSucceeded(1L, List.of(10L))).isTrue();
    }
}
