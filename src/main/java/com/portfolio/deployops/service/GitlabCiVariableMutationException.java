package com.portfolio.deployops.service;

/** 원격 변경 전 실패/명시 거절과 전송 후 결과 불명을 구분한다. 원본 예외/응답은 담지 않는다. */
public final class GitlabCiVariableMutationException extends IllegalStateException {
    private final boolean outcomeUnknown;

    public GitlabCiVariableMutationException(boolean outcomeUnknown) {
        super(outcomeUnknown
            ? "GitLab CI 변수 변경 결과를 확인할 수 없습니다. 실제로 적용되었을 수 있습니다."
            : "GitLab CI 변수 변경이 실행되지 않았거나 GitLab에서 거절되었습니다.");
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() { return outcomeUnknown; }
}
