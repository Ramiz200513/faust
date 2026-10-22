package com.jarvis.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Оборванный план — чтобы продолжить его с места сбоя, а не с начала.
 *
 * <h2>Зачем</h2>
 * Длинная связка из 8 шагов падает на шестом (не нашлась кнопка, кончилось
 * время, человек отменил). Начинать заново — значит повторить пять сделанных
 * шагов: скачать файл второй раз, отправить сообщение второй раз. Поэтому при
 * обрыве сохраняется, ЧТО уже сделано (с итогами — именами файлов и т.п.) и что
 * осталось. «Фауст, продолжи» — и план идёт дальше с упавшего шага, а
 * следующие шаги получают итоги сделанных, как будто обрыва не было.
 *
 * <p>Хранится одна последняя задача и не дольше {@link #MAX_AGE_MS}: через
 * несколько часов экран и файлы уже другие, и «продолжить» вслепую нельзя.
 */
public final class ResumeStore {

    /** Сколько живёт сохранённый план: 3 часа. */
    static final long MAX_AGE_MS = 3 * 60 * 60 * 1000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param goal      исходная просьба
     * @param done      что уже сделано, с итогами
     * @param remaining что осталось, начиная с упавшего шага
     * @param reason    почему оборвалось — человек видит это в вопросе «продолжить?»
     */
    public record Saved(String goal, List<StepResult> done, List<PlanStep> remaining, String reason, long savedAtMs) {}

    private ResumeStore() {}

    /** «продолжи», «доделай задачу», «продолжи с места сбоя». Не «продолжи музыку» — это пауза/плей. */
    private static final Pattern REQUEST = Pattern.compile(
            "^(?:(?:фауст|faust)[,!\\s]+)?(?:продолжи|продолжай|доделай|возобнови)"
            + "(?:\\s+(?:задачу|план|начатое|работу|с\\s+места\\s+(?:сбоя|остановки|ошибки)|где\\s+остановился))?"
            + "(?:\\s+пожалуйста)?[.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean isResumeRequest(String goal) {
        return goal != null && REQUEST.matcher(goal.strip().toLowerCase(Locale.ROOT).replace('ё', 'е')).matches();
    }

    static Path file() {
        String override = System.getProperty("faust.resumeFile");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "resume.json");
    }

    public static synchronized void save(Saved s) {
        try {
            Files.createDirectories(file().getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), s);
        } catch (IOException e) {
            System.err.println("не удалось сохранить оборванный план: " + e.getMessage());
        }
    }

    /** Последний оборванный план, если он ещё свежий и в нём что-то осталось. */
    public static synchronized Optional<Saved> load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            Saved s = MAPPER.readValue(f.toFile(), Saved.class);
            if (s.remaining() == null || s.remaining().isEmpty()) return Optional.empty();
            if (System.currentTimeMillis() - s.savedAtMs() > MAX_AGE_MS) return Optional.empty();
            return Optional.of(s);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static synchronized void clear() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException ignored) {
            // не удалилось — устареет само через MAX_AGE_MS
        }
    }
}
