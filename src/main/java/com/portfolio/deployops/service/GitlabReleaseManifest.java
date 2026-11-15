package com.portfolio.deployops.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata;
import com.portfolio.deployops.dto.GitlabBuildArtifactMetadata.ComponentArtifact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact, bounded CI contract; component IDs are resolved only from the approved project. */
final class GitlabReleaseManifest {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_BYTES = 256 * 1024;

    private GitlabReleaseManifest() { }

    static List<DeployComponent> artifacts(Project project) {
        return project.getComponents() == null ? List.of() : project.getComponents().stream()
            .filter(DeployComponent::isActive)
            .filter(component -> component.getArtifactType() != ArtifactType.PROXY_RELOAD)
            .sorted(Comparator.comparing(DeployComponent::getCode)).toList();
    }

    static boolean required(Project project) {
        var components = artifacts(project);
        return components.size() > 1 || components.stream()
            .anyMatch(component -> !Objects.equals(repository(project, component), text(project.getImageRepository())));
    }

    static String repository(Project project, DeployComponent component) {
        return text(component.getImageRepository()) == null
            ? text(project.getImageRepository()) : text(component.getImageRepository());
    }

    static void validateConfiguration(Project project) {
        Set<String> codes = new HashSet<>(), repositories = new HashSet<>();
        var components = artifacts(project);
        for (var component : components) {
            if (component.getId() == null || text(component.getCode()) == null
                    || !codes.add(component.getCode()) || repository(project, component) == null
                    || !repositories.add(repository(project, component))) {
                throw invalid("CI 배포 단위에는 고유한 코드·이미지 저장소가 필요합니다.");
            }
        }
        if (components.stream().filter(component -> Objects.equals(repository(project, component),
                text(project.getImageRepository()))).count() != 1) {
            throw invalid("프로젝트 대표 이미지 저장소와 일치하는 CI 배포 단위가 정확히 하나 필요합니다.");
        }
    }

    static GitlabBuildArtifactMetadata validateMetadata(Project project, GitlabBuildArtifactMetadata artifact) {
        try {
            var tree = new LinkedHashMap<String, Object>();
            tree.put("schemaVersion", 1); tree.put("pipelineId", artifact.pipelineId());
            tree.put("commitSha", artifact.commitSha()); tree.put("components", artifact.components());
            var validated = parse(JSON.writeValueAsString(tree), project, artifact);
            if (!Objects.equals(validated.releaseManifestSha256(), artifact.releaseManifestSha256()))
                throw invalid("배포 묶음 증적 해시가 일치하지 않습니다.");
            return validated;
        } catch (RemoteExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("배포 묶음 증적을 확인할 수 없습니다.");
        }
    }

    static GitlabBuildArtifactMetadata parse(String body, Project project, GitlabBuildArtifactMetadata primary) {
        if (body == null || body.isBlank() || body.length() > MAX_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("배포 묶음 JSON이 비었거나 허용 크기를 초과했습니다.");
        }
        try {
            validateConfiguration(project);
            JsonNode root = JSON.readTree(body);
            exactFields(root, Set.of("schemaVersion", "pipelineId", "commitSha", "components"));
            if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()
                    || root.path("schemaVersion").intValue() != 1
                    || !root.path("pipelineId").isIntegralNumber() || !root.path("pipelineId").canConvertToLong()
                    || root.path("pipelineId").longValue() != primary.pipelineId()
                    || !string(root, "commitSha").equalsIgnoreCase(primary.commitSha())) {
                throw invalid("배포 묶음의 schema·pipeline·commit이 성공한 CI와 다릅니다.");
            }
            JsonNode supplied = root.get("components");
            var expected = artifacts(project);
            if (!supplied.isArray() || supplied.size() != expected.size() || expected.isEmpty()) {
                throw invalid("배포 묶음의 컴포넌트 수가 승인된 구성과 다릅니다.");
            }
            var items = new java.util.ArrayList<ComponentArtifact>();
            Set<String> codes = new HashSet<>();
            for (JsonNode row : supplied) {
                exactFields(row, Set.of("code", "repository", "tag", "digest"));
                String code = string(row, "code");
                if (!codes.add(code)) throw invalid("배포 묶음의 컴포넌트 코드가 중복됩니다.");
                DeployComponent component = expected.stream().filter(item -> code.equals(item.getCode())).findFirst()
                    .orElseThrow(() -> invalid("등록되지 않았거나 이미지가 없는 컴포넌트입니다."));
                String repository = string(row, "repository"), tag = string(row, "tag"), digest = string(row, "digest");
                if (!Objects.equals(repository(project, component), repository)
                        || !GitlabCiContract.isValidImageTag(tag) || "latest".equalsIgnoreCase(tag)
                        || !digest.matches("sha256:[0-9a-fA-F]{64}")) {
                    throw invalid("배포 묶음의 저장소·태그·다이제스트가 승인된 구성과 다릅니다.");
                }
                items.add(new ComponentArtifact(code, repository, tag, digest.toLowerCase(java.util.Locale.ROOT)));
            }
            items.sort(Comparator.comparing(ComponentArtifact::code));
            if (items.stream().noneMatch(item -> item.repository().equals(primary.repository())
                    && item.tag().equals(primary.tag()) && item.digest().equalsIgnoreCase(primary.digest()))) {
                throw invalid("대표 이미지 증적이 전체 배포 묶음과 일치하지 않습니다.");
            }
            return new GitlabBuildArtifactMetadata(primary.jobId(), primary.pipelineId(), primary.repository(),
                primary.tag(), primary.digest(), primary.commitSha(), items,
                canonicalHash(primary.pipelineId(), primary.commitSha(), items));
        } catch (RemoteExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            // Never echo arbitrary CI artifact content into public logs.
            throw invalid("배포 묶음 JSON을 안전하게 해석할 수 없습니다.");
        }
    }

    static String canonicalHash(long pipelineId, String commitSha, List<ComponentArtifact> items) {
        try {
            var canonical = new LinkedHashMap<String, Object>();
            canonical.put("schemaVersion", 1);
            canonical.put("pipelineId", pipelineId);
            canonical.put("commitSha", commitSha.toLowerCase(java.util.Locale.ROOT));
            canonical.put("components", items.stream().sorted(Comparator.comparing(ComponentArtifact::code)).toList());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical)));
        } catch (Exception exception) {
            throw invalid("배포 묶음 증적 해시를 계산할 수 없습니다.");
        }
    }

    private static void exactFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid("배포 묶음 JSON 필드가 올바르지 않습니다.");
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw invalid("배포 묶음에 알 수 없는 필드가 있습니다.");
    }
    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || text(value.textValue()) == null
                || !value.textValue().equals(value.textValue().trim())) throw invalid("배포 묶음 필드가 올바르지 않습니다.");
        return value.textValue();
    }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static RemoteExecutionException invalid(String message) { return new RemoteExecutionException(message); }
}
