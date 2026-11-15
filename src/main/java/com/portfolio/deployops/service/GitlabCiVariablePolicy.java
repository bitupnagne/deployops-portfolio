package com.portfolio.deployops.service;

import java.util.List;

/** UI에서 바꿀 수 있는 CI 계약 변수와 강제 보안 속성. 임의의 CI 변수는 지원하지 않는다. */
public final class GitlabCiVariablePolicy {
    public static final List<String> KEYS = List.of(
        "NEXUS_REGISTRY_USER", "NEXUS_REGISTRY_PASSWORD", "DOCKER_BUILD_IMAGE",
        "CI_BUILD_IMAGE", "MAVEN_BUILD_IMAGE", "MAVEN_SETTINGS_FILE",
        "NEXUS_MAVEN_USER", "NEXUS_MAVEN_PASSWORD", "RUNTIME_BASE_IMAGE");

    private GitlabCiVariablePolicy() {}

    public static void requireKey(String key) {
        if (key == null || !KEYS.contains(key)) {
            throw new IllegalArgumentException("지원하지 않는 CI 변수입니다.");
        }
    }

    public static boolean masked(String key) {
        requireKey(key);
        return key.endsWith("_PASSWORD");
    }

    public static String variableType(String key) {
        requireKey(key);
        return "MAVEN_SETTINGS_FILE".equals(key) ? "file" : "env_var";
    }

    public static void validateValue(String key, String value) {
        requireKey(key);
        if (value == null || value.isBlank() || value.length() > 10000 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("변수 값은 공백이 아닌 1~10,000자로 입력해 주세요.");
        }
        if (masked(key) && (value.length() < 8 || value.codePoints().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException("비밀번호 변수는 공백 없는 한 줄로 8자 이상 입력해 주세요.");
        }
        if (!"file".equals(variableType(key)) && (value.contains("\n") || value.contains("\r"))) {
            throw new IllegalArgumentException("일반 변수는 한 줄로 입력해 주세요.");
        }
    }
}
