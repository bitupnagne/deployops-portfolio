package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ServerType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class GitlabCiMultiComponentTemplateTest {
    private final GitlabCiTemplateService service = new GitlabCiTemplateService();
    @TempDir Path temporary;

    private Project project() {
        return Project.builder().id(90L).gitlabProjectId(22L).imageRepository("registry.internal:5000/qa/api")
            .buildTool(BuildToolType.MAVEN).components(List.of(
                component("api", ServerType.WAS, ArtifactType.CONTAINER, BuildToolType.MAVEN, "api/target/*.jar"),
                component("web", ServerType.WEB, ArtifactType.STATIC_BUNDLE, BuildToolType.NPM, "web/dist/")
            )).build();
    }

    private DeployComponent component(String code, ServerType role, ArtifactType type, BuildToolType tool, String artifact) {
        return DeployComponent.builder().id(code.equals("api") ? 1L : 2L).code(code).role(role).artifactType(type)
            .imageRepository("registry.internal:5000/qa/" + code).buildTool(tool)
            .buildCommand(code.equals("api") ? "mvn -B -f api/pom.xml --settings \"$MAVEN_SETTINGS_FILE\" verify" : "cd web && npm ci && npm run build")
            .artifactPath(artifact).dockerfilePath(code + "/Dockerfile").dockerContextPath(code).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Project project) { return new Yaml().load(service.generate(project)); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> job(Map<String, Object> yaml, String name) { return (Map<String, Object>) yaml.get(name); }

    @Test
    @SuppressWarnings("unchecked")
    void independentBuildsPublishOneCompleteManifestAndCompatiblePrimaryArtifact() {
        Map<String, Object> yaml = yaml(project());
        Map<String, Object> api = job(yaml, "build_app_api"), web = job(yaml, "build_app_web");
        assertThat(api.get("image")).isEqualTo("$MAVEN_BUILD_IMAGE");
        assertThat(web.get("image")).isEqualTo("$CI_BUILD_IMAGE");
        assertThat((List<String>) api.get("script")).containsExactly("mvn -B -f api/pom.xml --settings \"$MAVEN_SETTINGS_FILE\" verify");
        assertThat((List<String>) web.get("script")).containsExactly("cd web && npm ci && npm run build");
        assertThat((List<String>) ((Map<String, Object>) api.get("artifacts")).get("paths")).containsExactly("api/target/*.jar");
        assertThat((List<String>) ((Map<String, Object>) web.get("artifacts")).get("paths")).containsExactly("web/dist");
        Map<String, Object> image = job(yaml, GitlabCiContract.IMAGE_JOB_NAME);
        assertThat((List<String>) image.get("needs")).containsExactly("build_app_api", "build_app_web");
        Map<String, Object> artifacts = (Map<String, Object>) image.get("artifacts");
        assertThat(artifacts.get("when")).isEqualTo("on_success");
        assertThat((List<String>) artifacts.get("paths")).containsExactly("deploy-image.env", "deploy-release.json");
        assertThat((List<String>) image.get("script")).allMatch(String.class::isInstance);
        assertThat(service.generate(project())).doesNotContain("DEPLOY_SCOPE", "when: always\n    paths");
    }

    @Test
    void ignoresProxyReloadAndInactiveComponentsWithoutChangingSingleImageContract() {
        Project project = project();
        project.getComponents().get(1).setArtifactType(ArtifactType.PROXY_RELOAD);
        String single = service.generate(project);
        assertThat(single).contains("build_app:", "deploy-image.env").doesNotContain("deploy-release.json", "build_app_web:");
        project.getComponents().get(1).setArtifactType(ArtifactType.STATIC_BUNDLE);
        project.getComponents().get(1).setActive(false);
        assertThat(service.generate(project)).isEqualTo(single);
    }

    @Test
    void rejectsSingleCustomRepositoryBeforeGeneratingAnIncompatibleCiContract() {
        Project project = project();
        project.setComponents(List.of(project.getComponents().get(1)));
        assertThatThrownBy(() -> service.generate(project))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("프로젝트 대표 이미지 저장소");
        project.getComponents().get(0).setImageRepository(null);
        assertThat(service.generate(project)).contains("build_app:", "deploy-image.env")
            .doesNotContain("deploy-release.json");
        project.getComponents().get(0).setImageRepository("  " + project.getImageRepository() + "  ");
        assertThat(service.generate(project)).contains("build_app:", "deploy-image.env")
            .doesNotContain("deploy-release.json");
    }

    @Test
    void rejectsDuplicateRepositoriesAndMissingUniquePrimary() {
        Project project = project();
        project.getComponents().get(1).setImageRepository(project.getImageRepository());
        assertThatThrownBy(() -> service.generate(project)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("서로 다른");
        Project missing = project(); missing.setImageRepository("registry.internal:5000/qa/other");
        assertThatThrownBy(() -> service.generate(missing)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("대표 이미지");
    }

    @Test
    void rejectsEscapingPathsControlCharactersAndOverlappingArtifacts() {
        for (String path : List.of("../Dockerfile", "/Dockerfile", "C:/Dockerfile", "a/../Dockerfile", "Dockerfile\njob:")) {
            Project project = project(); project.getComponents().get(1).setDockerfilePath(path);
            assertThatThrownBy(() -> service.generate(project)).isInstanceOf(IllegalArgumentException.class);
        }
        Project duplicate = project(); duplicate.getComponents().get(1).setArtifactPath("./api//target/app.jar");
        assertThatThrownBy(() -> service.generate(duplicate)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("겹칩니다");
        Project control = project(); control.getComponents().get(1).setBuildCommand("npm build\nexfiltrate");
        assertThatThrownBy(() -> service.generate(control)).isInstanceOf(IllegalArgumentException.class);
        Project variable = project(); variable.getComponents().get(1).setArtifactPath("$OUTSIDE/secret");
        assertThatThrownBy(() -> service.generate(variable)).isInstanceOf(IllegalArgumentException.class);
        Project nullCode = project(); nullCode.getComponents().get(1).setCode(null);
        assertThatThrownBy(() -> service.generate(nullCode)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dataPathsAreShellQuotedAndBuildCommandIsPreservedAsIntentionalCommand() {
        Project project = project();
        project.getComponents().get(1).setDockerfilePath("web/$HOME's Dockerfile");
        project.getComponents().get(1).setDockerContextPath("web/$(touch injected)");
        Map<String, Object> yaml = yaml(project);
        assertThat(job(yaml, GitlabCiContract.IMAGE_JOB_NAME).get("script").toString())
            .contains("'web/$HOME'\"'\"'s Dockerfile'", "'web/$(touch injected)'");
    }

    @Test
    void emittedImageScriptActuallyWritesBothDigestsAndPrimaryFromSameJob() throws Exception {
        runImageScript(false);
        var manifest = new ObjectMapper().readTree(temporary.resolve("deploy-release.json").toFile());
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.path("pipelineId").asLong()).isEqualTo(42L);
        assertThat(manifest.path("commitSha").asText()).isEqualTo("c".repeat(40));
        assertThat(manifest.path("components")).hasSize(2);
        assertThat(manifest.path("components").get(0).path("code").asText()).isEqualTo("api");
        assertThat(manifest.path("components").get(0).path("digest").asText()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(manifest.path("components").get(1).path("code").asText()).isEqualTo("web");
        assertThat(manifest.path("components").get(1).path("digest").asText()).isEqualTo("sha256:" + "b".repeat(64));
        assertThat(Files.readString(temporary.resolve("deploy-image.env")))
            .contains("IMAGE_REPOSITORY=registry.internal:5000/qa/api", "IMAGE_DIGEST=sha256:" + "a".repeat(64), "CI_PIPELINE_ID=42");
        String calls = Files.readString(temporary.resolve("docker-calls.log"));
        assertThat(calls).contains("build --network=none --pull=false --build-arg RUNTIME_BASE_IMAGE=internal/runtime@sha256:",
            "-f api/Dockerfile", "-f web/Dockerfile", "push registry.internal:5000/qa/web:");
        assertThat(calls).doesNotContain("fixture-password");
    }

    @Test
    void secondPublishFailureStopsJobAndNeverCompletesReleaseManifest() throws Exception {
        runImageScript(true);
        String partial = Files.readString(temporary.resolve("deploy-release.json"));
        assertThat(partial).doesNotContain("\"code\":\"web\"").doesNotEndWith("]}\n");
    }

    @SuppressWarnings("unchecked")
    private void runImageScript(boolean failWeb) throws Exception {
        Map<String, Object> yaml = yaml(project());
        List<String> scripts = (List<String>) job(yaml, GitlabCiContract.IMAGE_JOB_NAME).get("script");
        String stub = """
            set -eu
            docker() {
              printf '%s\\n' "$*" >> docker-calls.log
              case "$1" in
                login) cat >/dev/null ;;
                build) : ;;
                push) if [ "${FAIL_WEB:-false}" = true ] && [ "$2" != "${2%/web:*}" ]; then return 17; fi ;;
                inspect)
                  case "$*" in
                    */api:*) printf '%s\\n' 'registry.internal:5000/qa/api@sha256:API_DIGEST' ;;
                    */web:*) printf '%s\\n' 'registry.internal:5000/qa/web@sha256:WEB_DIGEST' ;;
                    *) return 19 ;;
                  esac ;;
                *) return 18 ;;
              esac
            }
            """.replace("API_DIGEST", "a".repeat(64)).replace("WEB_DIGEST", "b".repeat(64));
        Path script = temporary.resolve("image-job.sh");
        Files.writeString(script, stub + String.join("\n", scripts) + "\n");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String bash = windows ? Path.of(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"), "Git", "bin", "bash.exe").toString() : "/bin/bash";
        ProcessBuilder builder = new ProcessBuilder(bash, "--noprofile", "--norc", script.toAbsolutePath().toString())
            .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve("script-output.log").toFile());
        builder.environment().putAll(Map.of("DEPLOY_IMAGE_TAG_PATTERN", "${branch}-${shortSha}-${pipelineId}",
            "CI_COMMIT_REF_SLUG", "main", "CI_COMMIT_SHORT_SHA", "cccccccc", "CI_COMMIT_SHA", "c".repeat(40),
            "CI_PIPELINE_ID", "42", "NEXUS_REGISTRY_USER", "fixture", "NEXUS_REGISTRY_PASSWORD", "fixture-password",
            "FAIL_WEB", String.valueOf(failWeb), "RUNTIME_BASE_IMAGE", "internal/runtime@sha256:" + "d".repeat(64)));
        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(failWeb ? 17 : 0);
    }
}
