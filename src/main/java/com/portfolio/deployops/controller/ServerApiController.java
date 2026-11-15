package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.ServerForm;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.FileBrowseService;
import com.portfolio.deployops.service.ServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/servers")
@RequiredArgsConstructor
public class ServerApiController {

    private final ServerService serverService;
    private final ServerGroupRepository serverGroupRepository;
    private final FileBrowseService fileBrowseService;
    private final AuditLogService auditLogService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@RequestBody ServerForm form) {
        Server s = serverService.create(form, serverGroupRepository);
        auditLogService.recordCurrentRequired("서버 등록", "Server", String.valueOf(s.getId()),
            describe(s), AuditResult.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("서버가 추가되었습니다.", Map.of(
                "id", s.getId(), "name", s.getName()
            )));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody ServerForm form) {
        Server s = serverService.update(id, form, serverGroupRepository);
        auditLogService.recordCurrentRequired("서버 수정", "Server", String.valueOf(id),
            describe(s), AuditResult.SUCCESS);
        return ApiResponse.ok("서버 정보가 수정되었습니다.", Map.of(
            "id", s.getId(), "name", s.getName()
        ));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> delete(@PathVariable Long id) {
        // 삭제 뒤에는 무엇을 지웠는지 알 수 없다 — 설명을 먼저 만들어 둔다.
        String desc = serverService.findAll().stream()
            .filter(s -> id.equals(s.getId()))
            .findFirst().map(this::describe).orElse("id=" + id);
        serverService.delete(id);
        auditLogService.recordCurrentRequired("서버 삭제", "Server", String.valueOf(id),
            desc, AuditResult.SUCCESS);
        return ApiResponse.ok("서버가 삭제되었습니다.", null);
    }

    /** 감사로그에 남길 서버 식별 문구 — 이름만으로는 어느 망의 무엇인지 안 보인다. */
    private String describe(Server s) {
        return s.getName() + " (" + s.getIpAddress()
            + (s.getEnvironment() == null ? "" : ", " + s.getEnvironment().name()) + ")";
    }

    @PostMapping("/{id}/check")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ApiResponse<?> check(@PathVariable Long id) {
        return ApiResponse.ok(serverService.checkServer(id));
    }

    @PostMapping("/check-all")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    public ApiResponse<?> checkAll() {
        return ApiResponse.ok(serverService.checkAll());
    }

    /**
     * 서버 디렉터리 목록 (읽기 전용) — AI 로그 경로를 손으로 받아적지 않고 고르게 하기 위한 것.
     * 파일 내용은 나가지 않는다. 로그 경로를 설정하는 SUPER_ADMIN 만 호출할 수 있다.
     */
    @GetMapping("/browse")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<?> browse(@RequestParam(defaultValue = "local") String source,
                                 @RequestParam(defaultValue = "/") String path) {
        try {
            return ApiResponse.ok(fileBrowseService.browse(source, path));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error("BROWSE_FAILED", ex.getMessage());
        }
    }
}
