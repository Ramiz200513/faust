package com.jarvis.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Настройки агента, которые не хочется каждый раз передавать аргументами
 * командной строки: адрес релея и (отдельно) адрес релея для телефона —
 * пригодится в окне «Привязать телефон» (localhost там не годится).
 *
 * Файл: $HOME/.jarvis-agent/agent.properties, ключи "relayUrl" и
 * "phoneRelayUrl" (оба необязательны).
 *
 * Аргумент командной строки [0] (адрес релея), если передан, важнее файла —
 * это сохраняет привычный первый запуск с кодом привязки:
 *   gradlew run --args="ws://localhost:8080/ws <код>"
 */
public class AgentConfig {

    private static final String KEY_RELAY_URL = "relayUrl";
    private static final String KEY_PHONE_RELAY_URL = "phoneRelayUrl";
    static final String DEFAULT_RELAY_URL = "ws://localhost:8080/ws";

    private final String relayUrl;
    private final String phoneRelayUrl;

    private AgentConfig(String relayUrl, String phoneRelayUrl) {
        this.relayUrl = relayUrl;
        this.phoneRelayUrl = phoneRelayUrl;
    }

    /** Адрес релея для самого агента (WebSocket). */
    public String relayUrl() {
        return relayUrl;
    }

    /** Адрес релея для телефона (для QR), или null — тогда его надо определять автоматически. */
    public String phoneRelayUrl() {
        return phoneRelayUrl;
    }

    public static AgentConfig resolve(String[] args) {
        return resolve(args, Paths.get(System.getProperty("user.home"), ".jarvis-agent", "agent.properties"));
    }

    static AgentConfig resolve(String[] args, Path file) {
        Properties props = read(file);
        String argRelayUrl = args.length > 0 ? args[0].trim() : "";
        String relayUrl = !argRelayUrl.isEmpty()
                ? argRelayUrl
                : props.getProperty(KEY_RELAY_URL, DEFAULT_RELAY_URL).trim();
        if (relayUrl.isEmpty()) {
            relayUrl = DEFAULT_RELAY_URL;
        }

        String phoneRelayUrl = props.getProperty(KEY_PHONE_RELAY_URL);
        if (phoneRelayUrl != null) {
            phoneRelayUrl = phoneRelayUrl.trim();
            if (phoneRelayUrl.isEmpty()) {
                phoneRelayUrl = null;
            }
        }
        return new AgentConfig(relayUrl, phoneRelayUrl);
    }

    private static Properties read(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
    }
}
