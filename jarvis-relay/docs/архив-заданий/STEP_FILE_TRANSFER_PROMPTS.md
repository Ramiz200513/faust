Jarvis — шаг: передача файлов телефон↔ПК (13 сен. 2026)

Фича 4 из 5. Дальше — фон+уведомления (когда agent_task заканчивается, пока приложение
свёрнуто), уже отдельным промптом — она крупнее (нужен foreground-сервис Android), дробить
дальше не стал.

Вставляй по одному промпту, порядок важен: протокол → релей → агент → приложение. Все снимки
кода ниже — актуальное состояние файлов на 13 сен (после того, как ты прогнал прошлый промпт
про буфер обмена и быстрые команды — я перечитал файлы заново, чтобы не гадать).

## Общий контекст (для всех четырёх)

**Что делаем**: скачать файл с ПК на телефон (например, документ из Загрузок) и наоборот —
отправить файл с телефона на ПК. Как и с буфером обмена, файл летит просто как base64 внутри
уже существующего `CommandResult` (поле `data`) — новый тип `JarvisMessage` не нужен, релей
ничего пересылать по-новому не должен (в отличие от `AgentStep` в шаге со стримом, где как раз
понадобилась правка релея).

**Где хранится разрешённый список папок**: у `open_app` уже есть `AppCatalog` с 4 папками
(Downloads/Documents/Desktop/Pictures) — та же граница безопасности подходит и для файлов,
незачем заводить второй список. Новый класс `FileCatalog` переиспользует `AppCatalog.resolve(...)`,
но требует именно `Target.Folder` (не программу/браузер), и добавляет то, чего в `AppCatalog`
нет: безопасное разрешение ИМЕНИ ФАЙЛА внутри папки (блокирует `../` — попытку выйти за пределы
разрешённой папки) и лимит размера.

**Лимит размера — проверено конфигурацией, а не на глаз**: у релея `WebSocketConfig` поднимает
`maxTextMessageBufferSize` до 8 МБ (уже настроено под скриншоты). base64 увеличивает размер
примерно на треть, так что жёсткий потолок в 5 МБ на сырой файл (~6.7 МБ в base64) укладывается
в лимит с запасом. У агента (`RelayClient`) свой WebSocket-клиент на `java.net.http.WebSocket` —
он копит входящие сообщения в обычный `StringBuilder` без жёсткого предела, так что с его
стороны ничего поднимать не нужно.

**Одно предположение**: сохранение скачанного файла в приложении сделано через `MediaStore.Downloads`
(современный, «scoped storage» способ, без разрешения на запись во внешнее хранилище) — это
работает начиная с Android 10 (API 29). Если у вас `minSdk` ниже — скажи, дам путь для старых
версий (там нужно разрешение `WRITE_EXTERNAL_STORAGE` и прямой `File`).

---

## Промпт 1 — протокол (Command + ResultData, 3 копии)

Открой подряд:
- `C:\diplom\jarvis\shared\src\commonMain\kotlin\com\jarvis\protocol\Command.kt`
- `C:\diplom\jarvis\shared\src\commonMain\kotlin\com\jarvis\protocol\Result.kt`
- `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\protocol\Command.java`
- `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\protocol\ResultData.java`
- `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\protocol\Command.java`
- `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\protocol\ResultData.java`

### `Command.kt` (shared) — добавить в конец файла (после `SetClipboard`):
```kotlin
// --- Layer 6: передача файлов (те же папки, что уже разрешены open_app) ----

@Serializable
@SerialName("list_files")
data class ListFiles(val folder: String) : Command {
    override val name: String get() = "list_files"
}

@Serializable
@SerialName("send_file_to_phone")
data class SendFileToPhone(val folder: String, val fileName: String) : Command {
    override val name: String get() = "send_file_to_phone"
}

@Serializable
@SerialName("send_file_to_pc")
data class SendFileToPc(val folder: String, val fileName: String, val base64: String) : Command {
    override val name: String get() = "send_file_to_pc"
}
```

### `Result.kt` (shared) — добавить в конец файла:
```kotlin
@Serializable
data class FileEntry(val name: String, val sizeBytes: Long)

/** Результат [ListFiles]. */
@Serializable
@SerialName("file_list")
data class FileList(val entries: List<FileEntry>) : ResultData

/** Результат [SendFileToPhone]: содержимое файла. */
@Serializable
@SerialName("file_transfer")
data class FileTransfer(val fileName: String, val base64: String, val sizeBytes: Long) : ResultData
```

### `Command.java` (релей и агент — идентичные правки)
В `@JsonSubTypes` добавить (после `set_clipboard`):
```java
        @JsonSubTypes.Type(value = Command.ListFiles.class, name = "list_files"),
        @JsonSubTypes.Type(value = Command.SendFileToPhone.class, name = "send_file_to_phone"),
        @JsonSubTypes.Type(value = Command.SendFileToPc.class, name = "send_file_to_pc")
```
В `permits` добавить `Command.ListFiles, Command.SendFileToPhone, Command.SendFileToPc`.
В конец `interface Command { ... }` добавить:
```java
    // Layer 6: передача файлов
    record ListFiles(String folder) implements Command {
        public String name() { return "list_files"; }
    }
    record SendFileToPhone(String folder, String fileName) implements Command {
        public String name() { return "send_file_to_phone"; }
    }
    record SendFileToPc(String folder, String fileName, String base64) implements Command {
        public String name() { return "send_file_to_pc"; }
    }
```

### `ResultData.java` (релей и агент — идентичные правки)
В `@JsonSubTypes` добавить (после `clipboard_text`):
```java
        @JsonSubTypes.Type(value = ResultData.FileList.class, name = "file_list"),
        @JsonSubTypes.Type(value = ResultData.FileTransfer.class, name = "file_transfer")
```
В `permits` добавить `ResultData.FileList, ResultData.FileTransfer`.
В конец добавить:
```java
    record FileList(List<FileEntry> entries) implements ResultData {}

    record FileTransfer(String fileName, String base64, long sizeBytes) implements ResultData {}

    /** Not a ResultData itself — просто строка внутри FileList (как ProcessInfo у ProcessList). */
    record FileEntry(String name, long sizeBytes) {}
```

### Проверка
`./gradlew :shared:compileKotlin`, сборка релея и агента — без ошибок.

---

## Промпт 2 — Релей (whitelist)

Файл: `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\security\CommandWhitelist.java`.
Сейчас:
```java
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream",
            "get_clipboard", "set_clipboard"
    );
```
Поменять на:
```java
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream",
            "get_clipboard", "set_clipboard",
            "list_files", "send_file_to_phone", "send_file_to_pc"
    );
```
Больше релей трогать не нужно — `JarvisWebSocketHandler` тут ни при чём: файл едет внутри уже
существующего `CommandResult.data`, а не отдельным типом сообщения (в отличие от `AgentStep` в
шаге со стримом, где как раз требовалась правка пересылки).

### Проверка
Собрать релей — без ошибок.

---

## Промпт 3 — Агент (FileCatalog + выполнение команд)

### 3.1 Новый файл `FileCatalog.java`
`C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\FileCatalog.java`:
```java
package com.jarvis.agent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Разрешённые для передачи файлов папки — переиспользует те же алиасы, что и
 * open_app (Downloads/Documents/Desktop/Pictures из AppCatalog), но требует
 * именно Folder, а не программу/браузер. Отдельный класс, а не метод в
 * AppCatalog — у него другая ответственность: проверка имени файла и пути,
 * а не запуск программы.
 */
public final class FileCatalog {

    /** Жёсткий потолок на размер одного файла: 5 МБ raw (~6.7 МБ в base64) —
     *  с запасом укладывается в 8 МБ лимит WS-сообщения на релее. */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private FileCatalog() {}

    /** Резолвит имя папки (как в open_app) в реальный путь — только если это папка, не программа. */
    public static Optional<Path> resolveFolder(String folderName) {
        return AppCatalog.resolve(folderName)
                .filter(t -> t instanceof AppCatalog.Target.Folder)
                .map(t -> ((AppCatalog.Target.Folder) t).path());
    }

    /**
     * Безопасно резолвит имя файла ВНУТРИ папки — блокирует "../" и абсолютные
     * пути, которыми можно было бы выйти за пределы разрешённой папки.
     */
    public static Optional<Path> resolveFile(Path folder, String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        Path resolved = folder.resolve(fileName).normalize();
        if (!resolved.startsWith(folder)) return Optional.empty(); // попытка выйти из папки
        return Optional.of(resolved);
    }

    /** Список файлов (не папок) внутри — имя и размер. */
    public static List<FileEntry> list(Path folder) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    out.add(new FileEntry(p.getFileName().toString(), Files.size(p)));
                }
            }
        }
        return out;
    }

    public record FileEntry(String name, long sizeBytes) {}
}
```

### 3.2 `CommandExecutor.java` — обработка команд

В `execute(...)` сейчас:
```java
                case Command.GetClipboard c       -> getClipboard(requestId);
                case Command.SetClipboard c       -> ok(requestId, setClipboard(c.text()));
                default                        -> error(requestId, "команда не поддерживается агентом");
```
Поменять на:
```java
                case Command.GetClipboard c       -> getClipboard(requestId);
                case Command.SetClipboard c       -> ok(requestId, setClipboard(c.text()));
                case Command.ListFiles c          -> listFiles(requestId, c.folder());
                case Command.SendFileToPhone c    -> sendFileToPhone(requestId, c.folder(), c.fileName());
                case Command.SendFileToPc c       -> ok(requestId, receiveFileFromPhone(c.folder(), c.fileName(), c.base64()));
                default                        -> error(requestId, "команда не поддерживается агентом");
```

Добавить три новых метода (например, рядом с `getClipboard`/`setClipboard`; `Files`, `Path`,
`Optional`, `Base64` уже импортированы — используются в `setVolume`/`openApp`/`captureFrame`):
```java
    private List<JarvisMessage> listFiles(String requestId, String folderName) {
        Optional<Path> folder = FileCatalog.resolveFolder(folderName);
        if (folder.isEmpty()) {
            return error(requestId, "не знаю такую папку «" + folderName + "»");
        }
        try {
            List<ResultData.FileEntry> entries = FileCatalog.list(folder.get()).stream()
                    .map(e -> new ResultData.FileEntry(e.name(), e.sizeBytes()))
                    .toList();
            return List.of(new JarvisMessage.CommandResult(
                    requestId, CommandStatus.OK, entries.size() + " файл(ов)", new ResultData.FileList(entries)));
        } catch (IOException e) {
            return error(requestId, "не удалось прочитать папку: " + e.getMessage());
        }
    }

    private List<JarvisMessage> sendFileToPhone(String requestId, String folderName, String fileName) {
        Optional<Path> folder = FileCatalog.resolveFolder(folderName);
        if (folder.isEmpty()) {
            return error(requestId, "не знаю такую папку «" + folderName + "»");
        }
        Optional<Path> file = FileCatalog.resolveFile(folder.get(), fileName);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            return error(requestId, "файл «" + fileName + "» не найден");
        }
        try {
            long size = Files.size(file.get());
            if (size > FileCatalog.MAX_FILE_BYTES) {
                return error(requestId, "файл слишком большой (" + (size / 1024 / 1024) + " МБ, лимит 5 МБ)");
            }
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file.get()));
            return List.of(new JarvisMessage.CommandResult(
                    requestId, CommandStatus.OK, "файл «" + fileName + "» отправлен",
                    new ResultData.FileTransfer(fileName, b64, size)));
        } catch (IOException e) {
            return error(requestId, "не удалось прочитать файл: " + e.getMessage());
        }
    }

    /** Файл с телефона -> в разрешённую папку ПК. Исключения ловит общий catch в execute(). */
    private String receiveFileFromPhone(String folderName, String fileName, String base64) throws IOException {
        Optional<Path> folder = FileCatalog.resolveFolder(folderName);
        if (folder.isEmpty()) {
            throw new IllegalArgumentException("не знаю такую папку «" + folderName + "»");
        }
        Optional<Path> file = FileCatalog.resolveFile(folder.get(), fileName);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("недопустимое имя файла «" + fileName + "»");
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length > FileCatalog.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("файл слишком большой (лимит 5 МБ)");
        }
        Files.write(file.get(), bytes);
        return "файл «" + fileName + "» сохранён в " + folderName;
    }
```
(Строка вызова `ok(requestId, receiveFileFromPhone(...))` в `execute(...)` бросает `IOException` —
её ловит общий `catch (Exception e)` вокруг всего `switch`, как и у `openUrl`/`setVolume` — ничего
дополнительно оборачивать не нужно.)

### Проверка
Собрать и запустить агент.

---

## Промпт 4 — Приложение

### 4.1 Новый файл `FileTransfer.kt` — работа с файлами на стороне телефона
`C:\diplom\jarvis\androidApp\src\main\kotlin\com\jarvis\app\FileTransfer.kt`:
```kotlin
package com.jarvis.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64

/** Тот же лимит, что и на агенте (FileCatalog.MAX_FILE_BYTES) — проверяем на телефоне заранее,
 *  чтобы не гнать base64 через сеть только затем, чтобы агент его отверг. */
const val MAX_TRANSFER_BYTES = 5L * 1024 * 1024

/**
 * Читает файл, выбранный через системный пикер (content:// Uri): имя + base64.
 * null — не удалось прочитать или файл больше лимита.
 */
fun readPickedFile(context: Context, uri: Uri): Pair<String, String>? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
    } ?: uri.lastPathSegment ?: "file"

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > MAX_TRANSFER_BYTES) return null

    return name to Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** Сохраняет файл, пришедший с ПК, в публичную папку "Загрузки" телефона через MediaStore
 *  (Android 10+, без разрешения на запись во внешнее хранилище). */
fun saveToPhoneDownloads(context: Context, fileName: String, bytes: ByteArray): Boolean = try {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri == null) false
    else {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        true
    }
} catch (e: Exception) {
    false
}
```

### 4.2 `ChatViewModel.kt` — состояние списка файлов + обработка результатов

Добавить импорт: `import android.util.Base64`.

Рядом с `_liveFrame` добавить:
```kotlin
    // Список файлов из последнего ListFiles — читает диалог "Файлы на ПК".
    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList: StateFlow<List<FileEntry>> = _fileList.asStateFlow()
```

Рядом с `requestPcClipboard()` добавить:
```kotlin
    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
    fun downloadFile(folder: String, fileName: String) = sendCommand(SendFileToPhone(folder, fileName))
    fun uploadFile(folder: String, fileName: String, base64: String) = sendCommand(SendFileToPc(folder, fileName, base64))

    /** Декодирует пришедший от агента файл и сохраняет в Загрузки телефона. */
    private fun saveDownloadedFile(fileName: String, base64: String) {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val ok = saveToPhoneDownloads(getApplication(), fileName, bytes)
        note(if (ok) "файл «$fileName» сохранён в Загрузки" else "не удалось сохранить «$fileName»")
    }
```

В `handle(msg: JarvisMessage)`, ветка `is CommandResult ->`, сейчас:
```kotlin
            is CommandResult -> {
                val outcome = when (msg.status) {
                    CommandStatus.OK -> CommandProgress.DONE
                    CommandStatus.DENIED -> CommandProgress.DENIED
                    CommandStatus.ERROR, CommandStatus.TIMEOUT -> CommandProgress.ERROR
                }
                updateProgress(msg.requestId) { outcome }
                // Буфер обмена ПК пришёл текстом в data — сразу кладём в буфер телефона,
                // отдельно от текста самого сообщения (он короткий, "буфер отправлен").
                (msg.data as? ClipboardText)?.let { copyToPhoneClipboard(it.text) }
                add(ChatMessage(author = Author.PC, text = resultText(msg)))
            }
```
Поменять на (заменили точечный `as? ClipboardText` на общий `when`, чтобы туда же встали файлы):
```kotlin
            is CommandResult -> {
                val outcome = when (msg.status) {
                    CommandStatus.OK -> CommandProgress.DONE
                    CommandStatus.DENIED -> CommandProgress.DENIED
                    CommandStatus.ERROR, CommandStatus.TIMEOUT -> CommandProgress.ERROR
                }
                updateProgress(msg.requestId) { outcome }
                // Часть команд несёт данные, которые нужно применить СВЕРХ обычного текста
                // в чате (буфер обмена -> в буфер телефона, список файлов -> в диалог, файл -> сохранить).
                when (val data = msg.data) {
                    is ClipboardText -> copyToPhoneClipboard(data.text)
                    is FileList -> _fileList.value = data.entries
                    is FileTransfer -> saveDownloadedFile(data.fileName, data.base64)
                    else -> {}
                }
                add(ChatMessage(author = Author.PC, text = resultText(msg)))
            }
```

### 4.3 `MainActivity.kt` — кнопка 📁 и диалог передачи файлов

Добавить импорт: `import com.jarvis.protocol.FileEntry`.

В `ChatScreen`, рядом с `var showSettings by remember { mutableStateOf(false) }` добавить:
```kotlin
    var showFiles by remember { mutableStateOf(false) }
```

В блоке `actions` `TopAppBar`, сейчас:
```kotlin
                actions = {
                    IconButton(onClick = onOpenDevices) { Text("🖥") }
                    IconButton(onClick = onOpenScreenView) { Text("🖵") }
                    IconButton(onClick = { showSettings = true }) { Text("⚙") }
                    IconButton(onClick = { voice.toggleTts() }) {
                        Text(if (ttsEnabled) "🔊" else "🔇")
                    }
                },
```
Поменять на:
```kotlin
                actions = {
                    IconButton(onClick = onOpenDevices) { Text("🖥") }
                    IconButton(onClick = onOpenScreenView) { Text("🖵") }
                    IconButton(onClick = { showFiles = true }) { Text("📁") }
                    IconButton(onClick = { showSettings = true }) { Text("⚙") }
                    IconButton(onClick = { voice.toggleTts() }) {
                        Text(if (ttsEnabled) "🔊" else "🔇")
                    }
                },
```

Рядом с блоком `if (showSettings) { SettingsDialog(...) }` добавить:
```kotlin
    if (showFiles) {
        FileTransferDialog(vm = vm, onDismiss = { showFiles = false })
    }
```

Новый composable — добавить рядом с `SettingsDialog`:
```kotlin
private val TRANSFER_FOLDERS = listOf(
    "downloads" to "Загрузки", "documents" to "Документы",
    "desktop" to "Рабочий стол", "pictures" to "Изображения",
)

/**
 * Список файлов в одной из разрешённых папок ПК + скачать/отправить.
 * folder — это те же строки-алиасы, что понимает open_app (см. AppCatalog на агенте).
 */
@Composable
fun FileTransferDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val files by vm.fileList.collectAsState()
    var folder by remember { mutableStateOf(TRANSFER_FOLDERS.first().first) }
    val context = LocalContext.current

    // Обновляем список при открытии диалога и при смене вкладки-папки.
    LaunchedEffect(folder) { vm.listFiles(folder) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = readPickedFile(context, uri)
        if (picked == null) {
            vm.note("не удалось прочитать файл (или он больше 5 МБ)")
        } else {
            val (name, base64) = picked
            vm.uploadFile(folder, name, base64)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(16.dp).width(320.dp)) {
                Text("Файлы на ПК", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    TRANSFER_FOLDERS.forEach { (key, label) ->
                        FilterChip(
                            selected = folder == key,
                            onClick = { folder = key },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text("пусто (или ещё грузится)", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(files, key = { it.name }) { f ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${f.name} (${f.sizeBytes / 1024} КБ)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.downloadFile(folder, f.name) }) { Text("⬇") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { pickLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⬆ отправить файл с телефона") }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
            }
        }
    }
}
```

### Проверка (финальная, по всем четырём промптам)
1. Пересобрать и переустановить все три части.
2. Открыть 📁 — должен сразу загрузиться список файлов из «Загрузки» (пусто, если там пусто).
3. Переключить вкладку на «Документы»/«Рабочий стол»/«Изображения» — список должен обновляться.
4. Положить в Загрузки на ПК небольшой файл (например, .txt) → открыть 📁 на телефоне → тапнуть
   ⬇ у этого файла → проверить, что он появился в Загрузках телефона (тем же именем).
5. Тапнуть «⬆ отправить файл с телефона» → выбрать любой файл до 5 МБ → проверить, что он
   появился в выбранной папке на ПК.
6. Попробовать отправить/скачать файл больше 5 МБ — должна прийти понятная ошибка про лимит, а
   не зависание или обрыв соединения.
7. (Необязательно, если охота проверить границу безопасности) Специально попытаться передать
   имя файла с `../` — должна прийти ошибка «недопустимое имя файла», а не выход за пределы
   разрешённой папки.
