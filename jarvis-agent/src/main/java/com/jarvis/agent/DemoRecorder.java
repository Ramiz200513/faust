package com.jarvis.agent;

import com.jarvis.agent.vision.TrajectoryCache;
import com.jarvis.agent.vision.UiaElement;
import com.jarvis.agent.vision.UiaSnapshot;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/**
 * Обучение показом: человек один раз делает задачу мышью и клавиатурой, а
 * агент записывает её как последовательность шагов «по элементам» — ровно в
 * том виде, в каком потом умеет её повторить без модели.
 *
 * <h2>Как устроено</h2>
 * <ul>
 *   <li><b>Низкоуровневые «ловушки» Windows</b> (WH_MOUSE_LL, WH_KEYBOARD_LL)
 *       сообщают о каждом клике и нажатии клавиши — в отдельном потоке с
 *       циклом сообщений, как этого требует Windows. Сама ловушка только
 *       кладёт событие в очередь: долго думать в ней нельзя, иначе у человека
 *       начнёт тормозить мышь.</li>
 *   <li><b>Снимки окна каждые полсекунды</b> (UI Automation, как у рентгена).
 *       Клик сопоставляется с ПОСЛЕДНИМ снимком ДО клика: к моменту, когда мы
 *       обработали событие, окно уже могло измениться от самого клика.</li>
 *   <li>Клик превращается в «нажать элемент такого-то типа с такой-то
 *       подписью» — самый маленький элемент под курсором. Координаты не
 *       записываются: окно в следующий раз может стоять в другом месте.</li>
 * </ul>
 *
 * <h2>Что НЕ записывается — важно для доверия</h2>
 * <ul>
 *   <li><b>Нажатия клавиш по символам.</b> Это был бы кейлоггер. Записывается
 *       только факт ввода, а ЧТО введено — берётся из поля через UI Automation
 *       после ввода. Поле пароля Windows помечает сама — его содержимое не
 *       попадает в запись никогда.</li>
 *   <li><b>Действия самого агента.</b> Robot помечается Windows как
 *       «искусственный ввод» (флаг INJECTED) — такие события отбрасываются.</li>
 *   <li>Клики по панели записи «Фауст» (кнопки «Готово» и «Отмена»).</li>
 * </ul>
 * Запись идёт только пока на экране панель «● Запись навыка» — никакой
 * скрытой записи.
 */
public final class DemoRecorder {

    /** Что сообщать панели записи. */
    public interface Listener {
        void onStep(int count, String description);
    }

    /** Итог записи: шаги и те же шаги человеческими фразами (1:1). */
    public record Recording(List<TrajectoryCache.Step> steps, List<String> descriptions, long durationMs) {}

    private static volatile boolean recordingNow;

    /** Идёт ли сейчас запись — пока идёт, агент не берёт ИИ-задачи (они попали бы в запись). */
    public static boolean isRecording() {
        return recordingNow;
    }

    private static final long SNAPSHOT_EVERY_MS = 500;
    private static final int KEEP_SNAPSHOTS = 16;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_KEYDOWN = 0x0100;
    private static final int WM_SYSKEYDOWN = 0x0104;
    private static final int WM_QUIT = 0x0012;
    private static final int LLMHF_INJECTED = 0x01;
    private static final int LLKHF_INJECTED = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;

    /** Клавиши, которые записываются как есть (не как «ввод текста»). */
    private static final Map<Integer, String> NAMED_KEYS = Map.ofEntries(
            Map.entry(0x0D, "enter"), Map.entry(0x1B, "esc"), Map.entry(0x09, "tab"),
            Map.entry(0x2E, "delete"), Map.entry(0x71, "f2"), Map.entry(0x74, "f5"),
            Map.entry(0x26, "up"), Map.entry(0x28, "down"), Map.entry(0x25, "left"), Map.entry(0x27, "right"),
            Map.entry(0x24, "home"), Map.entry(0x23, "end"), Map.entry(0x21, "pageup"), Map.entry(0x22, "pagedown"));

    /** Окна оболочки Windows (Пуск, поиск): ввод в них — это «открыть программу», а не шаг внутри неё. */
    private static final Set<String> SHELL_APPS = Set.of(
            "searchhost", "searchapp", "startmenuexperiencehost", "shellexperiencehost", "applicationframehost");

    private record Snap(long atMs, UiaSnapshot.Result window, String app) {}

    private sealed interface Event permits Click, Key, Typing, Stop {}
    /** x, y — физические пиксели экрана (так их отдаёт ловушка). */
    private record Click(int x, int y, long atMs) implements Event {}
    private record Key(String combo, long atMs) implements Event {}
    private record Typing(long atMs) implements Event {}
    private record Stop() implements Event {}

    private final UiaSnapshot uia = new UiaSnapshot();
    private final Deque<Snap> snaps = new ArrayDeque<>();
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final List<TrajectoryCache.Step> steps = new ArrayList<>();
    private final List<String> descriptions = new ArrayList<>();
    private final Listener listener;
    /** Где на экране панель записи (логические пиксели) — клики по ней не записываем. */
    private final Supplier<Rectangle> ignoreArea;
    private final double scale = UiaSnapshot.uiScale();
    /**
     * Принимать ли искусственный ввод (Robot). Только для самопроверки записи
     * (DemoSelfTest): там «человека» изображает Robot. Включается ТОЛЬКО
     * системным свойством JVM -Dfaust.demo.allowInjected=true, не через
     * agent.properties: в обычной работе агента его собственные клики не должны
     * попадать в запись никогда.
     */
    private final boolean allowInjected = Boolean.getBoolean("faust.demo.allowInjected");

    private volatile boolean running;
    private volatile int hookThreadId;
    private Thread hookThread;
    private Thread snapThread;
    private Thread worker;
    // Ссылки на колбэки ОБЯЗАНЫ жить всё время записи: иначе сборщик мусора
    // удалит их, а Windows продолжит вызывать — и процесс упадёт.
    private WinUser.LowLevelMouseProc mouseProc;
    private WinUser.LowLevelKeyboardProc keyProc;
    private WinUser.HHOOK mouseHook;
    private WinUser.HHOOK keyHook;

    private String lastApp = "";
    private boolean typingPending;
    private long lastTypingAt;
    private long startedAt;

    public DemoRecorder(Listener listener, Supplier<Rectangle> ignoreArea) {
        this.listener = listener;
        this.ignoreArea = ignoreArea;
    }

    // =========================================================================
    //  Начало и конец
    // =========================================================================

    public void start() {
        if (running) return;
        running = true;
        recordingNow = true;
        startedAt = System.currentTimeMillis();

        snapThread = new Thread(this::snapshotLoop, "demo-snapshots");
        snapThread.setDaemon(true);
        snapThread.start();

        worker = new Thread(this::processEvents, "demo-worker");
        worker.setDaemon(true);
        worker.start();

        hookThread = new Thread(this::hookLoop, "demo-hooks");
        hookThread.setDaemon(true);
        hookThread.start();
    }

    /** Закончить запись и вернуть, что записано. */
    public Recording stop() {
        events.offer(new Stop());
        try {
            // Последний ввод дочитывается свежим снимком — это до секунды.
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        shutdown();
        synchronized (steps) {
            return new Recording(List.copyOf(steps), List.copyOf(descriptions), System.currentTimeMillis() - startedAt);
        }
    }

    /** Бросить запись без сохранения. */
    public void cancel() {
        events.offer(new Stop());
        shutdown();
    }

    private void shutdown() {
        running = false;
        recordingNow = false;
        if (hookThreadId != 0) {
            // Разбудить цикл сообщений ловушек, чтобы он снял их и завершился.
            User32.INSTANCE.PostThreadMessage(hookThreadId, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        }
    }

    // =========================================================================
    //  Ловушки Windows — только кладут события в очередь
    // =========================================================================

    private void hookLoop() {
        hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        WinDef.HMODULE module = Kernel32.INSTANCE.GetModuleHandle(null);

        mouseProc = (nCode, wParam, info) -> {
            if (nCode >= 0 && wParam.intValue() == WM_LBUTTONDOWN
                    && (allowInjected || (info.flags & LLMHF_INJECTED) == 0)) {
                events.offer(new Click(info.pt.x, info.pt.y, System.currentTimeMillis()));
            }
            return User32.INSTANCE.CallNextHookEx(mouseHook, nCode, wParam,
                    new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        keyProc = (nCode, wParam, info) -> {
            int msg = wParam.intValue();
            if (nCode >= 0 && (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN)
                    && (allowInjected || (info.flags & LLKHF_INJECTED) == 0)) {
                Event e = classifyKey(info.vkCode);
                if (e != null) events.offer(e);
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
    }

    /**
     * Клавиша -> событие. Символы НЕ передаются: только «человек что-то печатает».
     * Сочетания с Alt и клавиша Win не записываются вовсе — агенту они запрещены.
     */
    private static Event classifyKey(int vk) {
        long now = System.currentTimeMillis();
        if (vk == 0x5B || vk == 0x5C) return null;                            // Win
        if (vk == 0x10 || vk == 0x11 || vk == 0x12 || (vk >= 0xA0 && vk <= 0xA5)) return null; // сами модификаторы
        boolean ctrl = (User32.INSTANCE.GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0;
        boolean alt = (User32.INSTANCE.GetAsyncKeyState(VK_MENU) & 0x8000) != 0;
        if (alt) return null;
        if (ctrl && vk >= 0x41 && vk <= 0x5A) {
            return new Key("ctrl+" + (char) Character.toLowerCase(vk), now);
        }
        if (ctrl) return null;
        String named = NAMED_KEYS.get(vk);
        if (named != null) return new Key(named, now);
        return new Typing(now);   // буква, цифра, пробел, Backspace — просто «печатает»
    }

    // =========================================================================
    //  Снимки окна
    // =========================================================================

    private void snapshotLoop() {
        while (running) {
            long t0 = System.currentTimeMillis();
            String app = WindowFocus.foregroundExe();
            UiaSnapshot.Result r = app.isEmpty() ? UiaSnapshot.Result.EMPTY : uia.captureForegroundWindow();
            synchronized (snaps) {
                // Время — момент НАЧАЛА снимка: он показывает окно не позже этого момента.
                snaps.addLast(new Snap(t0, r, app));
                while (snaps.size() > KEEP_SNAPSHOTS) snaps.removeFirst();
            }
            sleep(Math.max(50, SNAPSHOT_EVERY_MS - (System.currentTimeMillis() - t0)));
        }
    }

    /** Последний снимок, начатый не позже этого момента. */
    private Snap latestBefore(long atMs) {
        synchronized (snaps) {
            Snap best = null;
            for (Snap s : snaps) if (s.atMs() <= atMs) best = s;
            return best;
        }
    }

    /** Последний снимок между двумя моментами (для чтения введённого текста). */
    private Snap latestBetween(long fromMs, long toMs) {
        synchronized (snaps) {
            Snap best = null;
            for (Snap s : snaps) if (s.atMs() >= fromMs && s.atMs() <= toMs) best = s;
            return best;
        }
    }

    // =========================================================================
    //  Разбор событий — в отдельном потоке, не в ловушке
    // =========================================================================

    private void processEvents() {
        try {
            while (true) {
                Event e = events.take();
                switch (e) {
                    case Stop s -> {
                        flushTyping(System.currentTimeMillis(), true);
                        return;
                    }
                    case Typing t -> {
                        typingPending = true;
                        lastTypingAt = t.atMs();
                    }
                    case Key k -> onKey(k);
                    case Click c -> onClick(c);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            System.err.println("запись навыка: " + ex.getMessage());
        }
    }

    private void onKey(Key k) {
        Snap s = latestBefore(k.atMs());
        // Ctrl+V в текстовом поле — это ввод текста (что вставилось, прочитаем из поля).
        // Ctrl+V в Проводнике — вставка файла, это настоящий шаг.
        if ("ctrl+v".equals(k.combo()) && s != null && focusedTextField(s.window()) != null) {
            typingPending = true;
            lastTypingAt = k.atMs();
            return;
        }
        flushTyping(k.atMs(), false);
        if (s == null || isShell(s)) return;
        ensureApp(s);
        add(new TrajectoryCache.Step("key", null, null, null, -1, k.combo(), null, null, false),
            "Нажать клавиши " + k.combo());
    }

    private void onClick(Click c) {
        int lx = (int) Math.round(c.x() / scale);
        int ly = (int) Math.round(c.y() / scale);
        // Сначала дочитать набранное: обычно последний шаг показа — ввод текста,
        // а следом сразу «Готово». Раньше клик по панели отбрасывался ДО этого,
        // и последний ввод терялся (06.11: «купить молоко» в Блокноте не записалось).
        flushTyping(c.atMs(), false);
        Rectangle ignore = ignoreArea.get();
        if (ignore != null && ignore.contains(lx, ly)) return;   // кнопки панели записи
        Snap s = latestBefore(c.atMs() - 30);
        if (s == null || isShell(s)) {
            // Пуск, панель задач, рабочий стол: переход в другую программу
            // запишется сам — первым же кликом в её окне (ensureApp).
            return;
        }
        UiaElement el = elementAt(s.window(), lx, ly);
        if (el == null) {
            listener.onStep(steps.size(), "клик мимо известных элементов — не записан");
            return;
        }
        ensureApp(s);
        add(new TrajectoryCache.Step("click", el.controlType(), "uia", el.name(), -1, null, null, null, false),
            "Нажать «" + shortName(el.name()) + "»");
    }

    /**
     * Дочитать введённый текст: ЧТО напечатано, узнаём не по нажатиям, а из
     * поля в фокусе на снимке после ввода. Поле пароля — не записываем.
     */
    private void flushTyping(long beforeMs, boolean freshSnapshot) {
        if (!typingPending) return;
        typingPending = false;
        Snap s = freshSnapshot
                ? new Snap(System.currentTimeMillis(), uia.captureForegroundWindow(), WindowFocus.foregroundExe())
                : latestBetween(lastTypingAt + 150, beforeMs);
        // Свежий снимок при остановке может показать уже окно самого агента
        // (панель записи забрала фокус) — в нём набранного текста нет. Тогда
        // берём последний снимок программы, где печатали.
        if (s != null && isOwnWindow(s.app())) s = latestBetween(lastTypingAt + 150, beforeMs);
        if (s == null) s = latestBefore(beforeMs);
        if (s == null || isShell(s)) return;
        UiaElement field = typedField(s.window());
        if (field == null) {
            listener.onStep(steps.size(), "ввод текста — не удалось прочитать поле, не записан");
            return;
        }
        if (field.isPassword()) {
            listener.onStep(steps.size(), "ввод пароля — НЕ записан (агент пароли не вводит)");
            return;
        }
        String value = field.value() == null ? "" : field.value().strip();
        if (value.isEmpty()) return;
        ensureApp(s);
        add(new TrajectoryCache.Step("type", null, null, null, -1, value, null, null, false),
            "Ввести «" + shortName(value) + "»");
    }

    /** Если шаг — в другой программе, чем прошлый, сначала записать «открыть её». */
    private void ensureApp(Snap s) {
        if (s.app().equals(lastApp)) return;
        lastApp = s.app();
        String app = appName(s.app());
        add(new TrajectoryCache.Step("open_app", null, null, null, -1, app, null, null, false), "Открыть " + app);
    }

    private void add(TrajectoryCache.Step step, String description) {
        int count;
        synchronized (steps) {
            steps.add(step);
            descriptions.add(description);
            count = steps.size();
        }
        listener.onStep(count, description);
    }

    // =========================================================================
    //  Вспомогательное
    // =========================================================================

    /** Самый маленький элемент, в который попадает точка: кнопка внутри панели, а не вся панель. */
    static UiaElement elementAt(UiaSnapshot.Result w, int x, int y) {
        UiaElement best = null;
        for (UiaElement e : w.elements()) {
            if (x >= e.x() && x < e.x() + e.width() && y >= e.y() && y < e.y() + e.height()) {
                if (best == null || e.area() < best.area()) best = e;
            }
        }
        return best;
    }

    private static UiaElement focused(UiaSnapshot.Result w) {
        return w.elements().stream().filter(UiaElement::hasFocus).findFirst().orElse(null);
    }

    /**
     * Поле, в которое человек печатал.
     *
     * Обычно это элемент с фокусом. Но не всегда: в Блокноте Windows 11 фокус,
     * по мнению UI Automation, стоит на безымянной панели, а текст лежит в
     * «Редакторе текста» рядом с ней (замер 06.11: hasFocus=false у редактора,
     * true у пустой панели). Тогда берём текстовое поле, которое совпадает с
     * фокусом по месту на экране, а если такого нет — единственное текстовое
     * поле окна с текстом. Двух кандидатов — не угадываем.
     */
    static UiaElement typedField(UiaSnapshot.Result w) {
        UiaElement f = focused(w);
        if (f != null && f.value() != null && !f.value().isBlank()) return f;
        List<UiaElement> textFields = w.elements().stream()
                .filter(e -> isTextField(e) && e.value() != null && !e.value().isBlank())
                .toList();
        if (f != null) {
            List<UiaElement> overlapping = textFields.stream().filter(e -> overlaps(e, f)).toList();
            if (overlapping.size() == 1) return overlapping.get(0);
        }
        if (textFields.size() == 1) return textFields.get(0);
        return f;   // как раньше: пусто — ввод не запишется
    }

    private static boolean isTextField(UiaElement e) {
        String t = e.controlType() == null ? "" : e.controlType().toLowerCase(Locale.ROOT);
        return t.contains("edit") || t.contains("document") || t.contains("поле") || t.contains("изменение")
               || t.contains("документ");
    }

    private static boolean overlaps(UiaElement a, UiaElement b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
               && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }

    private static UiaElement focusedTextField(UiaSnapshot.Result w) {
        UiaElement f = focused(w);
        if (f == null) return null;
        String t = f.controlType() == null ? "" : f.controlType().toLowerCase(Locale.ROOT);
        return t.contains("edit") || t.contains("document") || t.contains("поле") || t.contains("изменение") ? f : null;
    }

    /** Окно самого агента (панель записи, диалоги) — Java-процесс. */
    static boolean isOwnWindow(String app) {
        return "java".equals(app) || "javaw".equals(app);
    }

    private static boolean isShell(Snap s) {
        return s.app().isBlank() || SHELL_APPS.contains(s.app())
               || s.window().windowTitle() == null || s.window().windowTitle().isBlank();
    }

    /** Имя программы для open_app — такое, которое потом найдёт AppCatalog. */
    static String appName(String exe) {
        return switch (exe) {
            case "explorer" -> "Проводник";
            case "notepad" -> "Блокнот";
            case "telegram" -> "Telegram";
            case "whatsapp", "whatsapp.root" -> "WhatsApp";
            case "chrome" -> "Google Chrome";
            case "msedge" -> "Microsoft Edge";
            case "firefox" -> "Firefox";
            case "calculatorapp", "calc" -> "Калькулятор";
            case "mspaint" -> "Paint";
            default -> exe.isEmpty() ? exe : Character.toUpperCase(exe.charAt(0)) + exe.substring(1);
        };
    }

    private static String shortName(String name) {
        if (name == null) return "";
        int comma = name.indexOf(',');
        String s = (comma > 0 ? name.substring(0, comma) : name).strip();
        return s.length() <= 40 ? s : s.substring(0, 39) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
