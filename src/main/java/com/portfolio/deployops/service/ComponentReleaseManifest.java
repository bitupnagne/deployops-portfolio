package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.DeployRequestItem;
import com.portfolio.deployops.domain.DeployScope;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.ComponentImageForm;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The persisted plan snapshot is the release manifest. This class validates and canonicalizes
 * its component artifacts before persistence; it never treats a mutable tag as release identity.
 */
final class ComponentReleaseManifest {
    private ComponentReleaseManifest() {}

    static List<DeployRequestItem> fromForms(Project project, List<ComponentImageForm> forms) {
        List<DeployRequestItem> items = new ArrayList<>();
        if (forms != null) {
            for (ComponentImageForm form : forms) {
                if (form == null) throw invalid("배포 단위 산출물이 비어 있습니다.");
                items.add(DeployRequestItem.builder().componentId(form.getComponentId())
                    .componentCode(form.getComponentCode()).imageTag(form.getImageTag())
                    .imageDigest(form.getImageDigest()).bundleRef(form.getBundleRef()).build());
            }
        }
        return canonical(project, items);
    }

    static List<DeployRequestItem> canonical(Project project, List<DeployRequestItem> supplied) {
        List<DeployComponent> components = components(project);
        List<DeployRequestItem> result = new ArrayList<>();
        HashSet<Long> ids = new HashSet<>();
        for (DeployRequestItem item : supplied == null ? List.<DeployRequestItem>of() : supplied) {
            DeployComponent component = components.stream().filter(candidate ->
                    (item.getComponentId() != null && Objects.equals(candidate.getId(), item.getComponentId()))
                    || (text(item.getComponentCode()) != null
                        && Objects.equals(candidate.getCode(), text(item.getComponentCode()))))
                .findFirst().orElseThrow(() -> invalid("등록되지 않은 배포 단위 산출물입니다."));
            if (component.getId() == null
                    || (item.getComponentId() != null && !Objects.equals(component.getId(), item.getComponentId()))
                    || (text(item.getComponentCode()) != null
                        && !Objects.equals(component.getCode(), text(item.getComponentCode())))) {
                throw invalid("배포 단위 ID와 코드가 일치하지 않습니다.");
            }
            if (!ids.add(component.getId())) throw invalid("배포 단위 산출물을 중복 지정할 수 없습니다.");
            if (component.getArtifactType() == ArtifactType.PROXY_RELOAD) {
                throw invalid("리버스 프록시 재적용 단위에는 이미지 산출물을 지정하지 않습니다.");
            }
            String digest = text(item.getImageDigest());
            if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
                throw invalid(component.getCode() + " 산출물의 sha256 다이제스트가 필요합니다. 태그만으로 승격하거나 배포할 수 없습니다.");
            }
            if (text(item.getBundleRef()) != null) {
                throw invalid("가변 번들 경로 대신 다이제스트가 고정된 정적 리소스 이미지를 사용해 주세요.");
            }
            if (repository(project, component) == null) throw invalid("배포 단위 이미지 저장소가 없습니다.");
            result.add(DeployRequestItem.builder().componentId(component.getId()).componentCode(component.getCode())
                .imageTag(text(item.getImageTag())).imageDigest(digest).build());
        }
        result.sort(Comparator.comparing(DeployRequestItem::getComponentId));
        return List.copyOf(result);
    }

    static void requireComplete(Project project, List<DeployRequestItem> items) {
        requireComplete(project, items, DeployScope.ALL);
    }

    static void requireComplete(Project project, List<DeployRequestItem> items, DeployScope requestedScope) {
        DeployScope scope = requestedScope == null ? DeployScope.ALL : requestedScope;
        List<DeployComponent> artifacts = components(project).stream()
            .filter(component -> scope.includes(component.getRole()))
            .filter(component -> component.getArtifactType() != ArtifactType.PROXY_RELOAD).toList();
        // The existing single-image contract remains valid only if it really uses one repository.
        if (items.isEmpty() && artifacts.size() <= 1
                && (artifacts.isEmpty() || Objects.equals(repository(project, artifacts.getFirst()), text(project.getImageRepository())))) {
            return;
        }
        for (DeployComponent component : artifacts) {
            if (items.stream().noneMatch(item -> Objects.equals(item.getComponentId(), component.getId()))) {
                throw invalid(component.getCode() + " 산출물이 빠졌습니다. 선택한 범위의 모든 배포 단위를 TEST에서 검증한 후 승격해 주세요.");
            }
        }
    }

    static void requireSameComponents(Project source, Project target) {
        List<String> before = identities(source);
        List<String> after = identities(target);
        if (!before.equals(after)) {
            throw invalid("TEST 이후 배포 단위 또는 이미지 저장소가 변경되었습니다. 새 TEST 배포를 확인해 주세요.");
        }
    }

    static void requireSameArtifacts(List<DeployRequestItem> expected, List<DeployRequestItem> actual) {
        if (expected.size() != actual.size()) throw invalid("TEST와 다른 산출물 묶음은 승격할 수 없습니다.");
        for (int i = 0; i < expected.size(); i++) {
            DeployRequestItem a = expected.get(i);
            DeployRequestItem b = actual.get(i);
            if (!Objects.equals(a.getComponentId(), b.getComponentId())
                    || !Objects.equals(a.getComponentCode(), b.getComponentCode())
                    || !Objects.equals(a.getImageDigest(), b.getImageDigest())
                    || !Objects.equals(a.getImageTag(), b.getImageTag())) {
                throw invalid("TEST와 다른 배포 단위 산출물입니다. 새 TEST 배포를 확인해 주세요.");
            }
        }
    }

    static void requirePrimaryImage(Project project, List<DeployRequestItem> items, String primaryDigest) {
        if (text(primaryDigest) == null || text(project.getImageRepository()) == null) return;
        for (DeployRequestItem item : items) {
            DeployComponent component = components(project).stream()
                .filter(candidate -> Objects.equals(candidate.getId(), item.getComponentId())).findFirst().orElseThrow();
            if (Objects.equals(repository(project, component), text(project.getImageRepository()))
                    && !Objects.equals(primaryDigest, item.getImageDigest())) {
                throw invalid("대표 이미지와 같은 저장소의 배포 단위 다이제스트가 다릅니다. CI 검증 이미지와 실행 산출물을 일치시켜 주세요.");
            }
        }
    }

    static List<DeployRequestItem> copies(List<DeployRequestItem> items) {
        return items.stream().map(item -> DeployRequestItem.builder()
            .componentId(item.getComponentId()).componentCode(item.getComponentCode())
            .imageTag(item.getImageTag()).imageDigest(item.getImageDigest()).bundleRef(item.getBundleRef()).build()).toList();
    }

    private static List<String> identities(Project project) {
        return components(project).stream().map(component -> component.getId() + "|" + component.getCode()
            + "|" + component.getRole() + "|" + component.getArtifactType() + "|" + repository(project, component))
            .sorted().toList();
    }

    private static List<DeployComponent> components(Project project) {
        return project == null || project.getComponents() == null ? List.of()
            : project.getComponents().stream().filter(DeployComponent::isActive).toList();
    }

    private static String repository(Project project, DeployComponent component) {
        return text(component.getImageRepository()) != null
            ? text(component.getImageRepository()) : text(project.getImageRepository());
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
