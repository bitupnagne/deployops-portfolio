package com.portfolio.deployops.domain;

/** 서버가 무슨 일을 하는 장비인가. 표에 그대로 찍히므로 한글 문구를 함께 둔다. */
public enum ServerType {

    DEPLOY("배포시스템"),
    GITLAB("GitLab"),
    NEXUS("Nexus"),
    RUNNER("GitLab Runner"),
    WAS("앱 서버"),
    WEB("웹 서버"),
    DB("데이터베이스"),
    BATCH("배치");

    private final String label;

    ServerType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
