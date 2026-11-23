package com.jarvis.agent.vision;

import com.jarvis.agent.HumanInput;
import com.jarvis.agent.SkillTemplate;
import com.jarvis.agent.protocol.JarvisMessage;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Режим наставника: «научи меня, а не сделай за меня».
 *
 * <h2>В чём отличие от обычной задачи</h2>
 * {@link ScreenAgent} сам выбирает элемент и сам кликает. Здесь выбор тот же (навык,
 * память или модель), но вместо клика агент ПОКАЗЫВАЕТ: стрелка и подсветка у элемента,
 * подпись «Нажмите «Специальные возможности»», голос ПК и сообщение на телефон. Кликает
 * человек. Агент по его вводу ({@link HumanInput}: только настоящий ввод, не Robot) и по
 * UI Automation видит, что шаг сделан, и показывает следующий.
 *
 * <h2>Откуда шаги</h2>
 * <ol>
 *   <li>Навык, выученный показом, или память удачного прогона — список шагов «тип элемента +
 *       подпись». Модель не нужна вовсе: 0 обращений к ИИ.</li>
 *   <li>Иначе — модель на каждом шаге ({@code Brain}): по списку элементов окна решает, что
 *       показать человеку дальше. После успешного урока путь записывается в память
 *       ({@link TrajectoryCache#recordUia}) — в следующий раз без ИИ.</li>
 * </ol>
 * Если записанный шаг не нашёлся на экране (окно выглядит иначе), урок продолжает модель.
 *
 * <h2>Как понять, что шаг сделан</h2>
 * <ul>
 *   <li>клик — человек нажал внутри подсвеченного прямоугольника (с запасом в несколько точек);
 *       нажал мимо — «не сюда», подсветка вздрагивает; после двух промахов или если окно
 *       сменилось — новый взгляд на экран (человек мог пойти своим путём — это не ошибка);</li>
 *   <li>ввод — в поле по UI Automation появился нужный текст, или нажаты Enter/Tab;</li>
 *   <li>клавиша — нажата та же клавиша или сочетание; прокрутка — колесо в нужную сторону;</li>
 *   <li>открыть программу — впереди окно этой программы.</li>
 * </ul>
 *
 * <h2>Управление</h2>
 * Кнопки панели на ПК и кнопки/голос телефона (поправки): «повтори» — показать ещё раз,
 * «сделай сам» — этот шаг делает агент (тем же кодом, что в обычной задаче, с теми же
 * защитами: пароли и платёжные данные не вводит никогда), «дальше» — считать шаг сделанным,
 * «стоп» — закончить. Любые другие слова — уточнение задачи для модели.
 *
 * <p>Класс не знает про окна и сеть: экран, модель, ввод, показ, телефон и «сделай сам»
 * передаются интерфейсами. Поэтому весь цикл проверяется тестами без настоящего экрана.
 */
public final class MentorSession {

    // --- что нужно сессии от внешнего мира -------------------------------------------------

    /** Модель: что показать дальше (тот же вызов, что у ScreenAgent в режиме UIA). */
    @FunctionalInterface
    public interface Brain {
        AgentAction next(String goal, String windowTitle, List<UiaElement> elements, List<String> history);
    }

    /** Что впереди на экране. */
    public interface Screen {
        UiaSnapshot.Result snapshot();

        /** Имя программы впереди (как WindowFocus.foregroundExe) — для шага «откройте программу». */
        String foregroundApp();

        /**
         * Заголовок окна впереди (как WindowFocus.foregroundTitle) — дёшево, без снимка UI
         * Automation: пока человек думает, проверять «не сменилось ли окно» приходится часто, а
         * полный снимок — это запуск отдельной программы на 0,3–0,6 с.
         */
        String foregroundTitle();
    }

    /** Показ на ПК: стрелка, подпись, голос. */
    public interface View {
        void start(String task);

        /** Показать элемент: стрелка, подсветка, подпись; голос читает {@code say}. */
        void point(UiaElement target, String caption, String say, int step, Integer total);

        /** Подсказка без элемента (клавиша, прокрутка, открыть программу). */
        void hint(String caption, String say, int step, Integer total);

        /**
         * Модель думает над следующим шагом — короткая подпись без голоса, чтобы не было тишины.
         * Это не шаг урока, поэтому отдельный метод (по умолчанию — ничего не показывать).
         */
        default void thinking(String caption, int step, Integer total) { }

        /** «Не сюда» — короткий знак на подсветке и голосом. */
        void wrong(String say);

        void done(String caption, String say);

        void end();

        /** Где панель с кнопками (логические точки): клики по ней — не шаг урока. Null — панели нет. */
        Rectangle panel();
    }

    /** Телефон: шаг сообщением с кнопками и чек-лист. */
    public interface Phone {
        void step(int step, String text, List<String> replies);

        void plan(List<JarvisMessage.PlanItem> items);
    }

    /** «Сделай сам»: выполнить шаг так же, как обычная задача (ScreenAgent::applyInWindow). */
    @FunctionalInterface
    public interface Doer {
        String doStep(AgentAction action, UiaElement target, UiaSnapshot.Result window) throws Exception;
    }

    /** Итог урока. */
    public record Result(boolean success, int steps, int byHuman, int byAgent, int llmCalls,
                         String summary, List<String> trace) {}

    /** Кнопки быстрых ответов на телефоне. */
    public static final List<String> REPLIES = List.of("Повтори", "Сделай сам", "Стоп");

    // --- настройки (значения по умолчанию — для живой работы; тесты ставят свои) ----------

    /** Сколько ждать человека на одном шаге до напоминания голосом. */
    long stepRemindMs = 120_000;
    /** Сколько ждать человека на одном шаге всего (дальше урок останавливается). */
    long stepGiveUpMs = 300_000;
    /** Пауза после сделанного шага, чтобы окно успело перерисоваться. */
    long settleMs = 600;
    /** Как часто смотреть на поле/окно (ввод, открытие программы). */
    long pollEveryMs = 700;
    /** Запас вокруг подсвеченного элемента, логических точек: попасть «впритык» — тоже попасть. */
    int hitMargin = 6;
    /** Сколько раз подряд искать записанный элемент, прежде чем отдать урок модели. */
    int findAttempts = 3;
    long findPauseMs = 500;
    /** Сколько раз спрашивать модель, если она не ответила (перегрузка, сеть), и пауза между попытками. */
    int modelAttempts = 2;
    long modelRetryPauseMs = 3_000;

    /**
     * Модель не ответила, а не решила «готово». GeminiVisionAgent при любой ошибке связи
     * возвращает done с причиной «LLM недоступна: …» — отличаем это от настоящего итога урока.
     */
    static boolean modelUnavailable(AgentAction a) {
        return a != null && "done".equalsIgnoreCase(a.action()) && !Boolean.TRUE.equals(a.success())
               && a.reason() != null && a.reason().startsWith("LLM недоступна");
    }

    private final Screen screen;
    private final Brain brain;          // null — только навык/память
    private final View view;
    private final Phone phone;
    private final Doer doer;
    private final double scale;         // физические пиксели ловушек -> логические
    private final BlockingQueue<HumanInput.Event> human = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> controls = new LinkedBlockingQueue<>();

    public MentorSession(Screen screen, Brain brain, View view, Phone phone, Doer doer, double scale) {
        this.screen = screen;
        this.brain = brain;
        this.view = view;
        this.phone = phone;
        this.doer = doer;
        this.scale = scale <= 0 ? 1.0 : scale;
    }

    /** Сюда HumanInput кладёт ввод человека (вызывается из потока ловушек — быстро). */
    public void onHuman(HumanInput.Event e) {
        human.offer(e);
    }

    /** Кнопка панели на ПК («Повтори», «Сделай сам», «Стоп»). */
    public void onControl(String word) {
        if (word != null) controls.offer(word);
    }

    // --- урок ---------------------------------------------------------------------------------

    /** Чем кончилось ожидание человека на шаге. */
    enum Wait { DONE, DO_IT, NEXT, REPEAT, REPLAN, STOP, GIVE_UP }

    /**
     * Провести урок.
     *
     * @param task      чему учим («включить экранную лупу»)
     * @param planned   шаги навыка или памяти (null — сразу модель)
     * @param shown     человеческие описания этих шагов (может быть null)
     * @param binding   переменные навыка (null — нет)
     * @param slot      значение слота памяти (null — нет)
     */
    public Result run(String task, List<TrajectoryCache.Step> planned, List<String> shown,
                      SkillTemplate.Binding binding, String slot,
                      CancelSignal cancel, Clarifications questions, long deadlineMs) {
        List<String> trace = new ArrayList<>();
        List<String> history = new ArrayList<>();
        List<TrajectoryCache.Step> learned = new ArrayList<>();
        List<JarvisMessage.PlanItem> plan = new ArrayList<>();
        boolean usedModel = false;
        int step = 0, byHuman = 0, byAgent = 0, llmCalls = 0, plannedAt = 0;
        long t0 = System.currentTimeMillis();
        String goal = "НАУЧИТЬ человека: " + task + ". Человек делает каждое действие сам, ты выбираешь, "
                      + "какое действие показать ему следующим. Пароли и платёжные данные он вводит сам.";
        Integer total = planned == null ? null : planned.size();
        if (planned != null) {
            for (int i = 0; i < planned.size(); i++) {
                String title = shown != null && i < shown.size() ? shown.get(i) : describe(planned.get(i));
                plan.add(new JarvisMessage.PlanItem(title, JarvisMessage.PlanItemStatus.PENDING, null));
            }
            phone.plan(List.copyOf(plan));
        }
        view.start(task);
        trace.add("урок: " + task + (planned == null ? " (шаги — от модели)" : " (шаги — из памяти/навыка: " + planned.size() + ")"));
        try {
            while (true) {
                if (cancel.isCancelled()) return finish(false, "остановлено", step, byHuman, byAgent, llmCalls, trace, null);
                if (System.currentTimeMillis() > deadlineMs) {
                    return finish(false, "время урока вышло", step, byHuman, byAgent, llmCalls, trace, null);
                }
                // Поправка с телефона, которая не команда, — уточнение задачи: дальше ведёт модель.
                String correction = questions == null ? null : questions.takePendingCorrection();
                if (correction != null) {
                    if (!handleWordBetweenSteps(correction) && brain != null && planned != null) {
                        trace.add("  уточнение «" + correction + "» — дальше урок ведёт модель");
                        planned = null;
                        total = null;
                    }
                }

                UiaSnapshot.Result w = screen.snapshot();
                AgentAction action;
                UiaElement target = null;
                boolean fromPlan = planned != null && plannedAt < planned.size();
                if (planned != null && plannedAt >= planned.size()) {
                    // Все записанные шаги пройдены — урок окончен.
                    learnIfNeeded(task, learned, usedModel, t0, llmCalls);
                    view.done("Готово! Вы сделали это сами", "Готово! Вы сделали это сами.");
                    return finish(true, "урок пройден", step, byHuman, byAgent, llmCalls, trace, plan);
                }
                if (fromPlan) {
                    TrajectoryCache.Step s = planned.get(plannedAt);
                    action = actionOf(s, binding, slot);
                    if ("click".equals(action.action())) {
                        target = findWithRetries(s, binding);
                        if (target == null) {
                            if (brain == null) {
                                return finish(false, "на экране нет «" + s.label() + "» — урок не может продолжиться",
                                        step, byHuman, byAgent, llmCalls, trace, plan);
                            }
                            trace.add("  записанного «" + s.label() + "» на экране нет — дальше урок ведёт модель");
                            planned = null;
                            total = null;
                            continue;
                        }
                    }
                } else {
                    if (brain == null) {
                        return finish(false, "для этого урока нет ни навыка, ни памяти, а модель недоступна",
                                step, byHuman, byAgent, llmCalls, trace, plan);
                    }
                    usedModel = true;
                    // Пока модель думает (живой урок 19.11: 1–16 с на шаг, при перегрузке до 30 с), на
                    // экране была тишина — человек не понимал, ждать ли. Короткая подпись без голоса.
                    view.thinking("Смотрю, что дальше…", step + 1, total);
                    action = null;
                    for (int attempt = 1; attempt <= modelAttempts; attempt++) {
                        llmCalls++;
                        action = brain.next(questions == null ? goal : questions.goalFor(goal), w.windowTitle(),
                                w.elements(), history);
                        if (!modelUnavailable(action) || attempt == modelAttempts || cancel.isCancelled()) break;
                        // Gemini бывает перегружен (503 на живом уроке 19.11) — это не конец урока:
                        // одна повторная попытка после паузы, человеку — что происходит.
                        trace.add("  ИИ не ответил (" + action.reason() + ") — пробую ещё раз");
                        phone.step(step + 1, "ИИ не ответил — пробую ещё раз…", REPLIES);
                        sleep(modelRetryPauseMs);
                    }
                    if (action == null) action = new AgentAction("done", null, null, null, null, "нет ответа");
                    if (modelUnavailable(action)) {
                        // Пройденное не теряем: если человек уже сделал часть шагов, они — в трейсе
                        // и журнале; в память урок не пишется (он не закончен).
                        view.wrong("ИИ сейчас недоступен. Попробуйте чуть позже.");
                        phone.step(step + 1, "ИИ сейчас недоступен — урок остановлен, попробуйте чуть позже", REPLIES);
                        return finish(false, "ИИ недоступен: " + action.reason(),
                                step, byHuman, byAgent, llmCalls, trace, plan);
                    }
                    String kind = kind(action);
                    if (kind.equals("done")) {
                        boolean ok = Boolean.TRUE.equals(action.success());
                        if (ok) {
                            learnIfNeeded(task, learned, usedModel, t0, llmCalls);
                            view.done("Готово! Вы сделали это сами", "Готово! Вы сделали это сами.");
                        }
                        return finish(ok, ok ? "урок пройден" : "не получилось: " + action.reason(),
                                step, byHuman, byAgent, llmCalls, trace, plan);
                    }
                    if (kind.equals("ask") || kind.equals("navigate") || kind.equals("find_file")
                        || kind.equals("paste_file") || kind.equals("extract_archive") || kind.equals("read")) {
                        // Эти действия у наставника не показываются: они про файлы и браузер агента.
                        history.add("действие «" + kind + "» в уроке не показывается — выбери действие в окне");
                        continue;
                    }
                    if (kind.equals("click")) {
                        target = ScreenAgent.findElementByIndex(w, action.index());
                        if (target == null) {
                            history.add("номера " + action.index() + " нет в списке — выбери из списка");
                            continue;
                        }
                    }
                    if (kind.equals("type")) target = focusedOrNull(w);
                }

                step++;
                String kind = kind(action);
                String[] text = captions(action, target);
                if (!fromPlan) {
                    plan.add(new JarvisMessage.PlanItem(text[0], JarvisMessage.PlanItemStatus.RUNNING, null));
                } else {
                    plan.set(plannedAt, new JarvisMessage.PlanItem(plan.get(plannedAt).title(),
                            JarvisMessage.PlanItemStatus.RUNNING, null));
                }
                phone.plan(List.copyOf(plan));
                present(action, target, text, step, total);
                phone.step(step, "👉 " + text[0], REPLIES);
                trace.add("шаг " + step + ": " + text[0]);

                // «Сделай сам» может не выйти: агенту запрещены Win-сочетания и поля паролей, элемент мог
                // исчезнуть. Раньше такой шаг всё равно считался сделанным и даже записывался в память.
                // Теперь: сказать человеку, что этот шаг — только его, и ждать дальше тот же шаг.
                Wait outcome;
                String doNote = null;
                while (true) {
                    outcome = waitForHuman(action, target, w, text, step, total, cancel, questions, deadlineMs);
                    if (outcome != Wait.DO_IT) break;
                    try {
                        doNote = doer.doStep(action, target, w);
                        break;
                    } catch (Exception e) {
                        String why = e.getMessage() == null || e.getMessage().isBlank() ? "не вышло" : e.getMessage();
                        trace.add("  «сделай сам» не вышло (" + why + ") — шаг ждёт человека");
                        view.wrong("Этот шаг я сделать не могу. Сделайте его сами, пожалуйста.");
                        phone.step(step, "Этот шаг Фауст сделать не может (" + why + "). Сделайте сами: " + text[0], REPLIES);
                    }
                }
                int planIndex = fromPlan ? plannedAt : plan.size() - 1;
                switch (outcome) {
                    case STOP -> {
                        return finish(false, "остановлено", step, byHuman, byAgent, llmCalls, trace, plan);
                    }
                    case GIVE_UP -> {
                        return finish(false, "человек не сделал шаг «" + text[0] + "» — урок остановлен",
                                step, byHuman, byAgent, llmCalls, trace, plan);
                    }
                    case REPLAN -> {
                        trace.add("  человек пошёл другим путём — смотрю на экран заново");
                        history.add("человек не сделал «" + text[0] + "», а пошёл другим путём — посмотри на окно заново");
                        plan.set(planIndex, new JarvisMessage.PlanItem(plan.get(planIndex).title(),
                                JarvisMessage.PlanItemStatus.SKIPPED, "другим путём"));
                        if (planned != null) {
                            if (brain == null) {
                                return finish(false, "урок разошёлся с экраном", step, byHuman, byAgent, llmCalls, trace, plan);
                            }
                            planned = null;
                            total = null;
                        }
                        continue;
                    }
                    case DO_IT -> {
                        byAgent++;
                        trace.add("  сделал за человека: " + (doNote == null ? text[0] : doNote));
                        history.add("агент по просьбе человека сделал сам: " + text[0]);
                    }
                    case DONE, NEXT -> {
                        byHuman++;
                        trace.add(outcome == Wait.DONE ? "  человек сделал сам" : "  человек сказал «дальше»");
                        history.add("человек сделал: " + text[0]);
                    }
                }
                plan.set(planIndex, new JarvisMessage.PlanItem(plan.get(planIndex).title(),
                        JarvisMessage.PlanItemStatus.DONE, outcome == Wait.DO_IT ? "сделал Фауст" : null));
                phone.plan(List.copyOf(plan));
                TrajectoryCache.Step rec = ScreenAgent.stepFrom(action, target, task,
                        questions == null ? new Clarifications(UserAsker.NONE) : questions);
                if (rec != null) learned.add(rec);
                if (fromPlan) plannedAt++;
                sleep(settleMs);
            }
        } finally {
            view.end();
        }
    }

    // --- ожидание человека --------------------------------------------------------------------

    private Wait waitForHuman(AgentAction action, UiaElement target, UiaSnapshot.Result before,
                              String[] text, int step, Integer total,
                              CancelSignal cancel, Clarifications questions, long deadlineMs) {
        human.clear();             // ввод ДО показа шага к этому шагу не относится
        String kind = kind(action);
        long shownAt = System.currentTimeMillis();
        long lastPoll = shownAt;
        boolean reminded = false;
        boolean typed = false;
        int misses = 0;
        String windowBefore = screen.foregroundTitle();
        while (true) {
            if (cancel.isCancelled()) return Wait.STOP;
            long now = System.currentTimeMillis();
            if (now > deadlineMs) return Wait.GIVE_UP;
            if (!reminded && now - shownAt > stepRemindMs) {
                reminded = true;
                view.wrong("Если не получается, скажите «сделай сам» — я покажу, как это делается.");
            }
            if (now - shownAt > stepGiveUpMs) return Wait.GIVE_UP;

            // Команды: кнопки панели ПК и слова с телефона (поправки).
            String word = controls.poll();
            if (word == null && questions != null && questions.hasPendingCorrection()) {
                String c = questions.takePendingCorrection();
                Wait w = control(c);
                if (w == null && c != null) {
                    // Не команда — уточнение задачи: модель учтёт его (goalFor), а шаг смотрим заново.
                    // Без модели уточнять некому — шаг ждёт дальше как был.
                    if (brain != null) return Wait.REPLAN;
                    continue;
                }
                word = c;
            }
            if (word != null) {
                Wait w = control(word);
                if (w == Wait.REPEAT) {            // «повтори» — показать ещё раз, ждать дальше
                    present(action, target, text, step, total);
                } else if (w != null) {
                    return w;
                }
            }

            HumanInput.Event e;
            try {
                e = human.poll(Math.min(200, pollEveryMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Wait.STOP;
            }
            if (e != null) {
                switch (e) {
                    case HumanInput.Click c -> {
                        int x = (int) Math.round(c.x() / scale), y = (int) Math.round(c.y() / scale);
                        Rectangle panel = view.panel();
                        if (panel != null && panel.contains(x, y)) break;      // кнопки «Фауста» — не шаг
                        if (target != null && hits(target, x, y)) {
                            if (kind.equals("click")) return Wait.DONE;
                            break;                                             // клик в поле перед вводом — правильно
                        }
                        if (kind.equals("click")) {
                            misses++;
                            if (misses >= 2) return Wait.REPLAN;
                            view.wrong("Не сюда. Нажмите на подсвеченное.");
                        }
                    }
                    case HumanInput.Key k -> {
                        String need = normKeys(action.text());
                        if (kind.equals("key") && normKeys(k.combo()).equals(need)) return Wait.DONE;
                        if (kind.equals("type") && typed && (k.combo().equals("enter") || k.combo().equals("tab"))) {
                            return Wait.DONE;
                        }
                    }
                    case HumanInput.Typing t -> typed = true;
                    case HumanInput.Wheel wh -> {
                        if (kind.equals("scroll")) {
                            int amount = action.amount() == null ? 3 : action.amount();
                            // amount > 0 — вниз; у колеса вниз — отрицательное значение.
                            if ((amount > 0 && wh.delta() < 0) || (amount < 0 && wh.delta() > 0)) return Wait.DONE;
                        }
                    }
                }
            }

            // Раз в pollEveryMs — посмотреть на окно: ввод, открытие программы, смена окна.
            if (System.currentTimeMillis() - lastPoll >= pollEveryMs) {
                lastPoll = System.currentTimeMillis();
                if (kind.equals("open_app")) {
                    if (appMatches(action.text(), screen.foregroundApp(), screen.foregroundTitle())) return Wait.DONE;
                    continue;
                }
                if (kind.equals("type") && typed) {
                    UiaSnapshot.Result now2 = screen.snapshot();
                    if (fieldHas(now2, target, action.text())) return Wait.DONE;
                    continue;
                }
                if (kind.equals("click") || kind.equals("key") || kind.equals("scroll")) {
                    String titleNow = screen.foregroundTitle();
                    // Окно сменилось без нашего шага — человек пошёл своим путём.
                    if (windowBefore != null && !windowBefore.isBlank() && titleNow != null && !titleNow.isBlank()
                        && !titleNow.equals(windowBefore)) {
                        return Wait.REPLAN;
                    }
                }
            }
        }
    }

    /** Слово человека -> команда; null — это не команда (уточнение задачи). */
    static Wait control(String word) {
        if (word == null) return null;
        String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^а-яa-z ]", " ").strip()
                .replaceAll("\\s+", " ");
        if (w.matches("(стоп|хватит|отмена|закончи|прекрати|выход).*")) return Wait.STOP;
        if (w.matches("(сделай сам|сделай за меня|сделай ты|давай ты|сам сделай|не получается|не могу).*")) return Wait.DO_IT;
        if (w.matches("(повтори|еще раз|покажи еще|не понял|не поняла|не вижу|где это|где)( .*)?")) return Wait.REPEAT;
        if (w.matches("(дальше|готово|сделал|сделала|следующий|пропусти|далее)( .*)?")) return Wait.NEXT;
        return null;
    }

    /** Слово между шагами — команда для следующего шага (его обработает ожидание); true — это была команда. */
    private boolean handleWordBetweenSteps(String word) {
        if (control(word) == null) return false;
        controls.offer(word);
        return true;
    }

    // --- что показать -----------------------------------------------------------------------

    private void present(AgentAction a, UiaElement target, String[] text, int step, Integer total) {
        if (target != null) view.point(target, text[0], text[1], step, total);
        else view.hint(text[0], text[1], step, total);
    }

    /**
     * Подпись на экране и фраза для голоса. [0] — подпись (коротко), [1] — что сказать.
     * Пароли: текст не показывается и не произносится — человек вводит его сам.
     */
    static String[] captions(AgentAction a, UiaElement target) {
        String kind = kind(a);
        String name = target == null || target.name() == null || target.name().isBlank() ? null : target.name();
        return switch (kind) {
            case "click" -> {
                String what = name == null ? "на подсвеченное" : "«" + name + "»";
                yield new String[]{"Нажмите " + what, "Нажмите " + (name == null ? "на подсвеченное." : name + ".")};
            }
            case "type" -> {
                if (target != null && target.isPassword()) {
                    yield new String[]{"Введите пароль сами — Фауст его не видит", "Введите пароль сами."};
                }
                String t = a.text() == null ? "" : a.text();
                String field = name == null ? "" : " в поле «" + name + "»";
                yield new String[]{"Введите" + field + ": «" + t + "»", "Введите" + (name == null ? "" : " в поле " + name)
                        + ": " + t + "."};
            }
            case "key" -> {
                String k = prettyKeys(a.text());
                yield new String[]{"Нажмите " + k, "Нажмите " + k + "."};
            }
            case "scroll" -> {
                boolean down = a.amount() == null || a.amount() > 0;
                yield new String[]{"Прокрутите колёсиком мыши " + (down ? "вниз" : "вверх"),
                        "Прокрутите колёсиком мыши " + (down ? "вниз." : "вверх.")};
            }
            case "open_app" -> {
                String app = a.text() == null ? "программу" : a.text();
                yield new String[]{"Откройте «" + app + "»: нажмите «Пуск» и начните печатать «" + app.toLowerCase(Locale.ROOT) + "»",
                        "Откройте " + app + ". Нажмите Пуск и начните печатать название."};
            }
            default -> new String[]{"Следующий шаг", "Следующий шаг."};
        };
    }

    // --- вспомогательное ------------------------------------------------------------------------

    private static String kind(AgentAction a) {
        return a == null || a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT);
    }

    /** Шаг памяти/навыка -> действие (как его понимает исполнитель). */
    private static AgentAction actionOf(TrajectoryCache.Step s, SkillTemplate.Binding binding, String slot) {
        String text = s.text();
        if ("type".equals(s.action())) {
            if (binding != null && text != null) text = binding.typed(text);
            else if (s.textFromGoal() && slot != null) text = slot;
        }
        return new AgentAction(s.action(), null, text, s.amount(), s.url(), null);
    }

    private UiaElement findWithRetries(TrajectoryCache.Step s, SkillTemplate.Binding binding) {
        for (int i = 0; i < findAttempts; i++) {
            UiaSnapshot.Result w = screen.snapshot();
            UiaElement e = binding != null && binding.hasSlot(s.label())
                    ? ScreenAgent.matchByValue(w, s, binding) : ScreenAgent.matchElement(w, s);
            if (e != null) return e;
            sleep(findPauseMs);
        }
        return null;
    }

    private static UiaElement focusedOrNull(UiaSnapshot.Result w) {
        return w.elements().stream().filter(UiaElement::hasFocus).findFirst().orElse(null);
    }

    /** Попал ли клик (логические точки) в элемент с запасом. */
    boolean hits(UiaElement t, int x, int y) {
        return x >= t.x() - hitMargin && x <= t.x() + t.width() + hitMargin
               && y >= t.y() - hitMargin && y <= t.y() + t.height() + hitMargin;
    }

    /** В поле (тот же тип и подпись, иначе поле в фокусе) есть нужный текст. */
    static boolean fieldHas(UiaSnapshot.Result w, UiaElement field, String text) {
        if (text == null || text.isBlank()) return true;
        String need = text.strip().toLowerCase(Locale.ROOT);
        for (UiaElement e : w.elements()) {
            boolean same = field != null && e.controlType() != null && e.controlType().equals(field.controlType())
                           && e.name() != null && e.name().equals(field.name());
            if ((same || e.hasFocus()) && e.value() != null && e.value().toLowerCase(Locale.ROOT).contains(need)) return true;
        }
        return false;
    }

    /** Впереди нужная программа: имя exe или заголовок окна содержат её название (без регистра и «.exe»). */
    static boolean appMatches(String want, String exe, String title) {
        if (want == null || want.isBlank()) return false;
        String w = want.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
        String e = exe == null ? "" : exe.toLowerCase(Locale.ROOT).replace(".exe", "");
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return (!e.isBlank() && (e.contains(w) || w.contains(e))) || t.contains(w);
    }

    /** Порядок модификаторов — тот же, в каком их пишет HumanInput: «ctrl+alt+shift+win+клавиша». */
    private static final List<String> MODIFIER_ORDER = List.of("ctrl", "alt", "shift", "win");

    /**
     * Разные написания одной клавиши. Модель пишет как придётся — «Win+=», «windows + plus»,
     * «Meta+Plus», «win++», — а перехват клавиатуры присылает одно: «win+plus». Без приведения
     * к одному виду правильно нажатое сочетание не засчитывалось бы.
     */
    private static final java.util.Map<String, String> KEY_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("control", "ctrl"), java.util.Map.entry("ctl", "ctrl"),
            java.util.Map.entry("windows", "win"), java.util.Map.entry("meta", "win"),
            java.util.Map.entry("super", "win"), java.util.Map.entry("cmd", "win"),
            java.util.Map.entry("lwin", "win"), java.util.Map.entry("rwin", "win"),
            java.util.Map.entry("return", "enter"), java.util.Map.entry("escape", "esc"),
            java.util.Map.entry("del", "delete"), java.util.Map.entry("pgup", "pageup"),
            java.util.Map.entry("pgdn", "pagedown"), java.util.Map.entry("page_up", "pageup"),
            java.util.Map.entry("page_down", "pagedown"),
            java.util.Map.entry("=", "plus"), java.util.Map.entry("add", "plus"),
            java.util.Map.entry("oem_plus", "plus"), java.util.Map.entry("плюс", "plus"),
            java.util.Map.entry("-", "minus"), java.util.Map.entry("subtract", "minus"),
            java.util.Map.entry("oem_minus", "minus"), java.util.Map.entry("минус", "minus"),
            java.util.Map.entry("пробел", "space"), java.util.Map.entry("spacebar", "space"));

    /** Сочетание к одному виду: нижний регистр, синонимы, модификаторы по порядку. «Win + =» -> «win+plus». */
    static String normKeys(String keys) {
        if (keys == null) return "";
        String k = keys.toLowerCase(Locale.ROOT).replace(" ", "");
        if (k.isEmpty()) return "";
        // Сам «+» как клавиша: «win++» или просто «+».
        if (k.equals("+")) k = "plus";
        else if (k.endsWith("++")) k = k.substring(0, k.length() - 2) + "+plus";
        List<String> mods = new ArrayList<>();
        String main = null;
        for (String part : k.split("\\+")) {
            if (part.isEmpty()) continue;
            String p = KEY_ALIASES.getOrDefault(part, part);
            if (MODIFIER_ORDER.contains(p)) {
                if (!mods.contains(p)) mods.add(p);
            } else {
                main = p;
            }
        }
        mods.sort(java.util.Comparator.comparingInt(MODIFIER_ORDER::indexOf));
        StringBuilder sb = new StringBuilder();
        for (String m : mods) sb.append(m).append('+');
        if (main != null) sb.append(main);
        else if (sb.length() > 0) sb.setLength(sb.length() - 1);   // одни модификаторы
        return sb.toString();
    }

    /** «ctrl+s» -> «Ctrl+S», «enter» -> «Enter», «win+plus» -> «Win+«плюс»» (человеку понятнее слово). */
    static String prettyKeys(String keys) {
        if (keys == null || keys.isBlank()) return "клавишу";
        StringBuilder sb = new StringBuilder();
        for (String p : normKeys(keys).split("\\+")) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(switch (p) {
                case "plus" -> "«плюс»";
                case "minus" -> "«минус»";
                case "space" -> "«пробел»";
                default -> p.length() == 1 ? p.toUpperCase(Locale.ROOT)
                        : Character.toUpperCase(p.charAt(0)) + p.substring(1);
            });
        }
        return sb.toString();
    }

    /** Шаг памяти -> строка чек-листа, если человеческого описания нет. */
    private static String describe(TrajectoryCache.Step s) {
        return switch (s.action() == null ? "" : s.action()) {
            case "click" -> "Нажать «" + s.label() + "»";
            case "type" -> "Ввести «" + (s.text() == null ? "" : s.text()) + "»";
            case "key" -> "Нажать " + prettyKeys(s.text());
            case "open_app" -> "Открыть «" + s.text() + "»";
            case "scroll" -> "Прокрутить";
            default -> s.action();
        };
    }

    /** Успешный урок с моделью — в память: в следующий раз тот же урок без ИИ. */
    private static void learnIfNeeded(String task, List<TrajectoryCache.Step> learned, boolean usedModel,
                                      long t0, int llmCalls) {
        if (!usedModel || learned.isEmpty()) return;
        try {
            TrajectoryCache.recordUia(task, List.copyOf(learned), System.currentTimeMillis() - t0, llmCalls);
        } catch (RuntimeException e) {
            System.err.println("урок не записан в память: " + e.getMessage());
        }
    }

    private Result finish(boolean ok, String summary, int steps, int byHuman, int byAgent, int llmCalls,
                          List<String> trace, List<JarvisMessage.PlanItem> plan) {
        trace.add("итог урока: " + summary + "; шагов " + steps + ", сам человек — " + byHuman + ", за него — "
                  + byAgent + ", обращений к ИИ — " + llmCalls);
        if (plan != null) {
            // Незавершённые пункты — пропущены (или не понадобились), чтобы чек-лист на телефоне не «висел».
            for (int i = 0; i < plan.size(); i++) {
                JarvisMessage.PlanItem p = plan.get(i);
                if (p.status() == JarvisMessage.PlanItemStatus.PENDING || p.status() == JarvisMessage.PlanItemStatus.RUNNING) {
                    plan.set(i, new JarvisMessage.PlanItem(p.title(), JarvisMessage.PlanItemStatus.SKIPPED, null));
                }
            }
            phone.plan(List.copyOf(plan));
        }
        return new Result(ok, steps, byHuman, byAgent, llmCalls, summary, trace);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
