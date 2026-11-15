package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.UserService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileApiController {

    private final UserService userService;
    private final GitlabService gitlabService;

    @PostMapping("/password")
    public ApiResponse<?> changePassword(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal SecurityUserDetails currentUser) {

        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            return ApiResponse.error("MISSING_FIELD", "필수 항목이 누락되었습니다.");
        }
        User changed;
        try {
            changed = userService.changePassword(currentUser.getId(), currentPassword, newPassword);
        } catch (BadCredentialsException e) {
            userService.recordPasswordChangeFailure(currentUser.getUser(), "WRONG_CURRENT_PASSWORD");
            return ApiResponse.error("WRONG_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
        } catch (IllegalArgumentException e) {
            userService.recordPasswordChangeFailure(currentUser.getUser(), "PASSWORD_POLICY_REJECTED");
            return ApiResponse.error("INVALID_PASSWORD", e.getMessage());
        }
        // W-05: 세션에 올라간 사용자 사본도 동기화 — 강제변경 인터셉터가 계속 리다이렉트하지 않게.
        if (currentUser.getUser() != null) {
            currentUser.getUser().setForcePasswordChange(false);
            currentUser.getUser().setPassword(changed.getPassword());
            currentUser.getUser().setPasswordChangedAt(changed.getPasswordChangedAt());
        }
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }

    @PostMapping("/gitlab-token")
    public ApiResponse<?> saveGitlabToken(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal SecurityUserDetails currentUser) {

        String token = body.getOrDefault("token", "").trim();
        boolean testOnly = Boolean.parseBoolean(body.getOrDefault("testOnly", "false"));

        if (token.isBlank()) {
            User saved = userService.saveGitlabToken(currentUser.getId(), null, null);
            syncSessionGitlabUser(currentUser, saved);
            return ApiResponse.ok("GitLab 계정 연동이 해제되었습니다.");
        }

        try {
            Map<String, Object> gitlabUser = gitlabService.testConnection(token, null);
            String username = (String) gitlabUser.get("username");
            String name = (String) gitlabUser.get("name");

            if (!testOnly) {
                User saved = userService.saveGitlabToken(currentUser.getId(), token, username);
                syncSessionGitlabUser(currentUser, saved);
            }
            return ApiResponse.ok(Map.of(
                "message", "GitLab 연결 성공 - " + name + " (" + username + ")",
                "username", username,
                "name", name
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("GITLAB_AUTH", e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("GitLab URL")) {
                return ApiResponse.error("NO_GITLAB_URL", msg);
            }
            return ApiResponse.error("GITLAB_ERROR", "연결 실패: " + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    private void syncSessionGitlabUser(SecurityUserDetails currentUser, User saved) {
        if (currentUser == null || currentUser.getUser() == null || saved == null) {
            return;
        }
        currentUser.getUser().setGitlabToken(saved.getGitlabToken());
        currentUser.getUser().setGitlabUsername(saved.getGitlabUsername());
    }
}
