package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.OfficeNotificationService;
import org.junit.jupiter.api.Test;

class OfficeNotificationOutboxApiControllerTest {

    private final OfficeNotificationService officeNotificationService = mock(OfficeNotificationService.class);
    private final OfficeNotificationOutboxApiController controller = new OfficeNotificationOutboxApiController(officeNotificationService);

    @Test
    void retryReportsQueueAcceptanceRatherThanSuccessfulDelivery() {
        when(officeNotificationService.retryDeadLetter(44L)).thenReturn(true);

        ApiResponse<?> response = controller.retry(44L);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("Office 알림 재전송을 접수했습니다. 다음 전송 주기에 처리됩니다.");
        verify(officeNotificationService).retryDeadLetter(44L);
    }

    @Test
    void retryRejectsNonDeadLetterWithoutWritingAuditLog() {
        when(officeNotificationService.retryDeadLetter(44L)).thenReturn(false);

        ApiResponse<?> response = controller.retry(44L);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("OUTBOX_RETRY_NOT_AVAILABLE");
        verify(officeNotificationService).retryDeadLetter(44L);
    }
}
