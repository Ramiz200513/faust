package com.jarvis.agent.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * «Глаза» агента для обычных программ: снимает у активного окна список
 * элементов управления через Windows UI Automation.
 *
 * <h2>Зачем это нужно</h2>
 * В пиксельном режиме ({@link ScreenAgent}) модель получает картинку экрана и
 * называет координаты «на глаз»: шаг занимает секунды, а клик может уйти мимо
 * кнопки. В браузере эта проблема давно решена — там агент отдаёт модели
 * короткий список элементов DOM, и она просто называет номер. Windows умеет
 * отдавать такой же список для любого окна: имя элемента, его тип и точный
 * прямоугольник на экране. Этот класс и получает такой список.
 *
 * <h2>Как он это делает</h2>
 * UI Automation — это COM-интерфейс Windows. Дотянуться до него из Java можно
 * только вручную через COM-обёртки (много кода), поэтому снимок делает
 * маленькая программа на C# — {@code UiaDump.exe} (исходник в
 * {@code jarvis-agent/uia-helper}). Java запускает её и читает JSON. Замеры на
 * этой машине: 0.3–0.6 с на снимок против 5–38 с, которые модель тратит на
 * разбор картинки, — то есть накладные расходы на запуск процесса здесь
 * несущественны.
 *
 * <h2>Что отсеивается и почему</h2>
 * Windows отдаёт дерево целиком, вместе со служебными контейнерами и строками
 * списков, прокрученными далеко за пределы экрана. Модели это только мешает,
 * поэтому список чистится: см. {@link #isUseful} и {@link #filter}.
 */
public class UiaSnapshot {

    /** Куда распаковывается помощник при первом запуске (рядом с логом и токеном агента). */
    private static final Path HELPER_DIR = Paths.get(System.getProperty("user.home"), ".jarvis-agent", "uia");
    private static final String[] HELPER_FILES = {
            "UiaDump.exe", "UiaDump.dll", "UiaDump.deps.json", "UiaDump.runtimeconfig.json"
    };

    /** Сколько ждём снимок, прежде чем считать, что помощник завис. */
    private static final long TIMEOUT_SECONDS = 10;

    /** Сколько элементов максимум отдаём модели: длинный список вредит не меньше, чем пустой. */
    private static final int MAX_ELEMENTS_FOR_MODEL = 50;

    /**
     * Элемент, занимающий такую долю площади окна, — это контейнер (панель,
     * фон), а не то, по чему кликают. Кликать по нему бессмысленно, а модель он
     * сбивает: выглядит как «большая кнопка».
     */
    private static final double CONTAINER_AREA_RATIO = 0.8;

    /**
     * Типы, по которым осмысленно кликать или в которые вводят текст.
     * Названия приходят от Windows локализованными, поэтому сверяемся и с
     * русскими, и с английскими вариантами.
     */
    private static final Set<String> USEFUL_TYPES = Set.of(
            "button", "кнопка",
            "edit", "поле ввода", "изменение",
            "list item", "элемент списка",
            "menu item", "элемент меню",
            "check box", "флажок",
            "radio button", "переключатель",
            "combo box", "поле со списком",
            "tab item", "элемент вкладки", "вкладка",
            "tree item", "элемент дерева",
            "hyperlink", "ссылка",
            "split button", "кнопка с раскрытием",
            "list view", "представление списка", "список",
            "tree view", "представление дерева", "дерево",
            "slider", "ползунок",
            "spinner", "счётчик",
            "document", "документ",
            "text", "текст"
    );

    /**
     * Подписи кнопок рамки окна — в русской и английской локализации сразу:
     * Windows отдаёт их на языке системы, а у части программ (Telegram, Qt)
     * они остаются английскими даже в русской Windows.
     */
    private static final Set<String> CHROME_NAMES = Set.of(
            "свернуть", "развернуть", "восстановить", "закрыть", "system",
            "minimize", "maximize", "restore", "close",
            "свернуть параметры", "закрыть параметры", "свернуть параметры в окно");

    private final ObjectMapper mapper = new ObjectMapper();
    private Path helperExe;

    /** Результат снимка: что за окно и какие в нём элементы. */
    public record Result(String windowTitle, List<UiaElement> elements, long elapsedMs) {

        /** Пустой снимок: режим выключен настройкой либо окно ничего не отдало. */
        public static final Result EMPTY = new Result("", List.of(), 0);

        /**
         * Достаточно ли дерево богатое, чтобы работать по нему, а не по пикселям.
         *
         * <p>Считаем только содержательные элементы. Рамку окна — «Свернуть»,
         * «Развернуть», «Закрыть», системное меню — Windows рисует сама, и она
         * есть даже у окна, про внутренности которого система ничего не знает
         * (программы на своих движках, игры). Замер на окне Electron-приложения
         * дал ровно 5 элементов, и все пять были этой рамкой: по такому «списку»
         * агент не сделает ничего полезного, ему нужны пиксели.
         */
        public boolean isUsable() {
            return elements.stream().filter(e -> !isWindowChrome(e.name())).count() >= 5;
        }

        /** Кнопки рамки окна, одинаковые у всех программ и бесполезные для задачи. */
        private static boolean isWindowChrome(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            return CHROME_NAMES.contains(n);
        }
    }

    /**
     * Снять дерево активного окна.
     *
     * @return список элементов; при любой неудаче — пустой результат, а не исключение:
     *         вызывающий цикл просто откатится на пиксельный режим
     */
    public Result captureForegroundWindow() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return new Result("", List.of(), 0);
            long handle = com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
            return capture(handle);
        } catch (Exception e) {
            System.out.println("UIA: не удалось снять дерево окна — " + e.getMessage());
            return new Result("", List.of(), 0);
        }
    }

    /** Снять дерево окна с конкретным хэндлом. */
    public Result capture(long hwnd) {
        long t0 = System.currentTimeMillis();
        try {
            Path exe = ensureHelper();
            Process p = new ProcessBuilder(exe.toString(), "--hwnd", Long.toString(hwnd))
                    .redirectErrorStream(false)
                    .start();

            String json;
            try (InputStream in = p.getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result("", List.of(), System.currentTimeMillis() - t0);
            }

            return parse(json, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            System.out.println("UIA: снимок не получился — " + e.getMessage());
            return new Result("", List.of(), System.currentTimeMillis() - t0);
        }
    }

    // --- разбор и чистка списка ----------------------------------------

    private Result parse(String json, long elapsedMs) throws Exception {
        return parse(json, elapsedMs, uiScale());
    }

    /**
     * @param scale во сколько раз физические пиксели мельче логических (1.25 при масштабе 125%)
     */
    Result parse(String json, long elapsedMs, double scale) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root.has("error")) {
            System.out.println("UIA: " + root.get("error").asText());
            return new Result("", List.of(), elapsedMs);
        }

        String windowTitle = root.path("windowTitle").asText("");
        int[] windowRect = toLogical(parseRect(root.path("windowRect").asText("")), scale);
        long windowArea = windowRect == null ? 0 : (long) windowRect[2] * windowRect[3];

        List<Raw> raw = new ArrayList<>();
        for (JsonNode n : root.path("elements")) {
            int[] r = toLogical(parseRect(n.path("rect").asText("")), scale);
            if (r == null) continue;
            raw.add(new Raw(
                    n.path("controlType").asText("").toLowerCase(Locale.ROOT),
                    // Имена бывают многострочными («Строка 1,\nстолбец 1») — в списке
                    // для модели каждый элемент должен занимать ровно одну строку.
                    n.path("name").asText("").replaceAll("\\s+", " ").trim(),
                    r[0], r[1], r[2], r[3],
                    n.path("isPassword").asBoolean(false),
                    n.path("isEnabled").asBoolean(true),
                    n.path("isOffscreen").asBoolean(false),
                    n.path("value").asText("").replaceAll("\\s+", " ").trim(),
                    n.path("hasFocus").asBoolean(false)
            ));
        }

        return new Result(windowTitle, filter(raw, windowArea), elapsedMs);
    }

    /** Промежуточное представление: то же, что пришло из JSON, но ещё без номера. */
    private record Raw(String controlType, String name,
                       int x, int y, int width, int height,
                       boolean isPassword, boolean isEnabled, boolean isOffscreen,
                       String value, boolean hasFocus) {
        long area() {
            return (long) width * height;
        }
    }

    /**
     * Превращает сырое дерево в короткий список для модели.
     *
     * <p>Порядок важен: сначала выбрасываем заведомо бесполезное, потом убираем
     * дубли, и только потом обрезаем по количеству — иначе лимит съели бы
     * контейнеры, а настоящие кнопки в список бы не попали.
     */
    private List<UiaElement> filter(List<Raw> raw, long windowArea) {
        List<Raw> useful = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Raw r : raw) {
            if (!isUseful(r, windowArea)) continue;
            // Дубль — это один и тот же элемент, обёрнутый в собственного родителя
            // (частый случай: поле ввода и его внутренняя часть с тем же текстом
            // и тем же прямоугольником). Оставляем первый, внешний.
            String key = r.name + "|" + r.x + "," + r.y + "," + r.width + "," + r.height;
            if (!seen.add(key)) continue;
            useful.add(r);
        }

        List<Raw> clickable = List.copyOf(useful);
        useful.removeIf(r -> isLabelOfAnother(r, clickable));

        // Если элементов больше лимита — выбрасываем наименее полезные. Сначала
        // уходят крупные (контейнеры и широкие полосы), потом просто подписи:
        // кнопка или поле ввода почти всегда нужнее надписи в статус-баре.
        if (useful.size() > MAX_ELEMENTS_FOR_MODEL) {
            useful.sort((a, b) -> {
                int byKind = Integer.compare(priority(a), priority(b));
                return byKind != 0 ? byKind : Long.compare(a.area(), b.area());
            });
            useful = new ArrayList<>(useful.subList(0, MAX_ELEMENTS_FOR_MODEL));
        }

        // И только теперь — порядок чтения: сверху вниз, а в пределах строки
        // слева направо. Номера должны идти так же, как человек читает окно:
        // модель ориентируется по ним, и «кнопка [3] рядом с полем [2]» для неё
        // осмысленно, а перемешанный список — нет.
        useful.sort((a, b) -> {
            // Элементы в пределах одной строки (разница по высоте меньше их
            // собственной высоты) считаем соседями и сортируем по горизонтали.
            int rowHeight = Math.max(16, Math.min(a.height, b.height));
            if (Math.abs(a.y - b.y) < rowHeight) return Integer.compare(a.x, b.x);
            return Integer.compare(a.y, b.y);
        });

        List<UiaElement> result = new ArrayList<>(useful.size());
        for (int i = 0; i < useful.size(); i++) {
            Raw r = useful.get(i);
            result.add(new UiaElement(i, r.controlType, r.name,
                    r.x, r.y, r.width, r.height, r.isPassword, r.isEnabled,
                    r.value, r.hasFocus));
        }
        return result;
    }

    /**
     * Насколько элемент ценен для агента, когда все в список не помещаются:
     * меньше число — важнее. По чему кликают и куда вводят, нужно в первую
     * очередь; надписи полезны только как справка о состоянии окна.
     */
    private int priority(Raw r) {
        return switch (r.controlType) {
            case "button", "кнопка", "edit", "поле ввода", "изменение",
                 "menu item", "элемент меню", "check box", "флажок",
                 "radio button", "переключатель", "combo box", "поле со списком",
                 "tab item", "элемент вкладки", "вкладка", "hyperlink", "ссылка",
                 "split button", "кнопка с раскрытием" -> 0;
            case "list item", "элемент списка", "tree item", "элемент дерева" -> 1;
            case "text", "текст" -> 3;
            default -> 2;
        };
    }

    /**
     * Не является ли этот элемент просто подписью другого, уже попавшего в
     * список. Windows часто отдаёт и вкладку, и лежащий внутри неё текст с тем
     * же словом — для модели это два разных номера, ведущих в одно место.
     * Оставляем тот, по которому кликают (вкладку), подпись убираем.
     */
    private boolean isLabelOfAnother(Raw candidate, List<Raw> all) {
        if (!"text".equals(candidate.controlType) && !"текст".equals(candidate.controlType)) return false;
        for (Raw other : all) {
            if (other == candidate) continue;
            if ("text".equals(other.controlType) || "текст".equals(other.controlType)) continue;
            boolean inside = candidate.x >= other.x && candidate.y >= other.y
                    && candidate.x + candidate.width <= other.x + other.width
                    && candidate.y + candidate.height <= other.y + other.height;
            if (inside) return true;
        }
        return false;
    }

    /** Стоит ли показывать этот элемент модели. */
    private boolean isUseful(Raw r, long windowArea) {
        if (r.isOffscreen) return false;                       // Windows сама говорит, что элемент не виден
        if (r.width <= 0 || r.height <= 0) return false;
        // Окно бывает шире экрана (уехало за край, горизонтальная прокрутка).
        // Его элементы Windows отдаёт как обычные, но кликнуть по ним нельзя:
        // мышь туда просто не доедет. В проводнике так попадались строки с
        // координатой клика x=2021 при экране шириной 1920.
        if (!screenBounds().contains(r.x + r.width / 2, r.y + r.height / 2)) return false;
        if (r.name.isBlank()) return false;                    // безымянный элемент модели ничего не скажет
        if (!USEFUL_TYPES.contains(r.controlType)) return false;
        // Полоски-разделители и невидимые тени: один из размеров в пару пикселей.
        if (r.width < 4 || r.height < 4) return false;
        // Контейнер во весь экран — не кнопка.
        return !(windowArea > 0 && r.area() > windowArea * CONTAINER_AREA_RATIO);
    }

    /**
     * Общий прямоугольник всех мониторов. Считается один раз: состав мониторов
     * во время задачи не меняется, а обращение к графической среде не бесплатное.
     */
    private java.awt.Rectangle screenBounds() {
        if (screenBounds == null) {
            java.awt.Rectangle all = new java.awt.Rectangle();
            for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                all = all.union(d.getDefaultConfiguration().getBounds());
            }
            screenBounds = all;
        }
        return screenBounds;
    }

    private java.awt.Rectangle screenBounds;

    /**
     * Масштаб экрана Windows с точки зрения Java: 1.25 при «125%».
     *
     * <h3>Почему это важно (ошибка найдена 30.09 на живом прогоне)</h3>
     * Помощник UiaDump отдаёт прямоугольники в ФИЗИЧЕСКИХ пикселях экрана
     * (1920×1080), а Java — и Robot, которым агент кликает, и размеры экрана —
     * работает в ЛОГИЧЕСКИХ (1536×864 при 125%). Без перевода клик в точку
     * (837, 81) уезжал в физическую (1046, 101): мимо адресной строки
     * Проводника, в окно Firefox позади него. А фильтр «элемент за краем
     * экрана» сравнивал физический x с логической шириной 1536 и выбрасывал
     * всё, что правее 80% экрана, — там часто и стоит кнопка «Отправить».
     *
     * <p>Берём масштаб основного монитора. На нескольких мониторах с РАЗНЫМ
     * масштабом это приближение — честное ограничение, на ноутбуке с одним
     * экраном его нет.
     */
    public static double uiScale() {
        try {
            double s = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
            return s > 0 ? s : 1.0;
        } catch (Exception | Error e) {
            return 1.0; // без графической среды (тесты на сервере) — считаем 100%
        }
    }

    /** Физические пиксели -> логические (в них работают Robot и оверлеи). */
    static int[] toLogical(int[] r, double scale) {
        if (r == null || scale == 1.0) return r;
        return new int[]{
                (int) Math.round(r[0] / scale), (int) Math.round(r[1] / scale),
                (int) Math.round(r[2] / scale), (int) Math.round(r[3] / scale)
        };
    }

    /** "12,34,100,40" -> [12,34,100,40]; null, если строка не та. */
    private static int[] parseRect(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- распаковка помощника -------------------------------------------

    /**
     * Кладёт UiaDump.exe и его спутники в ~/.jarvis-agent/uia при первом
     * обращении. Почему не запускаем прямо из jar: Windows не умеет запускать
     * .exe изнутри архива, файл должен лежать на диске.
     */
    private synchronized Path ensureHelper() throws Exception {
        if (helperExe != null && Files.exists(helperExe)) return helperExe;

        Files.createDirectories(HELPER_DIR);
        for (String file : HELPER_FILES) {
            Path target = HELPER_DIR.resolve(file);
            try (InputStream in = UiaSnapshot.class.getResourceAsStream("/uia/" + file)) {
                if (in == null) throw new IllegalStateException("в сборке агента нет /uia/" + file);
                // Перезаписываем каждый раз: так обновлённый помощник из новой
                // сборки не останется незамеченным из-за старого файла на диске.
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.FileSystemException busy) {
                // Файл занят - значит, он уже на месте и кем-то используется.
                if (!Files.exists(target)) throw busy;
            }
        }
        helperExe = HELPER_DIR.resolve("UiaDump.exe");
        return helperExe;
    }
}
