package com.portfolio.deployops.controller;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Team;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.dto.TeamForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamApiController {

    private final TeamService teamService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<?> list() {
        List<Team> teams = teamService.findAll();
        return ApiResponse.ok(teams);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@RequestBody TeamForm form) {
        Team t;
        try {
            t = teamService.create(form);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        auditLogService.recordCurrentRequired("팀 생성", "Team", String.valueOf(t.getId()),
            t.getName(), AuditResult.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("팀이 생성되었습니다.", Map.of("id", t.getId(), "name", t.getName())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody TeamForm form) {
        Team t = teamService.update(id, form);
        auditLogService.recordCurrentRequired("팀 수정", "Team", String.valueOf(id),
            t.getName(), AuditResult.SUCCESS);
        return ApiResponse.ok("팀이 수정되었습니다.", Map.of("id", t.getId(), "name", t.getName()));
    }

    /**
     * 팀 <b>비활성화</b>(soft) — 소속 멤버는 팀 없음으로 풀린다.
     *
     * <p>멤버가 풀리는 것이 이 동작의 실질적 영향이라 감사로그에 인원수를 함께 남긴다.
     * "팀 하나 지웠다"보다 "사람 N명의 소속이 사라졌다"가 나중에 알아야 할 사실이다.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<?> delete(@PathVariable Long id) {
        Team before = teamService.findById(id);
        int members = before.getMembers() == null ? 0 : before.getMembers().size();
        teamService.delete(id);
        auditLogService.recordCurrentRequired("팀 비활성화", "Team", String.valueOf(id),
            before.getName() + " (소속 해제 " + members + "명)", AuditResult.SUCCESS);
        return ApiResponse.ok("팀이 삭제되었습니다.", null);
    }
}
