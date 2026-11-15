package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * GitLab 인바운드 웹훅 처리.
 * Merge Request 가 열릴 때 요청자(GitLab 사용자)가 배포시스템에 매핑(gitlab_username)되어 있지 않으면
 * 해당 MR 을 자동으로 close 하고 안내 코멘트를 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitlabWebhookService {

    private final SystemSettingService settingService;
    private final GitlabService gitlabService;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    public enum Outcome { IGNORED, ALLOWED, AUTO_CLOSED }

    /** 웹훅 시크릿(X-Gitlab-Token)을 상수시간 비교로 검증한다. 시크릿 미설정 시 거부. */
    public boolean verifySecret(String provided) {
        String expected = settingService.get("gitlab.webhook.secret", "");
        if (!StringUtils.hasText(expected) || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.trim().getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public Outcome handle(Map<String, Object> payload) {
        if (payload == null) {
            return Outcome.IGNORED;
        }
        String objectKind = asString(payload.get("object_kind"));
        if (!"merge_request".equals(objectKind)) {
            return Outcome.IGNORED;
        }

        Map<String, Object> attributes = asMap(payload.get("object_attributes"));
        String action = asString(attributes.get("action")).toLowerCase(Locale.ROOT);
        // 새로 열리거나 다시 열린 MR 만 검사 대상으로 한다.
        if (!"open".equals(action) && !"reopen".equals(action)) {
            return Outcome.IGNORED;
        }

        Long gitlabProjectId = firstLong(
            attributes.get("target_project_id"),
            asMap(payload.get("project")).get("id"));
        Long mergeRequestIid = firstLong(attributes.get("iid"));
        String username = asString(asMap(payload.get("user")).get("username"));

        if (gitlabProjectId == null || mergeRequestIid == null) {
            log.warn("[gitlab-webhook] MR payload missing project/iid — ignored");
            return Outcome.IGNORED;
        }

        Project project = projectRepository.findByGitlabProjectId(gitlabProjectId).orElse(null);
        if (project == null) {
            // 배포시스템이 관리하지 않는 프로젝트는 관여하지 않는다.
            log.info("[gitlab-webhook] unmanaged gitlab project {} — ignored", gitlabProjectId);
            return Outcome.IGNORED;
        }

        boolean mapped = StringUtils.hasText(username)
            && userRepository.findByGitlabUsername(username).isPresent();
        if (mapped) {
            return Outcome.ALLOWED;
        }

        // 미등록 GitLab 사용자의 MR — 자동 close.
        String systemToken = settingService.get("gitlab.token", "").trim();
        if (!StringUtils.hasText(systemToken)) {
            log.warn("[gitlab-webhook] unmapped user '{}' on MR !{} (project {}), but no system token to auto-close",
                username, mergeRequestIid, project.getName());
            return Outcome.IGNORED;
        }

        String displayUser = StringUtils.hasText(username) ? "@" + username : "(알 수 없음)";
        String note = "🚫 배포시스템에 등록되지 않은 GitLab 사용자 " + displayUser
            + " 님의 요청이라 자동으로 종료되었습니다.\n\n"
            + "배포시스템 관리자에게 계정 연동(프로필의 GitLab 사용자명 등록)을 요청한 뒤 다시 시도해 주세요.";
        try {
            gitlabService.postMergeRequestNote(systemToken, null, gitlabProjectId, mergeRequestIid, note);
        } catch (Exception e) {
            log.warn("[gitlab-webhook] failed to post note on MR !{}: {}", mergeRequestIid, e.getMessage());
        }
        try {
            gitlabService.closeMergeRequest(systemToken, null, gitlabProjectId, mergeRequestIid);
            log.info("[gitlab-webhook] auto-closed MR !{} (project {}) from unmapped user '{}'",
                mergeRequestIid, project.getName(), username);
            return Outcome.AUTO_CLOSED;
        } catch (Exception e) {
            log.warn("[gitlab-webhook] failed to auto-close MR !{}: {}", mergeRequestIid, e.getMessage());
            return Outcome.IGNORED;
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Long firstLong(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                    // 다음 후보로
                }
            }
        }
        return null;
    }
}
