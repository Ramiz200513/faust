package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Понятная причина ошибки Gemini — без данных запроса.
 *
 * <h2>Зачем</h2>
 * 23.11 Gemini десятки раз ответил 429, а в журнале было только «Gemini HTTP 429»: нельзя
 * понять, это минутный лимит (подождать), дневная квота (до завтра) или оплата (идти в
 * консоль Google). Всё это Google пишет в теле ответа.
 *
 * <h2>Почему не всё тело</h2>
 * Тело ошибки может повторять запрос — а в нём фразы человека и список окон его ПК. Поэтому
 * берём только служебные поля Google: {@code error.status} (RESOURCE_EXHAUSTED и т.п.),
 * {@code error.message} (обрезанный до 200 символов) и названия квот из
 * {@code error.details[].violations[].quotaId|quotaMetric}.
 */
final class GeminiErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGE = 200;

    private GeminiErrors() {}

    /** «Gemini HTTP 429 RESOURCE_EXHAUSTED: <текст> [квота: …]»; тело не разобралось — только код. */
    static String describe(int status, String body) {
        StringBuilder sb = new StringBuilder("Gemini HTTP ").append(status);
        try {
            JsonNode err = MAPPER.readTree(body == null ? "" : body).path("error");
            if (err.isMissingNode()) return sb.toString();
            String st = err.path("status").asText("");
            String msg = err.path("message").asText("").replaceAll("\\s+", " ").strip();
            if (!st.isEmpty()) sb.append(' ').append(st);
            if (!msg.isEmpty()) {
                sb.append(": ").append(msg.length() > MAX_MESSAGE ? msg.substring(0, MAX_MESSAGE) + "…" : msg);
            }
            StringBuilder quotas = new StringBuilder();
            for (JsonNode d : err.path("details")) {
                for (JsonNode v : d.path("violations")) {
                    String q = v.path("quotaId").asText(v.path("quotaMetric").asText(""));
                    if (!q.isEmpty() && quotas.indexOf(q) < 0) {
                        if (quotas.length() > 0) quotas.append(", ");
                        quotas.append(q);
                    }
                }
            }
            if (quotas.length() > 0) sb.append(" [квота: ").append(quotas).append(']');
        } catch (Exception ignored) {
            // не JSON (например, HTML от прокси) — хватит кода
        }
        return sb.toString();
    }
}
