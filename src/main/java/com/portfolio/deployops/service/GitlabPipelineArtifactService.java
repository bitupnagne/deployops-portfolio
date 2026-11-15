package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 성공한 image job의 artifact를 배포 메타데이터 정본으로 읽고 교차 검증한다. */
@Service
@RequiredArgsConstructor
public class GitlabPipelineArtifactService {

    private static final int MAX_DOTENV_LENGTH = 16 * 1024;
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");

    private final GitlabService gitlabService;

    public GitlabBuildArtifactMetadata resolveAndVerify(
        String token,
        String gitlabUrl,
        Project project,
        Map<String, Object> pipeline
    ) throws Exception {
        if (project == null || project.getGitlabProjectId() == null) {
            throw new RemoteExecutionException("GitLab 프로젝트 정보를 확인할 수 없습니다.");
        }
        long pipelineId = requireLong(pipeline.get("id"), "완료된 GitLab pipeline id가 없습니다.");
        String pipelineSha = requireText(pipeline.get("sha"), "완료된 GitLab pipeline commit SHA가 없습니다.");

        List<Map<String, Object>> jobs = gitlabService.getPipelineJobs(
            token, gitlabUrl, project.getGitlabProjectId(), pipelineId);
        Map<String, Object> imageJob = jobs.stream()
            .filter(job -> GitlabCiContract.IMAGE_JOB_NAME.equals(String.valueOf(job.get("name"))))
            .filter(job -> "success".equalsIgnoreCase(String.valueOf(job.get("status"))))
            .max((left, right) -> Long.compare(longValue(left.get("id")), longValue(right.get("id"))))
            .orElseThrow(() -> new RemoteExecutionException(
                "성공한 GitLab job '" + GitlabCiContract.IMAGE_JOB_NAME + "'을 찾을 수 없습니다."));
        long jobId = requireLong(imageJob.get("id"), "이미지 job id를 확인할 수 없습니다.");

        String dotenv = gitlabService.downloadJobArtifactFile(
            token,
            gitlabUrl,
            project.getGitlabProjectId(),
            jobId,
            GitlabCiContract.ARTIFACT_FILE
        );
        Map<String, String> values = parseDotenv(dotenv);

        String repository = requireValue(values, "IMAGE_REPOSITORY");
        String tag = requireValue(values, "IMAGE_TAG");
        String digest = requireValue(values, "IMAGE_DIGEST");
        String commitSha = requireValue(values, "CI_COMMIT_SHA");
        long artifactPipelineId = requireLong(requireValue(values, "CI_PIPELINE_ID"),
            "deploy-image.env의 CI_PIPELINE_ID가 숫자가 아닙니다.");

        String expectedRepository = requireText(project.getImageRepository(), "프로젝트 이미지 저장소가 비어 있습니다.");
        if (!expectedRepository.equals(repository)) {
            throw new RemoteExecutionException(
                "deploy-image.env 이미지 저장소가 프로젝트 설정과 다릅니다. expected="
                    + expectedRepository + ", actual=" + repository);
        }
        if ("latest".equalsIgnoreCase(tag)) {
            throw new RemoteExecutionException("변경 가능한 이미지 태그 latest는 배포할 수 없습니다.");
        }
        if (!GitlabCiContract.isValidImageTag(tag)) {
            throw new RemoteExecutionException(
                "deploy-image.env의 IMAGE_TAG가 Docker tag 규칙에 맞지 않거나 미치환 토큰을 포함합니다.");
        }
        if (!DIGEST_PATTERN.matcher(digest).matches()) {
            throw new RemoteExecutionException("deploy-image.env의 IMAGE_DIGEST 형식이 올바르지 않습니다.");
        }
        if (!pipelineSha.equalsIgnoreCase(commitSha)) {
            throw new RemoteExecutionException(
                "deploy-image.env commit SHA가 완료된 pipeline과 다릅니다. expected="
                    + pipelineSha + ", actual=" + commitSha);
        }
        if (pipelineId != artifactPipelineId) {
            throw new RemoteExecutionException(
                "deploy-image.env pipeline id가 완료된 pipeline과 다릅니다. expected="
                    + pipelineId + ", actual=" + artifactPipelineId);
        }

        GitlabBuildArtifactMetadata primary = new GitlabBuildArtifactMetadata(
            jobId, pipelineId, repository, tag, digest.toLowerCase(java.util.Locale.ROOT), commitSha);
        if (!GitlabReleaseManifest.required(project)) return primary;
        // No 404/failure fallback for a multi-component release: both contracts come
        // from this exact successful job, never another job or a mutable branch.
        String release = gitlabService.downloadJobArtifactFile(token, gitlabUrl,
            project.getGitlabProjectId(), jobId, GitlabCiContract.RELEASE_ARTIFACT_FILE);
        return GitlabReleaseManifest.parse(release, project, primary);
    }

    Map<String, String> parseDotenv(String body) {
        if (body == null || body.isBlank()) {
            throw new RemoteExecutionException("GitLab deploy-image.env artifact가 비어 있습니다.");
        }
        if (body.length() > MAX_DOTENV_LENGTH) {
            throw new RemoteExecutionException("GitLab deploy-image.env artifact가 허용 크기를 초과했습니다.");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new RemoteExecutionException("deploy-image.env에 잘못된 줄이 있습니다: " + line);
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (values.putIfAbsent(key, value) != null) {
                throw new RemoteExecutionException("deploy-image.env에 중복된 키가 있습니다: " + key);
            }
        }
        return values;
    }

    private String requireValue(Map<String, String> values, String key) {
        return requireText(values.get(key), "deploy-image.env에 " + key + " 값이 없습니다.");
    }

    private String requireText(Object value, String message) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new RemoteExecutionException(message);
        }
        return String.valueOf(value).trim();
    }

    private long requireLong(Object value, String message) {
        try {
            return Long.parseLong(requireText(value, message));
        } catch (NumberFormatException e) {
            throw new RemoteExecutionException(message, e);
        }
    }

    private long longValue(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }
}
