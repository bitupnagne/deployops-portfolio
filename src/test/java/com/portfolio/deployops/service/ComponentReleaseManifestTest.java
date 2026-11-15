package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ComponentImageForm;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentReleaseManifestTest {
    private static final String API_DIGEST = "sha256:" + "a".repeat(64);
    private static final String WEB_DIGEST = "sha256:" + "b".repeat(64);

    @Test
    void completeReleaseUsesCanonicalComponentIdsAndImmutableDigests() {
        Project project = project();
        var items = ComponentReleaseManifest.fromForms(project,
            List.of(form(2L, "web", WEB_DIGEST), form(1L, "api", API_DIGEST)));
        ComponentReleaseManifest.requireComplete(project, items);
        assertThat(items).extracting(DeployRequestItem::getComponentCode).containsExactly("api", "web");
        assertThat(items).extracting(DeployRequestItem::getImageDigest).containsExactly(API_DIGEST, WEB_DIGEST);
    }

    @Test
    void missingSecondArtifactCannotFallBackToPrimaryImage() {
        Project project = project();
        assertThatThrownBy(() -> ComponentReleaseManifest.requireComplete(project,
            ComponentReleaseManifest.fromForms(project, List.of(form(1L, "api", API_DIGEST)))))
            .hasMessageContaining("web 산출물이 빠졌습니다");
        assertThatThrownBy(() -> ComponentReleaseManifest.requireComplete(project, List.of()))
            .hasMessageContaining("산출물이 빠졌습니다");
    }

    @Test
    void tagOnlyAndMutableBundleAreRejected() {
        ComponentImageForm tagOnly = form(1L, "api", null);
        tagOnly.setImageTag("release-7");
        assertThatThrownBy(() -> ComponentReleaseManifest.fromForms(project(), List.of(tagOnly)))
            .hasMessageContaining("다이제스트");
        ComponentImageForm bundle = form(2L, "web", WEB_DIGEST);
        bundle.setBundleRef("/tmp/current.tar");
        assertThatThrownBy(() -> ComponentReleaseManifest.fromForms(project(), List.of(bundle)))
            .hasMessageContaining("가변 번들");
    }

    @Test
    void foreignConflictingAndDuplicateComponentsAreRejected() {
        assertThatThrownBy(() -> ComponentReleaseManifest.fromForms(project(), List.of(form(99L, "other", API_DIGEST))))
            .hasMessageContaining("등록되지 않은");
        assertThatThrownBy(() -> ComponentReleaseManifest.fromForms(project(), List.of(form(1L, "web", API_DIGEST))))
            .hasMessageContaining("ID와 코드");
        assertThatThrownBy(() -> ComponentReleaseManifest.fromForms(project(),
            List.of(form(1L, "api", API_DIGEST), form(1L, "api", API_DIGEST))))
            .hasMessageContaining("중복");
    }

    @Test
    void promotionRejectsChangedDigestOrRepositoryAndCopiesWithoutSourceIds() {
        Project project = project();
        var expected = ComponentReleaseManifest.fromForms(project,
            List.of(form(1L, "api", API_DIGEST), form(2L, "web", WEB_DIGEST)));
        var changed = ComponentReleaseManifest.fromForms(project,
            List.of(form(1L, "api", WEB_DIGEST), form(2L, "web", WEB_DIGEST)));
        assertThatThrownBy(() -> ComponentReleaseManifest.requireSameArtifacts(expected, changed))
            .hasMessageContaining("다른 배포 단위 산출물");
        Project changedRepository = project();
        changedRepository.getComponents().get(1).setImageRepository("registry/foreign");
        assertThatThrownBy(() -> ComponentReleaseManifest.requireSameComponents(project, changedRepository))
            .hasMessageContaining("이미지 저장소");
        expected.getFirst().setId(9L);
        expected.getFirst().setDeployRequestId(10L);
        var copies = ComponentReleaseManifest.copies(expected);
        assertThat(copies.getFirst()).isNotSameAs(expected.getFirst());
        assertThat(copies.getFirst().getId()).isNull();
        assertThat(copies.getFirst().getDeployRequestId()).isNull();
    }

    @Test
    void legacySingleImageStillWorksButSeparateComponentRepositoryRequiresArtifact() {
        Project project = Project.builder().imageRepository("registry/api").build();
        ComponentReleaseManifest.requireComplete(project, List.of());
        project.setComponents(List.of(component(1L, "api", ServerType.WAS, "registry/api")));
        ComponentReleaseManifest.requireComplete(project, List.of());
        project.getComponents().getFirst().setImageRepository("registry/other");
        assertThatThrownBy(() -> ComponentReleaseManifest.requireComplete(project, List.of()))
            .hasMessageContaining("산출물이 빠졌습니다");
    }

    @Test
    void proxyDoesNotRequireAnImageButCannotReceiveOne() {
        Project project = project();
        project.getComponents().get(1).setArtifactType(ArtifactType.PROXY_RELOAD);
        ComponentReleaseManifest.requireComplete(project, List.of());
        assertThatThrownBy(() -> ComponentReleaseManifest.fromForms(project, List.of(form(2L, "web", WEB_DIGEST))))
            .hasMessageContaining("리버스 프록시");
    }

    @Test
    void primaryCiImageCannotDescribeADifferentExecutedComponent() {
        Project project = project();
        var items = ComponentReleaseManifest.fromForms(project, List.of(
            form(1L, "api", API_DIGEST), form(2L, "web", WEB_DIGEST)));
        ComponentReleaseManifest.requirePrimaryImage(project, items, API_DIGEST);
        assertThatThrownBy(() -> ComponentReleaseManifest.requirePrimaryImage(project, items, WEB_DIGEST))
            .hasMessageContaining("CI 검증 이미지와 실행 산출물");
    }

    @Test
    void scopedReleaseRequiresOnlyArtifactsAndTargetsInItsVerifiedScope() {
        var project = project();
        var api = ComponentReleaseManifest.fromForms(project, List.of(form(1L, "api", API_DIGEST)));
        ComponentReleaseManifest.requireComplete(project, api, com.portfolio.deployops.domain.DeployScope.WAS_ONLY);
        assertThatThrownBy(() -> ComponentReleaseManifest.requireComplete(project, api,
            com.portfolio.deployops.domain.DeployScope.ALL)).hasMessageContaining("web").hasMessageContaining("산출물이 빠졌습니다");
        var targets = List.of(com.portfolio.deployops.domain.Server.builder().serverType(ServerType.WAS).build());
        assertThat(EffectiveDeployTargets.missingTargetRoles(project, targets,
            com.portfolio.deployops.domain.DeployScope.WAS_ONLY)).isEmpty();
        assertThat(EffectiveDeployTargets.missingTargetRoles(project, targets,
            com.portfolio.deployops.domain.DeployScope.ALL)).containsExactly(ServerType.WEB);
    }

    static Project project() {
        return Project.builder().id(1L).imageRepository("registry/api").components(List.of(
            component(1L, "api", ServerType.WAS, "registry/api"),
            component(2L, "web", ServerType.WEB, "registry/web"))).build();
    }

    private static DeployComponent component(Long id, String code, ServerType role, String repository) {
        return DeployComponent.builder().id(id).projectId(1L).code(code).role(role).active(true)
            .artifactType(role == ServerType.WEB ? ArtifactType.STATIC_BUNDLE : ArtifactType.CONTAINER)
            .imageRepository(repository).build();
    }

    static ComponentImageForm form(Long id, String code, String digest) {
        ComponentImageForm form = new ComponentImageForm();
        form.setComponentId(id); form.setComponentCode(code);
        form.setImageTag("release-1"); form.setImageDigest(digest);
        return form;
    }
}
