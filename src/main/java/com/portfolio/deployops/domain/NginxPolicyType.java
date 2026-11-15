package com.portfolio.deployops.domain;

public enum NginxPolicyType {
    REVERSE_PROXY("리버스 프록시"),
    STATIC_SPA("정적 SPA"),
    STATIC_FILES("정적 파일");

    private final String label;

    NginxPolicyType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
