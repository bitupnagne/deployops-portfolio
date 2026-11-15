package com.portfolio.deployops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeployRequestCreateForm {

    @NotNull(message = "프로젝트를 선택해 주세요.")
    private Long projectId;

    @NotBlank(message = "배포 환경을 선택해 주세요.")
    private String environment;

    @NotBlank(message = "배포 방식을 선택해 주세요.")
    private String deployMode;

    /** 배포 범위: ALL | WAS_ONLY | WEB_ONLY (미지정 시 ALL). */
    private String deployScope;

    /** 배포 전략: SAFE | WAVE | MANUAL (미지정 시 SAFE). */
    private String deployStrategy;

    /** Empty means all mapped groups of the requested environment; creation pins the actual IDs. */
    @Size(max = 1000)
    private java.util.List<@NotNull @Positive Long> serverGroupIds;

    /** 컴포넌트별 산출물(CSR 등). 비어 있으면 요청 단일 이미지로 동작. */
    private java.util.List<ComponentImageForm> componentImages;

    @Size(max = 200, message = "브랜치 또는 태그는 200자 이하로 입력해 주세요.")
    private String branch;

    @NotBlank(message = "배포 사유를 입력해 주세요.")
    @Size(min = 10, max = 1000, message = "배포 사유는 10자 이상 1000자 이하로 입력해 주세요.")
    private String reason;

    @Positive(message = "GitLab Pipeline ID는 1 이상이어야 합니다.")
    private Long gitlabPipelineId;

    @Size(max = 64, message = "Commit SHA는 64자 이하로 입력해 주세요.")
    private String commitSha;

    @Size(max = 255, message = "이미지 태그는 255자 이하로 입력해 주세요.")
    private String imageTag;

    @Size(max = 255, message = "이미지 Digest는 255자 이하로 입력해 주세요.")
    private String imageDigest;

    /** PROD 승격에 사용할 TEST_CONFIRMED 요청. 화면에서 선택한 산출물과 서버 사용 산출물을 일치시킨다. */
    @Positive(message = "TEST 확인 요청 ID는 1 이상이어야 합니다.")
    private Long promotionSourceRequestId;

    /** 병합 증적을 서버에서 조회해 정확한 결과 SHA만 빌드하기 위한 MR IID. */
    @Positive(message = "MR IID는 1 이상이어야 합니다.")
    private Long sourceMergeRequestIid;
}
