package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ProjectImageHistoryStatus;
import com.portfolio.deployops.repository.ProjectImageHistoryRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProjectImageHistoryService {

    private final ProjectImageHistoryRepository projectImageHistoryRepository;

    public record ImageProvenance(Long pipelineId, String commitSha) {}

    @Transactional(readOnly = true)
    public Optional<ImageProvenance> findExactImageProvenance(Project project, String tag, String digest) {
        if (project == null || project.getId() == null || isBlank(project.getImageRepository())
            || isBlank(tag) || isBlank(digest)) return Optional.empty();
        List<ProjectImageHistory> matches = projectImageHistoryRepository.findExactImageProvenance(
            project.getId(), normalize(project.getImageRepository()), normalize(tag), normalize(digest));
        if (matches.isEmpty()) return Optional.empty();
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "선택한 이미지의 Pipeline·Commit 증적이 여러 건입니다. 이미지 이력을 확인해 주세요.");
        }
        ProjectImageHistory match = matches.getFirst();
        String sha = normalize(match.getCommitSha());
        if (match.getGitlabPipelineId() == null || match.getGitlabPipelineId() <= 0
            || sha == null || !sha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "선택한 이미지의 Pipeline·Commit 증적이 올바르지 않습니다.");
        }
        return Optional.of(new ImageProvenance(match.getGitlabPipelineId(), sha.toLowerCase(java.util.Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public List<ProjectImageHistory> findRecentByProjectId(Long projectId) {
        return projectImageHistoryRepository.findRecentByProjectId(
            projectId, ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT);
    }

    @Transactional(readOnly = true)
    public ProjectImageHistory findById(Long id) {
        return projectImageHistoryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image history not found."));
    }

    @Transactional(readOnly = true)
    public Optional<ProjectImageHistory> findLatestRollbackCandidate(DeployRequest request) {
        if (request == null
            || request.getProject() == null
            || request.getProject().getId() == null
            || request.getEnvironment() == null
            || request.getStatus() == null
            || !isFailureStatus(request.getStatus())) {
            return Optional.empty();
        }

        List<String> stableStatuses = switch (request.getEnvironment()) {
            case TEST -> List.of(
                ProjectImageHistoryStatus.TEST_DEPLOYED.name(),
                ProjectImageHistoryStatus.ROLLED_BACK.name()
            );
            case PROD -> List.of(
                ProjectImageHistoryStatus.PROD_DEPLOYED.name(),
                ProjectImageHistoryStatus.ROLLED_BACK.name()
            );
        };

        return projectImageHistoryRepository.findLatestStableByProjectIdAndEnvironmentAndStatuses(
            request.getProject().getId(),
            request.getEnvironment().name(),
            stableStatuses
        );
    }

    @Transactional(readOnly = true)
    public String buildImageReference(ProjectImageHistory history) {
        if (history == null || isBlank(history.getImageRepository()) || isBlank(history.getImageTag())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image repository metadata is incomplete.");
        }

        String repository = normalize(history.getImageRepository());
        if (!isBlank(history.getImageDigest())) {
            return repository + "@" + normalize(history.getImageDigest());
        }
        return repository + ":" + normalize(history.getImageTag());
    }

    @Transactional(readOnly = true)
    public String buildPullCommand(ProjectImageHistory history) {
        return "docker pull " + buildImageReference(history);
    }

    @Transactional(readOnly = true)
    public String buildSaveCommand(ProjectImageHistory history) {
        Project project = history != null ? history.getProject() : null;
        String baseName = project != null && !isBlank(project.getName())
            ? sanitizeFileToken(project.getName())
            : "image";
        String imageTag = history != null && !isBlank(history.getImageTag())
            ? sanitizeFileToken(history.getImageTag())
            : "latest";
        return "docker save -o " + baseName + "-" + imageTag + ".tar " + buildImageReference(history);
    }

    @Transactional(readOnly = true)
    public String buildDeliveryGuide(ProjectImageHistory history) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Docker image delivery guide").append('\n');
        builder.append("# imageHistoryId=").append(history.getId()).append('\n');
        builder.append("# imageReference=").append(buildImageReference(history)).append('\n');
        builder.append('\n');
        builder.append("# 1. Login to registry if required").append('\n');
        builder.append("# docker login <registry>").append('\n');
        builder.append('\n');
        builder.append("# 2. Pull image").append('\n');
        builder.append(buildPullCommand(history)).append('\n');
        builder.append('\n');
        builder.append("# 3. Save image as tar").append('\n');
        builder.append(buildSaveCommand(history)).append('\n');
        builder.append('\n');
        builder.append("# 4. Optional load on another machine").append('\n');
        builder.append("# docker load -i <saved-tar-file>").append('\n');
        return builder.toString();
    }

    @Transactional
    public void recordBuildCompletion(DeployRequest request) {
        saveSnapshot(request, ProjectImageHistoryStatus.BUILD_COMPLETED);
    }

    @Transactional
    public void recordDeploymentStatus(DeployRequest request) {
        if (request == null) {
            return;
        }

        ProjectImageHistoryStatus historyStatus = switch (request.getStatus()) {
            case TEST_DEPLOYED -> ProjectImageHistoryStatus.TEST_DEPLOYED;
            case COMPLETED -> ProjectImageHistoryStatus.PROD_DEPLOYED;
            case ROLLED_BACK -> ProjectImageHistoryStatus.ROLLED_BACK;
            default -> null;
        };

        if (historyStatus == null) {
            return;
        }

        saveSnapshot(request, historyStatus);
    }

    private void saveSnapshot(DeployRequest request, ProjectImageHistoryStatus historyStatus) {
        if (request == null
            || request.getId() == null
            || request.getProject() == null
            || request.getProject().getId() == null
            || isBlank(request.getImageTag())) {
            return;
        }

        ProjectImageHistory history = projectImageHistoryRepository
            .findByDeployRequestIdAndHistoryStatus(request.getId(), historyStatus)
            .orElseGet(ProjectImageHistory::new);

        history.setProject(request.getProject());
        history.setDeployRequest(request);
        history.setEnvironment(request.getEnvironment());
        history.setBranch(normalize(request.getBranch()));
        history.setImageRepository(normalize(request.getProject().getImageRepository()));
        history.setImageTag(normalize(request.getImageTag()));
        history.setImageDigest(normalize(request.getImageDigest()));
        history.setGitlabPipelineId(request.getGitlabPipelineId());
        history.setCommitSha(normalize(request.getCommitSha()));
        history.setHistoryStatus(historyStatus);

        projectImageHistoryRepository.save(history);
    }

    private boolean isFailureStatus(DeployStatus status) {
        return status == DeployStatus.TEST_FAILED || status == DeployStatus.PROD_FAILED;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sanitizeFileToken(String value) {
        return normalize(value).replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
