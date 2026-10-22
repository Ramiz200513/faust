# Мульти-ПК: команда сразу нескольким компьютерам

## Общий контекст

Сейчас в приложении уже можно привязать несколько ПК (экран «Устройства»,
`DevicesScreen.kt` + `DeviceRepository.kt`) и переключать, какой из них
**активный** — именно ему уходят команды из чата. Но управлять несколькими
ПК **одновременно** нельзя: `ChatViewModel` держит ровно один `JarvisClient`,
а у того — ровно одно WebSocket-соединение (`private var ws: WebSocket?`).
На релее то же самое: `JarvisWebSocketHandler` привязывает каждую сессию
ровно к одному `targetAgentId` при авторизации. Значит «раздать одну и ту же
команду трём ПК сразу» через существующий чат-канал в принципе нельзя —
там только один провод к одному ПК.

Городить второй постоянный `JarvisClient` на каждый выбранный ПК — тяжело
и хрупко (нужно синхронизировать переподключения, обрывы связи, статусы —
для разовой рассылки это overkill). Вместо этого используем то, что уже
есть и уже проверено: `RelayPairing.kt` в этом же приложении открывает
**короткое одноразовое соединение** (`oneShot`) для проверки статуса ПК
(`checkStatus`) — подключился, дождался одного ответа, закрылся. Добавим
туда же `sendCommand()` — тот же принцип, только два сообщения подряд
(Auth → CommandRequest) вместо одного, и ждём `CommandResult`. Никаких
изменений на релее и агенте не нужно вообще: `CommandRequest`/`CommandResult`
и сами команды (`LockScreen`, `SetVolume`, `OpenUrl`) уже существуют,
проходят whitelist (`open_url`, `lock_screen`, `set_volume` — все трое уже
в `CommandWhitelist.ALLOWED`) и уже реализованы в `CommandExecutor` на
агенте (`LockScreen`/`SetVolume` уже используются как быстрые кнопки в
чате). Это чисто клиентская фича.

Получится новый экран «Мульти-ПК»: чек-боксы напротив каждого привязанного
ПК + три кнопки быстрых команд снизу («Заблокировать», «Тише», «Открыть
сайт»). Нажатие рассылает команду **параллельно** всем отмеченным ПК —
через `async`/`awaitAll`, так что офлайн или зависший ПК не тормозит
остальные — и под именем каждого ПК появляется свой результат (✓ готово /
✗ причина).

Четыре промпта: (1) добавить `sendCommand()` в `RelayPairing.kt`,
(2) новый `MultiPcViewModel.kt`, (3) новый `MultiPcScreen.kt`,
(4) подключить экран в `MainActivity.kt`. Порядок обязателен — экран
ссылается на вью-модель, вью-модель — на `sendCommand`.

---

## Промпт 1 — `RelayPairing.kt`: рассылка одной команды одному ПК

Файл: `androidApp/src/main/kotlin/com/jarvis/app/RelayPairing.kt`

Добавить новый sealed-интерфейс результата и функцию `sendCommand()` в
объект `RelayPairing`, сразу после функции `checkStatus()` (перед приватным
`oneShot()`). `sendCommand()` не переиспользует `oneShot()` напрямую (тому
нужен ровно один ответ, а тут нужно два сообщения — Auth, затем
CommandRequest), поэтому это отдельная реализация того же принципа
«открылись → дождались → закрылись».

Добавить после блока `enum class DeviceOnlineStatus { ... }` (перед `object RelayPairing`):

```kotlin
/** Итог отправки одной команды уже привязанному ПК (см. sendCommand). */
sealed interface CommandOutcome {
    data class Success(val result: CommandResult) : CommandOutcome
    /** Релей ответил, но отверг вход (например, ПК успели отвязать). */
    data class Rejected(val reason: String) : CommandOutcome
    /** Сеть, таймаут или ПК офлайн — не про саму команду. */
    data class Failed(val message: String) : CommandOutcome
}
```

Внутри `object RelayPairing { ... }`, сразу после `checkStatus()` (перед
`private suspend fun oneShot`), добавить:

```kotlin
    /**
     * Авторизоваться под уже привязанным ПК, отправить ОДНУ команду и
     * дождаться CommandResult. Как и [checkStatus] — короткое соединение
     * без переподключений; используется для одновременной рассылки одной
     * команды нескольким ПК сразу (экран «Мульти-ПК»), где держать
     * постоянный JarvisClient на каждый ПК было бы избыточно.
     */
    suspend fun sendCommand(device: PairedDevice, command: Command, timeoutMs: Long = 15_000): CommandOutcome {
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<CommandOutcome> { cont ->
                val httpUrl = device.relayUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
                val commandRequestId = UUID.randomUUID().toString()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JarvisJson.encodeToString<JarvisMessage>(
                                Auth(
                                    requestId = UUID.randomUUID().toString(),
                                    deviceId = device.id,
                                    token = device.token,
                                    role = DeviceRole.PHONE,
                                )
                            )
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = try {
                            JarvisJson.decodeFromString<JarvisMessage>(text)
                        } catch (e: Exception) {
                            Log.e("RelayPairing", "не разобрал ответ: $text", e)
                            null
                        }
                        when {
                            msg is AuthResult && msg.ok -> webSocket.send(
                                JarvisJson.encodeToString<JarvisMessage>(CommandRequest(commandRequestId, command))
                            )
                            msg is AuthResult -> {
                                if (cont.isActive) cont.resume(CommandOutcome.Rejected(msg.reason ?: "вход отклонён"))
                                webSocket.close(1000, "done")
                            }
                            msg is CommandResult && msg.requestId == commandRequestId -> {
                                if (cont.isActive) cont.resume(CommandOutcome.Success(msg))
                                webSocket.close(1000, "done")
                            }
                            // Прочее (например, случайный ScreenFrame) игнорируем — ждём именно свой CommandResult.
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("RelayPairing", "ошибка соединения", t)
                        if (cont.isActive) cont.resume(CommandOutcome.Failed(t.message ?: "ошибка соединения"))
                    }
                }

                val ws = http.newWebSocket(Request.Builder().url(httpUrl).build(), listener)
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
        return outcome ?: CommandOutcome.Failed("нет ответа от ПК (офлайн или таймаут)")
    }
```

Импорты уже есть все нужные (`Command`, `CommandRequest`, `CommandResult` —
через `import com.jarvis.protocol.*`; `UUID`, `WebSocket`, `Response`,
`WebSocketListener`, `suspendCancellableCoroutine`, `withTimeoutOrNull` —
уже импортированы в этом файле). Ничего добавлять в `import` не нужно.

---

## Промпт 2 — новый файл `MultiPcViewModel.kt`

Файл: `androidApp/src/main/kotlin/com/jarvis/app/MultiPcViewModel.kt` (новый)

```kotlin
package com.jarvis.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.protocol.Command
import com.jarvis.protocol.CommandStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние отправки команды одному ПК из списка «Мульти-ПК». */
enum class BroadcastState { IDLE, SENDING, OK, ERROR }

/** Одна строка экрана «Мульти-ПК»: привязанный ПК + отмечен ли он + результат последней рассылки. */
data class MultiPcRow(
    val device: PairedDevice,
    val selected: Boolean,
    val state: BroadcastState = BroadcastState.IDLE,
    val detail: String? = null,
)

/**
 * Состояние экрана «Мульти-ПК»: выбор нескольких уже привязанных ПК и
 * рассылка одной и той же быстрой команды всем выбранным сразу.
 *
 * В отличие от чата (который всегда говорит с ОДНИМ активным ПК через
 * постоянный JarvisClient), тут на каждый выбранный ПК открывается своё
 * короткое соединение через RelayPairing.sendCommand — параллельно,
 * независимо друг от друга: зависший или офлайн-ПК не блокирует остальные,
 * у каждого свой результат в списке.
 */
class MultiPcViewModel(app: Application) : AndroidViewModel(app) {

    private val devicesRepo = DeviceRepository(app)

    private val _rows = MutableStateFlow<List<MultiPcRow>>(emptyList())
    val rows: StateFlow<List<MultiPcRow>> = _rows.asStateFlow()

    init {
        viewModelScope.launch {
            devicesRepo.devices.collect { list ->
                // Список ПК мог обновиться (добавили/удалили) — не теряем отметки
                // и результаты уже выбранных строк.
                val prevById = _rows.value.associateBy { it.device.id }
                _rows.value = list.map { d ->
                    val prev = prevById[d.id]
                    MultiPcRow(
                        device = d,
                        selected = prev?.selected ?: false,
                        state = prev?.state ?: BroadcastState.IDLE,
                        detail = prev?.detail,
                    )
                }
            }
        }
    }

    fun toggle(deviceId: String) {
        _rows.value = _rows.value.map {
            if (it.device.id == deviceId) it.copy(selected = !it.selected) else it
        }
    }

    /** Отметить/снять все разом (кнопка «Все»/«Снять» в шапке). */
    fun selectAll(select: Boolean) {
        _rows.value = _rows.value.map { it.copy(selected = select) }
    }

    /** Отправить команду всем отмеченным ПК параллельно, независимо друг от друга. */
    fun broadcast(command: Command) {
        val targets = _rows.value.filter { it.selected }
        if (targets.isEmpty()) return

        _rows.value = _rows.value.map {
            if (it.selected) it.copy(state = BroadcastState.SENDING, detail = null) else it
        }

        viewModelScope.launch {
            val outcomes = targets
                .map { row -> async { row.device.id to RelayPairing.sendCommand(row.device, command) } }
                .awaitAll()
                .toMap()

            _rows.value = _rows.value.map { row ->
                val outcome = outcomes[row.device.id] ?: return@map row
                val (state, detail) = when (outcome) {
                    is CommandOutcome.Success -> {
                        val ok = outcome.result.status == CommandStatus.OK
                        (if (ok) BroadcastState.OK else BroadcastState.ERROR) to
                            (outcome.result.message ?: if (ok) "готово" else outcome.result.status.name)
                    }
                    is CommandOutcome.Rejected -> BroadcastState.ERROR to outcome.reason
                    is CommandOutcome.Failed -> BroadcastState.ERROR to outcome.message
                }
                row.copy(state = state, detail = detail)
            }
        }
    }
}
```

---

## Промпт 3 — новый файл `MultiPcScreen.kt`

Файл: `androidApp/src/main/kotlin/com/jarvis/app/MultiPcScreen.kt` (новый)

```kotlin
package com.jarvis.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.protocol.LockScreen
import com.jarvis.protocol.OpenUrl
import com.jarvis.protocol.SetVolume

/**
 * Экран «Мульти-ПК»: отметить несколько из уже привязанных ПК и разослать
 * им одну и ту же быструю команду разом. См. MultiPcViewModel — каждому
 * отмеченному ПК уходит независимое короткое соединение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPcScreen(onBack: () -> Unit, vm: MultiPcViewModel = viewModel()) {
    val rows by vm.rows.collectAsState()
    var showUrlDialog by remember { mutableStateOf(false) }

    val selectedCount = rows.count { it.selected }
    val sending = rows.any { it.state == BroadcastState.SENDING }

    if (showUrlDialog) {
        UrlDialog(
            onConfirm = { url -> showUrlDialog = false; vm.broadcast(OpenUrl(url)) },
            onDismiss = { showUrlDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мульти-ПК") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { vm.selectAll(selectedCount < rows.size) }) {
                            Text(if (selectedCount < rows.size) "Все" else "Снять")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (rows.size < 2) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Нужно хотя бы два привязанных ПК.\nПривяжите ещё один на экране «Устройства».",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.device.id }) { row ->
                    MultiPcRowView(row = row, onToggle = { vm.toggle(row.device.id) })
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.broadcast(LockScreen) },
                    enabled = selectedCount > 0 && !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("🔒 Блок.") }
                OutlinedButton(
                    onClick = { vm.broadcast(SetVolume(0)) },
                    enabled = selectedCount > 0 && !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("🔇 Тише") }
                OutlinedButton(
                    onClick = { showUrlDialog = true },
                    enabled = selectedCount > 0 && !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("🌐 Сайт") }
            }
        }
    }
}

@Composable
private fun MultiPcRowView(row: MultiPcRow, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = row.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(row.device.name, style = MaterialTheme.typography.bodyLarge)
                when (row.state) {
                    BroadcastState.SENDING -> Text("отправка…", style = MaterialTheme.typography.labelMedium)
                    BroadcastState.OK -> Text(
                        "✓ ${row.detail ?: "готово"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = onlineColor(),
                    )
                    BroadcastState.ERROR -> Text(
                        "✗ ${row.detail ?: "ошибка"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    BroadcastState.IDLE -> {}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть сайт на выбранных ПК") },
        text = {
            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(url) }) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
```

`onlineColor()` уже объявлена в проекте (используется в `DevicesScreen.kt`
тем же способом, без импорта — она в этом же пакете `com.jarvis.app`),
поэтому здесь тоже ничего дополнительно импортировать не нужно.

---

## Промпт 4 — подключить экран в `MainActivity.kt`

Файл: `androidApp/src/main/kotlin/com/jarvis/app/MainActivity.kt`

**4.1.** Добавить пункт в перечисление экранов:

```kotlin
// было
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY }
// стало
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC }
```

**4.2.** В `when (screen) { ... }` внутри `MainActivity.onCreate`, рядом с
другими `AppScreen.* -> ...Screen(...)`, добавить новую ветку и прокинуть
колбэк в `ChatScreen`:

```kotlin
// было
                        var screen by remember { mutableStateOf(AppScreen.CHAT) }
                        when (screen) {
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                                onOpenHistory = { screen = AppScreen.HISTORY },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.HISTORY -> HistoryScreen(onBack = { screen = AppScreen.CHAT })
                        }
// стало
                        var screen by remember { mutableStateOf(AppScreen.CHAT) }
                        when (screen) {
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                                onOpenHistory = { screen = AppScreen.HISTORY },
                                onOpenMultiPc = { screen = AppScreen.MULTI_PC },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.HISTORY -> HistoryScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.MULTI_PC -> MultiPcScreen(onBack = { screen = AppScreen.CHAT })
                        }
```

**4.3.** Добавить параметр в сигнатуру `ChatScreen`:

```kotlin
// было
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
// стало
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenMultiPc: () -> Unit = {},
) {
```

**4.4.** Добавить кнопку в шапку чата, рядом с кнопкой «Устройства» (🖥):

```kotlin
// было
                        IconButton(onClick = onOpenDevices) { Text("🖥") }
                        IconButton(onClick = onOpenScreenView) { Text("🖵") }
// стало
                        IconButton(onClick = onOpenDevices) { Text("🖥") }
                        IconButton(onClick = onOpenMultiPc) { Text("📡") }
                        IconButton(onClick = onOpenScreenView) { Text("🖵") }
```

---

## Проверка

- [ ] Открывается сборка: `RelayPairing.kt` компилируется, `CommandOutcome` виден и в `MultiPcViewModel.kt` (тот же пакет `com.jarvis.app`).
- [ ] На главном экране чата появилась новая иконка 📡 рядом с 🖥.
- [ ] Если привязан только один ПК — экран «Мульти-ПК» показывает «нужно хотя бы два» вместо пустого списка.
- [ ] Привязать второй ПК (или воспользоваться тем, что уже привязаны из прошлых шагов) → на экране «Мульти-ПК» видно оба, чек-боксы независимые, «Все»/«Снять» переключает разом.
- [ ] Отметить оба ПК (у обоих запущен агент, оба онлайн) → нажать «🔒 Блок.» → на обоих ПК экран блокируется, в списке под каждым именем появляется «✓ готово» почти одновременно (не по очереди — важно на защите показать, что это параллельно, а не последовательный обход списка).
- [ ] Один из двух ПК выключить/закрыть агент → отметить оба → «🔇 Тише» → у выключенного в списке «✗ нет ответа от ПК (офлайн или таймаут)», у второго — «✓ готово»; второй ПК не ждёт первого (результат приходит сразу, а не через 15 секунд таймаута первого).
- [ ] «🌐 Сайт» → ввести URL → открывается на всех отмеченных ПК в браузере по умолчанию.
- [ ] Активный ПК в чате (тот, что выбран на экране «Устройства») никак не меняется после рассылки на экране «Мульти-ПК» — это независимые каналы, чат по-прежнему говорит с прежним активным ПК.
- [ ] Отдельно (уже почти наверняка так, раз всё выше работает) — Logcat не показывает исключений при переходе между экранами «Чат» ↔ «Мульти-ПК» туда-обратно несколько раз (проверка, что `MultiPcViewModel` не течёт и не падает при пересоздании).
