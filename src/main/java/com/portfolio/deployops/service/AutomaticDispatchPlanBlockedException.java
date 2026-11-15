package com.portfolio.deployops.service;

/** 재시도로 해결되지 않는 snapshot 누락·무결성 실패·현재 계획 drift를 자동 스캔에서 영구 제외한다. */
public class AutomaticDispatchPlanBlockedException extends RuntimeException {

    public AutomaticDispatchPlanBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
