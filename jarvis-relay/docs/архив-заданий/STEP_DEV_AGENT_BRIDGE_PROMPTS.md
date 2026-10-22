# Вау-фича: Dev-агент — голосовой мост к вашему coding-агенту на ПК

## Общий контекст

Запрос был конкретный: сказать в телефон «зайди в Android Studio, поменяй
версию кода» — и чтобы это ушло уже существующему на ПК AI coding-агенту
(Gemini в Android Studio), а не общему vision-агенту Jarvis. Перед тем как
проектировать, проверил, насколько это реализуемо технически — и нашёл
важную развилку.

**Agent Mode в Android Studio (тот самый «Gemini-агент», который реально
редактирует файлы, гоняет сборку, чинит баги) управляется ТОЛЬКО через
панель в самой IDE.** Официальная документация прямо говорит: нет ни CLI,
ни API, ни внешнего триггера, и прочитать его ответ программно неоткуда —
только руками в чате. Единственный способ дотянуться до него снаружи —
это то же самое, что уже умеет ваш vision-agent в SCREEN-режиме: кликнуть
по панели, напечатать текст, попытаться прочитать ответ скриншотом/OCR.
Рабочий путь, но хрупкий и без гарантий.

**Вместо этого мост наведён к другому, уже реально стоящему у вас
инструменту.** В процессе объяснения выяснилось, что вы уже используете
локальный CLI coding-агент — именно им вы применяете мои промпт-доки к
коду. Проверил по файлам на ПК: в `jarvis-agent/.claude/` есть (пустая,
но существующая) папка — это artefact именно Claude Code CLI, значит он
у вас уже установлен и хоть раз запускался в этой папке. У такого
инструмента, в отличие от IDE-панели, ЕСТЬ headless-режим: флаг `-p`
запускает разовую задачу без интерактивного чата, `--output-format json`
отдаёт результат одним предсказуемым JSON-объектом на stdout, а код
завершения процесса (0/не 0) прямо говорит, успех это или нет. Никакого
OCR, никакого «авось распознается» — то же самое, что вы делаете руками
в терминале, просто по команде с телефона.

Формат ответа выбрали в разговоре: **явный профиль-чип в приложении** —
пользователь один раз заводит профиль (папка проекта на ПК + шаблон
команды запуска), потом на телефоне выбирает профиль и диктует/печатает
цель. LLM-парсинг фразы в это специально не встраиваем — меньше риска,
что модель ошибётся и решит, что обычная фраза — это команда для
coding-агента.

**Честно про модель безопасности** (это прямо ляжет в главу диплома про
whitelist). До сих пор whitelist ограничивал ЗАКРЫТЫЙ набор действий —
даже у `kill_process`/`send_file_to_pc` параметр один и предсказуемый.
Здесь параметр — это `commandTemplate`, который пользователь сам один раз
вписывает В ПРИЛОЖЕНИИ на своём телефоне; whitelist разрешает сам ТИП
команды («может запустить настроенный процесс»), а какой именно процесс —
решает не голос и не LLM, а владелец телефона заранее. Модель доверия не
ломается (тот же человек владеет и телефоном, и ПК), но boundary честно
сдвигается: это самая мощная команда в системе, и именно поэтому она идёт
через ТОТ ЖЕ `ConfirmationGate`, что и `agent_task`/`kill_process` — на ПК
всё равно нужно нажать «Разрешить», прежде чем что-либо запустится.
Плюс — минимальная защита от command injection: цель с кавычками/`&`/`|`/
`^`/`%`/бэктиком/переносом строки отклоняется ДО запуска, а не пытается
экранироваться (Windows-кавычки в `cmd.exe` — отдельная больная тема,
проще запретить, чем правильно экранировать).

Архитектурно переиспользуем уже существующее по максимуму: `busyGoal`
(одна долгая задача одновременно — vision-агент ИЛИ dev-агент, не обе
сразу), `cancelRequested`/`cancel_task` (тот же флаг, тот же UI-чип «⛔
Стоп»), `ConfirmationGate` (то же окно на ПК), `agentPool` в `RelayClient`
(та же причина — долгая операция не должна блокировать чтение сокета).
Единственная реально новая часть — сам запуск процесса
(`ProcessBuilder`) и разбор его результата.

Порядок промптов: протокол (3 места) → whitelist → `jarvis-agent`
(выполнение процесса + маршрутизация) → приложение (хранилище профилей →
экран → `ChatViewModel`) → `MainActivity.kt`.

---

## Промпт 1 — протокол: команда `dev_agent_task` (ТРЁХ местах, как обычно)

### 1.1. `jarvis/shared/src/commonMain/kotlin/com/jarvis/protocol/Command.kt`

Добавь в конец файла, после блока `CancelTask`:

```kotlin
// было (конец файла)
@Serializable
@SerialName("cancel_task")
data object CancelTask : Command {
    override val name: String get() = "cancel_task"
}
// стало (конец файла)
@Serializable
@SerialName("cancel_task")
data object CancelTask : Command {
    override val name: String get() = "cancel_task"
}

// --- Layer 8: внешний coding-агент на ПК (CLI-мост) -------------------------

/**
 * Задача для внешнего coding-агента (например, `claude -p`), настроенного
 * пользователем как "профиль" на телефоне (см. DevAgentProfile в
 * androidApp). workDir/commandTemplate приходят с телефона — это то, что
 * пользователь один раз сам вписал в приложении, не голос и не LLM.
 * {goal} в commandTemplate подставляется агентом на ПК перед запуском.
 */
@Serializable
@SerialName("dev_agent_task")
data class DevAgentTask(
    /** Абсолютный путь на ПК — рабочая папка процесса. */
    val workDir: String,
    /** Например: claude -p "{goal}" --output-format json --permission-mode acceptEdits */
    val commandTemplate: String,
    val goal: String,
) : Command {
    override val name: String get() = "dev_agent_task"
}
```

### 1.2. `jarvis-agent/src/main/java/com/jarvis/agent/protocol/Command.java`

```java
// было
        @JsonSubTypes.Type(value = Command.CancelTask.class, name = "cancel_task")
})
public sealed interface Command permits
        Command.NaturalLanguage, Command.SetVolume, Command.Shutdown, Command.Reboot,
        Command.LockScreen, Command.KillProcess, Command.ListProcesses, Command.OpenUrl,
        Command.OpenApp, Command.Screenshot, Command.AgentTask, Command.Click,
        Command.TypeText, Command.Scroll, Command.StartScreenStream, Command.StopScreenStream,
        Command.GetClipboard, Command.SetClipboard,
        Command.ListFiles, Command.SendFileToPhone, Command.SendFileToPc,
        Command.CancelTask {
// стало
        @JsonSubTypes.Type(value = Command.CancelTask.class, name = "cancel_task"),
        @JsonSubTypes.Type(value = Command.DevAgentTask.class, name = "dev_agent_task")
})
public sealed interface Command permits
        Command.NaturalLanguage, Command.SetVolume, Command.Shutdown, Command.Reboot,
        Command.LockScreen, Command.KillProcess, Command.ListProcesses, Command.OpenUrl,
        Command.OpenApp, Command.Screenshot, Command.AgentTask, Command.Click,
        Command.TypeText, Command.Scroll, Command.StartScreenStream, Command.StopScreenStream,
        Command.GetClipboard, Command.SetClipboard,
        Command.ListFiles, Command.SendFileToPhone, Command.SendFileToPc,
        Command.CancelTask, Command.DevAgentTask {
```

И в конец интерфейса, после блока `CancelTask`:

```java
// было
    // Layer 7: управление уже запущенной задачей
    record CancelTask() implements Command {
        public String name() { return "cancel_task"; }
    }
}
// стало
    // Layer 7: управление уже запущенной задачей
    record CancelTask() implements Command {
        public String name() { return "cancel_task"; }
    }

    // Layer 8: внешний coding-агент на ПК (CLI-мост)
    record DevAgentTask(String workDir, String commandTemplate, String goal) implements Command {
        public String name() { return "dev_agent_task"; }
    }
}
```

### 1.3. `jarvis-relay/src/main/java/com/jarvis/relay/protocol/Command.java`

Ровно та же правка, что в 1.2 — файл побайтово тот же класс (проверил
`diff` — единственное отличие между копиями это строка `package`).

---

## Промпт 2 — whitelist

Файл: `jarvis-relay/src/main/java/com/jarvis/relay/security/CommandWhitelist.java`

```java
// было
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream",
            "get_clipboard", "set_clipboard",
            "list_files", "send_file_to_phone", "send_file_to_pc",
            "cancel_task"
    );
// стало
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream",
            "get_clipboard", "set_clipboard",
            "list_files", "send_file_to_phone", "send_file_to_pc",
            "cancel_task", "dev_agent_task"
    );
```

---

## Промпт 3 — `jarvis-agent`: запуск внешнего процесса

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/CommandExecutor.java`

### 3.1. Импорты

```java
// было
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
// стало
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
```

### 3.2. Константа таймаута и запрещённые символы в цели

```java
// было
    /** Жёсткий потолок общего времени на одну задачу agent_task. */
    private static final Duration AGENT_TASK_TIMEOUT = Duration.ofMinutes(3);
    /** Сколько ждать ответа на диалог подтверждения (ConfirmationGate), прежде чем считать это отказом. */
    private static final int CONFIRM_TIMEOUT_SECONDS = 20;
// стало
    /** Жёсткий потолок общего времени на одну задачу agent_task. */
    private static final Duration AGENT_TASK_TIMEOUT = Duration.ofMinutes(3);
    /** Coding-агент (dev_agent_task) может реально править код и гонять сборку — даём больше времени. */
    private static final Duration DEV_AGENT_TIMEOUT = Duration.ofMinutes(10);
    /**
     * Символы, которых не должно быть в продиктованной цели dev_agent_task —
     * она попадает в cmd.exe как часть команды (см. runDevAgentTask), и
     * корректно экранировать кавычки/метасимволы в Windows-шелле — отдельная
     * больная тема. Проще отклонить, чем понадеяться на экранирование.
     */
    private static final Pattern UNSAFE_GOAL_CHARS = Pattern.compile("[\"&|^%`\\r\\n]");
    /** Сколько ждать ответа на диалог подтверждения (ConfirmationGate), прежде чем считать это отказом. */
    private static final int CONFIRM_TIMEOUT_SECONDS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();
```

### 3.3. Ветка в `execute()`

```java
// было
                case Command.AgentTask c      -> agentTask(requestId, c);
                case Command.CancelTask c     -> cancelTask(requestId);
// стало
                case Command.AgentTask c      -> agentTask(requestId, c);
                case Command.DevAgentTask c   -> devAgentTask(requestId, c);
                case Command.CancelTask c     -> cancelTask(requestId);
```

### 3.4. Сами методы — добавь сразу после `runAgentTask(...)` (перед
комментарием `// --- concrete actions ------`)

```java
// было
        result.add(new JarvisMessage.CommandResult(requestId, status, msg, report));
        return result;
    }

    // --- concrete actions ------------------------------------------------
// стало
        result.add(new JarvisMessage.CommandResult(requestId, status, msg, report));
        return result;
    }

    // --- внешний coding-агент (dev_agent_task) ---------------------------

    /**
     * Точка входа dev_agent_task: та же эксклюзивность (busyGoal), что и у
     * agent_task — coding-агент и vision-агент не бегут одновременно — и то
     * же окно подтверждения на ПК, только с текстом про внешний процесс.
     */
    private List<JarvisMessage> devAgentTask(String requestId, Command.DevAgentTask task) {
        if (UNSAFE_GOAL_CHARS.matcher(task.goal()).find()) {
            return error(requestId, "цель содержит недопустимые символы (\" & | ^ % ` или перенос строки) — переформулируйте без них");
        }
        String label = "dev-агент: " + task.goal();
        if (!busyGoal.compareAndSet(null, label)) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        cancelRequested.set(false);
        try {
            if (tray != null) tray.setTaskStatus("ждёт подтверждения: " + label);
            ConfirmationGate.Decision decision = askConfirmation(
                    "Разрешить внешнему coding-агенту работать в папке\n«" + task.workDir()
                            + "»?\nЗадача: «" + task.goal() + "»");
            if (decision != ConfirmationGate.Decision.ALLOWED) {
                return error(requestId, decision == ConfirmationGate.Decision.DENIED
                        ? "отклонено на ПК"
                        : "не подтверждено на ПК за " + CONFIRM_TIMEOUT_SECONDS + " с — отклонено");
            }
            if (tray != null) tray.setTaskStatus("выполняю: " + label);
            return runDevAgentTask(requestId, task);
        } finally {
            busyGoal.set(null);
            if (tray != null) tray.clearTaskStatus();
        }
    }

    /**
     * Запускает commandTemplate (с {goal} -> task.goal()) через cmd.exe в
     * task.workDir(). Не блокирует поток "молча" — опрашивает процесс каждые
     * 500мс, проверяя cancelRequested и дедлайн, поэтому cancel_task и общий
     * таймаут срабатывают, даже если сам процесс завис. stdout/stderr читаются
     * в отдельных потоках-дренажах — иначе процесс с длинным выводом может
     * зависнуть на заполненном пайпе ОС раньше, чем мы успеем его прочитать.
     */
    private List<JarvisMessage> runDevAgentTask(String requestId, Command.DevAgentTask task) {
        String command = task.commandTemplate().replace("{goal}", task.goal());

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.directory(new File(task.workDir()));
            process = pb.start();
        } catch (IOException e) {
            return error(requestId, "не удалось запустить dev-агента: " + e.getMessage());
        }
        try {
            process.getOutputStream().close(); // dev-агент не должен ждать ввод с клавиатуры
        } catch (IOException ignored) { }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = drainAsync(process.getInputStream(), stdout);
        Thread errReader = drainAsync(process.getErrorStream(), stderr);

        Instant deadline = Instant.now().plus(DEV_AGENT_TIMEOUT);
        boolean finished = false;
        boolean cancelled = false;
        boolean timedOut = false;
        while (true) {
            if (cancelRequested.get()) { cancelled = true; break; }
            if (Instant.now().isAfter(deadline)) { timedOut = true; break; }
            try {
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) { finished = true; break; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                break;
            }
        }
        if (!finished) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        try { outReader.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { errReader.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (cancelled) {
            return error(requestId, "dev-задача отменена");
        }
        if (timedOut) {
            return error(requestId, "dev-агент не уложился в " + DEV_AGENT_TIMEOUT.toMinutes() + " мин — остановлен");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String tail = stderr.length() > 0 ? stderr.toString() : stdout.toString();
            return error(requestId, "dev-агент завершился с ошибкой (код " + exitCode + "): " + trim(tail, 500));
        }
        return ok(requestId, trim(extractResult(stdout.toString()), 4000));
    }

    /** Достаём поле "result" из JSON claude -p --output-format json; если это не JSON — отдаём как есть. */
    private String extractResult(String stdout) {
        try {
            JsonNode node = MAPPER.readTree(stdout.trim());
            if (node.has("result")) return node.get("result").asText();
        } catch (Exception ignored) {
            // не JSON (другой CLI-инструмент или другой --output-format) — отдаём сырой вывод
        }
        return stdout.trim();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…(обрезано)";
    }

    private Thread drainAsync(InputStream in, StringBuilder out) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // поток закрылся вместе с процессом — не ошибка
            }
        }, "dev-agent-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // --- concrete actions ------------------------------------------------
```

Важная оговорка про кодировку: остальной проект уже борется с этим (см.
комментарии в `build.gradle.kts` про `-Dstdout.encoding=UTF-8` для самого
Jarvis-агента) — но ВНЕШНИЙ процесс, который мы здесь запускаем, читает
свою кодировку из настроек ТОЙ программы/консоли, а не из JVM-флагов
Jarvis. Если CLI-инструмент на вашем ПК пишет в консоль не в UTF-8 (по
умолчанию у `cmd.exe` часто CP866/CP1251), кириллица в результате может
прийти кракозябрами — в этом случае самое простое исправление: добавить
`chcp 65001 &&` в начало `commandTemplate` (переключает кодовую страницу
консоли на UTF-8 перед запуском самой команды) — проверить это вживую
нужно обязательно, я не могу проверить с вашей стороны.

### 3.5. `RelayClient.java` — та же очередь потока, что у `agent_task`

```java
// было
    private ExecutorService poolFor(Command command) {
        if (command instanceof Command.AgentTask) return agentPool;
        if (command instanceof Command.KillProcess
                || command instanceof Command.SendFileToPhone
                || command instanceof Command.SendFileToPc) return confirmPool;
        return null;
    }
// стало
    private ExecutorService poolFor(Command command) {
        if (command instanceof Command.AgentTask || command instanceof Command.DevAgentTask) return agentPool;
        if (command instanceof Command.KillProcess
                || command instanceof Command.SendFileToPhone
                || command instanceof Command.SendFileToPc) return confirmPool;
        return null;
    }
```

---

## Промпт 4 — приложение: профили dev-агента (новый файл)

Создай `androidApp/src/main/kotlin/com/jarvis/app/DevAgentProfiles.kt`:

```kotlin
package com.jarvis.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Профиль внешнего coding-агента на ПК — где лежит проект и какой командой
 * его запускать. Задаётся один раз в приложении (тот же принцип, что и
 * CustomActions.kt: пользователь сам расширяет систему, а не только код).
 *
 * commandTemplate уходит на ПК как есть и там подставляется в cmd.exe
 * (см. CommandExecutor.runDevAgentTask) — {goal} заменяется на
 * продиктованную цель. Пример для Claude Code:
 * claude -p "{goal}" --output-format json --permission-mode acceptEdits
 */
@Serializable
data class DevAgentProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Абсолютный путь НА ПК (не на телефоне), например C:\diplom\jarvis */
    val workDir: String,
    val commandTemplate: String,
)

/** Тот же файловый JSON-паттерн, что и в CustomActionsRepository/TaskHistoryRepository. */
class DevAgentProfilesRepository(context: Context) {

    private val file = File(context.filesDir, "dev_agent_profiles.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _items = MutableStateFlow<List<DevAgentProfile>>(emptyList())
    val items: StateFlow<List<DevAgentProfile>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<DevAgentProfile>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun add(profile: DevAgentProfile) = withContext(Dispatchers.IO) {
        val updated = (_items.value + profile).take(MAX_PROFILES)
        _items.value = updated
        persist(updated)
    }

    suspend fun update(profile: DevAgentProfile) = withContext(Dispatchers.IO) {
        val updated = _items.value.map { if (it.id == profile.id) profile else it }
        _items.value = updated
        persist(updated)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val updated = _items.value.filterNot { it.id == id }
        _items.value = updated
        persist(updated)
    }

    private fun persist(list: List<DevAgentProfile>) {
        runCatching { file.writeText(json.encodeToString(list)) }
    }

    private companion object {
        /** Профилей мало и они настраиваются редко — не список задач. */
        const val MAX_PROFILES = 8
    }
}
```

---

## Промпт 5 — приложение: экран профилей и запуска (новый файл)

Создай `androidApp/src/main/kotlin/com/jarvis/app/DevAgentScreen.kt`:

```kotlin
package com.jarvis.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * Экран «Dev-агент»: профили внешних coding-агентов на ПК (папка проекта +
 * команда запуска — см. DevAgentProfiles.kt) — управление профилями и запуск
 * задачи по одному из них. Результат приходит в чат обычным сообщением ПК
 * (ChatViewModel.sendDevAgentTask) — этот экран только выбирает профиль и
 * собирает цель; дальше всё как у любой другой команды: индикатор прогресса,
 * «⛔ Стоп», озвучка ответа — ничего из этого здесь заново не делаем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevAgentScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
) {
    val profiles by vm.devAgentProfiles.collectAsState()
    var editing by remember { mutableStateOf<DevAgentProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<DevAgentProfile?>(null) }

    if (showEditor) {
        ProfileEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false; editing = null },
            onSave = { profile ->
                if (editing == null) vm.addDevAgentProfile(profile) else vm.updateDevAgentProfile(profile)
                showEditor = false
                editing = null
            },
        )
    }

    running?.let { profile ->
        RunGoalDialog(
            profile = profile,
            onDismiss = { running = null },
            onRun = { goal ->
                vm.sendDevAgentTask(profile, goal)
                running = null
                onBack()
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Dev-агент", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                    actions = {
                        IconButton(onClick = { editing = null; showEditor = true }) { Text("➕") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "Пока нет ни одного профиля. Нажмите ➕ и укажите папку " +
                        "проекта на ПК и команду запуска вашего coding-агента, " +
                        "например:\nclaude -p \"{goal}\" --output-format json --permission-mode acceptEdits\n" +
                        "{goal} заменится на то, что вы продиктуете.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    onRun = { running = profile },
                    onEdit = { editing = profile; showEditor = true },
                    onDelete = { vm.removeDevAgentProfile(profile.id) },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: DevAgentProfile,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onRun,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🧑\u200D💻", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    profile.workDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Text("✏") }
            IconButton(onClick = onDelete) { Text("🗑") }
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: DevAgentProfile?,
    onDismiss: () -> Unit,
    onSave: (DevAgentProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var workDir by remember { mutableStateOf(initial?.workDir ?: "") }
    var commandTemplate by remember { mutableStateOf(initial?.commandTemplate ?: "") }

    val valid = name.isNotBlank() && workDir.isNotBlank() && commandTemplate.contains("{goal}")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый профиль" else "Изменить профиль") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = workDir, onValueChange = { workDir = it },
                    label = { Text("Папка проекта на ПК") },
                    placeholder = { Text("C:\\diplom\\jarvis") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = commandTemplate, onValueChange = { commandTemplate = it },
                    label = { Text("Команда запуска") },
                    placeholder = { Text("claude -p \"{goal}\" --output-format json --permission-mode acceptEdits") },
                    supportingText = { Text("Обязательно должна содержать {goal}") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        DevAgentProfile(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            workDir = workDir.trim(),
                            commandTemplate = commandTemplate.trim(),
                        )
                    )
                },
            ) { Text(if (initial == null) "Добавить" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun RunGoalDialog(
    profile: DevAgentProfile,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var goal by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Что сделать в «${profile.workDir}»?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = goal, onValueChange = { goal = it },
                    placeholder = { Text("например: обнови зависимость Compose до последней версии") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = goal.isNotBlank(), onClick = { onRun(goal.trim()) }) { Text("Отправить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
```

`minLines` у `OutlinedTextField` — стандартный параметр Material3, но
версия не проверялась именно под него (как и `ExposedDropdownMenuBox` в
прошлой фиче) — если IDE подсветит как неизвестный, убери `minLines =`,
поле просто останется однострочным без переносов, на функциональность
это не влияет.

---

## Промпт 6 — `ChatViewModel.kt`: подключить профили и отправку задачи

Файл: `androidApp/src/main/kotlin/com/jarvis/app/ChatViewModel.kt`.
Импорты не нужны — `DevAgentTask` приходит через уже существующий
`import com.jarvis.protocol.*`, `DevAgentProfile`/`DevAgentProfilesRepository`
в том же пакете `com.jarvis.app`.

### 6.1. Поле репозитория и StateFlow

```kotlin
// было
    private val customActionsRepo = CustomActionsRepository(app)

    /** Пользовательские быстрые команды — читают ряд чипов в чате и экран «Свои команды». */
    val customActions: StateFlow<List<CustomActionRecord>> = customActionsRepo.items
// стало
    private val customActionsRepo = CustomActionsRepository(app)
    private val devAgentProfilesRepo = DevAgentProfilesRepository(app)

    /** Пользовательские быстрые команды — читают ряд чипов в чате и экран «Свои команды». */
    val customActions: StateFlow<List<CustomActionRecord>> = customActionsRepo.items

    /** Профили внешних coding-агентов — читает экран «Dev-агент». */
    val devAgentProfiles: StateFlow<List<DevAgentProfile>> = devAgentProfilesRepo.items
```

(Если вы уже применили `STEP_CUSTOM_QUICK_ACTIONS_PROMPTS.md` — блок
`customActionsRepo`/`customActions` уже на месте, просто добавь две новые
строки рядом. Если ещё не применили — ищи якорь `private val history =
TaskHistoryRepository(app)` и добавляй оба поля после него.)

### 6.2. Загрузка при старте

```kotlin
// было
        viewModelScope.launch { history.load() }
        viewModelScope.launch { customActionsRepo.load() }
// стало
        viewModelScope.launch { history.load() }
        viewModelScope.launch { customActionsRepo.load() }
        viewModelScope.launch { devAgentProfilesRepo.load() }
```

(Если `customActionsRepo.load()` ещё нет — добавь `devAgentProfilesRepo.load()`
сразу после `history.load()`.)

### 6.3. Управление профилями

Добавь рядом с `addCustomAction`/`updateCustomAction`/`removeCustomAction`
(или, если их ещё нет, сразу после `fun clearHistory() { ... }`):

```kotlin
    fun addDevAgentProfile(profile: DevAgentProfile) {
        viewModelScope.launch { devAgentProfilesRepo.add(profile) }
    }

    fun updateDevAgentProfile(profile: DevAgentProfile) {
        viewModelScope.launch { devAgentProfilesRepo.update(profile) }
    }

    fun removeDevAgentProfile(id: String) {
        viewModelScope.launch { devAgentProfilesRepo.remove(id) }
    }
```

### 6.4. Отправка задачи — новый метод рядом с `send(text: String)`

Это НЕ `sendCommand()` (тот путь без чата/прогресса) — `dev_agent_task`
может выполняться минутами, поэтому ему нужны и пузырь в чате, и статус
SENT→RUNNING→DONE/ERROR (чтобы работали индикатор и кнопка «⛔ Стоп»), как
у обычной команды. Структура — копия `send(text: String)`, только вместо
`NaturalLanguage(text)` — готовый `DevAgentTask`:

```kotlin
    /** Запустить dev-агента по профилю. Тот же путь прогресса, что у обычной команды — просто не через NL. */
    fun sendDevAgentTask(profile: DevAgentProfile, goal: String) {
        if (goal.isBlank()) return
        if (activeAgentOffline) {
            val name = _activeDevice.value?.name
            note(
                if (name != null) "ПК «$name» сейчас офлайн — включите агент на компьютере и повторите"
                else "ПК сейчас офлайн — команда не отправлена"
            )
            return
        }
        val requestId = UUID.randomUUID().toString()
        add(
            ChatMessage(
                author = Author.ME,
                text = "🧑\u200D💻 ${profile.name}: $goal",
                requestId = requestId,
                progress = CommandProgress.SENT,
            )
        )
        client.send(
            CommandRequest(
                requestId = requestId,
                command = DevAgentTask(
                    workDir = profile.workDir,
                    commandTemplate = profile.commandTemplate,
                    goal = goal,
                ),
            )
        )
        viewModelScope.launch {
            delay(600)
            updateProgress(requestId) { if (it == CommandProgress.SENT) CommandProgress.RUNNING else it }
        }
    }
```

Результат придёт как обычный `CommandResult` — `handle()` его уже умеет
обрабатывать ОБЩИМ путём (совпадение по `requestId`, `resultText()` берёт
`msg.message`, которое агент прислал как `ok(...)`/`error(...)`), так что
в `ChatViewModel.kt` больше ничего трогать не нужно: то же поле, что
покажется в чате, озвучится TTS.

---

## Промпт 7 — `MainActivity.kt`: экран и иконка

### 7.1. Новый экран в `AppScreen`

```kotlin
// было
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC, CUSTOM_ACTIONS }
// стало
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC, CUSTOM_ACTIONS, DEV_AGENT }
```

(Если `CUSTOM_ACTIONS` ещё не применялась — у тебя в `enum` не будет этого
элемента, просто допиши `DEV_AGENT` в конец того списка, что есть.)

### 7.2. Навигация

```kotlin
// было
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                                onOpenHistory = { screen = AppScreen.HISTORY },
                                onOpenMultiPc = { screen = AppScreen.MULTI_PC },
                                onOpenCustomActions = { screen = AppScreen.CUSTOM_ACTIONS },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.HISTORY -> HistoryScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.MULTI_PC -> MultiPcScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.CUSTOM_ACTIONS -> CustomActionsScreen(onBack = { screen = AppScreen.CHAT })
// стало
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                                onOpenHistory = { screen = AppScreen.HISTORY },
                                onOpenMultiPc = { screen = AppScreen.MULTI_PC },
                                onOpenCustomActions = { screen = AppScreen.CUSTOM_ACTIONS },
                                onOpenDevAgent = { screen = AppScreen.DEV_AGENT },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.HISTORY -> HistoryScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.MULTI_PC -> MultiPcScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.CUSTOM_ACTIONS -> CustomActionsScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.DEV_AGENT -> DevAgentScreen(onBack = { screen = AppScreen.CHAT })
```

(Если `CUSTOM_ACTIONS`/`onOpenCustomActions` ещё нет — ориентируйся на
`MULTI_PC` как якорь и добавляй только строки про `DEV_AGENT`.)

### 7.3. Параметр `ChatScreen`

```kotlin
// было
    onOpenMultiPc: () -> Unit = {},
    onOpenCustomActions: () -> Unit = {},
) {
// стало
    onOpenMultiPc: () -> Unit = {},
    onOpenCustomActions: () -> Unit = {},
    onOpenDevAgent: () -> Unit = {},
) {
```

### 7.4. Иконка в шапке чата

```kotlin
// было
                        IconButton(onClick = onOpenHistory) { Text("🕘") }
                        IconButton(onClick = onOpenCustomActions) { Text("🧩") }
// стало
                        IconButton(onClick = onOpenHistory) { Text("🕘") }
                        IconButton(onClick = onOpenCustomActions) { Text("🧩") }
                        IconButton(onClick = onOpenDevAgent) { Text("🧑\u200D💻") }
```

(Если `🧩`/`onOpenCustomActions` ещё нет — добавь иконку 🧑‍💻 сразу после
`IconButton(onClick = onOpenHistory) { Text("🕘") }`.)

---

## Проверка

- [ ] Собираются все четыре модуля: `shared`, `jarvis-agent`, `jarvis-relay`, `androidApp`.
- [ ] **Сначала протестировать на ЛЮБОЙ тестовой папке, НЕ на дипломном репозитории** — заведите профиль с `workDir` на пустую тестовую папку (например `C:\Temp\dev-agent-test`, `git init` там) и командой вроде `claude -p "{goal}" --output-format json --permission-mode acceptEdits`, продиктуйте что-то безобидное («создай файл hello.txt с текстом привет») — только после того, как это надёжно сработает несколько раз, заводите профиль на реальный репозиторий.
- [ ] Открыть 🧑‍💻 в шапке чата — экран «Dev-агент», пусто, текст-подсказка с примером команды на месте.
- [ ] ➕ → заполнить профиль (без `{goal}` в команде — кнопка «Добавить» неактивна) → с `{goal}` — активна, сохранить, карточка появилась.
- [ ] Тап по карточке (не по ✏/🗑) — открывается диалог «Что сделать?», ввести цель, «Отправить» — экран закрылся, в чате появилось сообщение с прогрессом (часы/точки), на ПК всплыло окно подтверждения с папкой и целью.
- [ ] На ПК нажать «Разрешить» — через какое-то время в чате появляется ответ ПК с результатом (текст из `result` в JSON от `claude -p`), TTS (если включена) озвучивает его.
- [ ] На ПК нажать «Отклонить» (или не ответить за 20 с) — в чате приходит ERROR с понятной причиной, процесс не запускается.
- [ ] Пока dev-задача выполняется — нажать «⛔ Стоп» в чате — процесс на ПК завершается (проверить в диспетчере задач/по логам агента), в чате приходит «dev-задача отменена».
- [ ] Продиктовать цель с кавычкой или амперсандом (`"`, `&`) — команда НЕ уходит на ПК, в чате сразу приходит ошибка про недопустимые символы (проверка на телефоне/относительно — на стороне агента, так что сообщение придёт как обычный ERROR).
- [ ] Пока dev-задача выполняется, попробовать запустить обычный `agent_task` (или другую dev-задачу) — приходит «агент занят: выполняется …» (общая эксклюзивность через busyGoal).
- [ ] ПК офлайн — попытка отправить dev-задачу сразу даёт понятное сообщение в чат, ничего не зависает.
- [ ] Кириллица в цели и в результате — не кракозябры (если кракозябры — см. оговорку про `chcp 65001 &&` в Промпте 3.4).
- [ ] Профиль с заведомо кривым `workDir` (несуществующая папка) — приходит понятная ошибка запуска, а не зависание/краш агента.
- [ ] Редактирование и удаление профиля на экране «Dev-агент» работают, не задваивая записи (id сохраняется при редактировании).
- [ ] Долгая задача (реальный рефакторинг, не однострочный тест) — укладывается в 10-минутный таймаут или явно и с понятным сообщением обрывается по истечении.

## Что осталось незакрытым (осознанно)

- Нет живого показа шагов coding-агента (как у `AgentStep` для vision-агента) — только финальный результат. `claude -p --output-format stream-json` это умеет отдать построчно, но разбор NDJSON-потока — отдельная, более рискованная задача; естественное следующее усиление этой фичи.
- Экранирование цели — не полноценное (просто запрет опасных символов), не настоящий безопасный shell-quoting. Работает для обычных фраз, чувствительно к границам.
- `dev_agent_task` не пишется в `RunJournal` (тот заточен под `AgentReport` vision-агента) — соответственно, не попадёт в общий бенчмарк/метрики, если вы всё же вернётесь к измеримой части по нему отдельно.
- Один профиль = одна команда целиком; нет отдельных «инструментов» (build/test/commit) — это уже ближе к направлению MCP/tool-calling, которое обсуждали и сознательно не выбрали в этот раз.
- Профили настраиваются на телефоне и полностью доверяются при выполнении — тот же телефон, что управляет и всем остальным; отдельного PIN/биометрии именно на dev-агента нет (можно взять backlog-пункт 6, если понадобится дополнительный рубеж).
