package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.portfolio.deployops.service.OfficeNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** 알림 조회·복구는 최고 관리자만 서비스에 도달하며 실제 전송은 실행하지 않는다. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OfficeNotificationOutboxAuthorizationTest.Config.class)
class OfficeNotificationOutboxAuthorizationTest {
    @Autowired private OfficeNotificationOutboxApiController controller;
    @Autowired private OfficeNotificationService notifications;

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean OfficeNotificationService notifications() { return mock(OfficeNotificationService.class); }
        @Bean OfficeNotificationOutboxApiController controller(OfficeNotificationService notifications) {
            return new OfficeNotificationOutboxApiController(notifications);
        }
    }

    @BeforeEach void resetCalls() { clearInvocations(notifications); }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void superAdminCanReadAndRequestRecoveryWithoutActualTransmission() {
        assertThat(controller.list("DEAD_LETTER", 0, 30).isSuccess()).isTrue();
        assertThat(controller.retry(44L).isSuccess()).isFalse();
        verify(notifications).getOutboxSnapshot("DEAD_LETTER", 0, 30);
        verify(notifications).retryDeadLetter(44L);
    }

    private void deniedBeforeService() {
        assertThatThrownBy(() -> controller.list("DEAD_LETTER", 0, 30)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.retry(44L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(notifications);
    }

    @Test @WithMockUser(roles = "DEVELOPER") void developerCannotReadOrRetry() { deniedBeforeService(); }
    @Test @WithMockUser(roles = "PROJECT_MANAGER") void projectManagerCannotReadOrRetry() { deniedBeforeService(); }
    @Test @WithMockUser(roles = "APPROVER") void approverCannotReadOrRetry() { deniedBeforeService(); }
    @Test @WithMockUser(roles = "OPERATOR") void operatorCannotReadOrRetry() { deniedBeforeService(); }
    @Test @WithAnonymousUser void anonymousCannotReadOrRetry() { deniedBeforeService(); }
}
