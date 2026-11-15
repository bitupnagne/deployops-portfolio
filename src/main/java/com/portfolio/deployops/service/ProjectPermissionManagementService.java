package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.ProjectUserPermission;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.ProjectPermissionForm;
import com.portfolio.deployops.dto.ProjectPermissionForm.Grant;
import com.portfolio.deployops.dto.ProjectPermissionView;
import com.portfolio.deployops.dto.ProjectPermissionView.EffectivePermission;
import com.portfolio.deployops.dto.ProjectPermissionView.RawPermission;
import com.portfolio.deployops.dto.ProjectPermissionView.UserSummary;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProjectPermissionManagementService {
    private final ProjectRepository projects;
    private final UserRepository users;
    private final ProjectUserPermissionRepository permissions;
    private final AuditLogService audit;

    private static final List<Grant> AVAILABLE = available();
    private static final Comparator<Grant> ORDER = Comparator.comparing((Grant grant) -> grant.permission().name())
        .thenComparing(grant -> grant.environment().name());

    @Transactional(readOnly = true)
    public ProjectPermissionView.ProjectUsers projectUsers(Long projectId, SecurityUserDetails actor) {
        requireCurrentAdmin(actor);
        Project project = project(projectId);
        return new ProjectPermissionView.ProjectUsers(projectId, project.getName(),
            users.findAllWithTeam().stream().map(this::summary).toList());
    }

    @Transactional(readOnly = true)
    public ProjectPermissionView get(Long projectId, Long userId, SecurityUserDetails actor) {
        User admin = requireCurrentAdmin(actor);
        Project project = project(projectId);
        User target = user(userId);
        return view(project, target, admin.getId(), permissions.findByProjectIdAndUserId(projectId, userId));
    }

    @Transactional(readOnly = true)
    public List<ProjectPermissionView> byUser(Long userId, SecurityUserDetails actor) {
        User admin = requireCurrentAdmin(actor);
        User target = user(userId);
        Map<Long, List<ProjectUserPermission>> grouped = new LinkedHashMap<>();
        permissions.findByUserId(userId).forEach(row ->
            grouped.computeIfAbsent(row.getProject().getId(), ignored -> new ArrayList<>()).add(row));
        return projects.findAll().stream()
            .map(project -> view(project, target, admin.getId(), grouped.getOrDefault(project.getId(), List.of())))
            .toList();
    }

    @Transactional
    public ProjectPermissionView replace(Long projectId, Long userId, ProjectPermissionForm form,
                                         SecurityUserDetails principal, String ip) {
        requireAdminPrincipal(principal);
        if (principal.getId().equals(userId)) throw forbidden("자기 자신의 프로젝트 권한은 변경할 수 없습니다.");
        validateForm(form);
        // 같은 사용자 조합의 잠금 순서를 고정하고, 세션에 남은 과거 관리자 역할을 신뢰하지 않는다.
        Map<Long, User> lockedUsers = new LinkedHashMap<>();
        for (Long id : List.of(principal.getId(), userId).stream().sorted().toList()) {
            lockedUsers.put(id, users.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")));
        }
        User actor = lockedUsers.get(principal.getId());
        assertActiveAdmin(actor);
        User target = lockedUsers.get(userId);
        if (projects.lockById(projectId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
        Project project = project(projectId);
        List<ProjectUserPermission> before = permissions.findByProjectIdAndUserIdForUpdate(projectId, userId);
        if (!revision(projectId, target, before).equals(form.expectedRevision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "권한 또는 사용자 상태가 변경되었습니다. 최신 내용을 불러온 뒤 다시 저장해 주세요.");
        }
        Set<Grant> requested = new LinkedHashSet<>(form.grants());
        Map<Grant, ProjectUserPermission> existing = new LinkedHashMap<>();
        before.forEach(row -> existing.put(grant(row), row));
        Set<Grant> previouslyActive = new LinkedHashSet<>();
        before.stream().filter(ProjectUserPermission::isActive).map(this::grant).forEach(previouslyActive::add);
        Set<Grant> added = new LinkedHashSet<>(requested);
        added.removeAll(previouslyActive);
        if (!added.isEmpty() && (!target.isActive() || target.getRole() == UserRole.SUPER_ADMIN || !project.isActive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성 사용자·프로젝트 또는 최고 관리자에게 새 직접 권한을 부여할 수 없습니다. 기존 권한 회수는 가능합니다.");
        }
        for (Grant grant : added) {
            if (!AVAILABLE.contains(grant)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 권한과 환경 조합입니다.");
        }
        Set<Grant> all = new LinkedHashSet<>(existing.keySet());
        all.addAll(requested);
        for (Grant grant : all.stream().sorted(ORDER).toList()) {
            boolean wasActive = previouslyActive.contains(grant);
            boolean active = requested.contains(grant);
            if (wasActive == active) continue;
            ProjectUserPermission row = existing.get(grant);
            int changed = row == null
                ? permissions.insert(ProjectUserPermission.builder().project(project).user(target)
                    .permission(grant.permission()).environment(grant.environment()).active(true).build())
                : permissions.setActive(projectId, userId, grant.permission(), grant.environment(), active);
            if (changed != 1) throw new IllegalStateException("프로젝트 권한 저장 건수가 올바르지 않습니다.");
            // recordCurrent는 실패를 삼킨다. record를 같은 트랜잭션으로 호출하여 감사 실패도 롤백한다.
            audit.record(actor.getName(), "프로젝트 권한 변경", "ProjectUserPermission", projectId + ":" + userId,
                "actorId=" + actor.getId() + " projectId=" + projectId + " userId=" + userId
                    + " permission=" + grant.permission() + " environment=" + grant.environment()
                    + " active=" + wasActive + "->" + active + " reason=" + form.reason().trim(),
                AuditResult.SUCCESS, ip);
        }
        return view(project, target, actor.getId(), permissions.findByProjectIdAndUserIdForUpdate(projectId, userId));
    }

    private ProjectPermissionView view(Project project, User target, Long actorId, List<ProjectUserPermission> rows) {
        List<Grant> active = rows.stream().filter(ProjectUserPermission::isActive).map(this::grant).toList();
        List<EffectivePermission> effective = new ArrayList<>();
        if (target.isActive()) {
            for (Grant candidate : AVAILABLE) {
                String source = null;
                if (target.getRole() == UserRole.SUPER_ADMIN) source = "SUPER_ADMIN 역할";
                else if (target.getRole() == UserRole.PROJECT_MANAGER && Set.of(ProjectPermissionType.PROJECT_VIEW,
                    ProjectPermissionType.MR_REVIEW).contains(candidate.permission())) source = "PROJECT_MANAGER 역할";
                else if (active.stream().anyMatch(grant -> grant.permission() == candidate.permission()
                    && (grant.environment() == ProjectPermissionEnvironment.ALL || grant.environment() == candidate.environment()))) {
                    source = "직접 부여";
                }
                if (source != null) effective.add(new EffectivePermission(candidate, source));
            }
        }
        List<String> notes = new ArrayList<>();
        notes.add("승인 자격과 별도로 승인 정책의 지정 승인자·단계·자기 승인 금지 조건을 충족해야 합니다.");
        if (target.getRole() == UserRole.APPROVER) notes.add("APPROVER 역할의 전역 배포 승인 자격은 직접 권한을 회수해도 유지됩니다. TEST 완료 확인에는 TEST_APPROVE 권한이 별도로 필요합니다.");
        if (target.getRole() == UserRole.SUPER_ADMIN) notes.add("SUPER_ADMIN은 역할로 모든 프로젝트 권한을 갖습니다. 직접 권한을 회수해도 역할 권한은 유지됩니다.");
        if (!target.isActive()) notes.add("비활성 계정입니다. 새 권한 부여는 금지되며 기존 권한 회수만 가능합니다. 기존 로그인 세션 회수는 별도 관리 대상입니다.");
        if (!project.isActive()) notes.add("비활성 프로젝트에는 새 권한을 부여할 수 없습니다.");
        notes.add("DEPLOY_REQUEST / ALL은 TEST·PROD 요청뿐 아니라 수동 GitLab 파이프라인 실행 권한도 포함합니다.");
        boolean editable = !target.getId().equals(actorId);
        return new ProjectPermissionView(project.getId(), project.getName(), summary(target), revision(project.getId(), target, rows),
            editable, editable && target.isActive() && target.getRole() != UserRole.SUPER_ADMIN && project.isActive(),
            editable ? "" : "자기 자신의 권한은 변경할 수 없습니다.",
            rows.stream().sorted(Comparator.comparing(this::grant, ORDER)).map(row -> new RawPermission(grant(row), row.isActive())).toList(),
            effective, AVAILABLE, notes);
    }

    private String revision(Long projectId, User target, List<ProjectUserPermission> rows) {
        StringBuilder value = new StringBuilder(projectId + ":" + target.getId() + ":" + target.getRole() + ":" + target.isActive());
        rows.stream().sorted(Comparator.comparing(this::grant, ORDER)).forEach(row -> value.append('|')
            .append(row.getPermission()).append(':').append(row.getEnvironment()).append(':').append(row.isActive()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void validateForm(ProjectPermissionForm form) {
        if (form == null || form.grants() == null || form.grants().size() > 30
            || form.reason() == null || form.reason().isBlank() || form.reason().length() > 200
            || form.expectedRevision() == null || !form.expectedRevision().matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "권한 목록, 변경 사유와 최신 revision이 필요합니다.");
        }
        if (form.grants().stream().anyMatch(grant -> grant == null || grant.permission() == null || grant.environment() == null)
            || new LinkedHashSet<>(form.grants()).size() != form.grants().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "권한 목록에 빈 항목이나 중복이 있습니다.");
        }
    }

    private User requireCurrentAdmin(SecurityUserDetails principal) {
        requireAdminPrincipal(principal);
        User actor = user(principal.getId());
        assertActiveAdmin(actor);
        return actor;
    }
    private void requireAdminPrincipal(SecurityUserDetails principal) {
        if (principal == null || principal.getId() == null || !"SUPER_ADMIN".equals(principal.getRole())) throw forbidden("최고 관리자만 프로젝트 권한을 관리할 수 있습니다.");
    }
    private void assertActiveAdmin(User actor) {
        if (!actor.isActive() || actor.getRole() != UserRole.SUPER_ADMIN) throw forbidden("관리자 권한이 변경되었거나 계정이 비활성화되었습니다. 다시 로그인해 주세요.");
    }
    private User user(Long id) { return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")); }
    private Project project(Long id) { return projects.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.")); }
    private ResponseStatusException forbidden(String reason) { return new ResponseStatusException(HttpStatus.FORBIDDEN, reason); }
    private Grant grant(ProjectUserPermission row) { return new Grant(row.getPermission(), row.getEnvironment()); }
    private UserSummary summary(User user) { return new UserSummary(user.getId(), user.getLoginId(), user.getName(), user.getRole(), user.getRole().getLabel(), user.isActive()); }
    private static List<Grant> available() {
        List<Grant> result = new ArrayList<>();
        for (ProjectPermissionType permission : ProjectPermissionType.values()) {
            if (permission == ProjectPermissionType.DEPLOY_REQUEST) result.add(new Grant(permission, ProjectPermissionEnvironment.ALL));
            switch (permission) {
                case PROJECT_VIEW, MR_REVIEW, MR_APPROVE, MR_MERGE, CI_VARIABLE_MANAGE -> result.add(new Grant(permission, ProjectPermissionEnvironment.ALL));
                case TEST_APPROVE, TEST_EXECUTE -> result.add(new Grant(permission, ProjectPermissionEnvironment.TEST));
                case PROD_APPROVE, PROD_EXECUTE -> result.add(new Grant(permission, ProjectPermissionEnvironment.PROD));
                case DEPLOY_REQUEST, ROLLBACK_EXECUTE, RUNTIME_CONFIG_MANAGE, RUNTIME_LOG_READ -> {
                    result.add(new Grant(permission, ProjectPermissionEnvironment.TEST));
                    result.add(new Grant(permission, ProjectPermissionEnvironment.PROD));
                }
            }
        }
        return List.copyOf(result);
    }
}
