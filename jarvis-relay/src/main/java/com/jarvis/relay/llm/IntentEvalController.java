package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.persistence.IntentLesson;
import com.jarvis.relay.protocol.Command;
import com.jarvis.relay.security.CommandWhitelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Замер точности разбора фраз и выгрузка «уроков» — служебные эндпоинты для
 * научной части и для дообучения своей модели.
 *
 * <p>Скрипт {@code eval/intent_eval.py} прогоняет через {@code /parse}
 * тестовый набор и сравнивает ответы с эталоном. Команды НИКУДА не
 * отправляются: ни агенту, ни в журнал — только разбираются.
 *
 * <p>Режимы (engine):
 * <ul>
 *   <li>{@code stub} — только регулярные выражения;</li>
 *   <li>{@code gemini} — только Gemini, без коротких путей и запасного разбора;</li>
 *   <li>{@code local} — только НАША модель (ответ любой уверенности);</li>
 *   <li>{@code system} (по умолчанию) — вся связка, как её видит телефон,
 *       ВКЛЮЧАЯ белый список и запрет опасных целей: запрещённая команда
 *       возвращается как «не понял» с пометкой denied.</li>
 * </ul>
 *
 * <p>Защищён админским секретом (как PairingController): вызовы тратят квоту модели.
 */
@RestController
@RequestMapping("/api/admin/intent")
public class IntentEvalController {

    private final StubIntentParser stub;
    private final GeminiIntentParser gemini;
    private final LocalIntentParser local;
    private final HybridIntentParser hybrid;
    private final IntentLessons lessons;
    private final CommandWhitelist whitelist;
    private final ObjectMapper mapper;

    @Value("${jarvis.admin-token}")
    private String adminToken;

    public IntentEvalController(StubIntentParser stub, GeminiIntentParser gemini, LocalIntentParser local,
                                HybridIntentParser hybrid, IntentLessons lessons, CommandWhitelist whitelist,
                                ObjectMapper mapper) {
        this.stub = stub;
        this.gemini = gemini;
        this.local = local;
        this.hybrid = hybrid;
        this.lessons = lessons;
        this.whitelist = whitelist;
        this.mapper = mapper;
    }

    public record ParseRequest(String phrase, String engine) {}

    @PostMapping("/parse")
    public ResponseEntity<?> parse(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                   @RequestBody ParseRequest request) {
        if (!isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad admin token"));
        }
        if (request == null || request.phrase() == null || request.phrase().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "phrase is required"));
        }
        String engine = request.engine() == null || request.engine().isBlank() ? "system" : request.engine();
        String phrase = request.phrase();

        // Ответ собираем картой: в нём есть null (команда «не понял»), а Map.of их не терпит.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", engine);
        long t0 = System.nanoTime();
        Command cmd = null;
        try {
            switch (engine) {
                case "stub" -> cmd = stub.parse(phrase);
                case "gemini" -> cmd = gemini.parseModelOnly(phrase);
                case "local" -> {
                    LocalIntentParser.Result r = local.parse(phrase);
                    cmd = r.command();
                    out.put("source", "local");
                    out.put("confidence", r.confidence());
                    out.put("localRaw", r.raw());
                    out.put("localKind", r.kind().name());
                    if (r.kind() == LocalIntentParser.Kind.UNAVAILABLE) out.put("error", "своя модель недоступна");
                }
                case "system" -> {
                    // requestId = null: замер не должен плодить уроки.
                    HybridIntentParser.Decision d = hybrid.decide(phrase, null);
                    cmd = d.command();
                    out.put("source", d.source());
                    out.put("confidence", d.confidence());
                    out.put("localRaw", d.localRaw());
                    out.put("localMs", d.localMs());
                    out.put("geminiMs", d.geminiMs());
                    String denied = cmd == null ? null : whitelist.denyReason(cmd);
                    if (denied != null) {
                        out.put("denied", denied);
                        cmd = null; // для человека это отказ, а не выполненная команда
                    }
                }
                default -> {
                    return ResponseEntity.badRequest().body(Map.of("error", "unknown engine: " + engine));
                }
            }
        } catch (Exception e) {
            // Текст ответа модели не возвращаем — только класс и сообщение ошибки.
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        out.put("ms", (System.nanoTime() - t0) / 1_000_000);
        JsonNode json = cmd == null ? null : mapper.valueToTree(cmd);
        out.put("command", json);
        return ResponseEntity.ok(out);
    }

    /**
     * Подтверждённые уроки — фразы, которые поняла только Gemini и которые
     * затем успешно выполнились. Их забирает {@code jarvis-ml/retrain.py}.
     */
    @GetMapping("/lessons")
    public ResponseEntity<?> lessons(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad admin token"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (IntentLesson l : lessons.confirmed()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phrase", l.getPhrase());
            try {
                row.put("command", mapper.readTree(l.getCommandJson()));
            } catch (Exception e) {
                continue; // испорченная запись — не урок
            }
            row.put("localAnswer", l.getLocalAnswer());
            row.put("confirmedAt", String.valueOf(l.getConfirmedAt()));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** Сравнение за постоянное время — как в PairingController. */
    private boolean isAuthorized(String provided) {
        if (provided == null) return false;
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                adminToken.getBytes(StandardCharsets.UTF_8));
    }
}
