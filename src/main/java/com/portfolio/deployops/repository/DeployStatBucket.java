package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployStatus;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 대시보드 차트용 집계 한 칸 — {@code (날짜, 환경, 상태) → 건수}.
 *
 * <p>MyBatis 가 채우므로 기본 생성자와 setter 가 필요하다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeployStatBucket {

    private LocalDate day;
    private DeployEnvironment environment;
    private DeployStatus status;
    private long count;
}
