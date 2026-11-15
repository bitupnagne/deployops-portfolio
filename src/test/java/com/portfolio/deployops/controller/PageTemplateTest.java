package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 화면 템플릿의 규약을 지킨다.
 *
 * <p>여기서 잡는 것은 "서버는 200 을 돌려주는데 화면은 쓸 수 없는" 부류다.
 * HTTP 상태코드로는 절대 드러나지 않아서, curl 로 확인하면 정상으로 보인다.
 */
class PageTemplateTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "templates", "pages");

    /** {@code <div ... class="...modal-overlay..." ...>} 여는 태그 하나. */
    private static final Pattern MODAL_TAG =
        Pattern.compile("<div[^>]*class=\"[^\"]*modal-overlay[^\"]*\"[^>]*>");

    private static List<Path> pageTemplates() throws IOException {
        try (Stream<Path> s = Files.list(PAGES)) {
            return s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    @Test
    @DisplayName("notice-box 안의 문장은 한 덩어리로 감싸야 한다 — 아니면 세로로 쪼개진다")
    void noticeBoxContentIsWrappedInASingleBlock() throws Exception {
        // .notice-box 는 display:flex 다(아이콘 + 내용 두 칸을 나란히 두려고).
        // 그래서 안에 <b> 나 <strong> 을 섞어 문장을 쓰면 그 조각들이 각각 flex 아이템이 되어
        // 한 줄로 흐르지 않고 세로 컬럼으로 쪼개진다 — 문장이 읽을 수 없게 된다.
        //
        // 실제로 exec-policy · profile · project-detail 세 화면이 이 상태였고,
        // 새 화면(server-map)을 만들다 같은 함정을 다시 밟았다.
        // 해결은 간단하다: 내용을 <div> 하나로 감싼다.
        // 여는 태그를 찾을 때 <b>따옴표 안의 &gt; 를 태그 끝으로 보면 안 된다</b>.
        // th:if="${n > 0}" 처럼 비교 연산자가 들어가면 [^>]* 가 거기서 끊긴다.
        // 그러면 잘린 속성 조각이 "내용"으로 읽혀 멀쩡한 화면이 위반으로 잡히고(실제로 그랬다),
        // 반대로 그런 태그를 쓴 진짜 위반은 통째로 놓친다.
        // 따옴표 구간을 통으로 건너뛰어 태그 경계를 제대로 찾는다.
        String attrs = "(?:[^>\"]|\"[^\"]*\")*";
        Pattern open = Pattern.compile(
            "<div" + attrs + "class=\"[^\"]*\\bnotice-box\\b[^\"]*\"" + attrs + ">");
        List<String> offenders = new ArrayList<>();

        List<Path> all = new ArrayList<>(pageTemplates());
        all.add(Path.of("src", "main", "resources", "templates", "layout", "main.html"));
        for (Path page : all) {
            String html = Files.readString(page);
            Matcher m = open.matcher(html);
            while (m.find()) {
                // 여는 태그 바로 뒤 내용을 본다. 텍스트가 먼저 나오고 그 뒤에 인라인 태그가
                // 이어지면 flex 아이템이 둘 이상이 된다.
                String rest = html.substring(m.end(), Math.min(html.length(), m.end() + 400));
                int close = rest.indexOf("</div>");
                if (close < 0) {
                    continue;
                }
                String inner = rest.substring(0, close).trim();
                boolean startsWithText = !inner.isEmpty() && inner.charAt(0) != '<';
                boolean hasInlineTag = inner.contains("<b>") || inner.contains("<strong>");
                if (startsWithText && hasInlineTag) {
                    offenders.add(page.getFileName() + " → " + inner.replaceAll("\\s+", " ").substring(0,
                        Math.min(60, inner.replaceAll("\\s+", " ").length())));
                }
            }
        }

        assertThat(offenders)
            .as(".notice-box 안의 문장이 감싸여 있지 않다. flex 때문에 세로로 쪼개져 보인다.")
            .isEmpty();
    }

    @Test
    @DisplayName("모달은 기본이 숨김이어야 한다 — 아니면 화면 전체가 막힌다")
    void everyModalOverlayStartsHidden() throws Exception {
        // .modal-overlay 는 position:fixed + inset:0 이고 CSS 에 display 규칙이 없다.
        // 그래서 인라인 style="display:none" 이 없으면 처음부터 화면을 덮어 버리고,
        // 그 위의 모든 클릭을 가로챈다 — 페이지가 통째로 먹통이 된다.
        // 실제로 server-groups.html 에서 이 선언을 빠뜨려 그 증상이 났다.
        List<String> offenders = new ArrayList<>();

        for (Path page : pageTemplates()) {
            String html = Files.readString(page);
            Matcher m = MODAL_TAG.matcher(html);
            while (m.find()) {
                String tag = m.group();
                if (!tag.replace(" ", "").contains("display:none")) {
                    offenders.add(page.getFileName() + " → " + tag.trim());
                }
            }
        }

        assertThat(offenders)
            .as("모달에 style=\"display:none\" 이 없다. 이 화면은 열자마자 아무것도 눌리지 않는다.")
            .isEmpty();
    }

    @Test
    @DisplayName("모달이 하나라도 있으면 닫는 방법이 있어야 한다")
    void everyPageWithAModalCanCloseIt() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (Path page : pageTemplates()) {
            String html = Files.readString(page);
            if (!MODAL_TAG.matcher(html).find()) {
                continue;
            }
            // 닫기 버튼(modal-close) 또는 취소 버튼이 없으면 사용자가 빠져나올 수 없다.
            boolean closable = html.contains("modal-close") || html.contains(">취소<");
            if (!closable) {
                offenders.add(page.getFileName().toString());
            }
        }

        assertThat(offenders).as("모달을 닫을 수단이 없는 화면").isEmpty();
    }
}
