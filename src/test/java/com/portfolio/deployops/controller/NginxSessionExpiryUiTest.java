package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NginxSessionExpiryUiTest {

    private static final Path TEMPLATE = Path.of(
        "src/main/resources/templates/pages/nginx-policies.html");

    @Test
    @DisplayName("Nginx API 세션 만료 HTML 응답은 로그인 안내로 전환하고 JSON 응답은 그대로 처리한다")
    void redirectsLoginHtmlAndPreservesJsonHandling() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "Node 없음 — UI 응답 처리 검사를 건너뛴다");

        String html = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        int functionStart = html.indexOf("function nginxResult(response)");
        int functionEnd = html.indexOf("function toggleTlsFields()", functionStart);
        assertThat(functionStart).isGreaterThanOrEqualTo(0);
        assertThat(functionEnd).isGreaterThan(functionStart);
        int scriptBodyStart = html.indexOf('>', html.lastIndexOf("<script", functionStart)) + 1;
        String functions = html.substring(scriptBodyStart, functionEnd);

        String harness = """
            const assert = require('node:assert/strict');
            const window = { location: { origin: 'http://deploy.local', assigned: null,
              assign(path) { this.assigned = path; } } };
            %s
            (async () => {
              const loginHtml = {
                ok: true, redirected: true, url: 'http://deploy.local/login',
                headers: { get: () => 'text/html; charset=utf-8' },
                json: () => { throw new Error('HTML must not be parsed as JSON'); }
              };
              assert.throws(() => nginxResult(loginHtml), /로그인 세션이 만료되었습니다/);
              assert.equal(window.location.assigned, '/login?expired=1');

              const validJson = {
                ok: true, redirected: false, url: 'http://deploy.local/api/v1/nginx-changes/servers/1/inspect',
                headers: { get: () => 'application/json' },
                json: async () => ({ success: true, data: { output: 'nginx ok' } })
              };
              assert.deepEqual(await nginxResult(validJson), { success: true, data: { output: 'nginx ok' } });
              assert.equal(window.location.assigned, '/login?expired=1');

              const unexpectedText = {
                ok: true, redirected: false, url: 'http://deploy.local/api/v1/nginx-changes/servers/1/inspect',
                headers: { get: () => 'text/plain' }, json: async () => ({})
              };
              assert.throws(() => nginxResult(unexpectedText), /서버 응답을 확인할 수 없습니다/);
            })().catch(error => { console.error(error); process.exitCode = 1; });
            """.formatted(functions);

        Path script = Files.createTempFile("nginx-session-expiry-", ".js");
        try {
            Files.writeString(script, harness, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("node", script.toString())
                .redirectErrorStream(true).start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AssertionError("Nginx session-expiry UI test timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }
}
