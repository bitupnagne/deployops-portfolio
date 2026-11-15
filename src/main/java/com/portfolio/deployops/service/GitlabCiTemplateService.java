package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.PipelineType;
import com.portfolio.deployops.domain.Project;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 프로젝트 설정으로 <code>.gitlab-ci.yml</code> 초안을 만든다.
 *
 * <p>파이프라인 타입(DOCKER/JAR/STATIC)이 무엇이든 실행·정적 산출물은 이미지로 전달한다.
 * 다중 배포 단위는 각 이미지를 빌드하고 같은 성공 job에서 전체 릴리스 명세를 내보낸다.
 *
 * <p>예전에는 DOCKER 가 아니면 예외를 던졌다. 그런데 배포 준비 판정은 CI 파일이 없으면
 * <b>차단</b>이라고 말한다. JAR 프로젝트를 만든 사람은 "CI 파일이 없어 빌드가 안 돕니다" 를 보고
 * 옆의 "권장 예시 보기" 를 눌렀다가 영어 오류만 받았다 — 막다른 길이었다.
 *
 * <p>주석을 한글로 넉넉히 단다. 이 파일을 처음 받는 사람은 GitLab CI 를 모르는 개발자이고,
 * 러너 태그·CI/CD 변수처럼 <b>저장소 바깥에서 준비해야 하는 것</b>은 파일만 봐서는 알 수 없다.
 */
@Service
public class GitlabCiTemplateService {

    public String generate(Project project) {
        validate(project);
        List<DeployComponent> artifacts = project.getComponents() == null ? List.of()
            : project.getComponents().stream().filter(DeployComponent::isActive)
                .filter(component -> component.getArtifactType() != ArtifactType.PROXY_RELOAD)
                .sorted(Comparator.comparing(DeployComponent::getCode, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        if (artifacts.size() > 1) return generateComponents(project, artifacts);
        if (artifacts.size() == 1 && !project.getImageRepository().trim().equals(
                valueOrDefault(artifacts.get(0).getImageRepository(), project.getImageRepository().trim()))) {
            throw new IllegalArgumentException(
                "CI를 생성하려면 단일 배포 단위의 이미지 저장소를 프로젝트 대표 이미지 저장소와 일치시켜 주세요.");
        }

        PipelineType pipelineType = project.getPipelineType() == null
            ? PipelineType.DOCKER : project.getPipelineType();
        String buildCommand = valueOrDefault(project.getBuildCommand(), defaultBuildCommand(project.getBuildTool()));
        String dockerfilePath = valueOrDefault(project.getDockerfilePath(), "Dockerfile");
        String dockerContextPath = valueOrDefault(project.getDockerContextPath(), ".");
        String imageRepository = project.getImageRepository().trim();
        String runnerTag = GitlabCiContract.runnerTag();
        String imageTagPattern = GitlabCiContract.imageTagPattern(project);

        StringBuilder yaml = new StringBuilder();
        appendHeader(yaml, pipelineType, runnerTag);
        yaml.append("workflow:\n");
        yaml.append("  rules:\n");
        yaml.append("    # DeployOps API가 요청한 빌드만 보호 변수와 이미지 push 권한을 사용합니다.\n");
        yaml.append("    - if: '$CI_PIPELINE_SOURCE == \"api\"'\n");
        yaml.append("      when: always\n");
        yaml.append("    - when: never\n\n");
        yaml.append("stages:\n");
        yaml.append("  - build\n");
        yaml.append("  - image\n\n");
        yaml.append("variables:\n");
        yaml.append("  IMAGE_REPOSITORY: \"").append(escapeYaml(imageRepository)).append("\"\n");
        // ${branch} 같은 계약 토큰을 GitLab/Runner가 환경변수로 먼저 확장하지 않게 원문을 보존한다.
        yaml.append("  DEPLOY_IMAGE_TAG_PATTERN:\n");
        yaml.append("    value: \"").append(escapeYaml(imageTagPattern)).append("\"\n");
        yaml.append("    expand: false\n");
        yaml.append("  DOCKERFILE_PATH: \"").append(escapeYaml(dockerfilePath)).append("\"\n");
        yaml.append("  DOCKER_CONTEXT_PATH: \"").append(escapeYaml(dockerContextPath)).append("\"\n\n");
        appendBuildJob(yaml, project, buildCommand, runnerTag);
        appendImageJob(yaml, project, pipelineType, runnerTag);
        return yaml.toString();
    }

    /**
     * 파일 맨 위에 "저장소 바깥에서 준비해야 하는 것" 을 적는다.
     *
     * <p>러너 태그와 CI/CD 변수는 이 파일을 아무리 들여다봐도 알 수 없고,
     * 준비가 안 되어 있으면 파이프라인이 <b>시작조차 하지 않거나</b>(태그 불일치)
     * 이미지 push 에서 인증 실패로 죽는다. 처음 쓰는 사람이 가장 오래 헤매는 두 자리다.
     */
    private void appendHeader(StringBuilder yaml, PipelineType pipelineType, String runnerTag) {
        yaml.append("# 배포시스템이 만든 초안입니다. 내용을 확인한 뒤 MR 로 올려 주세요.\n");
        yaml.append("# 이미지 빌드/푸시는 API로 생성한 파이프라인에서만 실행됩니다. API source 자체는 호출자를 식별하지 않습니다.\n");
        yaml.append("# 일반 push/MR 파이프라인은 보호된 Nexus 자격증명을 사용하지 않도록 제외됩니다.\n");
        yaml.append("#\n");
        yaml.append("# 이 파일이 돌기 전에 GitLab 에서 두 가지를 먼저 준비해야 합니다.\n");
        yaml.append("#  1) 러너 태그 '").append(runnerTag).append("' 를 가진 러너 1대\n");
        yaml.append("#     (Settings > CI/CD > Runners. 태그가 맞는 러너가 없으면 파이프라인이 계속 대기만 합니다)\n");
        yaml.append("#  2) CI/CD 변수 NEXUS_REGISTRY_USER, NEXUS_REGISTRY_PASSWORD\n");
        yaml.append("#     (Settings > CI/CD > Variables. 비밀번호는 'Masked' 로 두세요)\n");
        yaml.append("#\n");
        yaml.append("#  3) Maven 프로젝트: MAVEN_BUILD_IMAGE = 프로젝트가 요구하는 Maven + JDK 내부 이미지(@sha256 고정)\n");
        yaml.append("#     JDK 버전과 Node 필요 여부는 각 프로젝트의 빌드 설정에서 검증합니다.\n");
        yaml.append("#     기타 빌드: CI_BUILD_IMAGE = 해당 도구가 설치된 내부 이미지. DOCKER_BUILD_IMAGE = Docker CLI 이미지\n");
        yaml.append("#     이미지는 Runner에서 접근/인증 가능해야 합니다. 빈 image 오류는 위 변수를 먼저 등록하세요.\n");
        yaml.append("#  4) Maven: MAVEN_SETTINGS_FILE을 GitLab File 변수로 등록(mirrorOf=* 내부 Nexus).\n");
        yaml.append("#     settings의 server 인증은 ${env.NEXUS_MAVEN_USER}/${env.NEXUS_MAVEN_PASSWORD} 참조; 비밀번호는 Masked.\n");
        yaml.append("#     보호 변수 사용 시 브랜치/Runner 보호 범위도 일치시켜야 합니다. settings/비밀번호를 artifact에 넣지 마세요.\n");
        yaml.append("#  5) Dockerfile.release 또는 ARG RUNTIME_BASE_IMAGE를 쓰는 초안은 RUNTIME_BASE_IMAGE에 준비된 내부 런타임 이미지(@sha256)를 지정하세요.\n");
        yaml.append("# 파이프라인 타입: ").append(pipelineTypeNote(pipelineType)).append("\n");
        yaml.append("# 각 이미지 배포 단위의 결과물은 이미지입니다. 정적 파일도 이미지에서 추출합니다.\n");
    }

    private String pipelineTypeNote(PipelineType pipelineType) {
        return switch (pipelineType) {
            case DOCKER -> "Docker — 빌드 결과를 Dockerfile 로 이미지에 담습니다.";
            case JAR -> "JAR — 빌드한 jar 를 Dockerfile 로 이미지에 담아 컨테이너로 띄웁니다.";
            case STATIC -> "Static — 빌드한 정적 파일을 이미지에 담고, 배포할 때 그 안에서 꺼내 웹서버에 놓습니다.";
        };
    }

    private void appendBuildJob(StringBuilder yaml, Project project, String buildCommand, String runnerTag) {
        appendBuildJob(yaml, project, buildCommand, runnerTag, "build_app");
    }

    private void appendBuildJob(StringBuilder yaml, Project project, String buildCommand, String runnerTag, String jobName) {
        yaml.append(jobName).append(":\n");
        yaml.append("  stage: build\n");
        boolean maven = project.getBuildTool() == BuildToolType.MAVEN;
        yaml.append("  image: \"").append(maven ? "$MAVEN_BUILD_IMAGE" : "$CI_BUILD_IMAGE").append("\"\n");
        yaml.append("  tags:\n");
        yaml.append("    - ").append(runnerTag).append("\n");
        if (maven) {
            yaml.append("  before_script:\n");
            yaml.append("    - ").append(quoteScript("command -v mvn >/dev/null || { echo 'MAVEN_BUILD_IMAGE에 Maven을 설치하여 다시 반입하세요.' >&2; exit 1; }")).append("\n");
            yaml.append("    - ").append(quoteScript("command -v java >/dev/null || { echo 'MAVEN_BUILD_IMAGE에 프로젝트용 JDK를 설치하여 다시 반입하세요.' >&2; exit 1; }")).append("\n");
            yaml.append("    - ").append(quoteScript("test -f \"${MAVEN_SETTINGS_FILE:-}\" || { echo 'MAVEN_SETTINGS_FILE을 내부 Nexus용 GitLab File 변수로 등록하세요.' >&2; exit 1; }")).append("\n");
            yaml.append("    - ").append(quoteScript("test -n \"${NEXUS_MAVEN_USER:-}\" && test -n \"${NEXUS_MAVEN_PASSWORD:-}\" || { echo 'NEXUS_MAVEN_USER/PASSWORD 변수와 보호 브랜치 범위를 확인하세요.' >&2; exit 1; }")).append("\n");
        }
        yaml.append("  script:\n");
        yaml.append("    - ").append(quoteScript(buildCommand)).append("\n");

        /*
          산출물을 다음 단계로 넘긴다. 예전에는 STATIC 을 빼고 있었는데,
          이미지 단계가 다른 러너에서 돌면 빌드 결과가 없어 docker build 가 빈 것을 담는다.
          정적이야말로 넘겨야 하는 쪽이다 — 이미지에 들어갈 내용이 그 파일들이다.
        */
        String artifactPath = trimToNull(project.getArtifactPath());
        if (artifactPath == null && maven) artifactPath = "target/*.jar";
        if (artifactPath != null) {
            yaml.append("  artifacts:\n");
            yaml.append("    expire_in: 1 day\n");
            if (maven) {
                yaml.append("    when: always\n");
                yaml.append("    reports:\n");
                yaml.append("      junit:\n");
                yaml.append("        - 'target/surefire-reports/TEST-*.xml'\n");
                yaml.append("        - 'target/failsafe-reports/TEST-*.xml'\n");
            }
            yaml.append("    paths:\n");
            yaml.append("      - ").append(quoteScript(artifactPath)).append("\n");
        }
        yaml.append("\n");
    }

    /**
     * CI always builds the complete frozen component set. Deployment scope is applied by
     * the deployment engine, never by dropping artifacts from the CI release manifest.
     */
    private String generateComponents(Project project, List<DeployComponent> components) {
        List<ComponentBuild> builds = componentBuilds(project, components);
        String runnerTag = GitlabCiContract.runnerTag();
        StringBuilder yaml = new StringBuilder();
        appendHeader(yaml, project.getPipelineType() == null ? PipelineType.DOCKER : project.getPipelineType(), runnerTag);
        yaml.append("# 다중 배포 단위: 각 빌드 결과를 같은 성공 이미지 job의 전체 릴리스 명세로 결속합니다.\n");
        yaml.append("# 실제 배포 범위가 일부 역할이어도 CI는 등록된 이미지 배포 단위를 모두 빌드합니다.\n");
        yaml.append("# STATIC_BUNDLE 이미지는 /dist(실행 설정으로 변경 가능)에 정적 파일을 넣습니다.\n");
        yaml.append("workflow:\n  rules:\n    - if: '$CI_PIPELINE_SOURCE == \"api\"'\n      when: always\n    - when: never\n\n");
        yaml.append("stages:\n  - build\n  - image\n\nvariables:\n");
        yaml.append("  DEPLOY_IMAGE_TAG_PATTERN:\n    value: ").append(quoteScript(GitlabCiContract.imageTagPattern(project)))
            .append("\n    expand: false\n\n");
        for (ComponentBuild build : builds) {
            Project view = Project.builder().buildTool(build.tool()).artifactPath(build.artifact()).build();
            appendBuildJob(yaml, view, build.command(), runnerTag, build.job());
        }
        yaml.append(GitlabCiContract.IMAGE_JOB_NAME).append(":\n  stage: image\n  image: \"$DOCKER_BUILD_IMAGE\"\n");
        yaml.append("  tags:\n    - ").append(runnerTag).append("\n  needs:\n");
        builds.forEach(build -> yaml.append("    - ").append(build.job()).append("\n"));
        yaml.append("  script:\n");
        script(yaml, "IMAGE_TAG=$(printf '%s' \"$DEPLOY_IMAGE_TAG_PATTERN\""
            + " | sed -e \"s|\\${branch}|${CI_COMMIT_REF_SLUG}|g\""
            + " -e \"s|\\${shortSha}|${CI_COMMIT_SHORT_SHA}|g\""
            + " -e \"s|\\${commitSha}|${CI_COMMIT_SHA}|g\""
            + " -e \"s|\\${pipelineId}|${CI_PIPELINE_ID}|g\")");
        script(yaml, "test \"$IMAGE_TAG\" != latest && printf '%s' \"$IMAGE_TAG\" | grep -Eq '"
            + GitlabCiContract.IMAGE_TAG_REGEX + "' || { echo '릴리스 이미지 태그가 올바르지 않습니다.' >&2; exit 1; }");
        script(yaml, "printf '%s' \"$CI_PIPELINE_ID\" | grep -Eq '^[1-9][0-9]*$' && "
            + "printf '%s' \"$CI_COMMIT_SHA\" | grep -Eq '^[0-9a-fA-F]{40,64}$' || exit 1");
        script(yaml, "umask 077; printf '{\"schemaVersion\":1,\"pipelineId\":%s,\"commitSha\":\"%s\",\"components\":[' "
            + "\"$CI_PIPELINE_ID\" \"$CI_COMMIT_SHA\" > " + GitlabCiContract.RELEASE_ARTIFACT_FILE);
        HashSet<String> registries = new HashSet<>();
        for (int i = 0; i < builds.size(); i++) {
            ComponentBuild build = builds.get(i);
            String registry = build.repository().substring(0, build.repository().indexOf('/'));
            if (registries.add(registry)) {
                script(yaml, "printf '%s' \"$NEXUS_REGISTRY_PASSWORD\" | docker login " + shell(registry)
                    + " --username \"$NEXUS_REGISTRY_USER\" --password-stdin");
            }
            script(yaml, "COMPONENT_REPOSITORY=" + shell(build.repository()) + "; DOCKERFILE_PATH="
                + shell(build.dockerfile()) + "; DOCKER_CONTEXT_PATH=" + shell(build.context()));
            script(yaml, componentDockerBuild(build));
            script(yaml, "docker push \"$COMPONENT_REPOSITORY:$IMAGE_TAG\"");
            // Select this exact repository, rather than assuming RepoDigests[0] belongs to it.
            script(yaml, "COMPONENT_DIGEST=$(docker inspect --format='{{range .RepoDigests}}{{println .}}{{end}}' "
                + "\"$COMPONENT_REPOSITORY:$IMAGE_TAG\" | while IFS= read -r ref; do "
                + "case \"$ref\" in \"$COMPONENT_REPOSITORY\"@sha256:*) printf '%s\\n' \"${ref#*@}\";; esac; done); "
                + "printf '%s' \"$COMPONENT_DIGEST\" | grep -Eq '^sha256:[0-9a-f]{64}$' && "
                + "test \"${#COMPONENT_DIGEST}\" -eq 71 || { echo '이미지 저장소의 단일 digest를 확인할 수 없습니다.' >&2; exit 1; }");
            String prefix = i == 0 ? "" : ",";
            script(yaml, "printf '" + prefix + "{\"code\":\"%s\",\"repository\":\"%s\",\"tag\":\"%s\",\"digest\":\"%s\"}' "
                + shell(build.code()) + " \"$COMPONENT_REPOSITORY\" \"$IMAGE_TAG\" \"$COMPONENT_DIGEST\" >> "
                + GitlabCiContract.RELEASE_ARTIFACT_FILE);
            if (build.repository().equals(project.getImageRepository().trim())) {
                script(yaml, "printf 'IMAGE_REPOSITORY=%s\\nIMAGE_TAG=%s\\nIMAGE_DIGEST=%s\\nCI_COMMIT_SHA=%s\\nCI_PIPELINE_ID=%s\\n' "
                    + "\"$COMPONENT_REPOSITORY\" \"$IMAGE_TAG\" \"$COMPONENT_DIGEST\" \"$CI_COMMIT_SHA\" \"$CI_PIPELINE_ID\" > "
                    + GitlabCiContract.ARTIFACT_FILE);
            }
        }
        script(yaml, "printf ']}\\n' >> " + GitlabCiContract.RELEASE_ARTIFACT_FILE
            + "; test -s " + GitlabCiContract.ARTIFACT_FILE);
        yaml.append("  artifacts:\n    when: on_success\n    paths:\n      - ").append(GitlabCiContract.ARTIFACT_FILE)
            .append("\n      - ").append(GitlabCiContract.RELEASE_ARTIFACT_FILE)
            .append("\n    reports:\n      dotenv: ").append(GitlabCiContract.ARTIFACT_FILE)
            .append("\n    expire_in: 7 days\n");
        return yaml.toString();
    }

    private List<ComponentBuild> componentBuilds(Project project, List<DeployComponent> components) {
        List<ComponentBuild> result = new ArrayList<>();
        HashSet<String> codes = new HashSet<>(), repositories = new HashSet<>();
        for (DeployComponent component : components) {
            String code = trimToNull(component.getCode());
            if (code == null || !code.matches("[a-z][a-z0-9_-]{0,63}") || !codes.add(code))
                throw new IllegalArgumentException("다중 CI 배포 단위 코드는 중복 없는 영문 소문자·숫자·밑줄·하이픈이어야 합니다.");
            String repository = valueOrDefault(component.getImageRepository(), project.getImageRepository().trim());
            if (!repository.matches("[a-z0-9][a-z0-9.:-]*(/[a-z0-9][a-z0-9._-]*)+")
                    || !repositories.add(repository))
                throw new IllegalArgumentException("다중 CI 배포 단위마다 서로 다른 올바른 이미지 저장소가 필요합니다.");
            BuildToolType tool = component.getBuildTool() == null ? project.getBuildTool() : component.getBuildTool();
            String command = valueOrDefault(component.getBuildCommand(),
                valueOrDefault(project.getBuildCommand(), defaultBuildCommand(tool)));
            if (command.length() > 500 || command.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("배포 단위 빌드 명령은 제어 문자가 없는 500자 이내 한 줄로 입력하세요.");
            String dockerfile = relativePath(valueOrDefault(component.getDockerfilePath(),
                valueOrDefault(project.getDockerfilePath(), "Dockerfile")), false);
            String context = relativePath(valueOrDefault(component.getDockerContextPath(),
                valueOrDefault(project.getDockerContextPath(), ".")), false);
            String artifact = relativePath(valueOrDefault(component.getArtifactPath(),
                valueOrDefault(project.getArtifactPath(), tool == BuildToolType.MAVEN ? "target/*.jar" : null)), true);
            String root = artifactRoot(artifact);
            for (ComponentBuild previous : result) {
                String previousRoot = artifactRoot(previous.artifact());
                if (root.equals(previousRoot) || root.startsWith(previousRoot + "/") || previousRoot.startsWith(root + "/"))
                    throw new IllegalArgumentException("다중 CI의 산출물 경로가 겹칩니다. 배포 단위별로 별도 디렉터리를 사용하세요.");
            }
            result.add(new ComponentBuild(code, repository, tool, command, artifact, dockerfile, context));
        }
        if (!repositories.contains(project.getImageRepository().trim()))
            throw new IllegalArgumentException("프로젝트 대표 이미지 저장소와 일치하는 배포 단위가 하나 필요합니다.");
        return result;
    }

    private String relativePath(String value, boolean glob) {
        if (value == null || value.length() > 500 || value.startsWith("/") || value.startsWith("-")
                || value.contains("\\") || value.contains(":") || (glob && value.contains("$"))
                || value.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.asList(value.split("/", -1)).contains("..")
                || (!glob && (value.contains("*") || value.contains("?") || value.contains("[")))) {
            throw new IllegalArgumentException("Dockerfile·컨텍스트·산출물은 저장소 안의 상대 경로로 입력하세요. 산출물에만 glob을 사용할 수 있습니다.");
        }
        String normalized = java.util.Arrays.stream(value.split("/"))
            .filter(segment -> !segment.isEmpty() && !segment.equals("."))
            .collect(java.util.stream.Collectors.joining("/"));
        return normalized.isEmpty() ? "." : normalized;
    }

    private String artifactRoot(String path) {
        int end = path.length();
        for (char wildcard : new char[]{'*', '?', '['}) {
            int index = path.indexOf(wildcard);
            if (index >= 0) end = Math.min(end, index);
        }
        String prefix = path.substring(0, end);
        if (end < path.length()) prefix = prefix.substring(0, Math.max(0, prefix.lastIndexOf('/')));
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        if (prefix.isBlank() || prefix.equals("."))
            throw new IllegalArgumentException("다중 CI 산출물은 배포 단위별 하위 디렉터리를 지정하세요.");
        return prefix;
    }

    private String componentDockerBuild(ComponentBuild build) {
        String command = "docker build --network=none --pull=false ";
        if (build.tool() == BuildToolType.MAVEN && (build.dockerfile().equals("Dockerfile.release")
                || build.dockerfile().endsWith("/Dockerfile.release"))) {
            return "printf '%s' \"${RUNTIME_BASE_IMAGE:-}\" | grep -Eq '^.+@sha256:[0-9a-f]{64}$' || exit 1; "
                + "set -- \"$DOCKER_CONTEXT_PATH\"/target/*.jar; test \"$#\" -eq 1 && test -f \"$1\" || exit 1; "
                + "JAR_SHA256=$(sha256sum \"$1\" | cut -d ' ' -f 1); " + command
                + "--build-arg RUNTIME_BASE_IMAGE=\"$RUNTIME_BASE_IMAGE\" --build-arg JAR_SHA256=\"$JAR_SHA256\" "
                + "-f \"$DOCKERFILE_PATH\" -t \"$COMPONENT_REPOSITORY:$IMAGE_TAG\" \"$DOCKER_CONTEXT_PATH\"";
        }
        return "set --; if [ -n \"${RUNTIME_BASE_IMAGE:-}\" ]; then "
            + "printf '%s' \"$RUNTIME_BASE_IMAGE\" | grep -Eq '^.+@sha256:[0-9a-f]{64}$' || exit 1; "
            + "set -- --build-arg \"RUNTIME_BASE_IMAGE=$RUNTIME_BASE_IMAGE\"; fi; "
            + command + "\"$@\" -f \"$DOCKERFILE_PATH\" -t \"$COMPONENT_REPOSITORY:$IMAGE_TAG\" \"$DOCKER_CONTEXT_PATH\"";
    }

    private void script(StringBuilder yaml, String command) {
        yaml.append("    - ").append(quoteScript(command)).append("\n");
    }

    private String shell(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    private record ComponentBuild(String code, String repository, BuildToolType tool, String command,
                                  String artifact, String dockerfile, String context) {
        String job() { return "build_app_" + code; }
    }

    private void appendImageJob(StringBuilder yaml, Project project, PipelineType pipelineType, String runnerTag) {
        yaml.append("build_and_push_image:\n");
        if (pipelineType == PipelineType.STATIC) {
            /*
              배포할 때 이미지 안의 정해진 경로에서 정적 파일을 꺼낸다
              (RemoteDeployService, 기본 /dist — 시스템 설정 deploy.static.image-path).
              Dockerfile 이 다른 곳에 두면 배포는 성공했다고 하면서 빈 화면이 나간다.
            */
            yaml.append("  # Dockerfile 에서 정적 파일을 이미지의 /dist 에 두어야 합니다.\n");
            yaml.append("  # 배포할 때 그 경로에서 꺼내 갑니다(시스템 설정 deploy.static.image-path 로 바꿀 수 있음).\n");
        }
        yaml.append("  stage: image\n");
        yaml.append("  image: \"$DOCKER_BUILD_IMAGE\"\n");
        yaml.append("  tags:\n");
        yaml.append("    - ").append(runnerTag).append("\n");
        yaml.append("  needs:\n");
        yaml.append("    - build_app\n");
        yaml.append("  script:\n");
        yaml.append("    - ").append(quoteScript(
            "IMAGE_TAG=$(printf '%s' \"$DEPLOY_IMAGE_TAG_PATTERN\""
                + " | sed -e \"s|\\${branch}|${CI_COMMIT_REF_SLUG}|g\""
                + " -e \"s|\\${shortSha}|${CI_COMMIT_SHORT_SHA}|g\""
                + " -e \"s|\\${commitSha}|${CI_COMMIT_SHA}|g\""
                + " -e \"s|\\${pipelineId}|${CI_PIPELINE_ID}|g\")"
        )).append("\n");
        yaml.append("    - test -n \"$IMAGE_TAG\"\n");
        yaml.append("    - test \"$IMAGE_TAG\" != \"latest\"\n");
        yaml.append("    - ").append(quoteScript(
            "printf '%s' \"$IMAGE_TAG\" | grep -Eq '" + GitlabCiContract.IMAGE_TAG_REGEX
                + "' || { echo 'IMAGE_TAG가 Docker tag 규칙에 맞지 않거나 ${...} 토큰이 남았습니다.' >&2; exit 1; }"
        )).append("\n");
        yaml.append("    - echo \"$NEXUS_REGISTRY_PASSWORD\" | docker login \"${IMAGE_REPOSITORY%%/*}\" --username \"$NEXUS_REGISTRY_USER\" --password-stdin\n");
        yaml.append("    - ").append(quoteScript("if [ \"${DOCKERFILE_PATH##*/}\" = Dockerfile.release ]; then "
            + "printf '%s' \"${RUNTIME_BASE_IMAGE:-}\" | grep -Eq '^.+@sha256:[0-9a-f]{64}$' || { echo 'RUNTIME_BASE_IMAGE에 준비된 내부 런타임 digest를 지정하세요.' >&2; exit 1; }; "
            + "set -- \"$DOCKER_CONTEXT_PATH\"/target/*.jar; test \"$#\" -eq 1 && test -f \"$1\" || { echo 'release 빌드에는 target의 검증된 실행 JAR 한 개가 필요합니다.' >&2; exit 1; }; "
            + "JAR_SHA256=$(sha256sum \"$1\" | cut -d ' ' -f 1); "
            + "docker build --network=none --pull=false --build-arg RUNTIME_BASE_IMAGE=\"$RUNTIME_BASE_IMAGE\" --build-arg JAR_SHA256=\"$JAR_SHA256\" -f \"$DOCKERFILE_PATH\" -t \"$IMAGE_REPOSITORY:$IMAGE_TAG\" \"$DOCKER_CONTEXT_PATH\"; "
            + "else set --; if [ -n \"${RUNTIME_BASE_IMAGE:-}\" ]; then "
            + "printf '%s' \"$RUNTIME_BASE_IMAGE\" | grep -Eq '^.+@sha256:[0-9a-f]{64}$' || exit 1; "
            + "set -- --build-arg \"RUNTIME_BASE_IMAGE=$RUNTIME_BASE_IMAGE\"; fi; "
            + "docker build --network=none --pull=false \"$@\" -f \"$DOCKERFILE_PATH\" -t \"$IMAGE_REPOSITORY:$IMAGE_TAG\" \"$DOCKER_CONTEXT_PATH\"; fi")).append("\n");
        yaml.append("    - docker push \"$IMAGE_REPOSITORY:$IMAGE_TAG\"\n");
        yaml.append("    - IMAGE_DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' \"$IMAGE_REPOSITORY:$IMAGE_TAG\" | sed 's#^.*@##')\n");
        yaml.append("    - printf '%s' \"$IMAGE_DIGEST\" | grep -Eq '^sha256:[0-9a-fA-F]{64}$'\n");
        yaml.append("    - printf 'IMAGE_REPOSITORY=%s\\nIMAGE_TAG=%s\\nIMAGE_DIGEST=%s\\nCI_COMMIT_SHA=%s\\nCI_PIPELINE_ID=%s\\n' \"$IMAGE_REPOSITORY\" \"$IMAGE_TAG\" \"$IMAGE_DIGEST\" \"$CI_COMMIT_SHA\" \"$CI_PIPELINE_ID\" > ")
            .append(GitlabCiContract.ARTIFACT_FILE).append("\n");
        yaml.append("  artifacts:\n");
        yaml.append("    paths:\n");
        yaml.append("      - ").append(GitlabCiContract.ARTIFACT_FILE).append("\n");
        yaml.append("    reports:\n");
        yaml.append("      dotenv: ").append(GitlabCiContract.ARTIFACT_FILE).append("\n");
        yaml.append("    expire_in: 7 days\n");
    }

    /*
      막을 것만 막는다. 여기서 던지는 문장은 화면에 그대로 뜨므로
      "무엇이 없다" 가 아니라 <b>어디 가서 무엇을 채우면 되는지</b>까지 적는다.
      파이프라인 타입은 더 이상 막지 않는다 — 세 타입 모두 같은 모양으로 만들 수 있다.
    */
    private void validate(Project project) {
        if (project == null || project.getId() == null) {
            throw new IllegalArgumentException("프로젝트 정보를 읽을 수 없습니다.");
        }
        if (project.getGitlabProjectId() == null) {
            throw new IllegalArgumentException(
                "GitLab 저장소가 연결되어 있지 않아 예시를 만들 수 없습니다. "
                    + "프로젝트 상세 > GitLab 설정에서 저장소를 먼저 연결해 주세요.");
        }
        if (trimToNull(project.getImageRepository()) == null) {
            throw new IllegalArgumentException(
                "이미지 저장소가 비어 있어 예시를 만들 수 없습니다. "
                    + "프로젝트 상세 > CI/CD 설정에서 이미지 저장소를 먼저 지정해 주세요.");
        }
    }

    /**
     * 빌드 명령을 정해 두지 않았을 때 쓰는 기본값.
     *
     * <p>빌드 도구를 고르지 않았으면 <b>일부러 실패하는 명령</b>을 넣는다. 예전에는 {@code echo} 였는데,
     * 그러면 빌드 단계가 초록색으로 통과하고 이미지 단계에서 "담을 게 없다" 로 죽는다.
     * 원인에서 먼 자리에서 터지는 셈이라, 채워야 할 칸을 그 자리에서 말해 주는 편이 낫다.
     */
    private String defaultBuildCommand(BuildToolType buildTool) {
        if (buildTool == null || buildTool == BuildToolType.NONE) {
            return unsetBuildCommand();
        }
        return switch (buildTool) {
            case MAVEN -> "mvn -B -ntp --settings \"$MAVEN_SETTINGS_FILE\" clean verify";
            case GRADLE -> "./gradlew clean build";
            case NPM -> "npm ci && npm run build";
            case C -> "gcc -O2 -Wall -Wextra -o app main.c";
            case STATIC, NONE -> unsetBuildCommand();
        };
    }

    private String unsetBuildCommand() {
        return "echo '빌드 명령이 정해져 있지 않습니다. "
            + "프로젝트 상세 > CI/CD 설정의 빌드 명령을 채우고 이 줄을 바꿔 주세요.'; exit 1";
    }

    private String quoteScript(String command) {
        return "\"" + escapeYaml(command) + "\"";
    }

    private String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String valueOrDefault(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized != null ? normalized : defaultValue;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
