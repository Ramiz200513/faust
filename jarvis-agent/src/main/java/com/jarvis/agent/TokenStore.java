package com.jarvis.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Хранит постоянный токен агента И его deviceId на диске между запусками.
 * Оба нужны вместе: релей находит устройство по deviceId и сверяет токен с
 * BCrypt-хэшем. Если deviceId менять при каждом запуске (как было раньше),
 * после перезапуска релей не узнаёт устройство — "unknown device".
 *
 * Файл: $HOME/.jarvis-agent/token.properties, ключи "token" и "deviceId".
 */
public class TokenStore {

    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE_ID = "deviceId";

    private final Path file;

    public TokenStore() {
        this(Paths.get(System.getProperty("user.home"), ".jarvis-agent", "token.properties"));
    }

    TokenStore(Path file) {
        this.file = file;
    }

    /** Постоянный токен, или null если ещё нет. */
    public String load() {
        return read(KEY_TOKEN);
    }

    /** Сохранённый deviceId, или null если ещё нет. */
    public String loadDeviceId() {
        return read(KEY_DEVICE_ID);
    }

    /** Сохранить токен (не затирая deviceId). */
    public void save(String token) {
        write(KEY_TOKEN, token);
    }

    /** Сохранить deviceId (не затирая токен). */
    public void saveDeviceId(String deviceId) {
        write(KEY_DEVICE_ID, deviceId);
    }

    /** Стереть токен и deviceId — используется пунктом трея «Настроить заново»:
     *  агент как будто ни разу не привязывался, следующий запуск снова
     *  покажет окно приветствия. */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("Не удалось удалить " + file + ": " + e.getMessage());
        }
    }

    // --- внутреннее: читаем/пишем весь файл, чтобы ключи не затирали друг друга ---

    private Properties readAll() {
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

    private String read(String key) {
        String v = readAll().getProperty(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private void write(String key, String value) {
        Properties props = readAll();          // сохраняем то, что уже было
        props.setProperty(key, value);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Данные агента jarvis. Не передавайте этот файл третьим лицам.");
            }
        } catch (IOException e) {
            System.err.println("Не удалось сохранить в " + file + ": " + e.getMessage());
        }
    }
}
