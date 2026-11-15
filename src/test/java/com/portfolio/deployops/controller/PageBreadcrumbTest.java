package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 브레드크럼 문구가 서버와 JS 두 곳에 있다 — 그 둘이 어긋나지 않게 한다.
 *
 * <p>서버({@code PageViewController.PAGE_LABELS})는 최초 렌더를, JS({@code app.js} 의
 * {@code PAGE_META})는 SPA 전환을 담당한다. 한쪽에만 화면을 추가하면 진입 경로에 따라
 * 다른 문구가 나오거나 pageId 가 그대로 노출된다.
 *
 * <p>실제로 {@code mr-approvals} 가 {@code ALLOWED_PAGES} 에는 있고 {@code PAGE_META} 에는
 * 없어서, 그 화면의 브레드크럼이 "mr-approvals" 로 나오고 있었다.
 */
class PageBreadcrumbTest {

    private static final Path APP_JS =
        Path.of("src", "main", "resources", "static", "js", "app.js");

    /** app.js 의 PAGE_META 키 — {@code 'dashboard': { label: ... }} 형태. */
    private static Set<String> jsPageMetaKeys() throws Exception {
        String js = Files.readString(APP_JS);
        int start = js.indexOf("const PAGE_META");
        assertThat(start).as("app.js 에서 PAGE_META 를 찾지 못했다").isGreaterThan(-1);
        int end = js.indexOf("};", start);
        String block = js.substring(start, end);

        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([a-z-]+)'\\s*:\\s*\\{").matcher(block);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    @Test
    @DisplayName("이동 가능한 모든 화면에 브레드크럼 문구가 있어야 한다")
    void everyAllowedPageHasALabel() throws Exception {
        Set<String> jsKeys = jsPageMetaKeys();

        // ALLOWED_PAGES 는 private 이라 화면 목록을 템플릿 파일에서 얻는다 —
        // pages/*.html 하나가 곧 이동 가능한 화면 하나다.
        try (var s = Files.list(Path.of("src", "main", "resources", "templates", "pages"))) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".html")) {
                    continue;
                }
                String pageId = name.substring(0, name.length() - ".html".length());
                if (pageId.equals("error404")) {
                    continue; // 오류 화면은 브레드크럼 대상이 아니다
                }

                assertThat(PageViewController.pageLabel(pageId))
                    .as("서버에 '%s' 브레드크럼 문구가 없다 — pageId 가 그대로 노출된다", pageId)
                    .isNotEqualTo(pageId);

                assertThat(jsKeys)
                    .as("app.js PAGE_META 에 '%s' 가 없다 — SPA 전환 시 문구가 어긋난다", pageId)
                    .contains(pageId);
            }
        }
    }

    /**
     * 화면 제목(h1)이 이동경로의 마지막 조각과 같은 말인지 본다.
     *
     * <p>같은 화면을 두 이름으로 부르면 지금 어디에 있는지 매번 다시 읽어야 한다.
     * 실제로 이동경로는 "인프라 관리 &gt; 프로젝트" 인데 제목은 "프로젝트 관리",
     * "새 배포 요청" 인데 제목은 "배포 요청" 이라 목록 화면과 헷갈렸다.
     *
     * <p>제목을 고칠 때 이동경로도 같이 고치게 만드는 것이 목적이다.
     */
    @Test
    @DisplayName("화면 제목이 이동경로 마지막 조각과 같다")
    void pageTitleMatchesBreadcrumbLeaf() throws Exception {
        Pattern title = Pattern.compile("class=\"page-title\"[^>]*>([^<]+)");
        Path pages = Path.of("src", "main", "resources", "templates", "pages");

        try (var s = Files.list(pages)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".html")) {
                    continue;
                }
                String pageId = name.substring(0, name.length() - ".html".length());
                if (pageId.equals("error404")) {
                    continue;
                }
                Matcher m = title.matcher(Files.readString(p));
                if (!m.find()) {
                    continue;   // 제목이 없는 화면은 검사 대상이 아니다
                }
                assertThat(m.group(1).trim())
                    .as("'%s' 화면의 제목과 이동경로가 다른 말이다", pageId)
                    .isEqualTo(PageViewController.pageLeaf(pageId));
            }
        }
    }

    @Test
    @DisplayName("브레드크럼은 상위/하위로 쪼개진다")
    void labelSplitsIntoParentAndLeaf() {
        assertThat(PageViewController.pageParent("audit")).isEqualTo("시스템");
        assertThat(PageViewController.pageLeaf("audit")).isEqualTo("감사 로그");

        // 1단계 화면은 상위가 없다 — 화살표를 그리면 안 된다.
        assertThat(PageViewController.pageParent("dashboard")).isNull();
        assertThat(PageViewController.pageLeaf("dashboard")).isEqualTo("대시보드");
    }
}
