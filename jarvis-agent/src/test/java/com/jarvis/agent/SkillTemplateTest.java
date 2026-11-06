package com.jarvis.agent;

import com.jarvis.agent.vision.TrajectoryCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Показ с переменными: показали «отправь Пете отчёт» — работает «отправь Маше
 * расписание». Проверяется без экрана: что агент нашёл в показе, как разбирает
 * новую фразу и что подставит в поле и в клик.
 */
class SkillTemplateTest {

    private static TrajectoryCache.Step open(String app) {
        return new TrajectoryCache.Step("open_app", null, null, null, -1, app, null, null, false);
    }

    private static TrajectoryCache.Step click(String type, String label) {
        return new TrajectoryCache.Step("click", type, "uia", label, -1, null, null, null, false);
    }

    private static TrajectoryCache.Step type(String text) {
        return new TrajectoryCache.Step("type", null, null, null, -1, text, null, null, false);
    }

    /** Так выглядит запись «отправь Пете отчёт» в Telegram — как её пишет DemoRecorder. */
    private static final List<TrajectoryCache.Step> SEND_DEMO = List.of(
            open("Telegram"),
            click("поле", "Поиск"),
            type("Петя"),
            click("элемент списка", "Петя Иванов, был(а) недавно"),
            click("кнопка", "Прикрепить файл"),
            type("отчёт.pdf"),
            click("кнопка", "Открыть"),
            click("кнопка", "Отправить"));

    private static final List<String> SEND_DESCRIPTIONS = List.of(
            "Открыть Telegram", "Нажать «Поиск»", "Ввести «Петя»", "Нажать «Петя Иванов»",
            "Нажать «Прикрепить файл»", "Ввести «отчёт.pdf»", "Нажать «Открыть»", "Нажать «Отправить»");

    @BeforeEach
    void clean() throws Exception {
        Files.deleteIfExists(Path.of(System.getProperty("faust.skillsFile")));
    }

    @Test
    void typedText_isFound_evenWhenFocusIsOnAnUnnamedPane_likeWindows11Notepad() {
        // Ровно так снимок выглядел 06.11: фокус у пустой панели, текст — в редакторе.
        var editor = new com.jarvis.agent.vision.UiaElement(1, "document", "Редактор текста",
                0, 80, 800, 500, false, true, "купить молоко", false);
        var pane = new com.jarvis.agent.vision.UiaElement(2, "pane", "",
                0, 80, 800, 500, false, true, "", true);
        var search = new com.jarvis.agent.vision.UiaElement(3, "edit", "Поиск",
                600, 10, 150, 30, false, true, "", false);
        var window = new com.jarvis.agent.vision.UiaSnapshot.Result("Блокнот", List.of(pane, editor, search), 10);
        assertEquals("Редактор текста", DemoRecorder.typedField(window).name());
    }

    @Test
    void wordsMatch_upToRussianEndings_butNotDifferentNames() {
        assertTrue(SkillTemplate.sameWord("Пете", "Петя"));
        assertTrue(SkillTemplate.sameWord("отчёта", "отчет"));
        assertTrue(SkillTemplate.sameWord("Маше", "Маша"));
        assertFalse(SkillTemplate.sameWord("Маша", "мама"), "общее «ма» — это не одно слово");
    }

    @Test
    void shortWords_neverStartAVariable() {
        // В поле вводили «в 10:00», во фразе есть «в» — предлог переменной не станет.
        SkillTemplate.Generalized g = SkillTemplate.generalize("напомни в обед",
                List.of(open("Блокнот"), type("в")), List.of("Открыть Блокнот", "Ввести «в»"));
        assertFalse(g.hasSlots());
    }

    @Test
    void generalize_findsWhoAndWhat_inTheDemo() {
        SkillTemplate.Generalized g = SkillTemplate.generalize("Отправь Пете отчёт", SEND_DEMO, SEND_DESCRIPTIONS);

        assertEquals(List.of("отправь", "{1}", "{2}"), g.template());
        assertEquals(2, g.slots().size());
        SkillTemplate.Slot who = g.slots().get(0), what = g.slots().get(1);
        assertEquals("Пете", who.said());
        assertEquals("Петя", who.shown());
        assertTrue(who.inflected(), "сказано «Пете», а вводили «Петя» — в поиск пойдёт основа");
        assertEquals("отчёт", what.said());
        assertFalse(what.inflected());

        // Ввод «Петя» и клик по «Петя Иванов» — одна и та же переменная.
        assertEquals("{1}", g.steps().get(2).text());
        assertEquals("{1}", g.steps().get(3).label());
        assertEquals("{2}", g.steps().get(5).text(), "расширение файла не входит в переменную");
        // Постоянные шаги не тронуты.
        assertEquals("Поиск", g.steps().get(1).label());
        assertEquals("Отправить", g.steps().get(7).label());
        assertEquals("Ввести «{1}»", g.descriptions().get(2));
    }

    @Test
    void newPhrase_isParsed_andValuesSubstituted() {
        SkillTemplate.Generalized g = SkillTemplate.generalize("отправь Пете отчёт", SEND_DEMO, SEND_DESCRIPTIONS);

        Map<Integer, String> v = SkillTemplate.match(g.template(), g.slots(), "Отправь Маше расписание").orElseThrow();
        assertEquals(Map.of(1, "Маше", 2, "расписание"), v);

        SkillTemplate.Binding b = new SkillTemplate.Binding(g.slots(), v);
        assertEquals("Маш", b.typed("{1}"), "в поиск — основа: найдёт и «Маша», и «Машу»");
        assertEquals("расписание", b.typed("{2}"));
        assertEquals("Ввести «Маше»", b.shown("Ввести «{1}»"));
        assertTrue(b.labelMatches("{1}", "Маша Петрова, в сети"));
        assertFalse(b.labelMatches("{1}", "Петя Иванов, был(а) недавно"));

        // Не та фраза — не этот навык.
        assertTrue(SkillTemplate.match(g.template(), g.slots(), "открой ютуб").isEmpty());
        assertTrue(SkillTemplate.match(g.template(), g.slots(), "отправь").isEmpty());
    }

    @Test
    void relayRegex_recognizesTheSameFamilyOfPhrases() {
        SkillTemplate.Generalized g = SkillTemplate.generalize("отправь Пете отчёт", SEND_DEMO, SEND_DESCRIPTIONS);
        // Тот же текст выражения проверяется на релее (SkillPhrasesTest.SEND) —
        // если формат здесь поменяется, тест покажет, что релей надо поправить тоже.
        assertEquals("\\Qотпра\\E\\S*\\s+\\S+(?:\\s+\\S+){0,3}\\s+\\S+(?:\\s+\\S+){0,3}",
                SkillTemplate.toRelayRegex(g.template()));
        Pattern p = Pattern.compile(SkillTemplate.toRelayRegex(g.template()));
        assertTrue(p.matcher("отправь маше расписание").matches());
        assertTrue(p.matcher("отправь маше ивановой расписание").matches());
        assertFalse(p.matcher("открой ютуб").matches());
        assertFalse(p.matcher("отправь").matches());
    }

    @Test
    void demoWithoutDataFromThePhrase_hasNoVariables() {
        SkillTemplate.Generalized g = SkillTemplate.generalize("утренний старт",
                List.of(open("Telegram"), click("кнопка", "Избранное")), List.of("Открыть Telegram", "Нажать «Избранное»"));
        assertFalse(g.hasSlots());
    }

    @Test
    void store_findsSkillByTemplate_andKeepsOldSkillsWorking() {
        SkillTemplate.Generalized g = SkillTemplate.generalize("отправь Пете отчёт", SEND_DEMO, SEND_DESCRIPTIONS);
        SkillStore.save(new SkillStore.Skill("отправить файл", List.of(), g.steps(), g.descriptions(),
                1, 20_000, g.template(), g.slots()));
        SkillStore.save(new SkillStore.Skill("утро", List.of("доброе утро"),
                List.of(click("кнопка", "Пуск")), List.of("Нажать «Пуск»"), 1, 1000));

        SkillStore.Found found = SkillStore.find("Фауст, отправь Маше расписание").orElseThrow();
        assertEquals("отправить файл", found.skill().name());
        assertEquals("Ввести «Маше»", found.shownDescriptions().get(2));
        assertEquals("отправь … …", found.skill().templateText());

        // Старый навык без переменных — как раньше, и без binding.
        SkillStore.Found morning = SkillStore.find("доброе утро").orElseThrow();
        assertNull(morning.binding());
        assertTrue(SkillStore.find("открой ютуб").isEmpty());
    }
}
