package com.portfolio.deployops.domain;

/**
 * 승인된 실행 계획에 포함하는 전역 배포 런타임 값.
 *
 * <p>주소와 실행 옵션만 담고 비밀번호·토큰·SSH 키 내용은 절대 담지 않는다. 자격증명은
 * 실행 시점에 암호화된 설정 저장소에서 읽어 회전이 가능하게 유지한다.
 */
public record DeploymentRuntimeSettings(
    String imageTransfer,
    String imageStagingDirectory,
    String staticImagePath,
    String containerMemory,
    String nexusUrl,
    String nexusRegistry,
    String nexusRegistryApiUrl,
    boolean nexusPipelineCredentials,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    String nexusMavenBaseUrl
) {
    public DeploymentRuntimeSettings(String imageTransfer, String imageStagingDirectory,
        String staticImagePath, String containerMemory, String nexusUrl, String nexusRegistry,
        String nexusRegistryApiUrl, boolean nexusPipelineCredentials) {
        this(imageTransfer, imageStagingDirectory, staticImagePath, containerMemory, nexusUrl,
            nexusRegistry, nexusRegistryApiUrl, nexusPipelineCredentials, null);
    }
}
