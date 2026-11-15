package com.portfolio.deployops.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 이 프로젝트를 지금 배포할 수 있는가, 없다면 무엇이 남았는가.
 *
 * <p>프로젝트 상세에는 입력 칸이 46개 있는데 그중 <b>첫 배포를 실제로 막는 것은 둘</b>이다
 * (이미지 저장소 · 서버 그룹 매핑). 나머지는 기본값이 있거나 나중에 고쳐도 된다.
 * 그런데 화면은 46개를 모두 같은 무게로 늘어놓아서, 새로 만든 프로젝트 앞에 앉으면
 * 무엇부터 해야 하는지 알 수 없다. {@code ProjectForm} 에는 검증 애너테이션이 하나도 없어
 * 서버도 알려 주지 않는다 — 결국 배포를 눌러 보고 실패해야 알게 된다.
 *
 * <p>그래서 "무엇이 남았는지" 를 화면이 묻지 않아도 먼저 말해 준다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectReadiness {

    /** 막는 것이 없으면 true. 경고는 있어도 true 일 수 있다. */
    private boolean deployable;

    /** 배포를 막는 항목 수. 배지에 그대로 쓴다. */
    private int blockingCount;

    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** BLOCKER 없으면 배포 자체가 안 된다 · WARNING 되긴 하는데 위험하다 · DONE 끝난 것. */
        private String level;
        private String title;
        /** 왜 필요한지 — 시키는 이유를 같이 준다. */
        private String why;
        /** 이 항목을 채우는 탭 id({@code data-tab} 값). 화면이 그 탭을 열어 준다. */
        private String anchor;
    }
}
