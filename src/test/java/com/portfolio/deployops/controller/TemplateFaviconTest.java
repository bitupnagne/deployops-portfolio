package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateFaviconTest {

    private static final Path TEMPLATE_ROOT = Path.of("src", "main", "resources", "templates");
    private static final Path FAVICON = Path.of(
        "src", "main", "resources", "static", "img", "assistant-mark.svg");
    private static final String FAVICON_LINK =
        "<link rel=\"icon\" type=\"image/svg+xml\" th:href=\"@{/img/assistant-mark.svg}\">";

    @Test
    @DisplayName("모든 독립 head는 존재하는 로컬 SVG favicon을 명시한다")
    void everyDocumentHeadReferencesTheBundledFavicon() throws Exception {
        List<Path> documents = List.of(
            TEMPLATE_ROOT.resolve("layout/main.html"),
            TEMPLATE_ROOT.resolve("login.html"),
            TEMPLATE_ROOT.resolve("otp.html"),
            TEMPLATE_ROOT.resolve("otp-setup.html")
        );

        for (Path document : documents) {
            assertThat(Files.readString(document))
                .as("%s favicon link", document.getFileName())
                .contains(FAVICON_LINK);
        }
        assertThat(FAVICON).exists().isRegularFile();
        assertThat(Files.size(FAVICON)).isPositive();
    }
}
