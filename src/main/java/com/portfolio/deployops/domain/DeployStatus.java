package com.portfolio.deployops.domain;

/**
 * 배포 요청 상태.
 *
 * <p>한글 문구를 여기 둔다. 그동안 화면마다 제각기 매핑을 들고 있었고
 * ({@code request-detail.html} 의 {@code statusLabelMap} 등) 새 상태가 늘 때 한 곳만 고치면
 * 다른 화면에는 {@code PROD_DEPLOYING} 같은 열거형 이름이 그대로 노출됐다.
 *
 * <p>{@link #isInFlight()} 는 "아직 끝나지 않았다"는 뜻이다 — 이 값이 참인 요청이 있으면
 * 그 프로젝트는 지금 손대면 안 되는 상태다. 화면 경고와
 * {@code DeployRequestService} 의 동시 실행 차단이 같은 판단을 쓴다.
 */
public enum DeployStatus {

    TEST_REQUESTED("TEST 요청 접수", true),
    TEST_BUILDING("TEST 빌드 중", true),
    TEST_DEPLOYING("TEST 배포 중", true),
    TEST_DEPLOYED("TEST 배포 완료", false),
    TEST_FAILED("TEST 실패", false),
    TEST_CONFIRMED("TEST 확인 완료", false),
    APPROVAL_PENDING("승인 대기", true),
    APPROVAL_COMPLETED("승인 완료 · 배포 대기", true),
    REJECTED("반려", false),
    CANCELLED("요청 철회", false),
    PROD_DEPLOYING("운영 배포 중", true),
    COMPLETED("배포 완료", false),
    PROD_FAILED("운영 배포 실패", false),
    ROLLED_BACK("롤백됨", false);

    private final String label;
    private final boolean inFlight;

    DeployStatus(String label, boolean inFlight) {
        this.label = label;
        this.inFlight = inFlight;
    }

    /** 사람이 읽는 상태 문구. */
    public String getLabel() {
        return label;
    }

    /**
     * 아직 끝나지 않은 상태인가.
     *
     * <p>실패·반려는 <b>거짓</b>이다 — 나쁜 결과지만 그 요청은 끝났고, 새 요청을 막지 않는다.
     */
    public boolean isInFlight() {
        return inFlight;
    }
}
