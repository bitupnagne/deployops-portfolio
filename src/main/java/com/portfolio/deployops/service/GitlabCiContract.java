package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import java.util.List;
import java.util.regex.Pattern;

/** 생성 CI와 배포 오케스트레이터가 함께 지키는 이름·태그 규칙. */
public final class GitlabCiContract {

    public static final String IMAGE_JOB_NAME = "build_and_push_image";
    public static final String ARTIFACT_FILE = "deploy-image.env";
    public static final String RELEASE_ARTIFACT_FILE = "deploy-release.json";
    /** 폐쇄망 설치기의 기본 GITLAB_RUNNER_TAG_LIST=docker,offline 계약과 같은 공용 태그. */
    public static final String RUNNER_TAG = "offline";
    public static final String DEFAULT_IMAGE_TAG_PATTERN = "${branch}-${shortSha}-${pipelineId}";
    /** Docker distribution reference의 tag 문법: 첫 글자 포함 최대 128자. */
    public static final String IMAGE_TAG_REGEX = "^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$";
    public static final List<String> REQUIRED_PROJECT_VARIABLES = List.of(
        "NEXUS_REGISTRY_USER",
        "NEXUS_REGISTRY_PASSWORD"
    );
    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile(IMAGE_TAG_REGEX);

    private GitlabCiContract() {
    }

    public static String runnerTag() {
        return RUNNER_TAG;
    }

    public static String imageTagPattern(Project project) {
        String configured = project != null ? trimToNull(project.getImageTagPattern()) : null;
        return configured != null ? configured : DEFAULT_IMAGE_TAG_PATTERN;
    }

    public static boolean isValidImageTag(String tag) {
        return tag != null && IMAGE_TAG_PATTERN.matcher(tag).matches();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
