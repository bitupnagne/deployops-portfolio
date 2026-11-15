package com.portfolio.deployops.service;

import java.util.Set;

/** Path validation is shared by browser requests, persisted bindings and outbound Nexus requests. */
public final class NexusDependencyPolicy {
    private NexusDependencyPolicy() {}

    public static String repository(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,60}"))
            throw new IllegalArgumentException("저장소 이름이 올바르지 않습니다.");
        return value;
    }

    public static String format(String value) {
        if (!Set.of("maven2", "raw").contains(value == null ? "" : value))
            throw new IllegalArgumentException("파일 반입은 Maven2·RAW hosted 저장소만 지원합니다. npm은 npm publish, Docker는 CI 이미지 push를 사용하세요.");
        return value;
    }

    public static String path(String value) {
        if (value == null || value.isBlank() || value.length() > 1000 || value.startsWith("/")
                || value.endsWith("/") || value.contains("\\") || value.contains("%"))
            throw new IllegalArgumentException("저장소 상대 경로가 올바르지 않습니다.");
        for (String part : value.split("/", -1)) {
            if (part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9_+.,=@-]+"))
                throw new IllegalArgumentException("경로에는 영문·숫자와 . _ - + , = @ 만 사용할 수 있으며 상위 경로는 허용하지 않습니다.");
        }
        return value;
    }

    public static String target(String format, String namespace, String relativePath) {
        format(format);
        String path = path(relativePath);
        String prefix = namespace == null ? "" : namespace.trim();
        if (!prefix.isEmpty()) {
            path(prefix);
            // Maven coordinates must remain unchanged. Namespace is an allow-list, not a prepended directory.
            if ("maven2".equals(format)) {
                if (!path.startsWith(prefix + "/"))
                    throw new IllegalArgumentException("Maven 경로는 지정한 groupId 경로(" + prefix + "/)로 시작해야 합니다.");
            } else {
                path = prefix + "/" + path;
            }
        }
        return path(path);
    }

    public static void chunk(long total, long offset, long received, long length, long maximum) {
        if (total <= 0 || offset < 0 || offset != received || length <= 0 || length > maximum
                || offset > total || length > total - offset)
            throw new IllegalArgumentException("청크 위치 또는 길이가 일치하지 않습니다. 진행 상태를 새로 조회해 재개하세요.");
    }
}
