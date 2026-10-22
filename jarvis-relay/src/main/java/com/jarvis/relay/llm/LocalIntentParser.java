package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * НАША модель разбора фраз: маленькая языковая модель (Qwen2.5), дообученная
 * на собственном датасете команд «Фауста» и запущенная локально через Ollama.
 *
 * <h2>Чем отличается от Gemini</h2>
 * <ul>
 *   <li>Работает на этом же компьютере: ни интернета, ни ключа, ни оплаты.</li>
 *   <li>Отвечает коротким JSON за десятки–сотни миллисекунд (Gemini — 2–3 с).</li>
 *   <li>Знает «наши правила» из обучающих данных: папки открывает open_app,
 *       «выключи компьютер» — не команда.</li>
 * </ul>
 *
 * <h2>Формат ответа модели</h2>
 * Тот же JSON, что в протоколе, но без лишнего: для задачи агенту модель
 * отвечает только {@code {"type":"agent_task","scope":"screen"}}, а цель
 * (саму фразу) и лимит шагов подставляет код. Так модели не нужно
 * переписывать длинную фразу слово в слово — это и быстрее, и нет ошибок
 * переписывания.
 *
 * <h2>Уверенность</h2>
 * Ollama отдаёт вероятность каждого слова ответа. Их произведение — вероятность
 * всего ответа целиком: 0.99 — модель уверена, 0.4 — сомневалась между
 * вариантами. По ней {@link HybridIntentParser} решает, верить ли ответу или
 * спросить Gemini.
 */
@Component
public class LocalIntentParser {

    private static final Logger log = LoggerFactory.getLogger(LocalIntentParser.class);

    /** Длинные тексты (рецепты, письма) — не для маленькой модели: она училась на коротких фразах. */
    private static final int MAX_PHRASE_CHARS = 300;
    /** Ollama не отвечает — не ждём её на каждой фразе, а пробуем снова через полминуты. */
    private static final long RETRY_AFTER_MS = 30_000;

    public enum Kind {
        /** Модель назвала команду, и она прошла проверку формата. */
        COMMAND,
        /** Модель честно сказала «не понял». */
        UNKNOWN,
        /** Ответ — не JSON, неизвестный тип или кривые параметры. */
        INVALID,
        /** Модель недоступна: Ollama не запущена или модель не установлена. */
        UNAVAILABLE
    }

    /**
     * @param confidence вероятность ответа целиком, 0..1 (0 — Ollama её не отдала)
     * @param raw        что модель ответила дословно — для журнала уроков и разбора ошибок
     */
    public record Result(Kind kind, Command command, double confidence, String raw, long ms) {
        static Result unavailable() {
            return new Result(Kind.UNAVAILABLE, null, 0, null, 0);
        }
    }

    private final ObjectMapper mapper;
    private final String url;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile long downUntil;

    public LocalIntentParser(ObjectMapper mapper,
                             @Value("${jarvis.local-llm.url:http://host.docker.internal:11434}") String url,
                             @Value("${jarvis.local-llm.model:faust-intent}") String model) {
        this.mapper = mapper;
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.model = model;
        if (this.url.isBlank()) {
            log.info("Своя модель: адрес Ollama не задан — выключена");
        } else {
            log.info("Своя модель: {} через {}", model, this.url);
        }
    }

    public String modelName() {
        return model;
    }

    /** Разобрать фразу своей моделью. Никогда не бросает исключений: любая беда — это UNAVAILABLE или INVALID. */
    public Result parse(String phrase) {
        if (url.isBlank() || System.currentTimeMillis() < downUntil) return Result.unavailable();
        if (phrase == null || phrase.isBlank() || phrase.length() > MAX_PHRASE_CHARS) {
            return new Result(Kind.INVALID, null, 0, null, 0);
        }
        long t0 = System.nanoTime();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            body.put("logprobs", true);
            // Модель остаётся в памяти час после последней фразы — иначе каждая
            // первая команда ждала бы её загрузки несколько секунд.
            body.put("keep_alive", "60m");
            body.putArray("messages").addObject().put("role", "user").put("content", phrase.strip());
            ObjectNode options = body.putObject("options");
            options.put("temperature", 0);   // один и тот же ответ на одну и ту же фразу
            options.put("num_predict", 96);  // команда — это два десятка слов; дальше только мусор

            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/chat"))
                    .timeout(Duration.ofSeconds(20))  // с запасом на первую загрузку модели в память
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() != 200) {
                // 404 — модель ещё не создана в Ollama (см. jarvis-ml/README).
                markDown("Ollama HTTP " + resp.statusCode());
                return Result.unavailable();
            }
            JsonNode root = mapper.readTree(resp.body());
            String raw = root.path("message").path("content").asText("").strip();
            double confidence = confidence(root.path("logprobs"));
            return interpret(phrase.strip(), raw, confidence, ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unavailable();
        } catch (Exception e) {
            markDown(e.getClass().getSimpleName() + ": " + e.getMessage());
            return Result.unavailable();
        }
    }

    private void markDown(String why) {
        downUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
        log.warn("Своя модель недоступна ({}), следующая попытка через {} с", why, RETRY_AFTER_MS / 1000);
    }

    /** Произведение вероятностей всех слов ответа = вероятность ответа целиком. */
    static double confidence(JsonNode logprobs) {
        if (logprobs == null || !logprobs.isArray() || logprobs.isEmpty()) return 0;
        double sum = 0;
        for (JsonNode t : logprobs) sum += t.path("logprob").asDouble(0);
        return Math.exp(sum);
    }

    /**
     * Ответ модели -> команда протокола. Не private — ради тестов.
     *
     * <p>Проверяется всё, что может сломать исполнение: тип, диапазон
     * громкости, пустые имена, адрес сайта. Сомнение толкуется как INVALID —
     * тогда фраза уйдёт в Gemini, а не исполнится наугад.
     */
    Result interpret(String phrase, String raw, double confidence, long ms) {
        JsonNode n;
        try {
            n = mapper.readTree(raw);
        } catch (Exception e) {
            return new Result(Kind.INVALID, null, confidence, raw, ms);
        }
        String type = n.path("type").asText("");
        Command cmd = switch (type) {
            case "set_volume" -> {
                JsonNode level = n.path("level");
                yield level.isInt() && level.asInt() >= 0 && level.asInt() <= 100
                        ? new Command.SetVolume(level.asInt()) : null;
            }
            case "open_url" -> {
                String u = normalizeUrl(n.path("url").asText(""));
                yield u == null ? null : new Command.OpenUrl(u);
            }
            case "open_app" -> {
                String app = n.path("appName").asText("").strip();
                yield app.isEmpty() || app.length() > 80 ? null : new Command.OpenApp(app);
            }
            case "kill_process" -> {
                String proc = n.path("processName").asText("").strip();
                yield proc.isEmpty() || proc.length() > 80 || proc.contains(" ") ? null : new Command.KillProcess(proc);
            }
            case "screenshot" -> new Command.Screenshot();
            case "lock_screen" -> new Command.LockScreen();
            case "list_processes" -> new Command.ListProcesses();
            case "agent_task" -> {
                // Цель — сама фраза целиком: модель её не переписывает (см. javadoc класса).
                String scope = n.path("scope").asText("");
                yield switch (scope) {
                    case "browser" -> new Command.AgentTask(phrase, AgentScope.BROWSER, 15);
                    case "screen" -> new Command.AgentTask(phrase, AgentScope.SCREEN, 20);
                    default -> null;
                };
            }
            default -> null;
        };
        if ("unknown".equals(type)) return new Result(Kind.UNKNOWN, null, confidence, raw, ms);
        if (cmd == null) return new Result(Kind.INVALID, null, confidence, raw, ms);
        return new Result(Kind.COMMAND, cmd, confidence, raw, ms);
    }

    /** «youtube.com» -> «https://youtube.com»; не похоже на адрес — null. */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.strip();
        if (u.isEmpty() || u.contains(" ") || u.length() > 300) return null;
        if (!u.toLowerCase(Locale.ROOT).startsWith("http://") && !u.toLowerCase(Locale.ROOT).startsWith("https://")) {
            u = "https://" + u;
        }
        try {
            String host = URI.create(u).getHost();
            return host != null && host.contains(".") ? u : null;
        } catch (Exception e) {
            return null;
        }
    }
}
