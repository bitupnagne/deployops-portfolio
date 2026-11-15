package com.portfolio.deployops.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** GitLab 파이프라인을 만들기 전에 확인하는 최소 실행 준비 상태. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitlabCiReadiness {

    private boolean ready;
    private String ref;
    private String refType;
    private boolean protectedRef;
    private String runnerTag;
    private int matchingOnlineRunnerCount;
    private int executableRunnerCount;
    private List<String> requiredVariables;
    private List<String> missingVariables;
    private List<String> unavailableProtectedVariables;
    private List<String> blockers;
    private List<String> warnings;
}
