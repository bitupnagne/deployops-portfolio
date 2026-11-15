package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Detects common source layouts and produces safe, reviewable build-file previews. */
@Service
public class BuildProfileService {

    public Map<String, Object> inspect(Map<String, String> files, Project project) {
        return inspect(files, project, null);
    }

    public Map<String, Object> inspect(Map<String, String> files, Project project, String sourceType) {
        Map<String, String> source = files == null ? Map.of() : files;
        BuildToolType tool = detect(source);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buildTool", tool.name());
        result.put("detectedFiles", source.keySet().stream().sorted().toList());
        result.put("hasDockerfile", source.keySet().stream().anyMatch(p -> p.equalsIgnoreCase("Dockerfile")
            || p.toLowerCase().endsWith("/dockerfile")));
        result.put("hasGitlabCi", source.keySet().stream().anyMatch(p -> p.equalsIgnoreCase(".gitlab-ci.yml")));
        result.putAll(new SourceBuildProfileService().inspect(source, project, sourceType));
        if (result.get("hasDockerfile").equals(true) && (sourceType == null || sourceType.isBlank())) result.remove("generatedDockerfile");
        return result;
    }

    public BuildToolType detect(Map<String, String> files) {
        if (files == null || files.isEmpty()) return BuildToolType.NONE;
        if (files.keySet().stream().anyMatch(p -> p.equalsIgnoreCase("pom.xml"))) return BuildToolType.MAVEN;
        if (files.keySet().stream().anyMatch(p -> p.equalsIgnoreCase("build.gradle")
            || p.equalsIgnoreCase("build.gradle.kts"))) return BuildToolType.GRADLE;
        if (files.keySet().stream().anyMatch(p -> p.equalsIgnoreCase("package.json"))) return BuildToolType.NPM;
        long cFiles = files.keySet().stream().filter(p -> p.toLowerCase().endsWith(".c")).count();
        return cFiles == 1 ? BuildToolType.C : BuildToolType.NONE;
    }

    public String generateDockerfile(BuildToolType tool) {
        return generateDockerfile(tool, null);
    }

    public String generateDockerfile(BuildToolType tool, Project project) {
        return switch (tool == null ? BuildToolType.NONE : tool) {
            case C -> """
                FROM %s AS builder
                RUN dnf install -y gcc && dnf clean all
                WORKDIR /src
                COPY . .
                RUN gcc %s -o app %s

                FROM %s
                COPY --from=builder /src/app /opt/app/app
                RUN chmod 0755 /opt/app/app
                ENTRYPOINT [\"/opt/app/app\"]
                """.formatted(
                    safeImage(project != null ? project.getRuntimeBaseImage() : null, "rockylinux:8.10"),
                    safeCompilerOptions(project != null ? project.getCompilerOptions() : null),
                    safePath(project != null ? project.getSourceEntryPath() : null, "*.c"),
                    safeImage(project != null ? project.getRuntimeBaseImage() : null, "rockylinux:8.10"));
            case MAVEN -> """
                FROM eclipse-temurin:21-jdk AS builder
                WORKDIR /src
                COPY . .
                RUN ./mvnw -B clean package -DskipTests
                FROM eclipse-temurin:21-jre
                COPY --from=builder /src/target/*.jar /opt/app/app.jar
                ENTRYPOINT [\"java\",\"-jar\",\"/opt/app/app.jar\"]
                """;
            case GRADLE -> """
                FROM eclipse-temurin:21-jdk AS builder
                WORKDIR /src
                COPY . .
                RUN ./gradlew clean build -x test
                FROM eclipse-temurin:21-jre
                COPY --from=builder /src/build/libs/*.jar /opt/app/app.jar
                ENTRYPOINT [\"java\",\"-jar\",\"/opt/app/app.jar\"]
                """;
            default -> "";
        };
    }

    private String safeImage(String value, String fallback) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9./:_-]+")) return fallback;
        return value;
    }

    private String safePath(String value, String fallback) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_./*+-]+")) return fallback;
        return value;
    }

    private String safeCompilerOptions(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_./+*=:-]+(?:\\s+[A-Za-z0-9_./+*=:-]+)*")) {
            return "-O2 -Wall -Wextra";
        }
        return value;
    }

    private Map<String, String> suggestions(Map<String, String> files, BuildToolType tool) {
        Map<String, String> out = new LinkedHashMap<>();
        switch (tool) {
            case C -> {
                String c = files.keySet().stream().filter(p -> p.toLowerCase().endsWith(".c")).findFirst().orElse("main.c");
                out.put("buildCommand", "gcc -O2 -Wall -Wextra -o app " + c);
                out.put("artifactPath", "app");
            }
            case MAVEN -> { out.put("buildFilePath", "pom.xml"); out.put("buildCommand", "./mvnw -B clean package -DskipTests"); }
            case GRADLE -> { out.put("buildFilePath", files.keySet().stream().anyMatch(p -> p.equalsIgnoreCase("build.gradle.kts")) ? "build.gradle.kts" : "build.gradle"); out.put("buildCommand", "./gradlew clean build -x test"); }
            case NPM -> { out.put("buildFilePath", "package.json"); out.put("buildCommand", "npm ci && npm run build"); }
            default -> { }
        }
        return out;
    }
}
