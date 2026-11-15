package com.portfolio.deployops.controller;

import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.ReferenceReport;
import com.portfolio.deployops.service.ReferenceLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "왜 지울 수 없는지" 를 화면이 물어보는 자리.
 *
 * <p>삭제가 409 로 막히면 화면이 여기를 불러 무엇이 막고 있는지 목록으로 펼친다.
 * 조회 전용이고, 여기서 무언가를 지우지는 않는다.
 *
 * <p>삭제 권한과 같은 권한을 요구한다 — 지울 수 없는 사람에게 "무엇이 걸려 있는지"
 * 를 보여 줄 이유가 없고, 그 목록 자체가 시스템 구성 정보다.
 */
@RestController
@RequestMapping("/api/v1/references")
@RequiredArgsConstructor
public class ReferenceApiController {

    private final ReferenceLookupService referenceLookupService;

    @GetMapping("/{type}/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or (hasRole('OPERATOR') and #type == 'server-group')")
    public ApiResponse<ReferenceReport> lookup(@PathVariable String type, @PathVariable Long id) {
        return ApiResponse.ok(referenceLookupService.lookup(type, id));
    }
}
