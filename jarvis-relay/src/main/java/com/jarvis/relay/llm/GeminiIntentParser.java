package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Real intent parser: sends the spoken phrase to Google Gemini and asks it to
 * reply with strict JSON in our own {@link Command} format (the "type" field
 * picks the record). {@link StubIntentParser} stays as the offline fallback
 * and is used whenever the API key is missing or the call/parse fails.
 *
 * С 05.10 телефону отвечает не этот класс напрямую, а связка
 * {@link HybridIntentParser}: сначала наша дообученная модель, и только если
 * она не справилась — Gemini (через {@link #parseModelOnly}).
 *
 * Invariants respected:
 *  - the API key never leaves this class and is never logged;
 *  - the parsed command still goes through the whitelist in the handler;
 *  - the JSON shape is identical to the wire protocol (Jackson polymorphism).
 */
@Component
public class GeminiIntentParser implements IntentParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiIntentParser.class);

    /** REST endpoint; "%s" is the model name. Key is added as a query param. */
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    /** Tells the model exactly which commands exist and the JSON to emit. */
    private static final String SYSTEM_PROMPT = """
            Ты — парсер команд для системы удалённого управления персональным компьютером.
            На вход — фраза пользователя на естественном языке (обычно по-русски).
            Ответь СТРОГО одним JSON-объектом: поле "type" плюс параметры команды.
            Без markdown, без ```-ограждений, без пояснений.

            Доступные команды и их формат:
            - {"type":"set_volume","level":<целое 0..100>}   // "тише" ≈ 30, "громче" ≈ 80, "выключи звук" = 0
            - {"type":"open_url","url":"<полный адрес со схемой https://>"}
            - {"type":"open_app","appName":"<имя программы, напр. notepad, spotify>"}
              // open_app открывает и ПАПКИ пользователя: appName "загрузки", "документы",
              // "рабочий стол", "изображения". Просто открыть папку — это open_app, а не agent_task.
            - {"type":"screenshot"}
            - {"type":"lock_screen"}
            - {"type":"list_processes"}
            - {"type":"kill_process","processName":"<имя процесса, напр. chrome.exe>"}
            - {"type":"agent_task","goal":"<исходная фраза пользователя>","scope":"browser"|"screen","maxSteps":15}
              // используй agent_task для сложных многошаговых действий, которые нельзя
              // свести к одной команде выше: найти что-то, нажать кнопку, поставить лайк,
              // подписаться, заполнить форму. scope="browser" — действие внутри веб-страницы
              // (DOM), scope="screen" — действие в программах на ПК (мессенджеры, проводник).
              // Просьба из НЕСКОЛЬКИХ действий в разных программах («скачай … и отправь …»)
              // — это ОДИН agent_task с исходной фразой целиком, scope="screen", maxSteps=20:
              // агент на ПК сам разобьёт её на шаги. Не выбрасывай из goal ни одного действия.
              // Утренние и рабочие рутины разработчика («подними сервер в докере»,
              // «подготовь всё к работе», «доброе утро, начинаем работу») — тоже
              // agent_task со всей фразой, scope="screen", maxSteps=20: на ПК есть
              // выученные навыки и доверенные скрипты, агент выберет нужный сам.

            Если фразу нельзя однозначно отнести ни к одной команде — верни {"type":"unknown"}.
            Верни {"type":"unknown"} (а НЕ agent_task) и тогда, когда просят то, чего система
            делать не должна или не умеет: выключить или перезагрузить компьютер, отформатировать
            диск, удалить все файлы, перевести деньги, что-то купить или оплатить, поставить
            будильник или напоминание, узнать температуру или состояние железа, перевести или
            написать текст без указания, где это сделать на ПК. agent_task — только для действий
            в браузере и программах, которые агент может выполнить мышью и клавиатурой.

            Примеры:
            "покажи папку с документами" -> {"type":"open_app","appName":"документы"}
            "открой мои картинки" -> {"type":"open_app","appName":"изображения"}
            "выруби комп" -> {"type":"unknown"}
            "оплати интернет" -> {"type":"unknown"}
            "разбуди меня в восемь" -> {"type":"unknown"}
            "сделай потише" -> {"type":"set_volume","level":30}
            "потише" -> {"type":"set_volume","level":30}
            "погромче" -> {"type":"set_volume","level":80}
            "вообще выключи звук" -> {"type":"set_volume","level":0}
            "закрой хром" -> {"type":"kill_process","processName":"chrome.exe"}
            "убей процесс notepad" -> {"type":"kill_process","processName":"notepad.exe"}
            "запусти блокнот" -> {"type":"open_app","appName":"notepad"}
            "открой спотифай" -> {"type":"open_app","appName":"spotify"}
            "открой википедию" -> {"type":"open_url","url":"https://ru.wikipedia.org"}
            "зайди на ютуб" -> {"type":"open_url","url":"https://youtube.com"}
            "сделай скриншот" -> {"type":"screenshot"}
            "заблокируй компьютер" -> {"type":"lock_screen"}
            "какие процессы сейчас запущены" -> {"type":"list_processes"}
            "найди на ютубе видео про котиков и открой первое" -> {"type":"agent_task","goal":"найди на ютубе видео про котиков и открой первое","scope":"browser","maxSteps":15}
            "поставь лайк на этом видео" -> {"type":"agent_task","goal":"поставь лайк на этом видео","scope":"browser","maxSteps":15}
            "скачай с сайта отчёт и отправь его Лере и Маше в вотсапе" -> {"type":"agent_task","goal":"скачай с сайта отчёт и отправь его Лере и Маше в вотсапе","scope":"screen","maxSteps":20}
            "найди файл отчёт в загрузках и перешли его в телеграм Пете" -> {"type":"agent_task","goal":"найди файл отчёт в загрузках и перешли его в телеграм Пете","scope":"screen","maxSteps":20}
            "подними сервер диплома в докере" -> {"type":"agent_task","goal":"подними сервер диплома в докере","scope":"screen","maxSteps":20}
            "доброе утро, начинаем работу" -> {"type":"agent_task","goal":"доброе утро, начинаем работу","scope":"screen","maxSteps":20}
            "сколько будет 2 плюс 2" -> {"type":"unknown"}
            """;

    private final IntentParser fallback;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    /**
     * Сколько ждать ответа Gemini на разбор фразы. Было жёстко 8 с: 06.11 связь
     * с Google после включения ПК была медленной (даже список моделей шёл 8 с),
     * ответ приходил за 7–9 с — и каждая фраза кончалась «не понял». Лучше
     * подождать дольше, чем ответить «не понял» на нормальную просьбу.
     */
    private final Duration timeout;

    public GeminiIntentParser(StubIntentParser fallback,
                              ObjectMapper mapper,
                              @Value("${jarvis.llm.api-key:}") String apiKey,
                              @Value("${jarvis.llm.model:gemini-2.0-flash}") String model,
                              @Value("${jarvis.llm.intent-timeout-seconds:15}") int timeoutSeconds) {
        this.fallback = fallback;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(3, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (this.apiKey.isBlank()) {
            log.info("LLM: ключа нет, regex-режим");
        } else {
            log.info("LLM: включена, модель {}", model);
        }
    }

    @Override
    public Command parse(String phrase) {
        // «навык утро», «запусти скрипт сервер диплома» — без модели: так быстрее
        // и не спутать «запусти скрипт …» с открытием программы.
        Command skill = StubIntentParser.skillOrScript(phrase);
        if (skill != null) {
            log.info("Skill/script phrase -> agent_task");
            return skill;
        }
        // No key -> don't even try the network, go straight to regex.
        if (apiKey.isBlank()) {
            return fallback.parse(phrase);
        }
        try {
            String commandJson = askGemini(phrase);
            Command cmd = mapper.readValue(commandJson, Command.class);
            log.info("LLM parsed '{}' -> {}", phrase, cmd.name());
            return cmd;
        } catch (UnknownIntentException e) {
            log.info("LLM did not recognize '{}', trying regex fallback", phrase);
            return fallback.parse(phrase);
        } catch (Exception e) {
            // Network error, non-200, bad JSON, unknown "type" — degrade gracefully.
            log.warn("LLM call failed ({}: {}), using regex fallback",
                    e.getClass().getSimpleName(), e.getMessage());
            return fallback.parse(phrase);
        }
    }

    /**
     * Только модель — без коротких путей («навык …») и без запасного разбора
     * регулярками. Нужно для честного замера точности самой модели
     * (IntentEvalController): обычный {@link #parse} прячет её ошибки за
     * запасным путём.
     *
     * @return команда или null, если модель ответила «unknown»
     * @throws Exception нет ключа, сеть, квота, ответ не в формате протокола
     */
    public Command parseModelOnly(String phrase) throws Exception {
        if (apiKey.isBlank()) throw new IllegalStateException("ключ LLM не задан");
        try {
            return mapper.readValue(askGemini(phrase), Command.class);
        } catch (UnknownIntentException e) {
            return null;
        }
    }

    /** Calls Gemini and returns the raw Command JSON string it produced. */
    private String askGemini(String phrase) throws Exception {
        String requestBody = mapper.writeValueAsString(buildRequest(phrase));
        URI uri = URI.create(ENDPOINT.formatted(model));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // Body may echo request data — log the status only, never the body.
            throw new IllegalStateException("Gemini HTTP " + resp.statusCode());
        }

        // Answer lives at candidates[0].content.parts[0].text
        JsonNode root = mapper.readTree(resp.body());
        JsonNode textNode = root.path("candidates").path(0)
                .path("content").path("parts").path(0).path("text");
        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new IllegalStateException("empty Gemini response");
        }

        String answer = stripFences(textNode.asText().trim());
        log.debug("Gemini raw answer: {}", answer);

        // The model's escape hatch — treat as "not understood".
        if ("unknown".equals(mapper.readTree(answer).path("type").asText())) {
            throw new UnknownIntentException();
        }
        return answer;
    }

    /** Builds the Gemini generateContent request body as a JSON tree. */
    private ObjectNode buildRequest(String phrase) {
        ObjectNode body = mapper.createObjectNode();

        body.putObject("systemInstruction")
                .putArray("parts")
                .addObject().put("text", SYSTEM_PROMPT);

        ArrayNode contents = body.putArray("contents");
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        userTurn.putArray("parts").addObject().put("text", phrase);

        ObjectNode cfg = body.putObject("generationConfig");
        cfg.put("temperature", 0);                       // deterministic
        cfg.put("responseMimeType", "application/json"); // force pure JSON
        // Разбор фразы в команду — по образцам, размышлять тут не над чем, а
        // размышления Gemini стоят секунды (см. AgentLlmController).
        cfg.putObject("thinkingConfig").put("thinkingBudget", 0);

        return body;
    }

    /** Removes ```json ... ``` fences if a model ignores responseMimeType. */
    private static String stripFences(String s) {
        return s.replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
    }

    /** Signals that the model returned {"type":"unknown"}. */
    private static final class UnknownIntentException extends RuntimeException {
    }
}
