package com.portfolio.deployops.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;
public record NexusRestoreConfirmation(
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String cloneContainerId,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String manifestSha256,
        @NotNull Set<String> checklist,
        @NotNull @Size(max=16) List<@Valid Artifact> artifacts) {
    public record Artifact(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String ref,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String downloadedSha256) {
    }
}
