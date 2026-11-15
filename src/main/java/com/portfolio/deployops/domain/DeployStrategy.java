package com.portfolio.deployops.domain;

/**
 * 배포 전략 — 선택된 대상 서버를 어떤 순서/방식으로 배포할지.
 */
public enum DeployStrategy {
    /** A. 안전(기본): WAS 전부 → WEB 전부 (was1→was2→web1→web2), 1버튼 자동. */
    SAFE,
    /** B. 호기별 웨이브: 같은 호기끼리 묶어 (was1+web1) → (was2+web2). */
    WAVE,
    /** C. 수동: 운영자가 서버 단위로 클릭해 배포(스텝 실행 — Phase 3-B). */
    MANUAL
}
