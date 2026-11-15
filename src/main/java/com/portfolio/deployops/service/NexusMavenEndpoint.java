package com.portfolio.deployops.service;

import java.net.URI;

/** Runner-facing repository origin, distinct from DeployOps' Nexus management route. */
public final class NexusMavenEndpoint {
    private NexusMavenEndpoint() {}

    public static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return "";
        String input = value.trim();
        try {
            URI uri = URI.create(input);
            String scheme = uri.getScheme();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !path.matches("(?:/[A-Za-z0-9._~-]*)*")
                || !uri.normalize().getRawPath().equals(uri.getRawPath())
                || java.util.Arrays.stream(path.split("/")).anyMatch(part -> ".".equals(part) || "..".equals(part)))
                throw new IllegalArgumentException();
            return input.replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Runner Maven URL은 사용자 정보·쿼리·fragment 없이 http(s)://호스트:포트[/경로] 형식으로 입력하세요.");
        }
    }

    public static String resolve(String configured, String fallback) {
        String value = normalizeOptional(configured);
        if (!value.isBlank()) return value;
        value = normalizeOptional(fallback);
        if (value.isBlank()) throw new IllegalArgumentException("통합설정에 Nexus URL 또는 Runner Maven URL을 입력하세요.");
        return value;
    }
}
