package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitlabMergeRequestCreateForm {

    @NotBlank
    private String sourceBranch;

    private String targetBranch;

    @NotBlank
    private String title;

    private String description;

    private Boolean removeSourceBranch;
}
