package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 인증서 만료 추적. 폐쇄망이라 외부 모니터링이 없어, 이 점검이 유일한 사전 경고다.
 *
 * <p>가장 중요한 성질은 <b>같은 단계에서 반복해 남기지 않는 것</b>이다. 매일 도는
 * 점검이 같은 문구로 감사로그를 채우면 정작 새로 임계에 들어온 인증서를 못 본다.
 */
class CertificateExpiryServiceTest {

    private final NginxChangeRequestRepository repository = mock(NginxChangeRequestRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final CertificateExpiryService service = new CertificateExpiryService(repository, auditLogService);

    @Test
    void recordsTheMostUrgentThresholdTheCertificateHasCrossed() {
        NginxChangeRequest cert = cert(1L, LocalDateTime.now().plusDays(5), null);
        when(repository.findCurrentCertificates()).thenReturn(List.of(cert));

        service.checkExpiringCertificates();

        // 5일 남았으면 30/14 가 아니라 7일 단계다.
        verify(repository).markExpiryNotified(1L, 7);
    }

    @Test
    void doesNotRepeatTheSameThresholdOnEveryDailyRun() {
        NginxChangeRequest cert = cert(1L, LocalDateTime.now().plusDays(20), 30);
        when(repository.findCurrentCertificates()).thenReturn(List.of(cert));

        service.checkExpiringCertificates();

        verify(repository, never()).markExpiryNotified(any(), any());
        verify(auditLogService, never()).record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    /* 30일에 알렸어도 7일로 내려오면 다시 알려야 한다 — 단계가 급해진 것은 새 소식이다. */
    @Test
    void notifiesAgainWhenTheCertificateMovesToAMoreUrgentThreshold() {
        NginxChangeRequest cert = cert(1L, LocalDateTime.now().plusDays(3), 30);
        when(repository.findCurrentCertificates()).thenReturn(List.of(cert));

        service.checkExpiringCertificates();

        verify(repository).markExpiryNotified(1L, 7);
    }

    @Test
    void treatsAnAlreadyExpiredCertificateAsItsOwnStage() {
        NginxChangeRequest cert = cert(1L, LocalDateTime.now().minusDays(2), 7);
        when(repository.findCurrentCertificates()).thenReturn(List.of(cert));

        service.checkExpiringCertificates();

        verify(repository).markExpiryNotified(1L, 0);
    }

    @Test
    void ignoresCertificatesThatAreStillFarFromExpiry() {
        NginxChangeRequest cert = cert(1L, LocalDateTime.now().plusDays(90), null);
        when(repository.findCurrentCertificates()).thenReturn(List.of(cert));

        service.checkExpiringCertificates();

        verify(repository, never()).markExpiryNotified(any(), any());
        assertThat(service.findExpiring()).isEmpty();
    }

    @Test
    void expiringListCarriesTheRemainingDaysForTheScreen() {
        when(repository.findCurrentCertificates())
            .thenReturn(List.of(cert(1L, LocalDateTime.now().plusDays(10), null)));

        var expiring = service.findExpiring();

        assertThat(expiring).hasSize(1);
        assertThat(expiring.get(0).daysLeft()).isEqualTo(10);
        assertThat(expiring.get(0).expired()).isFalse();
    }

    private NginxChangeRequest cert(Long id, LocalDateTime notAfter, Integer notifiedDays) {
        return NginxChangeRequest.builder()
            .id(id)
            .status(NginxChangeStatus.APPLIED)
            .policyName("ha-tls")
            .server(Server.builder().id(9L).name("prdweb1").build())
            .certificateSubject("CN=web.example.local")
            .certificateNotAfter(notAfter)
            .certificateExpiryNotifiedDays(notifiedDays)
            .build();
    }
}
