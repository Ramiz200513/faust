package com.jarvis.agent.vision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Память траекторий в программах: подстановка свежего скачанного файла и
 * раздельное хранение браузерных и «оконных» траекторий. Файл памяти в тестах
 * временный (faust.trajectoriesFile задаёт build.gradle.kts).
 */
class UiaMemoryTest {

    private static final String CONTEXT_FIRST = "Уже сделано в этой задаче:\n  1. Скачай архив — готово; "
            + "скачан файл C:\\Users\\user\\Downloads\\Hello-World-master (6).zip (351 Б)";
    private static final String CONTEXT_SECOND = "Уже сделано в этой задаче:\n  1. Скачай архив — готово; "
            + "скачан файл C:\\Users\\user\\Downloads\\Hello-World-master (7).zip (351 Б)";

    @Test
    void downloadedPath_isRememberedAsPlaceholder_andReplacedWithTheNewOne() {
        String recorded = ScreenAgent.withPlaceholder("C:\\Users\\user\\Downloads\\Hello-World-master (6).zip", CONTEXT_FIRST);
        assertEquals(ScreenAgent.DOWNLOADED, recorded, "путь из факта скачивания — в метку");

        // В следующий раз скачался уже «(7)» — повтор должен вставить именно его, а не старый файл.
        assertEquals("C:\\Users\\user\\Downloads\\Hello-World-master (7).zip",
                ScreenAgent.resolvePlaceholder(recorded, CONTEXT_SECOND));
    }

    @Test
    void extractSpec_keepsDestination() {
        String rec = ScreenAgent.withPlaceholder(
                "C:\\Users\\user\\Downloads\\Hello-World-master (6).zip|Документы\\ФаустТест", CONTEXT_FIRST);
        assertEquals(ScreenAgent.DOWNLOADED + "|Документы\\ФаустТест", rec);
        assertEquals("C:\\Users\\user\\Downloads\\Hello-World-master (7).zip|Документы\\ФаустТест",
                ScreenAgent.resolvePlaceholder(rec, CONTEXT_SECOND));
    }

    @Test
    void placeholderWithoutFact_meansDoNotReplay() {
        assertNull(ScreenAgent.resolvePlaceholder(ScreenAgent.DOWNLOADED, "нет фактов"));
        // Обычный путь (не из факта) остаётся как был — его и повторяем.
        String readme = "C:\\Users\\user\\Documents\\ФаустТест\\Hello-World-master\\README";
        assertEquals(readme, ScreenAgent.withPlaceholder(readme, CONTEXT_FIRST));
        assertEquals(readme, ScreenAgent.resolvePlaceholder(readme, CONTEXT_SECOND));
    }

    @Test
    void browserAndWindowTrajectories_doNotMix() {
        String goal = "тестовая цель памяти " + System.nanoTime();
        TrajectoryCache.recordUia(goal, List.of(
                new TrajectoryCache.Step("click", "button", "uia", "Отправить", -1, null, null, null, false)), 1000, 3);

        assertTrue(TrajectoryCache.findUia(goal).isPresent());
        assertTrue(TrajectoryCache.find(goal).isEmpty(), "браузер не должен «открывать» траекторию программы");

        TrajectoryCache.forgetGoal(goal);
        assertTrue(TrajectoryCache.findUia(goal).isEmpty());
    }

    @Test
    void phrases_areHuman() {
        assertEquals("Нажимаю «Избранное»",
                ActionPhrases.describe("click", null, "Избранное, Ramiz, В сети, Закреплено, вчера в 10:40"));
        assertEquals("Прикрепляю файл report.pdf", ActionPhrases.describe("paste_file", "C:\\x\\report.pdf", null));
        assertEquals("Перехожу на github.com", ActionPhrases.describe("navigate", "https://github.com/a", null));
    }
}
