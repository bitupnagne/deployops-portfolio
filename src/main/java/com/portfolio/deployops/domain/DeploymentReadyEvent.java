package com.portfolio.deployops.domain;

/** 커밋 이후 자동 실행 여부를 평가할 수 있게 하는 가벼운 도메인 이벤트. */
public record DeploymentReadyEvent(Long deployRequestId) { }
