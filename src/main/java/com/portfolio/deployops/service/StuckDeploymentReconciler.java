package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.repository.DeployRequestRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 "진행 중"으로 멈춰 있는 배포를 실패로 정리한다.
 *
 * <p>배포 실행은 {@code @Async} 스레드에서 돌아가고 진행 상태는 DB 에만 있다. 그 스레드가 살아 있는
 * 동안 앱이 죽으면(재시작·OOM·전원) 요청은 {@code *_DEPLOYING} 인 채로 남는데, 그 상태를 되살릴
 * 스레드는 이미 없다. 실행 관문이 "준비 상태"를 요구하므로 재실행도 막혀서, 사람이 수동 완료 처리를
 * 하기 전까지 그 건은 화면에서 영원히 돌아가는 것처럼 보인다.
 *
 * <p>부팅 시점에 남아 있는 진행 중 건은 정의상 전부 유실된 것이므로 실패로 내려 두고,
 * 무슨 일이 있었는지 로그에 남긴다. 운영자는 실패한 건을 다시 요청하면 된다.
 *
 * <p>이 클래스는 <b>단일 인스턴스 운영</b>을 전제로 한다. 배포시스템을 2대 이상으로 늘리면
 * 한쪽이 기동할 때 다른 쪽이 정상 수행 중인 배포까지 실패로 내리므로, 그때는 실행 소유자
 * (인스턴스 ID)와 하트비트를 도입해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "false", matchIfMissing = true)
public class StuckDeploymentReconciler {

    private static final Map<DeployStatus, DeployStatus> STUCK_TO_FAILED = Map.of(
        DeployStatus.TEST_BUILDING, DeployStatus.TEST_FAILED,
        DeployStatus.TEST_DEPLOYING, DeployStatus.TEST_FAILED,
        DeployStatus.PROD_DEPLOYING, DeployStatus.PROD_FAILED
    );

    private static final String RESTART_FAILURE_REASON =
        "배포시스템이 재시작되어 진행 중인 실행이 중단되었습니다. 대상 서버 상태를 확인한 뒤 "
            + "실패 대상 재시도 가능 여부를 확인하세요. 빌드 단계에서 중단된 요청은 새 TEST 요청으로 처리해 주세요.";

    private final DeployRequestRepository deployRequestRepository;
    private final DeployRequestService deployRequestService;
    private final AuditLogService auditLogService;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        List<DeployRequest> stuck =
            deployRequestRepository.findByStatusIn(List.copyOf(STUCK_TO_FAILED.keySet()));
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("기동 시점에 진행 중으로 남아 있는 배포 {}건을 실패로 정리한다.", stuck.size());
        for (DeployRequest request : stuck) {
            DeployStatus from = request.getStatus();
            DeployStatus to = STUCK_TO_FAILED.get(from);
            try {
                // 조건부 전이라 혹시 다른 경로가 먼저 정리했으면 건드리지 않는다.
                if (!deployRequestService.tryTransitionToFailure(request.getId(), from, to, RESTART_FAILURE_REASON)) {
                    continue;
                }
                log.warn("배포 {} 상태를 {} → {} 로 정리했다(앱 재시작으로 실행 스레드 유실).",
                    request.getReqNo(), from, to);
                auditLogService.record(
                    "SYSTEM",
                    "중단된 배포 자동 정리",
                    "DeployRequest",
                    String.valueOf(request.getId()),
                    request.getReqNo() + " / " + from + " → " + to + " (앱 재시작으로 실행 유실)",
                    AuditResult.FAIL,
                    "127.0.0.1"
                );
            } catch (Exception e) {
                log.error("중단된 배포 {} 정리에 실패했습니다. 다른 요청의 복구와 서비스 기동은 계속합니다.",
                    request.getReqNo(), e);
            }
        }
    }
}
