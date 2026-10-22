# Android-приложение Faust — полное объяснение

> Файл 2 из 5. Цель — чтобы ты мог открыть любой файл приложения и понимать, **что он делает,
> зачем он нужен, с кем связан и почему написан именно так**. Общая картина — в файле 1,
> сервер — в файле 3, программа на ПК — в файле 4, своя модель разбора фраз — в файле 5.
>
> Папка проекта: `C:\diplom\jarvis`. Название в лаунчере телефона — **Faust**.
>
> Обновлено 13 октября 2026 (сверено с кодом). **Новое с 25 сентября:** карточки вопросов
> ИИ-агента и ответ на них (кнопкой, текстом, голосом), карточка-план многоэтапной задачи,
> навыки-рецепты в «Своих командах», переключатели «рентгена» и панели плана на ПК,
> два новых сообщения и две новые команды в протоколе. Изменились семь файлов:
> `MainActivity`, `ChatViewModel`, `CustomActions`, `CustomActionsScreen`,
> `VoiceController`, `SettingsRepository`, `Notifications` и протокол в `:shared`.

---

## Оглавление

1. [Что делает приложение](#1-что-делает-приложение)
2. [Структура проекта и сборка](#2-структура-проекта-и-сборка)
3. [Модуль `:shared` — протокол](#3-модуль-shared--протокол)
4. [Минимум Kotlin/Compose, чтобы читать код](#4-минимум-kotlincompose-чтобы-читать-код)
5. [Архитектура приложения: слои](#5-архитектура-приложения-слои)
6. [AndroidManifest.xml — разрешения](#6-androidmanifestxml--разрешения)
7. [Разбор по файлам](#7-разбор-по-файлам)
   - 7.1 MainActivity.kt — вход, навигация, экран чата
   - 7.2 ChatViewModel.kt — мозг чата
   - 7.3 JarvisClient.kt — WebSocket с переподключением
   - 7.4 RelayPairing.kt — короткие одноразовые соединения
   - 7.5 Привязка ПК: PairingController, PairingQr, QrScan, PairingScreen, PairingViewModel
   - 7.6 Хранилища: DeviceRepository, SettingsRepository
   - 7.7 Экран «Устройства» + Wake-on-LAN
   - 7.8 Живой экран ПК: ScreenViewScreen, TaskPreviewWindow, FrameDecoding
   - 7.9 Голос: VoiceController, ListeningOrb
   - 7.10 Wake-word «Фауст»: VoskWakeWord, WakeWordBus, JarvisConnectionService, Notifications
   - 7.11 Файлы: FileTransfer.kt + диалог
   - 7.12 История задач: TaskHistory, HistoryScreen
   - 7.13 Свои команды: CustomActions, CustomActionsScreen
   - 7.14 Мульти-ПК: MultiPcViewModel, MultiPcScreen
   - 7.15 Theme.kt
8. [Сквозные сценарии](#8-сквозные-сценарии)
9. [Потоки и корутины](#9-потоки-и-корутины)
10. [Где приложение хранит данные](#10-где-приложение-хранит-данные)
11. [Отладка и типичные проблемы](#11-отладка-и-типичные-проблемы)
12. [Известные ограничения и вопросы на защите](#12-известные-ограничения-и-вопросы-на-защите)
13. [Как расширять](#13-как-расширять)
14. [Шпаргалка команд](#14-шпаргалка-команд)

---

## 1. Что делает приложение

Приложение — это **пульт управления компьютером**. Оно:

- подключается к релею по WebSocket и держит соединение (с автопереподключением);
- показывает чат: твои команды справа, ответы ПК слева, системные заметки по центру;
- принимает команды текстом, голосом (кнопка 🎤) или по слову «Фауст» (фоновый офлайн-слух);
- быстрые кнопки-чипы отправляют готовые команды без поля ввода;
- показывает живой экран ПК и позволяет тапать по нему (клик на ПК), листать и печатать;
- показывает шаги ИИ-агента в реальном времени с прицелом, куда он нажмёт, и с обоснованием модели («💭 зачем»);
- показывает **план многоэтапной задачи** чек-листом, который обновляется на месте;
- показывает **вопросы ИИ-агента** карточкой с кнопками и отправляет ответ — кнопкой, текстом или голосом;
- переносит буфер обмена и файлы между телефоном и ПК;
- хранит несколько привязанных ПК, показывает их онлайн-статус, будит ПК по сети;
- ведёт историю ИИ-задач, позволяет создать свои кнопки и **навыки-рецепты** (список шагов словами);
- включает и выключает «рентген» и панель плана на экране ПК.

Приложение **не** разбирает фразы и **не** проверяет безопасность — это делает релей. Оно лишь отправляет фразу как `NaturalLanguage` и показывает ответ. Единственное исключение — **рецепты**: их шаги знает только телефон, поэтому фразу «навык утро» он узнаёт сам и шлёт на ПК уже готовую задачу агенту (см. 7.13).

## 2. Структура проекта и сборка

```
C:\diplom\jarvis\
├── settings.gradle.kts         ← подключает модули :shared и :androidApp
├── build.gradle.kts            ← объявляет плагины (apply false — применяются в модулях)
├── gradle/libs.versions.toml   ← «каталог версий»: все версии библиотек в одном месте
├── shared/                     ← протокол (Kotlin Multiplatform)
│   └── src/commonMain/kotlin/com/jarvis/protocol/
│       ├── Protocol.kt  Command.kt  Result.kt  Whitelist.kt
│   └── src/commonTest/.../ProtocolTest.kt
└── androidApp/                 ← само приложение
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/model-ru/    ← офлайн-модель Vosk (~87 МБ распакованная)
        ├── res/                ← иконки, логотип
        └── kotlin/com/jarvis/app/   ← 31 файл .kt
```

### Версии (из `libs.versions.toml`)

| Что | Версия | Зачем |
|---|---|---|
| Kotlin | 2.1.0 | язык |
| AGP (Android Gradle Plugin) | 8.7.3 | сборка APK |
| kotlinx.serialization | 1.7.3 | JSON ↔ Kotlin-объекты |
| kotlinx.coroutines | 1.9.0 | асинхронность |
| Compose BOM | 2024.12.01 | UI-фреймворк (BOM выравнивает версии всех compose-библиотек) |
| compileSdk / targetSdk | 35 | под какой Android собираем |
| **minSdk** | **24** (Android 7.0) | минимальная поддерживаемая версия |

### Зависимости `:androidApp` (из `androidApp/build.gradle.kts`)

| Библиотека | Зачем |
|---|---|
| `project(":shared")` | наш протокол — те же классы, что понимает релей |
| `okhttp3:okhttp:4.12.0` | WebSocket-клиент (выбран вместо Ktor — «надёжно на Android») |
| `datastore-preferences` | хранение настроек и списка ПК между запусками |
| `core-ktx` | `NotificationCompat` для уведомлений |
| `vosk-android` + `jna` (`@aar`) | офлайн-распознавание речи для слова «Фауст» |
| `zxing-android-embedded` | готовый экран сканирования QR-кода |
| Compose (`ui`, `material3`, `material-icons-extended`, `activity-compose`, `lifecycle-viewmodel-compose`) | интерфейс |

Ktor объявлен в каталоге версий, но в приложении **не используется** — остался от первоначального плана.

### Почему `:shared` — отдельный модуль

`:shared` — модуль **Kotlin Multiplatform** с двумя целями: `androidTarget` (для телефона) и `jvm()` (чтобы протокол компилировался и тестировался **без Android SDK** — `gradlew :shared:jvmTest`). Приложение подключает его как обычную зависимость. Так формат сообщений описан один раз и не может «разъехаться» между экранами приложения.

## 3. Модуль `:shared` — протокол

Это контракт между телефоном, релеем и агентом. На релее и агенте он **продублирован на Java** (см. файлы 3 и 4) — JSON должен совпадать байт в байт.

### 3.1 `Protocol.kt` — конверт сообщений

```kotlin
@Serializable
sealed interface JarvisMessage { val requestId: String }
```

`sealed interface` означает «закрытое семейство»: все наследники известны компилятору. Поэтому в `when (msg) { is Auth -> … is CommandResult -> … }` компилятор проверит, что ты ничего не забыл. `@Serializable` — библиотека kotlinx.serialization сама генерирует код превращения в JSON и обратно.

| Класс | `@SerialName` | Поля | Смысл |
|---|---|---|---|
| `Auth` | `auth` | `deviceId`, `token`, `role`, `protocolVersion=1` | Первое сообщение после открытия сокета |
| `AuthResult` | `auth_result` | `ok`, `reason?`, `agentOnline?` | Ответ на вход. `agentOnline` — только телефону: на связи ли его ПК. `null` = релей старый/не знает |
| `PairRequest` | `pair_request` | `code` | Привязка по одноразовому коду (вместо `Auth`) |
| `PairResult` | `pair_result` | `ok`, `deviceId?`, `deviceToken?`, `deviceName?`, `reason?` | Постоянный токен телефона для этого ПК |
| `CommandRequest` | `command_request` | `command: Command` | Команда |
| `CommandResult` | `command_result` | `status`, `message?`, `data: ResultData?` | Результат |
| `ScreenFrame` | `screen_frame` | `format`, `base64`, `width`, `height`, `screenWidth?`, `screenHeight?`, `seq?` | Кадр. `seq == null` — одиночный скриншот, `seq != null` — кадр живого стрима |
| `AgentStep` | `agent_step` | `step`, `description`, `screenshotBase64?`, …, `targetX?`, `targetY?` | Шаг ИИ-агента, пока задача идёт |
| `AgentQuestion` | `agent_question` | `questionId`, `question`, `options=[]`, `timeoutSeconds?` | Вопрос агента человеку посреди задачи. `options` — готовые ответы для кнопок; пусто — отвечать своими словами |
| `AgentPlan` | `agent_plan` | `steps: List<PlanItem>`, `revision=1` | План многоэтапной задачи. Присылается **целиком** при каждом изменении; `revision` 2+ — план перестроен |
| `Ping` / `Pong` | `ping`/`pong` | — | Проверка связи |

`PlanItem(title, status, note?)` — одна подзадача: что делается человеческим языком, статус `PlanItemStatus` (`pending`, `running`, `done`, `failed`, `skipped`) и короткий итог или причина неудачи.

У `AgentQuestion` и `AgentPlan` `requestId` — тот же, что у исходной `agent_task`: по нему телефон прикрепляет вопрос и план к нужной задаче в ленте, как и её шаги. Почему план приходит целиком, а не «изменился пункт 2»: телефону не нужно собирать состояние из кусочков — последний пришедший `AgentPlan` с этим `requestId` и есть правда.

**Важная тонкость `ScreenFrame`:** `width/height` — размер **картинки** (уже уменьшенной, например 800 px), а `screenWidth/screenHeight` — **реальное** разрешение экрана ПК (например 1920). Координаты тапа пересчитываются по реальному разрешению — иначе клик уйдёт не туда (это был баг, см. `ScreenViewScreen`).

**`JarvisJson`** — единая настройка JSON для всего приложения:

```kotlin
val JarvisJson = Json {
    classDiscriminator = "type"   // поле-метка типа, совпадает с @SerialName
    encodeDefaults = true         // писать поля со значением по умолчанию (protocolVersion и т.п.)
    ignoreUnknownKeys = true      // незнакомые поля не ломают старую версию
    prettyPrint = false
}
```

`ignoreUnknownKeys = true` даёт **обратную совместимость**: если релей начнёт присылать новое поле, старое приложение просто его проигнорирует, а не упадёт.

### 3.2 `Command.kt` — все команды

Каждая команда — класс с `@SerialName` и свойством `name` (ключ для белого списка и аудита). Разбиты на «слои»:

| Слой | Команды | Кто выполняет |
|---|---|---|
| 1. Естественный язык | `NaturalLanguage(text)` → `nl` | Только релей (превращает в конкретную) |
| 2. Системные | `SetVolume(level)`, `Shutdown`, `Reboot`, `LockScreen`, `KillProcess(processName)`, `ListProcesses`, `OpenUrl(url)`, `OpenApp(appName)`, `Screenshot` | Агент |
| 3. Computer-use | `AgentTask(goal, scope=BROWSER, maxSteps=15)`, `Click(x,y)`, `TypeText(text)`, `Scroll(dx=0, dy)` | Агент |
| 4. Живой экран | `StartScreenStream(fps, maxWidth)`, `StopScreenStream` | Агент |
| 5. Буфер обмена | `GetClipboard`, `SetClipboard(text)` | Агент |
| 6. Файлы | `ListFiles(folder)`, `SendFileToPhone(folder, fileName)`, `SendFileToPc(folder, fileName, base64)` | Агент |
| 7. Управление задачей | `CancelTask`, `AnswerQuestion(questionId, answer)` | Агент |
| 8. Показ на экране ПК | `SetDisplayOption(option, enabled)` — `option` только `"xray"` или `"plan"` | Агент |

Всего 24 команды. `AnswerQuestion` — ответ человека на `AgentQuestion`. Это **команда, а не отдельный тип сообщения** — намеренно: так ответ проходит тот же путь, что любая команда телефона, то есть через белый список и журнал аудита релея. `SetDisplayOption` меняет настройки агента, поэтому список ключей закрытый: через неё нельзя дотянуться, например, до подтверждения опасных команд.

`data object` (например `LockScreen`, `Screenshot`) — команда без параметров, существует в одном экземпляре. В JSON: `{"type":"lock_screen"}`.

`AgentScope`: `BROWSER` (агент работает в Chrome по DOM) или `SCREEN` (любая программа).

### 3.3 `Result.kt` — результаты

- `CommandStatus`: `OK`, `ERROR`, `DENIED` (отклонено белым списком), `TIMEOUT`.
- `ResultData` — типизированная «нагрузка» ответа:

| Класс | type | Когда приходит | Что делает приложение |
|---|---|---|---|
| `ProcessList(processes)` | `process_list` | `list_processes` | Пишет «процессов: N» |
| `ClipboardText(text)` | `clipboard_text` | `get_clipboard` | Кладёт в буфер телефона |
| `AgentReport(completed, stepsTaken, trace)` | `agent_report` | конец `agent_task` | Сохраняет в историю, уведомление |
| `FileList(entries)` | `file_list` | `list_files` | Показывает в диалоге «Файлы» |
| `FileTransfer(fileName, base64, sizeBytes)` | `file_transfer` | `send_file_to_phone` | Сохраняет в «Загрузки» |

### 3.4 `Whitelist.kt` — внимание, расхождение

В `:shared` лежит `CommandWhitelist.DEFAULT`, но **настоящая** проверка работает на релее (`jarvis-relay/.../security/CommandWhitelist.java`). Kotlin-версия устарела: в ней нет `start_screen_stream`, `get_clipboard`, файлов, `cancel_task`, `answer_question`, `set_display_option`, и про запрет опасных целей (`ForbiddenGoals` на релее) она не знает. Приложение этот класс **нигде не использует** — он используется только в тесте. На защите честно скажи: «эталон проверки — на релее; Kotlin-копия — документация исходного замысла, её стоит синхронизировать или удалить».

### 3.5 `ProtocolTest.kt` — тесты

«Round-trip»: объект → JSON → объект, результат должен совпасть. Плюс проверки, что в JSON есть нужные `"type"`. С 30 сентября тесты покрывают и `agent_question`, `agent_plan`, `answer_question`, `set_display_option`; тем же JSON проверяется Java-копия релея (`AgentQuestionProtocolTest`). Это и есть **эталон JSON**, которому должны соответствовать Java-копии. Запуск: `gradlew :shared:jvmTest`.

## 4. Минимум Kotlin/Compose, чтобы читать код

Если что-то из этого непонятно — код приложения читать будет тяжело. Коротко:

| Понятие | Что это | Пример в коде |
|---|---|---|
| **Composable-функция** | Функция с `@Composable`, которая описывает кусок экрана. Её вызывают заново («рекомпозиция»), когда меняются данные | `ChatScreen()`, `MessageBubble()` |
| **`remember { … }`** | Запомнить значение между рекомпозициями (иначе оно создавалось бы заново) | `val voice = remember { VoiceController(context) }` |
| **`mutableStateOf`** | Переменная, изменение которой перерисовывает экран | `var input by remember { mutableStateOf("") }` |
| **`StateFlow`** | «Поток состояния»: всегда хранит текущее значение, подписчики получают изменения | `vm.messages` |
| **`collectAsState()`** | Подписать Compose на `StateFlow` | `val messages by vm.messages.collectAsState()` |
| **`SharedFlow`** | Поток **событий** (без «текущего значения», каждое событие обрабатывается один раз) | `WakeWordBus.phrases` |
| **`LaunchedEffect(key)`** | Запустить корутину, когда экран появился или изменился `key` | авто-прокрутка при новом сообщении |
| **`DisposableEffect`** | То же + код очистки `onDispose`, когда экран уходит | освободить микрофон, отпустить стрим |
| **ViewModel** | Объект, переживающий поворот экрана; хранит состояние и логику | `ChatViewModel` |
| **`AndroidViewModel`** | ViewModel с доступом к `Application` (нужен `Context` для DataStore) | все ViewModel в проекте |
| **`viewModelScope`** | Область корутин, которая отменяется, когда ViewModel уничтожен | `viewModelScope.launch { … }` |
| **`viewModel()`** | Получить ViewModel в Composable. В одной Activity возвращает **тот же объект** | `ChatScreen`, `ScreenViewScreen`, `HistoryScreen` делят один `ChatViewModel` |
| **Корутина / `suspend`** | «Лёгкий поток»: функция, которая может приостановиться (ждать сеть), не блокируя UI | `RelayPairing.pair(...)` |
| **`Dispatchers.IO / Default / Main`** | На каком пуле потоков выполнять: IO — сеть/диск, Default — вычисления, Main — UI | `withContext(Dispatchers.Default) { decode }` |
| **`sealed interface`** | Закрытое семейство типов; `when` проверяется на полноту | `PairingUiState`, `PairOutcome` |

## 5. Архитектура приложения: слои

```
┌────────────────────────────── UI (Jetpack Compose) ───────────────────────────────┐
│ MainActivity → ChatScreen / DevicesScreen / ScreenViewScreen / HistoryScreen /    │
│                MultiPcScreen / CustomActionsScreen / PairingScreen                │
│ + TaskPreviewWindow, ListeningOrb, диалоги (Settings, Files, Zoom)                │
└──────────────▲───────────────────────────────────────────────┬────────────────────┘
               │ StateFlow (collectAsState)                     │ вызовы методов
┌──────────────┴──────────────── ViewModel ─────────────────────▼────────────────────┐
│ ChatViewModel (главный, общий для 4 экранов) │ DevicesViewModel │ PairingViewModel │
│ MultiPcViewModel                              │ PairingController (общая логика)   │
└──────────────▲───────────────────────────────────────────────┬────────────────────┘
               │                                                │
┌──────────────┴──────── Данные / сеть / платформа ─────────────▼────────────────────┐
│ JarvisClient (постоянный WS)    RelayPairing (короткие WS)    WakeOnLan (UDP)       │
│ DeviceRepository, SettingsRepository (DataStore)                                    │
│ TaskHistoryRepository, CustomActionsRepository (JSON-файлы)                         │
│ VoiceController (SpeechRecognizer + TTS)   FileTransfer (MediaStore)                │
│ JarvisConnectionService + VoskWakeWord ──WakeWordBus──► ChatViewModel               │
└──────────────────────────────────────────────────────────────────────────────────────┘
                                   │ протокол из :shared
                                   ▼
                              Релей (ws://…/ws)
```

Правило: **UI ничего не знает о сети**. Экран только читает `StateFlow` и вызывает методы ViewModel. ViewModel решает, что отправить. Это называют **MVVM** (Model–View–ViewModel), в облегчённом виде — без DI-фреймворков (Hilt/Koin): зависимости создаются прямо в ViewModel (`private val client = JarvisClient()`).

Навигации (Navigation Compose) нет: экраны переключаются обычной переменной `screen` в `MainActivity`. Разделов немного — фреймворк был бы избыточен.

## 6. AndroidManifest.xml — разрешения

| Разрешение | Тип | Зачем |
|---|---|---|
| `INTERNET` | обычное | WebSocket и Wake-on-LAN (UDP) |
| `RECORD_AUDIO` | **runtime** (спрашивается диалогом) | Кнопка 🎤 и слово «Фауст» |
| `CAMERA` (+ `uses-feature required=false`) | **runtime** | Сканер QR. `required=false` — без камеры приложение ставится, остаётся ручной ввод кода |
| `POST_NOTIFICATIONS` | **runtime** на Android 13+ | Уведомление «задача выполнена» |
| `FOREGROUND_SERVICE` | обычное | Запуск foreground-сервиса |
| `FOREGROUND_SERVICE_DATA_SYNC` | обычное | Тип сервиса «держим связь» |
| `FOREGROUND_SERVICE_MICROPHONE` | обычное | Тип сервиса «пишем звук» (Android 14+) |

Прочее:
- `<queries>` с `RecognitionService` и `TTS_SERVICE` — на Android 11+ приложение не «видит» другие пакеты. Без этого блока `SpeechRecognizer.isRecognitionAvailable()` вернёт `false`, а озвучка не найдёт движок.
- `android:usesCleartextTraffic="true"` — разрешает незашифрованный `ws://`. Иначе Android заблокирует соединение. В продакшене должен быть `wss://` (TLS).
- Сервис `JarvisConnectionService` объявлен с `foregroundServiceType="dataSync|microphone"`; конкретный тип выбирается в коде при старте.
- `android:label="Faust"` — имя в лаунчере.

## 7. Разбор по файлам

### 7.1 `MainActivity.kt` — вход, навигация и экран чата (1494 строки)

Самый большой файл. Состоит из нескольких частей.

#### Навигация

```kotlin
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC, CUSTOM_ACTIONS }
```

`MainActivity.onCreate` → `setContent { JarvisTheme { … } }`:
1. Создаёт `DeviceRepository` и подписывается на список ПК (`collectAsState(initial = null)`).
2. `null` — DataStore ещё не прочитан → ничего не показывает (чтобы не мигнуть экраном привязки).
3. Пустой список → `PairingScreen()` (первый запуск).
4. Есть хотя бы один ПК → переменная `screen` и `Crossfade` (плавная смена) между разделами.

Когда `PairingScreen` успешно привяжет ПК, он запишет его в DataStore → `devices` изменится → `MainActivity` **сама** переключится на чат. Явного сигнала «привязано» нет — это реактивность.

#### Быстрые подсказки и действия

```kotlin
QUICK_PHRASES  // вставляют текст в поле: "скриншот", "процессы", "запусти блокнот", "сделай потише", "найди на ютубе "
QUICK_ACTIONS  // сразу шлют Command: 🔒 LockScreen, 🔇 SetVolume(0), 🔊 SetVolume(50), 🌐 OpenApp("браузер"), 📋⬆, 📋⬇
```

Разница: `QUICK_PHRASES` идут через релей как фраза (`nl`) и разбираются ИИ; `QUICK_ACTIONS` шлют **готовую команду** через `vm.sendCommand(...)` — быстрее и детерминированно. «найди на ютубе » с пробелом — шаблон, который ты дописываешь.

#### `ChatScreen` — главный экран

Что он собирает из ViewModel (`collectAsState`): `messages`, `busy`, `status`, `activeDevice`, `unauthorized`, `repairing`, `wakeWordEnabled`, `wakeState`, `customActions`, `liveFrame`, `livePreview`, а с 30 сентября ещё `openQuestion` (открытый вопрос агента), `xrayEnabled` и `planPanelEnabled` (переключатели показа на ПК).

Ключевые механизмы внутри:

| Механизм | Как работает |
|---|---|
| **Отправка** `submit(text)` | Единая точка для текста из поля и распознанной речи → `vm.send(t)` |
| **Голос с правкой** `onSpeechText` | Распознанная фраза попадает в поле на **1.5 с** — можно поправить; потом уходит то, что в поле |
| **Кнопка 🎤** `onMicClick` | Проверяет разрешение; ставит `WakeWordBus.suspended = true`, чтобы Vosk отпустил микрофон (два потребителя `AudioRecord` одновременно не работают) |
| **Разрешения** | `rememberLauncherForActivityResult(RequestPermission())` — отдельные лаунчеры для микрофона, wake-word и уведомлений |
| **Жизненный цикл** | `LifecycleEventObserver`: `ON_STOP` → `vm.onAppBackgrounded()`, `ON_START` → `vm.onAppForegrounded()` |
| **Авто-прокрутка** | `LaunchedEffect(messages.size) { listState.animateScrollToItem(last) }` |
| **Озвучка** | При новом сообщении от ПК без картинки → `voice.speak(text)` |
| **Голосовой диалог** | Если новое сообщение — **вопрос агента**, телефон его озвучивает (к вопросу «Да/Нет» добавляет «Скажите „да“ или „нет“»), а когда договорит — **сам включает микрофон**, если вопрос ещё открыт и разрешение на микрофон выдано. Распознанное идёт обычным путём `submit → vm.send`, а `send`, видя открытый вопрос, отправляет это ответом |
| **Поле ввода как поле ответа** | Пока есть открытый вопрос, подсказка в поле меняется на «ответ для Фауста…»: всё напечатанное уходит агенту ответом, а не новой командой |
| **Сторож стрима** | Пока включено окошко живого экрана — раз в 4 с `vm.restartStreamIfSilent()` |
| **Повторная привязка** | Если релей отверг токен (`unauthorized`), вместо «Подкл.» кнопка «Привязать заново» → сканер QR |

Разметка сверху вниз: боковое меню (`ModalNavigationDrawer` → `ChatDrawer`), `TopAppBar` (заголовок «Faust», цветная точка статуса, индикатор wake-word, кнопка живого экрана, настройки) → плашка «ПК: имя» + «Подкл./Откл.» → список сообщений (`LazyColumn`) с окошком живого экрана поверх → ряд быстрых действий (включая «⛔ Стоп», видимый только пока `busy`) → ряд подсказок → поле ввода + 🎤 + отправить.

Статус подключения определяется **по строке**: `connected = status.contains("✓")`. Это простой, но хрупкий приём (см. раздел 12).

#### Прочие Composable в файле

| Функция | Что рисует |
|---|---|
| `ChatDrawer`, `DrawerLink` | Выдвижное меню: Устройства, Мульти-ПК, Экран ПК, Файлы, История задач, Свои команды, Настройки. Раньше были 7 кнопок в шапке — не помещались на 360dp |
| `WakeStateIndicator` | Иконка ⌛/👂/🎤 по состоянию wake-word, с подписью для TalkBack |
| `PillChip` | Чип-«пилюля» из макета |
| `statusDotColor` | Зелёный/красный/серый по тексту статуса |
| `SettingsDialog` | Адрес релея, переключатели «Просыпаться по Фауст» и «Озвучивать ответы», **«Рентген: как ИИ видит окно»** и **«План и мысли агента»** (оба управляют тем, что рисуется на экране ПК), «Отвязать этот ПК» |
| `QuestionBubble` | Карточка-вопрос агента: текст, кнопки-варианты, обратный отсчёт «осталось N с», строка состояния («отправляю ответ…», «✓ агент получил ответ „Да“», «вопрос закрыт — агент продолжил без ответа»). Варианты лежат в `FlowRow` — переносятся на новую строку, а не уезжают за край: имён контактов может быть три-четыре |
| `PlanBubble`, `PlanRow` | Карточка-план: нумерованный чек-лист подзадач со значком статуса и короткой заметкой (итог или причина неудачи) |
| `QuestionStatus` | Мелкая строка состояния под вопросом |
| `FileTransferDialog` | Вкладки Загрузки/Документы/Рабочий стол/Изображения, список файлов, ⬇ скачать, ⬆ отправить |
| `MessageBubble` | Пузырь сообщения: цвет по автору, «хвостик», картинка, прицел, время, статус «отправлено → выполняется… N с → готово ✓ / ошибка / отклонено» |
| `SystemNote` | Скромная строка по центру для системных сообщений |
| `ZoomableScreenshotDialog` | Скриншот на весь экран: щипок 1–5×, перетаскивание, двойной тап |
| `AimMarker` | Красный прицел с пульсирующей волной. Координаты переводятся в **доли** картинки → не зависят от масштаба отображения |
| `AgentStepHeader` | «🤖 ИИ-агент · шаг N» + «🎯 цель» |
| `EmptyChatHint` | Заставка «Чем помочь?» на пустом чате |

Счётчик секунд «выполняется… N с»: у каждого сообщения со статусом `RUNNING` свой `LaunchedEffect`, раз в секунду пересчитывающий время. Нужен, чтобы долгая ИИ-задача (одиночная — до 3 минут, по плану — до 10) не выглядела зависшей.

В списке сообщений пузырь выбирается по содержимому: `msg.question != null` → `QuestionBubble`, `msg.plan != null` → `PlanBubble`, иначе обычный `MessageBubble`. Карточка-вопрос тикает своим `LaunchedEffect` раз в секунду, **только пока вопрос открыт**: агент ждёт ограниченное время, и человеку полезно видеть, сколько осталось.

### 7.2 `ChatViewModel.kt` — мозг чата (843 строки)

Главный ViewModel. Им пользуются **четыре экрана** (Чат, Экран ПК, История, Свои команды) — Compose отдаёт им один и тот же объект.

#### Модель сообщения

```kotlin
enum class Author { ME, PC, SYSTEM }
enum class CommandProgress { SENT, RUNNING, DONE, ERROR, DENIED }

data class ChatMessage(
    val id: String = UUID…,        // ключ для LazyColumn и анимаций
    val author: Author,
    val text: String,
    val imageBase64: String? = null, // скриншот или картинка шага
    val requestId: String? = null,   // связывает команду с ответом
    val targetX: Int? = null, val targetY: Int? = null, // прицел ИИ
    val agentStep: Int? = null,      // номер шага agent_task
    val progress: CommandProgress? = null, // только у моих команд
    val sentAtMs: Long = now,
    val question: QuestionCard? = null,    // не null — это вопрос агента, пузырь рисуется карточкой
    val plan: AgentPlan? = null,           // не null — это план задачи, карточка-чек-лист (одна на задачу)
)

enum class QuestionState { WAITING, SENDING, ANSWERED, EXPIRED }

data class QuestionCard(
    val questionId: String,
    val options: List<String>,        // кнопки; пусто — только свой ответ в поле ввода
    val state: QuestionState = WAITING,
    val answer: String? = null,       // что ответил человек — показывается в карточке
    val expiresAtMs: Long? = null,    // когда агент перестанет ждать
) {
    fun isOpen(now): Boolean = state == WAITING && (expiresAtMs == null || now < expiresAtMs)
}
```

Жизнь вопроса: `WAITING` (кнопки активны) → `SENDING` (ответ ушёл, ждём подтверждения агента) → `ANSWERED` (агент принял). `EXPIRED` — агент больше не ждёт: вышло время, задача кончилась или отменена. `isOpen` смотрит не только на состояние, но и **на часы**: карточка не должна принимать ответ после того, как агент перестал ждать.

#### Зависимости (создаются прямо в классе)

`JarvisClient`, `DeviceRepository`, `PairingController`, `SettingsRepository`, `TaskHistoryRepository`, `CustomActionsRepository`.

#### Публичное состояние (StateFlow)

| Поле | Что хранит |
|---|---|
| `messages` | Список сообщений чата |
| `busy` | Есть ли команда в `SENT`/`RUNNING` — **вычисляется из `messages`**, а не хранится отдельно («один источник правды») → показывает кнопку «Стоп» |
| `openQuestion` | Последний открытый вопрос агента (или `null`) — тоже вычисляется из `messages`. Пока он есть, поле ввода работает как поле ответа |
| `xrayEnabled`, `planPanelEnabled` | Что показывать на экране ПК; читаются из `SettingsRepository`, по умолчанию оба включены |
| `status` | Строка статуса связи («подключено ✓», «ПК «X» офлайн», «переподключение… (через 4 с)») |
| `liveFrame` | Последний кадр живого стрима (в чат не попадает — иначе 3–5 кадров/с забили бы список) |
| `livePreview` | Включено ли окошко живого экрана |
| `fileList` | Файлы из последнего `ListFiles` |
| `activeDevice` | ПК, на который уходят команды |
| `unauthorized` | Релей отверг токен → нужна повторная привязка |
| `repairing` | Состояние повторной привязки |
| `wakeWordEnabled`, `wakeState` | Слово-триггер |
| `taskHistory`, `customActions` | Из репозиториев |

#### `init` — что происходит при создании

1. Загрузить историю и свои кнопки из файлов.
2. Подписаться на `WakeWordBus.phrases` → каждую фразу отправить через `send(it)` (тем же путём, что ручной ввод).
3. Подписаться на `WakeWordBus.notes` → служебные строки в чат.
4. Прочитать настройку wake-word; если была включена — поднять сервис сразу (приложение на переднем плане → Android 12+ разрешит).
5. `combine(devices, activeDeviceId)` → найти активный ПК → `connect()`. Дальше `drop(1).collect`: при смене активного ПК или его токена/адреса — переподключиться.

#### Отправка

- `send(text)` — фраза. Четыре развилки по порядку:
  1. известно, что ПК офлайн → **не отправляет**, а сразу пишет понятную заметку;
  2. есть открытый вопрос агента → это **ответ**, а не новая команда: `answerQuestion(...)`. Иначе «Маше» ушло бы на релей как фраза для разбора и превратилось бы в непонятную команду;
  3. фраза совпала с навыком-рецептом («навык утро», «доброе утро») → `sendTracked(text, recipe.toCommand())` — на ПК уходит готовая `AgentTask` с нумерованными шагами;
  4. иначе → `sendTracked(text, NaturalLanguage(text))`, фразу разберёт релей.
- `sendTracked(shownText, command)` — реплика `ME` со статусом `SENT` + команда **с тем же `requestId`**: к этой реплике потом прикрепятся шаги, план, вопросы и итог. Через **0.6 с** статус переходит в `RUNNING` (эвристика «приняли в работу»).
- `runCustomAction(record)` — чип «Своих команд». Рецепт показывается в чате репликой (ему нужно, к чему прикрепить план и вопросы); остальные чипы — мгновенные команды без реплики.
- `sendCommand(command)` — готовая команда без сообщения в чате (стрим, клик, быстрые кнопки).
- `note(text)` — локальная системная строка, в сеть не уходит.

#### Ответ на вопрос агента

`answerQuestion(messageId, answer)` вызывается кнопкой в карточке или из `send`:

1. карточка уже закрыта (`!isOpen()`) → в сеть ничего не идёт, карточка гаснет, заметка «вопрос уже неактуален — если это была новая команда, отправьте её ещё раз»;
2. иначе: новый `requestId` запоминается в `answerRequests[requestId] = messageId`, карточка → `SENDING`, в ленту добавляется реплика с ответом (диалог читается как в мессенджере: «Кому отправить?» — «Лере и Маше»), уходит `CommandRequest(AnswerQuestion(questionId, answer))`.

Результат этой команды отдельным пузырём не рисуется — он только переключает карточку: `OK` → `ANSWERED`, иначе → `EXPIRED` + заметка «агент уже не ждал ответа».

Карточку меняет `updateQuestion` через `_messages.update { … }` — **атомарно**, потому что пишут два потока: UI (нажатие кнопки) и OkHttp (пришёл результат).

#### Переключатели показа на ПК

`setXrayEnabled(on)` / `setPlanPanelEnabled(on)`: запомнить в `SettingsRepository` и послать `SetDisplayOption("xray" | "plan", on)` — ответ «рентген включён на ПК» придёт в чат. А `syncDisplayOptions()` шлёт оба значения **молча при каждом подключении** (их `requestId` кладутся в `silentRequests`, результат в чат не пишется): так переключатель на телефоне и экран ПК не расходятся, даже если ПК перезапускали или его настройки правили руками.

#### Живой экран: «владельцы» стрима

Стрим может хотеть окошко в чате (`HOLDER_PREVIEW`, ширина 480) и полноэкранный «Экран ПК» (`HOLDER_FULLSCREEN`, ширина 800). Раньше выход из полноэкранного слал `StopScreenStream`, и окошко застывало. Теперь:

```kotlin
acquireStream(holder, maxWidth)  // добавить владельца, запросить стрим с max шириной среди всех
releaseStream(holder)            // убрать; если никого не осталось — Stop, иначе перезапуск с меньшей шириной
restartStreamIfSilent()          // тишина > 12 с при наличии владельцев → повторный Start
```

Почему 12 с: агент даже на неподвижном экране шлёт «опорный» кадр раз в 3 с, так что 12 с тишины — точно обрыв (агент сам гасит стрим через 10 минут, или было переподключение).

#### Прочие действия

| Метод | Что делает |
|---|---|
| `connect()` / `disconnect()` | Подключиться к активному ПК / отключиться |
| `updateRelayUrl(url)` | Сменить адрес релея ПК (смена сети) → сохранится → `init` сам переподключится |
| `unpair(id)` | Отключиться и удалить ПК |
| `repairByQr(raw)` | Повторная привязка через `PairingController` (новый токен под тем же id) |
| `sendPhoneClipboardToPc()` | Прочитать буфер телефона → `SetClipboard` |
| `requestPcClipboard()` | `GetClipboard` → ответ `ClipboardText` → в буфер телефона |
| `setWakeWordEnabled(on)` | Сохранить + включить/выключить сервис |
| `cancelTask()` | `CancelTask` + честная заметка «остановится после текущего шага» |
| `listFiles/downloadFile/uploadFile` | Файловые команды |
| `onAppBackgrounded/Foregrounded` | Поднять/опустить keep-alive сервиса |
| `add/update/removeCustomAction`, `clearHistory` | Работа с репозиториями |

#### `handle(msg)` — обработка входящих

```kotlin
when (msg) {
  is CommandResult -> {
      // 0. служебный (silentRequests) → молча выйти
      //    результат нашего ответа на вопрос (answerRequests) → переключить карточку и выйти
      //    задача закончилась → её вопросы без ответа гаснут (EXPIRED)
      // 1. статус: OK→DONE, DENIED→DENIED, ERROR/TIMEOUT→ERROR
      // 2. данные: ClipboardText→буфер, FileList→диалог, FileTransfer→Загрузки,
      //            AgentReport→история (+ уведомление, если свёрнуто)
      // 3. если свёрнуто и больше нет ожидающих команд → отпустить keep-alive
      // 4. сообщение от PC с resultText(msg)
  }
  is ScreenFrame -> seq != null ? liveFrame = msg : (сообщение "📸 скриншот" + статус DONE,
                                                     но не затирая уже пришедший ERROR/DENIED)
  is AgentStep   -> карточка "🔎 описание" с картинкой и прицелом (статус НЕ меняет);
                    вторая строка описания — обоснование модели — показывается как "💭 …"
  is AgentPlan   -> карточка плана этой задачи уже есть → заменить содержимое НА МЕСТЕ;
                    нет → добавить сообщение "План из N шагов" (его и озвучит TTS)
  is AgentQuestion -> сообщение с QuestionCard(questionId, options, expiresAt = now + timeout);
                    приложение свёрнуто → уведомление "Вопрос от агента";
                    таймер на timeoutSeconds → карточка гаснет в тот же момент, что и ожидание агента
  is AuthResult  -> !ok → unauthorized; agentOnline==false → "ПК офлайн";
                    иначе "подключено ✓" + syncDisplayOptions()
  else           -> игнор (ping/pong)
}
```

Почему карточка плана одна: агент присылает план целиком при каждом изменении (подзадача началась, закончилась, план перестроен). Если бы каждое сообщение добавляло пузырь, в ленте оказалось бы пять копий одного списка.

`resultText(msg)` — короткий текст для чата и озвучки: `message`, а если его нет — сворачивает данные («процессов: 100», «готово за 4 шагов»).

`saveToHistory` — берёт текст запроса и время из **моего** сообщения с тем же `requestId` (в `AgentReport` их нет) и пишет `TaskRecord`.

### 7.3 `JarvisClient.kt` — WebSocket с автопереподключением (185 строк)

Постоянное соединение с релеем на **OkHttp**.

```
connect(url, token, deviceId, onMessage, onStatus)
   └─► openSocket()  ── ws:// заменяется на http:// (OkHttp так требует)
         └─► onOpen    → отправить Auth(role=PHONE)       статус "вход…"
             onMessage → разобрать JSON → AuthResult?
                             ok   → задержка = 1 с, "подключено ✓"
                             !ok  → authRejected, НЕ переподключаться, "вход отклонён"
                          → передать onMessage(msg)
             onFailure / onClosed → если wantConnected → scheduleReconnect()
```

**Экспоненциальная задержка:** 1 → 2 → 4 → 8 → 16 → 30 → 30 с. Сбрасывается в 1 с после успешного входа. Задержка планируется через `Handler(Looper.getMainLooper())` — задачи на главном потоке.

Флаги:
- `wantConnected` — пользователь хочет быть на связи (между `connect()` и `disconnect()`). Без него после «Откл.» клиент переподключался бы сам.
- `authRejected` — токен отвергнут. Переподключаться с тем же токеном бессмысленно, а `onClosed` не должен затереть статус «вход отклонён» безликим «отключено».

`send(msg)` — просто `ws?.send(JSON)`; OkHttp сам ставит отправки в очередь, так что звать можно из любого потока.

Важно: колбэки OkHttp приходят **не на главном потоке**. ViewModel пишет в `MutableStateFlow` — это потокобезопасно, поэтому работает.

### 7.4 `RelayPairing.kt` — короткие одноразовые соединения (204 строки)

`object` (синглтон) для случаев, когда держать постоянное соединение не нужно: открыл → получил один ответ → закрыл.

| Функция | Отправляет | Ждёт | Возвращает | Таймаут |
|---|---|---|---|---|
| `pair(relayUrl, code)` | `PairRequest` | `PairResult` | `PairOutcome.Success(PairedDevice)` / `Rejected(reason)` / `Failed(msg)` | 12 с |
| `checkStatus(device)` | `Auth` | `AuthResult` | `ONLINE` / `OFFLINE` / `UNAUTHORIZED` / `UNKNOWN` | 6 с |
| `sendCommand(device, command)` | `Auth`, потом `CommandRequest` | свой `CommandResult` | `CommandOutcome.Success/Rejected/Failed` | 15 с |

Приём превращения колбэков в `suspend`-функцию:

```kotlin
private suspend fun oneShot(relayUrl, onOpen): JarvisMessage? =
    suspendCancellableCoroutine { cont ->
        val listener = object : WebSocketListener() {
            override fun onOpen(ws, r) = onOpen(ws, requestId)
            override fun onMessage(ws, text) { if (cont.isActive) cont.resume(parse(text)); ws.close(1000, "done") }
            override fun onFailure(ws, t, r) { if (cont.isActive) cont.resume(null) }
        }
        val ws = http.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }   // таймаут/отмена → закрыть сокет
    }
```

`suspendCancellableCoroutine` «замораживает» корутину, пока кто-то не вызовет `cont.resume(...)`. `withTimeoutOrNull` сверху отменяет её по таймауту. `if (cont.isActive)` защищает от двойного `resume` (это исключение).

`agentOnline == null` (старый релей) трактуется как `ONLINE` — лучше не пугать пользователя «офлайн» без оснований.

### 7.5 Привязка ПК

#### `PairingQr.kt` — разбор QR

Формат: `jarvis-pair://pair?code=482931&relay=ws%3A%2F%2F…&name=…&mac=…`

- Строка из одних цифр → это просто код (relay берём из настроек).
- Иначе парсим как `Uri`, схема должна быть `jarvis-pair`, `code` обязателен; `relay`, `name`, `mac` — необязательны.
- Всё остальное → `null` (привязку не начинаем).

#### `QrScan.kt` — `rememberQrScanLauncher(onScanned, onDenied)`

Общая функция для трёх мест (первый запуск, «Устройства», повторная привязка в чате). Проверяет разрешение камеры → при необходимости спрашивает → открывает экран zxing (`ScanContract`) только для QR, без звука. Возвращает функцию, которую вешают на `onClick`. `contents == null` — пользователь закрыл сканер, не ошибка.

#### `PairingController.kt` — общая логика привязки

```kotlin
sealed interface PairingUiState { Idle, InProgress, Error(message), Done }
```

- `pairByCode(code, relayUrl)` / `pairByQr(raw, fallbackRelayUrl)` (адрес из QR важнее).
- Внутри `pair(...)`: проверки на пустоту → `InProgress` → `RelayPairing.pair` → при успехе `devicesRepo.addAndActivate(device.copy(mac = mac))` → `Done`.

Это не ViewModel, а помощник, живущий в `scope` своего владельца. Используется в `PairingViewModel`, `DevicesViewModel`, `ChatViewModel` — чтобы логика не дублировалась.

#### `PairingScreen.kt` + `PairingViewModel.kt` — первый запуск

Большая кнопка «📷 Сканировать QR»; под ссылкой «Ввести код вручную» — поля адреса релея и кода. После `Done` адрес сохраняется как «последний использованный» (подсказка для следующей привязки). Переход в чат происходит сам (см. 7.1).

### 7.6 Хранилища

#### `DeviceRepository.kt` — список ПК

```kotlin
@Serializable
data class PairedDevice(
    val id: String,        // deviceId агента — им телефон представляется в Auth
    val name: String,      // "Рабочий ПК"
    val token: String,     // постоянный токен ТЕЛЕФОНА для этого ПК
    val relayUrl: String,  // у каждого ПК свой релей (разные сети)
    val mac: String? = null, // для Wake-on-LAN; null у старых привязок
)
```

Хранение: **Preferences DataStore** (файл `devices`). DataStore хранит только примитивы, поэтому список пишется **одной JSON-строкой** (`devices_json`) + отдельный ключ `active_device_id`.

| Метод | Что делает |
|---|---|
| `devices: Flow<List<PairedDevice>>` | Поток списка (битый JSON → пустой список, без краша) |
| `activeDeviceId: Flow<String?>` | Поток id активного |
| `addAndActivate(device)` | Заменить запись с тем же id (или добавить) и сделать активной |
| `remove(id)` | Удалить; если была активной — сбросить выбор |
| `setActive(id)` | Выбрать активный |

Почему `Auth.deviceId` у телефона = id **агента**: релей по этому id находит привязки телефонов к этому ПК (`phone_bindings`) и сверяет токен (подробно — файл 3).

#### `SettingsRepository.kt` — настройки

Отдельный DataStore `settings`: `relay_url` (последний адрес, по умолчанию `ws://10.0.2.2:8080/ws` — «localhost компьютера» с точки зрения эмулятора), `wake_word_enabled`, а также `pc_xray_enabled` и `pc_plan_panel_enabled` — показывать ли на экране ПК «рентген» и панель «План и мысли» (оба по умолчанию `true`).

Эти два ключа — настройки **компьютера**, но хранятся на телефоне: телефон и есть пульт, и при каждом подключении он сообщает ПК свои значения (`syncDisplayOptions`, см. 7.2).

### 7.7 Экран «Устройства» + Wake-on-LAN

#### `DevicesViewModel.kt`

`DeviceUi(device, isActive, status, checking)`. При каждом изменении списка:
1. сохраняет уже известные статусы (иначе список мигал бы «неизвестно»);
2. вызывает `refreshStatuses()` — **параллельно** для всех ПК (`async { checkStatus } … awaitAll()`).

`pairByCode/pairByQr` берут адрес релея из `SettingsRepository`.

#### `DevicesScreen.kt`

Список карточек: радиокнопка «активный», имя, цветная точка + статус («в сети», «офлайн», «отвязано на релее», «неизвестно», «проверка…»), кнопка ⏻ «Разбудить» (если известен MAC), 🗑 «Отвязать». Шапка — ⟳ обновить статусы. FAB «+» → диалог «Привязать ПК» (код или QR).

#### `WakeOnLan.kt`

«Волшебный пакет»: 6 байт `0xFF` + MAC, повторённый 16 раз (итого 102 байта), UDP-broadcast на `255.255.255.255:9`. Вызывается на `Dispatchers.IO`.

Ограничения (честно пишутся в снэкбаре): работает в **одной локальной сети**, лучше с ПК на кабеле, и в Windows должна стоять галочка «Wake on Magic Packet» у сетевой карты. MAC приходит из QR-кода — у привязанных вручную ПК его нет, кнопка скрыта.

### 7.8 Живой экран ПК

#### `ScreenViewScreen.kt` — полноэкранный «Экран ПК»

Своей ViewModel нет — использует `ChatViewModel` (там уже есть соединение и `liveFrame`).

- `DisposableEffect`: при входе `acquireStream(FULLSCREEN, 800)`, при выходе `releaseStream(FULLSCREEN)`.
- **Тап → клик на ПК.** Главная формула:

```
realX = tapX × screenWidth / displayedWidth
realY = tapY × screenHeight / displayedHeight
```

`screenWidth` — **реальное** разрешение ПК из `ScreenFrame.screenWidth` (fallback — ширина кадра для старого агента). `displayedWidth` — сколько пикселей картинка заняла на телефоне. `Modifier.aspectRatio(frame.width / frame.height)` гарантирует, что картинка без чёрных полос → масштаб одинаков по всей площади.

- **Зум без поломки координат.** Щипок ловит внешний `Box` (он не масштабируется — координаты жеста стабильны), а тап ловит сама картинка **после** `graphicsLayer`. Compose переводит касание через обратную матрицу слоя → offset приходит в неувеличенных координатах → формула выше работает при любом зуме. Зум 1–4×, сдвиг ограничен так, чтобы край картинки не уезжал.
- **Нет двойного тапа для сброса зума** — `detectTapGestures(onDoubleTap)` задерживал бы **каждый** тап на ~300 мс (ждёт второй), и двойной клик на ПК стал бы невозможен. Вместо этого кнопка «1×».
- Боковая панель: ⬆/⬇ → `Scroll(dy = ∓3)`, ⌨ → поле ввода, текст уходит `TypeText` (на ПК вставляется через Ctrl+V туда, где фокус).

#### `TaskPreviewWindow.kt` — окошко в углу чата

Маленькое окно (по умолчанию 150dp, тянется за уголок от 110 до 320dp, размер переживает поворот — `rememberSaveable`). Подпись «● живой экран», кнопки «во весь экран» и «✕» (крестик **выключает стрим**, а не просто прячет — иначе ПК слал бы кадры впустую). Поверх — оранжевая точка цели ИИ из последнего `AgentStep`. Не появляется, пока не пришёл первый кадр.

#### `FrameDecoding.kt` — `rememberDecodedFrame(base64)`

Декодирование base64 → JPEG → `ImageBitmap` на `Dispatchers.Default`, **не в главном потоке**. Раньше это делалось в композиции (главный поток) 5 раз в секунду — отсюда рывки, узким местом был телефон, а не ПК. Пока новый кадр декодируется, показывается **предыдущий** (иначе картинка мигала бы пустотой). Битый кадр → остаётся старый.

### 7.9 Голос

#### `VoiceController.kt`

Не ViewModel и не трогает сеть. Живёт, пока показан экран (`remember` + `onDispose { shutdown() }`).

- **Распознавание:** системный `SpeechRecognizer` (обычно Google, **нужен интернет**), язык `ru-RU`, свободная речь. Создаётся лениво при первом нажатии. `onRmsChanged(rmsdB)` → уровень 0…1 для анимированного шара. Коды ошибок переводятся в понятный текст («не расслышал, повтори»).
- **Озвучка:** `TextToSpeech`, русская локаль, `QUEUE_FLUSH` (новая фраза прерывает старую), обрезка до 200 символов.
- **«Договорил — вызови»:** `speak(text, onDone)`. Каждой фразе даётся свой id, колбэк кладётся в `afterSpeech[id]`, а `UtteranceProgressListener.onDone` достаёт его и выполняет **через `mainHandler.post`** — колбэки TTS приходят не на главном потоке, а микрофон (`SpeechRecognizer`) можно трогать только с главного. На этом построен голосовой диалог: вопрос агента озвучен → включился микрофон. Если озвучка выключена, `onDone` не вызывается вовсе: молча включать микрофон человеку, который выбрал тишину, было бы странно.
- Наружу — Compose `State`: `isListening`, `ttsEnabled`, `voiceLevel`.

#### `ListeningOrb.kt`

`ListeningIndicator` — большой анимированный шар поверх чата во время записи, реагирует на громкость голоса. Тап по шару останавливает запись.

### 7.10 Wake-word «Фауст» (фоновый слух)

Как это устроено вместе:

```
 ┌─────────────── JarvisConnectionService (foreground) ──────────────┐
 │  VoskWakeWord ── микрофон 16 кГц ── офлайн STT ── "фауст открой ютуб"│
 │        │ onPhrase("открой ютуб")                                      │
 └────────┼──────────────────────────────────────────────────────────────┘
          ▼
     WakeWordBus.phrases (SharedFlow)  ──────►  ChatViewModel.send("открой ютуб")
     WakeWordBus.state   (StateFlow)   ──────►  индикатор в шапке + текст уведомления
     WakeWordBus.suspended ◄── ChatScreen (кнопка 🎤 занимает микрофон)
```

#### `VoskWakeWord.kt`

Идея: **отдельного детектора слова нет**. Vosk непрерывно переводит речь в текст, а мы ищем в тексте «фауст». Поэтому не нужно передавать микрофон между двумя движками.

- Первый запуск: модель копируется из `assets/model-ru` во внутреннюю память (`StorageService.unpack`) — нативный код Vosk не умеет читать из APK. Маркер версии — файл `uuid` в модели.
- Два сценария:
  1. «фауст открой ютуб» одной фразой → всё после слова = команда;
  2. «фауст» … пауза … «открой ютуб» → окно ожидания 8 с, следующая фраза = команда.
- `normalize`: нижний регистр, ё→е, убрать пунктуацию.
- `TRIGGERS`: «фаустт», «фаусд», «фаузт», «фаост», «фауст», «фа уст», «фаус», «фауз», «faust» — маленькая модель слышит по-разному. **Порядок важен**: короткий вариант должен идти после длинного, иначе «фаус» сработает внутри «фауст» и командой станет «т открой ютуб».
- **Граница приватности:** всё, что сказано не после «Фауст», никуда не уходит — распознаётся и выбрасывается на телефоне.

#### `WakeWordBus.kt`

Синглтон-«шина» между сервисом и ViewModel (они в одном процессе, полноценный bound-сервис был бы +50 строк ради того же). `WakeState`: `OFF`, `LOADING`, `WAITING`, `RECORDING`, `ERROR`. Фразы — `SharedFlow` (события, `extraBufferCapacity = 8`, чтобы `tryEmit` не терял).

#### `JarvisConnectionService.kt`

Foreground-сервис с **двумя независимыми причинами жить**:
1. **keep-alive задачи** — приложение свёрнуто, а ИИ-задача ещё идёт. Сервис **не владеет** соединением (оно в `ChatViewModel`), он только не даёт Android убить процесс.
2. **wake-word** — крутит Vosk.

Сервис живёт, пока истинна хоть одна причина. Команды приходят интентами `ACTION_TASK` / `ACTION_WAKE` с флагом `on`. Поэтому возврат в приложение шлёт `taskKeepAlive(false)`, а **не** «стоп сервис» — иначе выключился бы и wake-word.

Тонкости Android:
- `startForeground` вызывается **всегда и сразу** — иначе через ~5 с система убьёт процесс (`ForegroundServiceDidNotStartInTimeException`).
- Тип `microphone` объявляется **только** если wake-word включён и разрешение выдано — на Android 14+ иначе `SecurityException`.
- Android 12+ запрещает стартовать foreground-сервис из фона. Вызов из `ON_STOP` иногда не попадает в разрешённое окно → обёрнут в `runCatching` (не падаем; соединение всё равно живёт в ViewModel).
- `WakeWordBus.suspended` → `engine.stop()/start()` — отпустить микрофон на время кнопки 🎤.

#### `Notifications.kt`

Три канала: `jarvis_ongoing` («Соединение с ПК», `IMPORTANCE_MIN`, тихое постоянное), `jarvis_task_done` («Задача выполнена», обычное) и `jarvis_agent_question` («Вопрос от агента», `IMPORTANCE_HIGH` — всплывает поверх экрана). Разделены, чтобы можно было заглушить один, не потеряв другие. `updateOngoingNotification` меняет текст («Слушаю «Фауст»…», «Готовлю модель…») без перезапуска сервиса. `notifyAgentTaskFinished` и `notifyAgentQuestion` — тап открывает приложение.

Почему вопросу нужен свой громкий канал: агент ждёт ответа ограниченное время (по умолчанию 2 минуты). Если приложение свёрнуто и человек о вопросе не узнает, агент простоит до таймаута и решит «ответа нет» — а для необратимого шага это означает отказ.

### 7.11 Файлы: `FileTransfer.kt`

- `MAX_TRANSFER_BYTES = 5 МБ` — тот же лимит, что у агента. Проверяем на телефоне заранее, чтобы не гнать base64 впустую.
- `readPickedFile(context, uri)` — файл из системного пикера (`content://`): имя через `OpenableColumns.DISPLAY_NAME`, байты → base64 `NO_WRAP`.
- `saveToPhoneDownloads` — через `MediaStore.Downloads` (без разрешения на запись). **Работает только на Android 10+** (API 29). На 7–9 вернёт `false` → «не удалось сохранить» (явная проверка версии, без неё было бы падение).

Путь файла: телефон → `SendFileToPc(folder, name, base64)` → релей → агент (подтверждение на ПК) → запись в папку. Обратно: `SendFileToPhone` → агент читает → `FileTransfer` в `CommandResult.data` → телефон сохраняет.

Base64 раздувает данные на ~33%: 5 МБ → ~6.7 МБ, что укладывается в лимит 8 МБ на сообщение у релея.

### 7.12 История задач: `TaskHistory.kt`, `HistoryScreen.kt`

`TaskRecord(id, startedAtMs, request, completed, stepsTaken, summary, trace)`. Хранится в `filesDir/task_history.json` (не DataStore — трейсы большие), последние **50** записей, новые сверху. Протокол не менялся — всё уже приходило в `AgentReport`, просто раньше выбрасывалось.

Экран: карточки сворачиваются/разворачиваются по тапу (свёрнутая — итог, развёрнутая — весь трейс), «очистить» с подтверждением. Сети не касается.

### 7.13 Свои команды: `CustomActions.kt`, `CustomActionsScreen.kt`

`CustomCommandType`: `LOCK`, `VOLUME` (0–100), `OPEN_APP`, `OPEN_URL`, `KILL_PROCESS` и с 1 октября — `RECIPE` («Навык: рецепт по шагам»). Набор **сознательно ограничен** — это уже разрешённые команды, протокол и белый список трогать не нужно. `toCommand()` собирает настоящую команду (громкость ограничивается 0–100, по умолчанию 50). Хранение — `custom_actions.json`, максимум 12 чипов. Чипы появляются в ряду быстрых действий чата.

#### Навык-рецепт: научить агента словами

Человек перечисляет шаги составной задачи обычным языком, каждый с новой строки:

```
запусти скрипт сервер диплома
открой Telegram
напиши в Избранное «сервер поднят»
```

и, по желанию, **фразы запуска** через запятую («доброе утро, начинаем работу»). В записи `CustomActionRecord` шаги лежат в поле `param`, фразы — в новом поле `triggers`.

| Функция | Что делает |
|---|---|
| `recipeSteps()` | Строки без пустых и без нумерации, которую человек мог поставить сам («1.», «2)») |
| `recipeGoal()` | Собирает просьбу агенту: «Навык «утро» — выполни строго по шагам, по порядку:» и **нумерованный список** |
| `toCommand()` | `AgentTask(recipeGoal(), SCREEN, maxSteps = 20)` |
| `matchRecipe(phrase)` | Какой рецепт просят фразой: «навык утро», «выполни навык утро», «запусти навык утро» или одна из фраз запуска |

Три решения, которые стоит уметь объяснить:

1. **Новой команды протокола нет.** Рецепт — обычная `agent_task`. Признак рецепта для планировщика на ПК — именно нумерованный список в тексте цели (правило «РЕЦЕПТ» в его промпте, файл 4): «не пропускай шаги и не добавляй своих».
2. **Совпадение фразы — только точное** (после нормализации: регистр, ё→е, знаки препинания, обращение «Фауст»). Навык делает действия на ПК, «похоже на» тут не годится.
3. **Разбирает телефон, а не релей.** Шаги рецепта хранятся только на телефоне, поэтому `ChatViewModel.send` проверяет `matchRecipe` раньше, чем отправить фразу на разбор.

На экране «Свои команды» у рецепта многострочное поле шагов и поле «Фразы запуска через запятую»; в карточке показывается число шагов и фразы.

Не путать с двумя другими видами навыков, которые живут **на ПК** (файл 4): доверенные скрипты и навыки, выученные показом. Фразы «навык …» и «запусти скрипт …», не совпавшие с рецептом на телефоне, уходят на релей, и он без модели отдаёт их агенту.

### 7.14 Мульти-ПК: `MultiPcViewModel.kt`, `MultiPcScreen.kt`

Отметить несколько ПК и отправить одну быструю команду всем (блокировка, громкость, открыть URL). Для каждого ПК — **своё короткое соединение** `RelayPairing.sendCommand` параллельно (`async/awaitAll`). Зависший или офлайн ПК не блокирует остальные; у каждой строки свой статус `IDLE → SENDING → OK/ERROR` и текст.

### 7.15 `Theme.kt`

Тёплая тёмная тема по макету (`docs/Jarvis Mobile App.pdf`): мальва/пыльная роза — действия пользователя, персиковый крем — ответы ассистента, почти чёрный тёплый фон. Все `surfaceContainer*` заданы явно — иначе Material3 рисовал бы холодные серые плашки. `onlineColor()` — зелёный для статуса. Та же палитра повторена в окнах агента (`JarvisSwingTheme`).

## 8. Сквозные сценарии

### A. Первый запуск и привязка

1. `MainActivity`: DataStore прочитан, список пуст → `PairingScreen`.
2. «Сканировать QR» → `rememberQrScanLauncher` → разрешение камеры → zxing.
3. Сырой текст QR → `PairingViewModel.pairByQr` → `PairingController` → `PairingQr.parse` → `RelayPairing.pair(relay, code)`.
4. Сокет: `pair_request` → `pair_result{ok, deviceId, deviceToken, deviceName}` → закрыть.
5. `DeviceRepository.addAndActivate(PairedDevice(..., mac))` → DataStore изменился.
6. `MainActivity` видит непустой список → показывает `ChatScreen` → `ChatViewModel.init` → `connect()` → `JarvisClient` шлёт `auth` → «подключено ✓».

### B. Голосовая команда через «Фауст»

1. Сервис: Vosk слышит «фауст сделай потише» → `WakeWordBus.emitPhrase("сделай потише")`.
2. `ChatViewModel` (подписан на `phrases`) → `send("сделай потише")` → сообщение `ME/SENT` → `CommandRequest(NaturalLanguage)`.
3. Через 0.6 с — `RUNNING` («выполняется… 1 с»).
4. Приходит `CommandResult(OK, "громкость выставлена на 30%")` → `DONE` + сообщение `PC` → `LaunchedEffect` озвучивает.

### C. ИИ-задача со свёрнутым приложением

1. `send("найди на ютубе котов и открой первое")`.
2. Сворачиваешь → `ON_STOP` → `onAppBackgrounded` → есть ожидающая команда → `taskKeepAlive(true)` → сервис с уведомлением «Держим связь с ПК…».
3. Приходят `AgentStep` → карточки в чате (их увидишь, вернувшись).
4. Приходит `ScreenFrame` (финальный кадр) и `CommandResult` с `AgentReport` → история + `notifyAgentTaskFinished` → уведомление → keep-alive отпущен.

### D. Тап по живому экрану

1. Меню → «Экран ПК» → `acquireStream(FULLSCREEN, 800)` → `StartScreenStream(3, 800)`.
2. Агент шлёт `ScreenFrame(seq=0,1,2…)` → `liveFrame` → `LiveFrameImage` декодирует в фоне.
3. Тап в точку (200, 150) на картинке шириной 400 px, ПК 1920×1080 → `Click(960, 540)`.
4. Назад → `releaseStream` → если окошко в чате выключено → `StopScreenStream`.

### E. ПК отвязан на релее

1. `auth` → `auth_result{ok:false}` → `JarvisClient` ставит `authRejected`, не переподключается.
2. `ChatViewModel.handle` → `unauthorized = true`, статус «ПК «X» отвязан».
3. Плашка красная, кнопка «Привязать заново» → сканер → `repairByQr` → новый токен под тем же id → `init` видит изменение `PairedDevice` → `connect()`.

### F. Многоэтапная задача с вопросом

1. `send("скачай архив по ссылке … и отправь его в Telegram")` → реплика `ME`, `requestId = r1`.
2. Приходит `AgentPlan(r1, [Скачать архив ●, Открыть Telegram ○, Отправить файл ○])` → карточка «План из 3 шагов», TTS её озвучивает.
3. Приходит `AgentQuestion(r1, questionId = q7, "Отправить Hello-World.zip в Избранное?", ["Да","Нет"], 120)` → карточка с кнопками и отсчётом; TTS: «…Скажите „да“ или „нет“» → микрофон включается сам.
4. Человек говорит «да» → `onSpeechText` → `submit` → `vm.send("да")` → есть `openQuestion` → `answerQuestion` → карточка `SENDING`, реплика «да», команда `AnswerQuestion(q7, "да")` с новым `requestId = r2`.
5. `CommandResult(r2, OK, "ответ передан агенту")` → `answerRequests` знает, что `r2` — это ответ → карточка `ANSWERED`, пузыря нет.
6. Идут `AgentStep(r1, …)` — карточки шагов со сквозной нумерацией; `AgentPlan(r1, …)` обновляет ту же карточку плана: ✓ ✓ ●.
7. `ScreenFrame` + `CommandResult(r1, OK, "цель достигнута за 8 шаг(ов), 43 с, подзадач: 3, вопросов: 1\nскачан файл …", AgentReport)` → статус `DONE`, запись в историю.

### G. Навык-рецепт по фразе

1. В «Своих командах» создан рецепт «утро» с фразой запуска «доброе утро».
2. Vosk слышит «фауст доброе утро» → `WakeWordBus` → `send("доброе утро")`.
3. Вопроса нет; `matchRecipe("доброе утро")` находит рецепт → `sendTracked("доброе утро", AgentTask("Навык «утро» — выполни строго по шагам…\n1. …\n2. …", SCREEN, 20))`.
4. Релей фразу **не разбирает** — команда уже конкретная; он проверяет белый список и запрет опасных целей и пересылает агенту. Дальше — как в сценарии F: план (ровно из шагов рецепта), подтверждение перед скриптом, шаги, итог.

## 9. Потоки и корутины

| Что | Где выполняется |
|---|---|
| Composable, `LaunchedEffect` | Главный поток (UI) |
| Колбэки OkHttp (`onMessage` и т.п.) | Потоки OkHttp → пишут в `MutableStateFlow` (потокобезопасно) |
| Таймер переподключения | `Handler(Looper.getMainLooper())` — главный поток |
| DataStore | Сам уходит в фон; вызывается из `viewModelScope.launch` |
| JSON-файлы истории/кнопок | `withContext(Dispatchers.IO)` |
| Декодирование кадров | `Dispatchers.Default` |
| Wake-on-LAN | `Dispatchers.IO` |
| Vosk | Свой поток записи; колбэки — на главном |
| Сервис | `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` |

`@Synchronized` на `acquireStream/releaseStream/restartStreamIfSilent` — их зовут из разных мест (экран, таймер), и множество владельцев не должно испортиться.

## 10. Где приложение хранит данные

| Данные | Хранилище | Файл |
|---|---|---|
| Привязанные ПК (+ токены!) и активный | Preferences DataStore | `devices.preferences_pb` |
| Последний адрес релея, wake-word вкл/выкл, «рентген» и панель плана на ПК | Preferences DataStore | `settings.preferences_pb` |
| История ИИ-задач (50) | JSON | `files/task_history.json` |
| Свои кнопки и навыки-рецепты (12 всего) | JSON | `files/custom_actions.json` |

Вопросы и планы **нигде не хранятся** — они живут в списке сообщений чата в памяти и пропадают вместе с ним при закрытии приложения. В историю попадает только итог задачи (`AgentReport`).
| Модель Vosk | Распаковывается из assets | внутренняя папка `model` |
| Скачанные с ПК файлы | MediaStore | Публичные «Загрузки» |

Токены хранятся в DataStore **открытым текстом** во внутренней памяти приложения (другие приложения туда не имеют доступа без root). Улучшение — `EncryptedSharedPreferences`/Android Keystore.

«Очистить данные» приложения = забыть все ПК → снова экран привязки.

## 11. Отладка и типичные проблемы

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| «нет связи: failed to connect» | Неверный IP; адрес Radmin/WSL вместо Wi-Fi; брандмауэр; изоляция клиентов в вузовском Wi-Fi | `ipconfig` на ПК → адрес физического адаптера; правило для порта 8080; Tailscale |
| «ПК офлайн» | Агент не запущен / не подключён к релею | Трей агента: цвет значка; `agent.log` |
| «ПК отвязан» | Токен удалён/не совпадает на релее (пересоздали базу) | «Привязать заново» по QR |
| Тапы по экрану ПК мимо | Старый агент без `screenWidth` или релей в Docker со старой сборкой выбрасывает поле | Пересобрать релей `docker compose up -d --build`, перезапустить агента |
| Живой экран застыл | Стрим оборвался | Сторож перезапустит через ≤12 с; или выключить/включить окошко |
| Голос: «нет распознавания речи» | Нет Google-сервиса распознавания / нет `<queries>` | Проверить Google app; манифест |
| Wake-word не слушает | Нет разрешения на микрофон; сервис не стартовал из фона | Включить в настройках, пока приложение открыто |
| Нет уведомления «задача готова» | Не выдано `POST_NOTIFICATIONS` (Android 13+) | Настройки приложения → уведомления |
| «не удалось сохранить» файл | Android ниже 10 | Ограничение `MediaStore.Downloads` |
| «вопрос уже неактуален» | Ответили позже, чем агент ждал (по умолчанию 120 с), или задача уже кончилась | Отправить команду заново; увеличить `ask.timeoutSec` в `agent.properties` на ПК |
| Напечатал новую команду, а она ушла «ответом» | В ленте остался открытый вопрос агента | Сначала ответить на вопрос или дождаться, пока карточка погаснет |
| Нет карточек плана и вопросов, хотя агент их шлёт | Релей собран до 30.09 и не знает типов `agent_plan` / `agent_question`; либо на телефоне старая сборка приложения | Пересобрать релей (`start-faust.bat start`), поставить приложение (`start-faust.bat phone`) |
| Микрофон не включается после озвученного вопроса | Озвучка выключена или нет разрешения на микрофон | Включить «Озвучивать ответы», выдать разрешение |
| Переключатель «Рентген» не действует | ПК офлайн в момент переключения | Значение уйдёт само при следующем подключении (`syncDisplayOptions`) |

Логи: `adb logcat -s JarvisClient RelayPairing VoiceController VoskWakeWord`.

## 12. Известные ограничения и вопросы на защите

**Честные ограничения:**
1. `ws://` без шифрования + `usesCleartextTraffic` — трафик и токены видны в сети. Решение: TLS (`wss://`) на релее, обратный прокси (Caddy/nginx) или Tailscale (шифрует сам).
2. Токены в DataStore без шифрования.
3. Статус «подключено» определяется поиском «✓» в строке — хрупко; лучше отдельный `enum ConnectionState`.
4. Эвристика `RUNNING` через 0.6 с — это не подтверждение от сервера, а догадка.
5. `SetVolume` — абсолютный уровень: «убавь на 20%» поставит ровно 20%.
6. Нет unit-тестов для ViewModel (только тесты протокола).
7. Нет DI — зависимости создаются внутри ViewModel; тестировать с подменами сложнее.
8. Скачивание файлов не работает на Android < 10 при `minSdk 24`.
9. APK ~96 МБ из-за модели Vosk и нативных библиотек под 7 ABI (`abiFilters` сознательно не включён — сломал бы x86-эмулятор).
10. `SpeechRecognizer` (кнопка 🎤) обычно требует интернет, в отличие от Vosk.
11. Открытый вопрос «перехватывает» поле ввода: пока он не закрыт, новую команду отправить нельзя — только ответ. Это сознательный выбор (иначе «Маше» ушло бы на разбор как команда), но человеку это надо знать.
12. Рецепты хранятся только на этом телефоне: на втором телефоне того же ПК их нет, и фраза «навык утро» оттуда уйдёт на ПК как просьба о навыке, выученном показом.
13. Вопросы и планы не переживают перезапуск приложения — истории диалога с агентом нет.
14. Голосовой диалог (автовключение микрофона после вопроса) написан 30 сентября; на реальном телефоне на тот момент проверен не был — перед защитой прогнать вживую.

**Вероятные вопросы (новые):**

- *Почему ответ на вопрос — команда, а не отдельное сообщение?* — чтобы он шёл через белый список и аудит, как всё остальное с телефона. Сама по себе команда безвредна: агент примет её, только если ждёт ответа именно с этим случайным `questionId`.
- *Что будет, если ответить поздно?* — карточка гаснет по своему таймеру одновременно с ожиданием агента; опоздавший ответ в сеть не уходит, человеку говорится почему.
- *Зачем план приходит целиком, а не изменениями?* — телефону не надо собирать состояние из кусочков и бояться потерянного сообщения: последняя версия и есть правда.
- *Почему рецепт разбирает телефон, а не релей?* — шаги знает только телефон; на релей уходит уже готовая задача, и он проверяет её как любую другую.

**Вероятные вопросы:**

- *Почему OkHttp, а не Ktor?* — Ktor был в плане (KMP), но для Android-клиента OkHttp проще и стабильнее; протокол всё равно общий через `:shared`.
- *Зачем `:shared`, если релей на Java?* — Kotlin-модуль — эталон контракта с тестами; на Java он повторён вручную. Единая библиотека для всех трёх — хорошее направление развития.
- *Как телефон узнаёт, к какому запросу ответ?* — `requestId` (UUID), общий у запроса, шагов, кадров и результата.
- *Что будет, если сеть пропадёт?* — экспоненциальный реконнект 1…30 с; при отказе токена — без повторов, с предложением перепривязать.
- *Почему тап попадает точно при зуме?* — тап ловится после `graphicsLayer`, Compose инвертирует матрицу; пересчёт по реальному разрешению из `screenWidth`.
- *Почему wake-word не нарушает приватность?* — Vosk офлайн, в сеть уходит только текст после слова «Фауст».
- *Почему сервис не держит сокет сам?* — проще: соединение одно, в ViewModel; сервис лишь повышает приоритет процесса.

## 13. Как расширять

### Добавить новую команду (например, `mute_toggle`)

1. `shared/.../Command.kt`: `@Serializable @SerialName("mute_toggle") data object MuteToggle : Command { override val name get() = "mute_toggle" }`.
2. Тест в `ProtocolTest.kt` (round-trip).
3. **Релей:** запись в `@JsonSubTypes` и `record` в `Command.java`; имя в `CommandWhitelist`; при желании пример в промпт `GeminiIntentParser`.
4. **Агент:** тот же `record` в своём `Command.java`; ветка в `CommandExecutor.execute`.
5. Телефон: кнопка `QuickAction("🔈", "вкл/выкл звук") { it.sendCommand(MuteToggle) }`.
6. Доставить: релей `docker compose up -d --build`, агент — перезапуск, телефон — `installDebug`.

### Добавить экран

1. Значение в `AppScreen`.
2. Composable `XxxScreen(onBack)`; если нужна связь с ПК — `vm: ChatViewModel = viewModel()`.
3. Ветка в `when (current)` в `MainActivity` и пункт `DrawerLink` в `ChatDrawer`.

## 14. Шпаргалка команд

```powershell
# тесты протокола (без Android SDK)
.\gradlew :shared:jvmTest

# собрать и поставить на телефон по USB
.\gradlew :androidApp:installDebug
adb devices

# логи приложения
adb logcat -s JarvisClient RelayPairing VoskWakeWord VoiceController

# узнать адрес ПК для телефона
ipconfig        # адрес Wi-Fi/Ethernet, не Radmin (26.x) и не vEthernet (172.x)
```

### Итоговая карта «кто за что отвечает»

| Файл | Одной строкой |
|---|---|
| `MainActivity.kt` | Навигация + весь экран чата + диалоги + карточки вопроса и плана + голосовой диалог |
| `ChatViewModel.kt` | Состояние чата, отправка, разбор входящих, стрим, фон, ответы на вопросы, планы, рецепты, показ на ПК |
| `JarvisClient.kt` | Постоянный WebSocket, вход, реконнект |
| `RelayPairing.kt` | Одноразовые сокеты: привязка, статус, команда |
| `PairingController.kt` | Общая логика «код → токен → сохранить» |
| `PairingQr.kt` / `QrScan.kt` | Разбор QR / запуск сканера |
| `PairingScreen.kt` / `PairingViewModel.kt` | Первый запуск |
| `DeviceRepository.kt` / `SettingsRepository.kt` | DataStore: ПК и настройки |
| `DevicesScreen.kt` / `DevicesViewModel.kt` | Список ПК, статусы, WoL |
| `WakeOnLan.kt` | Magic packet |
| `ScreenViewScreen.kt` | Полноэкранный живой экран с тапами |
| `TaskPreviewWindow.kt` | Окошко живого экрана в чате |
| `FrameDecoding.kt` | Декодирование кадров в фоне |
| `VoiceController.kt` / `ListeningOrb.kt` | Речь ↔ текст, анимация |
| `VoskWakeWord.kt` / `WakeWordBus.kt` | Слово «Фауст» |
| `JarvisConnectionService.kt` / `Notifications.kt` | Фон и уведомления |
| `FileTransfer.kt` | Чтение/сохранение файлов |
| `TaskHistory.kt` / `HistoryScreen.kt` | История ИИ-задач |
| `CustomActions.kt` / `CustomActionsScreen.kt` | Свои кнопки и навыки-рецепты (шаги + фразы запуска) |
| `MultiPcViewModel.kt` / `MultiPcScreen.kt` | Рассылка на несколько ПК |
| `Theme.kt` | Цвета |
