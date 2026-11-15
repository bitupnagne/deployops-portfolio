package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Anthropic 요청에 temperature 를 실을지 판별하는 규칙을 고정한다.
 *
 * <p>모델 입력이 자유 텍스트라 사용자가 최신 모델명을 넣을 수 있는데, Opus 4.7 부터는
 * temperature 가 400 으로 거절된다 — 실어 보내면 AI 기능 전체가 죽는다.
 * 빼고 보내는 것은 어느 모델에서도 안전하므로 "모르면 뺀다"가 이 규칙의 기본값이다.
 */
class AiChatServiceTest {

    @Test
    void plainHttpCompatibleEndpointReceivesCompleteJsonWithoutH2cUpgrade() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var wire = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var header = new ByteArrayOutputStream();
                    int end = 0;
                    while (end != 0x0d0a0d0a) {
                        int value = input.read();
                        if (value < 0 || header.size() >= 16384) {
                            throw new java.io.IOException("Incomplete or oversized HTTP header");
                        }
                        header.write(value);
                        end = (end << 8) | value;
                    }
                    String[] lines = header.toString(StandardCharsets.ISO_8859_1).split("\r\n");
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (int i = 1; i < lines.length; i++) {
                        int colon = lines[i].indexOf(':');
                        if (colon <= 0) throw new java.io.IOException("Malformed HTTP header");
                        headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                    }
                    int size = Integer.parseInt(headers.get("content-length"));
                    if (size < 1 || size > 16384) throw new java.io.IOException("HTTP body bound");
                    byte[] body = input.readNBytes(size);
                    if (body.length != size) throw new java.io.IOException("Incomplete HTTP body");
                    byte[] response = ("{\"choices\":[{\"message\":{\"content\":\"wire-reply\"},"
                        + "\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(response);
                    output.flush();
                    return new WireRequest(lines[0], headers, new String(body, StandardCharsets.UTF_8));
                }
            });

            var service = new AiChatService(mock(SystemSettingService.class), mapper);
            var result = service.test(new AiChatService.AiConfig("vllm",
                "http://127.0.0.1:" + server.getLocalPort() + "/v1", "wire-model", "",
                128, 0.2, "wire-system", 5));

            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("wire-reply");
            WireRequest request = wire.get(6, TimeUnit.SECONDS);
            assertThat(request.requestLine()).isEqualTo("POST /v1/chat/completions HTTP/1.1");
            assertThat(request.headers()).containsEntry("content-type", "application/json")
                .doesNotContainKeys("upgrade", "http2-settings", "authorization");
            assertThat(request.headers().getOrDefault("connection", "").toLowerCase(Locale.ROOT))
                .doesNotContain("upgrade");
            var body = mapper.readTree(request.body());
            assertThat(body.path("model").asText()).isEqualTo("wire-model");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(128);
            assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(body.path("messages").size()).isEqualTo(2);
            assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
            assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("wire-system");
            assertThat(body.path("messages").path(1).path("role").asText()).isEqualTo("user");
            assertThat(body.path("messages").path(1).path("content").asText())
                .isEqualTo("다음 지시에 정확히 한 단어로만 답하세요: OK");
        }
    }

    private record WireRequest(String requestLine, Map<String, String> headers, String body) {}

    @Test
    @DisplayName("빈 API Key는 저장된 provider와 base URL이 모두 같을 때만 재사용한다")
    void testFormReusesStoredKeyOnlyForTheConfiguredTarget() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get("ai.provider", "")).thenReturn("openai");
        when(settings.get("ai.base-url", "")).thenReturn("https://api.example/v1");
        when(settings.get("ai.api-key", "")).thenReturn("stored-test-key");
        AiChatService service = new AiChatService(settings, mock(tools.jackson.databind.ObjectMapper.class));

        assertThat(service.configFromForm(Map.of(
            "provider", "openai", "baseUrl", "https://api.example/v1", "apiKey", ""
        )).apiKey()).isEqualTo("stored-test-key");
        assertThat(service.configFromForm(Map.of(
            "provider", "openai", "baseUrl", "https://other.example/v1", "apiKey", ""
        )).apiKey()).isEmpty();
        assertThat(service.configFromForm(Map.of(
            "provider", "anthropic", "baseUrl", "https://api.example/v1", "apiKey", ""
        )).apiKey()).isEmpty();
        assertThat(service.configFromForm(Map.of(
            "provider", "openai", "baseUrl", "https://other.example/v1", "apiKey", "entered-test-key"
        )).apiKey()).isEqualTo("entered-test-key");
        verify(settings, org.mockito.Mockito.times(1)).get("ai.api-key", "");
    }

    @Test
    @DisplayName("저장된 provider가 없으면 빈 키로 테스트하고 저장 키를 조회하지 않는다")
    void testFormDoesNotFallbackWhenSavedProviderIsMissing() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get("ai.base-url", "")).thenReturn("");
        AiChatService service = new AiChatService(settings, mock(tools.jackson.databind.ObjectMapper.class));

        assertThat(service.configFromForm(Map.of(
            "provider", "openai", "baseUrl", "", "apiKey", ""
        )).apiKey()).isEmpty();
        verify(settings, never()).get("ai.api-key", "");
    }

    @ParameterizedTest(name = "{0} → temperature 제외")
    @ValueSource(strings = {
        "claude-opus-5",
        "claude-sonnet-5",
        "claude-fable-5",
        "claude-mythos-5",
        "claude-opus-4-8",
        "claude-opus-4-7"
    })
    @DisplayName("temperature 를 거절하는 모델에는 싣지 않는다")
    void omitsSamplingParamsForModelsThatRejectThem(String model) {
        assertThat(AiChatService.acceptsSamplingParams(model)).isFalse();
    }

    @ParameterizedTest(name = "{0} → temperature 포함")
    @ValueSource(strings = {
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-opus-4-5",
        "claude-sonnet-4-5",
        "claude-haiku-4-5",
        "claude-haiku-4-5-20251001",   // 날짜 접미사가 붙어도 같은 계열
        "claude-3-opus-20240229"
    })
    @DisplayName("temperature 를 받아 주는 구세대 모델에는 그대로 싣는다")
    void keepsSamplingParamsForModelsThatAcceptThem(String model) {
        assertThat(AiChatService.acceptsSamplingParams(model)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "gpt-4o", "llama3.1:8b", "claude-opus-9"})
    @DisplayName("비어 있거나 모르는 모델이면 안전하게 제외한다")
    void omitsSamplingParamsWhenModelIsUnknown(String model) {
        assertThat(AiChatService.acceptsSamplingParams(model)).isFalse();
    }

    @Test
    @DisplayName("대소문자·공백이 섞여도 같은 판정을 유지한다")
    void normalizesModelIdBeforeMatching() {
        assertThat(AiChatService.acceptsSamplingParams("  Claude-Sonnet-4-6  ")).isTrue();
        assertThat(AiChatService.acceptsSamplingParams("  Claude-Opus-5  ")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "gpt-5", "gpt-4.1-mini"})
    @DisplayName("gpt- 로 시작하면 openai 로 추론한다")
    void infersOpenAiForGptModels(String model) {
        assertThat(AiChatService.inferProvider(model)).isEqualTo("openai");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"claude-opus-5", "llama3.1:8b", "qwen2.5", "GPT-4o"})
    @DisplayName("그 외에는 anthropic 으로 떨어진다 — 대문자 GPT 는 걸리지 않는다(설정 화면 목록이 소문자 ID)")
    void fallsBackToAnthropic(String model) {
        assertThat(AiChatService.inferProvider(model)).isEqualTo("anthropic");
    }
}
