package com.jarvis.agent.plan;

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
 * Память ПЛАНОВ: успешно выполненная просьба запоминается вместе со своим
 * планом. В следующий раз та же просьба не идёт к планировщику, а главное —
 * подзадачи получают ТЕ ЖЕ формулировки, и каждая находит свою выученную
 * траекторию (TrajectoryCache). Так вся многоэтапная задача повторяется
 * почти без модели: «учится на глазах».
 *
 * <p>Почему не просто траектории подзадач: планировщик каждый раз формулирует
 * подзадачи немного по-разному («Открой чат Избранное…» / «Найди чат
 * «Избранное»…»), и память по тексту подзадачи почти никогда бы не
 * срабатывала. Одинаковый план — одинаковые тексты — попадание в память.
 */
public interface PlanMemory {

    /** Запомненный план: подзадачи, вопрос-подтверждение и сколько он шёл в первый раз. */
    record Saved(List<PlanStep> steps, String confirm, long baselineMs) {}

    Optional<Saved> find(String goal);

    void save(String goal, List<PlanStep> steps, String confirm, long durationMs);

    /** План перестал работать — забыть, чтобы в следующий раз спланировать заново. */
    void forget(String goal);

    /** Без памяти — для тестов и прежнего поведения. */
    PlanMemory NONE = new PlanMemory() {
        @Override public Optional<Saved> find(String goal) { return Optional.empty(); }
        @Override public void save(String goal, List<PlanStep> steps, String confirm, long durationMs) { }
        @Override public void forget(String goal) { }
    };

    /** Хранилище в ~/.jarvis-agent/plans.json. */
    final class File implements PlanMemory {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Entry(String goal, List<PlanStep> steps, String confirm, long baselineMs, long savedAtMs) {}

        private static final int MAX = 30;
        private final ObjectMapper mapper = new ObjectMapper();
        private final Path file;

        public File() {
            this(Paths.get(System.getProperty("user.home"), ".jarvis-agent", "plans.json"));
        }

        File(Path file) {
            this.file = file;
        }

        @Override
        public synchronized Optional<Saved> find(String goal) {
            String key = TrajectoryCache.norm(goal);
            return load().stream().filter(e -> e.goal().equals(key)).findFirst()
                    .map(e -> new Saved(e.steps(), e.confirm(), e.baselineMs()));
        }

        @Override
        public synchronized void save(String goal, List<PlanStep> steps, String confirm, long durationMs) {
            String key = TrajectoryCache.norm(goal);
            List<Entry> items = new ArrayList<>(load());
            items.removeIf(e -> e.goal().equals(key));
            items.add(new Entry(key, List.copyOf(steps), confirm, durationMs, System.currentTimeMillis()));
            while (items.size() > MAX) items.remove(0);
            persist(items);
        }

        @Override
        public synchronized void forget(String goal) {
            String key = TrajectoryCache.norm(goal);
            List<Entry> items = new ArrayList<>(load());
            if (items.removeIf(e -> e.goal().equals(key))) persist(items);
        }

        private List<Entry> load() {
            try {
                if (Files.exists(file)) return List.of(mapper.readValue(Files.readString(file), Entry[].class));
            } catch (Exception e) {
                System.err.println("не удалось прочитать память планов: " + e.getMessage());
            }
            return List.of();
        }

        private void persist(List<Entry> items) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(items));
            } catch (Exception e) {
                System.err.println("не удалось сохранить память планов: " + e.getMessage());
            }
        }
    }
}
