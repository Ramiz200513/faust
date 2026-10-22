# Шаг: wake-word «Джарвис» на Vosk (вариант без регистрации и без интернета)

> **Этот документ ЗАМЕНЯЕТ `STEP_WAKE_WORD_PROMPTS.md` (вариант на Picovoice
> Porcupine).** Применяй что-то одно. Если Porcupine ещё не применён — просто
> игнорируй тот файл. Если уже применён — в конце есть раздел «Если Porcupine
> уже применён», там сказано, что откатить.

## Общий контекст

Причина смены движка: Picovoice требует регистрации на console.picovoice.ai и
AccessKey, а сайт открывается слишком долго. Vosk — открытый офлайновый движок
(Apache 2.0), **никакой регистрации, никаких ключей, никакой сети**.

### Что от этого стало лучше, а что хуже

| | Porcupine | Vosk |
|---|---|---|
| Регистрация/ключ | нужен AccessKey | не нужно ничего |
| Слово «Джарвис» | английская модель `JARVIS` | русская модель, слышит по-русски |
| Распознавание самой команды | облако Google (`SpeechRecognizer`) | **тоже офлайн** |
| Размер APK | +~5 МБ | **+~55 МБ** (модель 45 МБ + нативные либы) |
| Расход CPU/батареи | очень маленький | заметно больше (крутится полноценный STT) |
| Фраза целиком | «Джарвис» → пауза → команда | **«Джарвис, открой ютуб» одним вдохом** |

Два последних пункта важны. Минус — батарея: Porcupine слушает одно слово
крошечной моделью, Vosk крутит настоящее распознавание речи непрерывно.
Плюс — UX и демонстрация: на защите «Джарвис, открой ютуб» одной фразой, без
паузы посередине, смотрится заметно лучше, и вся цепочка «голос → команда»
работает **в самолётном режиме**, что само по себе хороший тезис для диплома.

### Как это устроено (и почему проще, чем с Porcupine)

С Porcupine было два движка, дерущихся за микрофон: Porcupine слушает слово,
потом отпускает `AudioRecord`, потом `SpeechRecognizer` слушает команду, потом
обратно. С Vosk движок **один**: он распознаёт текст непрерывно, а мы просто
ищем в готовом тексте слово «джарвис». Никакой передачи микрофона между
движками — весь этот кусок кода исчезает.

Обрабатываем два сценария:

1. `«джарвис открой ютуб»` — слово-триггер и команда в одной фразе → всё, что
   после «джарвис», уходит как команда.
2. `«джарвис»` … пауза … `«открой ютуб»` — после триггера взводим окно на 8
   секунд, и следующая распознанная фраза считается командой.

Прочитано актуальное состояние файлов перед написанием диффов:
`JarvisConnectionService.kt` (2156 б), `VoiceController.kt` (9441 б),
`ChatViewModel.kt` (19607 б), `MainActivity.kt` (36053 б), `Notifications.kt` (3212 б),
`AndroidManifest.xml` (2628 б), `build.gradle.kts` (2061 б), `SettingsRepository.kt` (1900 б).

Что из этого повлияло на дизайн:

1. `JarvisConnectionService` **не владеет** WebSocket-соединением — оно живёт в
   `ChatViewModel`/`JarvisClient`. Значит wake-word работает, пока жив процесс
   приложения (свёрнуто — да; смахнули из списка задач — нет). Честное
   ограничение, проговорить на защите.
2. `VoiceController` создаётся через `remember {}` и умирает вместе с экраном —
   для фонового прослушивания не годится. Движок живёт в сервисе.
3. У сервиса сейчас **одна** причина жить (незавершённая команда), и
   `onAppForegrounded()` гасит его безусловно. Так включённый wake-word умирал
   бы при каждом возврате в приложение → сервис переписывается на **две**
   независимые причины.
4. Сервис и `ChatViewModel` — разные объекты в одном процессе; связываем
   синглтоном-шиной `WakeWordBus`, а не bound-сервисом.
5. Vosk всё-таки держит `AudioRecord`, поэтому конфликт с ручной кнопкой 🎤
   (которая использует системный `SpeechRecognizer`) остаётся — решается флагом
   `WakeWordBus.suspended`.
6. Android 14+ требует `FOREGROUND_SERVICE_MICROPHONE` и
   `foregroundServiceType="microphone"`; объявлять тип microphone без выданного
   `RECORD_AUDIO` нельзя — `SecurityException`, поэтому тип выбирается в рантайме.

Правки только в `androidApp`. **Протокол, relay и агент не трогаются**, новых
команд нет, whitelist менять не нужно — распознанная фраза уходит тем же путём,
что и набранная руками (`send()` → `NaturalLanguage`).

Новых файлов: 2 (`WakeWordBus.kt`, `VoskWakeWord.kt`).
Изменяемых: 7 (`build.gradle.kts`, `AndroidManifest.xml`, `Notifications.kt`,
`JarvisConnectionService.kt`, `SettingsRepository.kt`, `ChatViewModel.kt`,
`MainActivity.kt`).

---

## Промпт 0 — модель в assets (ручной шаг, без кода)

Это надо сделать руками один раз, **до** сборки.

1. Скачай `https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip`
   (45 МБ, Apache 2.0 — лицензия позволяет класть в диплом и в репозиторий).
2. Распакуй. Внутри архива папка `vosk-model-small-ru-0.22` с подпапками
   `am/`, `conf/`, `graph/`, `ivector/` и файлом `README`.
3. Создай в проекте каталог `androidApp/src/main/assets/model-ru/` и положи
   **содержимое** папки из архива прямо туда (не саму папку). Должно получиться:

```
androidApp/src/main/assets/model-ru/am/...
androidApp/src/main/assets/model-ru/conf/...
androidApp/src/main/assets/model-ru/graph/...
androidApp/src/main/assets/model-ru/ivector/...
androidApp/src/main/assets/model-ru/README
androidApp/src/main/assets/model-ru/uuid      <- см. п.4
```

4. Создай текстовый файл `androidApp/src/main/assets/model-ru/uuid`
   с единственной строкой:

```
vosk-small-ru-0.22
```

   Зачем: `StorageService.unpack()` при первом запуске копирует модель из assets
   во внутреннее хранилище приложения (нативный код Vosk не умеет читать прямо
   из APK). Файл `uuid` — маркер версии: если он совпал с уже скопированным,
   Vosk пропускает копирование 45 МБ. Поменяешь модель — поменяй и эту строку,
   иначе приложение продолжит пользоваться старой.

> В официальном демо Vosk эту строку генерирует gradle-задача случайным UUID —
> нам так не надо: случайный UUID означает перекопирование 45 МБ при каждой
> сборке. Фиксированная строка лучше.

---

## Промпт 1 — `androidApp/build.gradle.kts`

Найди в `dependencies { ... }`:

```kotlin
    // NotificationCompat/NotificationChannel — уведомление о завершении задачи агента
    implementation("androidx.core:core-ktx:1.13.1")
```

Добавь сразу после:

```kotlin
    // Wake-word + офлайн-распознавание речи (Vosk, Apache 2.0).
    // Ни ключей, ни регистрации, ни сети. Суффикс @aar обязателен для обеих
    // зависимостей: обе поставляются только как AAR с нативными .so внутри.
    // JNA — то, через что Java-обёртка Vosk вызывает нативную библиотеку.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
```

**Опционально — урезать APK.** Vosk кладёт нативные библиотеки под четыре
архитектуры (arm64-v8a, armeabi-v7a, x86, x86_64). Реальному телефону нужна одна.
Если размер APK жмёт, добавь в `defaultConfig { ... }`:

```kotlin
        // Только 64-битный ARM — все телефоны последних лет. Убирает ~10 МБ
        // нативных библиотек. ВНИМАНИЕ: с этим фильтром приложение перестанет
        // ставиться на x86-эмулятор — для эмулятора добавь "x86_64".
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
```

---

## Промпт 2 — `androidApp/src/main/AndroidManifest.xml`

### 2.1. Разрешение

Найди:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Замени на:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <!-- Android 14+: foreground-сервис, который пишет звук (wake-word), обязан
         объявить тип microphone и иметь это разрешение. Обычное, не runtime. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### 2.2. Тип сервиса

Найди:

```xml
        <service
            android:name=".JarvisConnectionService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
```

Замени на:

```xml
        <!-- Два типа через "|": dataSync — когда сервис просто держит процесс
             живым до конца agent_task; microphone — когда включён wake-word.
             Конкретный тип выбирается в рантайме в startForeground(...), потому
             что объявлять microphone без выданного RECORD_AUDIO нельзя. -->
        <service
            android:name=".JarvisConnectionService"
            android:foregroundServiceType="dataSync|microphone"
            android:exported="false" />
```

---

## Промпт 3 — новый файл `WakeWordBus.kt`

Создай `androidApp/src/main/kotlin/com/jarvis/app/WakeWordBus.kt`:

```kotlin
package com.jarvis.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что сейчас делает wake-word. Пишет сервис, читает UI. */
enum class WakeState {
    OFF,        // выключен
    LOADING,    // первая распаковка модели из assets (45 МБ, только один раз)
    WAITING,    // ждём слово «Джарвис»
    RECORDING,  // услышали триггер, ждём команду отдельной фразой
    ERROR,      // не смог стартовать (нет модели / микрофон занят)
}

/**
 * Мостик между [JarvisConnectionService] (слушает микрофон в фоне) и
 * [ChatViewModel] (владеет WebSocket-соединением и отправляет команды).
 *
 * Почему синглтон-object, а не bound-сервис: оба живут в ОДНОМ процессе, и
 * связать их нужно ровно двумя событиями — «распознал фразу» и «случилась
 * ошибка». Полноценный binder тут — лишние 50 строк ради того же результата.
 *
 * SharedFlow (а не StateFlow) для фраз — это события: каждую фразу нужно
 * обработать ровно один раз, а не хранить как «текущее значение».
 * extraBufferCapacity нужен, чтобы tryEmit не потерял событие, если подписчик
 * в этот момент занят.
 */
object WakeWordBus {

    private val _phrases = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Распознанные команды — ChatViewModel шлёт их как обычные сообщения. */
    val phrases: SharedFlow<String> = _phrases.asSharedFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Служебные строки для чата («модель не загрузилась» и т.п.). */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private val _state = MutableStateFlow(WakeState.OFF)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    /**
     * Временно отключить wake-word, не выключая совсем: пока пользователь сам
     * держит микрофон (кнопка 🎤), Vosk обязан отпустить AudioRecord — иначе
     * системный SpeechRecognizer не получит звук.
     */
    val suspended = MutableStateFlow(false)

    fun emitPhrase(text: String) { _phrases.tryEmit(text) }
    fun emitNote(text: String) { _notes.tryEmit(text) }
    fun setState(state: WakeState) { _state.value = state }
}
```

---

## Промпт 4 — новый файл `VoskWakeWord.kt`

Создай `androidApp/src/main/kotlin/com/jarvis/app/VoskWakeWord.kt`:

```kotlin
package com.jarvis.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Wake-word и распознавание команды на одном офлайновом движке Vosk.
 *
 * Идея простая: Vosk непрерывно превращает звук в текст, а мы ищем в этом
 * тексте слово «джарвис». Отдельного движка слова-триггера нет — и вместе с
 * ним нет всей возни с передачей микрофона от одного движка другому.
 *
 * Два поддержанных сценария:
 *  1) «джарвис открой ютуб» — одной фразой; всё после триггера = команда.
 *  2) «джарвис» … пауза … «открой ютуб» — после триггера взводим окно на
 *     [ARMED_WINDOW_MS], и следующая фраза считается командой.
 *
 * Класс ничего не знает ни про сервис, ни про UI — только колбэки.
 * Все колбэки Vosk приходят на главном потоке, дополнительной синхронизации
 * не требуется.
 */
class VoskWakeWord(
    private val context: Context,
    private val onPhrase: (String) -> Unit,
    private val onStateChange: (WakeState) -> Unit,
    private val onNote: (String) -> Unit,
) : RecognitionListener {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    /** Хотим ли слушать. Нужен, потому что распаковка модели асинхронная:
     *  пользователь может выключить wake-word, пока она ещё распаковывается. */
    private var wantRunning = false

    /** До какого момента следующая фраза считается командой (сценарий 2). */
    private var armedUntilMs = 0L

    // -----------------------------------------------------------------

    fun start() {
        wantRunning = true
        val ready = model
        if (ready != null) {
            launchService(ready)
            return
        }
        // Первый запуск: копируем модель из assets во внутреннее хранилище —
        // нативный код Vosk не умеет читать файлы прямо из APK. Занимает
        // несколько секунд и делается ровно один раз (маркер — файл uuid).
        onStateChange(WakeState.LOADING)
        StorageService.unpack(
            context, ASSET_DIR, TARGET_DIR,
            { loaded ->
                model = loaded
                if (wantRunning) launchService(loaded)
            },
            { e ->
                Log.w(TAG, "модель не распаковалась", e)
                onNote("не удалось подготовить модель распознавания: ${e.message}")
                onStateChange(WakeState.ERROR)
            },
        )
    }

    private fun launchService(m: Model) {
        if (speech != null) return // уже слушаем
        try {
            val rec = Recognizer(m, SAMPLE_RATE).also { recognizer = it }
            speech = SpeechService(rec, SAMPLE_RATE).also { it.startListening(this) }
            onStateChange(WakeState.WAITING)
        } catch (e: Exception) {
            Log.w(TAG, "не удалось запустить SpeechService", e)
            onNote("не удалось занять микрофон: ${e.message}")
            releaseService()
            onStateChange(WakeState.ERROR)
        }
    }

    /** Отпустить микрофон, но сохранить загруженную модель (быстрый рестарт). */
    fun stop() {
        wantRunning = false
        armedUntilMs = 0L
        releaseService()
        onStateChange(WakeState.OFF)
    }

    private fun releaseService() {
        speech?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        speech = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }

    /** Полностью освободить всё, включая модель. Обязательно в Service.onDestroy(). */
    fun release() {
        stop()
        model?.let { runCatching { it.close() } }
        model = null
    }

    // -----------------------------------------------------------------
    //  RecognitionListener — колбэки Vosk
    // -----------------------------------------------------------------

    /** Промежуточная гипотеза: используем только чтобы раньше зажечь индикатор. */
    override fun onPartialResult(hypothesis: String?) {
        val text = normalize(field(hypothesis, "partial"))
        if (text.isNotBlank() && triggerEnd(text) >= 0) onStateChange(WakeState.RECORDING)
    }

    /** Финал фразы (после паузы) — основной путь. */
    override fun onResult(hypothesis: String?) = handle(field(hypothesis, "text"))

    /** Финал при остановке потока — обрабатываем так же. */
    override fun onFinalResult(hypothesis: String?) = handle(field(hypothesis, "text"))

    override fun onError(exception: Exception?) {
        Log.w(TAG, "ошибка Vosk", exception)
        onNote("wake-word: ошибка распознавания")
        onStateChange(WakeState.ERROR)
    }

    override fun onTimeout() { /* таймаут SpeechService не используем */ }

    // -----------------------------------------------------------------

    private fun handle(raw: String) {
        val text = normalize(raw)
        if (text.isBlank()) return

        val end = triggerEnd(text)
        val now = System.currentTimeMillis()

        when {
            // Сценарий 1: услышали триггер в этой же фразе.
            end >= 0 -> {
                val tail = text.substring(end).trim(' ', ',', '.', '!', '?')
                if (tail.isNotEmpty()) {
                    armedUntilMs = 0L
                    onStateChange(WakeState.WAITING)
                    onPhrase(tail)
                } else {
                    // Сказали только «Джарвис» — ждём команду следующей фразой.
                    armedUntilMs = now + ARMED_WINDOW_MS
                    onStateChange(WakeState.RECORDING)
                }
            }
            // Сценарий 2: триггер был в предыдущей фразе и окно ещё не истекло.
            now < armedUntilMs -> {
                armedUntilMs = 0L
                onStateChange(WakeState.WAITING)
                onPhrase(text)
            }
            // Обычная посторонняя речь — игнорируем. Именно здесь проходит
            // граница приватности: всё, что не после «Джарвис», никуда не уходит.
            else -> onStateChange(WakeState.WAITING)
        }
    }

    /** Достать поле из JSON-ответа Vosk ({"text":"…"} / {"partial":"…"}). */
    private fun field(hypothesis: String?, name: String): String =
        hypothesis?.let { runCatching { JSONObject(it).optString(name) }.getOrNull() }.orEmpty()

    /** Нижний регистр, ё→е, долой пунктуацию и двойные пробелы. */
    private fun normalize(text: String): String = text
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * @return индекс первого символа ПОСЛЕ слова-триггера, или -1.
     *
     * Вариантов написания несколько, потому что маленькая модель вполне может
     * услышать «джарвиз» или «жарвис» — заставлять пользователя выговаривать
     * ровно один вариант было бы жестоко.
     */
    private fun triggerEnd(text: String): Int {
        for (w in TRIGGERS) {
            val i = text.indexOf(w)
            if (i >= 0) return i + w.length
        }
        return -1
    }

    private companion object {
        const val TAG = "VoskWakeWord"
        const val SAMPLE_RATE = 16000.0f
        /** Папка модели в assets (см. Промпт 0). */
        const val ASSET_DIR = "model-ru"
        /** Куда Vosk распакует её внутри приложения. */
        const val TARGET_DIR = "model"
        /** Сколько ждать команду после одинокого «Джарвис». */
        const val ARMED_WINDOW_MS = 8_000L

        val TRIGGERS = listOf(
            "джарвис", "джарвиз", "джарвес",
            "жарвис", "жарвиз",
            "джарис", "джавис",
        )
    }
}
```

---

## Промпт 5 — `Notifications.kt`: текст постоянного уведомления стал изменяемым

Замени:

```kotlin
/** Обязательное постоянное уведомление foreground-сервиса (без него startForeground упадёт). */
fun buildOngoingNotification(context: Context): Notification {
    ensureChannels(context)
    return NotificationCompat.Builder(context, CHANNEL_ONGOING)
        .setContentTitle("Jarvis")
        .setContentText("Держим связь с ПК…")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}
```

на:

```kotlin
/**
 * Обязательное постоянное уведомление foreground-сервиса (без него
 * startForeground упадёт). Текст теперь параметр, потому что один и тот же
 * сервис показывает разные состояния: «держим связь», «слушаю Джарвис»,
 * «готовлю модель».
 */
fun buildOngoingNotification(
    context: Context,
    text: String = "Держим связь с ПК…",
): Notification {
    ensureChannels(context)
    return NotificationCompat.Builder(context, CHANNEL_ONGOING)
        .setContentTitle("Jarvis")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

/**
 * Обновить текст уже показанного постоянного уведомления, не перезапуская
 * сервис: notify() с тем же id просто заменяет содержимое.
 */
fun updateOngoingNotification(context: Context, notificationId: Int, text: String) {
    context.getSystemService(NotificationManager::class.java)
        ?.notify(notificationId, buildOngoingNotification(context, text))
}
```

---

## Промпт 6 — `JarvisConnectionService.kt`: полная замена файла

Замени содержимое файла целиком на:

```kotlin
package com.jarvis.app

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Foreground-сервис с ДВУМЯ независимыми причинами жить:
 *
 *  1. "keep-alive задачи" — приложение свёрнуто, а agent_task ещё выполняется.
 *     Сервис НЕ владеет WebSocket (это по-прежнему ChatViewModel/JarvisClient),
 *     он только не даёт системе убить процесс. Как и раньше.
 *  2. "wake-word" — крутим офлайновый Vosk, ищем в распознанном тексте слово
 *     «Джарвис» и отдаём команду в ChatViewModel через [WakeWordBus].
 *
 * Сервис живёт, пока истинна хотя бы одна причина, и сам себя останавливает,
 * когда обе стали ложными. Причины переключаются экшенами интента — см.
 * companion object.
 */
class JarvisConnectionService : Service() {

    private var taskKeepAlive = false
    private var wakeWordOn = false
    private var engine: VoskWakeWord? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Пользователь нажал 🎤 в приложении -> Vosk обязан отпустить микрофон,
        // иначе системный SpeechRecognizer не получит звук. Отпустил -> вернуть.
        // drop(1) — пропускаем текущее значение при подписке, реагируем только
        // на реальные переключения.
        scope.launch {
            WakeWordBus.suspended.drop(1).collect { suspended ->
                if (!wakeWordOn) return@collect
                if (suspended) engine?.stop() else engine?.start()
            }
        }

        // Состояние движка меняется — обновляем текст постоянного уведомления,
        // чтобы пользователь всегда видел, слушает телефон или нет.
        scope.launch {
            WakeWordBus.state.drop(1).collect { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TASK -> taskKeepAlive = intent.getBooleanExtra(EXTRA_ON, false)
            ACTION_WAKE -> wakeWordOn = intent.getBooleanExtra(EXTRA_ON, false)
        }

        // startForeground ОБЯЗАН быть вызван в первые ~5 секунд после
        // startForegroundService, иначе система убьёт процесс с
        // ForegroundServiceDidNotStartInTimeException — поэтому зовём его
        // ВСЕГДА и только потом решаем, не пора ли остановиться.
        goForeground()

        if (wakeWordOn) startWakeWord() else stopWakeWord()

        if (!taskKeepAlive && !wakeWordOn) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        scope.cancel()
        WakeWordBus.setState(WakeState.OFF)
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    //  Foreground + уведомление
    // -----------------------------------------------------------------

    private fun statusText(): String = when {
        !wakeWordOn -> "Держим связь с ПК…"
        else -> when (WakeWordBus.state.value) {
            WakeState.LOADING -> "Готовлю модель распознавания…"
            WakeState.RECORDING -> "Слушаю команду…"
            WakeState.ERROR -> "Wake-word не запустился"
            else -> "Слушаю «Джарвис»…"
        }
    }

    private fun goForeground() {
        val notification = buildOngoingNotification(this, statusText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Тип microphone объявляем ТОЛЬКО когда реально пишем звук и
            // разрешение выдано: на Android 14+ объявление типа microphone без
            // выданного RECORD_AUDIO бросает SecurityException.
            val type = if (wakeWordOn && hasMicPermission())
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() =
        updateOngoingNotification(this, NOTIFICATION_ID, statusText())

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // -----------------------------------------------------------------
    //  Wake-word
    // -----------------------------------------------------------------

    private fun startWakeWord() {
        if (!hasMicPermission()) {
            wakeWordOn = false
            WakeWordBus.setState(WakeState.ERROR)
            WakeWordBus.emitNote("нет доступа к микрофону — wake-word выключен")
            return
        }
        if (engine == null) {
            engine = VoskWakeWord(
                context = applicationContext,
                onPhrase = { WakeWordBus.emitPhrase(it) },
                onStateChange = { WakeWordBus.setState(it) },
                onNote = { WakeWordBus.emitNote(it) },
            )
        }
        if (!WakeWordBus.suspended.value) engine?.start()
    }

    private fun stopWakeWord() {
        engine?.stop()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_TASK = "com.jarvis.app.action.TASK_KEEPALIVE"
        private const val ACTION_WAKE = "com.jarvis.app.action.WAKE_WORD"
        private const val EXTRA_ON = "on"

        /** Держать процесс живым, пока не завершится agent_task (или отпустить). */
        fun taskKeepAlive(context: Context, on: Boolean) =
            send(context, ACTION_TASK, on)

        /** Включить/выключить постоянное прослушивание слова «Джарвис». */
        fun wakeWord(context: Context, on: Boolean) =
            send(context, ACTION_WAKE, on)

        private fun send(context: Context, action: String, on: Boolean) {
            val intent = Intent(context, JarvisConnectionService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ON, on)
            // Android 12+ запрещает поднимать foreground-сервис из фона. Вызов
            // из ON_STOP на части устройств не попадает в разрешённое окно и
            // бросает ForegroundServiceStartNotAllowedException. Для нас это не
            // фатально (соединение живёт в ViewModel), но падать нельзя.
            runCatching { context.startForegroundService(intent) }
        }
    }
}
```

---

## Промпт 7 — `SettingsRepository.kt`: запомнить, включён ли wake-word

### 7.1. Импорты

Найди:

```kotlin
import androidx.datastore.preferences.core.stringPreferencesKey
```

Замени на:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
```

### 7.2. Ключ и методы

Замени:

```kotlin
    private object Keys {
        val RELAY_URL = stringPreferencesKey("relay_url")
    }

    val relayUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.RELAY_URL] ?: DEFAULT_URL }

    suspend fun saveRelayUrl(relayUrl: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RELAY_URL] = relayUrl.trim() }
    }
```

на:

```kotlin
    private object Keys {
        val RELAY_URL = stringPreferencesKey("relay_url")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    }

    val relayUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.RELAY_URL] ?: DEFAULT_URL }

    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.WAKE_WORD_ENABLED] ?: false }

    suspend fun saveRelayUrl(relayUrl: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RELAY_URL] = relayUrl.trim() }
    }

    suspend fun saveWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.WAKE_WORD_ENABLED] = enabled }
    }
```

---

## Промпт 8 — `ChatViewModel.kt`

### 8.1. Репозиторий настроек

Найди:

```kotlin
    private val client = JarvisClient()
    private val devicesRepo = DeviceRepository(app)
    private val pairingController = PairingController(viewModelScope, devicesRepo)
```

Замени на:

```kotlin
    private val client = JarvisClient()
    private val devicesRepo = DeviceRepository(app)
    private val pairingController = PairingController(viewModelScope, devicesRepo)
    private val settings = SettingsRepository(app)
```

### 8.2. Состояние wake-word

Найди:

```kotlin
    // Свёрнуто ли приложение — влияет на то, поднимать ли foreground-сервис и
    // слать ли уведомление о завершении agent_task (см. MainActivity: ON_STOP/ON_START).
    private var isBackgrounded = false
```

Добавь сразу после:

```kotlin
    // Wake-word: включён ли. Значение живёт в DataStore, здесь — зеркало для
    // переключателя в настройках.
    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    /** Что сейчас делает движок слова-триггера — для индикатора в шапке. */
    val wakeState: StateFlow<WakeState> = WakeWordBus.state
```

### 8.3. Подписки в `init`

Найди начало `init`:

```kotlin
    init {
        viewModelScope.launch {
            val activeDeviceFlow = combine(devicesRepo.devices, devicesRepo.activeDeviceId) { devices, id ->
```

Замени на:

```kotlin
    init {
        // Фразы, распознанные фоновым сервисом после слова «Джарвис», уходят
        // ровно тем же путём, что и набранные руками (send -> NaturalLanguage).
        viewModelScope.launch { WakeWordBus.phrases.collect { send(it) } }
        // Служебные сообщения wake-word — просто строкой в чат.
        viewModelScope.launch { WakeWordBus.notes.collect { note(it) } }
        // Восстанавливаем настройку между запусками: если wake-word был включён,
        // поднимаем сервис сразу при старте (приложение в этот момент на
        // переднем плане — значит Android 12+ разрешит старт foreground-сервиса).
        viewModelScope.launch {
            _wakeWordEnabled.value = settings.wakeWordEnabled.first()
            if (_wakeWordEnabled.value) JarvisConnectionService.wakeWord(getApplication(), true)
        }
        viewModelScope.launch {
            val activeDeviceFlow = combine(devicesRepo.devices, devicesRepo.activeDeviceId) { devices, id ->
```

> Остальное тело `init` — без изменений.

### 8.4. Включение/выключение

Найди:

```kotlin
    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
```

Добавь **перед** этой строкой:

```kotlin
    /**
     * Включить/выключить постоянное прослушивание слова «Джарвис».
     * Разрешение RECORD_AUDIO спрашивает UI ДО вызова этого метода (ViewModel
     * не умеет показывать системные диалоги).
     */
    fun setWakeWordEnabled(on: Boolean) {
        _wakeWordEnabled.value = on
        viewModelScope.launch {
            settings.saveWakeWordEnabled(on)
            JarvisConnectionService.wakeWord(getApplication(), on)
            note(
                if (on) "wake-word включён — скажи «Джарвис, открой ютуб»"
                else "wake-word выключен"
            )
        }
    }

```

### 8.5. Сервис больше не выключается целиком

Замени:

```kotlin
    fun onAppBackgrounded() {
        isBackgrounded = true
        if (hasPendingCommands()) JarvisConnectionService.start(getApplication())
    }

    /** Экран вернули на передний план (ON_START) — сервис больше не нужен. */
    fun onAppForegrounded() {
        isBackgrounded = false
        JarvisConnectionService.stop(getApplication())
    }
```

на:

```kotlin
    fun onAppBackgrounded() {
        isBackgrounded = true
        if (hasPendingCommands()) JarvisConnectionService.taskKeepAlive(getApplication(), true)
    }

    /**
     * Экран вернули на передний план (ON_START) — keep-alive задачи больше не
     * нужен. ВАЖНО: именно taskKeepAlive(false), а не полная остановка сервиса —
     * иначе выключился бы и wake-word. Сервис сам себя остановит, если не
     * осталось ни одной причины жить.
     */
    fun onAppForegrounded() {
        isBackgrounded = false
        JarvisConnectionService.taskKeepAlive(getApplication(), false)
    }
```

И в `handle()` замени:

```kotlin
                // Незавершённых команд больше нет — фоновый сервис свою роль выполнил.
                if (isBackgrounded && !hasPendingCommands()) JarvisConnectionService.stop(getApplication())
```

на:

```kotlin
                // Незавершённых команд больше нет — эта причина держать сервис отпала
                // (wake-word, если включён, продолжит жить сам по себе).
                if (isBackgrounded && !hasPendingCommands()) {
                    JarvisConnectionService.taskKeepAlive(getApplication(), false)
                }
```

---

## Промпт 9 — `MainActivity.kt`

### 9.1. Состояние в `ChatScreen`

Найди:

```kotlin
    val repairing by vm.repairing.collectAsState()

    val context = LocalContext.current
```

Замени на:

```kotlin
    val repairing by vm.repairing.collectAsState()
    val wakeWordEnabled by vm.wakeWordEnabled.collectAsState()
    val wakeState by vm.wakeState.collectAsState()

    val context = LocalContext.current
```

### 9.2. Ручной микрофон приостанавливает wake-word

Найди:

```kotlin
    // Диалог запроса разрешения RECORD_AUDIO. Результат прилетает в лямбду.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voice.startListening(onResult = ::onSpeechText, onError = vm::note)
        else vm.note("нет доступа к микрофону — голосовой ввод недоступен")
    }
```

Замени на:

```kotlin
    // Пока пользователь сам держит микрофон, Vosk обязан его отпустить — два
    // потребителя AudioRecord одновременно не работают. Обёртка ставит и
    // снимает флаг WakeWordBus.suspended вокруг ручного распознавания.
    fun startManualListening() {
        WakeWordBus.suspended.value = true
        voice.startListening(
            onResult = { text -> WakeWordBus.suspended.value = false; onSpeechText(text) },
            onError = { msg -> WakeWordBus.suspended.value = false; vm.note(msg) },
        )
    }

    // Диалог запроса разрешения RECORD_AUDIO. Результат прилетает в лямбду.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startManualListening()
        else vm.note("нет доступа к микрофону — голосовой ввод недоступен")
    }

    // Отдельный запрос того же разрешения, но для включения wake-word: у него
    // другая реакция на выдачу (включить прослушивание, а не начать запись).
    val wakeWordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setWakeWordEnabled(true)
        else vm.note("без доступа к микрофону wake-word не работает")
    }

    fun onWakeWordChange(on: Boolean) {
        if (!on) { vm.setWakeWordEnabled(false); return }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.setWakeWordEnabled(true)
        else wakeWordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
```

### 9.3. Кнопка микрофона — через новую обёртку

Найди:

```kotlin
    fun onMicClick() {
        if (isListening) {                 // уже слушаем — второй тап останавливает
            voice.stopListening()
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) voice.startListening(onResult = ::onSpeechText, onError = vm::note)
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
```

Замени на:

```kotlin
    fun onMicClick() {
        if (isListening) {                 // уже слушаем — второй тап останавливает
            voice.stopListening()
            WakeWordBus.suspended.value = false   // вернуть wake-word микрофон
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startManualListening()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
```

### 9.4. Индикатор в шапке

Найди:

```kotlin
                actions = {
                    IconButton(onClick = onOpenDevices) { Text("🖥") }
```

Замени на:

```kotlin
                actions = {
                    // Видимый признак, что телефон слушает: ⏳ — распаковывается
                    // модель, 👂 — ждём «Джарвис», 🎙 — услышали, ждём команду.
                    when (wakeState) {
                        WakeState.LOADING -> Text("⏳", Modifier.padding(end = 4.dp))
                        WakeState.RECORDING -> Text("🎙", Modifier.padding(end = 4.dp))
                        WakeState.WAITING -> Text("👂", Modifier.padding(end = 4.dp))
                        else -> {}
                    }
                    IconButton(onClick = onOpenDevices) { Text("🖥") }
```

### 9.5. Передаём настройку в диалог

Найди:

```kotlin
    if (showSettings) {
        SettingsDialog(
            device = activeDevice,
            onSaveRelayUrl = { vm.updateRelayUrl(it) },
```

Замени на:

```kotlin
    if (showSettings) {
        SettingsDialog(
            device = activeDevice,
            wakeWordEnabled = wakeWordEnabled,
            onWakeWordChange = { on -> onWakeWordChange(on) },
            onSaveRelayUrl = { vm.updateRelayUrl(it) },
```

### 9.6. Сам диалог настроек

Найди весь `fun SettingsDialog(...)` и замени целиком на:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    device: PairedDevice?,
    wakeWordEnabled: Boolean,
    onWakeWordChange: (Boolean) -> Unit,
    onSaveRelayUrl: (String) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(device?.relayUrl) { mutableStateOf(device?.relayUrl.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device?.name ?: "Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("адрес релея (ws://…)") }, singleLine = true,
                )
                Text(
                    "Сменилась сеть? Отсканируй QR заново.",
                    style = MaterialTheme.typography.labelSmall,
                )

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Просыпаться по «Джарвис»", Modifier.weight(1f))
                    Switch(checked = wakeWordEnabled, onCheckedChange = onWakeWordChange)
                }
                Text(
                    "Распознавание полностью на телефоне, без интернета. " +
                        "В сеть уходит только сама команда — то, что сказано после «Джарвис».",
                    style = MaterialTheme.typography.labelSmall,
                )

                HorizontalDivider()

                OutlinedButton(
                    onClick = onUnpair,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Отвязать этот ПК") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSaveRelayUrl(url); onDismiss() }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
```

> `Switch` и `HorizontalDivider` приходят из `androidx.compose.material3.*`,
> который в этом файле уже импортирован звёздочкой — новых импортов не нужно.

---

## Проверка

1. **Сборка.** Gradle должен подтянуть `vosk-android` и `jna`. Если не находит —
   проверь `mavenCentral()` в `settings.gradle.kts`. Если ругается на дубликаты
   нативных библиотек — значит забыт суффикс `@aar` у одной из зависимостей.
2. **Модель на месте.** В `androidApp/src/main/assets/model-ru/` должны лежать
   `am/`, `conf/`, `graph/`, `ivector/` и файл `uuid`. Если модель забыта,
   приложение не упадёт, а покажет в чате «не удалось подготовить модель».
3. **Первый запуск.** ⚙ → переключатель «Просыпаться по «Джарвис»» → системный
   запрос микрофона → в шапке ⏳ на несколько секунд (распаковка 45 МБ), потом
   👂. Второй и последующие запуски — сразу 👂, без ⏳.
4. **Одной фразой.** Скажи «Джарвис, сделай скриншот» слитно → команда должна
   уйти в чат и выполниться на ПК.
5. **Двумя фразами.** Скажи «Джарвис» → индикатор меняется на 🎙 → через пару
   секунд скажи «открой ютуб» → команда уходит. Если промолчать больше 8 секунд,
   индикатор сам возвращается на 👂 и следующая случайная фраза НЕ уходит.
6. **Посторонняя речь.** Поговори рядом 2-3 минуты, не произнося «Джарвис» — в
   чат не должно попасть ничего. Это же и проверка приватности: наружу уходит
   только то, что сказано после триггера.
7. **В самолётном режиме.** Включи авиарежим → скажи «Джарвис, сделай скриншот» →
   фраза всё равно должна распознаться и попасть в поле ввода/чат (отправиться
   она, естественно, не сможет — ПК недоступен). Это доказательство, что
   распознавание офлайновое; хороший кадр для защиты.
8. **В фоне.** Сверни приложение (кнопка «домой», не смахивай из списка задач) →
   скажи «Джарвис, открой ютуб» → команда должна выполниться на ПК.
9. **Конфликт микрофонов.** При включённом wake-word нажми обычную кнопку 🎤 →
   ручное распознавание работает нормально, а после него 👂 возвращается в шапку.
10. **Не сломалось старое.** Запусти agent_task, сверни приложение → уведомление
    о завершении приходит, как раньше. Вернись в приложение → wake-word остался
    включённым, сервис не исчез.
11. **Переживает перезапуск.** Закрой и открой приложение — wake-word включается
    сам, раз был включён.
12. **Точность триггера.** Если срабатывает на похожие слова — убери лишние
    варианты из списка `TRIGGERS` в `VoskWakeWord.kt`. Если наоборот не слышит —
    посмотри в Logcat по тегу `VoskWakeWord`, что именно распознаётся, и добавь
    услышанный вариант в список.
13. **Батарея** (для главы про производительность): Настройки Android → Батарея →
    расход по приложениям. Замерь час с включённым wake-word и час без — разница
    и есть цифра в диплом. Ожидаемо больше, чем было бы у Porcupine.

---

## Известные ограничения (проговорить на защите — это плюс, а не минус)

1. **Нет доступа к аппаратному DSP.** У Siri/Google слово-триггер слушает
   отдельный маломощный чип, всегда включённый и не будящий процессор. Сторонним
   Android-приложениям этот уровень недоступен — отсюда и расход батареи, и
   обязательный foreground-сервис.
2. **Vosk дороже специализированного движка.** Мы крутим полноценное
   распознавание речи ради одного слова. Специализированный wake-word движок
   (Porcupine, openWakeWord) сделал бы это на порядок дешевле по CPU. Осознанный
   размен: мы платим батареей за отсутствие облачной зависимости, регистрации и
   ключей — и получаем офлайновое распознавание самой команды бонусом. Если
   батарея окажется неприемлемой, оптимизация известна: `Recognizer.setGrammar()`
   ограничивает словарь режима ожидания одним словом, и декодирование становится
   заметно дешевле.
3. **Постоянно видимое уведомление и индикатор микрофона.** Android намеренно не
   даёт скрыть, что приложение слушает. Это требование приватности платформы,
   обойти его нельзя и не нужно.
4. **APK тяжелеет примерно на 55 МБ.** 45 МБ модель + нативные библиотеки.
   Частично лечится `abiFilters` (см. Промпт 1) или загрузкой модели с сервера
   при первом запуске вместо упаковки в APK.
5. **Doze mode и прошивки.** При долгом бездействии с выключенным экраном
   система может усыпить процесс. Foreground-сервис сильно снижает риск, но на
   Xiaomi/Huawei/Samsung нужно ещё вручную разрешить автозапуск и снять
   ограничение батареи для приложения.
6. **Процесс должен быть жив.** Wake-word умирает, если смахнуть приложение из
   списка задач. Прямое следствие текущей архитектуры: WebSocket живёт в
   `ChatViewModel`, а не в сервисе. Полностью независимый фоновый режим — это
   отдельный крупный рефакторинг (перенос соединения в сервис + `START_STICKY` +
   `BOOT_COMPLETED`), тема для главы «дальнейшее развитие».
7. **Точность маленькой модели.** `vosk-model-small-ru-0.22` заметно слабее
   облачного распознавания Google на длинных и редких фразах. Для коротких
   команд («открой ютуб», «сделай скриншот», «выключи звук») этого хватает, но
   в главу про оценку качества стоит честно вынести сравнение: та же тестовая
   выборка фраз через Vosk и через системный `SpeechRecognizer` — это готовая
   измеримая часть исследования, а не просто «работает».

---

## Если Porcupine уже применён

Откатывать нужно немного — большая часть кода общая. Отличия:

* `build.gradle.kts` — убрать `implementation("ai.picovoice:porcupine-android:...")`,
  добавить две зависимости из Промпта 1.
* Удалить файл `WakeWordDetector.kt`, создать `VoskWakeWord.kt` (Промпт 4).
* `WakeWordBus.kt` — добавить значение `LOADING` в enum `WakeState`.
* `JarvisConnectionService.kt` — заменить целиком по Промпту 6 (исчезают
  `SpeechRecognizer`, передача микрофона, `EXTRA_ACCESS_KEY`).
* `SettingsRepository.kt` — удалить ключ `PICOVOICE_KEY` и метод
  `savePicovoiceKey`; `WAKE_WORD_ENABLED` остаётся.
* `ChatViewModel.kt` — удалить `_picovoiceKey`/`picovoiceKey`/`savePicovoiceKey`,
  упростить `setWakeWordEnabled` по Промпту 8.4 (проверка ключа больше не нужна).
* `MainActivity.kt` — убрать из `SettingsDialog` поле ввода ключа и параметры
  `picovoiceKey`/`onSavePicovoiceKey` (Промпт 9.5-9.6).
* Файлы `Notifications.kt`, манифест — уже в нужном виде, менять ничего не надо.
