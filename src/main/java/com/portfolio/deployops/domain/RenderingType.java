package com.portfolio.deployops.domain;

/** 화면을 누가 그리는가. 약어만으로는 뜻이 전해지지 않아 한글 문구를 함께 둔다. */
public enum RenderingType {

    SSR("서버가 화면을 그림"),
    CSR("브라우저가 화면을 그림"),
    API_ONLY("화면 없음(API 만)"),
    STATIC_ONLY("정적 파일만"),
    HYBRID("서버·브라우저 혼합");

    private final String label;

    RenderingType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
