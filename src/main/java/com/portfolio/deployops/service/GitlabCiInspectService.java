package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.BuildToolType;
import com.portfolio.deployops.domain.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 이미 있는 {@code .gitlab-ci.yml} 에서 프로젝트 설정을 거꾸로 읽어 낸다.
 *
 * <p>프로젝트를 만들면 상세 화면에 30개 칸이 비어 있는 채로 나온다. 그런데 그중 상당수는
 * <b>저장소의 CI 파일에 이미 적혀 있는 값</b>이다 — 이미지 저장소, Dockerfile 경로, 빌드 명령.
 * 같은 값을 사람이 다시 옮겨 적게 하는 것은 일도 늘고 틀릴 여지도 생긴다.
 *
 * <p>여기서 하는 일은 <b>제안</b>까지다. 자동으로 저장하지 않는다 —
 * CI 파일 형태는 프로젝트마다 다르고, 잘못 읽은 값을 조용히 넣어 두면
 * 나중에 배포가 엉뚱한 이미지를 집는데 아무도 그 출처를 모른다.
 * 화면이 "이렇게 읽었습니다" 라고 보여 주고 사람이 받아들이게 한다.
 *
 * <p>YAML 파서를 쓰지 않고 줄 단위로 훑는다. 우리가 찾는 것은 문서 구조가 아니라
 * script 줄 안의 명령어라, 파싱해 봐야 결국 문자열을 다시 뒤져야 한다.
 */
@Service
public class GitlabCiInspectService {

    /* variables: 블록에 우리가 쓰는 이름으로 들어 있는 경우 — 가장 확실하다. */
    private static final Pattern VAR_IMAGE = var("IMAGE_REPOSITORY");
    private static final Pattern VAR_DOCKERFILE = var("DOCKERFILE_PATH");
    private static final Pattern VAR_CONTEXT = var("DOCKER_CONTEXT_PATH");

    /** {@code docker build -f Dockerfile.prod -t repo:tag .} */
    private static final Pattern BUILD_F = Pattern.compile("docker\\s+build[^\\n]*?-f\\s+\"?([^\"\\s]+)\"?");
    private static final Pattern BUILD_T = Pattern.compile("docker\\s+build[^\\n]*?-t\\s+\"?([^\"\\s]+?)\"?(?=\\s|$)");
    /** {@code docker push registry/ns/app:tag} */
    private static final Pattern PUSH = Pattern.compile("docker\\s+push\\s+\"?([^\"\\s]+?)\"?(?=\\s|$)");

    /** {@code artifacts: paths: - target/app.jar} 의 첫 항목. */
    private static final Pattern ARTIFACT = Pattern.compile(
        "artifacts:\\s*\\n(?:\\s+[^\\n]*\\n)*?\\s+paths:\\s*\\n\\s+-\\s*\"?([^\"\\n]+)");

    private static Pattern var(String name) {
        return Pattern.compile("^\\s*" + name + "\\s*:\\s*\"?([^\"\\n#]+?)\"?\\s*$", Pattern.MULTILINE);
    }

    /**
     * CI 파일에서 읽어 낸 값 중 <b>지금 프로젝트 설정과 다른 것만</b> 돌려준다.
     *
     * <p>이미 같은 값이면 제안할 이유가 없다 — 목록에 넣으면 "고칠 것" 처럼 보여
     * 진짜 비어 있는 칸이 묻힌다.
     *
     * @return 필드명 → 제안값. 읽어 낸 것이 없으면 빈 map.
     */
    public Map<String, String> suggest(String yaml, Project project) {
        Map<String, String> found = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) {
            return found;
        }

        // 변수 블록이 우선. CI 파일이 우리 템플릿에서 나온 것이면 여기서 다 걸린다.
        put(found, "imageRepository", first(VAR_IMAGE, yaml));
        put(found, "dockerfilePath", first(VAR_DOCKERFILE, yaml));
        put(found, "dockerContextPath", first(VAR_CONTEXT, yaml));

        // 없으면 실제 명령줄에서 찾는다. ${VAR} 형태는 값이 아니므로 버린다.
        if (!found.containsKey("imageRepository")) {
            put(found, "imageRepository", stripTag(literal(first(BUILD_T, yaml))));
        }
        if (!found.containsKey("imageRepository")) {
            put(found, "imageRepository", stripTag(literal(first(PUSH, yaml))));
        }
        if (!found.containsKey("dockerfilePath")) {
            put(found, "dockerfilePath", literal(first(BUILD_F, yaml)));
        }

        put(found, "artifactPath", literal(first(ARTIFACT, yaml)));

        BuildToolType tool = detectBuildTool(yaml);
        if (tool != null) {
            found.put("buildTool", tool.name());
        }

        // 이미 같은 값이면 뺀다.
        found.entrySet().removeIf(e -> e.getValue() == null
            || e.getValue().equals(currentValue(project, e.getKey())));
        return found;
    }

    /** script 줄에 무엇을 부르는지로 빌드 도구를 짐작한다. */
    private BuildToolType detectBuildTool(String yaml) {
        String s = yaml.toLowerCase();
        // 순서가 중요하다 — npm 은 어느 프로젝트에나 섞여 들 수 있어 마지막에 본다.
        if (s.contains("mvnw") || s.contains("mvn ")) {
            return BuildToolType.MAVEN;
        }
        if (s.contains("gradlew") || s.contains("gradle ")) {
            return BuildToolType.GRADLE;
        }
        if (s.contains("npm ") || s.contains("yarn ") || s.contains("pnpm ")) {
            return BuildToolType.NPM;
        }
        return null;
    }

    private String currentValue(Project p, String field) {
        return switch (field) {
            case "imageRepository" -> p.getImageRepository();
            case "dockerfilePath" -> p.getDockerfilePath();
            case "dockerContextPath" -> p.getDockerContextPath();
            case "artifactPath" -> p.getArtifactPath();
            case "buildTool" -> p.getBuildTool() == null ? null : p.getBuildTool().name();
            default -> null;
        };
    }

    private void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * {@code registry:5000/ns/app:1.2.3} 에서 태그만 떼어 낸다.
     *
     * <p>단순히 첫 콜론에서 자르면 안 된다 — 레지스트리 주소에는 포트가 붙어
     * {@code nexus.example.com:18443/pay/api:1.2.3} 이 {@code nexus.example.com} 이 되어 버린다.
     * 태그는 <b>마지막 콜론 뒤에 슬래시가 없을 때</b>만 태그다.
     */
    private String stripTag(String image) {
        if (image == null) {
            return null;
        }
        int colon = image.lastIndexOf(':');
        if (colon < 0 || image.indexOf('/', colon) >= 0) {
            return image;   // 콜론이 없거나, 뒤에 경로가 더 있으면 포트다
        }
        return image.substring(0, colon);
    }

    /** {@code $VAR} · {@code ${VAR}} 처럼 값이 아닌 것은 제안하지 않는다. */
    private String literal(String value) {
        if (value == null || value.startsWith("$")) {
            return null;
        }
        return value.contains("${") ? null : value;
    }
}
