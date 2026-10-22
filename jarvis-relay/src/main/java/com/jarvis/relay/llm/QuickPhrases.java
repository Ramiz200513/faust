package com.jarvis.relay.llm;

import com.jarvis.relay.protocol.Command;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Быстрые команды, которые узнаются правилами, без модели: пауза, трек вперёд
 * и назад, громкость на N процентов от текущей, свернуть все окна.
 *
 * <h2>Почему правила, а не модель</h2>
 * Таких фраз немного, и звучат они почти всегда одинаково («пауза»,
 * «следующий трек», «громче на 20 процентов»). Правило отвечает за
 * микросекунды и одинаково каждый раз. А модель эту команду пока не знает
 * вовсе: её пришлось бы дообучать, и до этого «громче на 20%» превращалось в
 * «громкость 20» — то есть в звук ТИШЕ (экзамен Б, фраза b-45).
 *
 * <p>Правила намеренно узкие: фраза должна целиком состоять из команды (плюс
 * «Фауст», «пожалуйста»). «Поставь на паузу и открой почту» сюда не попадёт —
 * её разберёт делитель составных фраз (см. {@link HybridIntentParser}) по частям.
 */
public final class QuickPhrases {

    private QuickPhrases() {}

    // UNICODE_CHARACTER_CLASS обязателен: без него \w в Java — только латиница, и «следующ\w+» не видит «следующий».
    private static final int F = Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern PLAY_PAUSE = Pattern.compile(
            "(поставь\\s+(на\\s+)?паузу|на\\s+паузу|пауза|паузу|сними\\s+с\\s+паузы|сними\\s+паузу|сделай\\s+паузу|"
            + "останови\\s+(музыку|видео|воспроизведение|трек|песню)|"
            + "продолжи\\s+(воспроизведение|музыку|видео|играть)|включи\\s+(обратно|дальше)|play|pause)", F);
    private static final Pattern NEXT = Pattern.compile(
            "((включи|поставь|давай)\\s+)?(следующ\\w+\\s+(трек|песн\\w+|видео|композици\\w+)|"
            + "(переключи|пропусти|скипни)\\s+(трек|песн\\w+|видео)|(трек|песню)\\s+дальше|некст|next(\\s+track)?)", F);
    private static final Pattern PREV = Pattern.compile(
            "((включи|поставь|верни|давай)\\s+)?(предыдущ\\w+|прошл\\w+)\\s+(трек|песн\\w+|видео|композици\\w+)"
            + "|previous(\\s+track)?", F);
    private static final Pattern MINIMIZE = Pattern.compile(
            "(сверни|свернуть|спрячь|скрой)\\s+(все\\s+)?окна|покажи\\s+рабочий\\s+стол|minimize\\s+all", F);

    /** «громче на 20», «сделай тише на десять процентов», «прибавь звук на 15%». */
    private static final Pattern VOLUME_DELTA = Pattern.compile(
            "(сделай\\s+|сделать\\s+)?(?<dir>погромче|громче|прибавь|прибавить|увеличь|увеличить|подними|"
            + "потише|тише|убавь|убавить|уменьши|уменьшить|понизь|опусти)"
            + "(\\s+(звук|громкость))?\\s+на\\s+(?<n>\\d{1,3}|[а-я-]+(\\s+[а-я-]+)?)"
            + "(\\s*%|\\s+процент\\w*)?", F);

    /** Числа словами — те, что реально говорят про громкость. */
    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("пять", 5), Map.entry("десять", 10), Map.entry("пятнадцать", 15), Map.entry("двадцать", 20),
            Map.entry("двадцать пять", 25), Map.entry("тридцать", 30), Map.entry("сорок", 40),
            Map.entry("пятьдесят", 50), Map.entry("половину", 50), Map.entry("треть", 33),
            Map.entry("чуть-чуть", 5), Map.entry("немного", 10), Map.entry("капельку", 5));

    /** Фраза — быстрая команда целиком? Иначе null. */
    public static Command parse(String phrase) {
        String p = clean(phrase);
        if (p.isEmpty()) return null;
        if (PLAY_PAUSE.matcher(p).matches()) return new Command.QuickAction("play_pause", 0);
        if (NEXT.matcher(p).matches()) return new Command.QuickAction("next_track", 0);
        if (PREV.matcher(p).matches()) return new Command.QuickAction("prev_track", 0);
        if (MINIMIZE.matcher(p).matches()) return new Command.QuickAction("minimize_all", 0);
        Matcher m = VOLUME_DELTA.matcher(p);
        if (m.matches()) {
            Integer n = number(m.group("n"));
            if (n == null || n <= 0 || n > 100) return null;
            String dir = m.group("dir").toLowerCase(Locale.ROOT);
            boolean down = dir.contains("тише") || dir.startsWith("убав") || dir.startsWith("уменьш")
                           || dir.startsWith("пониз") || dir.startsWith("опуст");
            return new Command.QuickAction("volume_delta", down ? -n : n);
        }
        return null;
    }

    private static Integer number(String s) {
        if (s == null) return null;
        String t = s.strip().toLowerCase(Locale.ROOT);
        if (t.chars().allMatch(Character::isDigit)) return Integer.parseInt(t);
        if (WORDS.containsKey(t)) return WORDS.get(t);
        // «на двадцать процентов» — второе слово съело «процентов»: берём первое.
        String first = t.split("\\s+")[0];
        return WORDS.get(first);
    }

    /** Без обращения, вежливых слов и знаков в конце: «Фауст, пауза, пожалуйста!» -> «пауза». */
    static String clean(String phrase) {
        if (phrase == null) return "";
        String p = phrase.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
        p = p.replaceAll("^(фауст|faust|джарвис)[,!.\\s]+", "");
        p = p.replaceAll("[,\\s]*(пожалуйста|плиз|пж|please)[,\\s]*", " ");
        p = p.replaceAll("[.!?…]+$", "").replaceAll("^[,\\s]+|[,\\s]+$", "");
        return p.replaceAll("\\s+", " ");
    }
}
