package com.jarvis.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Мелкие флаги поведения агента, которые не хочется тащить через конструктор
 * AgentConfig (он и так собирается в нескольких местах). Читается из того же
 * agent.properties, что и AgentConfig.
 *
 * <h2>Как переопределить флаг, не трогая файл</h2>
 * Любой флаг можно задать системным свойством JVM с префиксом {@code faust.}:
 * <pre>
 *   java -Dfaust.overlay.intro=false ...
 * </pre>
 * Оно важнее файла. Удобно для демо-запуска и проверок: не нужно править
 * настоящий agent.properties и потом не забыть вернуть как было.
 */
public final class AgentSettings {

    private static final String KEY_CONFIRM_SENSITIVE = "confirmSensitiveCommands";

    // --- оверлеи «Фауст» (анимации поверх экрана во время ИИ-задачи) ----------

    /** Вариант A — заставка «Пробуждение» в начале задачи. */
    public static final String OVERLAY_INTRO = "overlay.intro";
    /** Вариант B — светящаяся рамка по краю экрана и плашка сверху. */
    public static final String OVERLAY_FRAME = "overlay.frame";
    /** Вариант D — ореол вокруг курсора и всплески при кликах. */
    public static final String OVERLAY_CURSOR = "overlay.cursor";
    /** Вариант C — статус-виджет в правом нижнем углу. */
    public static final String OVERLAY_WIDGET = "overlay.widget";
    /** Плавное движение мыши перед кликом вместо «телепортации». */
    public static final String OVERLAY_SMOOTH_MOUSE = "overlay.smoothMouse";
    /**
     * Прятать оверлеи от ЛЮБОГО захвата экрана (WDA_EXCLUDEFROMCAPTURE).
     *
     * true (по умолчанию) — агент не видит оверлеи на своих снимках, и окна не
     * моргают. Но их не увидят и OBS, демонстрация экрана в Zoom/Teams,
     * «Ножницы» и Print Screen. Поставь false, когда записываешь демо-видео или
     * показываешь экран удалённо: агент всё равно не увидит оверлеи — на время
     * его снимков они будут на миг убираться (см. AgentOverlays).
     */
    public static final String OVERLAY_EXCLUDE_FROM_CAPTURE = "overlay.excludeFromCapture";
    /**
     * Сколько раз в секунду перерисовывается рамка (вариант B). По умолчанию 10:
     * замер показал, что у прозрачного окна в Java дорого каждое обновление,
     * и на 60 кадрах рамка ест ~27% ядра, а на 10 — укладывается в 5–10%.
     * На мощном ПК для демонстрации можно поставить 20–30: будет плавнее.
     */
    public static final String OVERLAY_FRAME_FPS = "overlay.frameFps";
    /**
     * «Рентген»: рамки и номера элементов окна, которые видит модель, и вспышка
     * выбранного перед кликом. Только режим UI Automation. true по умолчанию.
     */
    public static final String OVERLAY_XRAY = "overlay.xray";
    /** Панель «План и мысли» справа: шаги плана, обоснование модели, проверка. true по умолчанию. */
    public static final String OVERLAY_PLAN = "overlay.plan";

    // --- режим зрения агента вне браузера ------------------------------------

    /**
     * Спрашивать ли у Windows список элементов активного окна (UI Automation)
     * вместо разглядывания скриншота. true по умолчанию: так шаг быстрее и клик
     * попадает точно в кнопку.
     *
     * Поставь uia.enabled=false, чтобы вернуть агента к проверенному
     * пиксельному поведению — например, если на незнакомой программе список
     * элементов оказался бесполезным, а времени разбираться нет.
     */
    public static final String UIA_ENABLED = "uia.enabled";

    // --- вопросы ИИ-агента человеку (этап 1 многоэтапных задач) --------------

    /**
     * Может ли ИИ-агент посреди задачи задать вопрос на телефон: какой файл,
     * кому отправить, можно ли нажать «Отправить». true по умолчанию.
     *
     * ask.enabled=false возвращает прежнее поведение: промпт не упоминает
     * вопросы, а если модель всё же спросит, агент ответит ей сам «действуй
     * без вопросов». Пересборка не нужна — файл читается на каждую задачу.
     */
    public static final String ASK_ENABLED = "ask.enabled";
    /** Сколько секунд ждать ответа на один вопрос (по умолчанию 120). */
    public static final String ASK_TIMEOUT_SEC = "ask.timeoutSec";
    /** Сколько вопросов можно задать за одну задачу (по умолчанию 5). */
    public static final String ASK_MAX_PER_TASK = "ask.maxPerTask";

    // --- планировщик многоэтапных задач (этап 2) ------------------------------

    /**
     * Разбивать ли ИИ-задачу на подзадачи перед выполнением (PlanRunner).
     * true по умолчанию. Простая просьба в одной программе всё равно идёт
     * прежним путём — план ей не нужен; цена — один короткий текстовый запрос
     * к модели в начале задачи.
     *
     * plan.enabled=false — ровно прежнее поведение: один цикл в режиме,
     * который выбрал релей, без запроса к планировщику.
     */
    public static final String PLAN_ENABLED = "plan.enabled";
    /** Общий лимит времени задачи по плану, минут (по умолчанию 10; без плана — 3). */
    public static final String PLAN_MAX_MINUTES = "plan.maxMinutes";
    /** Больше подзадач план не содержит (по умолчанию 8). */
    public static final String PLAN_MAX_SUBTASKS = "plan.maxSubtasks";
    /** Сколько раз можно перестроить план после неудачной подзадачи (по умолчанию 2). */
    public static final String PLAN_MAX_REPLANS = "plan.maxReplans";
    /**
     * Проверять ли «готово» каждой подзадачи плана (файл на диске + отдельный
     * взгляд модели на окно). true по умолчанию; false — верить исполнителю,
     * как было до проверок (на один запрос к модели меньше на подзадачу).
     */
    public static final String PLAN_VERIFY = "plan.verify";

    // --- инструменты для файлов (этап 3) --------------------------------------

    /**
     * Действия find_file и paste_file у ИИ-агента в программах: найти файл по
     * имени и вставить его через буфер обмена вместо окна «Открыть».
     * files.enabled=false — модель про них не узнает, а если всё же попросит,
     * получит отказ. Слежение за «Загрузками» от этого флага не зависит: оно
     * ничего не делает, только сообщает, что скачалось.
     */
    public static final String FILES_ENABLED = "files.enabled";


    private AgentSettings() {}

    /**
     * true (по умолчанию) — опасные команды (agent_task, kill_process, передача
     * файлов) требуют подтверждения на самом ПК, см. ConfirmationGate. Поставь
     * confirmSensitiveCommands=false в agent.properties, если мешает во время
     * разработки — на демонстрации/защите верни обратно true (или просто убери строку).
     */
    public static boolean confirmSensitiveCommands() {
        return flag(KEY_CONFIRM_SENSITIVE, true);
    }

    static boolean confirmSensitiveCommands(Path file) {
        return Boolean.parseBoolean(load(file).getProperty(KEY_CONFIRM_SENSITIVE, "true").trim());
    }

    /**
     * Значение логического флага: сначала системное свойство {@code faust.<key>},
     * потом agent.properties, потом значение по умолчанию.
     *
     * Файл читается при каждом вызове. Это дёшево (файл крошечный, а флаги
     * спрашивают раз на задачу), зато правка agent.properties подхватывается
     * без перезапуска агента.
     */
    public static boolean flag(String key, boolean defaultValue) {
        String fromJvm = System.getProperty("faust." + key);
        if (fromJvm != null) return Boolean.parseBoolean(fromJvm.trim());
        String fromFile = load(defaultFile()).getProperty(key);
        return fromFile == null ? defaultValue : Boolean.parseBoolean(fromFile.trim());
    }

    /**
     * Целое число из настроек — тем же порядком, что и {@link #flag}:
     * системное свойство faust.&lt;key&gt;, потом agent.properties, потом значение
     * по умолчанию. Нечисло или число вне [min, max] — берём значение по умолчанию.
     */
    public static int number(String key, int defaultValue, int min, int max) {
        String raw = System.getProperty("faust." + key);
        if (raw == null) raw = load(defaultFile()).getProperty(key);
        if (raw == null) return defaultValue;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= min && v <= max ? v : defaultValue;
        } catch (NumberFormatException e) {
            System.err.println("agent.properties: " + key + "=" + raw + " — не число, беру " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Строка из настроек тем же порядком: faust.&lt;key&gt;, потом agent.properties,
     * потом значение по умолчанию. Пустая строка считается «не задано».
     */
    public static String text(String key, String defaultValue) {
        String raw = System.getProperty("faust." + key);
        if (raw == null) raw = load(defaultFile()).getProperty(key);
        return raw == null || raw.isBlank() ? defaultValue : raw.strip();
    }

    /**
     * Записать одну настройку в agent.properties — остальной файл не трогая.
     *
     * <p>Зачем построчно, а не через Properties.store: store переписал бы файл
     * целиком — без комментариев и в другом порядке строк. А этот файл человек
     * правит руками. Здесь меняется только строка «key=…» (или дописывается в
     * конец). Кодировка ISO-8859-1 — та же, в которой Properties.load читает
     * файл, поэтому любые байты остальных строк сохраняются как были.
     */
    public static synchronized void save(String key, String value) throws IOException {
        Path file = defaultFile();
        java.nio.charset.Charset latin = java.nio.charset.StandardCharsets.ISO_8859_1;
        java.util.List<String> lines = Files.exists(file)
                ? new java.util.ArrayList<>(Files.readAllLines(file, latin))
                : new java.util.ArrayList<>();
        String line = key + "=" + value;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (t.startsWith(key + "=") || t.startsWith(key + " ") || t.equals(key)) {
                lines.set(i, line);
                replaced = true;
            }
        }
        if (!replaced) lines.add(line);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, latin);
    }

    private static Path defaultFile() {
        return Paths.get(System.getProperty("user.home"), ".jarvis-agent", "agent.properties");
    }

    private static Properties load(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
    }
}
