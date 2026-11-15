package com.portfolio.deployops.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 배포 요청의 컴포넌트별 산출물. CSR처럼 api·web 컴포넌트가 서로 다른 이미지를 쓸 때 사용한다.
 * 항목이 없으면 엔진은 요청의 단일 이미지로 폴백한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeployRequestItem {

    private Long id;
    private Long deployRequestId;
    private Long componentId;
    private String componentCode;
    private String imageTag;
    private String imageDigest;
    /** STATIC_BUNDLE 산출물 참조(번들 경로/주소). */
    private String bundleRef;
}
