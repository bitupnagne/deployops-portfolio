package com.portfolio.deployops.dto;

/** Values currently shown in the project GitLab settings form. No credential or API host is accepted. */
public record ProjectGitlabConnectionForm(Long gitlabProjectId, String repositoryUrl) {}
