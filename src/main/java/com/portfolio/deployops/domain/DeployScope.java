package com.portfolio.deployops.domain;

/**
 * 운영 배포 범위 — 어떤 역할의 서버까지 배포할지. was/web 독립 패치를 위한 선택값.
 * (api=WAS 역할, web=WEB 역할에 매핑)
 */
public enum DeployScope {
    /** 요청 프로젝트와 환경에 연결된 WAS·WEB 대상 전체. */
    ALL,
    /** api만(WAS 역할 서버만). */
    WAS_ONLY,
    /** web만(WEB 역할 서버만). */
    WEB_ONLY;

    public boolean includes(ServerType serverType) {
        return switch (this) {
            case ALL -> true;
            case WAS_ONLY -> serverType == ServerType.WAS;
            case WEB_ONLY -> serverType == ServerType.WEB;
        };
    }
}
