package com.jarvis.agent;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Window;
import java.util.Locale;

/**
 * Возможности Windows, которых нет в Swing, — через JNA.
 *
 * <h2>Что такое JNA и HWND</h2>
 * JNA (Java Native Access) позволяет вызвать функцию из системной DLL прямо из
 * Java, описав её обычным интерфейсом. У каждого окна в Windows есть «номер»
 * — HWND. Swing его не показывает, но JNA умеет достать его у любого окна
 * ({@link Native#getComponentPointer}). Получив HWND, можно менять у окна
 * флаги, о которых Swing ничего не знает.
 *
 * <h2>Флаги (extended styles), которые мы ставим</h2>
 * <ul>
 *   <li>{@code WS_EX_LAYERED} — окно рисуется с прозрачностью (для Java-окон с
 *       прозрачным фоном этот флаг уже стоит, мы его просто сохраняем);</li>
 *   <li>{@code WS_EX_TRANSPARENT} — «прозрачное для мыши»: клики проходят
 *       СКВОЗЬ окно в программу под ним. Без этого Robot кликал бы по нашему
 *       оверлею, а не по цели;</li>
 *   <li>{@code WS_EX_TOOLWINDOW} — окно не появляется в панели задач и в Alt+Tab;</li>
 *   <li>{@code WS_EX_NOACTIVATE} — клик по окну не делает его активным и не
 *       уводит фокус из программы, в которую агент печатает.</li>
 * </ul>
 *
 * <h2>Важно: вызывать ПОСЛЕ setVisible(true)</h2>
 * Настоящее окно Windows (а значит, и HWND) создаётся только при показе.
 * До этого менять нечего.
 *
 * <h2>Если что-то не вышло</h2>
 * Каждый метод возвращает true/false и никогда не бросает исключений: не
 * загрузилась JNA, другая ОС, старая Windows — оверлей просто работает без
 * этой возможности, а вызывающий код (см. {@link AgentOverlays}) подстрахует
 * по-своему. Агент из-за украшений падать не должен.
 */
public final class WinOverlayUtil {

    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_NOACTIVATE = 0x08000000;

    /** «Не показывать это окно ни в каком захвате экрана» (Windows 10 2004+). */
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * Функции SetWindowDisplayAffinity в готовом {@code User32} из jna-platform
     * нет — объявляем её сами. Имя метода должно совпадать с именем функции в
     * user32.dll, а типы параметров — с её сигнатурой: так JNA понимает, что
     * вызывать и как передать аргументы.
     */
    private interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean SetWindowDisplayAffinity(HWND hWnd, int dwAffinity);
    }

    private WinOverlayUtil() {}

    /**
     * Окно становится прозрачным для мыши: клики проходят сквозь него.
     * Для чисто декоративных оверлеев (затемнение, рамка, ореол курсора).
     */
    public static boolean makeClickThrough(Window w) {
        return addExStyle(w, WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                "click-through");
    }

    /**
     * По окну МОЖНО кликнуть, но фокус оно не забирает. Для окон с кнопкой
     * «Стоп»: WS_EX_TRANSPARENT тут нельзя — кнопка перестала бы нажиматься.
     */
    public static boolean makeNoActivate(Window w) {
        return addExStyle(w, WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, "no-activate");
    }

    /**
     * Окно не попадает на снимки экрана: ни в Robot.createScreenCapture, ни в
     * стрим на телефон. Vision-агент его просто не видит.
     *
     * Побочный эффект, о котором надо помнить: окно не увидят и OBS, Zoom,
     * «Ножницы» — см. флаг overlay.excludeFromCapture в {@link AgentSettings}.
     */
    public static boolean excludeFromCapture(Window w) {
        if (!WINDOWS) return false;
        try {
            HWND hwnd = hwnd(w);
            if (hwnd == null) return false;
            boolean ok = User32Ex.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
            if (!ok) {
                // Так бывает на Windows старше 10 версии 2004: функция есть, а
                // этого режима она ещё не знает.
                System.err.println("оверлей: SetWindowDisplayAffinity не сработал — окно будет видно на снимках");
            }
            return ok;
        } catch (Throwable t) {
            // Throwable, а не Exception: если JNA не смогла загрузить свою
            // нативную библиотеку, прилетает UnsatisfiedLinkError — это Error.
            System.err.println("оверлей: excludeFromCapture недоступен (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Добавить флаги к уже имеющимся (побитовое ИЛИ), ничего не сбрасывая. */
    private static boolean addExStyle(Window w, int flags, String what) {
        if (!WINDOWS) return false;
        try {
            HWND hwnd = hwnd(w);
            if (hwnd == null) return false;
            int ex = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, ex | flags);
            // Проверяем, что флаги правда встали, а не верим на слово.
            int after = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
            return (after & flags) == flags;
        } catch (Throwable t) {
            System.err.println("оверлей: " + what + " недоступен (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** HWND окна, или null, если окно ещё не показано (у него пока нет HWND). */
    private static HWND hwnd(Window w) {
        if (w == null || !w.isDisplayable()) return null;
        return new HWND(Native.getComponentPointer(w));
    }
}
