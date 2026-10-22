package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Текстовый запрос к Gemini — без картинок и без формата команд.
 *
 * <p>Нужен там, где Gemini работает «учителем» для нашей модели: придумывает
 * перефразировки для обучающего набора (AdminLlmController). Разбор фраз
 * (GeminiIntentParser) и прокси для агента (AgentLlmController) ходят в Gemini
 * своим кодом — их не трогаем, чтобы не сломать работающее.
 *
 * <p>Ключ, как и везде, живёт только на релее и в журнал не попадает.
 */
@Component
public class GeminiClient {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GeminiClient(ObjectMapper mapper,
                        @Value("${jarvis.llm.api-key:}") String apiKey,
                        @Value("${jarvis.llm.model:gemini-2.5-flash}") String model) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * @param temperature 0 — один и тот же ответ на один и тот же вопрос; около 1 —
     *                    разнообразие (нужно, когда просим придумать много разных фраз)
     * @param json        попросить чистый JSON без пояснений
     * @return текст ответа модели
     * @throws IllegalStateException нет ключа, код ответа не 200 или ответ пуст
     */
    public String generate(String prompt, double temperature, boolean json) throws Exception {
        if (apiKey.isBlank()) throw new IllegalStateException("ключ LLM не задан");

        ObjectNode body = mapper.createObjectNode();
        body.putArray("contents").addObject().put("role", "user")
                .putArray("parts").addObject().put("text", prompt);
        ObjectNode cfg = body.putObject("generationConfig");
        cfg.put("temperature", temperature);
        if (json) cfg.put("responseMimeType", "application/json");

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT.formatted(model)))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // Тело может повторять запрос — наружу только код.
            throw new IllegalStateException("Gemini HTTP " + resp.statusCode());
        }
        JsonNode text = mapper.readTree(resp.body()).path("candidates").path(0)
                .path("content").path("parts").path(0).path("text");
        if (text.isMissingNode() || text.asText().isBlank()) throw new IllegalStateException("empty Gemini response");
        return text.asText();
    }
}
