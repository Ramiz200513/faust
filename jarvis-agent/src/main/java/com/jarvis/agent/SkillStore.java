package com.jarvis.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.agent.vision.TrajectoryCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Навыки, которым человек научил агента ПОКАЗОМ (DemoRecorder).
 *
 * <p>Навык — это название, фразы-триггеры и выученная последовательность
 * шагов в том же формате, что память траекторий (TrajectoryCache.Step):
 * «открыть Telegram», «нажать «Поиск»», «ввести «Избранное»»… Повтор идёт
 * тем же кодом, что повтор из памяти, — без модели. А если окно изменилось и
 * повторить дословно нельзя, записанные шаги становятся для модели
 * ИНСТРУКЦИЕЙ («человек показал так: …»), и она доделывает навык сама.
 *
 * <p>Хранится в ~/.jarvis-agent/skills.json; свойство faust.skillsFile — для тестов.
 */
public final class SkillStore {

    /**
     * @param descriptions те же шаги человеческими фразами — для панели плана,
     *                     для инструкции модели и для списка навыков в трее
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Skill(String name, List<String> triggers, List<TrajectoryCache.Step> steps,
                        List<String> descriptions, long createdAtMs, long recordedMs,
                        /** Шаблон фразы с переменными: [«отправь», «{1}», «{2}»]; null — навык без переменных. */
                        List<String> template,
                        /** Переменные шаблона (см. SkillTemplate) — что было в показе и как сказано. */
                        List<SkillTemplate.Slot> slots) {

        @JsonCreator
        public Skill {
        }

        /** Навык без переменных — как до «показа с переменными» (и в старом skills.json). */
        public Skill(String name, List<String> triggers, List<TrajectoryCache.Step> steps,
                     List<String> descriptions, long createdAtMs, long recordedMs) {
            this(name, triggers, steps, descriptions, createdAtMs, recordedMs, null, null);
        }

        public boolean hasTemplate() {
            return template != null && !template.isEmpty();
        }

        /** Шаблон фразы для человека: «отправь {1} {2}» -> «отправь … …». */
        public String templateText() {
            return hasTemplate() ? String.join(" ", template).replaceAll("\\{\\d+}", "…") : null;
        }
    }

    /**
     * Найденный навык и значения переменных для этого запуска. binding == null —
     * навык без переменных (найден по названию или фразе-триггеру).
     */
    public record Found(Skill skill, SkillTemplate.Binding binding) {
        /** Шаги словами — уже с подставленными значениями: «Ввести «Маше»». */
        public List<String> shownDescriptions() {
            if (binding == null) return skill.descriptions();
            return skill.descriptions().stream().map(binding::shown).toList();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillStore() {}

    private static Path file() {
        String override = System.getProperty("faust.skillsFile");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "skills.json");
    }

    public static synchronized List<Skill> all() {
        try {
            Path f = file();
            if (Files.exists(f)) return List.of(MAPPER.readValue(Files.readString(f), Skill[].class));
        } catch (Exception e) {
            System.err.println("не удалось прочитать навыки: " + e.getMessage());
        }
        return List.of();
    }

    /** Сохранить навык; с тем же названием — заменить (научили заново). */
    public static synchronized void save(Skill skill) {
        List<Skill> items = new ArrayList<>(all());
        items.removeIf(s -> norm(s.name()).equals(norm(skill.name())));
        items.add(skill);
        persist(items);
    }

    public static synchronized boolean remove(String name) {
        List<Skill> items = new ArrayList<>(all());
        boolean removed = items.removeIf(s -> norm(s.name()).equals(norm(name)));
        if (removed) persist(items);
        return removed;
    }

    /**
     * Какой навык просят этой фразой. Совпадение — по названию или триггеру,
     * с приставкой или без: «навык утро», «выполни навык утро», «доброе утро».
     * Только точное совпадение после нормализации: навык выполняет действия на
     * ПК без модели, и «похоже на» тут не годится.
     */
    public static synchronized Optional<Skill> match(String goal) {
        return find(goal).map(Found::skill);
    }

    /**
     * Какой навык просят и с какими данными. Сначала точное совпадение
     * (название, «навык X», фраза-триггер), затем шаблоны с переменными:
     * «отправь Маше расписание» под шаблон «отправь {1} {2}», выученный из
     * показа «отправь Пете отчёт».
     */
    public static synchronized Optional<Found> find(String goal) {
        Optional<Skill> exact = matchExact(goal);
        if (exact.isPresent()) return Optional.of(new Found(exact.get(), null));
        String phrase = goal.strip().replaceFirst("(?iu)^(фауст|faust)[,!\\s]+", "");
        for (Skill s : all()) {
            if (!s.hasTemplate()) continue;
            Optional<java.util.Map<Integer, String>> values = SkillTemplate.match(s.template(), s.slots(), phrase);
            if (values.isPresent()) {
                return Optional.of(new Found(s, new SkillTemplate.Binding(
                        s.slots() == null ? List.of() : s.slots(), values.get())));
            }
        }
        return Optional.empty();
    }

    private static Optional<Skill> matchExact(String goal) {
        String g = norm(goal);
        // «Фауст, навык утро» — обращение к ассистенту не часть названия.
        if (g.startsWith("фауст ")) g = g.substring("фауст ".length());
        for (String prefix : new String[]{"выполни навык ", "запусти навык ", "навык "}) {
            if (g.startsWith(prefix)) {
                g = g.substring(prefix.length()).strip();
                break;
            }
        }
        for (Skill s : all()) {
            if (norm(s.name()).equals(g)) return Optional.of(s);
            if (s.triggers() != null) {
                for (String t : s.triggers()) {
                    if (!norm(t).isBlank() && norm(t).equals(g)) return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    private static String norm(String s) {
        return TrajectoryCache.norm(s);
    }

    private static void persist(List<Skill> items) {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items));
        } catch (Exception e) {
            System.err.println("не удалось сохранить навыки: " + e.getMessage());
        }
    }
}
