package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitlabReleaseManifestTest {
    static final String SHA = "a".repeat(40), API = "sha256:" + "b".repeat(64), WEB = "sha256:" + "c".repeat(64);
    static Project project() {
        return Project.builder().id(1L).gitlabProjectId(9L).imageRepository("registry/qa/api").components(List.of(
            DeployComponent.builder().id(11L).code("api").role(ServerType.WAS).artifactType(ArtifactType.CONTAINER)
                .imageRepository("registry/qa/api").active(true).build(),
            DeployComponent.builder().id(12L).code("web").role(ServerType.WEB).artifactType(ArtifactType.STATIC_BUNDLE)
                .imageRepository("registry/qa/web").active(true).build())).build();
    }
    static GitlabBuildArtifactMetadata primary() {
        return new GitlabBuildArtifactMetadata(99L, 8L, "registry/qa/api", "v1", API, SHA);
    }
    static String body() {
        return """
            {"schemaVersion":1,"pipelineId":8,"commitSha":"%s","components":[
              {"code":"web","repository":"registry/qa/web","tag":"v1","digest":"%s"},
              {"code":"api","repository":"registry/qa/api","tag":"v1","digest":"%s"}]}
            """.formatted(SHA, WEB, API);
    }
    @Test void completeReleaseResolvesRegisteredComponentsAndStableHash() {
        var parsed = GitlabReleaseManifest.parse(body(), project(), primary());
        assertThat(parsed.components()).extracting(GitlabBuildArtifactMetadata.ComponentArtifact::code).containsExactly("api","web");
        assertThat(parsed.releaseManifestSha256()).matches("[0-9a-f]{64}");
        assertThat(GitlabReleaseManifest.validateMetadata(project(), parsed)).isEqualTo(parsed);
        assertThat(GitlabReleaseManifest.parse(body().replace("  ",""),project(),primary()).releaseManifestSha256())
            .isEqualTo(parsed.releaseManifestSha256());
    }
    @Test void rejectsMissingExtraDuplicateUnknownAndProxyArtifacts() {
        for (String invalid : List.of(
                body().replace("\"code\":\"web\"", "\"code\":\"api\""),
                body().replace("\"code\":\"web\"", "\"code\":\"unknown\""),
                body().replace("},\n", "},\n{\"code\":\"extra\",\"repository\":\"x\",\"tag\":\"v1\",\"digest\":\""+WEB+"\"},\n"),
                "{\"schemaVersion\":1,\"pipelineId\":8,\"commitSha\":\""+SHA+"\",\"components\":[]}")) {
            assertThatThrownBy(() -> GitlabReleaseManifest.parse(invalid,project(),primary())).isInstanceOf(RemoteExecutionException.class);
        }
        var proxy = project(); proxy.getComponents().get(1).setArtifactType(ArtifactType.PROXY_RELOAD);
        assertThatThrownBy(() -> GitlabReleaseManifest.parse(body(),proxy,primary())).isInstanceOf(RemoteExecutionException.class);
    }
    @Test void rejectsRepositoryPipelineCommitMutableTagDigestAndPrimaryMismatch() {
        for (String invalid : List.of(
                body().replace("registry/qa/web","registry/other/web"),
                body().replace("\"pipelineId\":8","\"pipelineId\":9"),
                body().replace(SHA,"d".repeat(40)), body().replace("\"tag\":\"v1\"","\"tag\":\"latest\""),
                body().replace(WEB,"sha256:bad"), body().replace(API,"sha256:"+"d".repeat(64)),
                body().replace("\"schemaVersion\":1","\"schemaVersion\":4294967297"))) {
            assertThatThrownBy(() -> GitlabReleaseManifest.parse(invalid,project(),primary())).isInstanceOf(RemoteExecutionException.class);
        }
    }
    @Test void duplicateJsonFieldsTrailingDocumentAndOversizeAreRejectedWithoutEchoingContent() {
        for (String invalid : List.of(body().replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                body()+"{}", "secret-marker".repeat(30000))) {
            assertThatThrownBy(() -> GitlabReleaseManifest.parse(invalid,project(),primary()))
                .isInstanceOf(RemoteExecutionException.class).hasMessageNotContaining("secret-marker");
        }
    }
    @Test void duplicateRepositoriesAndMissingPrimaryCannotFormCiRelease() {
        var project = project(); project.getComponents().get(1).setImageRepository(project.getImageRepository());
        assertThatThrownBy(() -> GitlabReleaseManifest.validateConfiguration(project)).isInstanceOf(RemoteExecutionException.class);
        project.getComponents().get(1).setImageRepository("registry/qa/web"); project.setImageRepository("registry/missing");
        assertThatThrownBy(() -> GitlabReleaseManifest.validateConfiguration(project)).isInstanceOf(RemoteExecutionException.class);
    }
    @Test void manifestComesFromExactSuccessfulPrimaryJobAndMissingJsonNeverFallsBack() throws Exception {
        GitlabService gitlab = mock(GitlabService.class);
        var service = new GitlabPipelineArtifactService(gitlab);
        when(gitlab.getPipelineJobs("token",null,9L,8L)).thenReturn(List.of(
            Map.of("id",98L,"name",GitlabCiContract.IMAGE_JOB_NAME,"status","success"),
            Map.of("id",99L,"name",GitlabCiContract.IMAGE_JOB_NAME,"status","success"),
            Map.of("id",100L,"name",GitlabCiContract.IMAGE_JOB_NAME,"status","failed")));
        when(gitlab.downloadJobArtifactFile("token",null,9L,99L,GitlabCiContract.ARTIFACT_FILE)).thenReturn(
            "IMAGE_REPOSITORY=registry/qa/api\nIMAGE_TAG=v1\nIMAGE_DIGEST="+API+"\nCI_COMMIT_SHA="+SHA+"\nCI_PIPELINE_ID=8\n");
        when(gitlab.downloadJobArtifactFile("token",null,9L,99L,GitlabCiContract.RELEASE_ARTIFACT_FILE)).thenReturn(body());
        assertThat(service.resolveAndVerify("token",null,project(),Map.of("id",8L,"sha",SHA)).components()).hasSize(2);
        when(gitlab.downloadJobArtifactFile("token",null,9L,99L,GitlabCiContract.RELEASE_ARTIFACT_FILE)).thenThrow(new java.io.IOException("missing"));
        assertThatThrownBy(() -> service.resolveAndVerify("token",null,project(),Map.of("id",8L,"sha",SHA)))
            .isInstanceOf(java.io.IOException.class);
        verify(gitlab,never()).downloadJobArtifactFile("token",null,9L,98L,GitlabCiContract.RELEASE_ARTIFACT_FILE);
    }
}
