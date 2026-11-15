package com.portfolio.deployops.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 연동 클라이언트.
 *
 * <p>vLLM · Ollama · OpenAI 는 모두 OpenAI 호환 {@code /v1/chat/completions} 스펙을 쓰므로 하나의 경로로 처리하고,
 * Anthropic 만 {@code /v1/messages} 스펙으로 분기한다. base-url 을 비워 두면 provider 별 기본 주소를 사용한다.
 *
 * <p>설정은 {@link SystemSettingService} 의 {@code ai.*} 키에서 읽으며 API Key 는 저장 시 자동 암호화된다.
 */
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final int MAX_PROMPT_CHARS = 12000;

    private final SystemSettingService settings;
    private final ObjectMapper objectMapper;

    /**
     * temperature 를 받아 주는 Anthropic 모델 접두사.
     *
     * <p>Anthropic 은 Opus 4.7 부터 {@code temperature}·{@code top_p}·{@code top_k} 를 제거해서,
     * 이 값을 실어 보내면 400 으로 요청 자체가 거절된다(모델 입력이 자유 텍스트라 사용자가 최신 모델명을
     * 넣는 순간 AI 기능 전체가 죽는다). 반대로 <b>빼고 보내는 것은 어느 모델에서도 안전</b>하므로,
     * "허용 목록에 있을 때만 싣고 모르면 뺀다"로 둔다 — 신규 모델이 나와도 깨지지 않는 방향이다.
     *
     * <p>날짜 접미사가 붙은 ID(예: {@code claude-haiku-4-5-20251001}) 때문에 접두사로 비교한다.
     */
    private static final Set<String> SAMPLING_PARAM_MODEL_PREFIXES = Set.of(
        "claude-opus-4-6", "claude-sonnet-4-6",
        "claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5",
        "claude-opus-4-1", "claude-opus-4-0", "claude-sonnet-4-0",
        "claude-3-"
    );

    /** 이 Anthropic 모델이 temperature 를 받아 주는가. 모르는 모델은 안전하게 false. */
    static boolean acceptsSamplingParams(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String id = model.trim().toLowerCase();
        return SAMPLING_PARAM_MODEL_PREFIXES.stream().anyMatch(id::startsWith);
    }

    /**
     * provider 미지정 시 모델 ID 로 추론한다. 화면·설정·호출 세 곳에서 같은 규칙을 써야 하므로
     * 여기 한 곳에만 둔다 — 흩어져 있으면 한쪽만 고쳐져 화면 표시와 실제 호출 대상이 어긋난다.
     */
    public static String inferProvider(String model) {
        return model != null && model.startsWith("gpt-") ? "openai" : "anthropic";
    }

    /** provider 별 기본 base-url (자가호스팅 미입력 시 폴백). */
    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "anthropic" -> "https://api.anthropic.com";
            case "openai" -> "https://api.openai.com/v1";
            case "ollama" -> "http://localhost:11434/v1";
            case "vllm" -> "http://localhost:8000/v1";
            default -> "http://localhost:8000/v1";
        };
    }

    public record AiConfig(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        int maxTokens,
        double temperature,
        String systemPrompt,
        int timeoutSec
    ) {}

    public record TestResult(boolean success, String message, long latencyMs) {}

    /**
     * 모델 답변과 <b>그것이 끝까지 나온 것인지</b>.
     *
     * <p>지금까지는 본문만 돌려줬다. 그래서 max_tokens 에 걸려 문장 한가운데서 끊긴 답이
     * 정상 응답과 똑같이 화면에 실렸고, 사용자에게는 "AI 가 분석을 하다 말았다" 로 보였다.
     * 실제로 배포 로그 분석이 그 상태였다 — 기본 2048 토큰은 한국어로 4개 절을 쓰기에
     * 모자라 늘 마지막 항목이 잘렸다.
     */
    public record AiAnswer(String text, boolean truncated) {}

    public boolean isEnabled() {
        return Boolean.parseBoolean(settings.get("ai.enabled", "false"));
    }

    public boolean isFeatureEnabled(String featureKey, boolean defaultValue) {
        String value = settings.get(featureKey, null);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    /** 저장된 설정으로 프롬프트를 실행하고 모델 응답 텍스트를 돌려준다. */
    public String complete(String systemPrompt, String userPrompt) throws AiException {
        return answer(systemPrompt, userPrompt).text();
    }

    /** {@link #complete} 와 같지만 잘림 여부까지 알려 준다. */
    public AiAnswer answer(String systemPrompt, String userPrompt) throws AiException {
        return call(currentConfig(systemPrompt), userPrompt);
    }

    /** 긴 답이 필요한 분석용 — 저장된 최대 토큰이 작아도 이 값까지 올려 준다. */
    public AiAnswer answerLong(String systemPrompt, String userPrompt, int minTokens) throws AiException {
        AiConfig base = currentConfig(systemPrompt);
        int tokens = Math.max(base.maxTokens(), minTokens);
        return call(new AiConfig(base.provider(), base.baseUrl(), base.model(), base.apiKey(),
            tokens, base.temperature(), base.systemPrompt(), base.timeoutSec()), userPrompt);
    }

    /** 전달받은(미저장 가능) 설정으로 실제 모델에 연결을 시도한다. 설정 화면의 "테스트" 버튼용. */
    public TestResult test(AiConfig cfg) {
        long start = System.currentTimeMillis();
        try {
            String reply = call(cfg, "다음 지시에 정확히 한 단어로만 답하세요: OK").text();
            long elapsed = System.currentTimeMillis() - start;
            String preview = reply == null ? "" : reply.strip();
            if (preview.length() > 80) {
                preview = preview.substring(0, 80) + "...";
            }
            return new TestResult(true, "모델 응답 정상 (\"" + preview + "\")", elapsed);
        } catch (AiException e) {
            return new TestResult(false, e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private AiConfig currentConfig(String overrideSystemPrompt) {
        Map<String, String> s = settings.getCategory("ai");
        String model = orDefault(s.get("ai.model"), "claude-sonnet-4-6");
        String provider = orDefault(s.get("ai.provider"), inferProvider(model));
        String systemPrompt = overrideSystemPrompt != null && !overrideSystemPrompt.isBlank()
            ? overrideSystemPrompt
            : orDefault(s.get("ai.system-prompt"), "");
        return new AiConfig(
            provider,
            orDefault(s.get("ai.base-url"), ""),
            model,
            orDefault(s.get("ai.api-key"), ""),
            parseInt(s.get("ai.max-tokens"), 2048),
            parseDouble(s.get("ai.temperature"), 0.3),
            systemPrompt,
            parseInt(s.get("ai.timeout-sec"), 60)
        );
    }

    /** 설정 화면에서 넘어온 폼 값으로 AiConfig 를 만든다(테스트 전용). */
    public AiConfig configFromForm(Map<String, String> body) {
        String model = orDefault(trim(body.get("model")), "claude-sonnet-4-6");
        String provider = orDefault(trim(body.get("provider")), inferProvider(model));
        String baseUrl = trim(body.get("baseUrl"));
        String apiKey = trim(body.get("apiKey"));
        // 저장된 키는 동일한 대상에만 재사용한다. 빈 키와 새 주소의 조합은 자격 증명을 보내지 않는다.
        if (apiKey.isEmpty()) {
            String savedProvider = trim(settings.get("ai.provider", ""));
            String savedBaseUrl = trim(settings.get("ai.base-url", ""));
            if (!savedProvider.isEmpty() && provider.equals(savedProvider) && baseUrl.equals(savedBaseUrl)) {
                apiKey = settings.get("ai.api-key", "");
            }
        }
        return new AiConfig(
            provider,
            baseUrl,
            model,
            apiKey,
            parseInt(body.get("maxTokens"), 512),
            parseDouble(body.get("temperature"), 0.3),
            "",
            // 자가호스팅(vLLM/Ollama) 콜드 스타트 시 모델 VRAM 적재로 수십 초가 걸릴 수 있어 넉넉히 둔다
            parseInt(body.get("timeoutSec"), 90)
        );
    }

    private AiAnswer call(AiConfig cfg, String userPrompt) throws AiException {
        if (cfg.model() == null || cfg.model().isBlank()) {
            throw new AiException("모델명이 비어 있습니다.");
        }
        String prompt = truncate(userPrompt);
        try {
            return "anthropic".equals(cfg.provider())
                ? callAnthropic(cfg, prompt)
                : callOpenAiCompatible(cfg, prompt);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException(friendly(e));
        }
    }

    private AiAnswer callOpenAiCompatible(AiConfig cfg, String userPrompt) throws Exception {
        String endpoint = normalizeOpenAiBase(cfg) + "/chat/completions";
        Map<String, Object> payload = Map.of(
            "model", cfg.model(),
            "messages", List.of(
                Map.of("role", "system", "content", cfg.systemPrompt() == null ? "" : cfg.systemPrompt()),
                Map.of("role", "user", "content", userPrompt)
            ),
            "max_tokens", cfg.maxTokens(),
            "temperature", cfg.temperature(),
            "stream", false
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(cfg.timeoutSec()))
            .header("Content-Type", "application/json");
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + cfg.apiKey());
        }
        HttpResponse<String> response = HTTP.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() / 100 != 2) {
            throw new AiException(httpError(response));
        }
        JsonNode choice = objectMapper.readTree(response.body()).path("choices").path(0);
        // finish_reason=length 는 "할 말이 남았는데 토큰이 떨어졌다" 는 뜻이다.
        boolean cut = "length".equals(choice.path("finish_reason").asText(""));
        return new AiAnswer(requireText(choice.path("message").path("content").asText("")), cut);
    }

    private AiAnswer callAnthropic(AiConfig cfg, String userPrompt) throws Exception {
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank()
            ? defaultBaseUrl("anthropic")
            : cfg.baseUrl().replaceAll("/+$", "");
        String endpoint = base + "/v1/messages";
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new AiException("Anthropic 사용에는 API Key 가 필요합니다.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", cfg.model());
        payload.put("max_tokens", cfg.maxTokens());
        // Opus 4.7 이상은 temperature 를 400 으로 거절한다 — 받는 모델에만 싣는다.
        if (acceptsSamplingParams(cfg.model())) {
            payload.put("temperature", cfg.temperature());
        }
        payload.put("system", cfg.systemPrompt() == null ? "" : cfg.systemPrompt());
        payload.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));

        HttpResponse<String> response = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .header("Content-Type", "application/json")
                .header("x-api-key", cfg.apiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() / 100 != 2) {
            throw new AiException(httpError(response));
        }
        JsonNode root = objectMapper.readTree(response.body());
        boolean cut = "max_tokens".equals(root.path("stop_reason").asText(""));
        return new AiAnswer(requireText(root.path("content").path(0).path("text").asText("")), cut);
    }

    /** OpenAI 호환 base-url 정규화: 비면 기본값, {@code /v1} 이 없으면 붙인다. */
    private String normalizeOpenAiBase(AiConfig cfg) {
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank()
            ? defaultBaseUrl(cfg.provider())
            : cfg.baseUrl().trim();
        base = base.replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (!base.endsWith("/v1")) {
            base = base + "/v1";
        }
        return base;
    }

    private String requireText(String text) throws AiException {
        if (text == null || text.isBlank()) {
            throw new AiException("모델이 빈 응답을 반환했습니다.");
        }
        return text.strip();
    }

    private String httpError(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
        if (body.length() > 200) {
            body = body.substring(0, 200) + "...";
        }
        return "모델 서버 오류 (HTTP " + response.statusCode() + "): " + body;
    }

    private String friendly(Exception e) {
        // 예외 타입 우선 판별(메시지가 null 이어도 정확한 안내)
        if (e instanceof java.net.ConnectException) {
            return "모델 서버에 연결할 수 없습니다. IP/포트와 서버 실행 상태를 확인하세요.";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "호스트를 찾을 수 없습니다. base-url 을 확인하세요.";
        }
        if (e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.SocketTimeoutException) {
            return "모델 응답 시간이 초과되었습니다. 서버 상태나 모델 로딩을 확인하세요.";
        }
        String message = e.getMessage();
        if (message == null) {
            return "연결에 실패했습니다 (" + e.getClass().getSimpleName() + "). IP/포트를 확인하세요.";
        }
        if (message.contains("Connection refused")) {
            return "모델 서버에 연결할 수 없습니다. IP/포트와 서버 실행 상태를 확인하세요.";
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return "모델 응답 시간이 초과되었습니다.";
        }
        if (message.contains("UnknownHost") || message.contains("nodename")) {
            return "호스트를 찾을 수 없습니다. base-url 을 확인하세요.";
        }
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_PROMPT_CHARS
            ? text.substring(0, MAX_PROMPT_CHARS) + "\n...(이하 생략)"
            : text;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 모델 호출 실패를 사용자 메시지로 전달하기 위한 예외. */
    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }
    }
}
