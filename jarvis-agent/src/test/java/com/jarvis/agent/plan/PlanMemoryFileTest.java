package com.jarvis.agent.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Файл памяти планов: запомнить, найти по той же фразе, забыть. */
class PlanMemoryFileTest {

    @TempDir Path dir;

    @Test
    void saveFindForget() {
        PlanMemory.File m = new PlanMemory.File(dir.resolve("plans.json"));
        List<PlanStep> steps = List.of(PlanStep.browser("скачай архив"), PlanStep.openApp("Telegram"),
                PlanStep.screen("отправь файл в Избранное").withChecks("видно файл", null));

        m.save("Скачай архив и отправь в Избранное!", steps, "Отправить?", 36_000);

        // Регистр и знаки препинания не мешают узнать ту же просьбу.
        PlanMemory.Saved s = m.find("скачай архив и отправь в избранное").orElseThrow();
        assertEquals(steps, s.steps(), "план возвращается вместе с проверками");
        assertEquals(36_000, s.baselineMs());

        m.forget("скачай архив и отправь в избранное");
        assertTrue(m.find("скачай архив и отправь в избранное").isEmpty());
    }
}
