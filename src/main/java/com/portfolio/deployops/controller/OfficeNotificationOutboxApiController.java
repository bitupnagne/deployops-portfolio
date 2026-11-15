package com.portfolio.deployops.controller;

import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.OfficeNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operational visibility and recovery controls for the Office notification outbox. */
@RestController
@RequestMapping("/api/v1/office-notifications/outbox")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class OfficeNotificationOutboxApiController {

    private final OfficeNotificationService officeNotificationService;

    @GetMapping
    public ApiResponse<?> list(
        @RequestParam(defaultValue = "DEAD_LETTER") String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "30") int size
    ) {
        try {
            return ApiResponse.ok(officeNotificationService.getOutboxSnapshot(status, page, size));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("OUTBOX_STATUS_INVALID", "지원하지 않는 알림 상태입니다.");
        }
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<?> retry(@PathVariable Long id) {
        if (!officeNotificationService.retryDeadLetter(id)) {
            return ApiResponse.error("OUTBOX_RETRY_NOT_AVAILABLE", "재전송 가능한 실패 알림이 아닙니다.");
        }
        return ApiResponse.ok("Office 알림 재전송을 접수했습니다. 다음 전송 주기에 처리됩니다.");
    }
}
