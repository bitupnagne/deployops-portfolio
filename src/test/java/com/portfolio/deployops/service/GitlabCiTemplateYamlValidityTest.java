package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.PipelineType;
import com.portfolio.deployops.domain.Project;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * 만들어 준 {@code .gitlab-ci.yml} 이 <b>진짜 YAML 인지</b> 확인한다.
 *
 * <p>다른 테스트는 "이 문자열이 들어 있나" 를 본다. 그것만으로는 부족하다 —
 * 프로젝트 이름이나 빌드 명령에 따옴표 하나만 섞여도 문서 전체가 깨질 수 있는데,
 * 문자열 검사로는 통과한다. 우리는 이 파일을 <b>MR 로 저장소에 올린다.</b>
 * 깨진 채로 올라가면 GitLab 이 파이프라인을 거절하고, 처음 쓰는 사람은
 * "권장 예시대로 했는데 안 된다" 는 자리에 다시 서게 된다 — 이 기능이 없애려던 바로 그 자리다.
 *
 * <p>그래서 파서에 실제로 먹여 본다. 값은 사람이 넣는 것이라 무엇이든 들어올 수 있다고 본다.
 */
class GitlabCiTemplateYamlValidityTest {

    private final GitlabCiTemplateService service = new GitlabCiTemplateService();

    private static Project.ProjectBuilder base() {
        return Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .buildTool(BuildToolType.MAVEN)
            .artifactPath("target/app.jar")
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String yaml) {
        return new Yaml().load(yaml);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mavenUsesPreparedImagesAndInternalSettingsWithoutSkippingTests() {
        Map<String, Object> doc = parse(service.generate(base().artifactPath(null).build()));
        Map<String, Object> workflow = (Map<String, Object>) doc.get("workflow");
        List<Map<String, Object>> workflowRules = (List<Map<String, Object>>) workflow.get("rules");
        Map<String, Object> build = (Map<String, Object>) doc.get("build_app");
        Map<String, Object> image = (Map<String, Object>) doc.get("build_and_push_image");
        assertThat(workflowRules).hasSize(2);
        assertThat(workflowRules.get(0)).containsEntry("if", "$CI_PIPELINE_SOURCE == \"api\"")
            .containsEntry("when", "always");
        assertThat(workflowRules.get(1)).containsEntry("when", "never");
        assertThat(build.get("image")).isEqualTo("$MAVEN_BUILD_IMAGE");
        assertThat(image.get("image")).isEqualTo("$DOCKER_BUILD_IMAGE");
        assertThat((List<String>) build.get("script"))
            .containsExactly("mvn -B -ntp --settings \"$MAVEN_SETTINGS_FILE\" clean verify");
        assertThat((List<String>) build.get("before_script"))
            .anySatisfy(command -> assertThat(command).contains("test -f", "MAVEN_SETTINGS_FILE"))
            .anySatisfy(command -> assertThat(command).contains("NEXUS_MAVEN_PASSWORD", "exit 1"))
            .anySatisfy(command -> assertThat(command).contains("command -v java"))
            .anySatisfy(command -> assertThat(command).contains("command -v mvn"))
            .noneSatisfy(command -> assertThat(command).contains("command -v node"))
            .noneSatisfy(command -> assertThat(command).contains("JDK 21"));
        Map<String, Object> artifacts = (Map<String, Object>) build.get("artifacts");
        assertThat(artifacts.get("when")).isEqualTo("always");
        assertThat((List<String>) artifacts.get("paths")).containsExactly("target/*.jar");
        assertThat((Map<String, Object>) artifacts.get("reports")).containsKey("junit");
        assertThat((List<String>) image.get("script"))
            .anySatisfy(command -> assertThat(command)
                .contains("Dockerfile.release", "--network=none", "--pull=false", "JAR_SHA256", "RUNTIME_BASE_IMAGE"));
    }

    @Test
    void releaseDockerfilesHaveNoOnlineBuildStagesAndRequireVerifiedJar() throws Exception {
        for (String path : List.of("Dockerfile.release")) {
            String dockerfile = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            assertThat(dockerfile).contains("ARG RUNTIME_BASE_IMAGE", "FROM ${RUNTIME_BASE_IMAGE}",
                "ARG JAR_SHA256", "COPY target/*.jar /app/app.jar", "sha256sum -c -", "USER 10001:10001")
                .doesNotContain("apt-get", "mvn ", "npm ", "# syntax=", " AS builder", "COPY src");
        }
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("Dockerfile.release")))
            .doesNotContain("command -v ssh", "command -v scp", "openssh-client");
    }

    @ParameterizedTest
    @EnumSource(PipelineType.class)
    @DisplayName("세 파이프라인 타입 모두 파싱되는 YAML 을 낸다")
    void everyPipelineTypeProducesParseableYaml(PipelineType pipelineType) {
        String yaml = service.generate(base().pipelineType(pipelineType).build());

        assertThatCode(() -> parse(yaml)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PipelineType.class)
    @DisplayName("선언한 stage 와 job 이 실제로 맞물린다")
    void jobsReferenceDeclaredStages(PipelineType pipelineType) {
        Map<String, Object> doc = parse(service.generate(base().pipelineType(pipelineType).build()));

        List<String> stages = (List<String>) doc.get("stages");
        assertThat(stages).containsExactly("build", "image");

        // stage 이름이 어긋나면 GitLab 이 통째로 거절한다. 눈으로는 잘 안 보이는 종류의 오타다.
        Map<String, Object> buildJob = (Map<String, Object>) doc.get("build_app");
        Map<String, Object> imageJob = (Map<String, Object>) doc.get("build_and_push_image");
        assertThat(buildJob).isNotNull();
        assertThat(imageJob).isNotNull();
        assertThat(buildJob.get("stage")).isEqualTo("build");
        assertThat(imageJob.get("stage")).isEqualTo("image");

        // 이미지 단계는 빌드 결과를 받아야 한다.
        assertThat((List<String>) imageJob.get("needs")).containsExactly("build_app");
    }

    /*
      사람이 넣는 값이라 무엇이든 들어온다. 따옴표·역슬래시·콜론은
      YAML 에서 전부 의미를 가지므로, 이스케이프가 하나라도 새면 문서가 깨진다.
    */
    @ParameterizedTest
    @ValueSource(strings = {
        "mvn -Dmsg=\"hello world\" package",
        "sh -c 'echo a: b'",
        "build.bat C:\\\\app\\\\out",
        "npm run build -- --env name=\"x\"",
        "echo '#not-a-comment' && make",
    })
    @DisplayName("빌드 명령에 따옴표·역슬래시·콜론이 있어도 문서가 깨지지 않는다")
    void quotingSurvivesAwkwardBuildCommands(String buildCommand) {
        String yaml = service.generate(base().buildCommand(buildCommand).build());

        Map<String, Object> doc = parse(yaml);
        Map<String, Object> buildJob = (Map<String, Object>) doc.get("build_app");
        List<String> script = (List<String>) buildJob.get("script");

        // 깨지지 않는 것만으로는 부족하다 — 넣은 명령이 <그대로> 나와야 한다.
        assertThat(script).containsExactly(buildCommand);
    }

    @Test
    @DisplayName("생성 CI는 폐쇄망 설치기와 같은 공용 Runner 태그를 쓴다")
    void generatedCiUsesOfflineRunnerTag() {
        Map<String, Object> doc = parse(
            service.generate(base().name("결제 API (운영)").build()));

        Map<String, Object> buildJob = (Map<String, Object>) doc.get("build_app");
        List<String> tags = (List<String>) buildJob.get("tags");

        assertThat(tags).hasSize(1);
        assertThat(tags).containsExactly("offline");
    }

    @Test
    @DisplayName("빌드 명령을 안 정한 프로젝트도 YAML 로는 성립한다")
    void unsetBuildCommandStillProducesValidYaml() {
        String yaml = service.generate(base().buildTool(BuildToolType.NONE).build());

        Map<String, Object> doc = parse(yaml);
        Map<String, Object> buildJob = (Map<String, Object>) doc.get("build_app");
        List<String> script = (List<String>) buildJob.get("script");

        // 일부러 실패하는 명령이지만, 파일 자체는 성립해야 그 실패 메시지가 사람에게 보인다.
        assertThat(script).hasSize(1);
        assertThat(script.get(0)).contains("exit 1");
    }

    @Test
    @DisplayName("이미지 단계가 dotenv 로 태그와 지문을 돌려준다")
    void imageJobReportsTagAndDigestBack() {
        Map<String, Object> doc = parse(service.generate(base().build()));

        Map<String, Object> imageJob = (Map<String, Object>) doc.get("build_and_push_image");
        Map<String, Object> artifacts = (Map<String, Object>) imageJob.get("artifacts");
        Map<String, Object> reports = (Map<String, Object>) artifacts.get("reports");

        // 이 경로로 이미지 태그·지문이 배포시스템에 들어온다. 이름이 바뀌면 배포가 이미지를 못 찾는다.
        assertThat(reports.get("dotenv")).isEqualTo("deploy-image.env");
    }

    @Test
    @DisplayName("이미지 태그 패턴은 GitLab 변수 확장 전에 원문이 보존된다")
    void imageTagPatternDisablesGitlabVariableExpansion() {
        Map<String, Object> doc = parse(service.generate(base()
            .imageTagPattern("release-${commitSha}-${pipelineId}")
            .build()));

        Map<String, Object> variables = (Map<String, Object>) doc.get("variables");
        Map<String, Object> pattern = (Map<String, Object>) variables.get("DEPLOY_IMAGE_TAG_PATTERN");
        assertThat(pattern.get("value")).isEqualTo("release-${commitSha}-${pipelineId}");
        assertThat(pattern.get("expand")).isEqualTo(false);
    }
}
