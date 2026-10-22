package com.jarvis.agent.plan;

import com.jarvis.agent.vision.UiaElement;
import com.jarvis.agent.vision.UiaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Проверка результата подзадачи — файлом на диске и «взглядом» модели на окно (поддельные). */
class ResultVerifierTest {

    @TempDir Path dir;

    private final List<String> prompts = new ArrayList<>();

    private ResultVerifier verifier(String llmAnswer, UiaSnapshot.Result window) {
        return new ResultVerifier(p -> { prompts.add(p); return llmAnswer; }, () -> window);
    }

    private static UiaSnapshot.Result telegramWindow() {
        return new UiaSnapshot.Result("Избранное – (49529)", List.of(
                new UiaElement(1, "edit", "Сообщение...", 0, 0, 100, 30, false, true, "", true),
                new UiaElement(2, "list item", "Hello-World-master.zip, 351 Б, 21:54", 0, 0, 100, 30, false, true, "", false),
                new UiaElement(3, "button", "Отправить", 0, 0, 100, 30, false, true, "", false)), 100);
    }

    @Test
    void file_present_confirms() throws Exception {
        Path f = Files.writeString(dir.resolve("курс.txt"), "84,43");
        PlanStep step = PlanStep.screen("сохрани курс").withChecks(null, f.toString());

        StepVerifier.Check c = verifier("{}", null).verify(step, "сохранено");

        assertEquals(Boolean.TRUE, c.ok(), c.reason());
        assertTrue(prompts.isEmpty(), "для файла модель не нужна");
    }

    @Test
    void file_inNestedFolder_counts() throws Exception {
        // Архив распаковался со своей папкой: README не в ФаустТест, а в ФаустТест\Hello-World-master.
        Path nested = Files.createDirectories(dir.resolve("Hello-World-master"));
        Files.writeString(nested.resolve("README"), "Hello");
        PlanStep step = PlanStep.screen("распакуй").withChecks(null, dir.resolve("README").toString());

        StepVerifier.Check c = verifier("{}", null).verify(step, "распаковано");

        assertEquals(Boolean.TRUE, c.ok(), c.reason());
    }

    @Test
    void file_missing_refutes_evenIfModelWouldSayYes() {
        PlanStep step = PlanStep.screen("сохрани курс")
                .withChecks("заголовок окна — курс.txt", dir.resolve("нет.txt").toString());

        StepVerifier.Check c = verifier("{\"ok\":true,\"reason\":\"вроде сохранено\"}", telegramWindow())
                .verify(step, "сохранено");

        assertEquals(Boolean.FALSE, c.ok(), "файла нет — модель даже не спрашиваем");
        assertTrue(prompts.isEmpty());
    }

    @Test
    void window_check_asksAnIndependentModel() {
        PlanStep step = PlanStep.screen("отправь zip в Избранное")
                .withChecks("в чате Избранное последнее сообщение — файл Hello-World-master.zip", null);

        StepVerifier.Check c = verifier("{\"ok\":true,\"reason\":\"в списке есть сообщение с файлом\"}",
                telegramWindow()).verify(step, "отправлено");

        assertEquals(Boolean.TRUE, c.ok());
        String p = prompts.get(0);
        assertTrue(p.contains("Не верь сообщению исполнителя"), "проверяющий не должен верить исполнителю");
        assertTrue(p.contains("Hello-World-master.zip, 351 Б"), "проверяющий видит элементы окна");
    }

    @Test
    void window_refuted() {
        PlanStep step = PlanStep.screen("отправь").withChecks("видно отправленный файл", null);
        StepVerifier.Check c = verifier("```json\n{\"ok\":false,\"reason\":\"поле сообщения пустое, файла нет\"}\n```",
                telegramWindow()).verify(step, "отправлено");
        assertEquals(Boolean.FALSE, c.ok());
        assertEquals("поле сообщения пустое, файла нет", c.reason());
    }

    @Test
    void unusableWindow_isUnknown_notFailure() {
        PlanStep step = PlanStep.screen("отправь").withChecks("видно файл", null);
        StepVerifier.Check c = verifier("{\"ok\":false}", UiaSnapshot.Result.EMPTY).verify(step, "готово");
        assertNull(c.ok(), "не видно — не значит «не сделано»");
    }

    @Test
    void browserAndUncheckedSteps_areNotChecked() {
        assertNull(verifier("{}", telegramWindow()).verify(PlanStep.browser("найди курс"), "84,43"));
        assertNull(verifier("{}", telegramWindow()).verify(PlanStep.screen("что-то"), "готово"));
    }

    @Test
    void folderAliases_areResolved() {
        assertTrue(ResultVerifier.resolve("Рабочий стол\\курс.txt").orElseThrow().toString().endsWith("Desktop\\курс.txt"));
        assertTrue(ResultVerifier.resolve("Документы/ФаустТест/a.txt").orElseThrow().toString().endsWith("Documents\\ФаустТест\\a.txt"));
        assertTrue(ResultVerifier.resolve("Документы\\..\\..\\secret.txt").isEmpty(), "выйти из папки нельзя");
    }
}
