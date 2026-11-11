package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.relay.pairing.AgentAuthService;
import com.jarvis.relay.persistence.AgentLlmLog;
import com.jarvis.relay.persistence.AgentLlmLogRepository;
import com.jarvis.relay.persistence.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM-прокси для агента: держит {@code jarvis.llm.api-key} только здесь
 * (инвариант 3), агент присылает сюда голый промпт (+ опционально
 * скриншот) и получает сырой текстовый ответ Gemini — это то, чем
 * пользуются {@code GeminiVisionAgent}/{@code BrowserAgent}/{@code ScreenAgent}
 * в jarvis-agent вместо прямого похода в Google со своим ключом.
 */
@RestController
@RequestMapping("/api/agent/llm/generate")
public class AgentLlmController {

    private static final Logger log = LoggerFactory.getLogger(AgentLlmController.class);

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private static final long MAX_BODY_BYTES = 6L * 1024 * 1024;
    private static final int RATE_LIMIT_PER_MINUTE = 20;
    private static final long RATE_WINDOW_MILLIS = 60_000;

    private final AgentAuthService agentAuth;
    private final AgentLlmLogRepository llmLog;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    /** Сколько токенов модели разрешено «думать» перед ответом; -1 — на её усмотрение. */
    private final int thinkingBudget;

    public AgentLlmController(AgentAuthService agentAuth, AgentLlmLogRepository llmLog, ObjectMapper mapper,
                               @Value("${jarvis.llm.api-key:}") String apiKey,
                               @Value("${jarvis.llm.model:gemini-2.5-flash}") String model,
                               @Value("${jarvis.llm.thinking-budget:0}") int thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
        this.agentAuth = agentAuth;
        this.llmLog = llmLog;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record GenerateRequest(String prompt, String imageBase64, String mimeType) {}

    public record GenerateResponse(String text) {}

    @PostMapping
    public ResponseEntity<?> generate(@RequestHeader("X-Device-Id") String deviceId,
                                       @RequestHeader("X-Device-Token") String token,
                                       @RequestBody byte[] rawBody) {
        long start = System.currentTimeMillis();

        Optional<Device> device = agentAuth.authenticateAgent(deviceId, token);
        if (device.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad device id or token"));
        }

        if (apiKey.isBlank()) {
            log.warn("LLM proxy called by {} but jarvis.llm.api-key is not set", deviceId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "LLM is not configured on relay"));
        }

        if (rawBody.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "body too large (max 6MB)"));
        }

        if (!tryAcquire(deviceId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate limit exceeded (20 requests/min)"));
        }

        GenerateRequest request;
        try {
            request = mapper.readValue(rawBody, GenerateRequest.class);
        } catch (Exception e) {
            // Частая причина: клиент кодирует JSON не в UTF-8 (например, дефолтный
            // charset JVM на Windows) — кириллица в теле бьёт парсер. Сам текст
            // тела в лог не идёт (может быть подсказкой для атаки), только причина.
            log.warn("LLM proxy: invalid request body from {} ({}: {})", deviceId, e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid request body (check UTF-8 encoding)"));
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }

        boolean ok = false;
        try {
            String text = askGemini(request);
            ok = true;
            return ResponseEntity.ok(new GenerateResponse(text));
        } catch (Exception e) {
            log.warn("LLM proxy call failed for {} ({}: {})", deviceId, e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "gemini call failed"));
        } finally {
            long latencyMs = System.currentTimeMillis() - start;
            llmLog.save(new AgentLlmLog(deviceId, Instant.now(), latencyMs, ok));
            log.info("LLM proxy: device={} bytes={} latencyMs={} ok={}", deviceId, rawBody.length, latencyMs, ok);
        }
    }

    private String askGemini(GenerateRequest request) throws Exception {
        String requestBody = mapper.writeValueAsString(buildRequest(request));
        URI uri = URI.create(ENDPOINT.formatted(model));

        // 90с, не 30: мультимодальные запросы (скриншот ~80-100КБ base64) у Gemini
        // на практике занимают от 12 до 30+ секунд — с 30с таймаутом часть падала.
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> resp = sendWithRetry(httpRequest);
        if (resp.statusCode() != 200) {
            // Тело ответа может содержать эхо запроса — в исключение и в лог идёт только статус.
            throw new IllegalStateException("Gemini HTTP " + resp.statusCode());
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode textNode = root.path("candidates").path(0)
                .path("content").path("parts").path(0).path("text");
        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new IllegalStateException("empty Gemini response");
        }
        return textNode.asText();
    }

    /**
     * Gemini время от времени отвечает 503 ("model is overloaded") на секунду-две —
     * дешевле повторить запрос релей->Google здесь, чем заставлять агента заново
     * гонять картинку по каналу агент->релей. Ретраим только 503 и сетевые сбои
     * (IOException), не 429 (квота — повтор не поможет) и не прочие 4xx/5xx.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest httpRequest) throws Exception {
        long[] backoffMs = {1000, 2000};
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> resp = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 503 || attempt == backoffMs.length) {
                    return resp;
                }
            } catch (java.io.IOException e) {
                if (attempt == backoffMs.length) {
                    throw e;
                }
            }
            Thread.sleep(backoffMs[attempt]);
        }
    }

    private ObjectNode buildRequest(GenerateRequest request) {
        ObjectNode body = mapper.createObjectNode();

        ArrayNode contents = body.putArray("contents");
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode parts = userTurn.putArray("parts");
        parts.addObject().put("text", request.prompt());

        if (request.imageBase64() != null && !request.imageBase64().isBlank()) {
            ObjectNode inlineData = parts.addObject().putObject("inlineData");
            inlineData.put("mimeType", request.mimeType() != null && !request.mimeType().isBlank()
                    ? request.mimeType() : "image/jpeg");
            inlineData.put("data", request.imageBase64());
        }

        ObjectNode cfg = body.putObject("generationConfig");
        cfg.put("temperature", 0);
        // Размышления модели перед ответом. По умолчанию Gemini «думает» ~480
        // токенов даже над «ответь да» — замер 06.11: шаг агента шёл 11–25 с
        // вместо 2–4. Агент и так просит модель объяснить выбор в поле reason,
        // так что отдельное размышление ему почти ничего не даёт.
        // -1 — оставить как решит модель (если качество на сложных шагах просядет).
        if (thinkingBudget >= 0) cfg.putObject("thinkingConfig").put("thinkingBudget", thinkingBudget);
        return body;
    }

    /** Простое скользящее окно 1 минута на deviceId, без внешних зависимостей. */
    private boolean tryAcquire(String deviceId) {
        long now = System.currentTimeMillis();
        RateWindow window = rateWindows.computeIfAbsent(deviceId, id -> new RateWindow(now));
        synchronized (window) {
            if (now - window.startMillis >= RATE_WINDOW_MILLIS) {
                window.startMillis = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= RATE_LIMIT_PER_MINUTE;
        }
    }

    private static final class RateWindow {
        volatile long startMillis;
        final AtomicInteger count = new AtomicInteger();

        RateWindow(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
