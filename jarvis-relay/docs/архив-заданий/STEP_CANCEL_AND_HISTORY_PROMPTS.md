# Шаг: отмена активной задачи + история задач (пункты 2 и 3 бэклога)

## Общий контекст

Две фичи одним документом, как договаривались по мелким пунктам. Но сначала —
важная находка, из-за которой этот шаг оказался больше, чем «маленький».

### Находка: отмену физически некуда доставить

Прочитал `RelayClient.java` и `CommandExecutor.java` в текущем виде. Сейчас
происходит вот что:

```java
// RelayClient.Listener.onText(...)
buffer.append(data);
if (last) {
    handle(buffer.toString());   // <- ВНУТРИ синхронно выполняется вся команда
    buffer.setLength(0);
}
webSocket.request(1);            // <- следующее сообщение запрашивается ТОЛЬКО ПОСЛЕ
```

`handle()` вызывает `executor.execute(...)`, а тот для `agent_task` крутит цикл
«скриншот → LLM → действие» до трёх минут (`AGENT_TASK_TIMEOUT`). Всё это время
`onText` не возвращается, `webSocket.request(1)` не вызывается — и агент **не
читает из сокета вообще ничего**. Ни `cancel_task`, ни `ping`, ни новую команду.

То есть добавить команду отмены мало: она долетит до агента и будет молча
лежать в TCP-буфере, пока задача сама не закончится. В самом коде это уже было
отмечено как долг:

> `NB: пока выполняется синхронно и блокирует поток WebSocket на время задачи`
> `(до AGENT_TASK_TIMEOUT). Для длинных задач это стоит вынести в отдельный`
> `поток и слать отчёт по завершении — следующий шаг.`

Поэтому Промпт 1 — это тот самый вынос в отдельный поток. Без него остальное
не заработает, и никакие «хитрости» это не обходят.

Это, кстати, хороший абзац для диплома: классический пример того, как
архитектурное решение (синхронная обработка сообщений) блокирует продуктовое
требование (отменяемость длинной операции), и как это чинится развязкой потоков.

### Что делаем

| Промпт | Где | Что |
|---|---|---|
| 1 | агент | `agent_task` уезжает в отдельный поток, сокет продолжает читаться |
| 2 | протокол ×3 | новая команда `cancel_task` |
| 3 | relay | `cancel_task` в whitelist |
| 4 | агент | флаг отмены + проверка между шагами в обоих агентах |
| 5 | приложение | кнопка «Стоп», пока задача идёт |
| 6-8 | приложение | экран истории задач |

### Важные инварианты, подтверждённые чтением кода

* **Новая КОМАНДА не требует правки `JarvisWebSocketHandler`.** Правка там нужна
  только для нового типа `JarvisMessage` (как было с `AgentStep`), потому что
  `forwardToPhones` — типизированный `switch`. `cancel_task` — это `Command`
  внутри уже существующего `CommandRequest`, и `handleCommand` пропускает его
  через общий путь «parse → whitelist → forward». Менять там нечего.
* **Whitelist на relay — это `security/CommandWhitelist.java`**, а не
  `Whitelist.kt` в `:shared`. Kotlin-версия давно отстала (в её `DEFAULT` нет ни
  стрима, ни буфера обмена, ни файлов) и фактически не используется. В этом шаге
  я её не трогаю, но имей в виду: **это мёртвый код, который выглядит живым** —
  на защите могут спросить. Стоит либо удалить, либо привести в соответствие
  отдельным мелким шагом.
* **История задач не требует протокола вообще.** `AgentReport.trace` уже
  приходит в `CommandResult`. Нужно только сохранить его на телефоне и показать.

Прочитанные файлы (актуальное состояние на момент написания): `RelayClient.java`
(10326 б), `CommandExecutor.java` (28010 б), `ScreenAgent.java` (10262 б),
`BrowserAgent.java` (18136 б), `StepReporter.java`, `Command.kt` (5497 б),
`Command.java` relay (5690 б) и agent (5690 б), `CommandWhitelist.java` (1158 б),
`JarvisWebSocketHandler.java` (11762 б), `ChatViewModel.kt` (22537 б),
`MainActivity.kt` (39461 б).

---

## Промпт 1 — `jarvis-agent`: `RelayClient.java`, долгая задача в отдельный поток

### 1.1. Импорты

Найди:

```java
import java.util.concurrent.Executors;
```

Замени на:

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

### 1.2. Пул для долгих задач

Найди:

```java
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
```

Добавь сразу после:

```java
    /**
     * Отдельный поток под agent_task. Раньше задача выполнялась прямо в колбэке
     * onText, и до её конца агент НЕ ЧИТАЛ сокет (webSocket.request(1)
     * вызывается только после возврата из onText) — то есть команда отмены
     * физически не могла дойти. Одного потока достаточно: параллельно две
     * задачи всё равно не выполняются, за этим следит busyGoal в CommandExecutor.
     * daemon — чтобы не мешал JVM завершиться.
     */
    private final ExecutorService agentPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-agent-task");
        t.setDaemon(true);
        return t;
    });
```

### 1.3. Развилка в `handle()`

Найди:

```java
                case JarvisMessage.CommandRequest req -> {
                    System.out.println("Command: " + req.command().name());
                    // Run it and send back every resulting message.
                    List<JarvisMessage> out = executor.execute(req.requestId(), req.command());
                    out.forEach(RelayClient.this::send);
                }
```

Замени на:

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
                        // В том числе cancel_task: он только взводит флаг, и должен
                        // сработать НЕМЕДЛЕННО, а не встать в очередь за задачей.
                        executor.execute(req.requestId(), req.command())
                                .forEach(RelayClient.this::send);
                    }
                }
```

> Если `Command` ещё не импортирован в `RelayClient.java` — добавь
> `import com.jarvis.agent.protocol.Command;` к остальным импортам.

---

## Промпт 2 — протокол: новая команда `cancel_task` в ТРЁХ местах

Инвариант проекта: любое изменение протокола правится синхронно в Kotlin
`:shared`, в Java relay и в Java agent. Файлы relay и agent идентичны —
правка одна и та же.

### 2.1. `jarvis/shared/src/commonMain/kotlin/com/jarvis/protocol/Command.kt`

Найди в конце файла:

```kotlin
@Serializable
@SerialName("send_file_to_pc")
data class SendFileToPc(val folder: String, val fileName: String, val base64: String) : Command {
    override val name: String get() = "send_file_to_pc"
}
```

Добавь после него:

```kotlin
// --- Layer 7: управление уже запущенной задачей ----------------------------

/**
 * Прервать выполняющийся [AgentTask]. Без параметров: одновременно всё равно
 * выполняется только одна задача (busyGoal в CommandExecutor), поэтому
 * указывать, какую именно, не нужно.
 *
 * ВАЖНО: агент обязан обрабатывать её вне потока, занятого задачей — см.
 * RelayClient.agentPool, иначе команда не будет прочитана до конца задачи.
 */
@Serializable
@SerialName("cancel_task")
data object CancelTask : Command {
    override val name: String get() = "cancel_task"
}
```

### 2.2. `jarvis-relay/.../protocol/Command.java` И `jarvis-agent/.../protocol/Command.java`

**Обе правки одинаковые, сделай их в обоих файлах.**

(а) В аннотации `@JsonSubTypes` найди последнюю строку:

```java
        @JsonSubTypes.Type(value = Command.SendFileToPc.class, name = "send_file_to_pc")
})
```

Замени на:

```java
        @JsonSubTypes.Type(value = Command.SendFileToPc.class, name = "send_file_to_pc"),
        @JsonSubTypes.Type(value = Command.CancelTask.class, name = "cancel_task")
})
```

> Обрати внимание на запятую, которой раньше не было в конце предпоследней строки.

(б) В списке `permits` найди:

```java
        Command.ListFiles, Command.SendFileToPhone, Command.SendFileToPc {
```

Замени на:

```java
        Command.ListFiles, Command.SendFileToPhone, Command.SendFileToPc,
        Command.CancelTask {
```

(в) В самом конце интерфейса, после:

```java
    record SendFileToPc(String folder, String fileName, String base64) implements Command {
        public String name() { return "send_file_to_pc"; }
    }
```

добавь:

```java

    // Layer 7: управление уже запущенной задачей
    record CancelTask() implements Command {
        public String name() { return "cancel_task"; }
    }
```

---

## Промпт 3 — `jarvis-relay`: whitelist

`security/CommandWhitelist.java`. Найди:

```java
            "list_files", "send_file_to_phone", "send_file_to_pc"
    );
```

Замени на:

```java
            "list_files", "send_file_to_phone", "send_file_to_pc",
            "cancel_task"
    );
```

> Без этой строки команда будет молча отклонена со статусом DENIED — это самая
> частая ошибка при добавлении команды в этом проекте.

---

## Промпт 4 — `jarvis-agent`: флаг отмены и его проверка

### 4.1. Новый файл `vision/CancelSignal.java`

Создай `jarvis-agent/src/main/java/com/jarvis/agent/vision/CancelSignal.java`:

```java
package com.jarvis.agent.vision;

/**
 * Способ спросить «а не отменили ли задачу?» — по той же схеме, что и
 * {@link StepReporter}: маленький интерфейс с заглушкой NONE, чтобы
 * demo-запуски и старые перегрузки run(...) не пришлось трогать.
 *
 * Проверяется МЕЖДУ шагами цикла. Прервать сам запрос к LLM (единицы секунд)
 * мы не можем — значит, реакция на отмену занимает до одного шага. Для
 * пользователя это «несколько секунд», и это честно стоит указать в UI.
 */
public interface CancelSignal {
    boolean isCancelled();

    /** Отмена не поддерживается (demo-запуски). */
    CancelSignal NONE = () -> false;
}
```

### 4.2. `vision/ScreenAgent.java`

(а) Найди:

```java
    /** Без живого показа шагов — старое поведение (трей и т.п.), теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }
```

Замени на:

```java
    /** Без живого показа шагов — старое поведение (трей и т.п.), теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }

    /** Без отмены — прежняя пятипараметровая перегрузка, чтобы старые вызовы не ломались. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                      IntConsumer onStep, StepReporter stepReporter) {
        return run(goal, maxSteps, deadline, onStep, stepReporter, CancelSignal.NONE);
    }
```

(б) Найди сигнатуру основного метода:

```java
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter) {
```

Замени на:

```java
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter,
                                       CancelSignal cancelSignal) {
```

(в) Найди начало цикла:

```java
        for (int step = 1; step <= steps; step++) {
            if (Instant.now().isAfter(deadline)) {
                trace.add("стоп: превышен общий лимит времени задачи");
                break;
            }
            onStep.accept(step);
```

Замени на:

```java
        for (int step = 1; step <= steps; step++) {
            if (cancelSignal.isCancelled()) {
                trace.add("стоп: задачу отменил пользователь");
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                trace.add("стоп: превышен общий лимит времени задачи");
                break;
            }
            onStep.accept(step);
```

### 4.3. `vision/BrowserAgent.java` — то же самое

(а) Найди:

```java
    /** Без живого показа шагов — старое поведение, теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }
```

Замени на:

```java
    /** Без живого показа шагов — старое поведение, теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }

    /** Без отмены — прежняя пятипараметровая перегрузка, чтобы старые вызовы не ломались. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                      IntConsumer onStep, StepReporter stepReporter) {
        return run(goal, maxSteps, deadline, onStep, stepReporter, CancelSignal.NONE);
    }
```

(б) Найди сигнатуру основного метода:

```java
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter) {
```

Замени на:

```java
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter,
                                       CancelSignal cancelSignal) {
```

(в) Найди начало цикла (здесь он вложен в `try`, поэтому отступ на 4 пробела
больше, чем в ScreenAgent):

```java
            for (int step = 1; step <= steps; step++) {
                if (Instant.now().isAfter(deadline)) {
                    trace.add("стоп: превышен общий лимит времени задачи");
                    break;
                }
                onStep.accept(step);
```

Замени на:

```java
            for (int step = 1; step <= steps; step++) {
                if (cancelSignal.isCancelled()) {
                    trace.add("стоп: задачу отменил пользователь");
                    break;
                }
                if (Instant.now().isAfter(deadline)) {
                    trace.add("стоп: превышен общий лимит времени задачи");
                    break;
                }
                onStep.accept(step);
```

### 4.4. `CommandExecutor.java`

(а) Импорт. Найди строку с `AtomicReference` в импортах и добавь рядом:

```java
import java.util.concurrent.atomic.AtomicBoolean;
```

(б) Поле. Найди:

```java
    /** Чем занят агент сейчас (цель agent_task или «показ экрана»); null — свободен. */
    private final AtomicReference<String> busyGoal = new AtomicReference<>();
```

Добавь сразу после:

```java
    /**
     * Попросили ли отменить текущую задачу. Сбрасывается в начале каждой новой
     * задачи, взводится командой cancel_task (которая приходит в ДРУГОМ потоке —
     * отсюда Atomic, а не обычный boolean).
     */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
```

(в) Диспетчер. Найди:

```java
                case Command.AgentTask c      -> agentTask(requestId, c);
```

Замени на:

```java
                case Command.AgentTask c      -> agentTask(requestId, c);
                case Command.CancelTask c     -> cancelTask(requestId);
```

(г) Сброс флага. Найди:

```java
    private List<JarvisMessage> agentTask(String requestId, Command.AgentTask task) {
        if (!busyGoal.compareAndSet(null, task.goal())) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
```

Замени на:

```java
    private List<JarvisMessage> agentTask(String requestId, Command.AgentTask task) {
        if (!busyGoal.compareAndSet(null, task.goal())) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        // Сбрасываем ПОСЛЕ захвата busyGoal: иначе отмена, пришедшая в момент
        // между двумя задачами, могла бы погасить уже следующую.
        cancelRequested.set(false);
```

(д) Сам обработчик отмены. Найди:

```java
    /** Связь с релеем пропала — кадры отправлять некуда, стрим гасим. */
    public void onDisconnected() {
        streamer.stop();
    }
```

Добавь **перед** этим блоком:

```java
    /**
     * Отмена текущей задачи. Выполняется в потоке чтения сокета (не в потоке
     * задачи) и делает ровно одно: взводит флаг. Сам цикл агента проверяет его
     * между шагами и выходит — поэтому фактическая остановка занимает до одного
     * шага, обычно несколько секунд.
     */
    private List<JarvisMessage> cancelTask(String requestId) {
        String goal = busyGoal.get();
        if (goal == null) {
            return ok(requestId, "сейчас нечего отменять");
        }
        cancelRequested.set(true);
        return ok(requestId, "отменяю: " + goal);
    }

```

(е) Передача сигнала в агентов. Найди:

```java
        long start = System.currentTimeMillis();
        ResultData.AgentReport report = switch (task.scope()) {
            case BROWSER -> browserAgent.run(goal, maxSteps, deadline, onStep, stepReporter);
            case SCREEN -> screenAgent.run(goal, maxSteps, deadline, onStep, stepReporter);
        };
```

Замени на:

```java
        CancelSignal cancelSignal = cancelRequested::get;

        long start = System.currentTimeMillis();
        ResultData.AgentReport report = switch (task.scope()) {
            case BROWSER -> browserAgent.run(goal, maxSteps, deadline, onStep, stepReporter, cancelSignal);
            case SCREEN -> screenAgent.run(goal, maxSteps, deadline, onStep, stepReporter, cancelSignal);
        };
```

Добавь импорт, если его нет:

```java
import com.jarvis.agent.vision.CancelSignal;
```

(ж) Понятный итог для отменённой задачи. Найди:

```java
        CommandStatus status = report.completed() ? CommandStatus.OK : CommandStatus.ERROR;
        String msg = (report.completed() ? "цель достигнута" : "цель не достигнута")
                + " за " + report.stepsTaken() + " шаг(ов), " + (durationMs / 1000) + " с";
```

Замени на:

```java
        boolean cancelled = cancelRequested.get();
        CommandStatus status = report.completed() ? CommandStatus.OK : CommandStatus.ERROR;
        String msg = (cancelled ? "задача отменена"
                : report.completed() ? "цель достигнута" : "цель не достигнута")
                + " за " + report.stepsTaken() + " шаг(ов), " + (durationMs / 1000) + " с";
```

---

## Промпт 5 — приложение: кнопка «Стоп»

### 5.1. `ChatViewModel.kt` — импорты

Найди:

```kotlin
import kotlinx.coroutines.flow.first
```

Замени на:

```kotlin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
```

### 5.2. `ChatViewModel.kt` — флаг «идёт работа»

Найди:

```kotlin
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
```

Добавь сразу после:

```kotlin
    /**
     * Есть ли сейчас незавершённая команда — по этому флагу UI показывает
     * кнопку «Стоп». Вычисляется из самих сообщений, чтобы не заводить второй
     * источник правды: статус уже живёт в ChatMessage.progress.
     */
    val busy: StateFlow<Boolean> = _messages
        .map { list ->
            list.any { it.progress == CommandProgress.SENT || it.progress == CommandProgress.RUNNING }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

### 5.3. `ChatViewModel.kt` — сама отмена

Найди:

```kotlin
    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
```

Добавь **перед** этой строкой:

```kotlin
    /**
     * Прервать выполняющуюся задачу. Остановка не мгновенная: агент проверяет
     * флаг между шагами, а прервать текущий запрос к модели нельзя — поэтому
     * честно предупреждаем пользователя, а не делаем вид, что всё встало сразу.
     */
    fun cancelTask() {
        sendCommand(CancelTask)
        note("отменяю задачу — агент остановится после текущего шага")
    }

```

### 5.4. `MainActivity.kt` — кнопка в ряду быстрых команд

Найди:

```kotlin
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
```

Замени на:

```kotlin
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
```

Затем найди:

```kotlin
                QUICK_ACTIONS.forEach { action ->
                    AssistChip(
                        onClick = { action.run(vm) },
                        label = { Text("${action.emoji} ${action.label}") },
                    )
                }
```

Замени на:

```kotlin
                // Показываем только пока что-то выполняется — постоянная кнопка
                // «Стоп» в интерфейсе, которая почти всегда неактивна, только
                // занимает место и путает.
                if (busy) {
                    AssistChip(
                        onClick = { vm.cancelTask() },
                        label = { Text("⛔ Стоп") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }
                QUICK_ACTIONS.forEach { action ->
                    AssistChip(
                        onClick = { action.run(vm) },
                        label = { Text("${action.emoji} ${action.label}") },
                    )
                }
```

> `AssistChipDefaults` приходит из `androidx.compose.material3.*` — он уже
> импортирован звёздочкой. `CancelTask` в `ChatViewModel.kt` приходит из
> `com.jarvis.protocol.*` — тоже уже импортировано звёздочкой.

---

## Промпт 6 — приложение: хранилище истории (новый файл `TaskHistory.kt`)

Создай `androidApp/src/main/kotlin/com/jarvis/app/TaskHistory.kt`:

```kotlin
package com.jarvis.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Одна выполненная задача агента. Ровно то, что уже приходит в AgentReport,
 * плюс текст запроса пользователя (его AgentReport не содержит — берём из
 * сообщения чата с тем же requestId) и момент времени.
 */
@Serializable
data class TaskRecord(
    val id: String,
    val startedAtMs: Long,
    /** Что попросил пользователь — фраза из чата. */
    val request: String,
    val completed: Boolean,
    val stepsTaken: Int,
    /** Итоговая строка от агента («цель достигнута за 4 шаг(ов), 22 с»). */
    val summary: String,
    /** Пошаговый трейс — то, что агент писал по ходу работы. */
    val trace: List<String>,
)

/**
 * История задач на самом телефоне. Протокол не трогаем: всё нужное уже
 * приходит в CommandResult, вопрос только в том, чтобы это не выбрасывать.
 *
 * Хранилище — один JSON-файл во внутренней памяти приложения. DataStore тут
 * не подходит: он для небольших пар «ключ-значение», а трейсы растут.
 * Держим последние [MAX_RECORDS] записей — файл не должен расти бесконечно.
 */
class TaskHistoryRepository(context: Context) {

    private val file = File(context.filesDir, "task_history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _items = MutableStateFlow<List<TaskRecord>>(emptyList())
    /** Новые записи сверху. */
    val items: StateFlow<List<TaskRecord>> = _items.asStateFlow()

    /** Прочитать файл. Поломанный/устаревший файл — не повод падать, просто начинаем с пустой истории. */
    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<TaskRecord>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun add(record: TaskRecord) = withContext(Dispatchers.IO) {
        val updated = (listOf(record) + _items.value).take(MAX_RECORDS)
        _items.value = updated
        runCatching { file.writeText(json.encodeToString(updated)) }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _items.value = emptyList()
        runCatching { file.delete() }
        Unit
    }

    private companion object {
        const val MAX_RECORDS = 50
    }
}
```

---

## Промпт 7 — приложение: сохранение истории в `ChatViewModel.kt`

### 7.1. Репозиторий

Найди:

```kotlin
    private val settings = SettingsRepository(app)
```

Замени на:

```kotlin
    private val settings = SettingsRepository(app)
    private val history = TaskHistoryRepository(app)

    /** История задач — читает экран «История». */
    val taskHistory: StateFlow<List<TaskRecord>> = history.items
```

### 7.2. Загрузка при старте

В `init { ... }` найди:

```kotlin
        viewModelScope.launch { WakeWordBus.phrases.collect { send(it) } }
```

Добавь **перед** этой строкой:

```kotlin
        viewModelScope.launch { history.load() }
```

### 7.3. Запись отчёта

Найди в `handle()`:

```kotlin
                    is AgentReport -> if (isBackgrounded) notifyAgentTaskFinished(getApplication(), resultText(msg))
```

Замени на:

```kotlin
                    is AgentReport -> {
                        saveToHistory(msg, data)
                        if (isBackgrounded) notifyAgentTaskFinished(getApplication(), resultText(msg))
                    }
```

### 7.4. Метод сохранения и очистки

Найди:

```kotlin
    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
```

Добавь **перед** этой строкой:

```kotlin
    /**
     * Кладём завершённую задачу в историю. Текст запроса берём из сообщения
     * чата с тем же requestId — сам AgentReport его не содержит (агент знает
     * цель, но в отчёт она не попадает).
     */
    private fun saveToHistory(result: CommandResult, report: AgentReport) {
        val request = _messages.value
            .firstOrNull { it.author == Author.ME && it.requestId == result.requestId }
            ?.text
            .orEmpty()
        viewModelScope.launch {
            history.add(
                TaskRecord(
                    id = result.requestId,
                    startedAtMs = System.currentTimeMillis(),
                    request = request,
                    completed = report.completed,
                    stepsTaken = report.stepsTaken,
                    summary = resultText(result),
                    trace = report.trace,
                )
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

```

---

## Промпт 8 — приложение: экран истории

### 8.1. Новый файл `HistoryScreen.kt`

Создай `androidApp/src/main/kotlin/com/jarvis/app/HistoryScreen.kt`:

```kotlin
package com.jarvis.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * История выполненных задач агента. Данные локальные (см. TaskHistoryRepository) —
 * сети этот экран не касается вообще.
 *
 * Каждая карточка сворачивается/разворачивается по нажатию: свёрнутая — итог
 * одной строкой, развёрнутая — весь трейс. Трейс бывает на 20 строк, показывать
 * его всегда означало бы список, по которому невозможно листать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
) {
    val items by vm.taskHistory.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Записи о ${items.size} задачах будут удалены с телефона.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История задач") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Text("🗑") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "Пока пусто. Здесь появятся задачи агента — что просили, " +
                        "получилось ли и сколько шагов заняло.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items, key = { it.id }) { record -> HistoryCard(record) }
        }
    }
}

@Composable
private fun HistoryCard(record: TaskRecord) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(record.startedAtMs) {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(record.startedAtMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (record.completed) "✅" else "❌")
                Text(
                    record.request.ifBlank { "(без текста запроса)" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(time, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                record.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                record.trace.forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                }
            } else if (record.trace.isNotEmpty()) {
                Text(
                    "нажмите, чтобы посмотреть ${record.trace.size} шаг(ов)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
```

### 8.2. `MainActivity.kt` — новый экран в навигации

Найди:

```kotlin
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW }
```

Замени на:

```kotlin
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY }
```

Найди:

```kotlin
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
```

Замени на:

```kotlin
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                                onOpenHistory = { screen = AppScreen.HISTORY },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.HISTORY -> HistoryScreen(onBack = { screen = AppScreen.CHAT })
```

### 8.3. `MainActivity.kt` — параметр и кнопка

Найди:

```kotlin
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
) {
```

Замени на:

```kotlin
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
```

Найди:

```kotlin
                    IconButton(onClick = { showFiles = true }) { Text("📁") }
```

Замени на:

```kotlin
                    IconButton(onClick = { showFiles = true }) { Text("📁") }
                    IconButton(onClick = onOpenHistory) { Text("🕘") }
```

---

## Проверка

**Отмена**

1. Собери и запусти всё три части. Релей обязательно перезапусти — иначе новая
   команда не пройдёт whitelist (он читается при старте как статическое поле).
2. Запусти долгую задачу: «найди на ютубе видео про котов и включи его».
   Пока идут шаги, в ряду быстрых команд должна появиться красная «⛔ Стоп».
3. Нажми «Стоп» на 2-3 шаге. В чате появляется «отменяю задачу…», затем в
   течение нескольких секунд — итог «задача отменена за N шаг(ов)».
   **Ключевая проверка этого шага**: до правки RelayClient команда отмены не
   доходила вообще, задача докручивалась до конца.
4. Нажми «Стоп», когда ничего не выполняется (если сумеешь поймать момент, или
   отправь `cancel_task` вручную) — агент должен ответить «сейчас нечего
   отменять», а не упасть.
5. Запусти задачу, отмени, **сразу запусти новую** — новая должна нормально
   выполниться до конца, а не отмениться на первом же шаге (за это отвечает
   `cancelRequested.set(false)` в `agentTask`).
6. Проверь, что сокет теперь действительно читается во время задачи: запусти
   долгую задачу и, пока она идёт, нажми быструю команду 🔒 или 🔊. Раньше она
   зависла бы до конца задачи; теперь агент должен ответить сразу (либо выполнить,
   либо сказать «агент занят», в зависимости от команды).
7. Логи релея: при отмене должна быть строка аудита с `cancel_task`, а НЕ DENIED.

**История**

8. Выполни 2-3 задачи агента (успешную и заведомо провальную — например,
   «нажми несуществующую кнопку»). Открой 🕘 — обе должны быть в списке,
   новая сверху, с ✅/❌.
9. Нажми на карточку — разворачивается полный трейс шагов.
10. Отменённая задача тоже должна попасть в историю — с ❌ и строкой
    «стоп: задачу отменил пользователь» в трейсе.
11. Закрой и открой приложение — история на месте (файл `task_history.json`).
12. Нажми 🗑 → «Очистить» — список пустеет, после перезапуска остаётся пустым.
13. Выполни больше 50 задач (или временно поставь `MAX_RECORDS = 3` и выполни
    четыре) — самая старая должна вытесняться, файл не должен расти бесконечно.

---

## Что осталось незакрытым (осознанно)

* **Отмена не мгновенная.** Прервать текущий запрос к модели нельзя, поэтому
  реакция — до одного шага. В UI об этом сказано прямо. Мгновенная отмена
  потребовала бы асинхронного HTTP-клиента в `RelayLlmClient` с возможностью
  отмены запроса — отдельный шаг, если захочется.
* **История только на телефоне.** Она не синхронизируется между устройствами и
  не попадает на relay. Если для главы про эмпирическую оценку понадобится
  собирать статистику централизованно — у агента уже есть `RunJournal`
  (`runs\*.jsonl` на ПК), а на relay есть PostgreSQL; это отдельная работа,
  пересекающаяся с пунктом «метрики и дашборд» из бэклога.
* **`Whitelist.kt` в `:shared` остаётся мёртвым кодом.** Он не используется, но
  выглядит как настоящая защита. Стоит удалить отдельным маленьким коммитом,
  чтобы на защите не пришлось объяснять, почему в проекте два whitelist-а и
  один из них неполный.
