package com.portfolio.deployops.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.GitlabCiVariableDeleteForm;
import com.portfolio.deployops.dto.GitlabCiVariableForm;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GitlabCiVariableService {
    public record Metadata(String key, boolean configured, boolean masked,
                           @JsonProperty("protected") boolean protectedVariable,
                           String variableType, String environmentScope) {}
    public record View(boolean canManage, List<Metadata> variables, List<String> requiredVariables) {
        public View(boolean canManage, List<Metadata> variables) { this(canManage, variables, List.of()); }
    }

    private final ProjectRepository projects;
    private final ProjectPermissionService permissions;
    private final GitlabService gitlab;
    private final AuditLogService audit;
    private DeployComponentRepository components;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureComponents(DeployComponentRepository components) {
        this.components = components;
    }

    public View list(Long projectId, SecurityUserDetails actor) {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.PROJECT_VIEW);
        Project project = project(projectId);
        if (components != null) project.setComponents(components.findByProjectId(projectId));
        String token = readonlyToken(actor);
        List<Map<String, Object>> remote;
        try {
            remote = gitlab.getProjectVariables(token, null, project.getGitlabProjectId());
        } catch (Exception ignored) {
            throw upstreamFailure();
        }
        Map<String, Map<String, Object>> wildcard = new LinkedHashMap<>();
        for (Map<String, Object> variable : remote) {
            if ("*".equals(variable.get("environmentScope"))
                && variable.get("key") instanceof String key && GitlabCiVariablePolicy.KEYS.contains(key)) {
                if (wildcard.putIfAbsent(key, variable) != null) throw upstreamFailure();
            }
        }
        List<Metadata> metadata = GitlabCiVariablePolicy.KEYS.stream().map(key -> {
            Map<String, Object> existing = wildcard.get(key);
            return existing == null
                ? new Metadata(key, false, GitlabCiVariablePolicy.masked(key), true,
                    GitlabCiVariablePolicy.variableType(key), "*")
                : new Metadata(key, Boolean.TRUE.equals(existing.get("hasValue")),
                    Boolean.TRUE.equals(existing.get("masked")), Boolean.TRUE.equals(existing.get("protected")),
                    String.valueOf(existing.get("variableType")), "*");
        }).toList();
        return new View(permissions.hasPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE),
            metadata, GitlabCiReadinessService.requiredVariables(project));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void put(Long projectId, String key, GitlabCiVariableForm form, SecurityUserDetails actor, String ip) {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
        validateKey(key);
        String value = form == null ? null : form.value();
        try { GitlabCiVariablePolicy.validateValue(key, value); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        String reason = reason(form == null ? null : form.reason()).replace(value, "[REDACTED]");
        Project project = project(projectId);
        String token = token(actor);
        record(actor, projectId, key, "CI_VARIABLE_UPSERT_REQUEST", reason, AuditResult.SUCCESS, ip);
        try {
            gitlab.upsertProjectVariable(token, null, project.getGitlabProjectId(), key, value);
        } catch (Exception failure) {
            throw mutationFailure(failure, actor, projectId, key, "CI_VARIABLE_UPSERT", reason, ip);
        }
        record(actor, projectId, key, "CI_VARIABLE_UPSERT", reason, AuditResult.SUCCESS, ip);
    }

    public boolean matchesMavenSettings(Long projectId, String expected, SecurityUserDetails actor) {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
        Project project = project(projectId);
        try {
            return gitlab.matchesMavenSettingsFile(readonlyToken(actor), project.getGitlabProjectId(), expected);
        } catch (Exception ignored) { throw upstreamFailure(); }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(Long projectId, String key, GitlabCiVariableDeleteForm form, SecurityUserDetails actor, String ip) {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
        validateKey(key);
        String reason = reason(form == null ? null : form.reason());
        Project project = project(projectId);
        String token = token(actor);
        record(actor, projectId, key, "CI_VARIABLE_DELETE_REQUEST", reason, AuditResult.SUCCESS, ip);
        try {
            gitlab.deleteProjectVariable(token, null, project.getGitlabProjectId(), key);
        } catch (Exception failure) {
            throw mutationFailure(failure, actor, projectId, key, "CI_VARIABLE_DELETE", reason, ip);
        }
        record(actor, projectId, key, "CI_VARIABLE_DELETE", reason, AuditResult.SUCCESS, ip);
    }

    private Project project(Long id) {
        Project project = projects.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
        if (!project.isActive() || project.getGitlabProjectId() == null || project.getGitlabProjectId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "활성 프로젝트에 GitLab 저장소를 연결해 주세요.");
        }
        return project;
    }

    private String readonlyToken(SecurityUserDetails actor) {
        String token;
        try {
            token = gitlab.resolveReadonlyCiAccessToken(actor.getUser().getGitlabToken());
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitLab 인증 정보를 확인해 주세요.");
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitLab 토큰을 설정해 주세요.");
        }
        return token;
    }

    private String token(SecurityUserDetails actor) {
        String token;
        try {
            token = gitlab.resolveAccessToken(actor.getUser().getGitlabToken(), actor.getRole(), actor.getUsername());
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitLab 인증 정보를 확인해 주세요.");
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitLab 토큰을 설정해 주세요.");
        }
        return token;
    }

    private void validateKey(String key) {
        try { GitlabCiVariablePolicy.requireKey(key); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    private String reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경 사유는 1~200자로 입력해 주세요.");
        }
        return reason.trim();
    }

    // 외부 API는 DB와 함께 롤백되지 않는다. 요청 감사를 먼저 확정 저장하여 감사 실패 시 변경을 막는다.
    // 호출자의 트랜잭션도 중단(NOT_SUPPORTED)하여 AuditLogService의 각 record 트랜잭션을 확정한다.
    private void record(SecurityUserDetails actor, Long projectId, String key, String action,
                        String reason, AuditResult result, String ip) {
        try {
            audit.record(actor.getName(), action, "GitlabCiVariable", projectId + ":" + key,
                "actorId=" + actor.getId() + " projectId=" + projectId + " key=" + key + " reason=" + reason,
                result, ip);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "감사 기록을 저장하지 못했습니다. 변수 상태와 감사 기록을 확인한 뒤 다시 시도해 주세요.");
        }
    }

    private ResponseStatusException upstreamFailure() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "GitLab CI 변수 처리에 실패했습니다. 토큰 권한과 GitLab 상태를 확인해 주세요.");
    }

    private ResponseStatusException mutationFailure(Exception failure, SecurityUserDetails actor,
                                                    Long projectId, String key, String action, String reason, String ip) {
        if (failure instanceof GitlabAdminSystemTokenException) {
            record(actor, projectId, key, action, reason, AuditResult.FAIL, ip);
            return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "GitLab 시스템 연동 토큰이 관리자 계정(root/admin)입니다. "
                    + "대상 프로젝트의 Maintainer 권한과 api 범위를 가진 비관리자 전용 계정 PAT으로 "
                    + "시스템 연동 설정을 변경해 주세요. CI 변수는 변경되지 않았습니다.");
        }
        boolean unknown = !(failure instanceof GitlabCiVariableMutationException classified)
            || classified.isOutcomeUnknown();
        record(actor, projectId, key, action, reason, unknown ? AuditResult.UNKNOWN : AuditResult.FAIL, ip);
        return unknown
            ? new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                "GitLab CI 변수 변경 결과를 확인할 수 없습니다. 이미 적용되었을 수 있으므로 재전송 전에 상태를 새로 조회해 주세요.")
            : upstreamFailure();
    }
}
