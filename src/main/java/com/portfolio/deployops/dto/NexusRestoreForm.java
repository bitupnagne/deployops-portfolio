package com.portfolio.deployops.dto;
import jakarta.validation.constraints.*;
public record NexusRestoreForm(@NotNull @Positive Long backupRequestId,@NotBlank @Size(max=1000) String reason) {
}
