package com.portfolio.deployops.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 대시보드 차트 데이터.
 *
 * <p>화면이 그리기만 하면 되도록 <b>서버에서 완성해서</b> 내려보낸다.
 * 배포가 없던 날의 0 채우기, 상태 13종의 결과 분류 같은 판단을 JS 로 옮기면
 * 같은 규칙이 서버·화면 두 곳에 생기고 결국 어긋난다.
 */
@Getter
@Builder
public class DashboardChartsDto {

    /** 일자별 추이. 배포가 없던 날도 0 으로 채워 <b>빠짐없이</b> 들어 있다. */
    private List<DailyPoint> daily;

    /** 기간 합계 — 도넛과 성공률에 쓴다. */
    private Outcome total;

    /** 환경별 합계 (TEST/PROD). */
    private List<EnvSlice> byEnvironment;

    /** 집계 기간(일). 화면 부제에 그대로 쓴다. */
    private int days;

    @Getter
    @Builder
    public static class DailyPoint {
        /** ISO 날짜(yyyy-MM-dd). 화면에서 축 라벨로 자른다. */
        private String date;
        private long succeeded;
        private long failed;
        private long rolledBack;
        /** 아직 끝나지 않은 것(요청·빌드·배포중·승인대기). */
        private long inProgress;
        private long cancelled;
    }

    @Getter
    @Builder
    public static class EnvSlice {
        private String environment;
        private long succeeded;
        private long failed;
        private long rolledBack;
        private long inProgress;
        private long cancelled;
    }

    /**
     * 결과 합계.
     *
     * <p>성공률의 분모는 <b>끝난 것</b>(성공+실패+롤백)이다. 진행 중인 건을 분모에 넣으면
     * 배포가 몰린 시간대마다 성공률이 떨어지는 것처럼 보인다 — 아직 실패한 게 아닌데도.
     */
    @Getter
    @Builder
    public static class Outcome {
        private long succeeded;
        private long failed;
        private long rolledBack;
        private long inProgress;
        private long cancelled;

        public long getFinished() {
            return succeeded + failed + rolledBack;
        }

        /** 성공률(%). 끝난 게 없으면 -1 — 화면은 이때 숫자 대신 "데이터 없음"을 보여야 한다. */
        public int getSuccessRate() {
            long finished = getFinished();
            return finished == 0 ? -1 : (int) Math.round(succeeded * 100.0 / finished);
        }
    }
}
