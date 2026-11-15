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
 * 화면 템플릿의 접근성 규약을 지킨다.
 *
 * <p>{@link PageTemplateTest} 와 같은 부류의 검사다 — 서버는 200 을 돌려주고 마우스로도
 * 멀쩡히 동작하지만, <b>키보드나 스크린리더로는 쓸 수 없는</b> 상태를 잡는다.
 * 브라우저로 눈으로 봐서는 드러나지 않아서 사람이 확인해도 통과해 버린다.
 *
 * <p>2026-07-30 감사에서 실제로 이 셋이 전부 0 이었다 —
 * {@code for=} 0/155 · {@code scope=} 0/140 · 사이드바 앵커 15개 전부 {@code href} 없음.
 */
class TemplateAccessibilityTest {

    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");
    private static final Path PAGES = TEMPLATES.resolve("pages");
    private static final Path LAYOUT = TEMPLATES.resolve("layout").resolve("main.html");

    /** {@code <label class="form-label" ...>} 여는 태그. */
    private static final Pattern FORM_LABEL =
        Pattern.compile("<label[^>]*class=\"[^\"]*\\bform-label\\b[^\"]*\"[^>]*>");

    /** label 바로 뒤에 붙는 실제 폼 컨트롤. 읽기전용 {@code <div class="form-input">} 는 제외된다. */
    private static final Pattern NEXT_CONTROL =
        Pattern.compile("<(input|select|textarea)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ID_ATTR = Pattern.compile("\\bid=\"([^\"]+)\"");

    /** {@code <a class="nav-item" ...>} — 사이드바 메뉴 항목. */
    private static final Pattern NAV_ITEM = Pattern.compile("<a class=\"nav-item\"[^>]*>");

    private static List<Path> pageTemplates() throws IOException {
        try (Stream<Path> s = Files.list(PAGES)) {
            return s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    @Test
    @DisplayName("폼 라벨은 입력칸과 연결되어야 한다 — 아니면 스크린리더가 이름 없는 칸으로 읽는다")
    void everyFormLabelIsAssociatedWithItsControl() throws Exception {
        // <label>서버명</label><input id="server-name"> 처럼 나란히만 두면 시각적으로는 붙어 보이지만
        // 프로그램적 연결이 없다. 보조기술은 "편집창"이라고만 읽어 무엇을 넣는 칸인지 알 수 없고,
        // 라벨을 클릭해도 칸에 포커스가 가지 않는다.
        List<String> offenders = new ArrayList<>();

        for (Path page : pageTemplates()) {
            String html = Files.readString(page);
            Matcher m = FORM_LABEL.matcher(html);
            while (m.find()) {
                String tag = m.group();
                if (tag.contains("for=")) {
                    continue;
                }
                int close = html.indexOf("</label>", m.end());
                if (close < 0) {
                    continue;
                }
                Matcher ctrl = NEXT_CONTROL.matcher(html);
                if (!ctrl.find(close)) {
                    continue;
                }
                // 라벨과 컨트롤 사이에 다른 여는 태그가 있으면 짝이 아니다(읽기전용 div 표시 등).
                String between = html.substring(close + "</label>".length(), ctrl.start());
                if (between.matches("(?s).*<[a-zA-Z].*")) {
                    continue;
                }
                Matcher id = ID_ATTR.matcher(ctrl.group(2));
                if (!id.find()) {
                    continue; // id 가 없으면 for= 로 가리킬 대상 자체가 없다 — 별도 문제다.
                }
                offenders.add(page.getFileName() + " → " + id.group(1));
            }
        }

        assertThat(offenders)
            .as("form-label 에 for= 가 없다. 이 입력칸들은 보조기술에서 이름 없이 읽힌다.")
            .isEmpty();
    }

    @Test
    @DisplayName("표 머리글에는 scope 가 있어야 한다 — 아니면 셀과 머리글이 연결되지 않는다")
    void everyTableHeaderDeclaresScope() throws Exception {
        // 스크린리더는 셀을 읽을 때 "어느 열인지"를 함께 말해 준다. scope 가 없으면
        // 표가 클수록 값만 줄줄이 읽히고 무엇에 대한 값인지 알 수 없다.
        //
        // 주의: Thymeleaf 의 <th:block> 은 표 머리글이 아니다. 실제로 이 검사를 만들다
        // <th\b 로 매칭해 <th:block> 19개를 망가뜨린 적이 있어, 뒤에 공백/> 만 허용한다.
        Pattern th = Pattern.compile("<th(?=[\\s>])([^>]*)>");
        List<String> offenders = new ArrayList<>();

        List<Path> all = new ArrayList<>(pageTemplates());
        all.add(LAYOUT);
        for (Path page : all) {
            String html = Files.readString(page);
            Matcher m = th.matcher(html);
            while (m.find()) {
                if (!m.group(1).contains("scope=")) {
                    offenders.add(page.getFileName() + " → " + m.group().trim());
                }
            }
        }

        assertThat(offenders)
            .as("<th> 에 scope 가 없다. 표의 값과 머리글이 연결되지 않는다.")
            .isEmpty();
    }

    @Test
    @DisplayName("사이드바 메뉴는 키보드로 이동할 수 있어야 한다")
    void sidebarNavItemsAreKeyboardReachable() throws Exception {
        // href 없는 <a> 는 포커스를 받지 못한다. 탭 키로 도달할 수 없고 링크로 읽히지도 않아
        // 마우스를 쓸 수 없는 사용자에게는 메뉴 전체가 존재하지 않는 것과 같다.
        // 실제 경로(/deploy/{pageId})를 넣으면 새 탭 열기와 주소 미리보기도 함께 살아난다.
        String html = Files.readString(LAYOUT);
        List<String> offenders = new ArrayList<>();

        Matcher m = NAV_ITEM.matcher(html);
        while (m.find()) {
            String tag = m.group();
            if (!tag.contains("th:href=") && !tag.contains(" href=")) {
                offenders.add(tag.trim());
            }
        }

        assertThat(offenders)
            .as("href 없는 nav-item 이 있다. 이 메뉴는 키보드로 도달할 수 없다.")
            .isEmpty();
        // 검사가 항목을 하나도 못 찾고 조용히 통과하는 것을 막는다.
        assertThat(NAV_ITEM.matcher(html).results().count())
            .as("nav-item 을 찾지 못했다 — 셀렉터가 낡았는지 확인할 것")
            .isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("모달 닫기 버튼에는 접근 가능한 이름이 있어야 한다")
    void modalCloseButtonsHaveAccessibleNames() throws Exception {
        // 아이콘(SVG)만 든 버튼은 읽을 텍스트가 없다. aria-label 이 없으면 "버튼"으로만 읽혀
        // 무엇을 하는 버튼인지 알 수 없다.
        Pattern closeBtn = Pattern.compile("<button[^>]*class=\"[^\"]*\\bmodal-close\\b[^\"]*\"[^>]*>");
        List<String> offenders = new ArrayList<>();

        List<Path> all = new ArrayList<>(pageTemplates());
        all.add(LAYOUT);
        for (Path page : all) {
            String html = Files.readString(page);
            Matcher m = closeBtn.matcher(html);
            while (m.find()) {
                String tag = m.group();
                if (!tag.contains("aria-label=") && !tag.contains("title=")) {
                    offenders.add(page.getFileName() + " → " + tag.trim());
                }
            }
        }

        assertThat(offenders)
            .as("modal-close 버튼에 접근 가능한 이름이 없다.")
            .isEmpty();
    }
}
