package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.dto.GitlabCiReadiness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Runner와 CI/CD 변수 때문에 파이프라인이 대기·실패하는 일을 트리거 전에 차단한다. */
@Service
@RequiredArgsConstructor
public class GitlabCiReadinessService {

    private static final String ACCESS_PROTECTED = "ref_protected";
    private static final String ACCESS_UNPROTECTED = "not_protected";

    private final GitlabService gitlabService;

    /** main 보호와 별개인, 아직 생성되지 않은 MR 빌드 태그의 명시적 보호 규칙을 확인한다. */
    public GitlabCiReadiness evaluateForMergeRequestBuild(
        String token, String gitlabUrl, Project project, String ref
    ) throws Exception {
        GitlabCiReadiness result = evaluate(token, gitlabUrl, project, ref);
        List<String> blockers = new ArrayList<>(result.getBlockers());
        List<String> warnings = new ArrayList<>(result.getWarnings());
        String guidance = "GitLab 프로젝트 Settings > Repository > Protected tags에서 '"
            + GitlabService.IMMUTABLE_MR_TAG_POLICY
            + "' 또는 MR 태그 전체를 보호하는 'deploy/*' 규칙과 승인된 CI 실행 계정의 Allowed to create 권한을 확인해 주세요. "
            + "main 브랜치 보호만으로 MR 빌드 태그가 보호되지는 않습니다.";
        try {
            GitlabService.ImmutableTagPolicy policy = gitlabService.getImmutableMrTagPolicy(
                token, gitlabUrl, project.getGitlabProjectId());
            if (policy == null) {
                blockers.add("MR 빌드 태그 보호 정책을 확인할 수 없습니다. " + guidance);
            } else if (!policy.present()) {
                blockers.add("MR 빌드 태그 보호 규칙이 없어 새 이미지 빌드를 차단합니다. " + guidance);
            } else if (!policy.creationEnabled()) {
                blockers.add("MR 빌드 태그 보호 규칙의 생성 권한이 No access입니다. " + guidance);
            } else {
                warnings.add("MR 빌드 태그 보호 규칙 '" + policy.matchedPolicyName()
                    + "'를 확인했습니다. 승인·병합된 MR 증거와 배포 권한은 실제 실행 시 다시 확인됩니다.");
            }
        } catch (Exception e) {
            blockers.add("MR 빌드 태그 보호 정책 조회에 실패했습니다. 현재 GitLab 토큰의 조회 권한과 연결을 확인해 주세요. "
                + guidance);
        }
        result.setBlockers(List.copyOf(blockers));
        result.setWarnings(List.copyOf(warnings));
        result.setReady(blockers.isEmpty());
        return result;
    }

    public GitlabCiReadiness evaluate(
        String token,
        String gitlabUrl,
        Project project,
        String ref
    ) throws Exception {
        if (project == null || project.getGitlabProjectId() == null) {
            throw new IllegalArgumentException("GitLab 저장소가 연결되어 있지 않습니다.");
        }

        long projectId = project.getGitlabProjectId();
        String runnerTag = GitlabCiContract.runnerTag();
        GitlabService.RefProtection refProtection = gitlabService.getRefProtection(
            token, gitlabUrl, projectId, ref);
        List<Map<String, Object>> runners = gitlabService.getProjectRunners(token, gitlabUrl, projectId);
        List<String> runnerDetailFailures = new ArrayList<>();
        List<Map<String, Object>> runnerDetails = new ArrayList<>();
        for (Map<String, Object> runner : runners) {
            if (!isOnlineAndAcceptingJobs(runner)) {
                continue;
            }
            long runnerId = longValue(runner.get("id"));
            if (runnerId <= 0) {
                runnerDetailFailures.add("온라인 Runner의 ID를 확인할 수 없어 실행을 중단합니다.");
                continue;
            }
            try {
                runnerDetails.add(gitlabService.getRunnerDetails(token, gitlabUrl, runnerId));
            } catch (Exception e) {
                runnerDetailFailures.add("GitLab Runner #" + runnerId
                    + " 상세 정보를 확인할 수 없어 실행을 중단합니다: " + detailFailureMessage(e));
            }
        }
        List<Map<String, Object>> matchingOnlineRunners = runnerDetails.stream()
            .filter(this::isOnlineAndAcceptingJobs)
            .filter(runner -> tagsOf(runner).contains(runnerTag))
            .toList();
        int matchingOnlineRunnerCount = matchingOnlineRunners.size();
        int executableRunnerCount = 0;
        int protectedOnlyRunnerCount = 0;
        int unknownAccessRunnerCount = 0;
        int unprotectedRunnerCount = 0;
        for (Map<String, Object> runner : matchingOnlineRunners) {
            String accessLevel = text(runner.get("accessLevel"));
            if (ACCESS_PROTECTED.equals(accessLevel)) {
                protectedOnlyRunnerCount++;
                if (refProtection.protectedRef()) {
                    executableRunnerCount++;
                }
            } else if (ACCESS_UNPROTECTED.equals(accessLevel)) {
                unprotectedRunnerCount++;
                executableRunnerCount++;
            } else {
                unknownAccessRunnerCount++;
            }
        }

        List<Map<String, Object>> variables = gitlabService.getProjectVariables(token, gitlabUrl, projectId);
        Map<String, Map<String, Object>> wildcardVariables = new LinkedHashMap<>();
        for (Map<String, Object> variable : variables) {
            String key = text(variable.get("key"));
            String environmentScope = text(variable.get("environmentScope"));
            // 생성 job은 GitLab environment를 선언하지 않으므로 wildcard 변수만 확실히 전달된다.
            if (key != null && (environmentScope == null || "*".equals(environmentScope))) {
                wildcardVariables.putIfAbsent(key, variable);
            }
        }

        List<String> requiredVariables = requiredVariables(project);
        List<String> missingVariables = requiredVariables.stream()
            .filter(required -> !wildcardVariables.containsKey(required))
            .toList();
        List<String> unavailableProtectedVariables = requiredVariables.stream()
            .filter(wildcardVariables::containsKey)
            .filter(required -> !refProtection.protectedRef()
                && Boolean.TRUE.equals(wildcardVariables.get(required).get("protected")))
            .toList();
        List<String> blockers = new ArrayList<>();
        blockers.addAll(runnerDetailFailures);
        for (String key : requiredVariables) {
            Map<String, Object> variable = wildcardVariables.get(key);
            if (variable == null) continue;
            if (!Boolean.TRUE.equals(variable.get("hasValue"))) {
                blockers.add(key + " 변수 값이 비어 있거나 확인되지 않습니다. GitLab CI/CD Variables에서 값을 등록해 주세요.");
            }
            String expectedType = "MAVEN_SETTINGS_FILE".equals(key) ? "file" : "env_var";
            if (!expectedType.equals(variable.get("variableType"))) {
                blockers.add(key + " 변수 타입을 " + expectedType + "로 설정해 주세요.");
            }
        }
        if (matchingOnlineRunnerCount == 0) {
            blockers.add("온라인 상태이며 작업을 받을 수 있는 '" + runnerTag + "' 태그 Runner가 없습니다.");
        } else if (executableRunnerCount == 0) {
            if (!refProtection.protectedRef() && protectedOnlyRunnerCount > 0 && unknownAccessRunnerCount == 0) {
                blockers.add("GitLab ref '" + refProtection.ref()
                    + "'는 보호되지 않았지만 일치하는 Runner는 보호 ref 전용(ref_protected)입니다.");
            } else {
                blockers.add("일치하는 Runner의 access_level을 안전하게 확인할 수 없어 실행을 중단합니다.");
            }
        }
        if (!missingVariables.isEmpty()) {
            blockers.add("GitLab CI/CD 필수 변수가 없습니다: " + String.join(", ", missingVariables));
        }
        if (!unavailableProtectedVariables.isEmpty()) {
            blockers.add("보호되지 않은 ref에는 다음 Protected 변수가 전달되지 않습니다: "
                + String.join(", ", unavailableProtectedVariables));
        }

        List<String> warnings = variableWarnings(wildcardVariables, refProtection.protectedRef(), requiredVariables);
        if (refProtection.protectedRef() && unprotectedRunnerCount > 0) {
            warnings.add("보호 ref 작업을 일반(not_protected) Runner도 실행할 수 있습니다. 운영 Runner는 보호 전용을 권장합니다.");
        }
        if (!refProtection.protectedRef()
            && executableRunnerCount > 0
            && unavailableProtectedVariables.isEmpty()
            && missingVariables.isEmpty()) {
            warnings.add("현재 비보호 ref·일반 Runner·비보호 변수 조합으로 실행됩니다. 운영 기본은 ref, Runner, 비밀 변수를 모두 보호해 주세요.");
        }
        return GitlabCiReadiness.builder()
            .ready(blockers.isEmpty())
            .ref(refProtection.ref())
            .refType(refProtection.type())
            .protectedRef(refProtection.protectedRef())
            .runnerTag(runnerTag)
            .matchingOnlineRunnerCount(matchingOnlineRunnerCount)
            .executableRunnerCount(executableRunnerCount)
            .requiredVariables(requiredVariables)
            .missingVariables(missingVariables)
            .unavailableProtectedVariables(unavailableProtectedVariables)
            .blockers(List.copyOf(blockers))
            .warnings(List.copyOf(warnings))
            .build();
    }

    public void assertReady(String token, String gitlabUrl, Project project, String ref) throws Exception {
        GitlabCiReadiness readiness = evaluate(token, gitlabUrl, project, ref);
        if (!readiness.isReady()) {
            throw new RemoteExecutionException("GitLab CI 사전점검 실패: " + String.join(" ", readiness.getBlockers()));
        }
    }

    private boolean isOnlineAndAcceptingJobs(Map<String, Object> runner) {
        boolean online = Boolean.TRUE.equals(runner.get("online"))
            || "online".equalsIgnoreCase(String.valueOf(runner.getOrDefault("status", "")));
        return online && !Boolean.TRUE.equals(runner.get("paused"));
    }

    private Set<String> tagsOf(Map<String, Object> runner) {
        Object raw = runner.get("tagList");
        if (!(raw instanceof List<?> values)) {
            return Set.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (Object value : values) {
            String tag = text(value);
            if (tag != null) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private List<String> variableWarnings(
        Map<String, Map<String, Object>> variables,
        boolean protectedRef,
        List<String> requiredVariables
    ) {
        List<String> warnings = new ArrayList<>();
        for (String key : requiredVariables) {
            Map<String, Object> variable = variables.get(key);
            if (variable == null) {
                continue;
            }
            if (key.endsWith("_PASSWORD") && !Boolean.TRUE.equals(variable.get("masked"))) {
                warnings.add(key + " 변수를 Masked로 설정해 주세요.");
            }
            if (protectedRef && !Boolean.TRUE.equals(variable.get("protected"))) {
                warnings.add(key + " 변수를 Protected로 설정해 주세요.");
            }
        }
        return warnings;
    }

    /** Shared with the configuration UI; follows the generated CI's effective build contracts. */
    public static List<String> requiredVariables(Project project) {
        Set<String> required = new LinkedHashSet<>(GitlabCiContract.REQUIRED_PROJECT_VARIABLES);
        required.add("DOCKER_BUILD_IMAGE");
        var artifacts = project.getComponents() == null ? List.<com.portfolio.deployops.domain.DeployComponent>of()
            : project.getComponents().stream().filter(com.portfolio.deployops.domain.DeployComponent::isActive)
                .filter(component -> component.getArtifactType() != com.portfolio.deployops.domain.ArtifactType.PROXY_RELOAD)
                .toList();
        if (artifacts.size() > 1) {
            for (var component : artifacts) {
                BuildToolType tool = component.getBuildTool() == null ? project.getBuildTool() : component.getBuildTool();
                String dockerfile = component.getDockerfilePath();
                if (dockerfile == null || dockerfile.isBlank()) dockerfile = project.getDockerfilePath();
                addBuildRequirements(required, tool, dockerfile);
            }
        } else {
            // The existing one-image generator uses project-level build fields.
            addBuildRequirements(required, project.getBuildTool(), project.getDockerfilePath());
        }
        return List.copyOf(required);
    }

    private static void addBuildRequirements(Set<String> required, BuildToolType tool, String dockerfile) {
        if (tool == BuildToolType.MAVEN) {
            required.addAll(List.of("MAVEN_BUILD_IMAGE", "MAVEN_SETTINGS_FILE", "NEXUS_MAVEN_USER", "NEXUS_MAVEN_PASSWORD"));
        } else {
            required.add("CI_BUILD_IMAGE");
        }
        dockerfile = dockerfile == null ? null : dockerfile.trim();
        if (dockerfile != null && (dockerfile.equals("Dockerfile.release") || dockerfile.endsWith("/Dockerfile.release"))) {
            required.add("RUNTIME_BASE_IMAGE");
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String detailFailureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? "토큰 권한과 GitLab Runner API 상태를 확인해 주세요."
            : message;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
