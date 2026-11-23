package com.jarvis.agent.vision;

import com.jarvis.agent.HumanInput;
import com.jarvis.agent.protocol.JarvisMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Режим наставника без настоящего экрана: окно, модель, человек, показ и телефон подделаны.
 * «Человек» — отдельный поток: увидев подсказку, через мгновение кликает/печатает — тот же
 * порядок событий, что вживую (ввод до показа шага к шагу не относится и сбрасывается).
 */
class MentorSessionTest {

    private ScheduledExecutorService humanThread;

    @BeforeEach
    void setUp() {
        humanThread = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        humanThread.shutdownNow();
        // Удачный урок записывается в память траекторий (тестовый файл build/tmp/test-trajectories.json,
        // он живёт между запусками). Без уборки урок «лупа» оставался в файле и ломал чужие тесты
        // (UiaMemoryTest находил его вместо «забытой» цели). Забываем всё, что учили здесь.
        for (String goal : List.of("лупа", "включить лупу", "включить экранную лупу")) {
            TrajectoryCache.forgetGoal(goal);
        }
    }

    // --- подделки -----------------------------------------------------------------------------

    private static UiaElement el(int index, String type, String name, int x, int y) {
        return new UiaElement(index, type, name, x, y, 100, 30, false, true, "", false);
    }

    private static UiaSnapshot.Result window(String title, UiaElement... els) {
        return new UiaSnapshot.Result(title, List.of(els), 5);
    }

    /** Экран: всегда последний заданный снимок; заголовок окна можно менять из теста. */
    private static final class FakeScreen implements MentorSession.Screen {
        volatile UiaSnapshot.Result current;
        volatile String title;
        volatile String app = "systemsettings";

        FakeScreen(UiaSnapshot.Result w) {
            current = w;
            title = w.windowTitle();
        }

        @Override public UiaSnapshot.Result snapshot() { return current; }
        @Override public String foregroundApp() { return app; }
        @Override public String foregroundTitle() { return title; }
    }

    /** Модель по сценарию; запоминает цели и историю, с которыми её спросили. */
    private static final class ScriptBrain implements MentorSession.Brain {
        final Deque<AgentAction> script = new ArrayDeque<>();
        final List<String> goals = new CopyOnWriteArrayList<>();
        final List<List<String>> histories = new CopyOnWriteArrayList<>();

        ScriptBrain then(AgentAction a) {
            script.add(a);
            return this;
        }

        @Override
        public AgentAction next(String goal, String title, List<UiaElement> elements, List<String> history) {
            goals.add(goal);
            histories.add(List.copyOf(history));
            return script.isEmpty() ? new AgentAction("done", null, null, null, null, "сценарий кончился") : script.poll();
        }
    }

    private static AgentAction click(int index) {
        return new AgentAction("click", index, null, null, null, "показать");
    }

    private static AgentAction done() {
        return new AgentAction("done", null, null, null, null, "лупа включена", null, true);
    }

    /** Показ: записывает, что показано, и «человек» реагирует на каждый показ по сценарию. */
    private final class FakeView implements MentorSession.View {
        final List<String> shown = new CopyOnWriteArrayList<>();
        final List<String> wrongs = new CopyOnWriteArrayList<>();
        final AtomicBoolean ended = new AtomicBoolean();
        String doneCaption;
        MentorSession session;
        /** Что делает «человек», увидев показ номер N: список событий (или null — ничего). */
        BiFunction<Integer, UiaElement, List<Object>> human = (n, t) -> null;

        @Override public void start(String task) { }

        @Override
        public void point(UiaElement target, String caption, String say, int step, Integer total) {
            shown.add(caption);
            react(target);
        }

        @Override
        public void hint(String caption, String say, int step, Integer total) {
            shown.add(caption);
            react(null);
        }

        private void react(UiaElement target) {
            List<Object> actions = human.apply(shown.size(), target);
            if (actions == null) return;
            long delay = 60;
            for (Object a : actions) {
                long d = delay;
                humanThread.schedule(() -> {
                    if (a instanceof HumanInput.Event e) session.onHuman(e);
                    else if (a instanceof String word) session.onControl(word);
                    else if (a instanceof Runnable r) r.run();
                }, d, TimeUnit.MILLISECONDS);
                delay += 60;
            }
        }

        @Override public void wrong(String say) { wrongs.add(say); }
        @Override public void done(String caption, String say) { doneCaption = caption; }
        @Override public void end() { ended.set(true); }
        @Override public Rectangle panel() { return new Rectangle(800, 900, 300, 60); }
    }

    private static final class FakePhone implements MentorSession.Phone {
        final List<String> steps = new CopyOnWriteArrayList<>();
        final List<List<JarvisMessage.PlanItem>> plans = new CopyOnWriteArrayList<>();

        @Override public void step(int step, String text, List<String> replies) {
            assertEquals(MentorSession.REPLIES, replies, "на телефоне — кнопки «Повтори / Сделай сам / Стоп»");
            steps.add(text);
        }

        @Override public void plan(List<JarvisMessage.PlanItem> items) { plans.add(items); }
    }

    private static HumanInput.Click clickOn(UiaElement t) {
        return new HumanInput.Click(t.centerX(), t.centerY(), System.currentTimeMillis());
    }

    private static final HumanInput.Click MISS = new HumanInput.Click(5, 5, 0);

    private final UiaElement access = el(1, "listitem", "Специальные возможности", 100, 200);
    private final UiaElement magnifier = el(2, "listitem", "Экранная лупа", 100, 300);

    private MentorSession session(FakeScreen screen, MentorSession.Brain brain, FakeView view, FakePhone phone,
                                  List<String> doerLog) {
        MentorSession s = new MentorSession(screen, brain, view, phone,
                (a, t, w) -> {
                    doerLog.add(a.action() + ":" + (t == null ? "" : t.name()));
                    return "сделано";
                }, 1.0);
        s.settleMs = 0;
        s.pollEveryMs = 50;
        s.findPauseMs = 10;
        s.stepRemindMs = 5_000;
        s.stepGiveUpMs = 8_000;
        view.session = s;
        return s;
    }

    private static long deadline() {
        return System.currentTimeMillis() + 20_000;
    }

    // --- тесты ----------------------------------------------------------------------------------

    @Test
    void liveLesson_humanClicksEachStep_thenRememberedForNextTime() {
        TrajectoryCache.forgetGoal("включить экранную лупу");
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        ScriptBrain brain = new ScriptBrain().then(click(1)).then(click(2)).then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> List.of(clickOn(t));
        FakePhone phone = new FakePhone();
        List<String> doer = new ArrayList<>();

        MentorSession.Result r = session(screen, brain, view, phone, doer)
                .run("включить экранную лупу", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(2, r.byHuman());
        assertEquals(0, r.byAgent());
        assertEquals(3, r.llmCalls(), "2 шага + «готово»");
        assertTrue(doer.isEmpty(), "агент сам ничего не нажимал");
        assertEquals(List.of("Нажмите «Специальные возможности»", "Нажмите «Экранная лупа»"), view.shown);
        assertTrue(brain.goals.get(0).startsWith("НАУЧИТЬ человека: включить экранную лупу"));
        assertTrue(brain.histories.get(1).get(0).contains("человек сделал"), brain.histories.toString());
        assertEquals("Готово! Вы сделали это сами", view.doneCaption);
        assertTrue(view.ended.get());
        assertEquals("👉 Нажмите «Специальные возможности»", phone.steps.get(0));
        assertTrue(TrajectoryCache.findUia("включить экранную лупу").isPresent(), "урок записан — в следующий раз без ИИ");
        TrajectoryCache.forgetGoal("включить экранную лупу");
    }

    @Test
    void lessonFromMemory_noModelAtAll() {
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        List<TrajectoryCache.Step> steps = List.of(
                new TrajectoryCache.Step("click", "listitem", "uia", "Специальные возможности", -1, null, null, null, false),
                new TrajectoryCache.Step("click", "listitem", "uia", "Экранная лупа", -1, null, null, null, false));
        FakeView view = new FakeView();
        view.human = (n, t) -> List.of(clickOn(t));
        FakePhone phone = new FakePhone();

        MentorSession.Result r = session(screen, null, view, phone, new ArrayList<>())
                .run("включить лупу", steps, List.of("Открыть «Специальные возможности»", "Открыть «Экранная лупа»"),
                        null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(0, r.llmCalls(), "шаги из памяти — модель не нужна");
        assertEquals(2, r.byHuman());
        List<JarvisMessage.PlanItem> last = phone.plans.get(phone.plans.size() - 1);
        assertEquals("Открыть «Специальные возможности»", last.get(0).title(), "чек-лист — человеческими словами навыка");
        assertTrue(last.stream().allMatch(p -> p.status() == JarvisMessage.PlanItemStatus.DONE), last.toString());
    }

    @Test
    void missClick_saysNotHere_thenRightClickCounts() {
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        ScriptBrain brain = new ScriptBrain().then(click(1)).then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> n == 1 ? List.of(MISS, clickOn(t)) : null;

        MentorSession.Result r = session(screen, brain, view, new FakePhone(), new ArrayList<>())
                .run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(1, view.wrongs.size(), "один промах — «не сюда»");
        assertTrue(view.wrongs.get(0).startsWith("Не сюда"));
    }

    @Test
    void twoMisses_orOtherWindow_lookAgain_humanMayGoHisOwnWay() {
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        ScriptBrain brain = new ScriptBrain().then(click(1)).then(click(2)).then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> n == 1 ? List.of(MISS, MISS) : List.of(clickOn(t));

        MentorSession.Result r = session(screen, brain, view, new FakePhone(), new ArrayList<>())
                .run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertTrue(brain.histories.get(1).stream().anyMatch(h -> h.contains("другим путём")),
                "модель знает, что человек пошёл своим путём: " + brain.histories.get(1));

        // Окно сменилось без клика по подсветке — тоже взгляд заново.
        FakeScreen screen2 = new FakeScreen(window("Параметры", access, magnifier));
        ScriptBrain brain2 = new ScriptBrain().then(click(1)).then(done());
        FakeView view2 = new FakeView();
        view2.human = (n, t) -> n == 1 ? List.of((Runnable) () -> screen2.title = "Экранная лупа") : null;
        MentorSession.Result r2 = session(screen2, brain2, view2, new FakePhone(), new ArrayList<>())
                .run("лупа", null, null, null, null, () -> false, null, deadline());
        assertTrue(r2.success(), String.join("\n", r2.trace()));
        assertEquals(2, brain2.goals.size(), "после смены окна модель спросили заново");
    }

    @Test
    void doItForMe_agentDoesThisStep_andRepeatShowsAgain() {
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        ScriptBrain brain = new ScriptBrain().then(click(1)).then(click(2)).then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> switch (n) {
            case 1 -> List.of("повтори");              // показать ещё раз
            case 2 -> List.of("сделай сам");           // это — повтор того же шага
            default -> List.of(clickOn(t));
        };
        List<String> doer = new ArrayList<>();

        MentorSession.Result r = session(screen, brain, view, new FakePhone(), doer)
                .run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(List.of("click:Специальные возможности"), doer, "первый шаг сделал агент");
        assertEquals(1, r.byAgent());
        assertEquals(1, r.byHuman());
        assertEquals("Нажмите «Специальные возможности»", view.shown.get(1), "«повтори» — тот же шаг ещё раз");
    }

    @Test
    void phoneCorrection_commandOrRefinement() {
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        ScriptBrain brain = new ScriptBrain().then(click(1)).then(click(2)).then(done());
        Clarifications q = new Clarifications(UserAsker.NONE);
        FakeView view = new FakeView();
        view.human = (n, t) -> n == 1
                ? List.of((Runnable) () -> q.addCorrection("лучше через панель управления"))
                : List.of(clickOn(t));

        MentorSession.Result r = session(screen, brain, view, new FakePhone(), new ArrayList<>())
                .run("лупа", null, null, null, null, () -> false, q, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertTrue(brain.goals.get(1).contains("лучше через панель управления"),
                "уточнение с телефона ушло модели: " + brain.goals.get(1));
    }

    @Test
    void typeAndKeySteps_detectedFromFieldAndKeyboard() {
        UiaElement search = new UiaElement(3, "edit", "Найти параметр", 100, 100, 200, 30, false, true, "", true);
        FakeScreen screen = new FakeScreen(window("Параметры", search, access));
        ScriptBrain brain = new ScriptBrain()
                .then(new AgentAction("type", null, "лупа", null, null, "найти"))
                .then(new AgentAction("key", null, "enter", null, null, "открыть"))
                .then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> n == 1
                ? List.of(new HumanInput.Typing(0), (Runnable) () -> screen.current = window("Параметры",
                        new UiaElement(3, "edit", "Найти параметр", 100, 100, 200, 30, false, true, "лупа", true), access))
                : List.of(new HumanInput.Key("enter", 0));

        MentorSession.Result r = session(screen, brain, view, new FakePhone(), new ArrayList<>())
                .run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals("Введите в поле «Найти параметр»: «лупа»", view.shown.get(0));
        assertEquals("Нажмите Enter", view.shown.get(1));
    }

    @Test
    void panelClicks_areNotSteps_cancelAndGiveUp() {
        FakeScreen screen = new FakeScreen(window("Параметры", access, magnifier));
        // Клик по панели «Фауста» — не промах и не шаг; потом «стоп».
        FakeView view = new FakeView();
        view.human = (n, t) -> List.of(new HumanInput.Click(900, 920, 0), "стоп");
        MentorSession.Result r = session(screen, new ScriptBrain().then(click(1)), view, new FakePhone(), new ArrayList<>())
                .run("лупа", null, null, null, null, () -> false, null, deadline());
        assertFalse(r.success());
        assertEquals("остановлено", r.summary());
        assertTrue(view.wrongs.isEmpty(), "клик по панели — не «не сюда»");
        assertTrue(view.ended.get(), "оверлей убран в любом случае");

        // Человек ничего не делает — урок останавливается, а не висит вечно.
        FakeView idle = new FakeView();
        MentorSession s = session(screen, new ScriptBrain().then(click(1)), idle, new FakePhone(), new ArrayList<>());
        s.stepRemindMs = 100;
        s.stepGiveUpMs = 300;
        MentorSession.Result r2 = s.run("лупа", null, null, null, null, () -> false, null, deadline());
        assertFalse(r2.success());
        assertTrue(r2.summary().startsWith("человек не сделал шаг"), r2.summary());
        assertEquals(1, idle.wrongs.size(), "до остановки — одно напоминание");
    }

    @Test
    void memoryStepMissingOnScreen_withoutModel_honestFailure() {
        FakeScreen screen = new FakeScreen(window("Параметры", magnifier));
        List<TrajectoryCache.Step> steps = List.of(
                new TrajectoryCache.Step("click", "listitem", "uia", "Специальные возможности", -1, null, null, null, false));
        MentorSession.Result r = session(screen, null, new FakeView(), new FakePhone(), new ArrayList<>())
                .run("лупа", steps, null, null, null, () -> false, null, deadline());
        assertFalse(r.success());
        assertTrue(r.summary().contains("Специальные возможности"), r.summary());
    }

    @Test
    void words_captions_keys() {
        assertEquals(MentorSession.Wait.REPEAT, MentorSession.control("Повтори, пожалуйста"));
        assertEquals(MentorSession.Wait.DO_IT, MentorSession.control("сделай сам"));
        assertEquals(MentorSession.Wait.DO_IT, MentorSession.control("не получается"));
        assertEquals(MentorSession.Wait.NEXT, MentorSession.control("Готово!"));
        assertEquals(MentorSession.Wait.STOP, MentorSession.control("стоп"));
        assertNull(MentorSession.control("лучше через панель управления"));

        UiaElement pwd = new UiaElement(1, "edit", "Пароль", 0, 0, 100, 30, true, true, "", true);
        String[] c = MentorSession.captions(new AgentAction("type", null, "секрет123", null, null, ""), pwd);
        assertFalse(c[0].contains("секрет123") || c[1].contains("секрет123"), "пароль не показывается и не произносится");
        assertEquals("Ctrl+S", MentorSession.prettyKeys("ctrl+s"));
        assertTrue(MentorSession.appMatches("Параметры", "systemsettings", "Параметры"));
        assertTrue(MentorSession.appMatches("telegram", "Telegram.exe", ""));
    }

    // --- 23.11: доводка по живому уроку 19.11 ---------------------------------------------------

    @Test
    void keyNames_differentSpellings_sameCombo() {
        // Модель пишет как придётся, перехват клавиатуры присылает «win+plus».
        for (String s : List.of("Win + =", "windows+plus", "win++", "Meta+Plus", "WIN+ADD", "win+плюс")) {
            assertEquals("win+plus", MentorSession.normKeys(s), s);
        }
        assertEquals("ctrl+shift+t", MentorSession.normKeys("shift+ctrl+t"), "модификаторы по порядку");
        assertEquals("ctrl+s", MentorSession.normKeys("Control+S"));
        assertEquals("esc", MentorSession.normKeys("Escape"));
        assertEquals("plus", MentorSession.normKeys("+"));
        assertEquals("Win+«плюс»", MentorSession.prettyKeys("win+="));
    }

    @Test
    void keyStep_winPlus_countsWhenHumanPresses() {
        // Ровно урок 19.11: модель учит сочетанию (написав его по-своему), человек нажимает.
        FakeScreen screen = new FakeScreen(window("Параметры", access));
        ScriptBrain brain = new ScriptBrain()
                .then(new AgentAction("key", null, "Win + =", null, null, "лупа сочетанием"))
                .then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> List.of(new HumanInput.Key("win+plus", System.currentTimeMillis()));
        List<String> doer = new ArrayList<>();

        MentorSession.Result r = session(screen, brain, view, new FakePhone(), doer)
                .run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(1, r.byHuman(), "нажатие засчитано как сделанное человеком");
        assertEquals(List.of("Нажмите Win+«плюс»"), view.shown);
        assertTrue(doer.isEmpty());
    }

    @Test
    void doItFails_stepIsNotCounted_andWaitsForHuman() {
        // Агенту запрещены Win-сочетания: «сделай сам» на таком шаге не выйдет. Раньше шаг всё равно
        // считался сделанным и записывался в память.
        FakeScreen screen = new FakeScreen(window("Параметры", access));
        ScriptBrain brain = new ScriptBrain()
                .then(new AgentAction("key", null, "win+plus", null, null, "лупа"))
                .then(done());
        FakeView view = new FakeView();
        List<String> tried = new CopyOnWriteArrayList<>();
        MentorSession s = new MentorSession(screen, brain, view, new FakePhone(), (a, t, w) -> {
            tried.add(a.text());
            throw new IllegalArgumentException("сочетание «win+plus» агенту запрещено");
        }, 1.0);
        s.settleMs = 0;
        s.pollEveryMs = 50;
        s.stepRemindMs = 5_000;
        s.stepGiveUpMs = 8_000;
        view.session = s;
        // «Сделай сам», а когда не вышло — человек нажимает сам (позже: ожидание шага начинается заново).
        view.human = (n, t) -> List.of("Сделай сам", (Runnable) () -> humanThread.schedule(
                () -> s.onHuman(new HumanInput.Key("win+plus", System.currentTimeMillis())), 700, TimeUnit.MILLISECONDS));

        MentorSession.Result r = s.run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(List.of("win+plus"), tried, "агент попробовал");
        assertEquals(0, r.byAgent(), "но не смог — за человека ничего не сделано");
        assertEquals(1, r.byHuman());
        assertTrue(view.wrongs.stream().anyMatch(w -> w.contains("сделать не могу")), view.wrongs.toString());
        assertTrue(r.trace().stream().anyMatch(l -> l.contains("«сделай сам» не вышло")), String.join("\n", r.trace()));
    }

    private static AgentAction unavailable() {
        return new AgentAction("done", null, null, null, null, "LLM недоступна: Gemini HTTP 503");
    }

    @Test
    void modelOverloaded_onceIsRetried() {
        // Первая попытка урока 19.11 оборвалась на 503 от Gemini.
        FakeScreen screen = new FakeScreen(window("Параметры", access));
        ScriptBrain brain = new ScriptBrain().then(unavailable()).then(click(1)).then(done());
        FakeView view = new FakeView();
        view.human = (n, t) -> List.of(clickOn(t));
        FakePhone phone = new FakePhone();
        MentorSession s = session(screen, brain, view, phone, new ArrayList<>());
        s.modelRetryPauseMs = 10;

        MentorSession.Result r = s.run("лупа", null, null, null, null, () -> false, null, deadline());

        assertTrue(r.success(), String.join("\n", r.trace()));
        assertEquals(3, r.llmCalls(), "неудачная попытка тоже обращение: 503 + шаг + «готово»");
        assertTrue(phone.steps.stream().anyMatch(p -> p.contains("пробую ещё раз")), phone.steps.toString());
    }

    @Test
    void modelDown_endsHonestly_withoutPretendingDone() {
        FakeScreen screen = new FakeScreen(window("Параметры", access));
        ScriptBrain brain = new ScriptBrain().then(unavailable()).then(unavailable());
        FakeView view = new FakeView();
        MentorSession s = session(screen, brain, view, new FakePhone(), new ArrayList<>());
        s.modelRetryPauseMs = 10;

        MentorSession.Result r = s.run("лупа", null, null, null, null, () -> false, null, deadline());

        assertFalse(r.success());
        assertTrue(r.summary().startsWith("ИИ недоступен"), r.summary());
        assertEquals(2, r.llmCalls());
        assertNull(view.doneCaption, "«Готово!» не показываем");
    }
}
