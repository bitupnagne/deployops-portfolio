package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.repository.ProjectImageHistoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ImageProvenanceTest {
    final ProjectImageHistoryRepository repository = mock(ProjectImageHistoryRepository.class);
    final ProjectImageHistoryService service = new ProjectImageHistoryService(repository);
    final Project project = Project.builder().id(3L).imageRepository("demo-app").build();
    final String sha = "0123456789abcdef0123456789abcdef01234567";
    ProjectImageHistory row(Long pipeline, String commit) {
        return ProjectImageHistory.builder().gitlabPipelineId(pipeline).commitSha(commit).build();
    }
    @Test void exactRepositoryTagDigestLookupReturnsNormalizedAuthoritativePair() {
        when(repository.findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed"))
            .thenReturn(List.of(row(4L, sha.toUpperCase())));
        assertThat(service.findExactImageProvenance(project, " release-4 ", " sha256:fixed "))
            .contains(new ProjectImageHistoryService.ImageProvenance(4L, sha));
        verify(repository).findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed");
    }
    @Test void missingHistoryKeepsUnknownInsteadOfInferringFromTag() {
        when(repository.findExactImageProvenance(3L, "demo-app", "main-7b431c63-4", "sha256:fixed"))
            .thenReturn(List.of());
        assertThat(service.findExactImageProvenance(project, "main-7b431c63-4", "sha256:fixed")).isEmpty();
    }
    @Test void missingRepositoryOrDigestNeverQueriesHistory() {
        assertThat(service.findExactImageProvenance(project, "release-4", null)).isEmpty();
        assertThat(service.findExactImageProvenance(Project.builder().id(3L).build(), "release-4", "sha256:fixed")).isEmpty();
        verifyNoInteractions(repository);
    }
    @Test void ambiguousPairsFailClosedRatherThanSelectingNewestPipeline() {
        when(repository.findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed"))
            .thenReturn(List.of(row(4L, sha), row(5L, sha)));
        assertThatThrownBy(() -> service.findExactImageProvenance(project, "release-4", "sha256:fixed"))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void malformedCommitIsNotPromotedToEvidence() {
        when(repository.findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed"))
            .thenReturn(List.of(row(4L, "tag-fragment")));
        assertThatThrownBy(() -> service.findExactImageProvenance(project, "release-4", "sha256:fixed"))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void invalidPipelineIsNotPromotedToEvidence() {
        when(repository.findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed"))
            .thenReturn(List.of(row(0L, sha)));
        assertThatThrownBy(() -> service.findExactImageProvenance(project, "release-4", "sha256:fixed"))
            .isInstanceOf(ResponseStatusException.class);
    }
}
