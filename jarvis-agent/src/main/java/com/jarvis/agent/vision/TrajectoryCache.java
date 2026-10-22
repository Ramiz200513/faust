package com.jarvis.agent.vision;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Память computer-use агента: успешно выполненная задача сохраняется как
 * ТРАЕКТОРИЯ — последовательность действий с описанием элементов, по которым
 * кликали. При следующей такой же (или похожей) цели агент сначала пробует
 * повторить её БЕЗ ЕДИНОГО ОБРАЩЕНИЯ К МОДЕЛИ: та же задача выполняется за
 * пару секунд вместо десятков и бесплатно.
 *
 * <p>Это и есть ответ на вопрос «зачем тут LLM, если можно захардкодить»:
 * система сама превращает решение модели в захардкоженный сценарий, но только
 * после того, как оно один раз доказанно сработало.
 *
 * <h2>Почему индекс элемента нельзя сохранять</h2>
 * Нумерация {@code data-jarvis-idx} пересобирается на КАЖДОМ снимке страницы
 * (см. COLLECT_JS в {@link BrowserAgent}) — «click(5)» назавтра указывает на
 * другой элемент. Поэтому в траектории хранится описание элемента (тег, роль,
 * подпись) и его порядковый номер среди однотипных; на повторе он ищется
 * заново в свежем снимке.
 *
 * <h2>Параметризация</h2>
 * Если модель ввела текст, который дословно есть в цели, это место становится
 * слотом: цель «найди на youtube видео про котов и открой первое» сохраняется
 * как шаблон «найди на youtube {slot} и открой первое». Тогда траектория
 * подойдёт и к «…видео про собак…» — подставится новое содержимое слота.
 *
 * <h2>Граница ответственности</h2>
 * Кэш НЕ проверяет, что цель действительно достигнута — он проверяет только,
 * что все шаги выполнились без ошибок. Если страница изменилась так, что
 * элемент не находится, повтор прерывается и управление уходит обычному циклу
 * с моделью. Это честное ограничение, его стоит назвать самому.
 */
public final class TrajectoryCache {

    /** Маркер слота в шаблоне. Не может появиться в нормализованном тексте — там только буквы, цифры и пробелы. */
    private static final String SLOT = "{slot}";

    /**
     * Метки в трейсе. Трейс — единственное, что доходит до {@link RunJournal}
     * (менять ResultData.AgentReport нельзя: протокол пришлось бы править
     * синхронно в трёх местах), поэтому журнал вычитывает эти две строки,
     * чтобы посчитать долю задач, выполненных из памяти, и экономию запросов.
     */
    public static final String REPLAY_MARK = "цель достигнута из памяти";
    public static final String LLM_CALLS_MARK = "обращений к модели: ";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Файл памяти. Свойство faust.trajectoriesFile нужно тестам: они не должны
     * трогать настоящую память агента в домашней папке.
     */
    private static final Path FILE = System.getProperty("faust.trajectoriesFile") != null
            ? Paths.get(System.getProperty("faust.trajectoriesFile"))
            : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "trajectories.json");

    /** Чтобы файл не рос бесконечно; при переполнении вытесняется самая старая запись. */
    private static final int MAX_TRAJECTORIES = 50;

    /** Один шаг траектории — то же, что {@link AgentAction}, но с описанием элемента вместо индекса. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            /** click | type | scroll | navigate */
            String action,
            /** Тег элемента, по которому кликали (a, button, input…); null для действий без элемента. */
            String tag,
            /** role/type элемента, если были заданы. */
            String role,
            /** Видимая подпись элемента на момент записи — основной способ найти его снова. */
            String label,
            /** Номер среди элементов с тем же тегом и ролью — запасной способ, когда подпись изменилась. */
            int ordinal,
            /** Что вводили (для type). */
            String text,
            /** На сколько прокручивали (для scroll). */
            Integer amount,
            /** Куда переходили (для navigate). */
            String url,
            /** true, если text был дословно взят из цели — на повторе подставляется новый слот. */
            boolean textFromGoal
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trajectory(
            /** Нормализованная цель, где место переменной части занято {@link #SLOT}. */
            String template,
            /** Исходная формулировка — только для логов и отладки. */
            String sampleGoal,
            /** С какого адреса начинался запуск. */
            String startUrl,
            List<Step> steps,
            long recordedAtMs,
            /** Сколько заняла задача «вживую», мс — база для сравнения при замере ускорения. */
            long baselineMs,
            /** Сколько обращений к модели понадобилось «вживую» — вторая половина той же метрики. */
            int baselineLlmCalls,
            /** Сколько раз траекторию уже успешно воспроизвели. */
            int replays
    ) {}

    /** Найденная траектория вместе с содержимым слота для ЭТОЙ цели (null, если шаблон без слота). */
    public record Match(Trajectory trajectory, String slot) {}

    private static List<Trajectory> cache;

    private TrajectoryCache() {}

    // --- поиск ---------------------------------------------------------

    /**
     * Есть ли сохранённая траектория для этой цели.
     * Сначала пробуем шаблоны без слота (точное совпадение — самое надёжное),
     * затем со слотом: иначе шаблон «{slot}» поглотил бы вообще всё.
     */
    public static synchronized Optional<Match> find(String goal) {
        return find(goal, false);
    }

    /**
     * Траектория, выученная в ПРОГРАММЕ (режим UI Automation), а не в браузере.
     * Хранятся в том же файле, но с меткой {@link #UIA_START} вместо адреса —
     * иначе BrowserAgent попытался бы «открыть» такую траекторию как страницу.
     */
    public static synchronized Optional<Match> findUia(String goal) {
        return find(goal, true);
    }

    /** Метка начала для траекторий в программах (поле startUrl). */
    static final String UIA_START = "uia:";

    /** Запомнить успешный путь в программе — см. {@link #record}. */
    public static synchronized void recordUia(String goal, List<Step> steps, long durationMs, int llmCalls) {
        record(goal, UIA_START, steps, durationMs, llmCalls);
    }

    /**
     * Забыть всё, что выучено для этой цели. Вызывает PlanRunner, когда
     * проверка результата опровергла «готово» подзадачи: запомненный путь
     * повторил бы ту же ошибку, только быстро и без модели.
     */
    public static synchronized void forgetGoal(String goal) {
        find(goal, false).ifPresent(m -> forget(m.trajectory()));
        find(goal, true).ifPresent(m -> forget(m.trajectory()));
    }

    private static Optional<Match> find(String goal, boolean uia) {
        String normalized = norm(goal);
        if (normalized.isBlank()) return Optional.empty();

        for (Trajectory t : load()) {
            if (isUia(t) != uia) continue;
            if (!t.template().contains(SLOT) && t.template().equals(normalized)) {
                return Optional.of(new Match(t, null));
            }
        }
        for (Trajectory t : load()) {
            if (isUia(t) != uia) continue;
            int at = t.template().indexOf(SLOT);
            if (at < 0) continue;
            String prefix = t.template().substring(0, at);
            String suffix = t.template().substring(at + SLOT.length());
            Matcher m = Pattern.compile("^" + Pattern.quote(prefix) + "(.+?)" + Pattern.quote(suffix) + "$")
                    .matcher(normalized);
            if (m.matches()) {
                return Optional.of(new Match(t, m.group(1)));
            }
        }
        return Optional.empty();
    }

    // --- запись --------------------------------------------------------

    /**
     * Запомнить успешный запуск. Вызывается ТОЛЬКО когда модель сама сказала
     * «цель достигнута» — запоминать неудачные попытки означало бы
     * воспроизводить чужие ошибки быстро и бесплатно.
     */
    public static synchronized void record(String goal, String startUrl, List<Step> steps,
                                           long durationMs, int llmCalls) {
        if (steps == null || steps.isEmpty()) return;

        String template = buildTemplate(goal, steps);
        List<Trajectory> items = new ArrayList<>(load());
        items.removeIf(t -> t.template().equals(template));   // новая версия вытесняет прошлую
        items.add(new Trajectory(template, goal, startUrl, steps,
                System.currentTimeMillis(), durationMs, llmCalls, 0));
        while (items.size() > MAX_TRAJECTORIES) items.remove(0);

        cache = items;
        persist(items);
        System.out.println("Траектория сохранена (" + steps.size() + " шаг(ов)): " + template);
    }

    /** Отметить успешный повтор — счётчик нужен для таблицы «сколько задач пошло из памяти». */
    public static synchronized void noteReplay(Trajectory used) {
        List<Trajectory> items = new ArrayList<>(load());
        items.replaceAll(t -> t.template().equals(used.template())
                ? new Trajectory(t.template(), t.sampleGoal(), t.startUrl(), t.steps(),
                                 t.recordedAtMs(), t.baselineMs(), t.baselineLlmCalls(), t.replays() + 1)
                : t);
        cache = items;
        persist(items);
    }

    /**
     * Забыть траекторию, которая перестала воспроизводиться (страница изменилась).
     * Держать сломанную запись вредно: каждая следующая задача начиналась бы с
     * заведомо бесполезной попытки повтора.
     */
    public static synchronized void forget(Trajectory broken) {
        List<Trajectory> items = new ArrayList<>(load());
        items.removeIf(t -> t.template().equals(broken.template()));
        cache = items;
        persist(items);
        System.out.println("Траектория устарела и удалена: " + broken.template());
    }

    /** Сколько траекторий сейчас в памяти — для пункта трея и отчётности. */
    public static synchronized int size() {
        return load().size();
    }

    /**
     * Стереть всю память агента (пункт трея «Забыть выученные задачи»).
     * Нужен и для замеров: чтобы сравнить «первый раз» с «повтором», первый
     * раз должен быть действительно первым.
     */
    public static synchronized void forgetAll() {
        cache = new ArrayList<>();
        persist(cache);
        System.out.println("Память агента очищена");
    }

    // --- внутреннее ----------------------------------------------------

    /**
     * Превращает цель в шаблон: первое введённое значение, которое дословно
     * встречается в цели, заменяется слотом. Остальные шаги сохраняются как есть —
     * поддержка нескольких слотов усложнила бы сопоставление без заметной пользы.
     */
    private static String buildTemplate(String goal, List<Step> steps) {
        String normalized = norm(goal);
        for (Step s : steps) {
            if (!"type".equals(s.action()) || !s.textFromGoal()) continue;
            String typed = norm(s.text());
            int at = normalized.indexOf(typed);
            if (typed.isBlank() || at < 0) continue;
            return normalized.substring(0, at) + SLOT + normalized.substring(at + typed.length());
        }
        return normalized;
    }

    private static boolean isUia(Trajectory t) {
        return t.startUrl() != null && t.startUrl().startsWith(UIA_START);
    }

    /** Нижний регистр, ё→е, долой пунктуацию и двойные пробелы — чтобы «Открой YouTube!» и «открой youtube» совпали. */
    public static String norm(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^а-яa-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<Trajectory> load() {
        if (cache != null) return cache;
        try {
            if (Files.exists(FILE)) {
                cache = new ArrayList<>(List.of(MAPPER.readValue(Files.readString(FILE), Trajectory[].class)));
                return cache;
            }
        } catch (Exception e) {
            // Битый/устаревший файл — не повод падать: начинаем с пустой памяти,
            // первая же успешная задача перезапишет файл целиком.
            System.err.println("не удалось прочитать траектории: " + e.getMessage());
        }
        cache = new ArrayList<>();
        return cache;
    }

    private static void persist(List<Trajectory> items) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items));
        } catch (Exception e) {
            // Память агента — не критичный путь: не сохранили, значит в следующий
            // раз задача просто пойдёт обычным путём через модель.
            System.err.println("не удалось сохранить траектории: " + e.getMessage());
        }
    }
}
