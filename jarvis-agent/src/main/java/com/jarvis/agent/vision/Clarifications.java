package com.jarvis.agent.vision;

import com.jarvis.agent.AgentSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Вопросы человеку в рамках ОДНОЙ задачи: сколько спросили, что ответили,
 * сколько ждали. Создаётся на каждую agent_task заново.
 *
 * <h2>Зачем отдельный класс, а не просто вызов {@link UserAsker}</h2>
 * Сам вызов — это транспорт (вопрос уехал на телефон, ответ вернулся). А
 * вокруг него нужны правила, которые не должны зависеть от того, насколько
 * хорошо модель следует промпту:
 * <ul>
 *   <li><b>память ответов.</b> Модель видит только последние 8 строк истории.
 *       В длинной задаче ответ «отправь Лере и Маше» уехал бы за край через
 *       несколько шагов, и модель спросила бы снова или забыла бы про Машу.
 *       Поэтому ответы приклеиваются к самой цели ({@link #goalFor}) и видны
 *       модели на каждом шаге до конца задачи;</li>
 *   <li><b>без повторов.</b> Тот же вопрос второй раз человеку не уходит —
 *       модель сразу получает прежний ответ;</li>
 *   <li><b>лимит.</b> Не больше {@code ask.maxPerTask} вопросов на задачу
 *       (по умолчанию 5): агент, который дёргает человека на каждом шаге,
 *       хуже агента, который не спрашивает вовсе;</li>
 *   <li><b>никаких секретов.</b> Вопрос про пароль, код из SMS или данные
 *       карты не уходит на телефон, даже если модель его сформулировала
 *       (например, поддавшись тексту на странице);</li>
 *   <li><b>время ожидания не съедает лимит задачи</b> — см. {@link #extend}.</li>
 * </ul>
 */
public final class Clarifications {

    /**
     * Начало строки трейса о настоящем вопросе, ушедшем человеку. По ней
     * RunJournal и телефонная история узнают, что вопрос был, — не заводя
     * новых полей в AgentReport (это означало бы править протокол в трёх местах).
     */
    public static final String QUESTION_MARK = "вопрос человеку: ";

    /** Больше вариантов на кнопках телефона всё равно не поместится. */
    private static final int MAX_OPTIONS = 6;
    private static final int MAX_OPTION_LENGTH = 60;

    /**
     * Узкий список «секретных» слов — только для вопросов.
     *
     * <p>Общий {@link SensitiveInputGuard} здесь не годится: он ищет подстроку
     * «карт» и срабатывал бы на совершенно безобидное «Какую картинку
     * отправить?». Для ввода текста такая перестраховка оправдана (лучше
     * отказаться лишний раз), а вопрос — это разговор с человеком, и ложный
     * отказ ломал бы как раз ту функцию, ради которой класс написан.
     */
    private static final Set<String> SECRET_WORDS = Set.of(
            "парол", "password", "cvv", "cvc", "пин-код", "пин код", "pin-код",
            "номер карты", "данные карты", "срок действия карты",
            "код из смс", "код из sms", "код подтверждения", "одноразовый код");

    private final UserAsker asker;
    private final boolean enabled;
    private final int maxQuestions;
    /** Пары {вопрос, ответ} — только те, на которые человек реально ответил. */
    private final List<String[]> answered = new ArrayList<>();
    /** Сколько вопросов реально ушло человеку (для журнала и таблиц диплома). */
    private int asked;
    /** Сколько миллисекунд задача простояла в ожидании ответа. */
    private long waitedMs;
    /**
     * Контекст большой задачи для текущей подзадачи плана (см. PlanRunner):
     * исходная просьба и что уже сделано. null — задача не разбита на план.
     */
    private String taskContext;

    /**
     * Итог одного вопроса.
     *
     * @param answer ответ человека; null — ответа нет (молчание, лимит, спросить некого)
     * @param note   строка для трейса — модель прочтёт её в «УЖЕ СДЕЛАНО»
     */
    public record Reply(String answer, String note) {}

    /** Обычный путь: правила берутся из agent.properties. */
    public Clarifications(UserAsker asker) {
        this(asker,
             AgentSettings.flag(AgentSettings.ASK_ENABLED, true),
             AgentSettings.number(AgentSettings.ASK_MAX_PER_TASK, 5, 0, 20));
    }

    Clarifications(UserAsker asker, boolean enabled, int maxQuestions) {
        this.asker = asker == null ? UserAsker.NONE : asker;
        this.enabled = enabled;
        this.maxQuestions = maxQuestions;
    }

    /** Спросить некого — для demo-запусков и старых перегрузок run(...). */
    public static Clarifications none() {
        return new Clarifications(UserAsker.NONE, false, 0);
    }

    /**
     * Обработать действие модели {"action":"ask", ...}.
     *
     * @return строка для трейса. Модель увидит её в «УЖЕ СДЕЛАНО» на следующем
     *         шаге — поэтому каждая ветка не только сообщает, что произошло, но
     *         и подсказывает, что делать дальше.
     */
    public String ask(String question, List<String> options, CancelSignal cancel) {
        return request(question, options, cancel).note();
    }

    /**
     * То же, что {@link #ask}, но возвращает и сам ответ — он нужен
     * планировщику: по «Да»/«Нет» на подтверждение плана решает код, а не модель.
     * Все правила (повторы, лимит, секреты, молчание) — те же самые.
     */
    public Reply request(String question, List<String> options, CancelSignal cancel) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return new Reply(null, "вопрос пустой — сформулируй его или действуй сам");
        }
        if (asksForSecret(q)) {
            return new Reply(null, "такое не спрашиваю: пароли, коды и данные карт агенту не нужны — заверши done");
        }
        // Повтор — ещё до проверки лимита и доступности: прежний ответ есть,
        // и дёргать человека второй раз незачем.
        for (String[] qa : answered) {
            if (norm(qa[0]).equals(norm(q))) {
                return new Reply(qa[1], "уже спрашивал — ответ был: «" + qa[1] + "»");
            }
        }
        if (!enabled) {
            return new Reply(null, "спрашивать человека выключено в настройках — действуй сам; необратимое не делай");
        }
        if (!asker.available()) {
            return new Reply(null, "спросить некого (телефон не подключён) — действуй сам; необратимое не делай");
        }
        if (asked >= maxQuestions) {
            return new Reply(null, "лимит вопросов (" + maxQuestions + ") исчерпан — дальше действуй сам или заверши done");
        }

        asked++;
        long t0 = System.currentTimeMillis();
        String answer = asker.ask(q, cleanOptions(options), cancel);
        long dt = System.currentTimeMillis() - t0;
        waitedMs += dt;

        if (answer == null || answer.isBlank()) {
            // Молчание трактуем как «нет». Иначе агент, не дождавшись ответа
            // на «Отправить файл Лере?», мог бы решить, что можно, — а это ровно
            // то необратимое действие, ради которого он и спрашивал.
            return new Reply(null, QUESTION_MARK + "«" + q + "» — ответа нет (" + (dt / 1000)
                   + " с). Считай это ответом «нет»: необратимое не делай");
        }
        String a = answer.trim();
        answered.add(new String[]{q, a});
        return new Reply(a, QUESTION_MARK + "«" + q + "» → ответ: «" + a + "» (ждал " + (dt / 1000) + " с)");
    }

    /**
     * Задать контекст большой задачи для следующей подзадачи плана — или null,
     * чтобы убрать. Контекст попадает в промпт вместе с целью (см. {@link #goalFor}).
     *
     * <p>Живёт здесь, а не приклеивается к самой цели подзадачи, намеренно:
     * BrowserAgent по цели ищет задачу в памяти траекторий, и «скачай файл»
     * с приклеенной историей чужих шагов никогда бы там не нашлась.
     */
    public void setTaskContext(String context) {
        this.taskContext = context == null || context.isBlank() ? null : context.strip();
    }

    /** Текущий контекст плана (итоги прошлых подзадач) или null — нужен повтору из памяти. */
    public String taskContext() {
        return taskContext;
    }

    /**
     * Цель вместе с контекстом плана и ответами человека — именно её получает
     * модель на каждом шаге. Пока нет ни того, ни другого, возвращает цель как
     * есть, так что без плана и вопросов промпт не меняется ни на символ.
     */
    public String goalFor(String goal) {
        if (answered.isEmpty() && taskContext == null) return goal;
        StringBuilder sb = new StringBuilder(goal);
        if (taskContext != null) sb.append("\n\n").append(taskContext);
        if (answered.isEmpty()) return sb.toString();
        sb.append("\n\nУТОЧНЕНИЯ ОТ ПОЛЬЗОВАТЕЛЯ (он ответил на твои вопросы — считай это частью цели):\n");
        for (String[] qa : answered) {
            sb.append("  - на вопрос «").append(qa[0]).append("» ответил: «").append(qa[1]).append("»\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Лимит времени задачи, сдвинутый на время ожидания ответа.
     *
     * <p>На задачу даётся 3 минуты работы. Если человек думал над вопросом
     * полторы минуты, без сдвига агенту осталось бы полторы — задача упала бы
     * по времени из-за того, что агент вёл себя правильно и спросил.
     */
    public Instant extend(Instant deadline) {
        return deadline.plusMillis(waitedMs);
    }

    /** Сколько вопросов ушло человеку. */
    public int asked() {
        return asked;
    }

    /** На сколько из них человек ответил. */
    public int answeredCount() {
        return answered.size();
    }

    public long waitedMs() {
        return waitedMs;
    }

    // --- вспомогательное ---------------------------------------------------

    static boolean asksForSecret(String question) {
        String s = question.toLowerCase(Locale.ROOT);
        for (String w : SECRET_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /** Варианты для кнопок: без пустых, без повторов, не длиннее строки на экране. */
    static List<String> cleanOptions(List<String> options) {
        if (options == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String o : options) {
            if (o == null || o.isBlank()) continue;
            String t = o.trim();
            out.add(t.length() > MAX_OPTION_LENGTH ? t.substring(0, MAX_OPTION_LENGTH - 1) + "…" : t);
            if (out.size() == MAX_OPTIONS) break;
        }
        return List.copyOf(out);
    }

    /** «Отправить файл?» и «отправить  файл» — один и тот же вопрос. */
    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}«»—–]", " ").replaceAll("\\s+", " ").trim();
    }
}
