package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.GitlabCiVariableForm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NexusMavenBuildService {
    private final NexusDependencyUploadService uploads;
    private final NexusMetadataService nexus;
    private final GitlabCiVariableService variables;
    private final ProjectPermissionService permissions;
    private final SystemSettingService settings;

    public record Plan(String uploadRepository, String commonRepository, String groupName,
                       String mirrorUrl, String settingsXml, String warning) {}
    public record Verification(boolean settingsMatch, boolean credentialsConfigured, String message) {}

    public Map<String, Object> configuration(Long projectId, SecurityUserDetails actor) throws Exception {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
        return Map.of("commonRepository", settings.get(key(projectId), ""),
            "repositories", nexus.listRepositories("maven2").stream()
                .filter(row -> Set.of("hosted", "group").contains(String.valueOf(row.get("type")))).toList());
    }

    public Plan preview(Long projectId, String common, SecurityUserDetails actor) throws Exception {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
        var binding = uploads.binding(projectId);
        if (!"maven2".equals(binding.format()))
            throw new IllegalArgumentException("Maven 빌드 연결은 Maven2 반입 저장소에서만 지원합니다. RAW·npm은 별도 빌드 설정이 필요합니다.");
        NexusDependencyPolicy.repository(binding.repository());
        NexusDependencyPolicy.repository(common);
        var base = nexus.listRepositories("maven2").stream().filter(row -> common.equals(row.get("name"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("기존 Maven 저장소를 선택하세요."));
        if (!Set.of("hosted", "group").contains(String.valueOf(base.get("type"))))
            throw new IllegalArgumentException("기존 Maven 저장소는 hosted 또는 group이어야 합니다.");
        String suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((binding.repository() + "\n" + common).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        String group = "deploy-p" + projectId + "-" + suffix;
        NexusDependencyPolicy.repository(group);
        String url = nexus.runnerMavenBaseUrl() + "/repository/" + group + "/";
        String warning = "기존 group의 하위 proxy가 외부 저장소를 참조할 수 있습니다. 폐쇄망에서는 필요한 의존성과 플러그인을 사전 반입하고 Runner에서 실제 빌드를 확인하세요. "
            + "이 작업은 기존 MAVEN_SETTINGS_FILE 전체를 교체합니다. 사용자 정의 profile·인증서·proxy 설정이 있었다면 먼저 별도로 보존하세요.";
        return new Plan(binding.repository(), common, group, url, settingsXml(url), warning);
    }

    public Verification apply(Long projectId, String common, String expectedUpload, String reason,
                              SecurityUserDetails actor, String ip) throws Exception {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.CI_VARIABLE_MANAGE);
        if (reason == null || reason.isBlank() || reason.length() > 200)
            throw new IllegalArgumentException("변경 사유는 1~200자로 입력하세요.");
        Plan plan = preview(projectId, common, actor);
        if (!plan.uploadRepository().equals(expectedUpload))
            throw new IllegalArgumentException("반입 저장소 연결이 변경됐습니다. 미리보기를 다시 확인하세요.");
        nexus.ensureProjectMavenGroup(plan.groupName(), plan.uploadRepository(), common);
        variables.put(projectId, "MAVEN_SETTINGS_FILE", new GitlabCiVariableForm(plan.settingsXml(), reason), actor, ip);
        settings.saveAll("nexus-project-repositories", Map.of(key(projectId), common));
        return verify(projectId, common, actor);
    }

    public Verification verify(Long projectId, String common, SecurityUserDetails actor) throws Exception {
        Plan plan = preview(projectId, common, actor);
        List<String> members = plan.uploadRepository().equals(common) ? List.of(common) : List.of(plan.uploadRepository(), common);
        nexus.assertProjectMavenGroup(plan.groupName(), members);
        boolean matches = variables.matchesMavenSettings(projectId, plan.settingsXml(), actor);
        var view = variables.list(projectId, actor);
        boolean credentials = List.of("NEXUS_MAVEN_USER", "NEXUS_MAVEN_PASSWORD").stream().allMatch(key ->
            view.variables().stream().anyMatch(value -> key.equals(value.key()) && value.configured()
                && value.protectedVariable() && "env_var".equals(value.variableType())));
        return new Verification(matches, credentials, matches && credentials
            ? "프로젝트 group과 GitLab File 설정·인증 변수 구성을 확인했습니다. 실제 의존성 해결 및 빌드 성공은 승인된 CI 파이프라인 실행으로 확인하세요."
            : "빌드 연결이 준비되지 않았습니다. File 설정 일치 여부와 NEXUS_MAVEN_USER / NEXUS_MAVEN_PASSWORD 등록을 확인하세요.");
    }

    private String key(Long id) { return "nexus.dependency." + id + ".maven-common"; }

    static String settingsXml(String mirrorUrl) {
        String escaped = mirrorUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
              <mirrors>
                <mirror><id>deploy-project-nexus</id><mirrorOf>*</mirrorOf><url>%s</url></mirror>
              </mirrors>
              <servers>
                <server><id>deploy-project-nexus</id>
                  <username>${env.NEXUS_MAVEN_USER}</username>
                  <password>${env.NEXUS_MAVEN_PASSWORD}</password>
                </server>
              </servers>
            </settings>
            """.formatted(escaped);
    }
}
