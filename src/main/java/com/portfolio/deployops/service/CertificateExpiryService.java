package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적용된 TLS 인증서의 만료를 추적한다.
 *
 * <p>이 기능이 없을 때는 인증서가 만료되면 <b>장애로만</b> 알 수 있었다. 폐쇄망이라
 * 외부 모니터링이 붙지 않고, 갱신을 기억하는 것은 사람뿐이었다.
 *
 * <p>30/14/7일과 만료 후를 단계로 본다. 매일 돌지만 같은 단계에서 반복해 남기지 않는다
 * — 감사 로그가 같은 문구로 도배되면 정작 새로 넘어온 인증서를 못 본다. 더 급한
 * 단계로 내려갈 때만 다시 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateExpiryService {

    /** 급한 것부터. 0 은 이미 만료된 상태를 뜻한다. */
    private static final int[] THRESHOLD_DAYS = {0, 7, 14, 30};

    private final NginxChangeRequestRepository repository;
    private final AuditLogService auditLogService;

    /** 화면·API 가 쓰는 만료 임박 목록. 임계값 안에 든 것만 급한 순으로 준다. */
    @Transactional(readOnly = true)
    public List<ExpiringCertificate> findExpiring() {
        LocalDateTime now = LocalDateTime.now();
        return repository.findCurrentCertificates().stream()
            .map(request -> new ExpiringCertificate(request, daysLeft(request, now)))
            .filter(candidate -> thresholdFor(candidate.daysLeft()) != null)
            .toList();
    }

    @Scheduled(cron = "${app.nginx.certificate-expiry-cron:0 10 9 * * *}")
    @Transactional
    public void checkExpiringCertificates() {
        LocalDateTime now = LocalDateTime.now();
        for (NginxChangeRequest request : repository.findCurrentCertificates()) {
            long days = daysLeft(request, now);
            Integer threshold = thresholdFor(days);
            if (threshold == null) {
                continue;
            }
            Integer alreadyNotified = request.getCertificateExpiryNotifiedDays();
            // 같은 단계이거나 덜 급한 단계면 다시 남기지 않는다.
            if (alreadyNotified != null && alreadyNotified <= threshold) {
                continue;
            }
            record(request, days, threshold);
            repository.markExpiryNotified(request.getId(), threshold);
        }
    }

    private void record(NginxChangeRequest request, long days, int threshold) {
        String serverName = request.getServer() != null ? request.getServer().getName() : "(unknown)";
        String detail = "%s | %s | %s | 만료 %s".formatted(
            request.getPolicyName(),
            serverName,
            request.getCertificateSubject() == null ? "(subject 미상)" : request.getCertificateSubject(),
            days <= 0 ? "경과 " + Math.abs(days) + "일" : days + "일 남음");

        if (threshold == 0) {
            log.error("TLS 인증서가 만료되었습니다 — {}", detail);
        } else {
            log.warn("TLS 인증서 만료 임박({}일 이내) — {}", threshold, detail);
        }
        auditLogService.record(
            "SYSTEM",
            threshold == 0 ? "인증서 만료" : "인증서 만료 임박(" + threshold + "일)",
            "NginxChangeRequest",
            String.valueOf(request.getId()),
            detail,
            threshold == 0 ? AuditResult.FAIL : AuditResult.SUCCESS,
            null
        );
    }

    private long daysLeft(NginxChangeRequest request, LocalDateTime now) {
        // 화면의 "D-n"은 남은 24시간 묶음이 아니라 만료일과 오늘의 달력 날짜 차이다.
        // LocalDateTime끼리 계산하면 호출 사이의 몇 ms 때문에 정확히 10일 뒤도 9일로
        // 잘리는 등, 화면과 회귀 테스트가 실행 시각에 따라 흔들린다.
        return ChronoUnit.DAYS.between(
            now.toLocalDate(),
            request.getCertificateNotAfter().toLocalDate()
        );
    }

    /** 이 남은 일수가 걸리는 가장 급한 임계값. 어느 임계값에도 안 걸리면 null. */
    private Integer thresholdFor(long daysLeft) {
        for (int threshold : THRESHOLD_DAYS) {
            if (daysLeft <= threshold) {
                return threshold;
            }
        }
        return null;
    }

    public record ExpiringCertificate(NginxChangeRequest request, long daysLeft) {
        public boolean expired() {
            return daysLeft <= 0;
        }
    }
}
