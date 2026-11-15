package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.domain.*;
import org.junit.jupiter.api.Test;

class DeploymentRetryPolicyTest {
    @Test
    void legacyFailedRequestCannotBecomeEligibleFromPinnedDigestAlone() {
        var request = DeployRequest.builder().environment(DeployEnvironment.PROD).status(DeployStatus.PROD_FAILED)
            .project(project(DeployEnvironment.PROD)).imageDigest("sha256:"+"a".repeat(64)).build();
        assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
    }

    private Project project(DeployEnvironment environment) {
        var group = ServerGroup.builder().environment(environment == DeployEnvironment.TEST ? ServerEnvironment.TEST : ServerEnvironment.PROD)
            .servers(java.util.List.of(Server.builder().id(11L).serverType(ServerType.WAS).build(),
                Server.builder().id(12L).serverType(ServerType.WEB).build())).build();
        return Project.builder().imageRepository("registry/app").serverGroups(java.util.List.of(group)).build();
    }
    @Test
    void onlyMatchingFailedSafeRequestWithPinnedImageIsEligible() {
        for (DeployEnvironment env : DeployEnvironment.values()) {
            DeployRequest request = DeployRequest.builder().environment(env).deployStrategy(DeployStrategy.SAFE)
                .project(project(env))
                .status(env == DeployEnvironment.TEST ? DeployStatus.TEST_FAILED : DeployStatus.PROD_FAILED)
                .targetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29)).imageDigest("sha256:" + "a".repeat(64)).build();
            assertThat(DeploymentRetryPolicy.eligible(request)).isTrue();
            for (DeployStrategy strategy : new DeployStrategy[]{DeployStrategy.MANUAL, DeployStrategy.WAVE}) {
                request.setDeployStrategy(strategy);
                assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
            }
            request.setDeployStrategy(DeployStrategy.SAFE);
            request.setStatus(env == DeployEnvironment.TEST ? DeployStatus.PROD_FAILED : DeployStatus.TEST_FAILED);
            assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
        }
    }

    @Test
    void missingInvalidDigestAndRunningOrCompletedCannotRetry() {
        DeployRequest request = DeployRequest.builder().environment(DeployEnvironment.PROD)
            .project(project(DeployEnvironment.PROD))
            .status(DeployStatus.PROD_FAILED).build();
        for (String digest : new String[]{null, "latest", "sha256:abc", "sha256:" + "z".repeat(64)}) {
            request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest(digest);
            assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
        }
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "b".repeat(64));
        for (DeployStatus status : new DeployStatus[]{DeployStatus.PROD_DEPLOYING, DeployStatus.COMPLETED, DeployStatus.APPROVAL_COMPLETED}) {
            request.setStatus(status);
            assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
        }
        assertThat(DeploymentRetryPolicy.eligible(null)).isFalse();
    }

    @Test
    void componentOverridesRequireTheirOwnEffectiveDigestEvenWithPinnedRequestDigest() {
        var api = DeployComponent.builder().id(1L).code("api").role(ServerType.WAS).active(true)
            .artifactType(ArtifactType.CONTAINER).imageRepository("registry/api").build();
        var web = DeployComponent.builder().id(2L).code("web").role(ServerType.WEB).active(true)
            .artifactType(ArtifactType.STATIC_BUNDLE).imageRepository("registry/web").build();
        var apiItem = DeployRequestItem.builder().componentId(1L).imageDigest("sha256:" + "a".repeat(64)).build();
        var webItem = DeployRequestItem.builder().componentCode("web").imageDigest("sha256:" + "b".repeat(64)).build();
        var request = DeployRequest.builder().environment(DeployEnvironment.PROD).status(DeployStatus.PROD_FAILED)
            .project(project(DeployEnvironment.PROD))
            .targetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29)).items(java.util.List.of(apiItem, webItem)).build();
        request.getProject().setComponents(java.util.List.of(api, web));
        assertThat(DeploymentRetryPolicy.eligible(request)).isTrue();
        request.setTargetAttemptTrackingStartedAt(java.time.LocalDateTime.of(2026,10,8,2,29));
        request.setImageDigest("sha256:" + "c".repeat(64));
        webItem.setImageDigest(null); webItem.setImageTag("latest");
        assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
        webItem.setImageTag(null); webItem.setBundleRef("https://mutable/bundle.tar");
        assertThat(DeploymentRetryPolicy.eligible(request)).isFalse();
        request.setDeployScope(DeployScope.WAS_ONLY);
        assertThat(DeploymentRetryPolicy.eligible(request)).isTrue();
        request.setDeployScope(DeployScope.WEB_ONLY);
        web.setArtifactType(ArtifactType.PROXY_RELOAD);
        assertThat(DeploymentRetryPolicy.eligible(request)).isTrue();
    }
}
