package com.portfolio.deployops.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DashboardSummaryDto {
    private long todayRequestCount;
    private long pendingApprovalCount;
    private long deployingCount;
    private long todayFailureCount;
}
