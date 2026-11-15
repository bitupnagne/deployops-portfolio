package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 화면에 박아 넣은 스크립트가 문법적으로 성립하는지 확인한다.
 *
 * <p>인라인 스크립트에 문법 오류가 하나 있으면 그 {@code <script>} 블록 전체가 통째로
 * 버려진다. 그런데 화면은 멀쩡히 그려진다 — 서버는 200 을 주고, 표도 버튼도 다 보인다.
 * 눌러야 비로소 아무 일도 일어나지 않는다는 걸 알게 되고, 그때 보이는 건
 * "저장이 안 되네" 뿐이라 원인이 저장 로직 어딘가에 있다고 믿게 된다.
 *
 * <p>실제로 승인 정책 화면이 이 상태로 배포된 적이 있다. 삭제 핸들러에 닫는 괄호가
 * 하나 더 붙어 있었고, 그 때문에 <b>같은 블록에 있던 저장·모달·사람 선택이 전부</b>
 * 정의되지 않았다. 브라우저 콘솔을 열어 {@code Unexpected token ')'} 를 보기 전까지는
 * 정책 저장 API 를 의심하며 시간을 썼다.
 *
 * <p>Node 로 검사한다. 없으면 건너뛴다 — 폐쇄망 빌드 서버에 Node 를 요구하고 싶지는
 * 않고, 개발자 장비에서 먼저 걸리는 것만으로도 이 사고는 막힌다.
 */
class TemplateScriptSyntaxTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** src 속성이 있는 스크립트는 외부 파일이므로 대상이 아니다. */
    private static final Pattern INLINE_SCRIPT =
        Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>", Pattern.DOTALL);

    /**
     * Thymeleaf 의 {@code [[${...}]]} / {@code [(...)]} 는 서버에서 값으로 치환되므로
     * 그대로는 JS 가 아니다. 자리만 지키도록 리터럴로 바꿔 놓고 검사한다.
     */
    private static final Pattern TH_INLINE = Pattern.compile("\\[[\\[(].*?[\\])]\\]", Pattern.DOTALL);

    @Test
    @DisplayName("템플릿에 박힌 스크립트가 모두 파싱된다")
    void inlineScriptsParse() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "Node 없음 — 스크립트 문법 검사를 건너뛴다");

        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = INLINE_SCRIPT.matcher(html);
                int index = 0;
                while (m.find()) {
                    String js = TH_INLINE.matcher(m.group(1)).replaceAll("null");
                    if (!js.isBlank()) {
                        String error = syntaxError(js);
                        if (error != null) {
                            broken.add(TEMPLATES.relativize(file) + " [script#" + index + "] " + error);
                        }
                    }
                    index++;
                }
            }
        }

        assertThat(broken)
            .as("문법이 깨진 스크립트 블록은 통째로 실행되지 않아 그 화면의 버튼이 전부 죽는다")
            .isEmpty();
    }

    /**
     * 같은 화면에 두 번 들어가도 그 화면의 스크립트가 다시 도는지 본다.
     *
     * <p>{@code navigate()} 는 조각의 {@code <script>} 를 전역에 다시 심는다. 그런데
     * {@code const}/{@code let} 로 만든 전역 바인딩은 요소를 지워도 사라지지 않는다.
     * 그래서 두 번째 진입에서 {@code Identifier 'x' has already been declared} 가 나고
     * <b>그 조각 전체가 실행되지 않는다</b>. 화면은 서버가 그려 주므로 멀쩡해 보이는데
     * 목록 데이터만 첫 진입 때 값에 멈춰 있고 버튼은 옛 함수를 부른다.
     *
     * <p>실제로 17개 화면 중 9개가 이 상태였다. 이름이 겹치면 더 나빠서,
     * {@code _csrf} 를 함께 쓰던 연동 설정 · 실행 정책 · AI 설정은 서로를 죽여
     * <b>첫 진입부터</b> 깨졌다.
     *
     * <p>검사 방법 — 스크립트를 자기 자신과 이어 붙여 파싱한다. 재진입과 정확히 같은
     * 조건이라 별도의 스코프 분석이 필요 없다. {@code var} 와 함수 선언은 재선언이
     * 허용되므로 통과하고, {@code const}/{@code let}/{@code class} 만 걸린다.
     */
    @Test
    @DisplayName("화면 조각 스크립트는 다시 심어도 파싱된다")
    void fragmentScriptsSurviveReinjection() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "Node 없음 — 재진입 검사를 건너뛴다");

        Path pages = TEMPLATES.resolve("pages");
        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.walk(pages)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = INLINE_SCRIPT.matcher(html);
                int index = 0;
                while (m.find()) {
                    String js = TH_INLINE.matcher(m.group(1)).replaceAll("null");
                    if (!js.isBlank() && syntaxError(js) == null) {
                        // 원본은 멀쩡한데 두 번 이어 붙이면 깨진다 = 재선언이 있다는 뜻
                        String error = syntaxError(js + "\n;\n" + js);
                        if (error != null) {
                            broken.add(pages.relativize(file) + " [script#" + index + "] " + error);
                        }
                    }
                    index++;
                }
            }
        }

        assertThat(broken)
            .as("최상위 const/let 은 SPA 재진입에서 조각 전체를 죽인다 — var 를 쓸 것")
            .isEmpty();
    }

    /**
     * 화면 조각 파일에서 {@code </th:block>} 뒤에 내용이 남아 있지 않은지 본다.
     *
     * <p>각 화면은 {@code <th:block th:fragment="content">} 안쪽만 렌더링된다.
     * 그 바깥에 있는 것은 <b>브라우저까지 아예 가지 않는다</b> — 그런데 파일을 열어 보면
     * 멀쩡히 있으므로 코드를 읽는 사람은 동작한다고 믿는다.
     *
     * <p>실제로 프로젝트 상세의 배포 로그 패널과 AI 로그 분석 코드 90여 줄이
     * 이 바깥에 있었다. 화면에는 버튼이 보이는데 {@code loadDeployLogs} 는 정의된 적이
     * 없어 눌러도 아무 일이 없었고, 콘솔 오류조차 눌러야만 나므로 "로그가 없나 보다" 로
     * 넘어갔다. 이 검사는 Node 없이도 돈다.
     *
     * <p>닫는 {@code </body>} · {@code </html>} 는 파일 형식을 갖추기 위한 것이라 통과시킨다.
     */
    @Test
    @DisplayName("화면 조각 바깥에 내용이 남아 있지 않다")
    void nothingIsStrandedOutsideTheFragment() throws Exception {
        Path pages = TEMPLATES.resolve("pages");
        List<String> stranded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(pages)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                int end = html.lastIndexOf("</th:block>");
                if (end < 0) {
                    continue;
                }
                String tail = html.substring(end + "</th:block>".length());
                String meaningful = tail.replaceAll("</?(body|html)>", "").replaceAll("\\s+", "");
                if (!meaningful.isEmpty()) {
                    stranded.add(pages.relativize(file) + " — " + tail.strip().length() + "자 남음");
                }
            }
        }

        assertThat(stranded)
            .as("조각 바깥의 내용은 렌더링되지 않아 화면에 전달되지 않는다")
            .isEmpty();
    }

    /** 문법 오류가 있으면 그 메시지를, 멀쩡하면 null 을 준다. */
    private String syntaxError(String js) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("tpl-script-", ".js");
        try {
            Files.writeString(tmp, js, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("node", "--check", tmp.toString())
                .redirectErrorStream(true)
                .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;   // 멈춘 건 문법 문제가 아니다 — 조용히 넘긴다
            }
            if (p.exitValue() == 0) {
                return null;
            }
            return output.lines()
                .filter(l -> l.contains("Error"))
                .findFirst()
                .orElse("파싱 실패");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
