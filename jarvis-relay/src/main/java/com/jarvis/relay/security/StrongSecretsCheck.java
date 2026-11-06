package com.jarvis.relay.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * На сервере в интернете релей не запустится со слабыми секретами.
 *
 * Зачем. Значения по умолчанию (админ-токен dev-admin-change-me, пароль базы
 * jarvis) удобны дома: всё работает без настройки. Но если с ними выкатить
 * релей наружу, любой, кто прочитал README на GitHub, сможет выписывать коды
 * привязки (то есть получить доступ к ПК) и тратить ключ Gemini. Забыть
 * сменить их — самая частая ошибка при переезде на сервер, поэтому проверка
 * кодом, а не строчкой в инструкции.
 *
 * Включается jarvis.require-strong-secrets=true (в серверной сборке —
 * docker-compose.prod.yml). Дома выключено — ничего не ломается.
 */
@Component
public class StrongSecretsCheck {

    static final int MIN_ADMIN_TOKEN = 32;
    static final int MIN_DB_PASSWORD = 16;

    public StrongSecretsCheck(@Value("${jarvis.require-strong-secrets:false}") boolean required,
                              @Value("${jarvis.admin-token}") String adminToken,
                              @Value("${spring.datasource.password}") String dbPassword) {
        if (!required) return;
        List<String> problems = problems(adminToken, dbPassword);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("релей не запущен — слабые секреты для сервера: "
                    + String.join("; ", problems)
                    + ". Сгенерируйте случайные строки и впишите их в .env (см. deploy/README.md)");
        }
    }

    /** Что не так с секретами; пусто — всё в порядке. Отдельно — для теста. */
    static List<String> problems(String adminToken, String dbPassword) {
        List<String> out = new ArrayList<>();
        if (adminToken == null || adminToken.length() < MIN_ADMIN_TOKEN || adminToken.contains("change-me")) {
            out.add("JARVIS_ADMIN_TOKEN короче " + MIN_ADMIN_TOKEN + " символов или значение по умолчанию");
        }
        if (dbPassword == null || dbPassword.length() < MIN_DB_PASSWORD || dbPassword.equals("jarvis")) {
            out.add("JARVIS_DB_PASSWORD короче " + MIN_DB_PASSWORD + " символов или значение по умолчанию");
        }
        return out;
    }
}
