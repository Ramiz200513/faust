package com.jarvis.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Агент как программа для Windows: одна копия, автозапуск, адрес сервера из сборки.
 * Настоящий автозапуск тесты НЕ трогают: запись в реестр проверяется на своём
 * временном ключе HKCU\Software\Faust-test-autostart, который тест сам удаляет.
 */
class DesktopAppTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clean() {
        SingleInstance.release();
        System.clearProperty("faust.lockFile");
        System.clearProperty("faust.defaultRelayUrl");
        System.clearProperty("jpackage.app-path");
    }

    @Test
    void singleInstance_holdsOsLock_whileAlive() throws IOException {
        Path lock = tmp.resolve("agent.lock");
        System.setProperty("faust.lockFile", lock.toString());

        assertTrue(SingleInstance.acquire(), "первый агент занимает место");
        assertTrue(SingleInstance.acquire(), "повторный вызов в том же процессе — не «второй агент»");
        try (FileChannel other = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            // Внутри одной JVM занятая блокировка видна как исключение; другой процесс получил бы null.
            assertThrows(OverlappingFileLockException.class, other::tryLock, "файл заблокирован, пока агент жив");
        }
        SingleInstance.release();
        try (FileChannel other = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            assertNotNull(other.tryLock(), "агент завершился — место свободно");
        }
    }

    @Test
    void autostart_onlyForInstalledApp_andQuotesPath() throws IOException {
        assertFalse(Autostart.available(), "запуск не из Faust.exe — автозапуск недоступен, реестр не трогаем");
        assertFalse(Autostart.set(true));

        Path exe = Files.createFile(tmp.resolve("Faust.exe"));
        assertEquals("\"" + exe + "\" --autostart", Autostart.command(exe),
                "путь в кавычках (Program Files с пробелом) и тихий старт без окон");
    }

    /**
     * Запись в реестр — на СВОЁМ временном ключе HKCU\Software\Faust-test-autostart,
     * настоящий автозапуск не трогается. Главное: путь с пробелами и кавычками
     * доходит до реестра ровно таким, каким его собрали (13.11 через reg.exe кавычки терялись).
     */
    @Test
    void registry_keepsQuotedPathWithSpaces_exactly() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        String key = "Software\\Faust-test-autostart";
        String data = Autostart.command(Path.of("C:\\Program Files\\Иван Петров\\Faust\\Faust.exe"));
        try {
            Autostart.write(key, "Faust", data);
            assertEquals("\"C:\\Program Files\\Иван Петров\\Faust\\Faust.exe\" --autostart", Autostart.read(key, "Faust"));
            Autostart.delete(key, "Faust");
            assertNull(Autostart.read(key, "Faust"));
        } finally {
            com.sun.jna.platform.win32.Advapi32Util.registryDeleteKey(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key);
        }
    }

    @Test
    void defaultRelay_fromBuild_otherwiseLocal() throws IOException {
        Path props = tmp.resolve("agent.properties");
        assertEquals("ws://localhost:8080/ws", AgentConfig.resolve(new String[0], props).relayUrl(),
                "из Gradle — релей на этом ПК, как раньше");

        System.setProperty("faust.defaultRelayUrl", "wss://faust.example/ws");
        assertEquals("wss://faust.example/ws", AgentConfig.resolve(new String[0], props).relayUrl(),
                "установленная программа — сервер, вписанный при сборке");

        Files.writeString(props, "relayUrl=ws://192.168.1.5:8080/ws\n");
        assertEquals("ws://192.168.1.5:8080/ws", AgentConfig.resolve(new String[0], props).relayUrl(),
                "свой адрес в agent.properties важнее вписанного при сборке");
    }
}
