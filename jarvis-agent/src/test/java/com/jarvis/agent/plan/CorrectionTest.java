package com.jarvis.agent.plan;

import com.jarvis.agent.plan.PlanRunnerTest.FakePhone;
import com.jarvis.agent.plan.PlanRunnerTest.FakePlanner;
import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.CancelSignal;
import com.jarvis.agent.vision.Clarifications;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Поправка человека по ходу задачи: «нет, не Пете, а Васе».
 *
 * <p>Поддельный исполнитель «получает» поправку посреди своей подзадачи — ровно
 * так, как это бывает вживую: человек говорит в телефон, пока агент работает.
 * Проверяется то, за что отвечает код: прерванное не засчитывается, сделанное
 * не повторяется, хвост плана строится заново и заново подтверждается.
 */
class CorrectionTest {

    @BeforeAll
    static void settings() {
        System.setProperty("faust.ask.enabled", "true");
        System.setProperty("faust.ask.maxPerTask", "10");
    }

    private final List<String> executed = new ArrayList<>();
    private Clarifications questions;

    /**
     * Исполнитель: когда доходит до подзадачи, в цели которой есть ключ из
     * {@code corrections}, «человек присылает» поправку. Если подзадача должна
     * прерваться (как у настоящего агента по сигналу отмены) — она не завершена.
     */
    private PlanRunner runner(FakePlanner planner, FakePhone phone, Map<String, String> corrections, boolean finishAnyway) {
        questions = new Clarifications(phone);
        PlanRunner.SubtaskExecutor exec = (step, deadline) -> {
            executed.add(step.goal());
            for (Map.Entry<String, String> e : corrections.entrySet()) {
                if (step.goal().contains(e.getKey())) {
                    questions.addCorrection(e.getValue());
                    if (!finishAnyway) {
                        return new ResultData.AgentReport(false, 1, List.of("шаг 1: type(Пете)", "  стоп: задачу отменил пользователь"));
                    }
                }
            }
            return new ResultData.AgentReport(true, 2, List.of("шаг 1: click(1)", "  стоп: итог: " + step.goal()));
        };
        return new PlanRunner(planner, exec, name -> name, (items, rev) -> { }, questions, CancelSignal.NONE,
                new PlanRunner.Limits(8, 2, 3, Duration.ofMinutes(3)));
    }

    private static Instant later() {
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    private static PlannerReply.Steps sendToPetya() {
        return new PlannerReply.Steps(List.of(
                PlanStep.openApp("Telegram"),
                PlanStep.screen("отправь файл отчёт.pdf Пете")),
                "Отправить отчёт.pdf Пете в Telegram?");
    }

    @Test
    void correctionMidStep_interruptsIt_andReplansTail_withNewConfirmation() {
        PlannerReply.Steps tail = new PlannerReply.Steps(List.of(PlanStep.screen("отправь файл отчёт.pdf Васе")),
                "Отправить отчёт.pdf Васе в Telegram?");
        FakePlanner planner = new FakePlanner(sendToPetya(), tail);
        FakePhone phone = new FakePhone("Да", "Да");

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                runner(planner, phone, Map.of("Пете", "нет, не Пете, а Васе"), false)
                        .run("отправь отчёт Пете в телеграме", AgentScope.SCREEN, later()));

        assertTrue(f.report().completed(), String.join("\n", f.report().trace()));
        assertEquals(List.of("отправь файл отчёт.pdf Пете", "отправь файл отчёт.pdf Васе"), executed);
        // «Да» на Петю не переносится на Васю: поправка меняет, кому отправлять.
        assertEquals(List.of("Отправить отчёт.pdf Пете в Telegram?", "Отправить отчёт.pdf Васе в Telegram?"), phone.asked);
        // Планировщик знает, что это поправка, а не неудачный шаг.
        assertTrue(planner.failures.get(0).summary().startsWith(PlanRunner.CORRECTION_MARK), planner.failures.toString());
        assertTrue(planner.failures.get(0).summary().contains("прервана на середине"));
        // И видит саму поправку в цели — отдельным разделом.
        String lastGoal = planner.goals.get(planner.goals.size() - 1);
        assertTrue(lastGoal.contains("ПОПРАВКИ ПОЛЬЗОВАТЕЛЯ") && lastGoal.contains("а Васе"), lastGoal);
        assertEquals(0, f.replans(), "поправка — не перестройка после неудачи и не тратит её лимит");
    }

    @Test
    void stepThatFinishedAnyway_isKept_andNotRepeated() {
        PlannerReply.Steps plan = new PlannerReply.Steps(List.of(
                PlanStep.browser("скачай отчёт с сайта"),
                PlanStep.openApp("Telegram"),
                PlanStep.screen("отправь отчёт Лере")), "Отправить отчёт Лере?");
        PlannerReply.Steps tail = new PlannerReply.Steps(List.of(
                PlanStep.screen("отправь отчёт Лере"),
                PlanStep.screen("отправь отчёт Маше")), "Отправить отчёт Лере и Маше?");
        FakePhone phone = new FakePhone("Да", "Да");

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                runner(new FakePlanner(plan, tail), phone, Map.of("скачай", "и Маше тоже"), true)
                        .run("скачай отчёт и отправь Лере", AgentScope.SCREEN, later()));

        assertTrue(f.report().completed(), String.join("\n", f.report().trace()));
        assertEquals(List.of("скачай отчёт с сайта", "отправь отчёт Лере", "отправь отчёт Маше"), executed,
                "скачанное второй раз не качается, открытие Telegram заменено новым хвостом");
    }

    @Test
    void refusedConfirmation_afterCorrection_stops() {
        PlannerReply.Steps tail = new PlannerReply.Steps(List.of(PlanStep.screen("отправь файл отчёт.pdf Васе")),
                "Отправить отчёт.pdf Васе в Telegram?");
        FakePhone phone = new FakePhone("Да", "Нет");

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                runner(new FakePlanner(sendToPetya(), tail), phone, Map.of("Пете", "нет, Васе"), false)
                        .run("отправь отчёт Пете", AgentScope.SCREEN, later()));

        assertFalse(f.report().completed());
        assertFalse(executed.stream().anyMatch(g -> g.contains("Васе")), "без «Да» новый хвост не выполняется");
    }

    @Test
    void nothingLeftToDo_isASuccess() {
        FakePhone phone = new FakePhone("Да");
        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                runner(new FakePlanner(sendToPetya(), new PlannerReply.GiveUp("НЕЧЕГО ДЕЛАТЬ: отправлять не нужно")),
                        phone, Map.of("Пете", "стой, никому не отправляй"), false)
                        .run("отправь отчёт Пете", AgentScope.SCREEN, later()));

        assertTrue(f.report().completed(), "«ничего не отправляй» — законный конец задачи, а не провал");
    }

    @Test
    void tooManyCorrections_stopTheTask() {
        PlannerReply.Steps again = new PlannerReply.Steps(List.of(PlanStep.screen("отправь файл Пете")), "Отправить Пете?");
        FakePlanner planner = new FakePlanner(sendToPetya(), again, again, again, again);
        FakePhone phone = new FakePhone("Да", "Да", "Да", "Да", "Да");

        PlanRunner.Outcome.Finished f = assertInstanceOf(PlanRunner.Outcome.Finished.class,
                runner(planner, phone, Map.of("Пете", "нет, подожди"), false)
                        .run("отправь отчёт Пете", AgentScope.SCREEN, later()));

        assertFalse(f.report().completed());
        assertTrue(f.report().trace().get(f.report().trace().size() - 1).contains("поправок больше"),
                f.report().trace().toString());
    }

    @Test
    void plannerPrompt_forCorrection_isNotTheFailurePrompt() {
        StepResult marker = new StepResult(PlanStep.screen("отправь файл Пете"), false,
                PlanRunner.CORRECTION_MARK + "«нет, Васе»; подзадача прервана на середине");
        String prompt = GeminiTaskPlanner.buildPrompt("отправь файл Пете", List.of(), marker, List.of());

        assertTrue(prompt.contains("ПОПРАВКУ ПО ХОДУ"), prompt);
        assertFalse(prompt.contains("НЕ УДАЛАСЬ"), "это не неудача — «сделай по-другому» здесь неверно");
        assertTrue(prompt.contains("НЕЧЕГО ДЕЛАТЬ"));
    }
}
