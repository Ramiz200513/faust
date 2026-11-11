package com.jarvis.agent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор ответа планировщика — всех трёх видов и типичных вольностей модели. */
class GeminiTaskPlannerTest {

    @Test
    void steps_withConfirm() throws Exception {
        PlannerReply r = GeminiTaskPlanner.parse("""
                {"steps":[
                  {"scope":"browser","goal":"скачай отчёт с example.com"},
                  {"scope":"open_app","app":"WhatsApp"},
                  {"scope":"screen","goal":"отправь скачанный файл Лере"}],
                 "confirm":"Отправить отчёт Лере в WhatsApp?"}
                """);

        PlannerReply.Steps s = assertInstanceOf(PlannerReply.Steps.class, r);
        assertEquals(List.of(PlanStep.Kind.BROWSER, PlanStep.Kind.OPEN_APP, PlanStep.Kind.SCREEN),
                s.steps().stream().map(PlanStep::kind).toList());
        assertEquals("Открыть WhatsApp", s.steps().get(1).title());
        assertEquals("Отправить отчёт Лере в WhatsApp?", s.confirm());
    }

    @Test
    void checks_areParsed_onlyWhereTheyMakeSense() throws Exception {
        PlannerReply.Steps s = assertInstanceOf(PlannerReply.Steps.class, GeminiTaskPlanner.parse("""
                {"steps":[
                  {"scope":"browser","goal":"скачай","check":"видно файл","expect_file":"Загрузки\\\\a.zip"},
                  {"scope":"screen","goal":"сохрани","check":"заголовок — курс.txt","expect_file":"Рабочий стол\\\\курс.txt"}]}
                """));
        assertNull(s.steps().get(0).check(), "у браузера проверки взглядом нет");
        assertEquals("Загрузки\\a.zip", s.steps().get(0).expectFile());
        assertEquals("заголовок — курс.txt", s.steps().get(1).check());
    }

    @Test
    void installedApps_goToThePrompt() {
        String p = GeminiTaskPlanner.buildPrompt("напиши документ", List.of(), null, List.of(),
                List.of("LibreOffice Writer", "Telegram"));
        assertTrue(p.contains("LibreOffice Writer"), p);
    }

    @Test
    void ask_and_giveUp() throws Exception {
        PlannerReply.Ask a = assertInstanceOf(PlannerReply.Ask.class,
                GeminiTaskPlanner.parse("{\"ask\":\"Какой файл?\",\"options\":\"отчёт / фото\"}"));
        assertEquals(List.of("отчёт", "фото"), a.options());

        assertInstanceOf(PlannerReply.GiveUp.class,
                GeminiTaskPlanner.parse("```json\n{\"give_up\":\"нужен пароль\"}\n```"));
    }

    @Test
    void sloppySteps_areRepairedOrDropped() throws Exception {
        // Название программы в goal вместо app, неизвестный scope, пустая цель.
        PlannerReply.Steps s = assertInstanceOf(PlannerReply.Steps.class, GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"open_app","goal":"Telegram"},
                          {"scope":"teleport","goal":"???"},
                          {"scope":"screen","goal":""},
                          {"scope":"screen","goal":"найди чат Лера"}]}
                """));
        assertEquals(2, s.steps().size());
        assertEquals("Telegram", s.steps().get(0).app());
        assertEquals("", s.confirm());
    }

    @Test
    void nothingUsable_isNull_soTheOldPathRuns() throws Exception {
        assertNull(GeminiTaskPlanner.parse("{\"steps\":[]}"));
    }

    @Test
    void replanPrompt_mentionsWhatFailedAndWhy() {
        String p = GeminiTaskPlanner.buildPrompt("отправь отчёт Лере",
                List.of(new StepResult(PlanStep.browser("скачай отчёт"), true, "скачан report.pdf")),
                new StepResult(PlanStep.screen("нажми скрепку"), false, "кнопка не найдена"),
                List.of(PlanStep.screen("отправь")));
        assertTrue(p.contains("скачан report.pdf"), "что уже сделано — не повторять");
        assertTrue(p.contains("кнопка не найдена"), "почему сломалось — чтобы не предлагать то же самое");
    }

    @Test
    void gitAndCodeSteps_parsed() throws Exception {
        PlannerReply.Steps s = (PlannerReply.Steps) GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"code","repo":"демо","goal":"порт web 8090"},
                          {"scope":"git","repo":"демо","action":"commit","message":"Порт 8090"},
                          {"scope":"git","repo":"демо","action":"push"},
                          {"scope":"git","repo":"демо","action":"rebase"}],"confirm":"Запушить?"}
                """);
        assertEquals(List.of(PlanStep.code("демо", "порт web 8090"), PlanStep.git("демо", "commit: Порт 8090"),
                PlanStep.git("демо", "push")), s.steps(), "неизвестная операция git отбрасывается");
        assertEquals("", PlanRunner.confirmFor(s), "git и правки кода спрашивают сами, с диффом");
    }

    @Test
    void promptListsRepos_withoutPaths() {
        String p = GeminiTaskPlanner.buildPrompt("запушь", List.of(), null, List.of(), List.of(), List.of(),
                List.of("демо"));
        assertTrue(p.contains("РЕПОЗИТОРИИ (для git и code): демо"), p);
        assertTrue(p.contains("КОМАНДЫ ПРОЕКТОВ (для git run): нет"), p);
    }

    @Test
    void runStep_parsed_andCommandNamesInPrompt_withoutCommandLines() throws Exception {
        PlannerReply.Steps s = (PlannerReply.Steps) GeminiTaskPlanner.parse("""
                {"steps":[{"scope":"code","repo":"демо","goal":"порт 8090"},
                          {"scope":"git","repo":"демо","action":"run","command":"тесты"},
                          {"scope":"git","repo":"демо","action":"run"},
                          {"scope":"git","repo":"демо","action":"commit"}]}
                """);
        assertEquals(List.of(PlanStep.code("демо", "порт 8090"), PlanStep.git("демо", "run: тесты"),
                PlanStep.git("демо", "commit")), s.steps(), "run без названия команды отбрасывается");
        assertEquals("", PlanRunner.confirmFor(s), "прогон тестов — тоже без общего вопроса: команду задал человек");

        String p = GeminiTaskPlanner.buildPrompt("поменяй порт и запушь", List.of(), null, List.of(), List.of(),
                List.of(), List.of("демо", "диплом"), java.util.Map.of(),
                new java.util.LinkedHashMap<>(java.util.Map.of("демо", List.of("тесты", "сборка"))));
        assertTrue(p.contains("КОМАНДЫ ПРОЕКТОВ (для git run): демо: тесты, сборка"), p);
    }
}
