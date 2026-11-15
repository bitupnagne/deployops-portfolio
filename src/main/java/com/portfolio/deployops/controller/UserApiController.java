package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.UserForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserApiController {

    private final UserService userService;
    private final GitlabService gitlabService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<?> list() {
        List<Map<String, Object>> users = userService.findAll().stream()
            .filter(User::isActive)
            .map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "name", u.getName(),
                // 화면이 그대로 찍으므로 한글 라벨을 준다 — 승인자 고르는 목록에
                // DEVELOPER·APPROVER 가 그대로 보이고 있었다.
                "role", u.getRole().getLabel()
            ))
            .collect(Collectors.toList());
        return ApiResponse.ok(users);
    }

    @GetMapping("/gitlab-candidates")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> gitlabCandidates(@RequestParam(value = "search", required = false) String search) {
        String token = gitlabService.resolveAccessToken(null, "SUPER_ADMIN");
        if (token == null || token.isBlank()) {
            return ApiResponse.error("NO_TOKEN", "GitLab 시스템 토큰이 설정되어 있지 않습니다.");
        }
        try {
            return ApiResponse.ok(gitlabService.getUsers(token, null, search));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("GITLAB_AUTH", e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            return ApiResponse.error("GITLAB_ERROR", msg != null ? msg : "GitLab 사용자 조회에 실패했습니다.");
        }
    }

    /** GitLab 사용자 ↔ 배포시스템 사용자 매핑 점검: GitLab 사용자 목록에 매핑 상태를 표시. */
    @GetMapping("/gitlab-mapping")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> gitlabMapping(@RequestParam(value = "search", required = false) String search) {
        String token = gitlabService.resolveAccessToken(null, "SUPER_ADMIN");
        if (token == null || token.isBlank()) {
            return ApiResponse.error("NO_TOKEN", "GitLab 시스템 토큰이 설정되어 있지 않습니다.");
        }
        // gitlab_username(소문자) -> 배포시스템 사용자
        Map<String, User> mapped = userService.findAll().stream()
            .filter(u -> u.getGitlabUsername() != null && !u.getGitlabUsername().isBlank())
            .collect(Collectors.toMap(
                u -> u.getGitlabUsername().toLowerCase(),
                u -> u,
                (a, b) -> a));
        try {
            List<Map<String, Object>> rows = gitlabService.getUsers(token, null, search).stream()
                .map(g -> {
                    String uname = String.valueOf(g.getOrDefault("username", ""));
                    User m = mapped.get(uname.toLowerCase());
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("gitlabUsername", uname);
                    row.put("gitlabName", g.getOrDefault("name", ""));
                    row.put("gitlabState", g.getOrDefault("state", ""));
                    row.put("mapped", m != null);
                    row.put("mappedUserId", m != null ? m.getId() : null);
                    row.put("mappedUserName", m != null ? m.getName() : null);
                    return row;
                })
                .collect(Collectors.toList());
            return ApiResponse.ok(rows);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("GITLAB_AUTH", e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            return ApiResponse.error("GITLAB_ERROR", msg != null ? msg : "GitLab 사용자 조회에 실패했습니다.");
        }
    }

    /** 매핑 운영 UI 전용 — 특정 배포시스템 사용자에 gitlab_username 설정/해제. */
    @PostMapping("/{id}/gitlab-username")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> setGitlabUsername(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String username = body.getOrDefault("gitlabUsername", "");
        User user = userService.assignGitlabUsername(id, username);
        auditLogService.recordCurrentRequired("GitLab 사용자 매핑 변경", "User", String.valueOf(id),
            "gitlabUsername=" + (user.getGitlabUsername() == null ? "" : user.getGitlabUsername()),
            AuditResult.SUCCESS);
        return ApiResponse.ok("GitLab 사용자명이 갱신되었습니다.", Map.of(
            "id", user.getId(),
            "gitlabUsername", user.getGitlabUsername() == null ? "" : user.getGitlabUsername()
        ));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@RequestBody UserForm form) {
        if (form.getPassword() == null || form.getPassword().isBlank()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", "초기 비밀번호를 입력해 주세요."));
        }
        try {
            User user = userService.create(form);
            auditLogService.recordCurrentRequired("사용자 등록", "User", String.valueOf(user.getId()),
                describe(user), AuditResult.SUCCESS);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("사용자가 추가되었습니다.", Map.of(
                    "id", user.getId(), "name", user.getName()
                )));
        } catch (IllegalArgumentException e) {
            // Roll back the joined service transaction before MVC renders the validation response.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * 사용자 수정 — <b>역할 변경을 before → after 로 남긴다.</b>
     *
     * <p>이 시스템에서 가장 무거운 관리 동작이다. 역할이 곧 배포 실행 권한이라
     * "누가 누구에게 무슨 권한을 줬는가"가 남지 않으면 뒤이은 배포 기록만으로는
     * 그 배포가 정당했는지 판단할 수 없다. 그래서 이름만이 아니라 역할의 전후를 적는다.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody UserForm form) {
        String before = roleOf(userService.findById(id));
        User user = userService.update(id, form);
        String after = roleOf(user);

        String desc = describe(user);
        if (!before.equals(after)) {
            desc += " — 역할 " + before + " → " + after;
        }
        auditLogService.recordCurrentRequired(
            before.equals(after) ? "사용자 수정" : "사용자 역할 변경",
            "User", String.valueOf(id), desc, AuditResult.SUCCESS);
        return ApiResponse.ok("사용자 정보가 수정되었습니다.", Map.of(
            "id", user.getId(), "name", user.getName()
        ));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> resetPassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal SecurityUserDetails currentUser) {
        if (currentUser == null || !"SUPER_ADMIN".equals(currentUser.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", "관리자만 비밀번호를 재설정할 수 있습니다."));
        }
        if (id.equals(currentUser.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("SELF_RESET_FORBIDDEN", "본인 비밀번호는 내 정보에서 변경해 주세요."));
        }
        String temporaryPassword = body == null ? null : body.get("temporaryPassword");
        User user;
        try {
            user = userService.resetPassword(id, temporaryPassword);
        } catch (IllegalArgumentException e) {
            // The joined service transaction is already rollback-only. Propagate an HTTP
            // validation exception so this transaction rolls back before MVC renders 400.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        auditLogService.recordCurrentRequired("사용자 비밀번호 재설정", "User", String.valueOf(id),
            describe(user), AuditResult.SUCCESS);
        return ResponseEntity.ok(ApiResponse.ok("임시 비밀번호가 설정되었습니다. 다음 로그인 시 변경해야 합니다.", null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> deactivate(@PathVariable Long id) {
        String desc = describe(userService.findById(id));
        userService.deactivate(id);
        auditLogService.recordCurrentRequired("사용자 비활성화", "User", String.valueOf(id),
            desc, AuditResult.SUCCESS);
        return ApiResponse.ok("사용자가 비활성화되었습니다.", null);
    }

    /** 감사로그용 사용자 식별 문구 — 표시 이름은 겹칠 수 있어 로그인 ID 를 함께 적는다. */
    private String describe(User u) {
        return u.getName() + "(" + u.getLoginId() + ", " + roleOf(u) + ")";
    }

    private String roleOf(User u) {
        return u == null || u.getRole() == null ? "-" : u.getRole().name();
    }

    @PostMapping("/{id}/test-gitlab-token")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> testGitlabToken(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String token = body.getOrDefault("token", "").trim();
        if (token.isBlank()) {
            return ApiResponse.error("EMPTY_TOKEN", "토큰을 입력해 주세요.");
        }
        try {
            Map<String, Object> gitlabUser = gitlabService.testConnection(token, null);
            return ApiResponse.ok(Map.of(
                "message", "연결 성공 - " + gitlabUser.get("name") + " (" + gitlabUser.get("username") + ")",
                "username", gitlabUser.get("username"),
                "name", gitlabUser.get("name")
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("GITLAB_AUTH", e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            return ApiResponse.error("GITLAB_ERROR", msg != null ? msg : "연결 실패");
        }
    }
}
