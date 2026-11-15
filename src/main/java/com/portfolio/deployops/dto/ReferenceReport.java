package com.portfolio.deployops.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "왜 지울 수 없는지" 를 화면이 그릴 수 있는 형태로 담는다.
 *
 * <p>지금까지는 삭제를 막을 때 문장 하나만 돌려줬다 —
 * {@code "'PROD' 그룹을 배포 대상으로 쓰는 프로젝트가 있어 삭제할 수 없습니다: A, B"}.
 * 사실 관계는 맞지만 거기서 할 수 있는 일이 없다. A 가 어느 프로젝트인지 찾아
 * 화면을 옮겨 다녀야 하고, 그러다 보면 B 를 잊는다. 이름이 겹치면 어느 쪽인지도 모른다.
 *
 * <p>그래서 <b>막는 대상 하나하나를 항목으로</b> 돌려준다. 화면은 이것으로 목록을 그리고
 * 각 항목에서 해당 화면으로 바로 이동시킨다. 문장은 여전히 있지만, 그건 요약일 뿐이다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceReport {

    /** 지우려던 대상의 종류(server · server-group · policy). */
    private String type;
    private Long id;
    private String name;

    /** 막는 것이 하나도 없으면 true — 화면은 "지금 삭제" 버튼을 살릴 수 있다. */
    private boolean deletable;

    /** 왜 막히는지, 종류별로 묶어서. */
    private List<Blocker> blockers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Blocker {
        /** 사람이 읽는 제목. 예) "이 그룹을 배포 대상으로 쓰는 프로젝트". */
        private String title;
        /** 왜 이것이 삭제를 막는가. */
        private String why;
        /** 무엇을 하면 풀리는가. */
        private String howTo;
        /** 항목을 눌렀을 때 갈 화면 id({@code navigate()} 인자). null 이면 이동 불가. */
        private String page;
        /** 전체 건수. {@code items} 는 앞부분만 담을 수 있다. */
        private long total;
        private List<Item> items;
        /**
         * 사람이 화면에서 직접 치울 수 있는 종류인가.
         *
         * <p>배포 실행 기록처럼 <b>일부러 남기는 감사 자료</b>는 false 다. 그런 것에
         * "가서 지우세요" 라고 안내하면 지우지 말아야 할 것을 지우게 만든다.
         */
        private boolean resolvable;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private String name;
        /** 목록에서 같은 이름을 구분해 주는 보조 정보(환경 · 날짜 등). */
        private String detail;
    }
}
