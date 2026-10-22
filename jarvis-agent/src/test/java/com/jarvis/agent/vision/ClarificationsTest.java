package com.jarvis.agent.vision;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Правила вопросов человеку — то, что должно работать независимо от того,
 * насколько послушна модель: память ответов, запрет повторов и секретов,
 * лимит, молчание = «нет».
 */
class ClarificationsTest {

    /** Поддельный «телефон»: отвечает заранее заданными ответами и запоминает, что спросили. */
    private static final class FakePhone implements UserAsker {
        final List<String> asked = new ArrayList<>();
        final List<List<String>> options = new ArrayList<>();
        private final List<String> answers;

        FakePhone(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public String ask(String question, List<String> opts, CancelSignal cancel) {
            asked.add(question);
            options.add(opts);
            return answers.isEmpty() ? null : answers.remove(0);
        }
    }

    @Test
    void answerIsGluedToGoal_soModelSeesItOnEveryStep() {
        FakePhone phone = new FakePhone("Лере и Маше");
        Clarifications c = new Clarifications(phone, true, 5);

        String note = c.ask("Кому отправить файл?", List.of(), CancelSignal.NONE);

        assertTrue(note.startsWith(Clarifications.QUESTION_MARK), note);
        assertTrue(note.contains("Лере и Маше"), note);
        String goal = c.goalFor("отправь отчёт");
        assertTrue(goal.startsWith("отправь отчёт"), goal);
        assertTrue(goal.contains("УТОЧНЕНИЯ ОТ ПОЛЬЗОВАТЕЛЯ"), goal);
        assertTrue(goal.contains("Лере и Маше"), goal);
        assertEquals(1, c.asked());
        assertEquals(1, c.answeredCount());
    }

    @Test
    void withoutAnswers_goalIsUnchanged() {
        // Важно для выключателя: без вопросов промпт не должен измениться ни на символ.
        Clarifications c = new Clarifications(new FakePhone(), true, 5);
        assertEquals("открой блокнот", c.goalFor("открой блокнот"));
    }

    @Test
    void sameQuestionTwice_secondTimeAnsweredFromMemory() {
        FakePhone phone = new FakePhone("report.pdf");
        Clarifications c = new Clarifications(phone, true, 5);

        c.ask("Какой файл отправить?", List.of(), CancelSignal.NONE);
        String second = c.ask("какой  файл отправить", List.of(), CancelSignal.NONE);

        assertEquals(1, phone.asked.size(), "человека не должны спрашивать второй раз");
        assertTrue(second.contains("report.pdf"), second);
        assertEquals(1, c.asked());
    }

    @Test
    void silence_meansNo_andIsNotRemembered() {
        Clarifications c = new Clarifications(new FakePhone(/* ответа не будет */), true, 5);

        String note = c.ask("Отправить файл Лере?", List.of("Да", "Нет"), CancelSignal.NONE);

        assertTrue(note.contains("ответа нет"), note);
        assertTrue(note.contains("«нет»"), note);
        assertEquals("цель", c.goalFor("цель"), "молчание не должно попасть в уточнения как ответ");
        assertEquals(1, c.asked());
        assertEquals(0, c.answeredCount());
    }

    @Test
    void secretQuestion_neverReachesThePhone() {
        FakePhone phone = new FakePhone("1234");
        Clarifications c = new Clarifications(phone, true, 5);

        String note = c.ask("Какой пароль от почты?", List.of(), CancelSignal.NONE);

        assertTrue(phone.asked.isEmpty(), "вопрос про пароль не должен уйти на телефон");
        assertTrue(note.contains("не спрашиваю"), note);
        assertEquals(0, c.asked());
    }

    @Test
    void pictureIsNotASecret() {
        // Общий SensitiveInputGuard сработал бы на подстроку «карт» — здесь нельзя.
        FakePhone phone = new FakePhone("котик.jpg");
        Clarifications c = new Clarifications(phone, true, 5);

        c.ask("Какую картинку отправить?", List.of(), CancelSignal.NONE);

        assertEquals(1, phone.asked.size());
    }

    @Test
    void limitPerTask_isEnforced() {
        FakePhone phone = new FakePhone("a", "b", "c");
        Clarifications c = new Clarifications(phone, true, 2);

        c.ask("вопрос 1", List.of(), CancelSignal.NONE);
        c.ask("вопрос 2", List.of(), CancelSignal.NONE);
        String third = c.ask("вопрос 3", List.of(), CancelSignal.NONE);

        assertEquals(2, phone.asked.size());
        assertTrue(third.contains("лимит"), third);
    }

    @Test
    void disabledInSettings_phoneIsNotAsked() {
        FakePhone phone = new FakePhone("да");
        Clarifications c = new Clarifications(phone, false, 5);

        String note = c.ask("Отправить?", List.of("Да", "Нет"), CancelSignal.NONE);

        assertTrue(phone.asked.isEmpty());
        assertTrue(note.contains("выключено"), note);
    }

    @Test
    void noPhone_modelIsToldToActAlone() {
        String note = Clarifications.none().ask("Кому?", List.of(), CancelSignal.NONE);
        assertFalse(note.startsWith(Clarifications.QUESTION_MARK), note);
    }

    @Test
    void options_areCleaned() {
        FakePhone phone = new FakePhone("Да");
        Clarifications c = new Clarifications(phone, true, 5);

        c.ask("Отправить?", java.util.Arrays.asList(" Да ", "", null, "Да", "Нет"), CancelSignal.NONE);

        assertEquals(List.of("Да", "Нет"), phone.options.get(0));
    }

    @Test
    void deadline_isExtendedByWaitingTime() {
        UserAsker slowHuman = (q, o, cancel) -> {
            try { Thread.sleep(60); } catch (InterruptedException ignored) { }
            return "да";
        };
        Clarifications c = new Clarifications(slowHuman, true, 5);
        Instant deadline = Instant.parse("2026-09-30T12:00:00Z");

        c.ask("Можно?", List.of(), CancelSignal.NONE);

        assertTrue(c.waitedMs() >= 50, "ждали " + c.waitedMs() + " мс");
        assertEquals(deadline.plusMillis(c.waitedMs()), c.extend(deadline));
    }
}
