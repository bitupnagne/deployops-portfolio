package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.GitlabCiVariableDeleteForm;
import com.portfolio.deployops.dto.GitlabCiVariableForm;
import com.portfolio.deployops.service.GitlabCiVariableService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gitlab/deploy-projects/{deployProjectId}/ci-variables")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class GitlabCiVariableApiController {
    private final GitlabCiVariableService service;

    @GetMapping
    public ApiResponse<?> list(@PathVariable Long deployProjectId, @AuthenticationPrincipal SecurityUserDetails actor) {
        return ApiResponse.ok(service.list(deployProjectId, actor));
    }

    @PutMapping("/{key}")
    public ApiResponse<?> put(@PathVariable Long deployProjectId, @PathVariable String key,
                             @Valid @RequestBody GitlabCiVariableForm form,
                             @AuthenticationPrincipal SecurityUserDetails actor, HttpServletRequest request) {
        service.put(deployProjectId, key, form, actor, request.getRemoteAddr());
        return ApiResponse.ok("CI 변수를 저장했습니다.", null);
    }

    @DeleteMapping("/{key}")
    public ApiResponse<?> delete(@PathVariable Long deployProjectId, @PathVariable String key,
                                @Valid @RequestBody GitlabCiVariableDeleteForm form,
                                @AuthenticationPrincipal SecurityUserDetails actor, HttpServletRequest request) {
        service.delete(deployProjectId, key, form, actor, request.getRemoteAddr());
        return ApiResponse.ok("CI 변수를 삭제했습니다.", null);
    }

    // JSON 파싱 오류에는 입력 비밀값이 포함될 수 있으므로 공용 advice의 메시지 로그를 사용하지 않는다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> unreadableBody(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_BODY", "요청 형식을 확인해 주세요."));
    }
}
