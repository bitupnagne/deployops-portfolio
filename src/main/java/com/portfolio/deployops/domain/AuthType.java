package com.portfolio.deployops.domain;

/** 대상 서버에 SSH 로 붙을 때 무엇으로 인증하는가. */
public enum AuthType {

    SSH_KEY("SSH 키"),
    PASSWORD("비밀번호");

    private final String label;

    AuthType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
