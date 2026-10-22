package com.jarvis.agent.vision;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Последний рубеж защиты от ввода паролей/платёжных данных computer-use
 * агентом — ПОМИМО запрета в самом промпте (см. {@link GeminiVisionAgent}).
 * Модель иногда всё равно может предложить такое действие (галлюцинация,
 * успешная prompt-инъекция со страницы) — эта проверка работает уже на
 * уровне исполнения действия, независимо от LLM.
 *
 * Эвристика заведомо неполная (это не серебряная пуля), но отсекает явные
 * случаи: цель/поле явно про пароль или карту, либо сам текст похож на номер
 * карты (13-19 цифр подряд, возможно с пробелами/дефисами).
 */
public final class SensitiveInputGuard {

    private static final Pattern CARD_NUMBER = Pattern.compile("(?:\\d[ -]?){13,19}");

    /** Ищутся как подстроки: у них нет безобидных «соседей» внутри других слов. */
    private static final Set<String> KEYWORDS = Set.of(
            "password", "пароль", "парол", "card number", "номер карты",
            "cvv", "cvc", "пин-код", "пин код",
            "payment", "оплат", "banking", "карточк"
    );

    /**
     * Короткие ключевые слова — только ЦЕЛЫМ словом.
     *
     * <p>Раньше «карт», «card» и «pin» искались как подстроки и срабатывали на
     * «отправь картинку Лере», «открой pinterest», «shopping». С появлением
     * планировщика это стало блокером: подзадача «отправить картинку» просто
     * отказывалась стартовать. Теперь «карта/карты/карту…» и «card/pin»
     * ловятся, а «картинка» — нет.
     */
    private static final Pattern SHORT_WORDS = Pattern.compile(
            "(?<!\\p{L})(карт[аыуеой]{0,2}|cards?|pin)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private SensitiveInputGuard() {}

    /** Похоже ли на что-то, связанное с паролем/платёжными данными. */
    public static boolean mentionsSensitive(String text) {
        if (text == null || text.isBlank()) return false;
        String s = text.toLowerCase(Locale.ROOT);
        for (String kw : KEYWORDS) {
            if (s.contains(kw)) return true;
        }
        if (SHORT_WORDS.matcher(s).find()) return true;
        return CARD_NUMBER.matcher(s).find();
    }
}
