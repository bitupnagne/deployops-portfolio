package com.portfolio.deployops.controller;

import org.springframework.web.bind.annotation.PutMapping;
import java.util.List;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.ServerGroupForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ServerGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/server-groups")
@RequiredArgsConstructor
public class ServerGroupApiController {

    private final ServerGroupService serverGroupService;
    private final AuditLogService auditLogService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody ServerGroupForm form) {
        ServerGroup sg = serverGroupService.create(form);
        auditLogService.recordCurrentRequired("서버 그룹 생성", "ServerGroup", String.valueOf(sg.getId()),
            describe(sg), AuditResult.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("서버 그룹이 생성되었습니다.", Map.of(
                "id", sg.getId(), "name", sg.getName()
            )));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long id, @Valid @RequestBody ServerGroupForm form) {
        ServerGroup sg = serverGroupService.update(id, form);
        auditLogService.recordCurrentRequired("서버 그룹 수정", "ServerGroup", String.valueOf(id),
            describe(sg), AuditResult.SUCCESS);
        return ApiResponse.ok("서버 그룹이 수정되었습니다.", Map.of(
            "id", sg.getId(), "name", sg.getName()
        ));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> delete(@PathVariable Long id) {
        // 삭제 뒤에는 이름을 알 수 없다. 참조 검사에 걸려 409 가 나면 이 줄까지 오지 않는다.
        String desc = describe(serverGroupService.findById(id));
        serverGroupService.delete(id);
        auditLogService.recordCurrentRequired("서버 그룹 삭제", "ServerGroup", String.valueOf(id),
            desc, AuditResult.SUCCESS);
        return ApiResponse.ok("서버 그룹이 삭제되었습니다.", null);
    }

    /** 감사로그용 식별 문구 — 어느 환경의 그룹인지가 이름만큼 중요하다. */
    private String describe(ServerGroup sg) {
        return sg.getName()
            + (sg.getEnvironment() == null ? "" : " (" + sg.getEnvironment().name() + ")")
            + " / 최소 가용=" + sg.getMinAvailable()
            + ", 동시 교체=" + sg.getMaxUnavailable()
            + ", 관찰=" + sg.getObservationSeconds() + "초"
            + ", 실패=" + sg.getFailurePolicy()
            + ", 추가 승인 정책=" + (sg.getApprovalPolicyId() == null ? "없음 (프로젝트 기본 유지)" : sg.getApprovalPolicyId())
            + ", 배포 간격=" + sg.getRollingIntervalSec() + "초";
    }

    /**
     * 그룹 표시 순서 저장. 화면에서 "순서 변경"을 확정할 때 한 번에 보낸다.
     *
     * <p>보낸 순서대로 0,1,2… 를 다시 매긴다. 클라이언트가 계산한 번호를 그대로 믿지 않는 이유는,
     * 중간에 그룹이 지워졌거나 다른 사람이 먼저 바꿨을 때 번호가 어긋나기 때문이다.
     */
    @PutMapping("/order")
    @PreAuthorize("hasAnyRole('OPERATOR','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> reorder(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ApiResponse.error("VALIDATION_ERROR", "순서를 바꿀 그룹이 없습니다.");
        }
        serverGroupService.reorder(ids);
        auditLogService.recordCurrentRequired("서버 그룹 순서 변경", "ServerGroup", "-",
            "그룹 " + ids.size() + "개 순서 재지정", AuditResult.SUCCESS);
        return ApiResponse.ok("그룹 순서를 저장했습니다.");
    }
}
