Jarvis — шаг: быстрые команды + общий буфер обмена (13 сен. 2026)

Фичи 2 и 3 из 5 — объединены в один промпт (обе небольшие, ты попросил маленькие задачи не
дробить). Дальше — передача файлов и фон+уведомления, каждая уже отдельным промптом (они крупнее).

Вставляй по одному промпту своим ИИ-агентам, порядок важен: протокол → релей → агент →
приложение.

## Общий контекст (для всех четырёх)

**Быстрые команды**: в приложении уже ЕСТЬ «чипы-подсказки» (📸 скриншот, 📋 процессы, ▶️
найди на ютубе…) — но это просто автоподстановка текста в поле, всё равно нужно жать отправку.
Хотим ДРУГОЕ — кнопки, которые сразу шлют команду, без поля ввода вообще. Протокол трогать не
нужно: команды `LockScreen`, `SetVolume`, `OpenApp` уже существуют, просто зовём их напрямую
через уже готовый `ChatViewModel.sendCommand(Command)` (сделан в шаге со стримом).

**Буфер обмена**: скопировал текст на телефоне — можно сразу отправить на ПК (вставится куда
угодно), и наоборот — забрать то, что скопировано на ПК, на телефон. Это НОВАЯ пара команд
(`get_clipboard`/`set_clipboard`), протокол трогаем.

**Важная деталь по безопасности** (проверено чтением кода): у агента уже есть
`SensitiveInputGuard` — эвристика, которая находит в тексте признаки пароля/номера карты (её
использует vision-агент, чтобы не печатать такое). Она сейчас `package-private` (видна только
внутри `com.jarvis.agent.vision`), а `CommandExecutor` живёт в другом пакете (`com.jarvis.agent`)
— значит, просто так использовать её оттуда нельзя, нужно сделать класс и метод `public`. Логично
переиспользовать её и для буфера обмена: не пересылать на телефон содержимое буфера ПК, если оно
похоже на пароль/карту (мало ли что там случайно оказалось скопировано).

---

## Промпт 1 — протокол (3 файла)

Открой подряд:
- `C:\diplom\jarvis\shared\src\commonMain\kotlin\com\jarvis\protocol\Command.kt`
- `C:\diplom\jarvis\shared\src\commonMain\kotlin\com\jarvis\protocol\Result.kt`
- `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\protocol\Command.java`
- `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\protocol\ResultData.java`
- `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\protocol\Command.java`
- `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\protocol\ResultData.java`

(шесть файлов, но правки в каждом — по 2-3 строки; релей и агент сейчас байт-в-байт одинаковые)

### `Command.kt` (shared) — добавить в конец файла (после `StopScreenStream`):
```kotlin
// --- Layer 5: буфер обмена -------------------------------------------------

@Serializable
@SerialName("get_clipboard")
data object GetClipboard : Command {
    override val name: String get() = "get_clipboard"
}

@Serializable
@SerialName("set_clipboard")
data class SetClipboard(val text: String) : Command {
    override val name: String get() = "set_clipboard"
}
```

### `Result.kt` (shared) — добавить в конец файла:
```kotlin
/** Результат [GetClipboard]: содержимое буфера обмена ПК. */
@Serializable
@SerialName("clipboard_text")
data class ClipboardText(val text: String) : ResultData
```

### `Command.java` (и релей, и агент — идентичные правки)
В `@JsonSubTypes` добавить (после `stop_screen_stream`):
```java
        @JsonSubTypes.Type(value = Command.GetClipboard.class, name = "get_clipboard"),
        @JsonSubTypes.Type(value = Command.SetClipboard.class, name = "set_clipboard")
```
В `permits` добавить `Command.GetClipboard, Command.SetClipboard`.
В конец `interface Command { ... }` добавить:
```java
    // Layer 5: буфер обмена
    record GetClipboard() implements Command {
        public String name() { return "get_clipboard"; }
    }
    record SetClipboard(String text) implements Command {
        public String name() { return "set_clipboard"; }
    }
```

### `ResultData.java` (и релей, и агент — идентичные правки)
В `@JsonSubTypes` добавить:
```java
        @JsonSubTypes.Type(value = ResultData.ClipboardText.class, name = "clipboard_text")
```
В `permits` добавить `ResultData.ClipboardText`.
В конец добавить:
```java
    record ClipboardText(String text) implements ResultData {}
```

### Проверка
`./gradlew :shared:compileKotlin`, сборка релея и агента — без ошибок.

---

## Промпт 2 — Релей (whitelist для буфера обмена)

Быстрые команды ничего тут не требуют — `lock_screen`/`set_volume`/`open_app` уже разрешены.
Файл: `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\security\CommandWhitelist.java`.
Сейчас:
```java
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream"
    );
```
Поменять на:
```java
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream",
            "get_clipboard", "set_clipboard"
    );
```

### Проверка
Собрать релей — без ошибок.

---

## Промпт 3 — Агент (выполнить буфер обмена)

### 3.1 Расширить видимость `SensitiveInputGuard`
Файл: `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\vision\SensitiveInputGuard.java`.
Сейчас:
```java
final class SensitiveInputGuard {
    ...
    static boolean mentionsSensitive(String text) {
```
Поменять на:
```java
public final class SensitiveInputGuard {
    ...
    public static boolean mentionsSensitive(String text) {
```
(Комментарий класса можно оставить как есть — переиспользование вне пакета уже описано в
Общем контексте этого промпта, дублировать в коде не обязательно.)

### 3.2 `CommandExecutor.java` — обработка команд

Добавить импорт: `import com.jarvis.agent.vision.SensitiveInputGuard;`
(остальное — `Toolkit`, `StringSelection`, `Base64` — уже импортировано, используется в
`typeText`/`setVolume`/`screenshot`).

В `execute(...)` сейчас:
```java
                case Command.StartScreenStream c -> startScreenStream(requestId, c);
                case Command.StopScreenStream c  -> stopScreenStream(requestId);
                default                        -> error(requestId, "команда не поддерживается агентом");
```
Поменять на:
```java
                case Command.StartScreenStream c -> startScreenStream(requestId, c);
                case Command.StopScreenStream c  -> stopScreenStream(requestId);
                case Command.GetClipboard c       -> getClipboard(requestId);
                case Command.SetClipboard c       -> ok(requestId, setClipboard(c.text()));
                default                        -> error(requestId, "команда не поддерживается агентом");
```

Добавить два новых метода (например, рядом с `setVolume`):
```java
    /**
     * Содержимое буфера обмена ПК -> телефону. Не текст (картинка, файл и т.п.)
     * или похоже на пароль/карту (SensitiveInputGuard, как у vision-агента) —
     * отказываем, а не пересылаем вслепую.
     */
    private List<JarvisMessage> getClipboard(String requestId) {
        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            return error(requestId, "буфер обмена ПК пуст или это не текст");
        }
        if (SensitiveInputGuard.mentionsSensitive(text)) {
            return error(requestId, "похоже на пароль/платёжные данные — не пересылаю буфер обмена");
        }
        return List.of(new JarvisMessage.CommandResult(
                requestId, CommandStatus.OK, "буфер обмена ПК отправлен", new ResultData.ClipboardText(text)));
    }

    /** Текст с телефона -> в буфер обмена ПК (как typeText, но без вставки в поле). */
    private String setClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        return "буфер обмена ПК обновлён";
    }
```

### Проверка
Собрать и запустить агент. Вручную скопировать что-нибудь текстовое на ПК — понадобится в
проверке Промпта 4.

---

## Промпт 4 — Приложение (кнопки быстрых команд + буфер обмена)

### 4.1 `ChatViewModel.kt` — новые методы + обработка `ClipboardText`

Добавить импорты (если их ещё нет): `android.content.ClipData`, `android.content.ClipboardManager`.

Рядом с `sendCommand(...)` добавить:
```kotlin
    /** Кнопка «📋⬆»: буфер обмена телефона -> на ПК. */
    fun sendPhoneClipboardToPc() {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(getApplication())?.toString()
        if (text.isNullOrBlank()) {
            note("буфер обмена телефона пуст")
            return
        }
        sendCommand(SetClipboard(text))
        note("буфер обмена телефона отправлен на ПК")
    }

    /** Кнопка «📋⬇»: запросить буфер обмена ПК — придёт CommandResult с ClipboardText (см. handle). */
    fun requestPcClipboard() = sendCommand(GetClipboard)

    /** Кладём полученный от ПК текст в буфер обмена самого телефона — готово для вставки где угодно. */
    private fun copyToPhoneClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("jarvis", text))
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
                add(ChatMessage(author = Author.PC, text = resultText(msg)))
            }
```
Поменять на:
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

### 4.2 `MainActivity.kt` (`ChatScreen`) — ряд кнопок быстрых команд

Добавить константу рядом с `QUICK_PHRASES` (это ДРУГОЙ механизм — не подставляет текст в поле,
а сразу шлёт команду):
```kotlin
/** Одна быстрая команда: эмодзи-кнопка, которая сразу шлёт Command, без поля ввода. */
private data class QuickAction(val emoji: String, val label: String, val run: (ChatViewModel) -> Unit)

private val QUICK_ACTIONS = listOf(
    QuickAction("🔒", "заблокировать") { it.sendCommand(LockScreen) },
    QuickAction("🔇", "без звука") { it.sendCommand(SetVolume(0)) },
    QuickAction("🔊", "звук 50%") { it.sendCommand(SetVolume(50)) },
    QuickAction("🌐", "браузер") { it.sendCommand(OpenApp("браузер")) },
    QuickAction("📋⬆", "буфер -> ПК") { it.sendPhoneClipboardToPc() },
    QuickAction("📋⬇", "буфер <- ПК") { it.requestPcClipboard() },
)
```
В `ChatScreen`, сразу над блоком `// --- Quick-phrase chips ---`, добавить новый ряд (тот же
приём: `horizontalScroll` + `AssistChip`, чтобы не плодить новый визуальный стиль):
```kotlin
            // --- Быстрые команды (шлют сразу, без поля ввода) ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QUICK_ACTIONS.forEach { action ->
                    AssistChip(
                        onClick = { action.run(vm) },
                        label = { Text("${action.emoji} ${action.label}") },
                    )
                }
            }

            // --- Quick-phrase chips ---
```

### Проверка (финальная, по всем четырём промптам)
1. Пересобрать и переустановить все три части.
2. Тапнуть 🔒 — ПК должен заблокироваться сразу, без набора текста.
3. Тапнуть 🔇, затем 🔊 — громкость должна дважды измениться (сообщения в чате как у обычных
   команд, с индикатором «выполняется…/готово ✓»).
4. Скопировать что-нибудь текстовое на ПК → тапнуть 📋⬇ на телефоне → вставить в любое поле на
   телефоне (например, в поле ввода команды) — должен появиться тот же текст.
5. Скопировать что-нибудь текстовое на телефоне → тапнуть 📋⬆ → на ПК вставить (Ctrl+V) в любое
   текстовое поле — должен появиться тот же текст.
6. На ПК скопировать что-то похожее на номер карты (например, «4111 1111 1111 1111») → тапнуть
   📋⬇ на телефоне — должна прийти ошибка «похоже на пароль/платёжные данные», а НЕ содержимое.
