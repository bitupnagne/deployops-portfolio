package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RollbackRequestForm {

    @NotNull(message = "Image history must be selected.")
    private Long historyId;

    @NotBlank(message = "Deployment environment is required.")
    private String environment;

    @NotBlank(message = "Rollback reason is required.")
    @Size(min = 5, max = 1000, message = "Rollback reason must be between 5 and 1000 characters.")
    private String reason;
}
