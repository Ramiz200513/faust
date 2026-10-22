package com.jarvis.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Получить код привязки для ЭТОГО ПК без участия администратора:
 * POST /api/agent/register на релее (см. AgentRegistrationController).
 * Дальше код скармливается обычному {@link RelayClient} — тому самому пути,
 * который раньше работал только с кодом из командной строки.
 *
 * Нужно ради сценария «скачал инсталлятор, запустил, всё работает»: человеку
 * не приходится ни у кого просить код. Если релей отвечает 403 (администратор
 * выключил открытую регистрацию через jarvis.open-registration), окно
 * первого запуска показывает обычное поле для кода, выписанного вручную.
 */
final class SelfRegistration {

    private SelfRegistration() {}

    /** Релей отказал или недоступен — текст сообщения показываем в окне. */
    static class Unavailable extends Exception {
        /** true, если регистрация именно запрещена сервером, а не сорвалась по связи. */
        final boolean forbidden;

        Unavailable(String message, boolean forbidden) {
            super(message);
            this.forbidden = forbidden;
        }
    }

    /**
     * Просит у релея одноразовый код для нового устройства.
     *
     * @param relayWsUrl адрес вебсокета релея (ws://host:port/ws) — из него
     *                   получаем http-адрес эндпоинта
     * @param label      человекочитаемое имя ПК, попадёт в список устройств
     * @return шестизначный код, который тут же можно предъявить в Auth
     */
    static String requestCode(String relayWsUrl, String label) throws Unavailable {
        URI endpoint = registerEndpoint(relayWsUrl);
        String body = "{\"label\":\"" + escape(label) + "\"}";

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unavailable(e.getMessage() != null ? e.getMessage() : "нет связи с сервером", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("прервано", false);
        }

        if (response.statusCode() == 403) {
            throw new Unavailable("сервер не разрешает автоматическую регистрацию", true);
        }
        if (response.statusCode() != 200) {
            throw new Unavailable("сервер ответил " + response.statusCode(), false);
        }

        String code = extractCode(response.body());
        if (code == null) {
            throw new Unavailable("сервер не прислал код", false);
        }
        return code;
    }

    /** ws://host:8080/ws -> http://host:8080/api/agent/register (wss -> https). */
    static URI registerEndpoint(String relayWsUrl) {
        String http = relayWsUrl.trim()
                .replaceFirst("^ws://", "http://")
                .replaceFirst("^wss://", "https://");
        URI base = URI.create(http);
        String port = base.getPort() >= 0 ? ":" + base.getPort() : "";
        return URI.create(base.getScheme() + "://" + base.getHost() + port + "/api/agent/register");
    }

    /**
     * Достаёт "code" из ответа. Ответ — три поля, тащить сюда Jackson ради
     * одной строки незачем; формат задан нами же в AgentRegistrationController.
     */
    private static String extractCode(String json) {
        int key = json.indexOf("\"code\"");
        if (key < 0) return null;
        int open = json.indexOf('"', json.indexOf(':', key) + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        String code = json.substring(open + 1, close).trim();
        return code.isEmpty() ? null : code;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Имя ПК для списка устройств в телефоне: имя машины, иначе «Мой компьютер». */
    static String defaultLabel() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        if (name == null || name.isBlank()) {
            try {
                name = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                // имя машины — мелочь, без него просто подставим общее
            }
        }
        return (name == null || name.isBlank()) ? "Мой компьютер" : name.trim();
    }
}
