package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.RemoteCommandLog;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteCommandLogService {

    private final RemoteCommandLogRepository repository;
    private final SecretMaskingService secretMaskingService;
    private final com.portfolio.deployops.repository.ExecutionGuardRepository executionGuardRepository;

    public RemoteCommandLogService(RemoteCommandLogRepository repository, SecretMaskingService secretMaskingService) {
        this(repository, secretMaskingService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteCommandLogService(RemoteCommandLogRepository repository, SecretMaskingService secretMaskingService,
        com.portfolio.deployops.repository.ExecutionGuardRepository executionGuardRepository) {
        this.repository = repository;
        this.secretMaskingService = secretMaskingService;
        this.executionGuardRepository = executionGuardRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long beginTargetAttempt(DeployRequest request, Server server, String artifactRef) {
        if (executionGuardRepository == null || executionGuardRepository.lockSingleton() == null) {
            throw new RemoteExecutionException("배포 시도 기록 잠금을 확보하지 못했습니다. 원격 작업을 실행하지 않았습니다.");
        }
        if (request.getPlanSnapshotHash() == null || !request.getPlanSnapshotHash().matches("^[a-fA-F0-9]{64}$")) {
            throw new RemoteExecutionException("고정된 배포 계획을 확인할 수 없어 원격 작업을 실행하지 않았습니다.");
        }
        String artifactHash;
        try {
            artifactHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(artifactRef.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        var attempt = com.portfolio.deployops.domain.TargetDeploymentAttempt.builder()
            .deployRequestId(request.getId()).serverId(server.getId())
            .planHash(request.getPlanSnapshotHash()).artifactHash(artifactHash).build();
        if (repository.insertTargetAttempt(attempt) != 1 || attempt.getId() == null) {
            throw new RemoteExecutionException("배포 시도 기록을 저장하지 못했습니다. 원격 작업을 실행하지 않았습니다.");
        }
        return attempt.getId();
    }

    /** 한 번에 넘겨 볼 수 있는 최대 기록 수. AI 프롬프트 길이와 화면 응답성을 같이 고려한 값. */
    public static final int MAX_RECENT = 50;

    @Transactional(readOnly = true)
    public List<Long> latestSuccessfulServerIds(Long requestId) {
        return repository.findLatestSuccessfulServerIds(requestId);
    }

    /**
     * 프로젝트의 최근 원격 명령 기록.
     *
     * <p>화면의 "배포 로그" 패널과 AI 분석이 <b>같은 메서드</b>를 쓴다.
     * 둘이 다른 경로로 데이터를 가져오면, 사용자가 보는 것과 AI 가 보는 것이 어긋난다.
     */
    @Transactional(readOnly = true)
    public List<RemoteCommandLog> findRecentByProject(Long projectId, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RECENT));
        return repository.findRecentByProjectId(projectId, capped);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(DeployRequest request, Server server, RemoteCommandResult result) {
        persistResult(request, server, result, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAttemptResult(DeployRequest request, Server server, RemoteCommandResult result, Long attemptId) {
        persistResult(request, server, result, attemptId);
    }

    private void persistResult(DeployRequest request, Server server, RemoteCommandResult result, Long attemptId) {
        if (request == null || server == null || result == null) {
            return;
        }
        repository.insert(RemoteCommandLog.builder()
            .deployRequestId(request.getId())
            .targetAttemptId(attemptId)
            .serverId(server.getId())
            .serverName(server.getName())
            .commandDisplay(secretMaskingService.mask(result.commandDisplay()))
            .stdoutText(secretMaskingService.mask(result.stdout()))
            .stderrText(secretMaskingService.mask(result.stderr()))
            .exitCode(result.exitCode())
            .success(result.exitCode() == 0)
            .startedAt(result.startedAt())
            .finishedAt(result.finishedAt())
            .durationMs(result.durationMs())
            .build());
    }
}
