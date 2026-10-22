package com.jarvis.relay.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Gemini как «учитель» для нашей модели — служебный эндпоинт.
 *
 * <p>Скрипт сборки обучающего набора (jarvis-ml/build_trainset.py) просит
 * через него придумать разные формулировки одной и той же просьбы. Сам скрипт
 * ключа Gemini не знает: ключ остаётся на релее (инвариант 3), а скрипт
 * предъявляет админский секрет — тот же, что у выдачи кодов привязки.
 */
@RestController
@RequestMapping("/api/admin/llm")
public class AdminLlmController {

    private final GeminiClient gemini;

    @Value("${jarvis.admin-token}")
    private String adminToken;

    public AdminLlmController(GeminiClient gemini) {
        this.gemini = gemini;
    }

    /**
     * @param temperature по умолчанию 0.9 — нам нужны РАЗНЫЕ формулировки
     * @param json        по умолчанию true — ответ разбирает программа
     */
    public record GenerateRequest(String prompt, Double temperature, Boolean json) {}

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                      @RequestBody GenerateRequest request) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                adminToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad admin token"));
        }
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }
        try {
            String text = gemini.generate(request.prompt(),
                    request.temperature() == null ? 0.9 : request.temperature(),
                    request.json() == null || request.json());
            return ResponseEntity.ok(Map.of("text", text));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }
}
