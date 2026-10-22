package com.jarvis.agent.plan;

import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.protocol.JarvisMessage.PlanItemStatus;
import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.CancelSignal;
import com.jarvis.agent.vision.Clarifications;
import com.jarvis.agent.vision.UserAsker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Логика выполнения плана без сети и без экрана: планировщик, исполнитель
 * подзадач и «телефон» — поддельные. Проверяется ровно то, за что отвечает
 * код, а не модель: подтверждение, передача итогов между шагами,
 * перепланирование, остановка.
 */
class PlanRunnerTest {

    @BeforeAll
    static void settings() {
        // Правила вопросов берутся из agent.properties; в тестах — фиксированные,
        // чтобы результат не зависел от настроек на конкретном ПК.
        System.setProperty("faust.ask.enabled", "true");
        System.setProperty("faust.ask.maxPerTask", "10");
    }

    // --- поддельные участники ---------------------------------------------------

    /** Планировщик, который отвечает заранее заданными ответами и запоминает, что у него спросили. */
    static final class FakePlanner implements TaskPlanner {
        final List<PlannerReply> replies;
        final List<String> goals = new ArrayList<>();
        final List<StepResult> failures = new ArrayList<>();

        FakePlanner(PlannerReply... replies) {
            this.replies = new ArrayList<>(List.of(replies));
        }

        @Override
        public PlannerReply plan(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining) {
            goals.add(goal);
            if (failed != null) failures.add(failed);
            return replies.isEmpty() ? null : replies.remove(0);
        }
    }

    /** «Телефон»: отвечает по очереди и запоминает вопросы. */
    static final class FakePhone implements UserAsker {
        final List<String> answers;
        final List<String> asked = new ArrayList<>();

        FakePhone(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public String ask(String question, List<String> options, CancelSignal cancel) {
            asked.add(question);
            return answers.isEmpty() ? null : answers.remove(0);
        }
    }

    private final List<String> executed = new ArrayList<>();
    /** Что видела модель подзадачи: цель вместе с контекстом плана. */
    private final List<String> seenGoals = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();
    private final List<List<JarvisMessage.PlanItem>> published = new ArrayList<>();

    private Clarifications questions;

    /** Исполнитель: «успешно» с итогом, если цель не содержит слова «сломай». */
    private PlanRunner runner(TaskPlanner planner, UserAsker phone, int maxReplans) {
        questions = new Clarifications(phone);
        PlanRunner.SubtaskExecutor exec = (step, deadline) -> {
            executed.add(step.goal());
            seenGoals.add(questions.goalFor(step.goal()));
            boolean ok = !step.goal().contains("сломай");
            String reason = ok ? "итог: " + step.goal() : "кнопка не найдена";
            return new ResultData.AgentReport(ok, 3, List.of("шаг 1: click(1)", "  стоп: " + reason));
        };
        return new PlanRunner(planner, exec, name -> { opened.add(name); return name; },
                (items, rev) -> published.add(items), questions, CancelSignal.NONE,
                new PlanRunner.Limits(8, maxReplans, 3, Duration.ofMinutes(3)));
    }

    private static Instant later() {
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    private static PlannerReply.Steps sendFilePlan() {
        return new PlannerReply.Steps(List.of(
                PlanStep.browser("скачать отчёт с сайта"),
                PlanStep.openApp("WhatsApp"),
                PlanStep.screen("отправить скачанный файл Лере"),
                PlanStep.screen("отправить скачанный файл Маше")),
                "Отправить отчёт Лере и Маше в WhatsApp?");
    }

    // --- план не нужен ----------------------------------------------------------

    @Test
    void singleStep_goesTheOldWay_inPlannersScope() {
        FakePlanner planner = new FakePlanner(new PlannerReply.Steps(List.of(PlanStep.browser("найди видео")), ""));

        PlanRunner.Outcome out = runner(planner, new FakePhone(), 2).run("найди видео про котов", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Direct d = assertInstanceOf(PlanRunner.Outcome.Direct.class, out);
        assertEquals(AgentScope.BROWSER, d.scope());
        assertTrue(published.isEmpty(), "для обычной задачи чек-лист не нужен");
    }

    @Test
    void plannerUnavailable_taskStillRuns() {
        PlanRunner.Outcome out = runner(new FakePlanner(/* null */), new FakePhone(), 2)
                .run("что-то сделать", AgentScope.SCREEN, later());

        assertEquals(AgentScope.SCREEN, assertInstanceOf(PlanRunner.Outcome.Direct.class, out).scope());
    }

    // --- подтверждение ----------------------------------------------------------

    @Test
    void confirmedPlan_runsAllSteps_andPassesResultsForward() {
        FakePhone phone = new FakePhone("Да");
        PlanRunner.Outcome out = runner(new FakePlanner(sendFilePlan()), phone, 2)
                .run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class, out);
        assertTrue(f.report().completed(), String.join("\n", f.report().trace()));
        assertEquals(List.of("Отправить отчёт Лере и Маше в WhatsApp?"), phone.asked);
        assertEquals(List.of("WhatsApp"), opened, "программу открывает код, не модель");
        assertEquals(3, executed.size());
        assertEquals(3 * 3 + 1, f.report().stepsTaken(), "шаги подзадач + открытие программы");

        // Итог скачивания дошёл до шага отправки — так имя файла переходит между программами.
        String sendGoal = seenGoals.get(1);
        assertTrue(sendGoal.contains("ЭТО ОДИН ШАГ БОЛЬШОЙ ЗАДАЧИ"), sendGoal);
        assertTrue(sendGoal.contains("итог: скачать отчёт с сайта"), sendGoal);
        // И подтверждение видно исполнителю — он не будет спрашивать второй раз.
        assertTrue(sendGoal.contains("ответил: «Да»"), sendGoal);

        List<JarvisMessage.PlanItem> last = published.get(published.size() - 1);
        assertTrue(last.stream().allMatch(i -> i.status() == PlanItemStatus.DONE), last.toString());
    }

    @Test
    void refusedPlan_doesNothing() {
        PlanRunner.Outcome out = runner(new FakePlanner(sendFilePlan()), new FakePhone("Нет, не надо"), 2)
                .run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class, out);
        assertFalse(f.report().completed());
        assertTrue(executed.isEmpty(), "после «нет» ничего не выполняется");
        assertTrue(opened.isEmpty());
    }

    @Test
    void silence_onConfirm_meansNo() {
        PlanRunner.Outcome out = runner(new FakePlanner(sendFilePlan()), new FakePhone(/* молчит */), 2)
                .run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later());

        assertFalse(assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report().completed());
        assertTrue(executed.isEmpty());
    }

    @Test
    void answerWithCorrection_replansAndAsksAgain() {
        PlannerReply.Steps onlyLera = new PlannerReply.Steps(List.of(
                PlanStep.openApp("WhatsApp"), PlanStep.screen("отправить отчёт Лере")),
                "Отправить отчёт только Лере?");
        FakePlanner planner = new FakePlanner(sendFilePlan(), onlyLera);
        FakePhone phone = new FakePhone("только Лере", "да");

        PlanRunner.Outcome out = runner(planner, phone, 2)
                .run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later());

        assertTrue(assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report().completed());
        assertEquals(2, planner.goals.size());
        assertTrue(planner.goals.get(1).contains("только Лере"), "уточнение ушло планировщику");
        assertEquals(List.of("отправить отчёт Лере"), executed);
    }

    @Test
    void irreversibleStep_withoutPlannersConfirm_isConfirmedByCode() {
        PlannerReply.Steps noConfirm = new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"), PlanStep.screen("отправить Лере сообщение «буду в 7»")), "");
        FakePhone phone = new FakePhone("Нет");

        runner(new FakePlanner(noConfirm), phone, 2).run("напиши Лере", AgentScope.SCREEN, later());

        assertEquals(1, phone.asked.size(), "код сам спросил подтверждение");
        assertTrue(phone.asked.get(0).contains("отправить Лере"), phone.asked.get(0));
        assertTrue(executed.isEmpty());
    }

    // --- вопросы планировщика ---------------------------------------------------

    @Test
    void plannerAsks_beforeDoingAnything() {
        FakePlanner planner = new FakePlanner(
                new PlannerReply.Ask("Какой файл отправить?", List.of()),
                new PlannerReply.Steps(List.of(PlanStep.openApp("Telegram"), PlanStep.screen("прикрепить report.pdf")), ""));

        runner(planner, new FakePhone("report.pdf"), 2).run("отправь Маше файл", AgentScope.SCREEN, later());

        assertTrue(planner.goals.get(1).contains("report.pdf"));
        assertEquals(List.of("прикрепить report.pdf"), executed);
    }

    // --- неудача подзадачи --------------------------------------------------------

    @Test
    void failedStep_replansTheTail() {
        PlannerReply.Steps first = new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"), PlanStep.screen("сломай: нажми скрепку"), PlanStep.screen("старый хвост")), "");
        PlannerReply.Steps tail = new PlannerReply.Steps(List.of(PlanStep.screen("прикрепи через меню")), "");
        FakePlanner planner = new FakePlanner(first, tail);

        PlanRunner.Outcome out = runner(planner, new FakePhone(), 2).run("задача", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class, out);
        assertTrue(f.report().completed(), String.join("\n", f.report().trace()));
        assertEquals(1, f.replans());
        assertEquals("кнопка не найдена", planner.failures.get(0).summary(), "планировщик знает, почему сломалось");
        assertEquals(List.of("сломай: нажми скрепку", "прикрепи через меню"), executed, "старый хвост выброшен");

        List<JarvisMessage.PlanItem> last = published.get(published.size() - 1);
        assertEquals(PlanItemStatus.FAILED, last.get(1).status(), "неудача остаётся видна в чек-листе");
        assertEquals("прикрепи через меню", last.get(2).title());
    }

    @Test
    void failedStep_withoutReplans_stopsAndSkipsTheRest() {
        PlannerReply.Steps plan = new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"), PlanStep.screen("сломай"), PlanStep.screen("не дойдёт")), "");

        PlanRunner.Outcome out = runner(new FakePlanner(plan), new FakePhone(), 0).run("задача", AgentScope.SCREEN, later());

        assertFalse(assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report().completed());
        List<JarvisMessage.PlanItem> last = published.get(published.size() - 1);
        assertEquals(PlanItemStatus.SKIPPED, last.get(2).status());
        assertFalse(executed.contains("не дойдёт"));
    }

    // --- проверка результата -------------------------------------------------------

    @Test
    void falseDone_isCaughtByVerifier_andPlanIsRebuilt() {
        // Исполнитель говорит «готово», проверка на диске — нет: так вели себя
        // три прогона 30.09. Теперь это неудача подзадачи и перестройка плана.
        PlanStep save = PlanStep.screen("сохрани курс.txt").withChecks(null, "Рабочий стол\\курс.txt");
        PlannerReply.Steps first = new PlannerReply.Steps(List.of(PlanStep.openApp("Блокнот"), save), "");
        PlannerReply.Steps retry = new PlannerReply.Steps(List.of(PlanStep.screen("сохрани через меню Файл")), "");
        FakePlanner planner = new FakePlanner(first, retry);
        StepVerifier refuteSave = (step, summary) -> step.goal() != null && step.goal().contains("курс.txt")
                ? new StepVerifier.Check(false, "файл не появился") : null;

        questions = new Clarifications(new FakePhone());
        PlanRunner.SubtaskExecutor exec = (step, deadline) -> {
            executed.add(step.goal());
            return new ResultData.AgentReport(true, 2, List.of("  стоп: сохранено"));
        };
        PlanRunner r = new PlanRunner(planner, exec, name -> name, (items, rev) -> published.add(items),
                questions, CancelSignal.NONE, new PlanRunner.Limits(8, 2, 3, Duration.ofMinutes(3)), refuteSave);

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                r.run("запиши курс", AgentScope.SCREEN, later()));

        assertEquals(1, f.checksFailed());
        assertEquals(1, f.replans());
        assertTrue(planner.failures.get(0).summary().contains("файл не появился"),
                "планировщик узнал, что именно не подтвердилось");
        assertEquals(List.of("сохрани курс.txt", "сохрани через меню Файл"), executed);
    }

    /** Прогон с исполнителем, у которого первая попытка «сломай…» проваливается, и заданной проверкой. */
    private PlanRunner.Outcome.Finished runWith(FakePlanner planner, FakePhone phone, StepVerifier v) {
        questions = new Clarifications(phone);
        PlanRunner.SubtaskExecutor exec = (step, deadline) -> {
            executed.add(step.goal());
            boolean ok = !step.goal().contains("сломай");
            return new ResultData.AgentReport(ok, 2, List.of("  стоп: " + (ok ? "отправлено" : "кнопка не найдена")));
        };
        PlanRunner r = new PlanRunner(planner, exec, name -> name, (items, rev) -> published.add(items),
                questions, CancelSignal.NONE, new PlanRunner.Limits(8, 2, 3, Duration.ofMinutes(3)), v);
        return assertInstanceOf(PlanRunner.Outcome.Finished.class, r.run("задача", AgentScope.SCREEN, later()));
    }

    @Test
    void confirmedPlan_isNotReconfirmed_afterFailureReplan() {
        PlannerReply.Steps first = new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"), PlanStep.screen("сломай: отправь файл в Избранное")),
                "Отправить файл в Избранное?");
        PlannerReply.Steps tail = new PlannerReply.Steps(List.of(PlanStep.screen("отправь файл в Избранное через меню")),
                "Отправить файл в Избранное через меню?");
        FakePhone phone = new FakePhone("Да");

        PlanRunner.Outcome.Finished f = runWith(new FakePlanner(first, tail), phone, StepVerifier.NONE);

        assertTrue(f.report().completed());
        assertEquals(1, phone.asked.size(), "одно «Да» на весь план, без повторных подтверждений");
    }

    @Test
    void doubtfulSend_isNotRepeatedBlindly_humanSaysAlreadyDone() {
        PlannerReply.Steps plan = new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"), PlanStep.screen("отправь файл в Избранное").withChecks("видно файл", null)),
                "Отправить файл в Избранное?");
        StepVerifier doubts = (step, sum) -> step.check() == null ? null : new StepVerifier.Check(false, "имя файла другое");
        FakePlanner planner = new FakePlanner(plan);
        FakePhone phone = new FakePhone("Да", "Уже сделано");

        PlanRunner.Outcome.Finished f = runWith(planner, phone, doubts);

        assertTrue(f.report().completed(), "человек сказал «уже сделано» — план выполнен");
        assertEquals(1, executed.size(), "отправка не повторялась");
        assertEquals(1, planner.goals.size(), "перестройки не было");
        assertEquals(0, f.checksFailed(), "человек опроверг проверку — ложным успехом это не считается");
    }

    @Test
    void doubtfulSend_withoutAnswer_stopsWithoutRepeating() {
        PlannerReply.Steps plan = new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"), PlanStep.screen("отправь файл в Избранное").withChecks("видно файл", null)),
                "Отправить файл в Избранное?");
        StepVerifier doubts = (step, sum) -> step.check() == null ? null : new StepVerifier.Check(false, "не видно");

        PlanRunner.Outcome.Finished f = runWith(new FakePlanner(plan), new FakePhone("Да" /* дальше молчит */), doubts);

        assertFalse(f.report().completed());
        assertEquals(1, executed.size(), "без разрешения человека необратимое не повторяется");
    }

    // --- память планов -------------------------------------------------------------

    /** Память планов в оперативной памяти — настоящий файл тестам не нужен. */
    static final class FakeMemory implements PlanMemory {
        PlanMemory.Saved saved;
        String forgotten;

        @Override public java.util.Optional<Saved> find(String goal) { return java.util.Optional.ofNullable(saved); }
        @Override public void save(String goal, List<PlanStep> steps, String confirm, long ms) { saved = new Saved(steps, confirm, ms); }
        @Override public void forget(String goal) { forgotten = goal; saved = null; }
    }

    @Test
    void cleanSuccess_isRemembered_andSecondRunSkipsPlannerButStillConfirms() {
        FakeMemory memory = new FakeMemory();
        FakePhone phone = new FakePhone("Да", "Да");
        FakePlanner planner = new FakePlanner(sendFilePlan());

        // Первый раз: планировщик, подтверждение, запоминание.
        PlanRunner first = runner(planner, phone, 2).withMemory(memory, null);
        assertTrue(assertInstanceOf(PlanRunner.Outcome.Finished.class,
                first.run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later())).report().completed());
        assertNotNull(memory.saved, "чистый успех запомнен");
        assertEquals(1, planner.goals.size());

        // Второй раз: план из памяти, планировщик не вызывается, но «Да» спрашивается снова.
        boolean[] hit = {false};
        PlanRunner second = runner(planner, phone, 2).withMemory(memory, () -> hit[0] = true);
        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                second.run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later()));

        assertTrue(f.report().completed());
        assertTrue(f.planFromMemory());
        assertTrue(hit[0], "значок «из памяти» показан");
        assertEquals(1, planner.goals.size(), "планировщик во второй раз не вызывался");
        assertEquals(2, phone.asked.size(), "подтверждение необратимого не отменяется памятью");
    }

    @Test
    void refusal_ofRememberedPlan_doesNothing() {
        FakeMemory memory = new FakeMemory();
        memory.saved = new PlanMemory.Saved(sendFilePlan().steps(), sendFilePlan().confirm(), 36_000);

        PlanRunner r = runner(new FakePlanner(), new FakePhone("Нет"), 2).withMemory(memory, null);
        assertFalse(assertInstanceOf(PlanRunner.Outcome.Finished.class,
                r.run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, later())).report().completed());
        assertTrue(executed.isEmpty());
    }

    // --- мелочи -------------------------------------------------------------------

    @Test
    void verdict_onlyPureYesIsYes() {
        assertEquals(PlanRunner.Verdict.YES, PlanRunner.verdict("Да"));
        assertEquals(PlanRunner.Verdict.YES, PlanRunner.verdict("да, отправляй!"));
        assertEquals(PlanRunner.Verdict.YES, PlanRunner.verdict("Всё верно"));
        assertEquals(PlanRunner.Verdict.NO, PlanRunner.verdict("нет"));
        assertEquals(PlanRunner.Verdict.NO, PlanRunner.verdict("не отправляй"));
        assertEquals(PlanRunner.Verdict.NO, PlanRunner.verdict(null));
        assertEquals(PlanRunner.Verdict.OTHER, PlanRunner.verdict("да, но только Лере"));
        assertEquals(PlanRunner.Verdict.OTHER, PlanRunner.verdict("не Лере, а Маше"));
    }

    @Test
    void summary_includesFactsEstablishedByCode() {
        // Модель сказала «скачал отчёт», а на диске — «report (1).pdf»: следующему
        // шагу нужен точный путь, и он должен попасть в итог.
        ResultData.AgentReport r = new ResultData.AgentReport(true, 3, List.of(
                "шаг 3: done: скачал отчёт", "  стоп: скачал отчёт",
                "факт: скачан файл C:\\Users\\user\\Downloads\\report (1).pdf (240 КБ)"));
        assertEquals("скачал отчёт; скачан файл C:\\Users\\user\\Downloads\\report (1).pdf (240 КБ)",
                PlanRunner.summaryOf(r));
    }

    @Test
    void summary_isWhatTheModelSaidInDone() {
        ResultData.AgentReport r = new ResultData.AgentReport(true, 4,
                List.of("шаг 4: done: скачан report.pdf", "  стоп: скачан report.pdf"));
        assertEquals("скачан report.pdf", PlanRunner.summaryOf(r));
    }

    // --- доверенные скрипты -----------------------------------------------------

    /** Что «запускал» поддельный каталог скриптов. */
    private final List<String> scriptsRun = new ArrayList<>();

    private PlanRunner withFakeScripts(PlanRunner r) {
        return r.withScripts(name -> {
            scriptsRun.add(name);
            if (name.contains("сломан")) throw new IllegalStateException("скрипт «" + name + "» завершился с кодом 1");
            return "скрипт «" + name + "» выполнен";
        });
    }

    @Test
    void scriptInPlan_isAlwaysConfirmed_evenIfPlannerForgot() {
        // Утро разработчика: поднять сервер скриптом, потом сообщить в Telegram.
        // Планировщик подтверждения не попросил — код спросит сам: скрипт может всё.
        FakePlanner planner = new FakePlanner(new PlannerReply.Steps(List.of(
                PlanStep.runScript("сервер диплома"),
                PlanStep.openApp("Telegram"),
                PlanStep.screen("написать в Избранное «сервер поднят»")), ""));
        FakePhone phone = new FakePhone("Да");

        PlanRunner.Outcome out = withFakeScripts(runner(planner, phone, 2)).run("утро разработчика", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class, out);
        assertTrue(f.report().completed(), String.join("\n", f.report().trace()));
        assertEquals(1, phone.asked.size());
        assertTrue(phone.asked.get(0).contains("Запустить скрипт «сервер диплома»"), phone.asked.get(0));
        assertEquals(List.of("сервер диплома"), scriptsRun);
        assertEquals(List.of("Telegram"), opened);
    }

    @Test
    void scriptIsNotRun_withoutYes() {
        FakePlanner planner = new FakePlanner(new PlannerReply.Steps(List.of(
                PlanStep.runScript("сервер диплома"), PlanStep.openApp("Telegram")), ""));

        PlanRunner.Outcome out = withFakeScripts(runner(planner, new FakePhone("Нет"), 2))
                .run("утро разработчика", AgentScope.SCREEN, later());

        assertFalse(assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report().completed());
        assertTrue(scriptsRun.isEmpty(), "без «Да» скрипт не запускается");
    }

    @Test
    void presetScriptPlan_skipsPlanner_butStillAsks() {
        FakePlanner planner = new FakePlanner(); // его не должны спрашивать
        FakePhone phone = new FakePhone("Да");

        PlanRunner.Outcome out = withFakeScripts(runner(planner, phone, 2))
                .withPreset(List.of(PlanStep.runScript("сервер диплома")))
                .run("запусти скрипт сервер диплома", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class, out);
        assertTrue(f.report().completed(), String.join("\n", f.report().trace()));
        assertEquals(0, f.plannerCalls());
        assertTrue(planner.goals.isEmpty());
        assertEquals(1, phone.asked.size());
        assertEquals(List.of("сервер диплома"), scriptsRun);
    }

    @Test
    void failedPresetScript_stops_insteadOfInventingAnotherWay() {
        FakePlanner planner = new FakePlanner(new PlannerReply.Steps(List.of(PlanStep.screen("сделай как-нибудь иначе")), ""));

        PlanRunner.Outcome out = withFakeScripts(runner(planner, new FakePhone("Да"), 2))
                .withPreset(List.of(PlanStep.runScript("сломанный")))
                .run("запусти скрипт сломанный", AgentScope.SCREEN, later());

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class, out);
        assertFalse(f.report().completed());
        assertTrue(String.join("\n", f.report().trace()).contains("кодом 1"));
        assertTrue(executed.isEmpty(), "за человека другой способ не придумываем");
    }

    @Test
    void scriptNamedInAnotherCase_isNotAskedTwice_butUnnamedIsAdded() {
        // Модель написала «скрипт сервера диплома» — это он, второй раз не дописываем.
        PlannerReply.Steps named = new PlannerReply.Steps(List.of(PlanStep.runScript("сервер диплома")),
                "Запустить скрипт сервера диплома и написать в Избранное?");
        assertEquals("Запустить скрипт сервера диплома и написать в Избранное?", PlanRunner.confirmFor(named));

        // А если о скрипте в вопросе ни слова — код допишет его сам.
        PlannerReply.Steps forgot = new PlannerReply.Steps(List.of(PlanStep.runScript("сервер диплома")),
                "Написать в Избранное?");
        assertTrue(PlanRunner.confirmFor(forgot).contains("запустить скрипт «сервер диплома»"),
                PlanRunner.confirmFor(forgot));
    }

    @Test
    void plannerReply_withRunScript_isParsed() throws Exception {
        PlannerReply.Steps s = assertInstanceOf(PlannerReply.Steps.class, GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"run_script","script":"сервер диплома"},
                          {"scope":"open_app","app":"Telegram"}]}
                """));
        assertEquals(PlanStep.Kind.RUN_SCRIPT, s.steps().get(0).kind());
        assertEquals("Запустить скрипт «сервер диплома»", s.steps().get(0).title());

        String prompt = GeminiTaskPlanner.buildPrompt("утро", List.of(), null, List.of(), List.of(),
                List.of("сервер диплома"));
        assertTrue(prompt.contains("ДОВЕРЕННЫЕ СКРИПТЫ ПОЛЬЗОВАТЕЛЯ (для run_script): сервер диплома"), prompt);
    }
}
