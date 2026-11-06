package com.jarvis.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Сообщить релею, какими фразами просят навыки этого ПК.
 *
 * Зачем. Фразу с телефона сначала разбирает релей (своя модель или Gemini).
 * «Отправь Маше расписание» модель может переформулировать или принять за
 * другую команду — и тогда до навыка она не дойдёт. Список навыков есть
 * только у агента, поэтому агент сам отдаёт релею их фразы: шаблоны с
 * переменными — регулярными выражениями (SkillTemplate.toRelayRegex),
 * фразы-триггеры — как есть. Совпавшую фразу релей передаёт агенту
 * дословно, без модели.
 *
 * Сами шаги навыка на релей не уходят — только то, по чему узнать фразу.
 *
 * Когда: после каждого входа в релей (релей хранит фразы в памяти и после
 * перезапуска их не помнит) и после каждого изменения навыков.
 */
public final class SkillSync {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private SkillSync() {}

    /** Тело запроса: по навыку — название и выражения для фраз (уже нормализованных релеем). */
    static Map<String, Object> payload(List<SkillStore.Skill> skills) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillStore.Skill s : skills) {
            List<String> patterns = new ArrayList<>();
            if (s.hasTemplate()) patterns.add(SkillTemplate.toRelayRegex(s.template()));
            if (s.triggers() != null) {
                for (String t : s.triggers()) {
                    String n = String.join(" ", SkillTemplate.words(t).stream().map(SkillTemplate::norm).toList());
                    if (!n.isBlank()) patterns.add(Pattern.quote(n));
                }
            }
            if (patterns.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", s.name());
            item.put("patterns", patterns);
            out.add(item);
        }
        return Map.of("skills", out);
    }

    /** Отправить фразы навыков в фоне; ошибка — только строка в журнал, агент работает дальше. */
    public static void push(String relayWsUrl, TokenStore tokens) {
        Thread t = new Thread(() -> {
            try {
                String deviceId = tokens.loadDeviceId();
                String token = tokens.load();
                if (deviceId == null || token == null) return;
                URI relay = URI.create(relayWsUrl);
                String scheme = "wss".equalsIgnoreCase(relay.getScheme()) ? "https" : "http";
                String hostPort = relay.getHost() + (relay.getPort() > 0 ? ":" + relay.getPort() : "");
                HttpRequest req = HttpRequest.newBuilder(URI.create(scheme + "://" + hostPort + "/api/agent/skills"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("X-Device-Id", deviceId)
                        .header("X-Device-Token", token)
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                MAPPER.writeValueAsString(payload(SkillStore.all())), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    System.err.println("фразы навыков не переданы релею: HTTP " + resp.statusCode());
                } else {
                    System.out.println("Фразы навыков переданы релею: " + SkillStore.all().size() + " навык(ов).");
                }
            } catch (Exception e) {
                System.err.println("фразы навыков не переданы релею: " + e.getMessage());
            }
        }, "skill-sync");
        t.setDaemon(true);
        t.start();
    }
}
