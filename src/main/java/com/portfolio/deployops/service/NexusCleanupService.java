package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.NexusCleanupRequest;
import com.portfolio.deployops.domain.NexusCleanupStatus;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.repository.NexusCleanupRequestRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.ExecutionGuardRepository;
import com.portfolio.deployops.repository.ProjectImageHistoryRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NexusCleanupService {
    private final NexusCleanupRequestRepository repository;
    private final ProjectRepository projectRepository;
    private final ProjectImageHistoryRepository imageHistoryRepository;
    private final DeployRequestRepository deployRequestRepository;
    private final ExecutionGuardRepository executionGuardRepository;
    private final NexusMetadataService nexusMetadataService;
    private final TransactionTemplate transactionTemplate;

    public NexusCleanupService(NexusCleanupRequestRepository repository,
                               ProjectRepository projectRepository,
                               ProjectImageHistoryRepository imageHistoryRepository,
                               DeployRequestRepository deployRequestRepository,
                               ExecutionGuardRepository executionGuardRepository,
                               NexusMetadataService nexusMetadataService,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.imageHistoryRepository = imageHistoryRepository;
        this.deployRequestRepository = deployRequestRepository;
        this.executionGuardRepository = executionGuardRepository;
        this.nexusMetadataService = nexusMetadataService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<NexusCleanupRequest> findRecent() {
        return repository.findRecent();
    }

    @Transactional
    public NexusCleanupRequest create(Long projectId, String tag, String reason, Long requesterId) {
        executionGuardRepository.assertNoNexusMaintenance();
        Project project = requireProject(projectId);
        String normalizedTag = requireText(tag, 255, "삭제할 이미지 태그를 입력하세요.");
        String normalizedReason = requireText(reason, 1000, "정리 사유를 입력하세요.");
        try {
            Map<String, Object> manifest = nexusMetadataService.resolveManifest(project, normalizedTag);
            String digest = String.valueOf(manifest.getOrDefault("digest", ""));
            requireDigest(digest);
            assertNoInFlightDeployment(projectId);
            assertNotProtected(project, digest);
            NexusCleanupRequest request = NexusCleanupRequest.builder()
                .project(project)
                .requestedBy(User.builder().id(requesterId).build())
                .status(NexusCleanupStatus.PENDING)
                .repositoryPath(String.valueOf(manifest.getOrDefault("repositoryPath", "")))
                .imageRepository(project.getImageRepository())
                .imageTag(normalizedTag)
                .imageDigest(digest)
                .reason(normalizedReason)
                .build();
            repository.insert(request);
            return request;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nexus 정리 대상을 확인하지 못했습니다.", ex);
        }
    }

    public NexusCleanupRequest approveAndDelete(Long id, Long approverId) {
        ApprovalTarget target = transactionTemplate.execute(status -> prepareApproval(id, approverId));
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Nexus 정리 승인 트랜잭션 결과가 없습니다.");
        }
        NexusCleanupRequest result = transactionTemplate.execute(status -> executeApprovedDelete(target));
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Nexus 정리 실행 트랜잭션 결과가 없습니다.");
        }
        return result;
    }

    private ApprovalTarget prepareApproval(Long id, Long approverId) {
        if (executionGuardRepository.lockSingleton() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "배포 실행 잠금 정보를 찾을 수 없습니다.");
        }
        executionGuardRepository.assertNoNexusMaintenance();
        NexusCleanupRequest request = require(id);
        if (request.getRequestedBy().getId().equals(approverId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요청자와 승인자는 달라야 합니다.");
        }
        Project project = requireProject(request.getProject().getId());
        if (!java.util.Objects.equals(request.getImageRepository(), project.getImageRepository())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "승인 대기 중 프로젝트 이미지 저장소가 변경되었습니다. 정리를 다시 요청하세요.");
        }
        try {
            Map<String, Object> current = nexusMetadataService.resolveManifest(project, request.getImageTag());
            String currentDigest = String.valueOf(current.getOrDefault("digest", ""));
            String currentPath = String.valueOf(current.getOrDefault("repositoryPath", ""));
            if (!request.getRepositoryPath().equals(currentPath)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "승인 대기 중 Nexus 저장소 경로가 변경되었습니다. 정리를 다시 요청하세요.");
            }
            if (!request.getImageDigest().equalsIgnoreCase(currentDigest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "승인 대기 중 태그가 다른 이미지로 변경되어 삭제를 중단했습니다.");
            }
            assertNoInFlightDeployment();
            assertNotProtected(project, currentDigest);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "승인 대상 이미지를 다시 확인하지 못했습니다.", ex);
        }
        if (repository.acquireApproval(id, approverId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리되었거나 승인할 수 없는 요청입니다.");
        }
        return new ApprovalTarget(id, project.getId(), request.getImageRepository(),
            request.getRepositoryPath(), request.getImageTag(), request.getImageDigest());
    }

    private NexusCleanupRequest executeApprovedDelete(ApprovalTarget target) {
        if (executionGuardRepository.lockSingleton() == null) {
            repository.finish(target.id(), NexusCleanupStatus.FAILED.name(),
                "배포 실행 잠금 정보를 찾을 수 없습니다.");
            return require(target.id());
        }
        boolean deleteAttempted = false;
        try {
            executionGuardRepository.assertNoNexusMaintenance();
            NexusCleanupRequest deleting = require(target.id());
            if (deleting.getStatus() != NexusCleanupStatus.DELETING) {
                throw new IllegalArgumentException("승인된 삭제 작업 상태가 DELETING이 아닙니다.");
            }
            Project project = requireProject(target.projectId());
            if (!java.util.Objects.equals(target.imageRepository(), project.getImageRepository())) {
                throw new IllegalArgumentException("승인 후 프로젝트 이미지 저장소가 변경되었습니다.");
            }
            Map<String, Object> current = nexusMetadataService.resolveManifest(project, target.imageTag());
            String currentDigest = String.valueOf(current.getOrDefault("digest", ""));
            String currentPath = String.valueOf(current.getOrDefault("repositoryPath", ""));
            if (!target.repositoryPath().equals(currentPath)
                || !target.imageDigest().equalsIgnoreCase(currentDigest)) {
                throw new IllegalArgumentException("승인 후 Nexus 태그 또는 저장소 경로가 변경되었습니다.");
            }
            // 이 잠금은 배포 tryStart와 동일한 DB 행을 사용한다. 진행 중인 배포를 전역으로
            // 확인하고 원격 DELETE가 끝날 때까지 새 배포의 시작도 직렬화한다.
            assertNoInFlightDeployment();
            assertNotProtected(project, target.imageDigest());
            deleteAttempted = true;
            nexusMetadataService.deleteManifest(project, target.imageDigest());
            NexusMetadataService.TagDeleteResult tagResult = nexusMetadataService.deleteManifestTag(
                project, target.imageTag(), target.imageDigest());
            String completionMessage = switch (tagResult) {
                case DELETED -> "Nexus digest와 승인 태그를 삭제했습니다.";
                case ABSENT -> "Nexus digest를 삭제했으며 승인 태그도 존재하지 않습니다.";
                case MOVED -> "Nexus digest를 삭제했습니다. 태그는 새 digest로 이동해 보존했습니다.";
            };
            repository.finish(target.id(), NexusCleanupStatus.COMPLETED.name(), completionMessage);
        } catch (Exception ex) {
            NexusCleanupStatus outcome = deleteAttempted
                ? NexusCleanupStatus.RECONCILING : NexusCleanupStatus.FAILED;
            String message = outcome == NexusCleanupStatus.RECONCILING
                ? "삭제 결과를 확정하지 못했습니다. Nexus에서 승인 digest 존재 여부를 확인하세요. " + abbreviate(ex.getMessage(), 1500)
                : abbreviate(ex.getMessage(), 2000);
            repository.finish(target.id(), outcome.name(), message);
        }
        return require(target.id());
    }

    @Transactional
    public void reject(Long id, Long approverId, String reason) {
        NexusCleanupRequest request = require(id);
        if (request.getRequestedBy().getId().equals(approverId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요청자와 승인자는 달라야 합니다.");
        }
        String normalized = requireText(reason, 2000, "반려 사유를 입력하세요.");
        if (repository.reject(id, approverId, normalized) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
    }

    private void assertNotProtected(Project project, String digest) {
        boolean protectedDigest = imageHistoryRepository.findProtectedDigests(
                project.getImageRepository(), ImageHistoryRetentionPolicy.RECENT_ROLLBACK_HISTORY_LIMIT)
            .stream().anyMatch(value -> value != null && value.equalsIgnoreCase(digest));
        if (protectedDigest) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "현재 배포 또는 최근 복구 이력에 필요한 이미지는 정리할 수 없습니다.");
        }
    }

    private void assertNoInFlightDeployment(Long projectId) {
        List<DeployStatus> inFlight = java.util.Arrays.stream(DeployStatus.values())
            .filter(DeployStatus::isInFlight).toList();
        if (deployRequestRepository.countByStatusInForProjectIds(inFlight, List.of(projectId)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "진행 중인 배포 요청이 있어 Nexus 이미지 정리를 시작할 수 없습니다.");
        }
    }

    private void assertNoInFlightDeployment() {
        List<DeployStatus> inFlight = java.util.Arrays.stream(DeployStatus.values())
            .filter(DeployStatus::isInFlight).toList();
        if (deployRequestRepository.countByStatusIn(inFlight) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "공유 이미지 저장소의 안전을 위해 진행 중인 배포가 끝난 뒤 Nexus 이미지 정리를 승인하세요.");
        }
    }

    private record ApprovalTarget(Long id, Long projectId, String imageRepository,
                                  String repositoryPath, String imageTag, String imageDigest) {
    }

    private Project requireProject(Long id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
    }

    private NexusCleanupRequest require(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nexus 정리 요청을 찾을 수 없습니다."));
    }

    private String requireText(String value, int max, String message) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        if (normalized.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력값이 허용 길이를 초과했습니다.");
        }
        return normalized;
    }

    private void requireDigest(String digest) {
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nexus가 유효한 manifest digest를 반환하지 않았습니다.");
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) return "알 수 없는 오류";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
