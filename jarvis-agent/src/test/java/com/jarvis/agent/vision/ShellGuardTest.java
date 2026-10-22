package com.jarvis.agent.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Запрет на терминалы: то, что агент сделал на прогоне 30.09, должно быть
 * невозможно, а обычная работа (пути, имена файлов, сообщения) — не задета.
 */
class ShellGuardTest {

    @Test
    void terminalsAndSystemTools_areForbidden() {
        assertTrue(ShellGuard.isForbiddenApp("PowerShell"));          // ровно это открыл агент
        assertTrue(ShellGuard.isForbiddenApp("Windows PowerShell ISE"));
        assertTrue(ShellGuard.isForbiddenApp("Командная строка"));
        assertTrue(ShellGuard.isForbiddenApp("cmd"));
        assertTrue(ShellGuard.isForbiddenApp("Терминал"));
        assertTrue(ShellGuard.isForbiddenApp("Редактор реестра"));
    }

    @Test
    void ordinaryPrograms_areAllowed() {
        for (String app : new String[]{"Telegram", "WhatsApp", "Проводник", "Блокнот", "Калькулятор", "Paint", "Google Chrome"}) {
            assertFalse(ShellGuard.isForbiddenApp(app), app);
        }
    }

    @Test
    void commands_areRecognized() {
        // Дословно команда с прогона 30.09.
        assertTrue(ShellGuard.looksLikeCommand(
                "Expand-Archive -Path 'C:\\Users\\user\\Downloads\\Hello-World-master (7).zip' -DestinationPath 'C:\\x' -Force"));
        assertTrue(ShellGuard.looksLikeCommand("powershell -c Remove-Item C:\\*"));
        assertTrue(ShellGuard.looksLikeCommand("cmd /c del /q C:\\temp"));
        assertTrue(ShellGuard.looksLikeCommand("C:\\Users\\user\\Downloads\\setup.exe"));
    }

    @Test
    void ordinaryText_isNotACommand() {
        for (String t : new String[]{"буду в 7", "C:\\Users\\user\\Downloads", "курс.txt", "84,43 руб.",
                "ФаустТест", "Список покупок", "отчёт.pdf", "Hello-World-master.zip"}) {
            assertFalse(ShellGuard.looksLikeCommand(t), t);
        }
    }

    @Test
    void terminalWindows_areRecognized() {
        assertTrue(ShellGuard.isShellWindow("Windows PowerShell"));
        assertTrue(ShellGuard.isShellWindow("Администратор: Командная строка"));
        assertFalse(ShellGuard.isShellWindow("Избранное – (49589)"));
        assertFalse(ShellGuard.isShellWindow("Главная — проводник"));
    }
}
