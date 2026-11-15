package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.repository.SsoRepository;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 오피스 ↔ 배포시스템 링크의 <b>해제</b>를 지킨다.
 *
 * <p>왜 중요한가 — 배포 알림의 수신자는 {@code office_sso_link} 로 결정된다
 * ({@code findOfficeUserIdsByDeployUserIds} 가 {@code active = b'1'} 로 거른다).
 * 이 링크가 안 끊기면 <b>연동을 해제한 사용자에게 배포 알림이 계속 간다.</b>
 * 실제로 오피스만 끊기고 이쪽은 남는 상태였다.
 */
class SsoServiceTest {

    private static final String SECRET = "shared-secret-value";

    private final SsoRepository ssoRepository = mock(SsoRepository.class);
    private final SystemSettingService settingService = mock(SystemSettingService.class);
    private final UserService userService = mock(UserService.class);
    private final SsoService service = new SsoService(ssoRepository, settingService, userService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "sharedSecret", SECRET);
        // 설정값이 없으면 properties 기본값을 쓴다 — 실제 requireSecret 경로 그대로.
        when(settingService.get(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void revokeDeactivatesTheOfficeLinkSoNotificationsStop() {
        when(ssoRepository.deactivateOfficeLink(77L)).thenReturn(1);

        Map<String, Object> result = service.revokeLink(SECRET, 77L);

        verify(ssoRepository).deactivateOfficeLink(77L);
        assertThat(result).containsEntry("revoked", true);
    }

    /*
      오피스가 재시도할 수 있어야 양쪽이 결국 맞는다. 이미 끊긴 상태에서 다시 불러도
      실패로 답하면, 오피스는 롤백하고 링크가 살아 있는 채로 남는다.
    */
    @Test
    void revokeIsIdempotentWhenTheLinkIsAlreadyGone() {
        when(ssoRepository.deactivateOfficeLink(77L)).thenReturn(0);

        Map<String, Object> result = service.revokeLink(SECRET, 77L);

        assertThat(result).containsEntry("revoked", false);
    }

    @Test
    void revokeRejectsAWrongSharedSecret() {
        assertThatThrownBy(() -> service.revokeLink("wrong-secret", 77L))
            .isInstanceOf(IllegalArgumentException.class);

        verify(ssoRepository, never()).deactivateOfficeLink(anyLong());
    }

    @Test
    void revokeRejectsAMissingOfficeUserId() {
        assertThatThrownBy(() -> service.revokeLink(SECRET, null))
            .isInstanceOf(IllegalArgumentException.class);

        verify(ssoRepository, never()).deactivateOfficeLink(anyLong());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void consumeRejectsMissingTicketBeforeRepositoryAccess(String ticket) {
        assertThatThrownBy(() -> service.consumeTicket(ticket)).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(ssoRepository);
        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    @Test
    void consumeRejectsUnavailableTicketWithoutMarkingItUsed() {
        when(ssoRepository.findUserByValidTicket("unavailable")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeTicket("unavailable")).isInstanceOf(IllegalArgumentException.class);
        verify(ssoRepository, never()).markTicketUsed(anyString());
        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    @Test
    void consumeValidTicketPreservesUserAndMarksTrimmedTicketUsed() {
        User user = User.builder().id(7L).loginId("qa-user").password("encoded").role(UserRole.DEVELOPER).build();
        when(ssoRepository.findUserByValidTicket("valid")).thenReturn(Optional.of(user));
        when(ssoRepository.markTicketUsed("valid")).thenReturn(1);
        assertThat(service.consumeTicket(" valid ").getId()).isEqualTo(7L);
        verify(ssoRepository).markTicketUsed("valid");
        verify(userService).recordLogin(user);
    }

    @Test
    void timestampFailureDoesNotReturnAnAuthenticatedPrincipal() {
        User user = User.builder().id(7L).loginId("qa-user").role(UserRole.DEVELOPER).build();
        when(ssoRepository.findUserByValidTicket("valid")).thenReturn(Optional.of(user));
        when(ssoRepository.markTicketUsed("valid")).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("recording failed")).when(userService).recordLogin(user);
        assertThatThrownBy(() -> service.consumeTicket("valid")).isInstanceOf(IllegalStateException.class);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0", "2"})
    void nonSingleTicketClaimCannotRecordLoginOrReturnPrincipal(int affectedRows) {
        User user = User.builder().id(7L).loginId("qa-user").role(UserRole.DEVELOPER).build();
        when(ssoRepository.findUserByValidTicket("racing")).thenReturn(Optional.of(user));
        when(ssoRepository.markTicketUsed("racing")).thenReturn(affectedRows);
        assertThatThrownBy(() -> service.consumeTicket("racing")).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(userService);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void claimRechecksExpiryAlongWithUnusedStateAtTheDatabaseUpdate() throws Exception {
        String sql = SsoRepository.class.getMethod("markTicketUsed", String.class)
            .getAnnotation(org.apache.ibatis.annotations.Update.class).value()[0];
        assertThat(sql).isEqualTo("UPDATE sso_login_ticket SET used_at = NOW(6) WHERE ticket = #{ticket} AND used_at IS NULL AND expires_at > NOW(6)");
    }
}
