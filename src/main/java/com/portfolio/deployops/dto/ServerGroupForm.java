package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.ServerEnvironment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerGroupForm {
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 100)
    private String name;
    @jakarta.validation.constraints.NotNull
    private ServerEnvironment environment;
    @Min(value = 0, message = "서버 간 배포 간격은 0초 이상이어야 합니다.")
    @Max(value = 600, message = "서버 간 배포 간격은 600초 이하여야 합니다.")
    private int rollingIntervalSec = 30;
    @Min(0) @Max(1000)
    private int minAvailable = 1;
    @Min(1) @Max(1)
    private int maxUnavailable = 1;
    @Min(0) @Max(600)
    private int observationSeconds = 30;
    @jakarta.validation.constraints.NotNull
    private com.portfolio.deployops.domain.GroupFailurePolicy failurePolicy =
        com.portfolio.deployops.domain.GroupFailurePolicy.STOP;
    private String description;
    @Min(1)
    private Long approvalPolicyId;
    private Boolean wasVipEnabled;
    @jakarta.validation.constraints.Size(max = 64)
    private String wasVipAddress;
}
