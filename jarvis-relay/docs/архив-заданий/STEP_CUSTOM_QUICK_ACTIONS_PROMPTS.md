# Фича: пользовательские быстрые команды (Sep-17 список, пункт «custom quick-action chips»)

## Общий контекст

Сейчас в чате уже есть ряд быстрых команд-«пилюль» (`QUICK_ACTIONS` в
`MainActivity.kt`, фича от 13 сентября) — шесть захардкоженных чипов вроде
«🔒 заблокировать» и «🔇 без звука», каждый сразу шлёт готовую `Command` в
обход поля ввода и NL-разбора. Список зашит в код — чтобы добавить свою
команду, нужно менять исходники. Делаем этот список **дополняемым**:
пользователь сам заводит свои чипы прямо в приложении.

Прочитал код перед тем, как писать промпты — важные находки:

- `ChatViewModel.sendCommand(command: Command)` уже умеет отправить ЛЮБУЮ
  готовую команду протокола напрямую, минуя `NaturalLanguage` (им же
  пользуются существующие `QUICK_ACTIONS` и кнопки буфера обмена). Это
  готовый крючок — новым пользовательским чипам протокол/relay/agent
  трогать вообще не нужно.
- Ограничиваю пользовательские чипы **пятью типами команд**, у которых уже
  есть параметр ровно один (или нет параметра вовсе) и которые уже проходят
  whitelist на релее и уже реализованы в `CommandExecutor.java`: `LockScreen`
  (без параметра), `SetVolume(level)`, `OpenApp(appName)`, `OpenUrl(url)`,
  `KillProcess(processName)`. Это те же примитивы, что и во встроенных
  чипах — ничего нового агенту исполнять не придётся.
- Хранилище — тот же файловый JSON-паттерн, что уже используется в
  `TaskHistoryRepository` (история задач, фича от 15 сентября): один файл
  в `filesDir`, `kotlinx.serialization`, `MutableStateFlow` наружу. DataStore
  не подходит (список — это список записей, а не пара ключ-значение).
- Экран управления копирует структуру `HistoryScreen.kt`: `Scaffold` +
  `TopAppBar` с кнопкой «назад» + `LazyColumn` карточек на
  `surfaceContainer`, `RoundedCornerShape(16.dp)`. Никакой новой визуальной
  системы не придумываю.
- Встроенные `QUICK_ACTIONS` не трогаем и не делаем редактируемыми —
  пользовательские чипы просто дорисовываются в том же ряду following за
  ними. Так безопаснее (нельзя случайно удалить/сломать «фабричные» чипы)
  и проще (не нужна миграция «эти шесть — служебные, отметить как
  нередактируемые»).

Порядок промптов: сперва новый файл с типами и репозиторием (ни от чего не
зависит), затем новый файл экрана (зависит от первого), затем
`ChatViewModel.kt` (подключает репозиторий), и последним — `MainActivity.kt`
(навигация + иконка + рендер чипов в чате, завязан на предыдущие три).

`MainActivity.kt` на момент чтения (18 сентября) не тронут никаким другим
ещё не применённым промпт-доком — но на всякий случай перед вставкой ниже
сверься по `sed -n` с реальным файлом: если строки успели разъехаться,
ищи по тексту-якорю (в кавычках в описании каждой правки), а не по номеру
строки.

---

## Промпт 1 — новый файл `CustomActions.kt` (типы + хранилище)

Создай `androidApp/src/main/kotlin/com/jarvis/app/CustomActions.kt`:

```kotlin
package com.jarvis.app

import android.content.Context
import com.jarvis.protocol.Command
import com.jarvis.protocol.KillProcess
import com.jarvis.protocol.LockScreen
import com.jarvis.protocol.OpenApp
import com.jarvis.protocol.OpenUrl
import com.jarvis.protocol.SetVolume
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
 * Какую команду шлёт пользовательский чип. Сознательно ограниченный набор —
 * те же примитивы, что уже в встроенных QUICK_ACTIONS (MainActivity.kt):
 * whitelist на релее их уже пропускает, CommandExecutor.java их уже умеет
 * выполнять, протокол трогать не нужно.
 */
@Serializable
enum class CustomCommandType(val label: String, val needsParam: Boolean, val paramHint: String) {
    LOCK("Заблокировать экран", false, ""),
    VOLUME("Громкость", true, "0-100"),
    OPEN_APP("Открыть приложение", true, "например, браузер"),
    OPEN_URL("Открыть сайт", true, "например, youtube.com"),
    KILL_PROCESS("Завершить процесс", true, "например, chrome.exe"),
}

/** Один пользовательский чип быстрой команды. */
@Serializable
data class CustomActionRecord(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val label: String,
    val type: CustomCommandType,
    /** Пусто для LOCK; для остальных типов — то, что ввёл пользователь. */
    val param: String = "",
)

/** Собрать реальную команду протокола из записи чипа. */
fun CustomActionRecord.toCommand(): Command = when (type) {
    CustomCommandType.LOCK -> LockScreen
    CustomCommandType.VOLUME -> SetVolume(param.trim().toIntOrNull()?.coerceIn(0, 100) ?: 50)
    CustomCommandType.OPEN_APP -> OpenApp(param.trim())
    CustomCommandType.OPEN_URL -> OpenUrl(param.trim())
    CustomCommandType.KILL_PROCESS -> KillProcess(param.trim())
}

/**
 * Пользовательские быстрые команды. Тот же файловый JSON-паттерн, что и
 * TaskHistoryRepository: DataStore тут не годится (это список записей, а
 * не пара ключ-значение), список маленький и меняется редко.
 */
class CustomActionsRepository(context: Context) {

    private val file = File(context.filesDir, "custom_actions.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _items = MutableStateFlow<List<CustomActionRecord>>(emptyList())
    /** В порядке добавления — предсказуемо для ряда чипов в чате. */
    val items: StateFlow<List<CustomActionRecord>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<CustomActionRecord>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun add(record: CustomActionRecord) = withContext(Dispatchers.IO) {
        val updated = (_items.value + record).take(MAX_ACTIONS)
        _items.value = updated
        persist(updated)
    }

    suspend fun update(record: CustomActionRecord) = withContext(Dispatchers.IO) {
        val updated = _items.value.map { if (it.id == record.id) record else it }
        _items.value = updated
        persist(updated)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val updated = _items.value.filterNot { it.id == id }
        _items.value = updated
        persist(updated)
    }

    private fun persist(list: List<CustomActionRecord>) {
        runCatching { file.writeText(json.encodeToString(list)) }
    }

    private companion object {
        /** Чтобы ряд чипов не растянулся бесконечно — это чипы, а не список настроек. */
        const val MAX_ACTIONS = 12
    }
}
```

---

## Промпт 2 — новый файл `CustomActionsScreen.kt` (управление чипами)

Создай `androidApp/src/main/kotlin/com/jarvis/app/CustomActionsScreen.kt`:

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
 * Экран «Свои команды»: список пользовательских чипов (CustomActionsRepository)
 * + добавление/редактирование/удаление. Сами чипы рендерятся в чате
 * (MainActivity.kt) рядом со встроенными QUICK_ACTIONS — этот экран только
 * управляет списком, повторяет оформление HistoryScreen.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomActionsScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
) {
    val items by vm.customActions.collectAsState()
    var editing by remember { mutableStateOf<CustomActionRecord?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        ActionEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false; editing = null },
            onSave = { record ->
                if (editing == null) vm.addCustomAction(record) else vm.updateCustomAction(record)
                showEditor = false
                editing = null
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Свои команды", fontWeight = FontWeight.SemiBold) },
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
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "Пока нет своих команд. Нажмите ➕, чтобы добавить чип — " +
                        "он появится в чате рядом со встроенными быстрыми командами.",
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
            items(items, key = { it.id }) { record ->
                ActionCard(
                    record = record,
                    onEdit = { editing = record; showEditor = true },
                    onDelete = { vm.removeCustomAction(record.id) },
                )
            }
        }
    }
}

@Composable
private fun ActionCard(record: CustomActionRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onEdit,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(record.emoji.ifBlank { "•" }, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(record.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    if (record.type.needsParam) "${record.type.label}: ${record.param}" else record.type.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Text("🗑") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionEditorDialog(
    initial: CustomActionRecord?,
    onDismiss: () -> Unit,
    onSave: (CustomActionRecord) -> Unit,
) {
    var emoji by remember { mutableStateOf(initial?.emoji ?: "") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: CustomCommandType.LOCK) }
    var param by remember { mutableStateOf(initial?.param ?: "") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    val valid = label.isNotBlank() && (!type.needsParam || param.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая команда" else "Изменить команду") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it.take(2) },
                    label = { Text("Эмодзи") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = type.label, onValueChange = {}, readOnly = true,
                        label = { Text("Команда") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        CustomCommandType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    type = option
                                    if (!option.needsParam) param = ""
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                if (type.needsParam) {
                    OutlinedTextField(
                        value = param, onValueChange = { param = it },
                        label = { Text(type.paramHint) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CustomActionRecord(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            emoji = emoji.trim().ifBlank { "⚡" },
                            label = label.trim(),
                            type = type,
                            param = param.trim(),
                        )
                    )
                },
            ) { Text(if (initial == null) "Добавить" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
```

Про `ExposedDropdownMenuBox`/`menuAnchor()` — стандартный Material3 API,
но версия Compose BOM в проекте не проверялась под этот конкретный
компонент (в остальном коде используются `AssistChip`/`FilterChip`, не
выпадающий список). Если Android Studio подсветит `menuAnchor()` как
неизвестный член — значит используемая версия `material3` старая и
нужен вариант с явным `Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)`
(так называется в material3 1.3+) вместо `menuAnchor()` без параметров
(material3 до 1.3). Поправь по подсказке IDE — это единственное место
в этой фиче, где сигнатура API могла разъехаться между версиями.

---

## Промпт 3 — `ChatViewModel.kt`: подключить репозиторий

Файл: `androidApp/src/main/kotlin/com/jarvis/app/ChatViewModel.kt`.
Новые импорты не нужны — `CustomActionsRepository`/`CustomActionRecord`
в том же пакете `com.jarvis.app`.

### 3.1. Поле репозитория и StateFlow

```kotlin
// было
    private val settings = SettingsRepository(app)
    private val history = TaskHistoryRepository(app)

    /** История задач — читает экран «История». */
    val taskHistory: StateFlow<List<TaskRecord>> = history.items
// стало
    private val settings = SettingsRepository(app)
    private val history = TaskHistoryRepository(app)
    private val customActionsRepo = CustomActionsRepository(app)

    /** История задач — читает экран «История». */
    val taskHistory: StateFlow<List<TaskRecord>> = history.items

    /** Пользовательские быстрые команды — читают ряд чипов в чате и экран «Свои команды». */
    val customActions: StateFlow<List<CustomActionRecord>> = customActionsRepo.items
```

### 3.2. Загрузка при старте

```kotlin
// было
    init {
        // Фразы, распознанные фоновым сервисом после слова «Джарвис», уходят
        // ровно тем же путём, что и набранные руками (send -> NaturalLanguage).
        viewModelScope.launch { history.load() }
// стало
    init {
        // Фразы, распознанные фоновым сервисом после слова «Джарвис», уходят
        // ровно тем же путём, что и набранные руками (send -> NaturalLanguage).
        viewModelScope.launch { history.load() }
        viewModelScope.launch { customActionsRepo.load() }
```

### 3.3. Методы добавления/изменения/удаления

Добавь сразу после `fun clearHistory() { ... }`:

```kotlin
// было
    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
// стало
    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    fun addCustomAction(record: CustomActionRecord) {
        viewModelScope.launch { customActionsRepo.add(record) }
    }

    fun updateCustomAction(record: CustomActionRecord) {
        viewModelScope.launch { customActionsRepo.update(record) }
    }

    fun removeCustomAction(id: String) {
        viewModelScope.launch { customActionsRepo.remove(id) }
    }

    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
```

---

## Промпт 4 — `MainActivity.kt`: экран, иконка, рендер чипов в чате

Файл: `androidApp/src/main/kotlin/com/jarvis/app/MainActivity.kt`.

### 4.1. Новый экран в `AppScreen`

```kotlin
// было
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC }
// стало
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC, CUSTOM_ACTIONS }
```

### 4.2. Навигация — новая ветка `when(screen)` и параметр `ChatScreen`

```kotlin
// было
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
// стало
                        var screen by remember { mutableStateOf(AppScreen.CHAT) }
                        when (screen) {
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
                        }
```

### 4.3. Сигнатура `ChatScreen` — новый параметр

```kotlin
// было
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenMultiPc: () -> Unit = {},
) {
// стало
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenMultiPc: () -> Unit = {},
    onOpenCustomActions: () -> Unit = {},
) {
```

### 4.4. Читаем список чипов как состояние

```kotlin
// было
    val wakeWordEnabled by vm.wakeWordEnabled.collectAsState()
    val wakeState by vm.wakeState.collectAsState()
// стало
    val wakeWordEnabled by vm.wakeWordEnabled.collectAsState()
    val wakeState by vm.wakeState.collectAsState()
    val customActions by vm.customActions.collectAsState()
```

### 4.5. Иконка в шапке чата

```kotlin
// было
                        IconButton(onClick = onOpenDevices) { Text("🖥") }
                        IconButton(onClick = onOpenMultiPc) { Text("📡") }
                        IconButton(onClick = onOpenScreenView) { Text("🖵") }
                        IconButton(onClick = { showFiles = true }) { Text("📁") }
                        IconButton(onClick = onOpenHistory) { Text("🕘") }
// стало
                        IconButton(onClick = onOpenDevices) { Text("🖥") }
                        IconButton(onClick = onOpenMultiPc) { Text("📡") }
                        IconButton(onClick = onOpenScreenView) { Text("🖵") }
                        IconButton(onClick = { showFiles = true }) { Text("📁") }
                        IconButton(onClick = onOpenHistory) { Text("🕘") }
                        IconButton(onClick = onOpenCustomActions) { Text("🧩") }
```

### 4.6. Пользовательские чипы — в тот же ряд, следом за встроенными

```kotlin
// было
                QUICK_ACTIONS.forEach { action ->
                    PillChip("${action.emoji} ${action.label}") { action.run(vm) }
                }
            }
// стало
                QUICK_ACTIONS.forEach { action ->
                    PillChip("${action.emoji} ${action.label}") { action.run(vm) }
                }
                customActions.forEach { action ->
                    PillChip("${action.emoji} ${action.label}") { vm.sendCommand(action.toCommand()) }
                }
            }
```

Осторожно — в файле два похожих блока `PillChip("${...emoji} ${...label}")`
рядом (быстрые команды и quick-phrase чипы чуть ниже). Патчим именно тот,
что внутри `QUICK_ACTIONS.forEach` — он закрывается строкой `}` и сразу за
ней комментарий `// --- Quick-phrase chips ---`, это и есть якорь.

---

## Проверка

- [ ] Собирается `androidApp` — новых зависимостей не добавляли,
      `ExposedDropdownMenuBox`/`menuAnchor()` резолвится (см. предупреждение
      в конце Промпта 2 — если нет, поправить сигнатуру по версии material3).
- [ ] Свежая установка (или очистка данных приложения) — ряд быстрых команд
      выглядит как раньше (шесть встроенных чипов), новых пустых чипов нет.
- [ ] Нажать 🧩 в шапке чата — открывается экран «Свои команды», пусто,
      текст-подсказка на месте.
- [ ] ➕ → заполнить «🚀 / YouTube Music / Открыть приложение / youtube music» → «Добавить»
      — диалог закрылся, в списке новая карточка, вернуться назад (←) —
      в ряду быстрых команд в чате справа от встроенных появился чип
      «🚀 YouTube Music».
- [ ] Нажать на этот новый чип в чате — на ПК действительно открывается
      указанное приложение (тот же путь, что и у встроенного чипа «🌐 браузер»,
      команда `OpenApp`), в чате отображается обычный статус выполнения.
- [ ] На экране «Свои команды» тап по карточке открывает диалог
      редактирования с уже заполненными полями; изменить название/параметр →
      «Сохранить» — карточка и чип в чате обновились, id не поменялся
      (не задвоилось).
- [ ] Кнопка 🗑 на карточке — запись и чип пропадают сразу, без перезапуска
      приложения.
- [ ] Добавить чип типа «Заблокировать экран» (без параметра) — поле параметра
      в диалоге не показывается, кнопка «Добавить» активна и без него.
- [ ] Добавить чип типа «Громкость» с параметром вне 0-100 (например, `500`
      или пустая строка после того как тип уже выбран) — команда всё равно
      уходит (клэмп в `toCommand()`/дефолт 50), приложение не падает.
- [ ] Добавить 12+ чипов подряд — после 12-го новые не добавляются
      молча (или UI это не обрабатывает специально — минимум приложение не
      падает и не переполняет список; если хочется user-friendly предупреждение
      при достижении лимита, это можно доделать отдельно, не обязательно для
      этой фичи).
- [ ] Перезапустить приложение (кильнуть процесс) — все добавленные
      пользовательские чипы на месте, встроенные тоже.
- [ ] Ряд быстрых команд с 6 встроенными + несколькими своими чипами
      по-прежнему нормально скроллится по горизонтали и не ломает разметку.

## Что осталось незакрытым (осознанно)

- Нет перетаскивания/сортировки своих чипов — только порядок добавления.
- Нет своей иконки вместо эмодзи-текста — поле просто текстовое (макс. 2 символа).
- Встроенные `QUICK_ACTIONS` нельзя скрыть/переставить — только пользовательские.
- Лимит в 12 чипов — не настраиваемый, просто константа в коде.
