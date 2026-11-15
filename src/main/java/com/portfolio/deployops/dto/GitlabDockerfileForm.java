package com.portfolio.deployops.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitlabDockerfileForm extends GitlabCiFileForm {
    private String filePath;
    private Long componentId;
}
