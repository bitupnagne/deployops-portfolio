package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;

import com.portfolio.deployops.domain.Project;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SourceBuildProfileServiceTest {
    private final SourceBuildProfileService service = new SourceBuildProfileService();
    private static final String BOOT = "<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent><properties><java.version>8</java.version></properties></project>";

    @Test void mavenBootPreservesEvidenceAndRunsTestsWithoutInventingRuntimeVersion() {
        var result = service.inspect(Map.of("pom.xml", BOOT), null, null);
        assertThat(result).containsEntry("sourceType", "MAVEN_BOOT").containsEntry("selectionRequired", false);
        assertThat(settings(result)).containsEntry("buildCommand", "mvn -B -ntp --settings \"$MAVEN_SETTINGS_FILE\" clean verify");
        assertThat(result.get("evidence").toString()).contains("Java=8");
        assertThat((String) result.get("generatedDockerfile")).contains("ARG RUNTIME_BASE_IMAGE", "USER 10001:10001")
            .doesNotContain("temurin", "21", "skipTests");
    }

    @Test void ordinaryPomAndCommentOnlyBootDoNotPretendToBeSpringBoot() {
        for (String pom : new String[]{"<project/>", "<project><!-- <artifactId>spring-boot-starter-web</artifactId> --></project>"}) {
            var result = service.inspect(Map.of("pom.xml", pom), null, null);
            assertThat(result).containsEntry("selectionRequired", true).containsEntry("sourceType", "UNKNOWN");
            assertThat(result).doesNotContainKey("generatedDockerfile");
        }
    }

    @Test void gradleBootIsDeclarativeOnlyAndBuildKeepsTests() {
        var result = service.inspect(Map.of("build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.5.0\" }"), null, null);
        assertThat(result).containsEntry("sourceType", "GRADLE_BOOT");
        assertThat(settings(result)).containsEntry("buildCommand", "./gradlew clean test bootJar");
        assertThat(result.get("warnings").toString()).contains("실행하지 않았습니다", "JDK");
    }

    @Test void warDoesNotAssumeTomcat10OrUnwritableNonrootTomcat() {
        var result = service.inspect(Map.of("pom.xml", "<project><packaging>war</packaging></project>"), null, null);
        assertThat(result).containsEntry("sourceType", "WAR_TOMCAT");
        assertThat((String) result.get("generatedDockerfile")).contains("ROOT.war", "catalina.sh")
            .doesNotContain("tomcat:10", "USER 10001");
        assertThat(result.get("warnings").toString()).contains("javax/jakarta", "Java", "JEUS");
    }

    @Test void gradleWarKeepsGradleArtifactAndCommand() {
        var result = service.inspect(Map.of("build.gradle", "plugins { id 'war' }"), null, null);
        assertThat(result).containsEntry("sourceType", "WAR_TOMCAT");
        assertThat(settings(result)).containsEntry("artifactPath", "build/libs/*.war").containsEntry("buildTool", "GRADLE");
    }

    @Test void reactStaticAndPlainStaticWarnAboutActualOutput() {
        var react = service.inspect(Map.of("package.json", "{\"dependencies\":{\"react\":\"19\"},\"scripts\":{\"build\":\"vite build\"}}"), null, null);
        assertThat(react).containsEntry("sourceType", "REACT_STATIC");
        assertThat(settings(react)).containsEntry("buildCommand", "npm ci && npm run build").containsEntry("artifactPath", "dist");
        assertThat(react.get("warnings").toString()).contains("SSR");
        var plain = service.inspect(Map.of("index.html", "<html></html>"), null, null);
        assertThat(plain).containsEntry("sourceType", "STATIC");
        assertThat(plain.get("warnings").toString()).contains("dist 디렉터리는 예시");
    }

    @Test void procRequiresExecutableJarAndDoesNotClaimArbitraryMainWorks() {
        var result = service.inspect(Map.of("pom.xml", "<project><configuration><mainClass>qa.Main</mainClass></configuration></project>"), null, null);
        assertThat(result).containsEntry("sourceType", "JAVA_PROC");
        assertThat(result.get("warnings").toString()).contains("실행 가능한 JAR", "Main-Class", "런타임 의존성");
    }

    @Test void multipleEntryPointsNeedExplicitChoiceAndDraftDoesNotChangeProject() {
        var project = Project.builder().buildCommand("keep-this").artifactPath("keep.jar").build();
        var files = Map.of("api/pom.xml", BOOT, "ui/package.json", "{\"dependencies\":{\"react\":\"19\"}}");
        assertThat(service.inspect(files, project, null)).containsEntry("selectionRequired", true).containsEntry("recognition", "AMBIGUOUS");
        var selected = service.inspect(files, project, "MAVEN_BOOT");
        assertThat(selected).containsEntry("selectionRequired", false).containsEntry("recognition", "SELECTED");
        assertThat(settings(selected).get("buildCommand")).asString().contains("-f 'api/pom.xml'", "clean verify");
        assertThat(project.getBuildCommand()).isEqualTo("keep-this");
        assertThat(project.getArtifactPath()).isEqualTo("keep.jar");
    }

    @Test void selectedComponentContextIgnoresOtherManifest() {
        var project = Project.builder().dockerContextPath("ui").build();
        var result = service.inspect(Map.of("pom.xml", BOOT, "ui/package.json", "{\"dependencies\":{\"react\":\"19\"}}"), project, null);
        assertThat(result).containsEntry("sourceType", "REACT_STATIC").containsEntry("selectionRequired", false);
        assertThat(settings(result)).containsEntry("artifactPath", "ui/dist");
        assertThat((String) result.get("generatedDockerfile")).contains("COPY dist/ /dist/");
    }

    @Test void doctypeAndExternalEntityAreRejectedWithoutReadingOrResolvingThem() {
        String xml = "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///does-not-exist/secret'>]><project><artifactId>&x;</artifactId></project>";
        var result = service.inspect(Map.of("pom.xml", xml), null, null);
        assertThat(result).containsEntry("selectionRequired", true);
        assertThat(result.get("warnings").toString()).contains("DOCTYPE").doesNotContain("secret");
        assertThat(result.get("evidence").toString()).isEqualTo("[]");
    }

    @Test void invalidJsonAndUnrecognizedSourcesRequireExplicitChoice() {
        var result = service.inspect(Map.of("package.json", "not-json", "README.md", "anything"), null, null);
        assertThat(result).containsEntry("selectionRequired", true);
        assertThat(result).doesNotContainKey("generatedDockerfile");
        var selected = service.inspect(Map.of(), null, "JAVA_PROC");
        assertThat(selected).containsEntry("selectionRequired", false).containsEntry("draftOnly", true);
        assertThat(selected.get("warnings").toString()).contains("근거를 충분히 찾지 못했습니다");
        assertThatThrownBy(() -> service.inspect(Map.of(), null, "SHELL")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unsafeManifestPathNeverBecomesShellBuildCommand() {
        var result = service.inspect(Map.of("a';touch hacked;/pom.xml", BOOT), null, null);
        assertThat(result).containsEntry("selectionRequired", true);
        assertThat(result).doesNotContainKey("generatedDockerfile");
    }

    @Test void fallbackCannotUseAnotherComponentOrMissingConfiguredManifest() {
        var outside = Map.of("ui/main.c", "int main(){return 0;}", "ui/index.html", "<html/>");
        assertThat(service.inspect(outside, Project.builder().dockerContextPath("api").build(), null))
            .containsEntry("sourceType", "UNKNOWN").containsEntry("selectionRequired", true);
        assertThat(service.inspect(outside, Project.builder().buildFilePath("api/pom.xml").build(), null))
            .containsEntry("sourceType", "UNKNOWN").containsEntry("selectionRequired", true);
    }

    @Test void subdirectoryCCompileArtifactAndDockerContextHaveMatchingPaths() {
        var result = service.inspect(Map.of("ui/main.c", "int main(){return 0;}"),
            Project.builder().dockerContextPath("ui").build(), null);
        assertThat(result).containsEntry("sourceType", "C");
        assertThat(settings(result)).containsEntry("buildCommand", "gcc -O2 -Wall -Wextra -o ui/app ui/main.c")
            .containsEntry("artifactPath", "ui/app");
        assertThat((String) result.get("generatedDockerfile")).contains("COPY app /opt/app/app").doesNotContain("dnf install");
    }

    @Test void generatedDraftAndSingleImageCiUseTheSameInternalRuntimeArgument() {
        var profile = service.inspect(Map.of("pom.xml", BOOT), null, null);
        var project = Project.builder().id(1L).gitlabProjectId(2L).name("safe-example")
            .imageRepository("registry.internal/example/service").dockerfilePath("Dockerfile").build();
        String ci = new GitlabCiTemplateService().generate(project);
        assertThat((String) profile.get("generatedDockerfile")).contains("ARG RUNTIME_BASE_IMAGE", "FROM ${RUNTIME_BASE_IMAGE}");
        Map<String, Object> document = new Yaml().load(ci);
        assertThat(document.get("build_and_push_image")).isInstanceOf(Map.class);
        var imageJob = (Map<?, ?>) document.get("build_and_push_image");
        assertThat(imageJob.get("script")).isInstanceOf(List.class);
        var scripts = (List<?>) imageJob.get("script");
        assertThat(scripts).allSatisfy(command -> assertThat(command).isInstanceOf(String.class));
        var builds = scripts.stream().map(String.class::cast).filter(command -> command.contains("docker build ")).toList();
        assertThat(builds).hasSize(1);
        assertThat(builds.getFirst()).contains("else set --; if [ -n \"${RUNTIME_BASE_IMAGE:-}\" ]; then",
            "printf '%s' \"$RUNTIME_BASE_IMAGE\" | grep -Eq '^.+@sha256:[0-9a-f]{64}$' || exit 1",
            "set -- --build-arg \"RUNTIME_BASE_IMAGE=$RUNTIME_BASE_IMAGE\"",
            "docker build --network=none --pull=false \"$@\" -f \"$DOCKERFILE_PATH\" -t \"$IMAGE_REPOSITORY:$IMAGE_TAG\" \"$DOCKER_CONTEXT_PATH\"")
            .doesNotContain("else docker build -f");
    }

    @SuppressWarnings("unchecked") private Map<String, Object> settings(Map<String, Object> result) { return (Map<String, Object>) result.get("suggestions"); }
}
