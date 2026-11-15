package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.ManualServerExecution;
import com.portfolio.deployops.repository.ManualServerExecutionRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManualServerExecutionService {

    private final ManualServerExecutionRepository repository;

    @Transactional
    public void acquire(Long requestId, Long serverId, Long userId) {
        if (repository.retryFailed(requestId, serverId, userId) == 1) {
            return;
        }
        if (repository.insertRunning(requestId, serverId, userId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 서버는 이미 실행 중이거나 배포가 완료되었습니다.");
        }
    }

    public void succeed(Long requestId, Long serverId) {
        repository.finish(requestId, serverId, "SUCCESS", null);
    }

    public void fail(Long requestId, Long serverId, String message) {
        repository.finish(requestId, serverId, "FAILED", abbreviate(message));
    }

    public Map<Long, ManualServerExecution> statuses(Long requestId) {
        return repository.findByRequestId(requestId).stream()
            .collect(Collectors.toMap(ManualServerExecution::getServerId, Function.identity()));
    }

    public boolean allSucceeded(Long requestId, List<Long> targetIds) {
        Map<Long, ManualServerExecution> states = statuses(requestId);
        return !targetIds.isEmpty() && targetIds.stream().allMatch(id -> {
            ManualServerExecution state = states.get(id);
            return state != null && "SUCCESS".equals(state.getStatus());
        });
    }

    private String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
