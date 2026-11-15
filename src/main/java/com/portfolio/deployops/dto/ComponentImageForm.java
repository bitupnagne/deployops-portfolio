package com.portfolio.deployops.dto;

import lombok.Getter;
import lombok.Setter;

/** 요청 생성 시 컴포넌트별 산출물(이미지/번들) 지정. CSR 등 다중 컴포넌트용. */
@Getter
@Setter
public class ComponentImageForm {

    private Long componentId;
    private String componentCode;
    private String imageTag;
    private String imageDigest;
    private String bundleRef;
}
