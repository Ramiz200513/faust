package com.jarvis.agent;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Что делает ЧЕЛОВЕК мышью и клавиатурой — общий для записи показом ({@link DemoRecorder})
 * и режима наставника ({@code vision.MentorSession}).
 *
 * <h2>Как</h2>
 * Низкоуровневые «ловушки» Windows (WH_MOUSE_LL, WH_KEYBOARD_LL) в отдельном потоке с
 * циклом сообщений — так требует Windows. Сама ловушка только передаёт событие дальше:
 * долго думать в ней нельзя, иначе у человека начнёт тормозить мышь. Получатель
 * ({@code sink}) должен быть быстрым — обычно кладёт событие в очередь.
 *
 * <h2>Только человек</h2>
 * Клики и клавиши самого агента (Robot) Windows помечает флагом «искусственный ввод»
 * (INJECTED) — они отбрасываются. Это важно и для записи (агент не попадает в навык), и для
 * наставника: когда человек сказал «сделай сам» и шаг сделал агент, этот клик не
 * засчитывается как «человек нажал». Исключение — {@code allowInjected} для самопроверки
 * записи (DemoSelfTest), где «человека» изображает Robot.
 *
 * <h2>Что НЕ передаётся</h2>
 * Символы клавиш: это был бы кейлоггер. Только «человек печатает», имена служебных клавиш
 * (Enter, Esc, стрелки) и СОЧЕТАНИЯ с Ctrl / Alt / Win («ctrl+s», «win+plus», «alt+tab»),
 * а Shift — только со служебной клавишей («shift+tab»): Shift+буква — это заглавная буква,
 * то есть текст, и она остаётся «человек печатает».
 *
 * <p>До 23.11 сочетания с Win и Alt не передавались совсем — и наставник не мог засчитать шаг
 * «нажмите Win + плюс» (живой урок 19.11: экранную лупу Gemini учил включать именно так).
 * Запись показом получает события через {@link #legacyCombo} — для неё всё как раньше.
 *
 * <p>Вынесено из DemoRecorder 18.11 без изменения поведения записи.
 */
public final class HumanInput {

    /** Событие от человека. Координаты клика — ФИЗИЧЕСКИЕ пиксели экрана (так их отдаёт Windows). */
    public sealed interface Event permits Click, Key, Typing, Wheel {
        long atMs();
    }

    public record Click(int x, int y, long atMs) implements Event {}

    /** Служебная клавиша или сочетание: «enter», «esc», «ctrl+s». */
    public record Key(String combo, long atMs) implements Event {}

    /** Человек печатает (какой символ — не сообщается намеренно). */
    public record Typing(long atMs) implements Event {}

    /** Колесо мыши: отрицательное — вниз (как в Windows: 120 на «щелчок» колеса). */
    public record Wheel(int delta, long atMs) implements Event {}

    static final int WM_LBUTTONDOWN = 0x0201;
    static final int WM_MOUSEWHEEL = 0x020A;
    static final int WM_KEYDOWN = 0x0100;
    static final int WM_SYSKEYDOWN = 0x0104;
    static final int WM_QUIT = 0x0012;
    static final int LLMHF_INJECTED = 0x01;
    static final int LLKHF_INJECTED = 0x10;
    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;
    private static final int VK_LWIN = 0x5B;
    private static final int VK_RWIN = 0x5C;

    /**
     * Имена клавиш, которые бывают только В СОЧЕТАНИИ (Win+плюс, Ctrl+минус, Alt+F4, Win+пробел).
     * Без модификатора «плюс» или пробел — это просто текст, и их имя не передаётся.
     */
    private static final Map<Integer, String> COMBO_KEYS = Map.ofEntries(
            Map.entry(0xBB, "plus"), Map.entry(0x6B, "plus"),       // «=/+» в основном ряду и «+» на цифровом блоке
            Map.entry(0xBD, "minus"), Map.entry(0x6D, "minus"),
            Map.entry(0x20, "space"), Map.entry(0x08, "backspace"));

    /** Клавиши, которые передаются по имени (а не как «ввод текста»). */
    static final Map<Integer, String> NAMED_KEYS = Map.ofEntries(
            Map.entry(0x0D, "enter"), Map.entry(0x1B, "esc"), Map.entry(0x09, "tab"),
            Map.entry(0x2E, "delete"), Map.entry(0x71, "f2"), Map.entry(0x74, "f5"),
            Map.entry(0x26, "up"), Map.entry(0x28, "down"), Map.entry(0x25, "left"), Map.entry(0x27, "right"),
            Map.entry(0x24, "home"), Map.entry(0x23, "end"), Map.entry(0x21, "pageup"), Map.entry(0x22, "pagedown"));

    private final Consumer<Event> sink;
    private final boolean allowInjected;

    private volatile boolean running;
    private volatile int hookThreadId;
    private Thread hookThread;
    // Ссылки на колбэки ОБЯЗАНЫ жить всё время работы: иначе сборщик мусора
    // удалит их, а Windows продолжит вызывать — и процесс упадёт.
    private WinUser.LowLevelMouseProc mouseProc;
    private WinUser.LowLevelKeyboardProc keyProc;
    private WinUser.HHOOK mouseHook;
    private WinUser.HHOOK keyHook;

    /**
     * @param sink          получатель событий (вызывается из потока ловушек — быстро!)
     * @param allowInjected принимать ли ввод Robot (только для самопроверки записи)
     */
    public HumanInput(Consumer<Event> sink, boolean allowInjected) {
        this.sink = sink;
        this.allowInjected = allowInjected;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        hookThread = new Thread(this::hookLoop, "human-input-hooks");
        hookThread.setDaemon(true);
        hookThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (hookThreadId != 0) {
            // Разбудить цикл сообщений ловушек, чтобы он снял их и завершился.
            User32.INSTANCE.PostThreadMessage(hookThreadId, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        }
    }

    private void hookLoop() {
        hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        WinDef.HMODULE module = Kernel32.INSTANCE.GetModuleHandle(null);

        mouseProc = (nCode, wParam, info) -> {
            int msg = wParam.intValue();
            if (nCode >= 0 && (allowInjected || (info.flags & LLMHF_INJECTED) == 0)) {
                long now = System.currentTimeMillis();
                if (msg == WM_LBUTTONDOWN) {
                    sink.accept(new Click(info.pt.x, info.pt.y, now));
                } else if (msg == WM_MOUSEWHEEL) {
                    // Поворот колеса — старшее слово mouseData со знаком.
                    sink.accept(new Wheel((short) (info.mouseData >>> 16), now));
                }
            }
            return User32.INSTANCE.CallNextHookEx(mouseHook, nCode, wParam,
                    new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        keyProc = (nCode, wParam, info) -> {
            int msg = wParam.intValue();
            if (nCode >= 0 && (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN)
                    && (allowInjected || (info.flags & LLKHF_INJECTED) == 0)) {
                boolean ctrl = down(VK_CONTROL);
                boolean alt = down(VK_MENU);
                boolean shift = down(VK_SHIFT);
                boolean win = down(VK_LWIN) || down(VK_RWIN);
                Event e = classifyKey(info.vkCode, ctrl, alt, shift, win, System.currentTimeMillis());
                if (e != null) sink.accept(e);
            }
            return User32.INSTANCE.CallNextHookEx(keyHook, nCode, wParam,
                    new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        mouseHook = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_MOUSE_LL, mouseProc, module, 0);
        keyHook = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, keyProc, module, 0);

        // Ловушки вызываются Windows изнутри GetMessage этого потока.
        WinUser.MSG msg = new WinUser.MSG();
        while (running && User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            // сообщений для нас нет — просто крутим цикл
        }
        if (mouseHook != null) User32.INSTANCE.UnhookWindowsHookEx(mouseHook);
        if (keyHook != null) User32.INSTANCE.UnhookWindowsHookEx(keyHook);
        hookThreadId = 0;
    }

    private static boolean down(int vk) {
        return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
    }

    /** Прежняя форма (без Shift и Win) — для тестов и старых вызовов. */
    static Event classifyKey(int vk, boolean ctrl, boolean alt, long now) {
        return classifyKey(vk, ctrl, alt, false, false, now);
    }

    /**
     * Клавиша -> событие (без обращения к Windows — для тестов). Символы НЕ передаются:
     * только «человек печатает». Сочетание — именем, модификаторы всегда в порядке
     * ctrl, alt, shift, win (так же их приводит MentorSession.normKeys).
     */
    static Event classifyKey(int vk, boolean ctrl, boolean alt, boolean shift, boolean win, long now) {
        if (vk == VK_LWIN || vk == VK_RWIN) return null;                       // сама Win
        if (vk == 0x10 || vk == 0x11 || vk == 0x12 || (vk >= 0xA0 && vk <= 0xA5)) return null; // сами модификаторы
        String named = NAMED_KEYS.get(vk);
        if (ctrl || alt || win) {
            String key = comboKeyName(vk, named);
            if (key == null) return null;                                     // неизвестная клавиша — молчим
            StringBuilder sb = new StringBuilder();
            if (ctrl) sb.append("ctrl+");
            if (alt) sb.append("alt+");
            if (shift) sb.append("shift+");
            if (win) sb.append("win+");
            return new Key(sb.append(key).toString(), now);
        }
        if (named != null) return new Key(shift ? "shift+" + named : named, now);
        return new Typing(now);   // буква, цифра, пробел, Backspace, Shift+буква — просто «печатает»
    }

    /** Имя клавиши внутри сочетания: буква, цифра, F1–F12, служебная или «plus/minus/space». */
    private static String comboKeyName(int vk, String named) {
        if (vk >= 0x41 && vk <= 0x5A) return String.valueOf((char) Character.toLowerCase(vk));
        if (vk >= 0x30 && vk <= 0x39) return String.valueOf((char) vk);
        if (vk >= 0x70 && vk <= 0x7B) return "f" + (vk - 0x6F);
        if (named != null) return named;
        return COMBO_KEYS.get(vk);
    }

    /**
     * Сочетание так, как его видела запись показом ДО 23.11 (null — запись его не видела):
     * Win и Alt не записывались, Shift не учитывался, из Ctrl — только Ctrl+буква. Навыки,
     * записанные раньше, и сам способ записи не меняются: агент при повторе всё равно не
     * нажимает Win-сочетания (это запрещено ему в ScreenAgent.pressKeys).
     */
    public static String legacyCombo(String combo) {
        if (combo == null) return null;
        if (combo.contains("win+") || combo.contains("alt+")) return null;
        String c = combo.replace("shift+", "");
        if (c.startsWith("ctrl+")) return c.matches("ctrl\\+[a-z]") ? c : null;
        return NAMED_KEYS.containsValue(c) ? c : null;
    }
}
