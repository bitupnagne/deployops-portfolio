package com.portfolio.deployops.service;

/** A known local policy rejection before any GitLab mutation; contains no upstream details. */
public final class GitlabAdminSystemTokenException extends IllegalArgumentException {
    public GitlabAdminSystemTokenException() {
        super("GitLab 시스템 토큰에는 root/admin 계정을 사용할 수 없습니다.");
    }
}
