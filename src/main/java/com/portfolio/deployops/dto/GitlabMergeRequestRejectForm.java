package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GitlabMergeRequestRejectForm(
    @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{40,64}$") String reviewedSha,
    @NotBlank String reviewedTargetBranch,
    @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{40,64}$") String reviewedDiffBaseSha,
    @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{40,64}$") String reviewedDiffStartSha,
    @NotBlank(message = "반려 사유를 입력해 주세요.")
    @Size(max = 1000, message = "반려 사유는 1000자 이내로 입력해 주세요.") String reason
) { }
