package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitlabPipelineTriggerForm {

    @NotBlank
    private String ref;

    private Map<String, String> variables;
}
