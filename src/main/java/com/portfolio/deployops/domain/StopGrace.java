package com.portfolio.deployops.domain;

/** 컨테이너 종료 유예. 환경별 런타임 바인딩과 승인 계획에서 같은 범위를 사용한다. */
public final class StopGrace {
    public static final int DEFAULT_SECONDS = 30;
    private StopGrace() { }
    public static int seconds(Integer value) {
        int seconds = value == null ? DEFAULT_SECONDS : value;
        if (seconds < 1 || seconds > 300) throw new IllegalArgumentException("종료 유예 시간은 1~300초 정수로 입력해 주세요.");
        return seconds;
    }
}
