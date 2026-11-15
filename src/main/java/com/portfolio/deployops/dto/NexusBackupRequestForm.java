package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NexusBackupRequestForm(@NotBlank @Size(max=1000) String reason,
                                     boolean externalWritersStopped) {}
