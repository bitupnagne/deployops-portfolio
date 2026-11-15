package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.databind.ObjectMapper;

/** Reads declarative manifests only; never evaluates Gradle, npm scripts, XML entities or source code. */
public class SourceBuildProfileService {
    public enum SourceType {
        MAVEN_BOOT("Spring Boot · Maven"), GRADLE_BOOT("Spring Boot · Gradle"),
        WAR_TOMCAT("WAR · 외부 Tomcat"), REACT_STATIC("React 정적 화면"),
        STATIC("정적 파일"), JAVA_PROC("Java PROC"), C("C 프로그램"), UNKNOWN("직접 선택");
        final String label;
        SourceType(String label) { this.label = label; }
    }
    private record Candidate(SourceType type, String tool, String path, String javaVersion) {}

    public Map<String, Object> inspect(Map<String, String> files, Project project, String requestedType) {
        List<String> evidence = new ArrayList<>(), warnings = new ArrayList<>();
        List<Candidate> found = new ArrayList<>();
        Map<String, String> scopedFiles = new LinkedHashMap<>();
        String configured = project == null ? null : project.getBuildFilePath();
        String context = project == null ? null : project.getDockerContextPath();
        for (var entry : files.entrySet()) {
            String path = entry.getKey(), text = entry.getValue();
            if (path == null || !path.matches("[A-Za-z0-9_./+-]+") || path.startsWith("/")
                    || path.startsWith("-") || Arrays.asList(path.split("/", -1)).contains("..")) {
                warnings.add("안전하게 분석할 수 없는 저장소 경로를 제외했습니다."); continue;
            }
            if (configured != null && !configured.isBlank() && !path.equals(configured)) continue;
            if ((configured == null || configured.isBlank()) && context != null && !context.isBlank()
                    && !context.equals(".") && !path.startsWith(context.replaceAll("/$", "") + "/")) continue;
            scopedFiles.put(path, text);
            if (path.equals("pom.xml") || path.endsWith("/pom.xml")) {
                try {
                    Document document = parsePom(text);
                    String packaging = tag(document, "packaging");
                    boolean boot = hasArtifact(document, "spring-boot-starter-parent")
                        || hasArtifact(document, "spring-boot-maven-plugin")
                        || hasBootStarter(document);
                    SourceType type = "war".equals(packaging) ? SourceType.WAR_TOMCAT
                        : boot ? SourceType.MAVEN_BOOT
                        : !tag(document, "mainClass").isBlank() ? SourceType.JAVA_PROC : SourceType.UNKNOWN;
                    String version = first(tag(document, "java.version"), tag(document, "maven.compiler.release"), tag(document, "maven.compiler.source"));
                    found.add(new Candidate(type, "MAVEN", path, version));
                    evidence.add(path + ": packaging=" + (packaging.isBlank() ? "jar(기본값)" : packaging)
                        + (boot ? ", Spring Boot 선언" : "") + (version.isBlank() ? "" : ", Java=" + version));
                } catch (Exception e) { warnings.add(path + ": XML을 안전하게 해석하지 못했습니다. 외부 엔티티·DOCTYPE는 허용하지 않습니다."); }
            } else if (path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")) {
                String declarative = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                boolean boot = declarative.contains("org.springframework.boot");
                boolean war = Pattern.compile("(?:id\\s*\\(?\\s*['\"]war['\"]|apply\\s+plugin\\s*:\\s*['\"]war['\"])").matcher(declarative).find();
                boolean application = Pattern.compile("(?:id\\s*\\(?\\s*['\"]application['\"]|mainClass(?:Name)?\\s*[=.])").matcher(declarative).find();
                SourceType type = war ? SourceType.WAR_TOMCAT : boot ? SourceType.GRADLE_BOOT
                    : application ? SourceType.JAVA_PROC : SourceType.UNKNOWN;
                found.add(new Candidate(type, "GRADLE", path, ""));
                evidence.add(path + ": " + (war ? "war 플러그인" : boot ? "Spring Boot 선언" : application ? "application/mainClass 선언" : "Gradle 파일만 확인"));
                warnings.add(path + ": Gradle 코드를 실행하지 않았습니다. 동적 플러그인·JDK toolchain·산출물은 빌드에서 확인하세요.");
            } else if (path.equals("package.json") || path.endsWith("/package.json")) {
                try {
                    var json = new ObjectMapper().readTree(text);
                    boolean react = json.path("dependencies").has("react") || json.path("devDependencies").has("react");
                    boolean build = !json.path("scripts").path("build").asText("").isBlank();
                    found.add(new Candidate(react ? SourceType.REACT_STATIC : SourceType.UNKNOWN, "NPM", path, ""));
                    evidence.add(path + ": " + (react ? "React 의존성" : "Node manifest") + (build ? ", build script 존재" : ""));
                    if (!build) warnings.add(path + ": build script가 없습니다. 빌드 명령을 직접 확인하세요.");
                    if (react) warnings.add("React의 SSR 서버 실행 여부와 실제 정적 산출물 디렉터리는 자동 확정하지 않습니다.");
                } catch (Exception e) { warnings.add(path + ": package.json을 해석하지 못했습니다."); }
            }
        }
        if (found.isEmpty() && warnings.isEmpty() && scopedFiles.keySet().stream().anyMatch(p -> p.equals("index.html") || p.endsWith("/index.html"))) {
            found.add(new Candidate(SourceType.STATIC, "STATIC", "", "")); evidence.add("index.html 정적 진입 파일 존재");
        }
        if (found.isEmpty() && warnings.isEmpty() && scopedFiles.keySet().stream().filter(p -> p.endsWith(".c")).count() == 1) {
            found.add(new Candidate(SourceType.C, "C", scopedFiles.keySet().stream().filter(p -> p.endsWith(".c")).findFirst().orElseThrow(), ""));
            evidence.add("C 진입 소스 1개 존재");
        }
        SourceType selected = null;
        if (requestedType != null && !requestedType.isBlank()) {
            try { selected = SourceType.valueOf(requestedType.trim()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("지원하는 프로젝트 유형을 선택해 주세요."); }
            if (selected == SourceType.UNKNOWN) selected = null;
        }
        boolean ambiguous = found.size() > 1;
        Candidate candidate = selected == null ? found.size() == 1 ? found.get(0) : null
            : found.stream().filter(c -> c.type == SourceType.valueOf(requestedType.trim())).findFirst().orElse(null);
        SourceType detected = candidate == null ? SourceType.UNKNOWN : candidate.type;
        SourceType effective = selected != null ? selected : detected;
        boolean required = selected == null && (ambiguous || effective == SourceType.UNKNOWN);
        if (ambiguous) warnings.add("빌드 진입 파일이 여러 개입니다. 프로젝트 유형과 저장된 빌드 파일·컨텍스트를 명시적으로 확인하세요.");
        if (selected != null && candidate == null) warnings.add("선택한 유형의 근거를 충분히 찾지 못했습니다. 명시적 선택에 따른 초안이며 실제 빌드 성공을 뜻하지 않습니다.");
        warnings.add("반입된 빌더·런타임 이미지와 소스의 Java/Node 버전, 의존성·실행 형식은 별도 검증해야 합니다.");
        if (candidate == null || candidate.javaVersion.isBlank()) warnings.add("Java 버전은 확정하지 않았습니다. 임의의 JDK 21로 변경하지 않습니다.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceType", detected.name()); result.put("selectedSourceType", selected == null ? null : selected.name());
        result.put("selectionRequired", required); result.put("recognition", selected != null ? "SELECTED" : ambiguous ? "AMBIGUOUS" : effective == SourceType.UNKNOWN ? "UNKNOWN" : "DETECTED");
        result.put("evidence", evidence); result.put("warnings", warnings); result.put("draftOnly", true);
        result.put("supportedSourceTypes", Arrays.stream(SourceType.values()).map(t -> Map.of("value", t.name(), "label", t.label)).toList());
        if (!required) {
            Map<String, String> settings = settings(effective, candidate);
            result.put("suggestions", settings);
            result.put("generatedDockerfile", dockerfile(effective, settings, project));
            if (effective == SourceType.WAR_TOMCAT) warnings.add("RUNTIME_BASE_IMAGE는 Servlet(javax/jakarta)·Java 버전과 호환되는 내부 Tomcat 이미지여야 합니다. WAR의 JEUS 전용 기능도 확인하세요.");
            if (effective == SourceType.WAR_TOMCAT) warnings.add("COPY에 일치하는 WAR는 반드시 하나여야 합니다. Gradle war/bootWar·plain 분류 산출물이 함께 있으면 사용할 파일 경로를 직접 지정하세요.");
            if (effective == SourceType.JAVA_PROC) warnings.add("PROC 초안은 실행 가능한 JAR를 전제로 합니다. Main-Class·런타임 의존성 포함과 종료·중복 실행 제어를 확인하세요.");
            if (effective == SourceType.STATIC) warnings.add("dist 디렉터리는 예시입니다. 저장소의 실제 정적 파일 경로에 맞춰 빌드 명령·산출물·COPY 경로를 함께 검토하세요.");
            if (effective == SourceType.C) warnings.add("C 산출물은 준비된 내부 빌더에서 먼저 컴파일해야 합니다. 실행 권한·CPU 아키텍처·libc 호환성을 확인하세요.");
            warnings.add("Dockerfile의 RUNTIME_BASE_IMAGE에는 준비된 내부 이미지(@sha256)를 지정하세요. 초안에 외부 이미지를 자동 지정하지 않습니다.");
        } else result.put("suggestions", Map.of());
        return result;
    }

    private Map<String, String> settings(SourceType type, Candidate candidate) {
        String root = candidate == null || !candidate.path.contains("/") ? "" : candidate.path.substring(0, candidate.path.lastIndexOf('/') + 1);
        String tool = candidate == null ? type == SourceType.GRADLE_BOOT ? "GRADLE" : type == SourceType.REACT_STATIC ? "NPM" : type == SourceType.STATIC ? "STATIC" : type == SourceType.C ? "C" : "MAVEN" : candidate.tool;
        String command = switch (tool) {
            case "MAVEN" -> "mvn -B -ntp --settings \"$MAVEN_SETTINGS_FILE\"" + (root.isEmpty() ? "" : " -f '" + root + "pom.xml'") + " clean verify";
            case "GRADLE" -> "./gradlew" + (root.isEmpty() ? "" : " -p '" + root.substring(0, root.length() - 1) + "'") + (type == SourceType.GRADLE_BOOT ? " clean test bootJar" : " clean build");
            case "NPM" -> root.isEmpty() ? "npm ci && npm run build" : "npm --prefix '" + root.substring(0, root.length() - 1) + "' ci && npm --prefix '" + root.substring(0, root.length() - 1) + "' run build";
            case "STATIC" -> "test -d dist";
            default -> "gcc -O2 -Wall -Wextra -o " + root + "app " + (candidate == null ? "main.c" : candidate.path);
        };
        String artifact = type == SourceType.C ? root + "app" : type == SourceType.STATIC || type == SourceType.REACT_STATIC ? root + "dist"
            : root + (tool.equals("GRADLE") ? "build/libs/*" : "target/*") + (type == SourceType.WAR_TOMCAT ? ".war" : ".jar");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("buildTool", tool); result.put("buildCommand", command); result.put("artifactPath", artifact);
        result.put("pipelineType", type == SourceType.STATIC || type == SourceType.REACT_STATIC ? "STATIC" : "DOCKER");
        if (candidate != null && !candidate.path.isBlank()) result.put("buildFilePath", candidate.path);
        return result;
    }

    private String dockerfile(SourceType type, Map<String, String> settings, Project project) {
        String artifact = settings.get("artifactPath");
        String context = project == null || project.getDockerContextPath() == null ? "." : project.getDockerContextPath();
        if (!context.equals(".") && artifact.startsWith(context + "/")) artifact = artifact.substring(context.length() + 1);
        if (!artifact.matches("[A-Za-z0-9_./*+-]+") || Arrays.asList(artifact.split("/")).contains("..")) throw new IllegalArgumentException("산출물 경로를 확인해 주세요.");
        String header = "# 검토용 초안: CI에서 빌드한 산출물을 이미지에 넣습니다.\nARG RUNTIME_BASE_IMAGE\nFROM ${RUNTIME_BASE_IMAGE}\n";
        if (type == SourceType.C) return header + "WORKDIR /opt/app\nCOPY " + artifact + " /opt/app/app\nUSER 10001:10001\nENTRYPOINT [\"/opt/app/app\"]\n";
        if (type == SourceType.WAR_TOMCAT) return header + "# 내부 Tomcat 이미지의 webapps 경로·Servlet/Java 호환성을 확인하세요.\nCOPY " + artifact + " /usr/local/tomcat/webapps/ROOT.war\nCMD [\"catalina.sh\",\"run\"]\n";
        if (type == SourceType.REACT_STATIC || type == SourceType.STATIC) return header + "# STATIC_BUNDLE 배포는 /dist에서 파일을 꺼냅니다. 실제 산출물 경로를 확인하세요.\nCOPY " + artifact + "/ /dist/\n";
        return header + "WORKDIR /opt/app\nCOPY " + artifact + " /opt/app/app.jar\nUSER 10001:10001\nENTRYPOINT [\"java\",\"-jar\",\"/opt/app/app.jar\"]\n";
    }

    private Document parsePom(String text) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        var builder = f.newDocumentBuilder();
        builder.setEntityResolver((a, b) -> { throw new SAXException("External entity refused"); });
        builder.setErrorHandler(new DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; } });
        return builder.parse(new InputSource(new StringReader(text)));
    }
    private String tag(Document d, String name) { var nodes = d.getElementsByTagName(name); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim(); }
    private boolean hasArtifact(Document d, String name) { var nodes = d.getElementsByTagName("artifactId"); for (int i = 0; i < nodes.getLength(); i++) if (name.equals(nodes.item(i).getTextContent().trim())) return true; return false; }
    private boolean hasBootStarter(Document d) { var nodes = d.getElementsByTagName("artifactId"); for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i).getTextContent().trim().startsWith("spring-boot-starter")) return true; return false; }
    private String first(String... values) { return Arrays.stream(values).filter(v -> !v.isBlank()).findFirst().orElse(""); }
}
