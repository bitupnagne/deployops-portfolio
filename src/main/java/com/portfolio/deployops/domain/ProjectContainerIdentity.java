package com.portfolio.deployops.domain;

import java.util.Locale;

/** Runtime identity must not depend on mutable, potentially non-ASCII display names. */
public final class ProjectContainerIdentity {
    private ProjectContainerIdentity() { }

    public static String canonical(Long projectId) {
        if (projectId == null || projectId <= 0) throw new IllegalArgumentException("저장된 프로젝트 ID가 필요합니다.");
        return "deploy-project-" + projectId;
    }

    public static String resolve(Project project) {
        if (project.getContainerName() != null) {
            String name = project.getContainerName();
            if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"))
                throw new IllegalStateException("프로젝트 컨테이너 식별자가 올바르지 않습니다.");
            return name;
        }
        String raw = project.getName() != null ? project.getName() : "deploy-app";
        String sanitized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        sanitized = sanitized.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return sanitized.isBlank() ? "deploy-app" : sanitized;
    }
}
