package com.portfolio.deployops.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalActionForm {
    @Size(max = 1000)
    private String comment;
    @jakarta.validation.constraints.Min(1)
    private Integer expectedStepOrder;
}
