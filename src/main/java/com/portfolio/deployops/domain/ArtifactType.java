package com.portfolio.deployops.domain;

/**
 * 배포 단위를 대상 서버에 어떤 방식으로 올리는가.
 *
 * <p>이름은 코드에서 쓰는 값이고, 화면에는 label 을 보여 준다 —
 * STATIC_BUNDLE 만 보고 무엇을 하는지 알 수 있는 사람은 이걸 만든 사람뿐이다.
 */
public enum ArtifactType {

    CONTAINER("컨테이너로 실행"),
    STATIC_BUNDLE("화면 파일만 올리기"),
    PROXY_RELOAD("올린 뒤 웹서버 재적용");

    private final String label;

    ArtifactType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
