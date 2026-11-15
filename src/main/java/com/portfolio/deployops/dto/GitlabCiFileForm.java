package com.portfolio.deployops.dto;

import lombok.Data;

/**
 * 화면에서 고친 {@code .gitlab-ci.yml} 을 저장소에 반영할 때 받는 값.
 *
 * <p>{@link GitlabCiTemplateMrForm} 과 달리 <b>내용을 직접 싣는다</b> — 저쪽은 프로젝트
 * 설정으로부터 서버가 파일을 만들어 내지만, 여기는 사람이 편집기에서 고친 글자 그대로다.
 */
@Data
public class GitlabCiFileForm {

    /** 파일 전체 내용. 비어 있으면 거절한다 — 실수로 CI 를 지우는 사고를 막는다. */
    private String content;

    /** 없으면 {@code deploy-system/ci-edit-<시각>} 으로 만든다. */
    private String sourceBranch;
    /** 없으면 프로젝트의 MR 대상 브랜치 → 기본 브랜치 순으로 고른다. */
    private String targetBranch;
    /** 화면이 파일을 읽은 시점의 대상 branch 전체 commit SHA. */
    private String baseCommitSha;
    /** 조회한 원문의 SHA-256. 파일이 없으면 null. */
    private String originalSha256;

    private String title;
    private String description;
    private String commitMessage;
}
