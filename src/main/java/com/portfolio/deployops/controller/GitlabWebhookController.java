package com.portfolio.deployops.controller;

import com.portfolio.deployops.service.GitlabWebhookService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitLab 인바운드 웹훅 수신부.
 * GitLab 프로젝트 설정의 Webhook URL 로 {@code /api/v1/gitlab/webhook} 를 등록하고,
 * Secret token 에 배포시스템 설정 {@code gitlab.webhook.secret} 와 동일한 값을 넣는다.
 * Merge request events 만 구독하면 된다.
 */
@RestController
@RequestMapping("/api/v1/gitlab/webhook")
@RequiredArgsConstructor
public class GitlabWebhookController {

    private final GitlabWebhookService gitlabWebhookService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
        @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
        @RequestBody(required = false) Map<String, Object> payload
    ) {
        if (!gitlabWebhookService.verifySecret(token)) {
            return ResponseEntity.status(401).body(Map.of("status", "unauthorized"));
        }
        GitlabWebhookService.Outcome outcome = gitlabWebhookService.handle(payload);
        return ResponseEntity.ok(Map.of("status", "ok", "outcome", outcome.name()));
    }
}
