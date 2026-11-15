package com.portfolio.deployops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NginxPolicyForm {
    /** 관리 헤더를 제외한 HTTP context의 upstream/server 원문. 미지정 시 기존 필드 방식을 유지한다. */
    // multipart의 CRLF 확장을 허용하고 서비스에서 LF 정규화 후 UTF-8 60KB를 검사한다.
    @Size(max = 122880)
    private String rawConfig;

    private boolean upstreamListPresent;
    private Long wasVipProjectId;
    private Long wasVipGroupId;
    @jakarta.validation.Valid
    @Size(max=32)
    private java.util.List<NginxUpstreamForm> upstreams;

    @NotNull
    private Long serverId;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private String policyName;

    @Pattern(regexp = "REVERSE_PROXY|STATIC_SPA|STATIC_FILES")
    private String policyType = "REVERSE_PROXY";

    @NotBlank
    @Size(max = 255)
    private String serverName;

    @Min(1) @Max(65535)
    private int listenPort = 443;

    @Size(max = 255)
    private String upstreamHost;

    @Min(1) @Max(65535)
    private int upstreamPort = 8080;
    @Setter(lombok.AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean legacyUpstreamPortPresent;
    public void setUpstreamPort(int value) { upstreamPort=value; legacyUpstreamPortPresent=true; }


    private boolean secondaryUpstreamEnabled;
    @Size(max = 255)
    private String secondaryUpstreamHost;
    @Min(1) @Max(65535)
    private Integer secondaryUpstreamPort;
    @Min(1) @Max(10)
    private int upstreamMaxFails = 1;
    @Min(1) @Max(300)
    private int upstreamFailTimeoutSec = 10;
    @Min(1) @Max(30)
    private int proxyConnectTimeoutSec = 3;

    @Size(max = 500)
    @Pattern(regexp = "^$|/[A-Za-z0-9._/+:-]+")
    private String webRoot;

    private boolean apiProxyEnabled;

    @Size(max = 80)
    private String apiPrefix;

    @Size(max = 255)
    private String apiUpstreamHost;

    @Min(1) @Max(65535)
    private Integer apiUpstreamPort;
    @Setter(lombok.AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean legacyApiUpstreamPortPresent;
    public void setApiUpstreamPort(Integer value) { apiUpstreamPort=value; legacyApiUpstreamPortPresent=true; }


    private boolean websocketEnabled;
    private boolean tlsEnabled;
    private boolean tls12 = true;
    private boolean tls13 = true;
    private boolean http2Enabled = true;
    private boolean redirectHttp = true;
    private boolean hstsEnabled = true;

    @Min(0) @Max(10240)
    private int clientMaxBodyMb = 50;

    @Min(1) @Max(3600)
    private int proxyReadTimeoutSec = 300;

    @Min(1) @Max(3600)
    private int proxySendTimeoutSec = 300;

    private boolean proxyBuffering = true;
    private boolean securityHeaders = true;

    @Size(max = 1000)
    private String allowedCidrs;

    @Size(max = 120)
    @Pattern(regexp = "[A-Za-z0-9._-]*")
    private String certificateName;
}
