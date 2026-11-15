package com.portfolio.deployops.domain;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerGroup {

    private Long id;
    private String name;
    private ServerEnvironment environment;

    @Builder.Default
    private int rollingIntervalSec = 30;

    /** Minimum healthy targets of each role kept available during replacement. Zero permits outage. */
    @Builder.Default
    private int minAvailable = 1;
    /** The executor currently replaces one target at a time. */
    @Builder.Default
    private int maxUnavailable = 1;
    @Builder.Default
    private int observationSeconds = 30;
    @Builder.Default
    private GroupFailurePolicy failurePolicy = GroupFailurePolicy.STOP;

    /** 화면 표시 순서. 배포 순서가 아니라 목록에 뿌리는 순서다(배포 순서는 Server.orderIndex). */
    private int displayOrder = 0;

    private String description;
    /** Null adds no group approval requirements; project approval policy still applies. */
    private Long approvalPolicyId;
    /** External L4 VIP. Individual L4 members are not controlled by Deploy. */
    private Boolean wasVipEnabled;
    private String wasVipAddress;

    @Builder.Default
    private List<Server> servers = new ArrayList<>();
}
