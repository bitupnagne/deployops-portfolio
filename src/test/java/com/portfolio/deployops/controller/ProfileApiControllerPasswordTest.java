package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.UserService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileApiControllerPasswordTest {

    @Test
    void ownPasswordChangeUpdatesSessionHashAfterPersistedChange() {
        UserService userService = org.mockito.Mockito.mock(UserService.class);
        GitlabService gitlab = org.mockito.Mockito.mock(GitlabService.class);
        User principalUser = User.builder().id(3L).loginId("approver")
            .role(UserRole.APPROVER).password("old-hash")
            .forcePasswordChange(true).active(true).build();
        SecurityUserDetails principal = new SecurityUserDetails(principalUser);
        User saved = User.builder().id(3L).password("new-hash")
            .forcePasswordChange(false).active(true).build();
        when(userService.changePassword(3L, "old-password", "Unique!Pass9")).thenReturn(saved);

        var response = new ProfileApiController(userService, gitlab).changePassword(
            Map.of("currentPassword", "old-password", "newPassword", "Unique!Pass9"), principal);

        assertThat(response.isSuccess()).isTrue();
        assertThat(principal.getPassword()).isEqualTo("new-hash");
        assertThat(principal.getUser().isForcePasswordChange()).isFalse();
        verify(userService).changePassword(3L, "old-password", "Unique!Pass9");
    }

    @Test
    void persistedPasswordRejectionLeavesStaleSessionUnchanged() {
        UserService service = org.mockito.Mockito.mock(UserService.class);
        GitlabService gitlab = org.mockito.Mockito.mock(GitlabService.class);
        SecurityUserDetails principal = new SecurityUserDetails(User.builder().id(3L)
            .role(UserRole.APPROVER).password("old-session-hash").forcePasswordChange(true).build());
        when(service.changePassword(3L, "Old!Pass9", "New!Pass10"))
            .thenThrow(new org.springframework.security.authentication.BadCredentialsException("stale"));

        var response = new ProfileApiController(service, gitlab).changePassword(
            Map.of("currentPassword", "Old!Pass9", "newPassword", "New!Pass10"), principal);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("WRONG_PASSWORD");
        assertThat(principal.getPassword()).isEqualTo("old-session-hash");
        assertThat(principal.getUser().isForcePasswordChange()).isTrue();
    }
}
