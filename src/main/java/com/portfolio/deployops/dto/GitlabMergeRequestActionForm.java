package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The exact source head that the human reviewed in the Deploy UI. */
public record GitlabMergeRequestActionForm(
    @NotBlank
    @Pattern(regexp = "(?i)^[0-9a-f]{40,64}$", message = "reviewedSha must be a full Git commit SHA")
    String reviewedSha,
    @NotBlank String reviewedTargetBranch,
    @NotBlank
    @Pattern(regexp = "(?i)^[0-9a-f]{40,64}$", message = "reviewedDiffBaseSha must be a full Git commit SHA")
    String reviewedDiffBaseSha,
    @NotBlank
    @Pattern(regexp = "(?i)^[0-9a-f]{40,64}$", message = "reviewedDiffStartSha must be a full Git commit SHA")
    String reviewedDiffStartSha
) { }
