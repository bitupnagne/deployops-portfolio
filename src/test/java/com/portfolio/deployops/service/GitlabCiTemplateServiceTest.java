package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.PipelineType;
import com.portfolio.deployops.domain.Project;
import org.junit.jupiter.api.Test;

class GitlabCiTemplateServiceTest {

    private final GitlabCiTemplateService service = new GitlabCiTemplateService();

    @Test
    void generateCreatesDockerPipelineWithNexusPushAndDotenvDigest() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.DOCKER)
            .buildTool(BuildToolType.GRADLE)
            .buildCommand("./gradlew clean build -x test")
            .artifactPath("build/libs/worksystem-server.jar")
            .dockerfilePath("Dockerfile")
            .dockerContextPath(".")
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("if: '$CI_PIPELINE_SOURCE == \"api\"'")
            .contains("when: never")
            .contains("stages:")
            .contains("build_app:")
            .contains("build_and_push_image:")
            .contains("NEXUS_REGISTRY_USER")
            .contains("NEXUS_REGISTRY_PASSWORD")
            .contains("docker build --network=none --pull=false")
            .contains("docker push")
            .contains("DEPLOY_IMAGE_TAG_PATTERN:")
            .contains("value: \"${branch}-${shortSha}-${pipelineId}\"")
            .contains("expand: false")
            .contains("$DEPLOY_IMAGE_TAG_PATTERN")
            .contains("dotenv: deploy-image.env")
            .contains("paths:\n      - deploy-image.env")
            .contains("IMAGE_DIGEST=$(docker inspect")
            .contains("CI_COMMIT_SHA=%s")
            .contains("CI_PIPELINE_ID=%s")
            .contains("192.0.2.10:5000/worksystem/worksystem-server");
    }

    @Test
    void generateRejectsProjectWithoutImageRepository() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.DOCKER)
            .build();

        // 화면에 그대로 뜨는 문장이라 한글이어야 하고, 어디를 채우면 되는지까지 있어야 한다.
        assertThatThrownBy(() -> service.generate(project))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이미지 저장소")
            .hasMessageContaining("CI/CD 설정");
    }

    @Test
    void generateRejectsProjectWithoutGitlabRepositoryInKorean() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .pipelineType(PipelineType.DOCKER)
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .build();

        assertThatThrownBy(() -> service.generate(project))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("GitLab 저장소")
            .hasMessageContaining("연결");
    }

    /*
      예전에는 DOCKER 가 아니면 예외를 던졌다. 배포 준비 판정은 CI 파일이 없으면 '차단' 이라
      말하는데, 정작 옆의 "권장 예시 보기" 는 JAR·정적 프로젝트에서 막다른 길이었다.
    */
    @Test
    void generateSupportsJarPipeline() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.JAR)
            .buildTool(BuildToolType.MAVEN)
            .artifactPath("target/worksystem-server.jar")
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("build_app:")
            .contains("build_and_push_image:")
            .contains("mvn -B -ntp --settings")
            .contains("clean verify")
            .contains("$MAVEN_BUILD_IMAGE")
            .contains("$DOCKER_BUILD_IMAGE")
            .doesNotContain("-DskipTests", "./mvnw -U")
            .contains("docker push")
            // 산출물을 이미지 단계로 넘겨야 docker build 가 담을 것이 있다.
            .contains("target/worksystem-server.jar");
    }

    @Test
    void mavenPrerequisitesKeepNexusChecksWithoutForcingDeployJdkOrNode() {
        Project project = Project.builder()
            .id(1L)
            .name("legacy-api")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.JAR)
            .buildTool(BuildToolType.MAVEN)
            .imageRepository("nexus.internal:5000/business/legacy-api")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("command -v java", "command -v mvn")
            .contains("test -f", "MAVEN_SETTINGS_FILE", "NEXUS_MAVEN_USER", "NEXUS_MAVEN_PASSWORD")
            .contains("NEXUS_REGISTRY_USER", "NEXUS_REGISTRY_PASSWORD")
            .contains("clean verify", "프로젝트가 요구하는 Maven + JDK")
            .doesNotContain("JAVA_VERSION=21", "JAVA_VERSION: 21", "JAVA_VERSION: \"21\"",
                "JDK 21", "command -v node", "Node.js를 설치하여 UI 테스트");
    }

    @Test
    void generateSupportsStaticPipelineAndPassesArtifactsToImageStage() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-web")
            .gitlabProjectId(101L)
            .pipelineType(PipelineType.STATIC)
            .buildTool(BuildToolType.NPM)
            .artifactPath("dist")
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-web")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("npm ci && npm run build")
            .contains("docker push");
        // 정적은 넘기지 않으면 이미지에 빈 것이 담긴다 — 예전에는 일부러 빼고 있었다.
        assertThat(yaml).contains("paths:").contains("- \"dist\"");
        // 배포가 이미지의 /dist 에서 꺼내 간다는 사실은 파일만 봐서는 알 수 없다.
        assertThat(yaml).contains("/dist");
    }

    /*
      빌드 도구를 안 고른 채로 돌면 예전에는 echo 라서 빌드가 초록으로 통과하고,
      이미지 단계에서 담을 게 없다며 죽었다. 원인에서 먼 자리에서 터진다.
    */
    @Test
    void generateFailsFastWhenBuildCommandIsNotConfigured() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.JAR)
            .buildTool(BuildToolType.NONE)
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("빌드 명령이 정해져 있지 않습니다")
            .contains("exit 1");
    }

    /*
      러너 태그와 CI/CD 변수는 저장소 바깥에서 준비해야 하는 것이라
      파일을 아무리 읽어도 알 수 없다. 준비가 없으면 파이프라인이 대기만 하거나 push 에서 죽는다.
    */
    @Test
    void generateExplainsWhatMustBePreparedOutsideTheRepository() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.DOCKER)
            .buildTool(BuildToolType.MAVEN)
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("러너 태그 'offline'")
            .contains("NEXUS_REGISTRY_USER")
            .contains("NEXUS_REGISTRY_PASSWORD")
            .contains("Settings > CI/CD");
    }

    @Test
    void generateUsesConfiguredDeployImageTagPattern() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.DOCKER)
            .buildTool(BuildToolType.MAVEN)
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .imageTagPattern("release-${commitSha}-${pipelineId}")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("value: \"release-${commitSha}-${pipelineId}\"")
            .contains("expand: false")
            .contains("${CI_COMMIT_SHA}")
            .contains("${CI_PIPELINE_ID}");
    }

    @Test
    void generatedCiFailsClearlyWhenCustomPatternLeavesUnsafeOrUnresolvedTag() {
        Project project = Project.builder()
            .id(1L)
            .name("worksystem-server")
            .gitlabProjectId(100L)
            .pipelineType(PipelineType.DOCKER)
            .buildTool(BuildToolType.MAVEN)
            .imageRepository("192.0.2.10:5000/worksystem/worksystem-server")
            .imageTagPattern("release ${unknown}")
            .build();

        String yaml = service.generate(project);

        assertThat(yaml)
            .contains("value: \"release ${unknown}\"")
            .contains(GitlabCiContract.IMAGE_TAG_REGEX)
            .contains("Docker tag 규칙")
            .contains("${...} 토큰이 남았습니다")
            .contains("exit 1");
    }
}
