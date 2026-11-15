package com.portfolio.deployops.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 프로젝트의 배포 단위. 프로젝트는 1..N개의 컴포넌트를 가지며(예: api, web),
 * 각 컴포넌트가 "무엇을(이미지/정적/프록시), 어느 역할(WAS/WEB)로, 어떻게(헬스체크)" 배포할지 안다.
 * SSR = api(CONTAINER,WAS) [+ web(PROXY_RELOAD,WEB)] / CSR = api(CONTAINER,WAS) + web(STATIC_BUNDLE,WEB).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeployComponent {

    private Long id;
    private Long projectId;

    /** 프로젝트 내 유일 코드(예: api, web, admin). */
    private String code;
    private String displayName;

    /** 배포 대상 역할(WAS/WEB) — 서버 타입과 매핑. */
    private ServerType role;

    private ArtifactType artifactType;

    /** CONTAINER/STATIC_BUNDLE(이미지화 시) 이미지 저장소. */
    private String imageRepository;
    /** STATIC_BUNDLE 산출물을 풀어 둘 nginx webroot. */
    private String webRoot;

    private BuildToolType buildTool;
    private String dockerfilePath;
    private String dockerContextPath;
    private String buildCommand;
    private String artifactPath;

    @Builder.Default
    private HealthCheckMode healthCheckMode = HealthCheckMode.SSH_LOCAL_CURL;
    private String healthCheckPath;
    private Integer healthCheckPort;
    /** WEB PROXY_RELOAD 전용 HTTP Host 헤더. null이면 기존 loopback Host를 사용한다. */
    private String proxyHealthHost;
    /** 환경별 선택값. null이면 공통 Host를 사용한다. */
    private String proxyHealthHostTest;
    private String proxyHealthHostProd;

    public String resolveProxyHealthHost(DeployEnvironment environment) {
        String override = environment == DeployEnvironment.TEST ? proxyHealthHostTest
            : environment == DeployEnvironment.PROD ? proxyHealthHostProd : null;
        return override == null || override.isBlank() ? proxyHealthHost : override;
    }
    private String containerUser;
    private boolean containerPrivileged = false;
    private String containerCapAdd;
    private boolean containerReadOnlyRootFs = false;

    /** 컴포넌트 간 기본 배포 순서(작을수록 먼저, 예: api=10, web=20). */
    @Builder.Default
    private int deployOrder = 0;

    @Builder.Default
    private boolean active = true;
}
