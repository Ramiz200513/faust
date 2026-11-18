package com.jarvis.agent;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Запуск «Фауста» вместе с Windows.
 *
 * <h2>Где</h2>
 * Обычная запись автозапуска ТЕКУЩЕГО пользователя:
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}, значение {@code Faust}.
 * Права администратора не нужны, другие пользователи ПК не затрагиваются; так же
 * делают Telegram, Discord и прочие программы. Запись видна и отключается в
 * «Диспетчер задач → Автозагрузка».
 *
 * <h2>Только для установленной программы</h2>
 * Записывается путь к {@code Faust.exe}, который создал jpackage: он сам передаёт
 * его в свойстве {@code jpackage.app-path}. При запуске из Gradle (разработка)
 * такого файла нет — автозапуск недоступен, и пункт меню скрыт.
 * Иначе в автозапуск попал бы путь к временной сборке, которая завтра исчезнет.
 *
 * <h2>Почему через WinAPI, а не reg.exe</h2>
 * Первая версия писала через {@code reg.exe /d "..."}. Проверка установленной
 * программы 13.11 показала: кавычки вокруг пути по дороге терялись (их съедает
 * разбор командной строки), и в реестр попадало {@code C:\...\Faust.exe --autostart}
 * без кавычек. С путём без пробелов это работает, а у «C:\Program Files\...» или
 * пользователя «Ivan Petrov» — нет. Прямой вызов WinAPI (JNA, Advapi32) кладёт
 * строку как есть, никакой разбор командной строки в нём не участвует.
 */
public final class Autostart {

    private Autostart() {}

    static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALUE = "Faust";
    /** Отметка в agent.properties: первый запуск установленной программы уже был. */
    static final String ASKED = "autostart.initialized";

    /** Путь к Faust.exe установленной программы или null, если запущены не из неё. */
    public static Path appExe() {
        String p = System.getProperty("jpackage.app-path");
        if (p == null || p.isBlank()) return null;
        Path exe = Paths.get(p);
        return Files.isRegularFile(exe) ? exe : null;
    }

    public static boolean available() {
        return appExe() != null && isWindows();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Есть ли запись автозапуска, указывающая на ЭТУ программу. */
    public static boolean isEnabled() {
        Path exe = appExe();
        return exe != null && command(exe).equalsIgnoreCase(read(RUN_KEY, VALUE));
    }

    /** Включить или выключить. @return получилось ли */
    public static boolean set(boolean on) {
        Path exe = appExe();
        if (exe == null || !isWindows()) return false;
        try {
            if (on) {
                write(RUN_KEY, VALUE, command(exe));
            } else {
                delete(RUN_KEY, VALUE);
            }
        } catch (RuntimeException e) {          // JNA сообщает об ошибках WinAPI исключениями
            System.err.println("автозапуск: " + e.getMessage());
            return false;
        }
        return isEnabled() == on;
    }

    /**
     * Строка автозапуска: путь в кавычках (в нём бывают пробелы) и флаг тихого
     * старта — при входе в Windows не показывать окон, только значок в трее.
     */
    static String command(Path exe) {
        return "\"" + exe + "\" " + AgentMain.FLAG_AUTOSTART;
    }

    /**
     * Первый запуск установленной программы: включить автозапуск по умолчанию
     * (агент полезен, только когда работает — иначе телефону некого звать) и
     * запомнить, что это уже сделано. Дальше решает человек пунктом в трее —
     * выключенное им заново не включится.
     */
    public static void initOnFirstRun() {
        if (!available() || AgentSettings.flag(ASKED, false)) return;
        boolean ok = set(true);
        try {
            AgentSettings.save(ASKED, "true");
        } catch (IOException e) {
            System.err.println("не удалось запомнить настройку автозапуска: " + e.getMessage());
        }
        System.err.println("автозапуск с Windows " + (ok ? "включён" : "включить не удалось"));
    }

    // --- реестр текущего пользователя (HKCU) -----------------------------------
    // Ключ — параметр, чтобы тест мог проверить запись на своём временном ключе,
    // не трогая настоящий автозапуск.

    static String read(String key, String value) {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, value)
                    ? Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, value) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void write(String key, String value, String data) {
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key)) {
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key);
        }
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, value, data);
    }

    static void delete(String key, String value) {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, value)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, value);
        }
    }
}
