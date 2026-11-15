package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitlabPipelineArtifactServiceTest {

    private static final String REPOSITORY = "192.0.2.10:5000/worksystem/app";
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final GitlabService gitlabService = mock(GitlabService.class);
    private final GitlabPipelineArtifactService service = new GitlabPipelineArtifactService(gitlabService);
    private final Project project = Project.builder()
        .id(1L)
        .gitlabProjectId(101L)
        .imageRepository(REPOSITORY)
        .build();

    @Test
    void resolvesSuccessfulImageJobArtifactAsSourceOfTruth() throws Exception {
        when(gitlabService.getPipelineJobs("token", null, 101L, 77L)).thenReturn(List.of(
            Map.of("id", 700L, "name", "build_app", "status", "success"),
            Map.of("id", 701L, "name", GitlabCiContract.IMAGE_JOB_NAME, "status", "success")
        ));
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv());

        GitlabBuildArtifactMetadata result = service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA));

        assertThat(result.jobId()).isEqualTo(701L);
        assertThat(result.pipelineId()).isEqualTo(77L);
        assertThat(result.repository()).isEqualTo(REPOSITORY);
        assertThat(result.tag()).isEqualTo("main-01234567-77");
        assertThat(result.digest()).isEqualTo(DIGEST);
        assertThat(result.commitSha()).isEqualTo(COMMIT_SHA);
    }

    @Test
    void rejectsArtifactWithoutDigest() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace("IMAGE_DIGEST=" + DIGEST, "IMAGE_DIGEST="));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("IMAGE_DIGEST");
    }

    @Test
    void rejectsArtifactFromDifferentPipeline() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace("CI_PIPELINE_ID=77", "CI_PIPELINE_ID=78"));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("pipeline id")
            .hasMessageContaining("expected=77")
            .hasMessageContaining("actual=78");
    }

    @Test
    void rejectsArtifactFromDifferentCommit() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace(COMMIT_SHA,
                "ffffffffffffffffffffffffffffffffffffffff"));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("commit SHA")
            .hasMessageContaining("expected=")
            .hasMessageContaining("actual=");
    }

    @Test
    void rejectsArtifactForDifferentRepository() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace(REPOSITORY, REPOSITORY + "-other"));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("이미지 저장소")
            .hasMessageContaining("expected=");
    }

    @Test
    void rejectsMalformedOrDuplicateDotenvKeys() {
        assertThatThrownBy(() -> service.parseDotenv("IMAGE_TAG=a\nIMAGE_TAG=b\n"))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("중복된 키");
    }

    @Test
    void rejectsMutableLatestTag() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace("IMAGE_TAG=main-01234567-77", "IMAGE_TAG=latest"));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("latest");
    }

    @Test
    void rejectsUnsafeOrUnresolvedImageTag() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace(
                "IMAGE_TAG=main-01234567-77", "IMAGE_TAG=release-${unknown}"));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("Docker tag 규칙")
            .hasMessageContaining("미치환 토큰");
    }

    @Test
    void rejectsImageTagLongerThanDockerLimit() throws Exception {
        stubImageJob();
        when(gitlabService.downloadJobArtifactFile(
            "token", null, 101L, 701L, GitlabCiContract.ARTIFACT_FILE))
            .thenReturn(validDotenv().replace(
                "IMAGE_TAG=main-01234567-77", "IMAGE_TAG=" + "a".repeat(129)));

        assertThatThrownBy(() -> service.resolveAndVerify(
            "token", null, project, Map.of("id", 77L, "sha", COMMIT_SHA)))
            .isInstanceOf(RemoteExecutionException.class)
            .hasMessageContaining("Docker tag 규칙");
    }

    private void stubImageJob() throws Exception {
        when(gitlabService.getPipelineJobs("token", null, 101L, 77L)).thenReturn(List.of(
            Map.of("id", 701L, "name", GitlabCiContract.IMAGE_JOB_NAME, "status", "success")
        ));
    }

    private String validDotenv() {
        return "IMAGE_REPOSITORY=" + REPOSITORY + "\n"
            + "IMAGE_TAG=main-01234567-77\n"
            + "IMAGE_DIGEST=" + DIGEST + "\n"
            + "CI_COMMIT_SHA=" + COMMIT_SHA + "\n"
            + "CI_PIPELINE_ID=77\n";
    }
}
