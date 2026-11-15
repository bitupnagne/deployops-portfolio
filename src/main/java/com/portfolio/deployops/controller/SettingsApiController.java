package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.AiChatService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.SecretMigrationService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.SystemSettingService;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsApiController {

    private final SystemSettingService settingService;
    private final SecretMigrationService secretMigrationService;
    private final AiChatService aiChatService;
    private final AuditLogService auditLogService;
    private final ExecutionPolicyService executionPolicyService;

    /** AI 설정 화면의 실제 연결 테스트. 저장 전 폼 값(provider/base-url/model/api-key)으로 모델에 핑을 보낸다. */
    @PostMapping("/ai/test")
    public ApiResponse<?> testAi(@RequestBody Map<String, String> body) {
        AiChatService.TestResult result = aiChatService.test(aiChatService.configFromForm(body));
        if (result.success()) {
            return ApiResponse.ok(result.message() + " · " + result.latencyMs() + "ms");
        }
        return ApiResponse.error("AI_TEST_FAIL", result.message());
    }

    @GetMapping("/{category}")
    public ApiResponse<?> get(@PathVariable String category) {
        return ApiResponse.ok(settingService.getCategoryForClient(category));
    }

    /**
     * 시스템 설정 저장 — 감사로그에는 <b>키 이름만</b> 남긴다.
     *
     * <p>이 경로로 GitLab 토큰·SSO 시크릿·AI API 키가 들어온다. 값을 남기면 감사로그 자체가
     * 시크릿 저장소가 된다. {@code AuditLogService} 가 마스킹을 하긴 하지만 그건 형식을 아는
     * 값에만 걸리는 그물이라, 애초에 값을 넘기지 않는 쪽이 맞다.
     *
     * <p>"무엇이 바뀌었나"는 키 이름으로 충분하다 — 어느 설정이 언제 누구 손에 바뀌었는지가
     * 감사에서 묻는 것이고, 바뀐 값 자체는 화면에서 확인할 일이다.
     */
    @PostMapping("/{category}")
    @Transactional
    public ApiResponse<?> save(@PathVariable String category, @RequestBody Map<String, String> values) {
        if ("exec-policy".equals(category)) {
            executionPolicyService.validateSettings(values);
        }
        settingService.saveAll(category, values);
        String keys = values == null || values.isEmpty()
            ? "(없음)"
            : values.keySet().stream().sorted().collect(Collectors.joining(", "));
        auditLogService.recordCurrentRequired("시스템 설정 변경", "SystemSetting", category,
            "변경 항목: " + keys, AuditResult.SUCCESS);
        return ApiResponse.ok("설정이 저장되었습니다.");
    }

    @GetMapping("/secrets/plain-status")
    public ApiResponse<?> inspectPlainSecrets() {
        return ApiResponse.ok(secretMigrationService.inspect());
    }

    @PostMapping("/secrets/migrate-plain")
    @Transactional
    public ApiResponse<?> migratePlainSecrets() {
        try {
            Object result = secretMigrationService.migratePlainSecrets();
            auditLogService.recordCurrentRequired("평문 시크릿 암호화", "Secret", "-",
                "migrate-plain 실행", AuditResult.SUCCESS);
            return ApiResponse.ok("평문 민감 정보 암호화가 완료되었습니다.", result);
        } catch (IllegalStateException e) {
            auditLogService.recordCurrentRequired("평문 시크릿 암호화", "Secret", "-",
                "실패: " + e.getMessage(), AuditResult.FAIL);
            return ApiResponse.error("SECRET_KEY_REQUIRED", e.getMessage());
        }
    }

    @GetMapping("/secrets/rotation-status")
    public ApiResponse<?> inspectRotationStatus() {
        return ApiResponse.ok(secretMigrationService.inspectRotation());
    }

    @PostMapping("/secrets/rotate-key")
    @Transactional
    public ApiResponse<?> rotateSecretsToCurrentKey() {
        try {
            Object result = secretMigrationService.rotateToCurrentKey();
            auditLogService.recordCurrentRequired("시크릿 키 로테이션", "Secret", "-",
                "rotate-key 실행", AuditResult.SUCCESS);
            return ApiResponse.ok("민감 정보 key rotation이 완료되었습니다.", result);
        } catch (IllegalStateException e) {
            auditLogService.recordCurrentRequired("시크릿 키 로테이션", "Secret", "-",
                "실패: " + e.getMessage(), AuditResult.FAIL);
            return ApiResponse.error("SECRET_KEY_REQUIRED", e.getMessage());
        }
    }
}
