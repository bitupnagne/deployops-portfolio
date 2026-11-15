package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.BuildToolType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildProfileServiceTest {
    private final BuildProfileService service = new BuildProfileService();

    @Test
    void detectsSingleCSourceAndProvidesBuildSettings() {
        Map<String, Object> profile = service.inspect(Map.of("main.c", "int main(){return 0;}"), null);

        assertThat(profile).containsEntry("buildTool", BuildToolType.C.name());
        assertThat(String.valueOf(((Map<?, ?>) profile.get("suggestions")).get("buildCommand")))
            .isEqualTo("gcc -O2 -Wall -Wextra -o app main.c");
        assertThat((String) profile.get("generatedDockerfile"))
            .contains("COPY app /opt/app/app", "ARG RUNTIME_BASE_IMAGE", "ENTRYPOINT")
            .doesNotContain("dnf install", "apt-get", "FROM rockylinux");
    }

    @Test
    void existingDockerfileAndCiAreNeverReplacedByGeneratedPreview() {
        Map<String, Object> profile = service.inspect(Map.of(
            "Dockerfile", "FROM alpine",
            ".gitlab-ci.yml", "build:\n  script: [echo ok]",
            "main.c", "int main(){return 0;}"), null);

        assertThat(profile).containsEntry("hasDockerfile", true).containsEntry("hasGitlabCi", true);
        assertThat(profile).doesNotContainKey("generatedDockerfile");
    }

    @Test
    void detectsCommonJavaAndNodeBuildFiles() {
        assertThat(service.detect(Map.of("pom.xml", "<project/>"))).isEqualTo(BuildToolType.MAVEN);
        assertThat(service.detect(Map.of("build.gradle.kts", "plugins {}"))).isEqualTo(BuildToolType.GRADLE);
        assertThat(service.detect(Map.of("package.json", "{}"))).isEqualTo(BuildToolType.NPM);
    }
}
