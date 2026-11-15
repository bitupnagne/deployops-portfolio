package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 답변을 그리는 {@code md.js} 가 <b>모델이 뱉은 글자를 실행 가능한 것으로 만들지 않는지</b> 지킨다.
 *
 * <p>이 렌더러는 {@code innerHTML} 에 문자열을 직접 넣는다. 그래서 이스케이프 순서가
 * 한 번만 틀려도 모델 답변 안의 {@code <script>} 가 그대로 실행된다. 그리고 이 답변은
 * <b>배포 로그 본문을 재료로 만들어진다</b> — 로그에 남는 문자열은 우리가 통제하지 못한다.
 * 빌드 출력, 컨테이너 stdout, 원격 명령의 stderr 어디에든 남의 글자가 들어올 수 있다.
 *
 * <p>Node 로 실제 함수를 돌려 확인한다. 없으면 건너뛴다.
 */
class MarkdownRendererTest {

    private static final Path MD_JS = Path.of("src/main/resources/static/js/md.js");

    @Test
    @DisplayName("답변 속 태그는 글자로만 남는다 — 실행되지 않는다")
    void htmlInAnswerIsNeutralised() throws Exception {
        String html = render("""
            일반 문단 <script>alert(1)</script> 입니다.
            <img src=x onerror=alert(2)>
            [링크](javascript:alert(3))
            """);

        // 태그가 태그로 살아 있으면 안 된다. 글자로 남는 것(&lt;img ...&gt;)은 무해하므로
        // "onerror 라는 낱말이 없을 것" 까지 요구하지 않는다 — 그건 지나쳐서 오탐이 된다.
        assertThat(html).doesNotContain("<script").doesNotContain("<img");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;img");
        // 마크다운 링크는 글자만 남긴다 — javascript: 주소가 href 로 살아나면 안 된다.
        assertThat(html).doesNotContain("<a ").doesNotContain("href");
        assertThat(html).contains("링크").doesNotContain("링크)");
    }

    @Test
    @DisplayName("번호 목록 안에 글머리표가 끼어도 번호가 이어진다")
    void nestedBulletsDoNotRestartNumbering() throws Exception {
        String html = render("""
            1. 첫째
               - 하위 항목
            2. 둘째
               - 하위 항목
            3. 셋째
            """);

        // <ol> 이 한 번만 열려야 1·2·3 으로 이어진다.
        // 중첩 때문에 부모를 닫아 버리면 화면에 1·1·1 로 찍힌다 — 실제로 그랬다.
        assertThat(count(html, "<ol")).isEqualTo(1);
        assertThat(count(html, "<ul")).isEqualTo(2);
        assertThat(count(html, "<li>")).isEqualTo(count(html, "</li>"));
    }

    @Test
    @DisplayName("코드블록 안의 마크다운 기호는 그대로 둔다")
    void codeBlockContentIsNotFormatted() throws Exception {
        String html = render("""
            아래 명령을 실행하세요.

            ```bash
            grep -r "**" /var/log
            docker ps -a | grep _test_
            ```
            """);

        assertThat(html).contains("<pre class=\"md-code\"").contains("data-lang=\"bash\"");
        // 코드 안의 ** 가 <strong> 으로 바뀌면 명령을 그대로 복사할 수 없게 된다.
        assertThat(html).contains("**");
        assertThat(html).doesNotContain("<strong>");
    }

    @Test
    @DisplayName("자리표시자가 화면에 새어 나오지 않는다")
    void placeholdersNeverLeak() throws Exception {
        String html = render("설명 문장 ```echo hi``` 그리고 이어지는 문장");
        assertThat(html).doesNotContain("BLOCK");
    }

    private int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** md.js 를 Node 에서 그대로 불러 렌더 결과를 받아 온다. */
    private String render(String markdown) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "Node 없음 — 마크다운 렌더 검사를 건너뛴다");

        Path script = Files.createTempFile("md-render-", ".js");
        Path input = Files.createTempFile("md-input-", ".txt");
        try {
            Files.writeString(input, markdown, StandardCharsets.UTF_8);
            Files.writeString(script, """
                const fs = require('fs');
                global.document = { createElement: () => ({}) };
                eval(fs.readFileSync(%s, 'utf8'));
                process.stdout.write(mdRender(fs.readFileSync(%s, 'utf8')));
                """.formatted(quote(MD_JS.toString()), quote(input.toString())),
                StandardCharsets.UTF_8);

            Process p = new ProcessBuilder("node", script.toString())
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("node 가 응답하지 않음");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("md.js 실행 실패:\n" + out);
            }
            return out;
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(input);
        }
    }

    /** 윈도우 경로의 역슬래시가 JS 문자열에서 이스케이프로 읽히지 않게 감싼다. */
    private String quote(String path) {
        return "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'";
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
