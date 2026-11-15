package com.portfolio.deployops.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** 서버가 검증한 release provenance 필드의 자기 일관성을 고정한다. */
public final class ReleaseVerificationEvidence {

    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-fA-F]{40,64}$");

    private ReleaseVerificationEvidence() { }

    public static void markVerifiedCiArtifact(
        DeployRequest request,
        long jobId,
        String repository,
        LocalDateTime verifiedAt
    ) {
        Objects.requireNonNull(request, "request");
        request.setReleaseVerificationStatus(ReleaseVerificationStatus.VERIFIED_CI_ARTIFACT);
        request.setReleaseVerificationJobId(jobId);
        request.setReleaseVerificationRepository(normalize(repository));
        request.setReleaseVerifiedAt(Objects.requireNonNull(verifiedAt, "verifiedAt")
            .truncatedTo(ChronoUnit.MICROS));
        request.setReleaseVerificationEvidenceHash(calculateHash(request));
        if (!isVerifiedCiArtifact(request)) {
            clear(request);
            throw new IllegalArgumentException("검증된 CI 산출물 증적 값이 올바르지 않습니다.");
        }
    }

    public static boolean copyVerifiedFrom(DeployRequest source, DeployRequest target) {
        if (!isVerifiedCiArtifact(source) || target == null) {
            if (target != null) {
                clear(target);
            }
            return false;
        }
        target.setReleaseVerificationStatus(source.getReleaseVerificationStatus());
        target.setReleaseVerificationJobId(source.getReleaseVerificationJobId());
        target.setReleaseVerificationRepository(source.getReleaseVerificationRepository());
        target.setReleaseVerifiedAt(source.getReleaseVerifiedAt());
        target.setReleaseVerificationEvidenceHash(source.getReleaseVerificationEvidenceHash());
        target.setReleaseManifestSha256(source.getReleaseManifestSha256());
        if (!isVerifiedCiArtifact(target)) {
            clear(target);
            return false;
        }
        return true;
    }

    public static boolean isVerifiedCiArtifact(DeployRequest request) {
        if (request == null
            || request.getReleaseVerificationStatus() != ReleaseVerificationStatus.VERIFIED_CI_ARTIFACT
            || request.getReleaseVerificationJobId() == null
            || request.getReleaseVerificationJobId() <= 0
            || request.getGitlabPipelineId() == null
            || request.getGitlabPipelineId() <= 0
            || normalize(request.getReleaseVerificationRepository()) == null
            || normalize(request.getImageTag()) == null
            || "latest".equalsIgnoreCase(normalize(request.getImageTag()))
            || normalize(request.getImageDigest()) == null
            || !DIGEST_PATTERN.matcher(normalize(request.getImageDigest())).matches()
            || normalize(request.getCommitSha()) == null
            || !COMMIT_PATTERN.matcher(normalize(request.getCommitSha())).matches()
            || request.getReleaseVerifiedAt() == null
            || normalize(request.getReleaseVerificationEvidenceHash()) == null) {
            return false;
        }
        if (request.getReleaseManifestSha256() != null
                && !request.getReleaseManifestSha256().matches("[0-9a-f]{64}")) return false;
        byte[] expected = calculateHash(request).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = request.getReleaseVerificationEvidenceHash().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    public static void clear(DeployRequest request) {
        request.setReleaseVerificationStatus(ReleaseVerificationStatus.UNVERIFIED);
        request.setReleaseVerificationJobId(null);
        request.setReleaseVerificationRepository(null);
        request.setReleaseVerifiedAt(null);
        request.setReleaseVerificationEvidenceHash(null);
        request.setReleaseManifestSha256(null);
    }

    private static String calculateHash(DeployRequest request) {
        String canonical = String.join("\u0000",
            String.valueOf(request.getReleaseVerificationJobId()),
            String.valueOf(request.getGitlabPipelineId()),
            value(request.getReleaseVerificationRepository()),
            value(request.getImageTag()),
            value(request.getImageDigest()).toLowerCase(),
            value(request.getCommitSha()).toLowerCase(),
            String.valueOf(request.getReleaseVerifiedAt())
        );
        // Null preserves the exact evidence hash of pre-manifest releases.
        if (request.getReleaseManifestSha256() != null) canonical += "\u0000" + request.getReleaseManifestSha256();
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static String value(String value) {
        String normalized = normalize(value);
        return normalized == null ? "" : normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
