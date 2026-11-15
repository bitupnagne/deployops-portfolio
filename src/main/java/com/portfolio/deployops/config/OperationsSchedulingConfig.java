package com.portfolio.deployops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 공개 데모는 실제 서버를 조회하거나 실행하는 예약 작업을 등록하지 않는다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "false", matchIfMissing = true)
public class OperationsSchedulingConfig {
}
