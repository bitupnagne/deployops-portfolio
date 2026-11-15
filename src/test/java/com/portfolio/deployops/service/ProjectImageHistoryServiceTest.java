package com.portfolio.deployops.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.portfolio.deployops.repository.ProjectImageHistoryRepository;
import org.junit.jupiter.api.Test;

class ProjectImageHistoryServiceTest {

    private final ProjectImageHistoryRepository repository = mock(ProjectImageHistoryRepository.class);
    private final ProjectImageHistoryService service = new ProjectImageHistoryService(repository);

    @Test
    void recentHistoryUsesTheSharedRollbackRetentionWindow() {
        service.findRecentByProjectId(42L);

        verify(repository).findRecentByProjectId(
            42L, ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT);
    }
}
