package com.jarvis.agent.plan;

import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.CancelSignal;
import com.jarvis.agent.vision.Clarifications;
import com.jarvis.agent.vision.ScreenAgent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Выполнение многоэтапной задачи по плану.
 *
 * <h2>Как устроено</h2>
 * <ol>
 *   <li><b>План.</b> {@link TaskPlanner} режет просьбу на подзадачи, каждая —
 *       в одной программе. Если данных не хватает (какой файл? кому?), он
 *       спрашивает человека ДО начала работы, а не на пятом шаге.</li>
 *   <li><b>Подтверждение.</b> Если в плане есть необратимое (отправить,
 *       удалить, купить), человек подтверждает весь план одним вопросом.
 *       Решение «можно/нельзя» принимает КОД по ответу «Да»/«Нет», а не модель.
 *       Ответ другими словами («только Лере») — это уточнение: план
 *       составляется заново и снова подтверждается.</li>
 *   <li><b>Выполнение.</b> Подзадачи идут по очереди. «Открыть программу» делает
 *       код, без модели; остальное — прежние {@code BrowserAgent}/{@code ScreenAgent}.
 *       Каждая подзадача получает итоги предыдущих: так имя скачанного файла
 *       доходит до шага, который его отправляет.</li>
 *   <li><b>Перепланирование.</b> Подзадача не удалась — планировщик получает,
 *       что сделано и почему сломалось, и строит новый хвост плана (не больше
 *       {@code plan.maxReplans} раз).</li>
 * </ol>
 * Телефон видит всё это чек-листом (сообщение agent_plan).
 *
 * <p>Объект одноразовый: на каждую задачу создаётся новый.
 */
public final class PlanRunner {

    /** Выполнить подзадачу в браузере или в программе (даёт CommandExecutor). */
    @FunctionalInterface
    public interface SubtaskExecutor {
        ResultData.AgentReport run(PlanStep step, Instant deadline);
    }

    /** Куда сообщать о каждом изменении плана — на телефон уходит agent_plan. */
    @FunctionalInterface
    public interface PlanListener {
        void onPlan(List<JarvisMessage.PlanItem> items, int revision);
    }

    /**
     * Ограничения плана.
     *
     * @param maxSubtasks      длиннее план обрезается — и защита от «рассылки всем»
     * @param maxReplans       сколько раз можно перестроить план после неудачи
     * @param maxClarifyRounds сколько раз подряд планировщик может спросить человека
     * @param subtaskTimeout   лимит времени одной подзадачи (как у обычной задачи)
     */
    public record Limits(int maxSubtasks, int maxReplans, int maxClarifyRounds, Duration subtaskTimeout) {}

    /** Чем кончилось. */
    public sealed interface Outcome {
        /**
         * План не нужен или не получился — выполнить задачу прежним способом,
         * одним циклом в указанном режиме. Так простые просьбы работают ровно
         * как раньше, а сбой планировщика не ломает задачу.
         */
        record Direct(AgentScope scope, int plannerCalls) implements Outcome {}

        /**
         * Задача выполнена (или остановлена) по плану.
         *
         * @param checksPassed проверок результата, подтвердивших итог подзадачи
         * @param checksFailed проверок, опровергнувших «готово» исполнителя, — сколько
         *                     ложных успехов поймано (цифра для диплома)
         */
        record Finished(ResultData.AgentReport report, int subtasks, int replans, int plannerCalls,
                        int checksPassed, int checksFailed, boolean planFromMemory, long baselineMs)
                implements Outcome {}
    }

    /** Пауза после открытия программы: окну нужно время, чтобы появиться. */
    private static final long OPEN_APP_SETTLE_MS = 1500;
    /** Итог подзадачи в чек-листе телефона — одной строкой. */
    private static final int NOTE_MAX = 90;

    /**
     * Признаки необратимого действия. Если планировщик сам не попросил
     * подтверждения, а в плане есть такая подзадача, код спросит сам: на
     * послушность модели в этом месте полагаться нельзя.
     */
    private static final Pattern IRREVERSIBLE = Pattern.compile(
            "(отправ|перешл|пересл|пошли|удали|удалить|сотри|стере|опублик|выложи|купи|закаж|оплат)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Слова, из которых целиком состоит «да». Подробнее — {@link #verdict}. */
    private static final Set<String> YES_WORDS = Set.of(
            "да", "ага", "угу", "конечно", "подтверждаю", "давай", "отправляй", "выполняй", "делай",
            "верно", "все", "так", "точно", "ок", "окей", "ok", "okay", "yes", "go");
    private static final Set<String> NO_WORDS = Set.of(
            "нет", "не", "надо", "нужно", "отмена", "отмени", "отменить", "стоп", "no", "cancel",
            "отправляй", "делай");

    private final TaskPlanner planner;
    private final SubtaskExecutor executor;
    private final ScreenAgent.AppOpener appOpener;
    private final PlanListener listener;
    private final Clarifications questions;
    private final CancelSignal cancel;
    private final Limits limits;
    private final StepVerifier verifier;

    private final List<String> trace = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private int revision = 0;
    private int plannerCalls = 0;
    private int replans = 0;
    private int stepsTaken = 0;
    private int checksPassed = 0;
    private int checksFailed = 0;
    /** Человек уже сказал «Да» на план — перестройка из-за неудачи не спрашивает снова. */
    private boolean planConfirmed = false;

    /**
     * Начало итога подзадачи, прерванной поправкой человека. По нему планировщик
     * отличает перестройку «человек передумал» от перестройки «шаг не удался»
     * (см. GeminiTaskPlanner.buildPrompt) — это разные просьбы к модели.
     */
    public static final String CORRECTION_MARK = "поправка человека: ";
    /**
     * Больше поправок за одну задачу не принимаем: каждая — это запрос к
     * планировщику и новое подтверждение. Четвёртая подряд поправка значит, что
     * задачу проще сформулировать заново.
     */
    static final int MAX_CORRECTIONS = 3;
    private int correctionsApplied = 0;

    /** Метка в трейсе: план взят из памяти, планировщик не вызывался. */
    public static final String PLAN_MEMORY_MARK = "план взят из памяти";
    private PlanMemory memory = PlanMemory.NONE;
    private Runnable onMemoryHit = () -> { };
    private boolean planFromMemory;
    private long baselineMs;
    /** Сколько раз планировщик спрашивал человека (подтверждение не в счёт) — такой план не запоминаем. */
    private int planningAsks;
    private String currentGoal;
    private long startNanos;
    /**
     * Причина остановить план вместо перестройки (не null — остановить). Ставится,
     * когда повторять шаг опасно, а человек не разрешил: см. {@link #verified}.
     */
    private String haltReason;

    /** Подзадача плана вместе с её состоянием в чек-листе. */
    private static final class Item {
        final PlanStep step;
        JarvisMessage.PlanItemStatus status = JarvisMessage.PlanItemStatus.PENDING;
        String note;
        /** Шаг не удался, и план перестроен: его заменили другие — продолжать его не нужно. */
        boolean replaced;

        Item(PlanStep step) {
            this.step = step;
        }
    }

    /** Без проверки результата подзадач — прежнее поведение. */
    public PlanRunner(TaskPlanner planner, SubtaskExecutor executor, ScreenAgent.AppOpener appOpener,
                      PlanListener listener, Clarifications questions, CancelSignal cancel, Limits limits) {
        this(planner, executor, appOpener, listener, questions, cancel, limits, StepVerifier.NONE);
    }

    public PlanRunner(TaskPlanner planner, SubtaskExecutor executor, ScreenAgent.AppOpener appOpener,
                      PlanListener listener, Clarifications questions, CancelSignal cancel, Limits limits,
                      StepVerifier verifier) {
        this.planner = planner;
        this.executor = executor;
        this.appOpener = appOpener;
        this.listener = listener;
        this.questions = questions;
        this.cancel = cancel;
        this.limits = limits;
        this.verifier = verifier;
    }

    /**
     * @param goal           просьба пользователя
     * @param requestedScope режим, который выбрал релей, — на случай, если план не понадобится
     * @param deadline       общий лимит времени всей задачи (время ответов человека не считается)
     */
    /** Как запустить доверенный скрипт по названию (даёт CommandExecutor через ScriptCatalog). */
    @FunctionalInterface
    public interface ScriptRunner {
        String run(String name) throws Exception;
    }

    private ScriptRunner scripts = name -> {
        throw new IllegalStateException("запуск скриптов здесь не подключён");
    };

    /** Подключить запуск доверенных скриптов. */
    public PlanRunner withScripts(ScriptRunner runner) {
        if (runner != null) this.scripts = runner;
        return this;
    }

    /**
     * Как выполнить подзадачу «git» или «правка кода» (даёт CommandExecutor через DevTools).
     *
     * @param following подзадачи, которые идут после этой, — чтобы спросить про коммит
     *                  и следующий за ним push одним вопросом
     * @param confirm   спросить человека «Да/Нет»; решение принимает код по ответу
     */
    @FunctionalInterface
    public interface DevRunner {
        String run(PlanStep step, List<PlanStep> following, Confirmer confirm) throws Exception;
    }

    /** Вопрос человеку от шага git/кода. */
    public interface Confirmer {
        /** «Да/Нет»: true — только явное согласие (см. {@link #verdict}). */
        boolean confirm(String question);

        /**
         * Выбор из вариантов («Да / Только коммит / Нет»): ответ — один из
         * вариантов как есть, а «да» своими словами — первый вариант. Всё
         * прочее, включая молчание, — null: решать за человека нельзя.
         */
        String choose(String question, List<String> options);
    }

    /**
     * Человек ответил «нет» на шаге, который без его согласия делать нельзя.
     * Такой отказ останавливает план целиком: перестроить план «в обход» отказа
     * значило бы искать другой способ сделать то, что человек запретил.
     */
    public static final class Declined extends RuntimeException {
        public Declined(String message) {
            super(message);
        }
    }

    private DevRunner dev = (step, following, confirm) -> {
        throw new IllegalStateException("git и правка кода здесь не подключены");
    };

    /** Подключить git и агента для кода. */
    public PlanRunner withDev(DevRunner runner) {
        if (runner != null) this.dev = runner;
        return this;
    }

    /** Готовый план без планировщика (не null — просьба сама называет, что делать). */
    private List<PlanStep> preset;

    /**
     * Выполнить готовый план, не спрашивая планировщика: «запусти скрипт сервер
     * диплома» прямо называет доверенный скрипт, и гадать тут нечего. Подтверждение
     * при этом спрашивается как обычно, а неудача не перестраивается — план
     * составлял не планировщик, и придумывать за человека другой способ нельзя.
     */
    public PlanRunner withPreset(List<PlanStep> steps) {
        this.preset = steps == null || steps.isEmpty() ? null : List.copyOf(steps);
        return this;
    }

    /**
     * Подключить память планов.
     *
     * @param onHit что сделать, когда план нашёлся в памяти (показать значок «из памяти»)
     */
    public PlanRunner withMemory(PlanMemory memory, Runnable onHit) {
        this.memory = memory == null ? PlanMemory.NONE : memory;
        this.onMemoryHit = onHit == null ? () -> { } : onHit;
        return this;
    }

    /** Оборванный план, который нужно продолжить (см. ResumeStore); null — обычный запуск. */
    private ResumeStore.Saved resume;
    /** Сохранять ли оборванный план для «продолжи» (в работе — да, в тестах — по желанию). */
    private boolean saveForResume;
    /** Итоги выполненных подзадач — для контекста следующих и для «продолжи». */
    private final List<StepResult> done = new ArrayList<>();

    /** Продолжить оборванный план: сделанное не повторяется, итоги передаются дальше. */
    public PlanRunner withResume(ResumeStore.Saved saved) {
        this.resume = saved;
        return this;
    }

    /** Сохранять ли оборванный план в ResumeStore, чтобы человек мог сказать «продолжи». */
    public PlanRunner withResumeSaving(boolean on) {
        this.saveForResume = on;
        return this;
    }

    public Outcome run(String goal, AgentScope requestedScope, Instant deadline) {
        currentGoal = goal;
        startNanos = System.nanoTime();
        // --- 0. готовый план или план из памяти -----------------------------------
        PlannerReply.Steps plan = null;
        if (resume != null) {
            // Продолжение спрашивается ВСЕГДА: прошло время, на экране могло
            // поменяться что угодно, и человек должен видеть, с какого шага пойдём.
            StringBuilder q = new StringBuilder("Продолжить задачу «").append(resume.goal()).append("»?");
            if (!resume.done().isEmpty()) {
                q.append("\nУже сделано: ");
                for (int i = 0; i < resume.done().size(); i++) {
                    q.append(i == 0 ? "" : "; ").append(resume.done().get(i).step().title());
                }
            }
            q.append("\nПродолжу с шага: ").append(resume.remaining().get(0).title());
            if (resume.reason() != null) q.append("\n(остановилось: ").append(resume.reason()).append(")");
            Clarifications.Reply r = questions.request(q.toString(), List.of("Да", "Нет"), cancel);
            trace.add("  " + r.note());
            if (verdict(r.answer()) != Verdict.YES) {
                ResumeStore.clear();
                return finish(false, "стоп: продолжать не стали");
            }
            planConfirmed = true;
            done.addAll(resume.done());
            for (StepResult d : resume.done()) {
                Item it = new Item(d.step());
                it.status = JarvisMessage.PlanItemStatus.DONE;
                it.note = shorten(d.summary());
                items.add(it);
            }
            plan = new PlannerReply.Steps(resume.remaining(), "");
            trace.add("продолжаю оборванный план: сделано " + resume.done().size() + ", осталось "
                      + resume.remaining().size());
        } else if (preset != null) {
            PlannerReply.Steps s = capped(new PlannerReply.Steps(preset, ""));
            String confirm = confirmFor(s);
            if (!confirm.isEmpty()) {
                Clarifications.Reply r = questions.request(confirm, List.of("Да", "Нет"), cancel);
                trace.add("  " + r.note());
                if (verdict(r.answer()) != Verdict.YES) return finish(false, "стоп: пользователь не подтвердил запуск");
                planConfirmed = true;
            }
            plan = s;
            trace.add("план без планировщика: просьба прямо называет, что сделать");
        }
        java.util.Optional<PlanMemory.Saved> saved = plan != null ? java.util.Optional.empty() : memory.find(goal);
        if (saved.isPresent() && !saved.get().steps().isEmpty()) {
            PlannerReply.Steps s = capped(new PlannerReply.Steps(saved.get().steps(), saved.get().confirm()));
            String confirm = confirmFor(s);
            Verdict v = Verdict.YES;
            if (!confirm.isEmpty()) {
                // Память ускоряет, но НЕ отменяет подтверждение необратимого:
                // «Да» спрашивается и у плана из памяти.
                Clarifications.Reply r = questions.request(confirm, List.of("Да", "Нет"), cancel);
                trace.add("  " + r.note());
                v = verdict(r.answer());
            }
            if (v == Verdict.NO) return finish(false, "стоп: пользователь не подтвердил план");
            if (v == Verdict.YES) {
                plan = s;
                planConfirmed = !confirm.isEmpty();
                planFromMemory = true;
                baselineMs = saved.get().baselineMs();
                trace.add(PLAN_MEMORY_MARK + " — без обращения к планировщику (в первый раз задача шла "
                          + (baselineMs / 1000) + " с)");
                onMemoryHit.run();
            }
            // OTHER («только Лере») — уточнение: план из памяти не подходит, планируем заново.
        }

        // --- 1. план, уточнения, подтверждение ----------------------------------
        for (int round = 0; plan == null; round++) {
            if (cancel.isCancelled()) return finish(false, "стоп: задачу отменил пользователь");
            // Поправка, пришедшая, пока план ещё не составлен, ничего прерывать не
            // должна: она уже в цели (goalFor), и план сразу учтёт её. Сигнал гасим.
            questions.takePendingCorrection();
            if (round > limits.maxClarifyRounds()) {
                return finish(false, "стоп: не удалось согласовать план за " + limits.maxClarifyRounds() + " уточнения");
            }

            PlannerReply reply = callPlanner(questions.goalFor(goal), List.of(), null, List.of());
            if (reply == null) {
                // Планировщик недоступен — задача всё равно должна выполниться.
                return new Outcome.Direct(requestedScope, plannerCalls);
            }
            if (reply instanceof PlannerReply.GiveUp g) {
                return finish(false, "стоп: планировщик отказался — " + g.reason());
            }
            if (reply instanceof PlannerReply.Ask a) {
                planningAsks++;
                Clarifications.Reply r = questions.request(a.question(), a.options(), cancel);
                trace.add("  " + r.note());
                if (r.answer() == null) return finish(false, "стоп: без ответа человека план не составить");
                continue; // ответ уже в questions.goalFor — планируем заново
            }

            PlannerReply.Steps s = capped((PlannerReply.Steps) reply);
            String confirm = confirmFor(s);
            if (isTrivial(s, confirm)) {
                // Одна подзадача в одной программе — это обычная задача, план
                // ей не нужен. Выполняем прежним путём, но в режиме, который
                // выбрал планировщик: он видел просьбу целиком.
                trace.clear();
                return new Outcome.Direct(scopeOf(s.steps().get(0)), plannerCalls);
            }
            if (!confirm.isEmpty()) {
                Clarifications.Reply r = questions.request(confirm, List.of("Да", "Нет"), cancel);
                trace.add("  " + r.note());
                switch (verdict(r.answer())) {
                    case NO -> { return finish(false, "стоп: пользователь не подтвердил план"); }
                    case OTHER -> { continue; } // «только Лере» — уточнение, план заново
                    case YES -> planConfirmed = true;
                }
            }
            plan = s;
        }

        // --- 2. выполнение ------------------------------------------------------
        plan.steps().forEach(step -> items.add(new Item(step)));
        revision = 1;
        trace.add("план (версия 1): " + describe(plan.steps()));
        publish();

        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            // Продолжение оборванного плана: сделанное уже в чек-листе, повторять нельзя.
            if (item.status == JarvisMessage.PlanItemStatus.DONE) continue;
            if (cancel.isCancelled()) return finish(false, "стоп: задачу отменил пользователь");
            if (Instant.now().isAfter(questions.extend(deadline).plusMillis(waitedMs))) {
                return finish(false, "стоп: превышен общий лимит времени задачи");
            }
            // Поправка пришла между подзадачами — эту ещё не начинали: план
            // перестраивается начиная с неё самой.
            String early = questions.takePendingCorrection();
            if (early != null) {
                Outcome stop = applyCorrection(early, item.step, null, i);
                if (stop != null) return stop;
                i--; // на месте i теперь первая подзадача нового хвоста
                continue;
            }

            item.status = JarvisMessage.PlanItemStatus.RUNNING;
            publish();
            trace.add("── подзадача " + (i + 1) + "/" + items.size() + " " + item.step.describe());

            questions.setTaskContext(contextFor(goal, done));
            StepResult result;
            try {
                List<PlanStep> following = items.subList(i + 1, items.size()).stream().map(it -> it.step).toList();
                result = execute(item.step, following, deadline);
            } finally {
                questions.setTaskContext(null);
            }
            // Поправка пришла во время подзадачи. Исполнитель её уже прервал (его
            // сигнал отмены учитывает поправку, см. CommandExecutor) — или успел
            // закончить сам. В обоих случаях хвост плана строится заново.
            String correction = cancel.isCancelled() ? null : questions.takePendingCorrection();
            if (correction != null) {
                if (result.completed()) {
                    // Успела закончиться — сделанное засчитываем, перестраиваем то, что после.
                    item.status = JarvisMessage.PlanItemStatus.DONE;
                    item.note = shorten(result.summary());
                    done.add(result);
                } else {
                    item.status = JarvisMessage.PlanItemStatus.SKIPPED;
                    item.note = "прервано поправкой";
                    item.replaced = true;
                }
                trace.add("  итог подзадачи: " + (result.completed() ? result.summary() : "прервана поправкой человека"));
                Outcome stop = applyCorrection(correction, item.step, result, i + 1);
                if (stop != null) return stop;
                continue;
            }
            // У ожидания проверка и есть его условие — второй раз смотреть (и платить) незачем.
            if (result.completed() && item.step.kind() != PlanStep.Kind.WAIT) result = verified(result);

            item.note = shorten(result.summary());
            item.status = result.completed() ? JarvisMessage.PlanItemStatus.DONE : JarvisMessage.PlanItemStatus.FAILED;
            publish();
            trace.add("  итог подзадачи: " + result.summary());

            if (result.completed()) {
                done.add(result);
                continue;
            }

            // --- 3. подзадача не удалась: перестроить хвост плана --------------
            if (cancel.isCancelled()) return finish(false, "стоп: задачу отменил пользователь");
            if (haltReason != null) return finish(false, "стоп: " + haltReason);
            // Готовый план человека не перестраиваем; продолжение — план планировщика, его можно.
            if (preset != null && resume == null) return finish(false, "стоп: " + result.summary());
            if (replans >= limits.maxReplans()) {
                return finish(false, "стоп: подзадача не удалась, а лимит перестроек плана ("
                                     + limits.maxReplans() + ") исчерпан");
            }
            List<PlanStep> remaining = items.subList(i + 1, items.size()).stream().map(it -> it.step).toList();
            PlannerReply reply = replan(goal, done, result, remaining);
            if (!(reply instanceof PlannerReply.Steps s)) {
                String why = reply instanceof PlannerReply.GiveUp g ? g.reason() : "планировщик недоступен";
                return finish(false, "стоп: план не перестроить — " + why);
            }
            s = capped(s);
            String confirm = confirmFor(s);
            if (!confirm.isEmpty() && planConfirmed) {
                // Перестройка после НЕУДАЧИ меняет способ, а не то, что и кому
                // делается: цель прежняя, и человек её уже подтвердил. На прогоне
                // 30.09 без этого правила за одну отправку файла прозвучало три
                // подтверждения — так человек быстро приучится жать «Да» не читая.
                trace.add("  подтверждение не спрашиваю повторно: план уже подтверждён, изменился только способ");
            } else if (!confirm.isEmpty()) {
                // Первого подтверждения не было (в исходном плане не было ничего
                // необратимого) — значит, это «Да» ещё никто не давал.
                Clarifications.Reply r = questions.request(confirm, List.of("Да", "Нет"), cancel);
                trace.add("  " + r.note());
                if (verdict(r.answer()) != Verdict.YES) {
                    return finish(false, "стоп: перестроенный план не подтверждён");
                }
                planConfirmed = true;
            }
            replans++;
            revision++;
            item.replaced = true;
            items.subList(i + 1, items.size()).clear();
            s.steps().forEach(step -> items.add(new Item(step)));
            trace.add("план перестроен (версия " + revision + "): " + describe(s.steps()));
            publish();
        }
        // Запоминаем только «чистый» успех: без перестроек, без опровергнутых
        // проверок и без уточнений у человека (ответы могли относиться только к
        // тому разу). Перестроенный план хранить нельзя — в нём неудачный шаг.
        // С поправками — тоже нет: исходный план человек по ходу изменил.
        if (preset == null && !planFromMemory && replans == 0 && checksFailed == 0 && planningAsks == 0
                && correctionsApplied == 0) {
            memory.save(goal, plan.steps(), plan.confirm(), (System.nanoTime() - startNanos) / 1_000_000);
        }
        return finish(true, "план выполнен");
    }

    // --- поправка человека по ходу задачи ---------------------------------------

    /**
     * Перестроить план с позиции {@code from} с учётом поправки человека.
     *
     * <p>Чем отличается от перестройки после неудачи:
     * <ul>
     *   <li>планировщик получает не «шаг не удался, сделай иначе», а «человек
     *       передумал» (см. {@link #CORRECTION_MARK});</li>
     *   <li>подтверждение спрашивается ВСЕГДА, даже если план уже подтверждали:
     *       поправка меняет именно то, что и кому делается («не Пете, а Васе»), а
     *       прежнее «Да» относилось к Пете. Правило «не переспрашивать после
     *       перестройки» придумано для смены способа, а не цели;</li>
     *   <li>свой лимит ({@link #MAX_CORRECTIONS}), не общий с перестройками после неудач.</li>
     * </ul>
     *
     * @param during подзадача, во время (или перед) которой пришла поправка
     * @param result её итог; null — подзадачу ещё не начинали
     * @param from   с какого места списка заменить план новым хвостом
     * @return null — план перестроен, можно продолжать; иначе — итог задачи (остановка)
     */
    private Outcome applyCorrection(String correction, PlanStep during, StepResult result, int from) {
        trace.add("  поправка человека: «" + correction + "»");
        // План составлял человек (скрипт, git) — за него другой план не придумываем.
        if (preset != null && resume == null) {
            return finish(false, "стоп: задача по готовому плану не перестраивается — пришлите её заново с поправкой");
        }
        if (correctionsApplied >= MAX_CORRECTIONS) {
            return finish(false, "стоп: поправок больше " + MAX_CORRECTIONS + " — сформулируйте задачу заново");
        }
        correctionsApplied++;

        String state = result == null ? "подзадачу ещё не начинали"
                : result.completed() ? "подзадача успела завершиться: " + result.summary()
                : "подзадача прервана на середине: " + result.summary();
        StepResult marker = new StepResult(during, false, CORRECTION_MARK + "«" + correction + "»; " + state);
        List<PlanStep> remaining = items.subList(from, items.size()).stream()
                .filter(it -> it.status == JarvisMessage.PlanItemStatus.PENDING)
                .map(it -> it.step)
                .toList();

        PlannerReply reply = replan(currentGoal, done, marker, remaining);
        if (reply instanceof PlannerReply.GiveUp g) {
            // Модель сказала, что после поправки делать нечего (например, «не надо
            // ничего отправлять»): это не провал задачи, а её законный конец.
            if (g.reason() != null && g.reason().strip().toUpperCase(Locale.ROOT).startsWith("НЕЧЕГО ДЕЛАТЬ")) {
                items.subList(from, items.size()).clear();
                publish();
                return finish(true, "поправка учтена: " + g.reason().strip());
            }
            return finish(false, "стоп: поправку не учесть — " + g.reason());
        }
        if (!(reply instanceof PlannerReply.Steps s)) {
            return finish(false, "стоп: поправку не учесть — планировщик недоступен");
        }
        s = capped(s);
        String confirm = confirmFor(s);
        if (!confirm.isEmpty()) {
            Clarifications.Reply r = questions.request(confirm, List.of("Да", "Нет"), cancel);
            trace.add("  " + r.note());
            if (verdict(r.answer()) != Verdict.YES) {
                return finish(false, "стоп: план после поправки не подтверждён");
            }
            planConfirmed = true;
        }
        revision++;
        items.subList(from, items.size()).clear();
        s.steps().forEach(step -> items.add(new Item(step)));
        trace.add("план перестроен по поправке (версия " + revision + "): " + describe(s.steps()));
        publish();
        return null;
    }

    // --- выполнение одной подзадачи --------------------------------------------

    private StepResult execute(PlanStep step, List<PlanStep> following, Instant deadline) {
        if (step.kind() == PlanStep.Kind.WAIT) {
            stepsTaken++;
            long t0 = System.currentTimeMillis();
            try {
                return waitFor(step);
            } finally {
                // Ожидание — не работа агента: в общий лимит задачи оно не входит,
                // как и время ответов человека. Иначе «дождись конца сборки» съедало
                // бы весь лимит, и на следующий шаг не оставалось бы времени.
                waitedMs += System.currentTimeMillis() - t0;
            }
        }
        if (step.kind() == PlanStep.Kind.GIT || step.kind() == PlanStep.Kind.CODE) {
            stepsTaken++;
            Confirmer confirm = new Confirmer() {
                @Override
                public boolean confirm(String question) {
                    Clarifications.Reply r = questions.request(question, List.of("Да", "Нет"), cancel);
                    trace.add("  " + r.note());
                    return verdict(r.answer()) == Verdict.YES;
                }

                @Override
                public String choose(String question, List<String> options) {
                    Clarifications.Reply r = questions.request(question, options, cancel);
                    trace.add("  " + r.note());
                    String a = r.answer() == null ? "" : r.answer().strip();
                    for (String o : options) if (o.equalsIgnoreCase(a)) return o;
                    return verdict(r.answer()) == Verdict.YES ? options.get(0) : null;
                }
            };
            try {
                return new StepResult(step, true, dev.run(step, following, confirm));
            } catch (Declined e) {
                haltReason = e.getMessage();
                return new StepResult(step, false, e.getMessage());
            } catch (Exception e) {
                return new StepResult(step, false, e.getMessage());
            }
        }
        if (step.kind() == PlanStep.Kind.RUN_SCRIPT) {
            stepsTaken++;
            try {
                return new StepResult(step, true, scripts.run(step.app()));
            } catch (Exception e) {
                return new StepResult(step, false, e.getMessage());
            }
        }
        if (step.kind() == PlanStep.Kind.OPEN_APP) {
            stepsTaken++;
            try {
                String opened = appOpener.open(step.app());
                sleep(OPEN_APP_SETTLE_MS);
                return new StepResult(step, true, "открыто: " + opened);
            } catch (Exception e) {
                return new StepResult(step, false, "не удалось открыть «" + step.app() + "»: " + e.getMessage());
            }
        }

        // Лимит подзадачи — свои 3 минуты, но не дальше общего лимита задачи.
        // Вычитаем уже прождённое: исполнитель сам прибавит к лимиту ВСЁ время
        // ожидания ответов (Clarifications.extend), включая то, что было до него.
        Instant own = Instant.now().plus(limits.subtaskTimeout());
        Instant subDeadline = (own.isBefore(deadline) ? own : deadline).minusMillis(questions.waitedMs());

        ResultData.AgentReport report = executor.run(step, subDeadline);
        stepsTaken += report.stepsTaken();
        report.trace().forEach(line -> trace.add("  " + line));
        return new StepResult(step, report.completed(), summaryOf(report));
    }

    /** Сколько ждать условия, если планировщик не сказал (минут). */
    private int waitDefaultMinutes = 10;
    /** Сколько всего уже прождано шагами WAIT — сдвигает общий лимит задачи. */
    private long waitedMs = 0;
    /** Сколько миллисекунд в «минуте» ожидания; не final — тесты сжимают время. */
    long waitUnitMs = 60_000;
    /** Как часто опрашивать процесс и файл; не final — тесты ставят меньше. */
    long waitPollMs = 3_000;
    /** Как часто смотреть на окно моделью — это платный запрос. */
    long waitVisualEveryMs = 15_000;
    /** Сколько раз за один шаг можно спросить модель: потолок цены ожидания. */
    static final int WAIT_MAX_MODEL_CHECKS = 20;
    /** Работает ли процесс с таким именем — подменяется в тестах. */
    java.util.function.Predicate<String> processRunning = PlanRunner::isProcessRunning;

    /** Сколько по умолчанию ждать условия шага WAIT (из plan.waitMinutes). */
    public PlanRunner withWaitDefault(int minutes) {
        this.waitDefaultMinutes = Math.max(1, Math.min(60, minutes));
        return this;
    }

    /**
     * Шаг «дождаться»: процесс закрылся, файл появился или на экране видно
     * нужное. Ничего не делает — только смотрит, поэтому безопасен, и его
     * результат — факт: «процесс gradle завершился через 3 мин 10 с».
     */
    private StepResult waitFor(PlanStep step) {
        if (step.app() == null && step.expectFile() == null && step.check() == null) {
            return new StepResult(step, false, "непонятно, чего ждать: нет ни процесса, ни файла, ни признака на экране");
        }
        int minutes = step.waitMinutes() == null ? waitDefaultMinutes : Math.max(1, Math.min(60, step.waitMinutes()));
        long start = System.currentTimeMillis();
        long until = start + minutes * waitUnitMs;
        long nextVisual = start;
        int modelChecks = 0;
        PlanStep probe = PlanStep.screen(step.goal()).withChecks(step.check(), step.expectFile());
        trace.add("  жду до " + minutes + " мин: " + step.goal());
        while (true) {
            if (cancel.isCancelled()) return new StepResult(step, false, "ожидание отменено");
            // Поправку саму не забираем — её разберёт цикл плана сразу после этого шага.
            if (questions.hasPendingCorrection()) return new StepResult(step, false, "ожидание прервано поправкой");
            long now = System.currentTimeMillis();
            String waited = " (ждал " + human(now - start) + ")";
            if (step.app() != null && !processRunning.test(step.app())) {
                return new StepResult(step, true, "процесс «" + step.app() + "» завершился" + waited);
            }
            boolean visualDue = step.check() != null && now >= nextVisual && modelChecks < WAIT_MAX_MODEL_CHECKS;
            boolean fileOnly = step.expectFile() != null && step.check() == null;
            if (step.app() == null && (fileOnly || visualDue)) {
                StepVerifier.Check c;
                try {
                    c = verifier.verify(probe, "");
                } catch (Exception e) {
                    c = null;
                }
                if (step.check() != null) {
                    modelChecks++;
                    nextVisual = now + waitVisualEveryMs;
                }
                if (c != null && Boolean.TRUE.equals(c.ok())) {
                    return new StepResult(step, true, "дождался: " + c.reason() + waited);
                }
            }
            if (now >= until) {
                return new StepResult(step, false, "не дождался за " + minutes + " мин: " + step.goal());
            }
            if (step.app() == null && step.expectFile() == null && modelChecks >= WAIT_MAX_MODEL_CHECKS) {
                return new StepResult(step, false, "не дождался: " + WAIT_MAX_MODEL_CHECKS
                                                   + " проверок экрана не увидели «" + step.check() + "»");
            }
            sleep(waitPollMs);
        }
    }

    private static String human(long ms) {
        long sec = ms / 1000;
        return sec < 60 ? sec + " с" : (sec / 60) + " мин " + (sec % 60) + " с";
    }

    /** Есть ли процесс, в имени программы которого есть это слово («gradle», «java.exe»). */
    static boolean isProcessRunning(String name) {
        String want = name.toLowerCase(Locale.ROOT).replace(".exe", "").strip();
        if (want.isEmpty()) return false;
        return ProcessHandle.allProcesses().anyMatch(ph -> ph.info().command()
                .map(c -> {
                    String file = java.nio.file.Paths.get(c).getFileName().toString().toLowerCase(Locale.ROOT);
                    return file.replace(".exe", "").contains(want);
                })
                .orElse(false));
    }

    /**
     * Проверить «готово» исполнителя (см. {@link StepVerifier}). Опровергнутый
     * итог превращается в неудачу подзадачи — дальше сработает обычное
     * перепланирование, и планировщик узнает, ЧТО именно не подтвердилось.
     * Неуверенная проверка (ok == null) итог не отменяет: лучше пропустить
     * ошибку, чем сломать выполненную задачу из-за того, что проверке было
     * плохо видно.
     */
    private StepResult verified(StepResult result) {
        StepVerifier.Check c;
        try {
            c = verifier.verify(result.step(), result.summary());
        } catch (Exception e) {
            c = new StepVerifier.Check(null, "проверка упала: " + e.getMessage());
        }
        if (c == null) return result;
        if (Boolean.TRUE.equals(c.ok())) {
            checksPassed++;
            trace.add("  проверка: подтверждено — " + c.reason());
            return result;
        }
        if (Boolean.FALSE.equals(c.ok())) {
            checksFailed++;
            trace.add("  проверка: НЕ подтверждено — " + c.reason());
            // Выученный путь этой подзадачи привёл к неверному результату —
            // повторять его из памяти нельзя.
            if (result.step().goal() != null) com.jarvis.agent.vision.TrajectoryCache.forgetGoal(result.step().goal());
            if (isIrreversible(result.step())) {
                return askBeforeRepeating(result, c.reason());
            }
            return new StepResult(result.step(), false,
                    "исполнитель сообщил «" + result.summary() + "», но проверка не подтвердила: " + c.reason());
        }
        trace.add("  проверка: не удалось понять — " + c.reason());
        return result;
    }

    private static boolean isIrreversible(PlanStep step) {
        if (step.kind() == PlanStep.Kind.GIT || step.kind() == PlanStep.Kind.CODE
            || step.kind() == PlanStep.Kind.WAIT) return false;
        return step.goal() != null && IRREVERSIBLE.matcher(step.goal()).find();
    }

    /**
     * Проверка сомневается в необратимом шаге (отправить, удалить). Повторить
     * его автоматически нельзя: проверка тоже ошибается, и повтор мог бы
     * отправить файл второй раз. На прогоне 30.09 ровно так и было: проверяющий
     * отверг правильную отправку из-за номера копии в имени файла. Решает
     * человек: «Повторить» — перестроить план; «Уже сделано» — считать шаг
     * выполненным; молчание — остановить план, ничего не повторяя.
     */
    private StepResult askBeforeRepeating(StepResult result, String reason) {
        Clarifications.Reply r = questions.request(
                "Не уверен, что шаг «" + result.step().title() + "» выполнен: " + reason
                        + ". Повторить его или он уже сделан?",
                List.of("Повторить", "Уже сделано"), cancel);
        trace.add("  " + r.note());
        String a = r.answer() == null ? "" : r.answer().toLowerCase(Locale.ROOT);
        // «не сделано» содержит «сделан» — но означает ровно обратное.
        boolean notDone = a.contains("не сделан") || a.contains("не выполн") || a.contains("не отправ");
        if (!notDone && (a.contains("уже") || a.contains("сделан")
                         || verdict(r.answer()) == Verdict.NO && r.answer() != null)) {
            checksFailed--; // человек опроверг проверку — это не пойманный ложный успех
            return new StepResult(result.step(), true, result.summary() + " (человек подтвердил: уже сделано)");
        }
        if (notDone || a.contains("повтор") || verdict(r.answer()) == Verdict.YES) {
            return new StepResult(result.step(), false,
                    "исполнитель сообщил «" + result.summary() + "», но проверка не подтвердила: " + reason
                    + ". Человек разрешил повторить — сначала посмотри, не сделано ли уже");
        }
        haltReason = "проверка сомневается в необратимом шаге «" + result.step().title()
                     + "», а человек не разрешил повтор — ничего не повторяю";
        return new StepResult(result.step(), false, haltReason);
    }

    /**
     * Контекст для исполнителя подзадачи. Главное в нём — «делай только свою
     * часть»: без этой строки исполнитель, увидев большую задачу, пытался бы
     * сделать её целиком — и, например, отправил бы файл уже на шаге скачивания.
     */
    static String contextFor(String goal, List<StepResult> done) {
        StringBuilder sb = new StringBuilder()
                .append("ЭТО ОДИН ШАГ БОЛЬШОЙ ЗАДАЧИ: «").append(goal).append("».\n")
                .append("Делай только свою ЦЕЛЬ (она выше) — остальное выполнят следующие шаги плана.\n")
                .append("Когда твоя цель выполнена, сразу верни done и в reason коротко напиши итог — что\n")
                .append("получилось (имя файла, кому отправлено): его прочитают следующие шаги.");
        if (!done.isEmpty()) {
            sb.append("\nУже сделано в этой задаче:");
            for (int i = 0; i < done.size(); i++) {
                StepResult r = done.get(i);
                sb.append("\n  ").append(i + 1).append(". ").append(r.step().title())
                  .append(" — ").append(r.summary());
            }
        }
        return sb.toString();
    }

    /**
     * Итог подзадачи — то, что модель написала в done (или причина остановки),
     * плюс факты, которые установил код: «скачан файл C:\...\report.pdf».
     *
     * <p>Факты важнее слов модели: она может написать «скачал отчёт», а на
     * диске файл называется «report (1).pdf». Следующей подзадаче нужен
     * именно точный путь — его она и вставит в сообщение.
     */
    static String summaryOf(ResultData.AgentReport report) {
        List<String> t = report.trace();
        String said = null;
        for (int i = t.size() - 1; i >= 0 && said == null; i--) {
            int at = t.get(i).indexOf("стоп:");
            if (at >= 0) {
                String s = t.get(i).substring(at + "стоп:".length()).strip();
                if (!s.isEmpty() && !"null".equals(s)) said = s;
            }
        }
        if (said == null) {
            said = report.completed() ? "готово" : "не завершено за " + report.stepsTaken() + " шаг(ов)";
        }
        List<String> facts = t.stream()
                .map(String::strip)
                .filter(line -> line.startsWith(com.jarvis.agent.DownloadWatcher.FACT_MARK))
                .map(line -> line.substring(com.jarvis.agent.DownloadWatcher.FACT_MARK.length()))
                .toList();
        return facts.isEmpty() ? said : said + "; " + String.join("; ", facts);
    }

    // --- планировщик -----------------------------------------------------------

    private PlannerReply callPlanner(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining) {
        plannerCalls++;
        return planner.plan(goal, done, failed, remaining);
    }

    /** Перестроить хвост плана; вопросы планировщика человеку — внутри. */
    private PlannerReply replan(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining) {
        for (int round = 0; round <= limits.maxClarifyRounds(); round++) {
            PlannerReply reply = callPlanner(questions.goalFor(goal), done, failed, remaining);
            if (!(reply instanceof PlannerReply.Ask a)) return reply;
            Clarifications.Reply r = questions.request(a.question(), a.options(), cancel);
            trace.add("  " + r.note());
            if (r.answer() == null) return new PlannerReply.GiveUp("нет ответа человека");
        }
        return new PlannerReply.GiveUp("слишком много уточнений подряд");
    }

    /** План длиннее лимита обрезается — и это видно в трейсе. */
    private PlannerReply.Steps capped(PlannerReply.Steps s) {
        // Шаги «для каждого из списка» лимит не обрезает: обрезанный список молча
        // пропустил бы людей. Сам список ограничен (RecipientLists.MAX).
        int max = limits.maxSubtasks() + s.listSteps();
        if (s.steps().size() <= max) return s;
        trace.add("  план из " + s.steps().size() + " подзадач обрезан до " + max);
        return new PlannerReply.Steps(s.steps().subList(0, max), s.confirm(), s.listSteps());
    }

    /** План из одной подзадачи в браузере или программе, без необратимого — обычная задача. */
    private static boolean isTrivial(PlannerReply.Steps s, String confirm) {
        PlanStep.Kind k = s.steps().size() == 1 ? s.steps().get(0).kind() : null;
        return (k == PlanStep.Kind.BROWSER || k == PlanStep.Kind.SCREEN) && confirm.isEmpty();
    }

    /**
     * Вопрос-подтверждение: от планировщика, а если он промолчал, но в плане есть
     * необратимое, — собранный кодом из самих подзадач.
     */
    static String confirmFor(PlannerReply.Steps s) {
        // План только из git и правок кода: подтверждение спросят сами шаги, с диффом.
        // Общий вопрос в начале был бы вторым «Да» на то же самое (планировщик
        // нередко пишет confirm и для push, хотя его просили этого не делать).
        if (s.steps().stream().allMatch(st -> st.kind() == PlanStep.Kind.GIT || st.kind() == PlanStep.Kind.CODE)) {
            return "";
        }
        // Запуск скрипта спрашивается ВСЕГДА — даже если планировщик написал
        // своё подтверждение, но забыл в нём скрипт: скрипт делает на ПК что
        // угодно, что в нём записано, и человек должен видеть, какой именно.
        List<String> scriptsInPlan = s.steps().stream()
                .filter(st -> st.kind() == PlanStep.Kind.RUN_SCRIPT)
                .filter(st -> !mentions(s.confirm(), st.app()))
                .map(PlanStep::title)
                .toList();
        if (!s.confirm().isEmpty()) {
            return scriptsInPlan.isEmpty() ? s.confirm()
                    : s.confirm() + " И " + String.join("; ", scriptsInPlan).toLowerCase(Locale.ROOT) + "?";
        }
        // git и правка кода в общий вопрос не входят: они спрашивают сами, в момент
        // коммита и push, и показывают настоящий дифф (см. DevTools).
        List<String> risky = s.steps().stream()
                .filter(st -> st.kind() == PlanStep.Kind.RUN_SCRIPT
                              || st.kind() != PlanStep.Kind.GIT && st.kind() != PlanStep.Kind.CODE
                                 && st.kind() != PlanStep.Kind.WAIT
                                 && st.goal() != null && IRREVERSIBLE.matcher(st.goal()).find())
                .map(PlanStep::title)
                .toList();
        return risky.isEmpty() ? "" : "Подтвердите: " + String.join("; ", risky) + "?";
    }

    /**
     * Назван ли скрипт в вопросе модели — с точностью до падежа: «скрипт
     * сервера диплома» — это тот же «сервер диплома». Сравниваем основы: первые
     * пять букв каждого слова названия.
     */
    static boolean mentions(String confirm, String scriptName) {
        if (confirm == null || confirm.isBlank() || scriptName == null) return false;
        String c = confirm.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String word : scriptName.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            String stem = word.length() > 5 ? word.substring(0, 5) : word;
            if (!c.contains(stem)) return false;
        }
        return true;
    }

    enum Verdict { YES, NO, OTHER }

    /**
     * Как понять ответ на подтверждение.
     *
     * <p>«Да» засчитывается, только если ответ ЦЕЛИКОМ состоит из слов согласия
     * («да», «да, отправляй», «ок»). «Да, но только Лере» — уже не согласие, а
     * уточнение: план будет составлен заново. «Нет», «не надо», «отмена» —
     * отказ. Молчание — тоже отказ. Спорные случаи уходят в уточнение, а не в
     * «да»: ошибиться в сторону лишнего вопроса дешевле, чем отправить не тому.
     */
    static Verdict verdict(String answer) {
        if (answer == null) return Verdict.NO;
        // ё -> е, чтобы «всё верно» и «все верно» были одним и тем же ответом.
        String[] words = answer.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}]+");
        List<String> w = java.util.Arrays.stream(words).filter(x -> !x.isEmpty()).toList();
        if (w.isEmpty()) return Verdict.OTHER;
        if (YES_WORDS.containsAll(w)) return Verdict.YES;
        if (NO_WORDS.containsAll(w)) return Verdict.NO;
        return Verdict.OTHER;
    }

    // --- отчёт и чек-лист -------------------------------------------------------

    private Outcome finish(boolean completed, String reason) {
        trace.add(reason);
        rememberForResume(completed, reason);
        // План из памяти не сработал — забываем, в следующий раз спланируем заново.
        // Отмена человеком — не повод: план тут ни при чём.
        if (!completed && planFromMemory && !cancel.isCancelled()) memory.forget(currentGoal);
        // Что не начато — уже не будет сделано: пусть телефон это видит.
        boolean changed = false;
        for (Item it : items) {
            if (it.status == JarvisMessage.PlanItemStatus.PENDING || it.status == JarvisMessage.PlanItemStatus.RUNNING) {
                it.status = JarvisMessage.PlanItemStatus.SKIPPED;
                changed = true;
            }
        }
        if (changed) publish();
        return new Outcome.Finished(new ResultData.AgentReport(completed, stepsTaken, List.copyOf(trace)),
                items.size(), replans, plannerCalls, checksPassed, checksFailed, planFromMemory, baselineMs);
    }

    /**
     * Оборвавшийся план — в ResumeStore, чтобы «Фауст, продолжи» пошёл дальше с
     * упавшего шага. Не сохраняем, если человек сам сказал «нет» (продолжать то,
     * что он запретил, нельзя) и если работа ещё не начиналась. Выполненный
     * план стирает сохранённый: продолжать нечего.
     */
    private void rememberForResume(boolean completed, String reason) {
        if (!saveForResume) return;
        if (completed) {
            ResumeStore.clear();
            return;
        }
        boolean declined = haltReason != null || reason.contains("не подтвердил") || reason.contains("не стали");
        List<PlanStep> remaining = items.stream()
                .filter(it -> it.status != JarvisMessage.PlanItemStatus.DONE && !it.replaced)
                .map(it -> it.step)
                .toList();
        boolean started = items.stream().anyMatch(it -> it.status != JarvisMessage.PlanItemStatus.PENDING);
        if (declined || !started || remaining.isEmpty()) return;
        ResumeStore.save(new ResumeStore.Saved(currentGoal, List.copyOf(done), remaining,
                reason.replaceFirst("^стоп:\\s*", ""), System.currentTimeMillis()));
    }

    private void publish() {
        if (items.isEmpty()) return;
        List<JarvisMessage.PlanItem> out = items.stream()
                .map(it -> new JarvisMessage.PlanItem(it.step.title(), it.status, it.note))
                .toList();
        listener.onPlan(out, revision);
    }

    private static String describe(List<PlanStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(i + 1).append(") ").append(steps.get(i).describe());
        }
        return sb.toString();
    }

    private static AgentScope scopeOf(PlanStep step) {
        return step.kind() == PlanStep.Kind.BROWSER ? AgentScope.BROWSER : AgentScope.SCREEN;
    }

    private static String shorten(String s) {
        if (s == null) return null;
        return s.length() <= NOTE_MAX ? s : s.substring(0, NOTE_MAX - 1) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
