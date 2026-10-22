package com.jarvis.agent;

import com.jarvis.agent.vision.TrajectoryCache;
import com.jarvis.agent.vision.UiaElement;
import com.jarvis.agent.vision.UiaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Три способа научить агента составной задаче — то, что проверяется без экрана:
 * какой навык просят фразой, как запись показом понимает клик, и как агент
 * запускает доверенный скрипт (только из своей папки и только по имени).
 *
 * <p>Файлы навыков и скриптов — во временной папке сборки (см. build.gradle.kts),
 * настоящие файлы человека тесты не трогают.
 */
class SkillsTest {

    private static TrajectoryCache.Step click(String label) {
        return new TrajectoryCache.Step("click", "кнопка", "uia", label, -1, null, null, null, false);
    }

    @BeforeEach
    void clean() throws Exception {
        Files.deleteIfExists(Path.of(System.getProperty("faust.skillsFile")));
        Path dir = ScriptCatalog.folder();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.delete(p);
            }
        }
    }

    // --- навыки, выученные показом ----------------------------------------------

    @Test
    void skill_isFound_byName_prefix_orTrigger_only_exactly() {
        SkillStore.save(new SkillStore.Skill("утро", List.of("доброе утро", "начинаем работу"),
                List.of(click("Пуск")), List.of("Нажать «Пуск»"), 1, 1000));

        assertTrue(SkillStore.match("утро").isPresent());
        assertTrue(SkillStore.match("Навык утро").isPresent());
        assertTrue(SkillStore.match("выполни навык Утро!").isPresent(), "регистр и знаки не важны");
        assertTrue(SkillStore.match("доброе утро").isPresent());
        assertTrue(SkillStore.match("Начинаем работу.").isPresent());

        // Навык делает действия на ПК без модели: «похоже на» — не совпадение.
        assertTrue(SkillStore.match("утро в деревне").isEmpty());
        assertTrue(SkillStore.match("доброе").isEmpty());
        assertTrue(SkillStore.match("навык вечер").isEmpty());
    }

    @Test
    void skill_withSameName_isReplaced_andCanBeRemoved() {
        SkillStore.save(new SkillStore.Skill("утро", List.of(), List.of(click("A")), List.of("A"), 1, 1));
        SkillStore.save(new SkillStore.Skill("Утро", List.of(), List.of(click("B"), click("C")), List.of("B", "C"), 2, 1));

        assertEquals(1, SkillStore.all().size(), "научили заново — старый навык заменён");
        assertEquals(2, SkillStore.match("утро").orElseThrow().steps().size());
        assertEquals("C", SkillStore.match("утро").orElseThrow().steps().get(1).label(),
                "шаги переживают сохранение в JSON");

        assertTrue(SkillStore.remove("УТРО"));
        assertTrue(SkillStore.match("утро").isEmpty());
    }

    // --- запись показом -----------------------------------------------------------

    @Test
    void click_isMatchedToTheSmallestElementUnderThePointer() {
        UiaElement window = new UiaElement(0, "панель", "Окно", 0, 0, 800, 600, false, true, null, false);
        UiaElement toolbar = new UiaElement(1, "панель инструментов", "Панель", 0, 0, 800, 50, false, true, null, false);
        UiaElement button = new UiaElement(2, "кнопка", "Отправить", 700, 10, 80, 30, false, true, null, false);
        UiaSnapshot.Result snap = new UiaSnapshot.Result("Окно", List.of(window, toolbar, button), 5);

        assertEquals("Отправить", DemoRecorder.elementAt(snap, 710, 20).name(), "кнопка, а не панель вокруг");
        assertEquals("Панель", DemoRecorder.elementAt(snap, 100, 20).name());
        assertEquals("Окно", DemoRecorder.elementAt(snap, 100, 300).name());
        assertNull(DemoRecorder.elementAt(snap, 900, 300), "мимо окна — шаг не записывается");
    }

    @Test
    void recordedProgram_isNamedTheWayAppCatalogFindsIt() {
        assertEquals("Проводник", DemoRecorder.appName("explorer"));
        assertEquals("Telegram", DemoRecorder.appName("telegram"));
        assertEquals("Блокнот", DemoRecorder.appName("notepad"));
        assertEquals("Code", DemoRecorder.appName("code"), "незнакомая программа — по имени процесса");
    }

    // --- доверенные скрипты -------------------------------------------------------

    @Test
    void scriptRequest_isRecognized_withQuotesAndPunctuation() {
        assertEquals("сервер диплома", ScriptCatalog.requestedName("запусти скрипт сервер диплома"));
        assertEquals("сервер диплома", ScriptCatalog.requestedName("Выполни скрипт «сервер диплома»."));
        assertEquals("сборка", ScriptCatalog.requestedName("скрипт сборка"));
        assertNull(ScriptCatalog.requestedName("запусти блокнот"));
        assertNull(ScriptCatalog.requestedName("запусти скрипт "));
    }

    @Test
    void script_runs_onlyFromItsFolder_byName() throws Exception {
        Path dir = ScriptCatalog.folder();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("привет.bat"), "@echo off\r\necho hello-from-script\r\n", StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve("заметки.txt"), "не скрипт", StandardCharsets.UTF_8);

        assertEquals(List.of("привет"), ScriptCatalog.names(), "только .bat/.cmd/.ps1");
        assertEquals("привет", ScriptCatalog.resolve("Привет").orElseThrow());

        String out = ScriptCatalog.run("привет");
        assertTrue(out.contains("выполнен") && out.contains("hello-from-script"), out);

        // Ни пути, ни аргументов: «..\\что-то» — не название скрипта из папки.
        assertThrows(IllegalArgumentException.class, () -> ScriptCatalog.run("..\\..\\Windows\\System32\\cmd"));
        assertThrows(IllegalArgumentException.class, () -> ScriptCatalog.run("заметки"));
    }

    @Test
    void failingScript_isAnError_notASuccess() throws Exception {
        Path dir = ScriptCatalog.folder();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("сломан.cmd"), "@echo off\r\necho oops\r\nexit /b 3\r\n", StandardCharsets.US_ASCII);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ScriptCatalog.run("сломан"));
        assertTrue(e.getMessage().contains("кодом 3") && e.getMessage().contains("oops"), e.getMessage());
    }

    @Test
    void scriptOutput_isReadable_inBothConsoleEncodings() {
        String text = "Сервер работает";
        assertEquals(text, ScriptCatalog.decodeLine(text.getBytes(StandardCharsets.UTF_8)), "после chcp 65001");
        assertEquals(text, ScriptCatalog.decodeLine(text.getBytes(java.nio.charset.Charset.forName("Cp866"))),
                "обычная русская консоль");
    }
}
