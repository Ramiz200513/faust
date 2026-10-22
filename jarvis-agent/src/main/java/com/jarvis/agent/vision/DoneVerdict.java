package com.jarvis.agent.vision;

import java.util.List;
import java.util.Locale;

/**
 * Достигнута ли цель, когда модель сказала done.
 *
 * <h2>Почему это отдельный вопрос (ошибка найдена 30.09 на живом прогоне)</h2>
 * Раньше успехом считалось ЛЮБОЕ done, кроме «LLM недоступна». Но модель
 * заканчивает цикл и тогда, когда сдаётся: «нужен ввод пароля — отказываюсь»,
 * «цель недостижима». На прогоне без планировщика так записался «успех»
 * задачи, в которой агент дошёл до страницы входа Telegram и отказался, — и
 * ещё один, где он нашёл курс, но в Блокнот так ничего и не записал. Для
 * таблиц диплома это худшая из ошибок: цифры успеха завышены.
 *
 * <p>Теперь модель явно пишет {@code "success": true|false}. Если не написала
 * (старый формат, забыла) — смотрим на текст причины: слова отказа и
 * невозможности означают неуспех.
 */
final class DoneVerdict {

    /** Признаки того, что done — это «сдаюсь», а не «готово». */
    private static final List<String> FAILURE_WORDS = List.of(
            "llm недоступна", "отказыва", "недостижим", "не удалось", "невозможно", "не могу",
            "не получилось", "не подтвердил", "не выполнен", "не найден");

    private DoneVerdict() {}

    static boolean succeeded(Boolean flag, String reason) {
        if (flag != null) {
            // Даже при success:true «LLM недоступна» — это сбой, а не успех
            // (так done подставляет сам агент, когда модель не ответила).
            return flag && !(reason != null && reason.startsWith("LLM недоступна"));
        }
        if (reason == null) return true;
        String r = reason.toLowerCase(Locale.ROOT);
        return FAILURE_WORDS.stream().noneMatch(r::contains);
    }
}
