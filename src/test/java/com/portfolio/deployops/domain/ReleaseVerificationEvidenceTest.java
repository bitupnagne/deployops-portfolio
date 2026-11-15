package com.portfolio.deployops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReleaseVerificationEvidenceTest {

    @Test
    void exactEvidenceIsValidAndMetadataTamperingInvalidatesIt() {
        DeployRequest request = verifiedShape();
        ReleaseVerificationEvidence.markVerifiedCiArtifact(
            request, 701L, "registry.example/app", LocalDateTime.of(2026, 9, 14, 10, 0));

        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(request)).isTrue();

        request.setGitlabPipelineId(78L);
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(request)).isFalse();
    }

    @Test
    void imageOnlyShapeHasNoTrustedProvenanceByDefault() {
        DeployRequest request = verifiedShape();

        assertThat(request.getReleaseVerificationStatus()).isEqualTo(ReleaseVerificationStatus.UNVERIFIED);
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(request)).isFalse();
    }

    @Test
    void completeManifestHashIsCopiedAndTamperingInvalidatesEvidence() {
        DeployRequest source = verifiedShape();
        source.setReleaseManifestSha256("b".repeat(64));
        ReleaseVerificationEvidence.markVerifiedCiArtifact(source,701L,"registry.example/app",
            LocalDateTime.of(2026,10,20,12,0));
        DeployRequest target = verifiedShape();
        assertThat(ReleaseVerificationEvidence.copyVerifiedFrom(source,target)).isTrue();
        assertThat(target.getReleaseManifestSha256()).isEqualTo("b".repeat(64));
        target.setReleaseManifestSha256("c".repeat(64));
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(target)).isFalse();
        target.setReleaseManifestSha256(null);
        assertThat(ReleaseVerificationEvidence.isVerifiedCiArtifact(target)).isFalse();
    }

    private DeployRequest verifiedShape() {
        return DeployRequest.builder()
            .gitlabPipelineId(77L)
            .commitSha("0123456789abcdef0123456789abcdef01234567")
            .imageTag("release-01234567-77")
            .imageDigest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .build();
    }
}
