package com.jarvis.agent.plan;

import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.CancelSignal;
import com.jarvis.agent.vision.Clarifications;
import com.jarvis.agent.vision.UserAsker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Длинные связки: «для каждого из списка», «дождись, пока…», «продолжи с
 * места сбоя». Без сети и экрана — планировщик, исполнитель и телефон поддельные.
 */
class LongChainsTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void settings() {
        System.setProperty("faust.ask.enabled", "true");
        System.setProperty("faust.ask.maxPerTask", "10");
        System.setProperty("faust.resumeFile", tmp.resolve("resume.json").toString());
    }

    @AfterEach
    void clean() {
        System.clearProperty("faust.resumeFile");
    }

    static final class Phone implements UserAsker {
        final List<String> answers;
        final List<String> asked = new ArrayList<>();

        Phone(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public String ask(String question, List<String> options, CancelSignal cancel) {
            asked.add(question);
            return answers.isEmpty() ? null : answers.remove(0);
        }
    }

    private static Map<String, List<String>> lists() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("одногруппники", List.of("Петя", "Маша", "Лера"));
        m.put("семья", List.of("мама", "папа"));
        return m;
    }

    // --- для каждого из списка ---------------------------------------------------

    @Test
    void forEach_expandedByCode_withEveryNameInConfirm() throws Exception {
        PlannerReply.Steps s = (PlannerReply.Steps) GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"open_app","app":"Telegram"},
                          {"scope":"for_each","list":"одногруппникам","steps":[
                              {"scope":"screen","goal":"отправь {имя} файл отчёт.pdf","check":"в чате {имя} файл"}]}],
                 "confirm":"Отправить отчёт одногруппникам?"}
                """, lists());

        assertEquals(List.of("Открыть Telegram", "отправь Петя файл отчёт.pdf", "отправь Маша файл отчёт.pdf",
                "отправь Лера файл отчёт.pdf"), s.steps().stream().map(PlanStep::title).toList());
        assertEquals("в чате Лера файл", s.steps().get(3).check());
        assertEquals(3, s.listSteps());
        assertTrue(s.confirm().contains("Получатели (3): Петя, Маша, Лера"), s.confirm());
    }

    @Test
    void forEach_unknownList_isRefused_notGuessed() throws Exception {
        PlannerReply r = GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"for_each","list":"все контакты","steps":[{"scope":"screen","goal":"напиши {имя} привет"}]}]}
                """, lists());
        PlannerReply.GiveUp g = assertInstanceOf(PlannerReply.GiveUp.class, r);
        assertTrue(g.reason().contains("есть: одногруппники, семья"), g.reason());
    }

    @Test
    void forEach_forgottenPlaceholder_stillMakesStepsDistinct() throws Exception {
        PlannerReply.Steps s = (PlannerReply.Steps) GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"for_each","list":"семья","steps":[{"scope":"screen","goal":"отправь фото"}]}]}
                """, lists());
        assertEquals(List.of("отправь фото (для: мама)", "отправь фото (для: папа)"),
                s.steps().stream().map(PlanStep::goal).toList());
    }

    @Test
    void longList_isNotSilentlyTruncated() {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(PlanStep.openApp("Telegram"));
        for (int i = 0; i < 10; i++) steps.add(PlanStep.screen("отправь человеку " + i));
        List<String> ran = new ArrayList<>();
        PlanRunner runner = new PlanRunner(
                (goal, done, failed, remaining) -> new PlannerReply.Steps(steps, "Отправить всем?", 10),
                (step, d) -> {
                    ran.add(step.goal());
                    return new ResultData.AgentReport(true, 1, List.of("стоп: ok"));
                },
                name -> name, (items, rev) -> { }, new Clarifications(new Phone("Да")), CancelSignal.NONE,
                new PlanRunner.Limits(8, 2, 3, Duration.ofMinutes(3)));

        runner.run("отправь всем", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(5)));

        assertEquals(10, ran.size(), "лимит 8 подзадач не режет список получателей");
    }

    // --- дождись, пока ------------------------------------------------------------

    private PlanRunner waitingRunner(StepVerifier verifier) {
        PlanRunner r = new PlanRunner(null, (step, d) -> new ResultData.AgentReport(true, 1, List.of("стоп: ok")),
                name -> name, (items, rev) -> { }, new Clarifications(new Phone()), CancelSignal.NONE,
                new PlanRunner.Limits(8, 0, 3, Duration.ofMinutes(3)), verifier);
        r.waitPollMs = 5;
        r.waitVisualEveryMs = 0;
        r.waitUnitMs = 300; // «минута» в тесте — 0,3 с
        return r;
    }

    @Test
    void wait_untilProcessEnds() {
        AtomicInteger polls = new AtomicInteger();
        PlanRunner r = waitingRunner(StepVerifier.NONE);
        r.processRunning = name -> polls.incrementAndGet() < 4; // «сборка» работает три опроса
        PlanRunner.Outcome out = r.withPreset(List.of(PlanStep.waitFor("пока закончится сборка", "gradle", null, null, 1)))
                .run("дождись конца сборки", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(1)));
        ResultData.AgentReport rep = assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report();
        assertTrue(rep.completed(), String.join("\n", rep.trace()));
        assertTrue(String.join("\n", rep.trace()).contains("процесс «gradle» завершился"));
    }

    @Test
    void wait_visualCheck_untilSeen_andTimeout() {
        AtomicInteger looks = new AtomicInteger();
        StepVerifier seenOnThird = (step, summary) ->
                new StepVerifier.Check(looks.incrementAndGet() >= 3, "в окне надпись BUILD SUCCESSFUL");
        PlanRunner.Outcome ok = waitingRunner(seenOnThird)
                .withPreset(List.of(PlanStep.waitFor("сборка", null, "видно BUILD SUCCESSFUL", null, 1)))
                .run("жди", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(1)));
        assertTrue(assertInstanceOf(PlanRunner.Outcome.Finished.class, ok).report().completed());
        assertEquals(3, looks.get());

        StepVerifier never = (step, summary) -> new StepVerifier.Check(false, "не видно");
        PlanRunner slow = waitingRunner(never);
        slow.waitVisualEveryMs = 1_000_000; // смотреть моделью только один раз — дальше ждём таймаута
        PlanRunner.Outcome late = slow
                .withPreset(List.of(PlanStep.waitFor("сборка", null, "видно BUILD SUCCESSFUL", null, 1)))
                .run("жди", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(1)));
        ResultData.AgentReport rep = assertInstanceOf(PlanRunner.Outcome.Finished.class, late).report();
        assertFalse(rep.completed());
        assertTrue(String.join("\n", rep.trace()).contains("не дождался за 1 мин"), String.join("\n", rep.trace()));
    }

    @Test
    void wait_parsedFromPlanner() throws Exception {
        PlannerReply.Steps s = (PlannerReply.Steps) GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"run_script","script":"сборка"},
                          {"scope":"wait","goal":"пока закончится сборка","process":"gradle","minutes":15},
                          {"scope":"wait","goal":"ничего"}]}
                """);
        assertEquals(2, s.steps().size(), "ожидание без условия отбрасывается");
        assertEquals(PlanStep.waitFor("пока закончится сборка", "gradle", null, null, 15), s.steps().get(1));
        assertEquals("", PlanRunner.confirmFor(new PlannerReply.Steps(List.of(
                PlanStep.waitFor("пока отправится письмо", "outlook", null, null, null)), "")),
                "ожидание ничего не делает — подтверждать нечего");
    }

    // --- продолжи с места сбоя -----------------------------------------------------

    @Test
    void brokenPlan_isSaved_andResumedFromFailedStep_withoutRepeatingDone() {
        List<PlanStep> plan = List.of(PlanStep.browser("скачай отчёт"), PlanStep.openApp("Telegram"),
                PlanStep.screen("отправь отчёт Лере"), PlanStep.screen("отправь отчёт Маше"));
        List<String> ran = new ArrayList<>();
        boolean[] broken = {true};
        PlanRunner.SubtaskExecutor exec = (step, d) -> {
            ran.add(step.goal());
            boolean fail = broken[0] && step.goal().contains("Лере");
            return new ResultData.AgentReport(!fail, 2, List.of(fail ? "стоп: чат Лера не найден"
                    : "стоп: готово" + (step.goal().contains("скачай") ? "; скачан report.pdf" : "")));
        };
        List<List<JarvisMessage.PlanItem>> published = new ArrayList<>();

        new PlanRunner((g, done, failed, rem) -> new PlannerReply.Steps(plan, "Отправить Лере и Маше?"), exec,
                name -> name, (items, rev) -> { }, new Clarifications(new Phone("Да")), CancelSignal.NONE,
                new PlanRunner.Limits(8, 0, 3, Duration.ofMinutes(3)))
                .withResumeSaving(true)
                .run("скачай отчёт и отправь Лере и Маше", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(5)));

        ResumeStore.Saved saved = ResumeStore.load().orElseThrow();
        assertEquals(List.of("скачай отчёт", "Открыть Telegram"),
                saved.done().stream().map(d -> d.step().title()).toList(), "сделано: скачивание и открытие программы");
        assertTrue(saved.done().get(0).summary().contains("скачан report.pdf"), "итоги сохраняются для следующих шагов");
        assertEquals("отправь отчёт Лере", saved.remaining().get(0).goal(), "продолжаем с упавшего шага");
        assertTrue(ResumeStore.isResumeRequest("Фауст, продолжи с места сбоя"));
        assertFalse(ResumeStore.isResumeRequest("продолжи музыку"));

        broken[0] = false;
        ran.clear();
        Phone phone = new Phone("Да");
        PlanRunner.Outcome out = new PlanRunner(null, exec, name -> name, (items, rev) -> published.add(items),
                new Clarifications(phone), CancelSignal.NONE, new PlanRunner.Limits(8, 0, 3, Duration.ofMinutes(3)))
                .withResumeSaving(true)
                .withPreset(saved.remaining())
                .withResume(saved)
                .run(saved.goal(), AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(5)));

        assertTrue(assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report().completed());
        assertFalse(ran.contains("скачай отчёт"), "сделанное не повторяется: " + ran);
        assertTrue(ran.contains("отправь отчёт Лере") && ran.contains("отправь отчёт Маше"), ran.toString());
        assertTrue(phone.asked.get(0).contains("Продолжу с шага: отправь отчёт Лере"), phone.asked.get(0));
        assertEquals(JarvisMessage.PlanItemStatus.DONE, published.get(0).get(0).status(), "сделанное видно в чек-листе");
        assertTrue(ResumeStore.load().isEmpty(), "выполненный план продолжать нечего");
    }

    @Test
    void declinedPlan_isNotOfferedForResume() {
        List<PlanStep> plan = List.of(PlanStep.screen("отправь отчёт Лере"));
        new PlanRunner((g, done, failed, rem) -> new PlannerReply.Steps(plan, "Отправить Лере?"),
                (step, d) -> new ResultData.AgentReport(true, 1, List.of("стоп: ok")),
                name -> name, (items, rev) -> { }, new Clarifications(new Phone("Нет")), CancelSignal.NONE,
                new PlanRunner.Limits(8, 0, 3, Duration.ofMinutes(3)))
                .withResumeSaving(true)
                .run("отправь отчёт Лере", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(5)));
        assertTrue(ResumeStore.load().isEmpty(), "человек сказал «нет» — продолжать нечего");
    }
}
