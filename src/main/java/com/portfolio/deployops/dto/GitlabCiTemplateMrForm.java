package com.portfolio.deployops.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitlabCiTemplateMrForm {

    private String sourceBranch;
    private String targetBranch;
    private String baseCommitSha;
    private String title;
    private String description;
    private Boolean removeSourceBranch;
}
