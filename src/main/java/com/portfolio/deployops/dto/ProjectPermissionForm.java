package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProjectPermissionForm(
    @NotNull @Size(max = 30) List<@NotNull @Valid Grant> grants,
    @NotBlank @Size(max = 200) String reason,
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedRevision
) {
    public record Grant(@NotNull ProjectPermissionType permission, @NotNull ProjectPermissionEnvironment environment) { }
}
