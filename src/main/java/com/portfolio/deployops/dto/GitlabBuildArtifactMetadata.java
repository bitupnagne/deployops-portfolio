package com.portfolio.deployops.dto;

/**
 * GitLab image job이 남긴 배포 산출물 계약.
 *
 * <p>파이프라인 성공 여부만으로는 어떤 이미지를 만들었는지 증명할 수 없다. 배포시스템은
 * 이 값을 job artifact에서 읽고 파이프라인·Nexus manifest와 대조한 뒤에만 요청에 반영한다.
 */
public record GitlabBuildArtifactMetadata(
    long jobId,
    long pipelineId,
    String repository,
    String tag,
    String digest,
    String commitSha,
    java.util.List<ComponentArtifact> components,
    String releaseManifestSha256
) {
    public GitlabBuildArtifactMetadata(long jobId, long pipelineId, String repository,
            String tag, String digest, String commitSha) {
        this(jobId, pipelineId, repository, tag, digest, commitSha, java.util.List.of(), null);
    }

    public GitlabBuildArtifactMetadata {
        components = components == null ? java.util.List.of() : java.util.List.copyOf(components);
    }

    public record ComponentArtifact(String code, String repository, String tag, String digest) { }
}
