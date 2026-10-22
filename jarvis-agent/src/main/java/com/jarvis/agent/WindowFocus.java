package com.jarvis.agent;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Вывести окно только что открытой программы на передний план.
 *
 * <h2>Зачем (найдено на живом прогоне 30.09)</h2>
 * «Открыть Telegram» при уже запущенном Telegram не делает его окно активным:
 * Windows не даёт фоновому процессу забирать фокус. На прогоне после шага
 * плана «Открыть Telegram» впереди остался Chrome, и исполнитель следующей
 * подзадачи увидел не то окно. В этот раз он догадался открыть Telegram ещё
 * раз, но мог бы и начать печатать в Chrome.
 *
 * <p>Поэтому после запуска код сам находит окно программы и выводит его
 * вперёд, а затем ПРОВЕРЯЕТ, что это получилось: исполнитель подзадачи
 * должен начинать работу в нужном окне, а не надеяться на это.
 */
public final class WindowFocus {

    /** Сколько ждать появления окна: холодный старт программы — до пары секунд. */
    private static final long WAIT_MS = 5_000;
    private static final long POLL_MS = 250;

    /** Функции user32, которых нет в готовом описании JNA. */
    private interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class);

        /**
         * Недокументированная, но много лет стабильная функция — ею пользуется
         * сама Windows при Alt+Tab. Её не касается запрет на «кражу фокуса»,
         * из-за которого SetForegroundWindow из фонового процесса часто молча
         * ничего не делает.
         */
        void SwitchToThisWindow(HWND hWnd, boolean fAltTab);

        /** Свёрнуто ли окно в панель задач — такое сначала нужно развернуть. */
        boolean IsIconic(HWND hWnd);
    }

    private WindowFocus() {}

    /**
     * Найти окно программы и сделать его активным.
     *
     * @param appName настоящее название из «Пуска» или словаря: «Telegram», «Блокнот», «Проводник»
     * @return заголовок окна, которое теперь на переднем плане; null — не нашли или не удалось
     */
    public static String bringToFront(String appName) {
        if (appName == null || appName.isBlank()) return null;
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            HWND hwnd = find(appName);
            if (hwnd != null && activate(hwnd)) return title(hwnd);
            sleep(POLL_MS);
        }
        return null;
    }

    /** Заголовок окна, которое сейчас на переднем плане. */
    public static String foregroundTitle() {
        HWND fg = User32.INSTANCE.GetForegroundWindow();
        return fg == null ? "" : title(fg);
    }

    /**
     * Программа активного окна — имя exe без расширения («telegram», «explorer»),
     * пусто, если это окно самого агента или процесс недоступен. Нужно записи
     * навыка: по нему видно, что человек переключился в другую программу.
     */
    public static String foregroundExe() {
        HWND fg = User32.INSTANCE.GetForegroundWindow();
        if (fg == null) return "";
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(fg, pid);
        if (pid.getValue() == (int) ProcessHandle.current().pid()) return "";
        String exe = exeName(pid.getValue());
        // Приложения из Microsoft Store (Калькулятор, Параметры, Фотографии) живут
        // в рамке ApplicationFrameHost: окно верхнего уровня принадлежит ей, а
        // настоящая программа — дочернее окно другого процесса. Берём его.
        if ("applicationframehost".equals(exe)) {
            int hostPid = pid.getValue();
            int[] appPid = {0};
            User32.INSTANCE.EnumChildWindows(fg, (child, data) -> {
                IntByReference p = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(child, p);
                if (p.getValue() != hostPid) {
                    appPid[0] = p.getValue();
                    return false;
                }
                return true;
            }, null);
            if (appPid[0] != 0) exe = exeName(appPid[0]);
        }
        return exe;
    }

    /** Браузеры: их вкладки называются как угодно, и по заголовку их окна не выбираем. */
    private static final List<String> BROWSERS = List.of("chrome", "msedge", "firefox", "browser", "opera", "brave");

    /**
     * Самое верхнее по Z-порядку видимое окно программы.
     *
     * <p>Сначала — по имени exe-файла: это надёжнее всего (Telegram.exe,
     * WhatsApp.exe), и так находится Telegram с открытым чатом, у которого в
     * заголовке «Избранное – (49529)», а не «Telegram». Если не нашли —
     * по заголовку: у Блокнота exe называется notepad.exe, а в заголовке
     * «— Блокнот». Но окна браузеров по заголовку не берём: вкладка
     * «Telegram Web – Google Chrome» — это не программа Telegram.
     */
    static HWND find(String appName) {
        String want = AppCatalog.norm(appName);
        String wantLatin = AppCatalog.translit(want);
        boolean wantBrowser = BROWSERS.stream().anyMatch(b -> want.contains(b) || wantLatin.contains(b));
        int ownPid = (int) ProcessHandle.current().pid();
        List<HWND> byTitle = new ArrayList<>();
        List<HWND> byExe = new ArrayList<>();

        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
            String t = title(hwnd);
            if (t.isBlank()) return true; // служебные окна без заголовка
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            if (pid.getValue() == ownPid) return true; // наши оверлеи и виджет
            String exe = exeName(pid.getValue());
            if (!exe.isEmpty() && (exe.contains(want) || exe.contains(wantLatin))) {
                byExe.add(hwnd);
                return true;
            }
            boolean isBrowser = BROWSERS.stream().anyMatch(exe::contains);
            String nt = AppCatalog.norm(t);
            if ((!isBrowser || wantBrowser) && (nt.contains(want) || nt.contains(wantLatin))) byTitle.add(hwnd);
            return true;
        }, Pointer.NULL);

        if (!byExe.isEmpty()) return byExe.get(0);
        return byTitle.isEmpty() ? null : byTitle.get(0);
    }

    /**
     * Сделать окно активным и проверить результат. Сначала обычный путь, при
     * отказе Windows — SwitchToThisWindow.
     */
    private static boolean activate(HWND hwnd) {
        User32 u = User32.INSTANCE;
        if (User32Extra.INSTANCE.IsIconic(hwnd)) u.ShowWindow(hwnd, WinUser.SW_RESTORE); // свёрнутое — развернуть
        u.SetForegroundWindow(hwnd);
        if (isForeground(hwnd)) return true;
        User32Extra.INSTANCE.SwitchToThisWindow(hwnd, true);
        sleep(150);
        return isForeground(hwnd);
    }

    private static boolean isForeground(HWND hwnd) {
        HWND fg = User32.INSTANCE.GetForegroundWindow();
        return fg != null && fg.equals(hwnd);
    }

    private static String title(HWND hwnd) {
        char[] buf = new char[512];
        int n = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
        // Некоторые программы ставят в начало заголовка невидимый знак
        // направления текста (U+200E) — он мешал бы сравнению.
        return new String(buf, 0, n).replace("\u200E", "").strip();
    }

    /** «C:\...\Telegram.exe» -> «telegram»; пусто, если процесс недоступен. */
    private static String exeName(int pid) {
        try {
            return ProcessHandle.of(pid)
                    .flatMap(h -> h.info().command())
                    .map(c -> c.substring(c.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT).replace(".exe", ""))
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
