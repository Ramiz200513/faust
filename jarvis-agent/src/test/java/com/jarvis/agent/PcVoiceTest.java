package com.jarvis.agent;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор ответа PowerShell-скрипта голоса — без запуска самого PowerShell. */
class PcVoiceTest {

    private static String parse(String output) throws Exception {
        return PcVoice.readVoiceLine(new BufferedReader(new StringReader(output)));
    }

    @Test
    void serviceLinesBeforeAnswer_areSkipped() throws Exception {
        // Ровно то, что пришло при замере 19.11: прогресс Add-Type («#< CLIXML») раньше ответа.
        assertEquals("VOICE Microsoft Irina Desktop", parse("#< CLIXML\r\nVOICE Microsoft Irina Desktop\r\n"));
    }

    @Test
    void sileroCommand_onlyWhenEverythingIsInPlace(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        assertNull(PcVoice.sileroCommand(null, "eugene"), "корня проекта нет (установленная программа)");
        assertNull(PcVoice.sileroCommand(root, "eugene"), "пустая папка — Silero нет, будет голос Windows");

        java.nio.file.Path python = root.resolve("jarvis-ml/.venv/Scripts/python.exe");
        java.nio.file.Path script = root.resolve("jarvis-agent/scripts/silero_voice.py");
        java.nio.file.Path model = root.resolve("jarvis-agent/tools/silero/v4_ru.pt");
        for (java.nio.file.Path p : java.util.List.of(python, script)) {
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, "x");
        }
        assertNull(PcVoice.sileroCommand(root, "eugene"), "без модели — тоже нет");

        java.nio.file.Files.createDirectories(model.getParent());
        java.nio.file.Files.writeString(model, "x");
        assertEquals(java.util.List.of(python.toString(), "-I", script.toString(), model.toString(), "eugene"),
                PcVoice.sileroCommand(root, "eugene"));
        assertEquals("eugene", PcVoice.sileroCommand(root, "evil; rm -rf").get(4),
                "имя голоса из настроек — только латинские буквы, иначе голос по умолчанию");
        assertEquals("aidar", PcVoice.sileroCommand(root, "aidar").get(4));
    }

    @Test
    void noVoiceAndEmptyOutput() throws Exception {
        assertEquals("NOVOICE", parse("NOVOICE\n"));
        assertNull(parse(""), "процесс закрылся сразу — голоса нет");
        assertNull(parse("мусор\n".repeat(30) + "VOICE поздно\n"), "ответ дальше 20 строк не ищем");
    }
}
