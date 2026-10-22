# Шаг: подтверждение опасных команд прямо на ПК

## Общий контекст

Следующий пункт из бэклога (раздел "Безопасность" в `ROADMAP_V2_AND_HANDOFF.md`):
опасные команды не должны выполняться молча только потому, что кто-то с телефона
их прислал (украденный/скомпрометированный токен, баг в vision-агенте, случайное
нажатие). Нужно, чтобы человек **за самим ПК** явно нажал "Разрешить", прежде чем
такая команда реально выполнится.

Сначала прочитал актуальный код (`CommandExecutor.java`, `RelayClient.java`, whitelist,
протокол) — шаг cancel_task/history уже применён (нашёл `Command.CancelTask`,
`cancelRequested`, `agentPool`, `HistoryScreen.kt` на месте), так что этот промпт
пишу поверх текущего, а не старого состояния. Заодно заметил, что твой локальный
агент сам добавил `ListeningOrb.kt` — это не из моих промптов, но не мешает этому шагу.

**Какие команды считаем опасными** и почему именно эти четыре:
- `agent_task` — автономный vision-агент сам жмёт кнопки/вводит текст на экране,
  это самый широкий доступ из всех команд;
- `kill_process` — может убить что угодно, вплоть до несохранённой работы;
- `send_file_to_phone` — чтение произвольного файла из разрешённых папок (утечка);
- `send_file_to_pc` — запись файла на ПК (можно затереть существующий).

Сознательно **не** включил сюда `click`/`type_text`/`scroll`/`lock_screen`/
`screenshot`/буфер обмена — по ним постоянно идёт обычное голосовое управление, и
диалог на каждый клик убил бы всю идею "скажи и сделается". Это осознанная граница,
и на защите её легко объяснить: подтверждение — для необратимых/широких по охвату
действий, а не для рутины.

**Как это устроено технически** (без единого протокольного изменения — ответ идёт
через уже существующий `CommandResult`, релею и приложению вообще не нужно ничего
знать про подтверждение, только увидеть "отклонено на ПК" как обычную ошибку):

1. Новый `ConfirmationGate.java` — модальное окно Swing с кнопками "Разрешить" /
   "Отклонить" и обратным отсчётом; если за 20 секунд никто не ответил (ПК
   заблокирован, никого рядом нет) — по умолчанию **отказ**, это безопаснее, чем
   выполнить команду молча.
2. `CommandExecutor` перед четырьмя опасными командами зовёт это окно и либо
   выполняет действие, либо возвращает обычную ошибку с понятным текстом.
3. Переключатель `confirmSensitiveCommands=false` в `agent.properties` — на случай,
   если во время разработки диалоги будут мешать (на защите верни `true`, это
   значение по умолчанию, менять руками не обязательно).
4. **Важно для отзывчивости**: диалог подтверждения может занять до ~20 секунд —
   это столько же, сколько раньше блокировал бы поток agent_task (см. прошлый шаг
   про `cancel_task`). Поэтому `kill_process`/`send_file_to_phone`/`send_file_to_pc`
   переезжают на тот же `agentPool`, что и `agent_task` — иначе на эти 20 секунд
   агент опять перестанет читать сокет, как это уже однажды было с `agent_task` до
   исправления.

## Промпт 1 — окно подтверждения

Новый файл `jarvis-agent/src/main/java/com/jarvis/agent/ConfirmationGate.java`:

```java
package com.jarvis.agent;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.lang.reflect.InvocationTargetException;

/**
 * Окно "разрешить/отклонить" для опасных команд (agent_task, kill_process,
 * передача файлов) — человек за ПК должен явно согласиться, прежде чем команда,
 * пришедшая с телефона, реально выполнится. Если окно не тронули timeoutSeconds
 * секунд (ПК заблокирован, никого нет рядом) — по умолчанию ОТКАЗ: так безопаснее,
 * чем молча выполнить команду.
 */
public final class ConfirmationGate {

    public enum Decision { ALLOWED, DENIED, TIMEOUT }

    private ConfirmationGate() {}

    /** Блокирует вызывающий поток, пока не ответят или не истечёт время. */
    public static Decision ask(String description, int timeoutSeconds) {
        Decision[] out = {Decision.TIMEOUT};
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = show(description, timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            System.err.println("ConfirmationGate: " + e.getCause());
        }
        return out[0];
    }

    private static Decision show(String description, int timeoutSeconds) {
        Decision[] holder = {Decision.TIMEOUT};
        JDialog dialog = new JDialog((Frame) null, "Jarvis — подтверждение", true);
        dialog.setAlwaysOnTop(true);
        // Крестиком закрыть нельзя молча — таймер и кнопки сами решают исход,
        // иначе easy-close случайно превратился бы в "разрешено".
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JLabel text = new JLabel("<html><body style='width: 280px'>" + escape(description) + "</body></html>");
        root.add(text, BorderLayout.CENTER);

        JLabel countdown = new JLabel("автоматически отклонится через " + timeoutSeconds + " с",
                SwingConstants.CENTER);
        countdown.setFont(countdown.getFont().deriveFont(Font.ITALIC, 11f));

        JButton allow = new JButton("Разрешить");
        JButton deny = new JButton("Отклонить");
        allow.addActionListener(e -> { holder[0] = Decision.ALLOWED; dialog.dispose(); });
        deny.addActionListener(e -> { holder[0] = Decision.DENIED; dialog.dispose(); });

        JPanel buttons = new JPanel();
        buttons.add(allow);
        buttons.add(deny);

        JPanel south = new JPanel(new BorderLayout());
        south.add(countdown, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(null);

        int[] left = {timeoutSeconds};
        Timer timer = new Timer(1000, e -> {
            left[0]--;
            countdown.setText("автоматически отклонится через " + left[0] + " с");
            if (left[0] <= 0) {
                holder[0] = Decision.TIMEOUT;
                dialog.dispose();
            }
        });
        timer.start();

        Toolkit.getDefaultToolkit().beep(); // звук — легко пропустить попап, если не смотришь на экран
        dialog.setVisible(true); // блокирует до dispose()
        timer.stop();
        return holder[0];
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
```

Новый файл `jarvis-agent/src/main/java/com/jarvis/agent/AgentSettings.java`:

```java
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
 */
public final class AgentSettings {

    private static final String KEY_CONFIRM_SENSITIVE = "confirmSensitiveCommands";

    private AgentSettings() {}

    /**
     * true (по умолчанию) — опасные команды (agent_task, kill_process, передача
     * файлов) требуют подтверждения на самом ПК, см. ConfirmationGate. Поставь
     * confirmSensitiveCommands=false в agent.properties, если мешает во время
     * разработки — на демонстрации/защите верни обратно true (или просто убери строку).
     */
    public static boolean confirmSensitiveCommands() {
        return confirmSensitiveCommands(
                Paths.get(System.getProperty("user.home"), ".jarvis-agent", "agent.properties"));
    }

    static boolean confirmSensitiveCommands(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return Boolean.parseBoolean(props.getProperty(KEY_CONFIRM_SENSITIVE, "true"));
    }
}
```

## Промпт 2 — подключаем подтверждение в CommandExecutor

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/CommandExecutor.java`.

Было:

```java
    /** Жёсткий потолок шагов agent_task — независимо от того, что попросила LLM в команде. */
    private static final int MAX_AGENT_STEPS = 20;
    /** Жёсткий потолок общего времени на одну задачу agent_task. */
    private static final Duration AGENT_TASK_TIMEOUT = Duration.ofMinutes(3);

    private final Robot robot;
```

Стало:

```java
    /** Жёсткий потолок шагов agent_task — независимо от того, что попросила LLM в команде. */
    private static final int MAX_AGENT_STEPS = 20;
    /** Жёсткий потолок общего времени на одну задачу agent_task. */
    private static final Duration AGENT_TASK_TIMEOUT = Duration.ofMinutes(3);
    /** Сколько ждать ответа на диалог подтверждения (ConfirmationGate), прежде чем считать это отказом. */
    private static final int CONFIRM_TIMEOUT_SECONDS = 20;

    private final Robot robot;
```

Было:

```java
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /** Живой экран: шлёт кадры сам, поэтому ему нужен sender, а не возврат из execute(). */
    private final ScreenStreamer streamer;
```

Стало:

```java
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /**
     * true (по умолчанию), если опасные команды (agent_task, kill_process,
     * передача файлов) должны спрашивать подтверждение на самом ПК — см.
     * ConfirmationGate. Выключается флагом confirmSensitiveCommands=false в
     * agent.properties (удобно во время разработки, на защите держи включённым).
     */
    private final boolean confirmSensitive = AgentSettings.confirmSensitiveCommands();
    /** Живой экран: шлёт кадры сам, поэтому ему нужен sender, а не возврат из execute(). */
    private final ScreenStreamer streamer;
```

Было:

```java
                case Command.KillProcess c    -> ok(requestId, killProcess(c.processName()));
```

Стало:

```java
                case Command.KillProcess c    -> confirmedThen(requestId,
                        "Завершить процессы, содержащие «" + c.processName() + "»?",
                        () -> killProcess(c.processName()));
```

Было:

```java
                case Command.SendFileToPhone c    -> sendFileToPhone(requestId, c.folder(), c.fileName());
                case Command.SendFileToPc c       -> ok(requestId, receiveFileFromPhone(c.folder(), c.fileName(), c.base64()));
```

Стало:

```java
                case Command.SendFileToPhone c    -> confirmedList(requestId,
                        "Отправить файл «" + c.fileName() + "» из папки «" + c.folder() + "» на телефон?",
                        () -> sendFileToPhone(requestId, c.folder(), c.fileName()));
                case Command.SendFileToPc c       -> confirmedThen(requestId,
                        "Сохранить присланный файл «" + c.fileName() + "» в папку «" + c.folder() + "» на этом ПК?",
                        () -> receiveFileFromPhone(c.folder(), c.fileName(), c.base64()));
```

Было (метод `agentTask`):

```java
    private List<JarvisMessage> agentTask(String requestId, Command.AgentTask task) {
        if (!busyGoal.compareAndSet(null, task.goal())) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        // Сбрасываем ПОСЛЕ захвата busyGoal: иначе отмена, пришедшая в момент
        // между двумя задачами, могла бы погасить уже следующую.
        cancelRequested.set(false);
        try {
            return runAgentTask(requestId, task);
        } finally {
            busyGoal.set(null);
            if (tray != null) tray.clearTaskStatus();
        }
    }
```

Стало:

```java
    private List<JarvisMessage> agentTask(String requestId, Command.AgentTask task) {
        if (!busyGoal.compareAndSet(null, task.goal())) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        // Сбрасываем ПОСЛЕ захвата busyGoal: иначе отмена, пришедшая в момент
        // между двумя задачами, могла бы погасить уже следующую.
        cancelRequested.set(false);
        try {
            if (tray != null) tray.setTaskStatus("ждёт подтверждения: " + task.goal());
            ConfirmationGate.Decision decision = askConfirmation(
                    "Разрешить автономному агенту действовать на этом ПК?\nЦель: «" + task.goal() + "»");
            if (decision != ConfirmationGate.Decision.ALLOWED) {
                return error(requestId, decision == ConfirmationGate.Decision.DENIED
                        ? "отклонено на ПК"
                        : "не подтверждено на ПК за " + CONFIRM_TIMEOUT_SECONDS + " с — отклонено");
            }
            return runAgentTask(requestId, task);
        } finally {
            busyGoal.set(null);
            if (tray != null) tray.clearTaskStatus();
        }
    }
```

Было (сразу после `cancelTask`, перед `onDisconnected`):

```java
    private List<JarvisMessage> cancelTask(String requestId) {
        String goal = busyGoal.get();
        if (goal == null) {
            return ok(requestId, "сейчас нечего отменять");
        }
        cancelRequested.set(true);
        return ok(requestId, "отменяю: " + goal);
    }

    /** Связь с релеем пропала — кадры отправлять некуда, стрим гасим. */
    public void onDisconnected() {
        streamer.stop();
    }
```

Стало (добавили три новых метода и функциональный интерфейс между ними):

```java
    private List<JarvisMessage> cancelTask(String requestId) {
        String goal = busyGoal.get();
        if (goal == null) {
            return ok(requestId, "сейчас нечего отменять");
        }
        cancelRequested.set(true);
        return ok(requestId, "отменяю: " + goal);
    }

    // --- подтверждение опасных команд на ПК -------------------------------

    /** confirmSensitive выключен (agent.properties) -> сразу ALLOWED, без диалога. */
    private ConfirmationGate.Decision askConfirmation(String description) {
        return confirmSensitive
                ? ConfirmationGate.ask(description, CONFIRM_TIMEOUT_SECONDS)
                : ConfirmationGate.Decision.ALLOWED;
    }

    /** Команда обычно возвращает просто текст (как ok(...)) — сперва подтверждение, потом действие. */
    private List<JarvisMessage> confirmedThen(String requestId, String description, ThrowingSupplier<String> action)
            throws Exception {
        return confirmedList(requestId, description, () -> ok(requestId, action.get()));
    }

    /** Команда сама строит список сообщений (как sendFileToPhone) — сперва подтверждение, потом действие. */
    private List<JarvisMessage> confirmedList(String requestId, String description,
                                               ThrowingSupplier<List<JarvisMessage>> action) throws Exception {
        return switch (askConfirmation(description)) {
            case ALLOWED -> action.get();
            case DENIED -> error(requestId, "отклонено на ПК");
            case TIMEOUT -> error(requestId, "не подтверждено на ПК за " + CONFIRM_TIMEOUT_SECONDS + " с — отклонено");
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Связь с релеем пропала — кадры отправлять некуда, стрим гасим. */
    public void onDisconnected() {
        streamer.stop();
    }
```

## Промпт 3 — не блокируем сокет на время диалога

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/RelayClient.java`. Диалог
подтверждения может занять до `CONFIRM_TIMEOUT_SECONDS` (20 с) — ровно то же самое
соображение, что уже привело к вынесению `agent_task` на `agentPool`: пока команда
не вернулась, `webSocket.request(1)` не вызывается, и агент не читает сокет. Три
новые команды с подтверждением переезжают туда же.

Было:

```java
                case JarvisMessage.CommandRequest req -> {
                    System.out.println("Command: " + req.command().name());
                    if (req.command() instanceof Command.AgentTask) {
                        // Долгая задача (до 3 минут) — в отдельный поток, иначе
                        // сокет перестанет читаться и отмена не дойдёт.
                        // send() уже synchronized, писать из другого потока безопасно.
                        agentPool.submit(() -> {
                            try {
                                executor.execute(req.requestId(), req.command())
                                        .forEach(RelayClient.this::send);
                            } catch (Exception e) {
                                System.err.println("agent_task упал: " + e.getMessage());
                            }
                        });
                    } else {
                        // Всё остальное — миллисекунды, выполняем как раньше, прямо здесь.
                        // В том числе cancel_task: он только взводит флаг и должен
                        // сработать НЕМЕДЛЕННО, а не встать в очередь за задачей.
                        List<JarvisMessage> out = executor.execute(req.requestId(), req.command());
                        out.forEach(RelayClient.this::send);
                    }
                }
```

Стало:

```java
                case JarvisMessage.CommandRequest req -> {
                    System.out.println("Command: " + req.command().name());
                    if (isPotentiallySlow(req.command())) {
                        // agent_task (до 3 минут) и команды с подтверждением на ПК
                        // (до ~20 с на диалог ConfirmationGate) — в отдельный поток,
                        // иначе сокет перестанет читаться на это время и, например,
                        // cancel_task или следующая команда не дойдут вовремя.
                        // send() уже synchronized, писать из другого потока безопасно.
                        agentPool.submit(() -> {
                            try {
                                executor.execute(req.requestId(), req.command())
                                        .forEach(RelayClient.this::send);
                            } catch (Exception e) {
                                System.err.println("команда упала: " + e.getMessage());
                            }
                        });
                    } else {
                        // Всё остальное — миллисекунды, выполняем как раньше, прямо здесь.
                        // В том числе cancel_task: он только взводит флаг и должен
                        // сработать НЕМЕДЛЕННО, а не встать в очередь за задачей.
                        List<JarvisMessage> out = executor.execute(req.requestId(), req.command());
                        out.forEach(RelayClient.this::send);
                    }
                }
```

И добавь рядом со `scheduleReconnect()` (или в любом другом месте класса) новый
приватный статический метод:

```java
    /**
     * agent_task и команды, требующие подтверждения на ПК (см. ConfirmationGate
     * в CommandExecutor), могут занять до десятков секунд — держим их на
     * agentPool, чтобы чтение сокета (и cancel_task) не блокировалось на это время.
     */
    private static boolean isPotentiallySlow(Command command) {
        return command instanceof Command.AgentTask
                || command instanceof Command.KillProcess
                || command instanceof Command.SendFileToPhone
                || command instanceof Command.SendFileToPc;
    }
```

Больше нигде ничего менять не нужно: ни протокол (Kotlin/Java×2), ни
`CommandWhitelist.java`, ни приложение — отказ приходит обычным `CommandResult`
со статусом ERROR и текстом вроде "отклонено на ПК", который уже сегодня
показывается в чате как обычная ошибка команды.

## Проверка

- [ ] С телефона отправляешь `kill_process notepad` (или что угодно из четырёх
      команд) — на ПК выскакивает окно с обратным отсчётом; жмёшь "Отклонить" —
      в приложении приходит "отклонено на ПК", процесс не тронут.
- [ ] То же самое, но жмёшь "Разрешить" — команда реально выполняется, как раньше.
- [ ] Ничего не нажимаешь 20 секунд — приходит "не подтверждено на ПК за 20 с —
      отклонено", команда не выполняется.
- [ ] Во время ожидания подтверждения (`agent_task`) в трее видно "ждёт
      подтверждения: <цель>"; после ответа — обычный статус шага или "свободен".
- [ ] Обычные команды (`click`, `type_text`, `screenshot`, `open_app` и т.п.)
      выполняются как раньше, без единого диалога — задержки не появилось.
- [ ] Если поставить `confirmSensitiveCommands=false` в
      `%USERPROFILE%\.jarvis-agent\agent.properties` и перезапустить агента —
      все четыре команды снова выполняются мгновенно, без диалога (для защиты
      верни `true` или удали строку).
- [ ] Пока агент ждёт подтверждения одной команды, вторая опасная команда с
      телефона встаёт в очередь на agentPool и не мешает первому диалогу (не
      открывается второе окно поверх первого).
