package com.jarvis.relay.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Фразы навыков каждого ПК: по ним релей узнаёт просьбу к навыку без модели.
 *
 * Навыки (в том числе выученные показом с переменными — «отправь {1} {2}»)
 * живут на ПК, и только агент знает их список. После входа и после каждого
 * изменения агент присылает сюда выражения для их фраз (PUT /api/agent/skills).
 * Совпала фраза — релей отдаёт её агенту дословно задачей, не спрашивая
 * модель: та могла бы переформулировать «отправь Маше расписание» так, что
 * навык уже не узнается.
 *
 * Хранится в памяти: после перезапуска релея агент пришлёт фразы снова при входе.
 * Каждый ПК видит только свои навыки — фразы хранятся по id агента.
 */
@Component
public class SkillPhrases {

    static final int MAX_SKILLS = 50;
    static final int MAX_PATTERNS_PER_SKILL = 5;
    static final int MAX_PATTERN_LENGTH = 400;
    /** Длиннее — это уже не голосовая просьба к навыку; заодно бережём регулярки от огромного ввода. */
    static final int MAX_PHRASE_LENGTH = 300;

    public record SkillIn(String name, List<String> patterns) {}

    private record Entry(String name, Pattern pattern) {}

    private final Map<String, List<Entry>> byAgent = new ConcurrentHashMap<>();

    /**
     * Заменить фразы навыков агента. Возвращает null или причину отказа
     * (слишком много, слишком длинно, битое выражение) — тогда ничего не меняется.
     */
    public String replace(String agentId, List<SkillIn> skills) {
        if (skills == null) skills = List.of();
        if (skills.size() > MAX_SKILLS) return "слишком много навыков (больше " + MAX_SKILLS + ")";
        List<Entry> entries = new ArrayList<>();
        for (SkillIn s : skills) {
            if (s == null || s.name() == null || s.patterns() == null) return "навык без названия или фраз";
            if (s.patterns().size() > MAX_PATTERNS_PER_SKILL) return "у навыка «" + s.name() + "» слишком много фраз";
            for (String p : s.patterns()) {
                if (p == null || p.isBlank() || p.length() > MAX_PATTERN_LENGTH) return "пустая или слишком длинная фраза";
                try {
                    entries.add(new Entry(s.name(), Pattern.compile(p)));
                } catch (PatternSyntaxException e) {
                    return "битое выражение у навыка «" + s.name() + "»";
                }
            }
        }
        byAgent.put(agentId, List.copyOf(entries));
        return null;
    }

    /** Название навыка, которым агент [agentId] узнает фразу, или null. */
    public String match(String agentId, String phrase) {
        if (agentId == null || phrase == null || phrase.length() > MAX_PHRASE_LENGTH) return null;
        List<Entry> entries = byAgent.get(agentId);
        if (entries == null || entries.isEmpty()) return null;
        String n = normalize(phrase);
        for (Entry e : entries) {
            if (e.pattern().matcher(n).matches()) return e.name();
        }
        return null;
    }

    /**
     * Та же нормализация, что у агента (SkillTemplate.norm по словам): строчные,
     * ё→е, знаки препинания по краям слов убраны, «Фауст,» в начале отброшено.
     */
    static String normalize(String phrase) {
        List<String> out = new ArrayList<>();
        for (String w : phrase.strip().split("\\s+")) {
            String x = w.toLowerCase(Locale.ROOT).replace('ё', 'е');
            int a = 0, b = x.length();
            while (a < b && !Character.isLetterOrDigit(x.charAt(a))) a++;
            while (b > a && !Character.isLetterOrDigit(x.charAt(b - 1))) b--;
            if (b > a) out.add(x.substring(a, b));
        }
        if (!out.isEmpty() && (out.get(0).equals("фауст") || out.get(0).equals("faust"))) out.remove(0);
        return String.join(" ", out);
    }
}
