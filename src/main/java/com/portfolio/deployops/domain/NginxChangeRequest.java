package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NginxChangeRequest {
    private Long id;
    @Builder.Default
    private java.util.List<NginxUpstream> upstreamSnapshots = new java.util.ArrayList<>();

    public java.util.List<NginxUpstream> getEffectiveUpstreams() {
        if (restoreAbsent) return java.util.List.of();
        String purpose = policyType == NginxPolicyType.REVERSE_PROXY ? "PROXY" : "API";
        if (upstreamSnapshots != null && !upstreamSnapshots.isEmpty())
            return upstreamSnapshots.stream().filter(x -> purpose.equals(x.getPurpose())).toList();
        if (policyType != NginxPolicyType.REVERSE_PROXY && !apiProxyEnabled) return java.util.List.of();
        var result = new java.util.ArrayList<NginxUpstream>();
        result.add(new NginxUpstream(purpose, 0, null,
            policyType == NginxPolicyType.REVERSE_PROXY ? upstreamHost : apiUpstreamHost,
            policyType == NginxPolicyType.REVERSE_PROXY ? upstreamPort : apiUpstreamPort));
        if (hasSecondaryUpstream()) result.add(new NginxUpstream(purpose, 1, null, secondaryUpstreamHost, secondaryUpstreamPort));
        return java.util.List.copyOf(result);
    }

    private Server server;
    private ServerControlMode requestedControlMode;
    private String requestedAgentBindingId;
    private User requestedBy;
    private User approvedBy;
    private Long rollbackOfId;
    private NginxChangeStatus status;
    private String policyName;
    private String rawConfig;
    private String rawConfigSha256;
    @Builder.Default
    private NginxPolicyType policyType = NginxPolicyType.REVERSE_PROXY;
    private String serverName;
    private int listenPort;
    private String upstreamHost;
    private int upstreamPort;
    private String secondaryUpstreamHost;
    private Integer secondaryUpstreamPort;
    @Builder.Default
    private int upstreamMaxFails = 1;
    @Builder.Default
    private int upstreamFailTimeoutSec = 10;
    @Builder.Default
    private int proxyConnectTimeoutSec = 3;

    public boolean hasSecondaryUpstream() {
        return secondaryUpstreamHost != null && !secondaryUpstreamHost.isBlank();
    }
    private String webRoot;
    private boolean apiProxyEnabled;
    private String apiPrefix;
    private String apiUpstreamHost;
    private Integer apiUpstreamPort;
    private boolean websocketEnabled;
    private boolean tlsEnabled;
    private String tlsProtocols;
    private boolean http2Enabled;
    private boolean redirectHttp;
    private boolean hstsEnabled;
    private int clientMaxBodyMb;
    private int proxyReadTimeoutSec;
    private int proxySendTimeoutSec;
    private boolean proxyBuffering;
    private boolean securityHeaders;
    private String allowedCidrs;
    private String certificateName;
    private String certificateFileName;
    private String privateKeyFileName;
    private String storageKey;
    private String backupPath;
    /** Native bytes, not a copy of the policy being undone. Immutable on pending requests. */
    private String backupSnapshotHash;
    private String appliedSnapshotHash;
    private String appliedConfigHash;
    private Long nativeRequestId;
    private String rollbackSnapshotHash;
    private Long expectedNativeRequestId;
    private String expectedCurrentSnapshotHash;
    private Long restoreTargetId;
    private boolean restoreAbsent;
    private String errorMessage;
    /** 적용된 인증서의 신원·만료. 에이전트가 apply 성공 시 돌려주는 값이다. */
    private String certificateSubject;
    private String certificateIssuer;
    private String certificateSans;
    private LocalDateTime certificateNotAfter;
    /** 이미 알린 만료 임계값(30/14/7). 같은 단계에서 반복 알림하지 않기 위한 표시. */
    private Integer certificateExpiryNotifiedDays;
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime appliedAt;
}
