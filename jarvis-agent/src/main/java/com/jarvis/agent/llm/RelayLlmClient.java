package com.jarvis.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.AgentConfig;
import com.jarvis.agent.TokenStore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Вызывает LLM НЕ напрямую (как раньше делал GeminiVisionAgent), а через
 * прокси-эндпоинт релея {@code POST /api/agent/llm/generate}. Ключ Gemini
 * живёт только в .env релея (инвариант 3) — агент вместо ключа отправляет
 * свои же device-креды (X-Device-Id/X-Device-Token), которыми и так
 * аутентифицируется на релее по WebSocket и в {@code AgentPairingController}.
 */
public class RelayLlmClient {

    /** Сколько раз пробуем запрос, включая первую попытку (при 502/503 и сетевых сбоях). */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private final AgentConfig config;
    private final TokenStore tokenStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public RelayLlmClient(AgentConfig config, TokenStore tokenStore) {
        this.config = config;
        this.tokenStore = tokenStore;
    }

    /** Агент привязан (есть постоянный токен и deviceId) — без этого проксировать запрос некуда. */
    public boolean isConfigured() {
        return tokenStore.load() != null && tokenStore.loadDeviceId() != null;
    }

    /**
     * @param prompt    текст промпта
     * @param jpegImage опциональная картинка (мультимодальный запрос), или null для текстового
     * @return сырой текстовый ответ модели
     */
    /**
     * Сколько всего запросов к модели ушло через этого клиента. Разница «после
     * задачи минус до» — сколько обращений к ИИ стоила задача: эта цифра идёт в
     * итог на телефон и в журнал прогонов (цена задачи для таблиц диплома).
     */
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    public int calls() {
        return calls.get();
    }

    public String generate(String prompt, byte[] jpegImage) throws Exception {
        calls.incrementAndGet();
        String deviceId = tokenStore.loadDeviceId();
        String token = tokenStore.load();
        if (deviceId == null || token == null) {
            throw new IllegalStateException("агент не привязан к релею — нет токена");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", prompt);
        if (jpegImage != null) {
            body.put("imageBase64", Base64.getEncoder().encodeToString(jpegImage));
            body.put("mimeType", "image/jpeg");
        }

        // Мультимодальный запрос (скриншот ~60-90 КБ в base64) через два хопа
        // агент -> релей -> Gemini заметно медленнее текстового, 30с не хватает.
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", deviceId)
                .header("X-Device-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = sendWithRetry(request);
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("релей вернул HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode textNode = mapper.readTree(resp.body()).path("text");
        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new IllegalStateException("пустой ответ LLM через релей");
        }
        return textNode.asText();
    }

    /**
     * Повторяет запрос при временных сбоях: 502/503 (Gemini периодически отвечает
     * "model is overloaded") и сетевых таймаутах. Без этого один случайный 503
     * обрывает ВСЮ задачу agent_task, а не один её шаг. 4xx не повторяем — это
     * наша собственная ошибка (нет токена, битое тело), повтор не поможет.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 502 && resp.statusCode() != 503 || attempt == MAX_ATTEMPTS) {
                    return resp;
                }
            } catch (IOException e) {
                lastError = e;
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
            }
            Thread.sleep(RETRY_DELAY_MS * attempt); // 2с, затем 4с
        }
        throw lastError != null ? lastError : new IllegalStateException("не удалось получить ответ от релея");
    }

    /** ws(s)://host:port/ws -> http(s)://host:port/api/agent/llm/generate — путь релея, не WebSocket. */
    private URI endpoint() {
        URI relay = URI.create(config.relayUrl());
        String scheme = "wss".equalsIgnoreCase(relay.getScheme()) ? "https" : "http";
        String hostPort = relay.getHost() + (relay.getPort() > 0 ? ":" + relay.getPort() : "");
        return URI.create(scheme + "://" + hostPort + "/api/agent/llm/generate");
    }
}
