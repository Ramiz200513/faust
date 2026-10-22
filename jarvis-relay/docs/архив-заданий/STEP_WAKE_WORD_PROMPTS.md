# Шаг: wake-word «Джарвис» — постоянное прослушивание слова-триггера

## Общий контекст

Задача: сказать вслух «Джарвис» → телефон просыпается, слушает следующую фразу,
и отправляет её на ПК точно так же, как если бы ты набрал её руками в чате.
Работает и когда приложение свёрнуто.

Перед написанием этого документа прочитано **актуальное** состояние файлов:
`JarvisConnectionService.kt` (2156 б), `VoiceController.kt` (9441 б),
`ChatViewModel.kt` (19607 б), `MainActivity.kt` (36053 б), `Notifications.kt` (3212 б),
`AndroidManifest.xml` (2628 б), `build.gradle.kts` (2061 б), `SettingsRepository.kt` (1900 б).

### Что уже есть и как это влияет на дизайн

1. `JarvisConnectionService` **не владеет** WebSocket-соединением — он только
   поднимает приоритет процесса, пока есть незавершённая команда. Соединение
   живёт в `ChatViewModel` → `JarvisClient`. Значит wake-word может работать,
   пока жив процесс приложения (свёрнуто — да; убито системой/пользователем — нет).
   Это честное ограничение, его нужно проговорить на защите, а не прятать.
2. `VoiceController` создаётся в Compose через `remember {}` и умирает вместе с
   экраном — он **не годится** для фонового прослушивания. Поэтому распознавание
   фразы после слова-триггера делает сам сервис, своим `SpeechRecognizer`.
3. Сервис и `ChatViewModel` — разные объекты в одном процессе, напрямую друг
   друга не видят. Связываем их синглтоном-шиной `WakeWordBus` (`object` с
   `SharedFlow`), а не биндингом сервиса — так проще и меньше кода.
4. Микрофон нельзя держать двумя движками сразу. Porcupine держит `AudioRecord`,
   `SpeechRecognizer` тоже хочет микрофон. Поэтому: сработал триггер → **стоп**
   Porcupine → распознавание фразы → **старт** Porcupine обратно. И то же самое
   при ручном нажатии кнопки микрофона в приложении (флаг `WakeWordBus.suspended`).
5. Начиная с Android 14 foreground-сервис, который пишет звук, обязан объявить
   тип `microphone` и иметь разрешение `FOREGROUND_SERVICE_MICROPHONE`. Сейчас в
   манифесте объявлен только `dataSync`. Тип микрофона нельзя объявлять, если
   `RECORD_AUDIO` не выдано — будет `SecurityException`, поэтому тип выбирается в
   рантайме.

### Движок wake-word

Picovoice Porcupine, **встроенное** ключевое слово `BuiltInKeyword.JARVIS` — то
есть НИКАКИХ `.ppn`-файлов в assets не нужно, только ключ доступа. Русское
«Джарвис» фонетически близко к английскому «Jarvis», встроенная модель на него
срабатывает. Если захочется чистого русского произношения — это отдельный шаг:
кастомное слово + русский `.pv` модельный файл с консоли Picovoice.

**Что нужно сделать руками один раз (без кода):** зайти на
`https://console.picovoice.ai/`, зарегистрироваться (бесплатный tier),
скопировать `AccessKey`. Ключ **не** зашивается в код и не коммитится — он
вводится в настройках приложения и хранится в DataStore (тот же инвариант, что
и с ключом Gemini: секреты не в APK и не в репозитории).

Правки только в `androidApp`. Протокол, relay и агент **не трогаются** — это
чисто клиентская фича, новых команд нет, whitelist менять не нужно.

Новых файлов: 2 (`WakeWordBus.kt`, `WakeWordDetector.kt`).
Изменяемых: 6 (`build.gradle.kts`, `AndroidManifest.xml`, `Notifications.kt`,
`JarvisConnectionService.kt`, `SettingsRepository.kt`, `ChatViewModel.kt`, `MainActivity.kt`).

---

## Промпт 1 — `androidApp/build.gradle.kts`: зависимость Porcupine

Найди блок с `core-ktx` в `dependencies { ... }`:

```kotlin
    // NotificationCompat/NotificationChannel — уведомление о завершении задачи агента
    implementation("androidx.core:core-ktx:1.13.1")
```

Добавь сразу после него:

```kotlin
    // Wake-word «Джарвис»: on-device движок Picovoice Porcupine. Работает офлайн,
    // маленькая модель (не полноценный STT) — поэтому расход батареи приемлемый.
    // Координата прописана явно, как okhttp/datastore/core-ktx выше, а не через
    // libs.versions.toml.
    implementation("ai.picovoice:porcupine-android:3.0.3")
```

> Примечание: на момент написания доступна и версия `4.0.2`, API тот же
> (`PorcupineManager.Builder`, `BuiltInKeyword.JARVIS`). Взята `3.0.3` как более
> обкатанная. Если Gradle не найдёт артефакт — проверь, что в
> `settings.gradle.kts` в `dependencyResolutionManagement` есть `mavenCentral()`.

---

## Промпт 2 — `androidApp/src/main/AndroidManifest.xml`: разрешение и тип сервиса

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

/** Что сейчас делает wake-word. Читает UI, пишет сервис. */
enum class WakeState {
    OFF,        // выключен
    WAITING,    // ждём слово «Джарвис»
    RECORDING,  // услышали — записываем саму команду
    ERROR,      // не смог стартовать (нет ключа / микрофон занят)
}

/**
 * Мостик между [JarvisConnectionService] (слушает микрофон в фоне) и
 * [ChatViewModel] (владеет WebSocket-соединением и отправляет команды).
 *
 * Почему синглтон-object, а не binder сервиса: оба живут в ОДНОМ процессе, и
 * связать их нужно ровно двумя событиями («распознал фразу», «случилась
 * ошибка»). Полноценный bound-сервис здесь — лишние 50 строк на onBind/
 * ServiceConnection ради того же результата.
 *
 * SharedFlow (а не StateFlow) для фраз — это события: каждую фразу нужно
 * обработать ровно один раз, а не хранить как «текущее значение».
 * extraBufferCapacity нужен, чтобы tryEmit не терял событие, если подписчик
 * (ViewModel) в этот момент занят.
 */
object WakeWordBus {

    private val _phrases = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Распознанные после слова-триггера фразы — ChatViewModel шлёт их как обычные команды. */
    val phrases: SharedFlow<String> = _phrases.asSharedFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Служебные сообщения для чата («не расслышал», «нет ключа» и т.п.). */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private val _state = MutableStateFlow(WakeState.OFF)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    /**
     * Временно отключить wake-word, не выключая его совсем: пока пользователь
     * сам держит микрофон (кнопка 🎤 в приложении), Porcupine обязан отпустить
     * AudioRecord — иначе SpeechRecognizer не получит звук.
     */
    val suspended = MutableStateFlow(false)

    fun emitPhrase(text: String) { _phrases.tryEmit(text) }
    fun emitNote(text: String) { _notes.tryEmit(text) }
    fun setState(state: WakeState) { _state.value = state }
}
```

---

## Промпт 4 — новый файл `WakeWordDetector.kt`

Создай `androidApp/src/main/kotlin/com/jarvis/app/WakeWordDetector.kt`:

```kotlin
package com.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.util.Log

/**
 * Тонкая обёртка над Picovoice Porcupine — распознаёт ОДНО слово «Jarvis»
 * полностью на устройстве, без сети.
 *
 * Почему не обычный SpeechRecognizer в цикле: тот гоняет полноценное
 * распознавание речи (часто ещё и через сеть) и сажает батарею за часы.
 * Porcupine — крошечная модель под одно слово, для того и сделана.
 *
 * Класс намеренно НИЧЕГО не знает ни про сервис, ни про UI: он получает
 * колбэки и зовёт их. Все решения (что делать при срабатывании) — в
 * [JarvisConnectionService].
 *
 * @param accessKey ключ с console.picovoice.ai; пользователь вводит его в
 *                  настройках, в коде/репозитории его нет.
 */
class WakeWordDetector(
    private val context: Context,
    private val accessKey: String,
    private val onDetected: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var manager: PorcupineManager? = null
    private var running = false

    /** @return true, если движок реально слушает микрофон. */
    fun start(): Boolean {
        if (accessKey.isBlank()) {
            onError("не задан ключ Picovoice — вставь его в настройках (⚙)")
            return false
        }
        if (running) return true

        val m = manager ?: try {
            PorcupineManager.Builder()
                .setAccessKey(accessKey)
                // Встроенное слово — никаких .ppn-файлов в assets не нужно.
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                // 0..1: выше — чаще срабатывает, но и больше ложных срабатываний.
                // 0.6 — компромисс под русское произношение «Джарвис».
                .setSensitivity(0.6f)
                .build(context, PorcupineManagerCallback { onDetected() })
                .also { manager = it }
        } catch (e: Exception) {
            // Чаще всего: неверный/просроченный AccessKey или нет интернета при
            // самой первой активации ключа.
            Log.w(TAG, "Porcupine не инициализировался", e)
            onError("wake-word не запустился: ${e.message}")
            return false
        }

        return try {
            m.start()
            running = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Porcupine.start()", e)
            onError("не удалось занять микрофон: ${e.message}")
            false
        }
    }

    /** Отпустить микрофон, но сохранить инициализированный движок (быстрый рестарт). */
    fun stop() {
        if (!running) return
        runCatching { manager?.stop() }
        running = false
    }

    /** Полностью освободить нативные ресурсы. Обязательно в Service.onDestroy(). */
    fun release() {
        stop()
        runCatching { manager?.delete() }
        manager = null
    }

    private companion object { const val TAG = "WakeWordDetector" }
}
```

---

## Промпт 5 — `Notifications.kt`: текст постоянного уведомления стал изменяемым

### 5.1.

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
 * «слушаю команду».
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

Файл маленький и меняется целиком — замени его содержимое полностью на:

```kotlin
package com.jarvis.app

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис с ДВУМЯ независимыми причинами жить:
 *
 *  1. "keep-alive задачи" — приложение свёрнуто, а agent_task ещё выполняется.
 *     Сервис НЕ владеет WebSocket (это по-прежнему ChatViewModel/JarvisClient),
 *     он только не даёт системе убить процесс. Как и раньше.
 *  2. "wake-word" — слушаем слово «Джарвис» через Picovoice Porcupine и после
 *     срабатывания распознаём саму команду через SpeechRecognizer, а результат
 *     отдаём в ChatViewModel через [WakeWordBus].
 *
 * Сервис живёт, пока истинна хотя бы одна причина, и сам себя останавливает,
 * когда обе стали ложными. Причины переключаются экшенами интента — см.
 * companion object.
 */
class JarvisConnectionService : Service() {

    private var taskKeepAlive = false
    private var wakeWordOn = false
    private var accessKey = ""

    private var detector: WakeWordDetector? = null
    private var recognizer: SpeechRecognizer? = null
    /** true, пока идёт распознавание самой команды — защита от повторного входа. */
    private var recording = false

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Пользователь нажал 🎤 в приложении -> Porcupine обязан отпустить
        // микрофон, иначе SpeechRecognizer в UI не получит звук. Отпустил ->
        // возвращаем прослушивание.
        scope.launch {
            WakeWordBus.suspended.collect { suspended ->
                if (!wakeWordOn || recording) return@collect
                if (suspended) {
                    detector?.stop()
                    WakeWordBus.setState(WakeState.OFF)
                } else {
                    if (detector?.start() == true) WakeWordBus.setState(WakeState.WAITING)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TASK -> taskKeepAlive = intent.getBooleanExtra(EXTRA_ON, false)
            ACTION_WAKE -> {
                wakeWordOn = intent.getBooleanExtra(EXTRA_ON, false)
                if (wakeWordOn) accessKey = intent.getStringExtra(EXTRA_ACCESS_KEY).orEmpty()
            }
        }

        // startForeground ОБЯЗАН быть вызван в первые ~5 секунд после
        // startForegroundService, иначе система убьёт процесс с
        // ForegroundServiceDidNotStartInTimeException — поэтому зовём его
        // ВСЕГДА и только потом решаем, не пора ли остановиться.
        goForeground(statusText())

        if (wakeWordOn) startWakeWord() else stopWakeWord()

        if (!taskKeepAlive && !wakeWordOn) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWakeWord()
        detector?.release()
        detector = null
        scope.cancel()
        WakeWordBus.setState(WakeState.OFF)
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    //  Foreground + уведомление
    // -----------------------------------------------------------------

    private fun statusText(): String = when {
        recording -> "Слушаю команду…"
        wakeWordOn -> "Слушаю «Джарвис»…"
        else -> "Держим связь с ПК…"
    }

    private fun goForeground(text: String) {
        val notification = buildOngoingNotification(this, text)
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
        if (detector == null) {
            detector = WakeWordDetector(
                context = applicationContext,
                accessKey = accessKey,
                onDetected = { main.post { onWakeWordDetected() } }, // колбэк приходит с аудио-потока
                onError = { msg ->
                    WakeWordBus.setState(WakeState.ERROR)
                    WakeWordBus.emitNote(msg)
                },
            )
        }
        if (!recording && WakeWordBus.suspended.value.not()) {
            if (detector?.start() == true) WakeWordBus.setState(WakeState.WAITING)
        }
    }

    private fun stopWakeWord() {
        detector?.stop()
        cancelRecognizer()
        recording = false
        WakeWordBus.setState(WakeState.OFF)
    }

    /** Сработало слово-триггер: отпускаем Porcupine и слушаем саму команду. */
    private fun onWakeWordDetected() {
        if (recording) return
        recording = true
        detector?.stop()                     // микрофон нужен SpeechRecognizer'у
        WakeWordBus.setState(WakeState.RECORDING)
        refreshNotification()
        startCommandRecognition()
    }

    private fun startCommandRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            WakeWordBus.emitNote("на устройстве нет распознавания речи")
            finishRecognition()
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                // ERROR_NO_MATCH/SPEECH_TIMEOUT — обычная ситуация (ложное
                // срабатывание на похожее слово), не шумим в чат.
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    WakeWordBus.emitNote("wake-word: ошибка распознавания (код $error)")
                }
                finishRecognition()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) WakeWordBus.emitPhrase(text)
                finishRecognition()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Из сервиса (в отличие от Activity) движок распознавания не может
            // сам определить вызывающее приложение — передаём явно.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        runCatching { rec.startListening(intent) }.onFailure {
            WakeWordBus.emitNote("не удалось запустить микрофон: ${it.message}")
            finishRecognition()
        }
    }

    /** Команда распознана (или не распознана) — возвращаемся к ожиданию слова-триггера. */
    private fun finishRecognition() {
        cancelRecognizer()
        recording = false
        if (wakeWordOn && !WakeWordBus.suspended.value) {
            if (detector?.start() == true) WakeWordBus.setState(WakeState.WAITING)
        } else {
            WakeWordBus.setState(WakeState.OFF)
        }
        refreshNotification()
    }

    private fun cancelRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_TASK = "com.jarvis.app.action.TASK_KEEPALIVE"
        private const val ACTION_WAKE = "com.jarvis.app.action.WAKE_WORD"
        private const val EXTRA_ON = "on"
        private const val EXTRA_ACCESS_KEY = "accessKey"

        /** Держать процесс живым, пока не завершится agent_task (или отпустить). */
        fun taskKeepAlive(context: Context, on: Boolean) =
            send(context, ACTION_TASK) { it.putExtra(EXTRA_ON, on) }

        /** Включить/выключить постоянное прослушивание слова «Джарвис». */
        fun wakeWord(context: Context, on: Boolean, accessKey: String = "") =
            send(context, ACTION_WAKE) {
                it.putExtra(EXTRA_ON, on)
                it.putExtra(EXTRA_ACCESS_KEY, accessKey)
            }

        private fun send(context: Context, action: String, fill: (Intent) -> Unit) {
            val intent = Intent(context, JarvisConnectionService::class.java)
                .setAction(action)
                .also(fill)
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

## Промпт 7 — `SettingsRepository.kt`: ключ Picovoice и флаг wake-word

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

### 7.2. Ключи и методы

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
        // Ключ Picovoice хранится ЗДЕСЬ, а не в коде/BuildConfig: тот же принцип,
        // что и с ключом Gemini — секретов в APK и в репозитории быть не должно.
        val PICOVOICE_KEY = stringPreferencesKey("picovoice_key")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    }

    val relayUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.RELAY_URL] ?: DEFAULT_URL }

    val picovoiceKey: Flow<String> = context.dataStore.data
        .map { it[Keys.PICOVOICE_KEY].orEmpty() }

    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.WAKE_WORD_ENABLED] ?: false }

    suspend fun saveRelayUrl(relayUrl: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RELAY_URL] = relayUrl.trim() }
    }

    suspend fun savePicovoiceKey(key: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PICOVOICE_KEY] = key.trim() }
    }

    suspend fun saveWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.WAKE_WORD_ENABLED] = enabled }
    }
```

---

## Промпт 8 — `ChatViewModel.kt`

### 8.1. Поля

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
    // Wake-word: включён ли и каким ключом. Значения живут в DataStore, здесь —
    // их зеркало для UI (switch в настройках) и для перезапуска сервиса.
    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    private val _picovoiceKey = MutableStateFlow("")
    val picovoiceKey: StateFlow<String> = _picovoiceKey.asStateFlow()

    /** Что сейчас делает движок слова-триггера — для индикатора в шапке. */
    val wakeState: StateFlow<WakeState> = WakeWordBus.state
```

### 8.3. Подписки в `init`

Найди в `init { ... }` строку:

```kotlin
    init {
        viewModelScope.launch {
            val activeDeviceFlow = combine(devicesRepo.devices, devicesRepo.activeDeviceId) { devices, id ->
```

Вставь перед `viewModelScope.launch {` три новых блока, чтобы начало `init` стало таким:

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
            _picovoiceKey.value = settings.picovoiceKey.first()
            _wakeWordEnabled.value = settings.wakeWordEnabled.first()
            if (_wakeWordEnabled.value && _picovoiceKey.value.isNotBlank()) {
                JarvisConnectionService.wakeWord(getApplication(), true, _picovoiceKey.value)
            }
        }
        viewModelScope.launch {
            val activeDeviceFlow = combine(devicesRepo.devices, devicesRepo.activeDeviceId) { devices, id ->
```

> Остальное тело `init` — без изменений.

### 8.4. Управление wake-word

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
        if (on && _picovoiceKey.value.isBlank()) {
            note("сначала вставь ключ Picovoice в настройках (⚙)")
            return
        }
        _wakeWordEnabled.value = on
        viewModelScope.launch {
            settings.saveWakeWordEnabled(on)
            if (on) {
                JarvisConnectionService.wakeWord(getApplication(), true, _picovoiceKey.value)
                note("wake-word включён — скажи «Джарвис»")
            } else {
                JarvisConnectionService.wakeWord(getApplication(), false)
                note("wake-word выключен")
            }
        }
    }

    /** Сохранить ключ Picovoice; если wake-word уже включён — перезапустить его с новым ключом. */
    fun savePicovoiceKey(key: String) {
        val trimmed = key.trim()
        if (trimmed == _picovoiceKey.value) return
        _picovoiceKey.value = trimmed
        viewModelScope.launch {
            settings.savePicovoiceKey(trimmed)
            if (_wakeWordEnabled.value) {
                JarvisConnectionService.wakeWord(getApplication(), false)
                if (trimmed.isNotBlank()) {
                    JarvisConnectionService.wakeWord(getApplication(), true, trimmed)
                }
            }
        }
    }

```

### 8.5. Сервис больше не выключается целиком

Было три места, где сервис останавливался «насовсем». Теперь останавливается
только причина «keep-alive задачи» — иначе включённый wake-word умирал бы,
как только пользователь вернётся в приложение.

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

### 9.1. Состояние wake-word в `ChatScreen`

Найди:

```kotlin
    val repairing by vm.repairing.collectAsState()

    val context = LocalContext.current
```

Замени на:

```kotlin
    val repairing by vm.repairing.collectAsState()
    val wakeWordEnabled by vm.wakeWordEnabled.collectAsState()
    val picovoiceKey by vm.picovoiceKey.collectAsState()
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
    // Пока пользователь сам держит микрофон, Porcupine обязан его отпустить —
    // два движка одновременно на одном AudioRecord не работают. Обёртка ставит
    // и снимает флаг WakeWordBus.suspended вокруг ручного распознавания.
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
                    // Видимый признак, что телефон слушает слово-триггер:
                    // 👂 — ждём «Джарвис», 🎙 — услышали, записываем команду.
                    if (wakeState != WakeState.OFF) {
                        Text(
                            if (wakeState == WakeState.RECORDING) "🎙" else "👂",
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(onClick = onOpenDevices) { Text("🖥") }
```

### 9.5. Передаём настройки в диалог

Найди:

```kotlin
    if (showSettings) {
        SettingsDialog(
            device = activeDevice,
            onSaveRelayUrl = { vm.updateRelayUrl(it) },
            onUnpair = {
                activeDevice?.let { vm.unpair(it.id) }
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
```

Замени на:

```kotlin
    if (showSettings) {
        SettingsDialog(
            device = activeDevice,
            wakeWordEnabled = wakeWordEnabled,
            picovoiceKey = picovoiceKey,
            onWakeWordChange = { on -> onWakeWordChange(on) },
            onSavePicovoiceKey = { vm.savePicovoiceKey(it) },
            onSaveRelayUrl = { vm.updateRelayUrl(it) },
            onUnpair = {
                activeDevice?.let { vm.unpair(it.id) }
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
```

### 9.6. Сам диалог настроек

Найди весь `fun SettingsDialog(...)` и замени его целиком на:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    device: PairedDevice?,
    wakeWordEnabled: Boolean,
    picovoiceKey: String,
    onWakeWordChange: (Boolean) -> Unit,
    onSavePicovoiceKey: (String) -> Unit,
    onSaveRelayUrl: (String) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(device?.relayUrl) { mutableStateOf(device?.relayUrl.orEmpty()) }
    var key by remember(picovoiceKey) { mutableStateOf(picovoiceKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device?.name ?: "Настройки") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("адрес релея (ws://…)") }, singleLine = true,
                )
                Text(
                    "Сменилась сеть? Отсканируй QR заново.",
                    style = MaterialTheme.typography.labelSmall,
                )

                HorizontalDivider()

                Text("Слово-триггер", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Просыпаться по «Джарвис»", Modifier.weight(1f))
                    Switch(checked = wakeWordEnabled, onCheckedChange = onWakeWordChange)
                }
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("ключ Picovoice (AccessKey)") }, singleLine = true,
                )
                Text(
                    "Бесплатный ключ — console.picovoice.ai. Хранится только на " +
                        "телефоне, в APK и в репозитории его нет.",
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
            TextButton(onClick = {
                onSavePicovoiceKey(key)
                onSaveRelayUrl(url)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
```

> `verticalScroll`/`rememberScrollState` уже импортированы в этом файле
> (`androidx.compose.foundation.rememberScrollState` и
> `androidx.compose.foundation.verticalScroll` — если второго нет, добавь его в
> импорты). `HorizontalDivider` и `Switch` приходят из `androidx.compose.material3.*`,
> который импортирован звёздочкой.

---

## Проверка

1. **Сборка.** Gradle должен подтянуть `ai.picovoice:porcupine-android`. Если
   артефакт не найден — проверь `mavenCentral()` в `settings.gradle.kts`.
2. **Ключ.** Запусти приложение → ⚙ → вставь AccessKey с console.picovoice.ai →
   «Сохранить». Без ключа переключатель должен отвечать сообщением
   «сначала вставь ключ Picovoice», а не молча ничего не делать.
3. **Включение.** ⚙ → переключатель «Просыпаться по «Джарвис»» → должен
   появиться системный запрос микрофона (если ещё не выдан), затем в шапке
   появляется 👂, в шторке — тихое уведомление «Слушаю «Джарвис»…».
4. **Срабатывание на переднем плане.** Скажи «Джарвис» → индикатор меняется на
   🎙, уведомление на «Слушаю команду…» → скажи «сделай скриншот» → фраза должна
   уйти в чат как обычное сообщение и выполниться на ПК. После этого индикатор
   возвращается на 👂.
5. **Срабатывание в фоне.** Сверни приложение (кнопка «домой», не смахивай из
   списка задач) → скажи «Джарвис, открой ютуб» → команда должна выполниться на
   ПК; вернувшись в приложение, увидишь её в истории чата.
6. **Конфликт микрофонов.** При включённом wake-word нажми обычную кнопку 🎤 →
   ручное распознавание должно работать нормально (не «микрофон занят»), а после
   его окончания wake-word обязан вернуться в режим ожидания (👂 снова в шапке).
7. **Не ломается старое поведение.** Запусти agent_task, сверни приложение →
   уведомление о завершении задачи должно приходить, как раньше. Затем вернись в
   приложение → wake-word (если включён) остаться включённым, сервис не должен
   исчезнуть.
8. **Выключение.** ⚙ → переключатель выключить → 👂 пропадает, уведомление
   исчезает (если нет активной задачи), «Джарвис» больше не срабатывает.
9. **Переживает перезапуск.** Закрой и открой приложение — wake-word должен
   включиться сам, раз он был включён (значение в DataStore).
10. **Ложные срабатывания.** Поговори рядом 2-3 минуты на посторонние темы —
    если срабатывает слишком часто, уменьши `setSensitivity(0.6f)` до `0.45f` в
    `WakeWordDetector.kt`; если не слышит — подними до `0.75f`.
11. **Расход батареи** (для главы про производительность, не для «работает/нет»):
    Настройки Android → Батарея → расход по приложениям. Замерь час с включённым
    wake-word и час без него, запиши разницу — это готовая цифра в диплом.

---

## Известные ограничения (проговорить на защите — это плюс, а не минус)

1. **Нет доступа к аппаратному DSP.** У Siri/Google Assistant слово-триггер
   слушает отдельный маломощный чип, всегда включённый и не будящий процессор.
   Сторонним Android-приложениям этот уровень недоступен, поэтому распознавание
   идёт на CPU — отсюда и расход батареи, и необходимость foreground-сервиса.
2. **Постоянно видимое уведомление.** Android намеренно не даёт скрыть, что
   приложение слушает микрофон, плюс показывает системный индикатор микрофона.
   Это требование приватности платформы, обойти его нельзя (и не нужно).
3. **Doze mode.** При долгом бездействии с выключенным экраном система может
   всё равно усыпить процесс. Foreground-сервис сильно снижает риск, но не
   убирает его полностью; на части прошивок (Xiaomi/Huawei/Samsung) нужно ещё
   вручную разрешить автозапуск и снять ограничение батареи для приложения.
4. **Процесс должен быть жив.** Wake-word умирает, если смахнуть приложение из
   списка задач или если систему заставят убить процесс. Это прямое следствие
   текущей архитектуры: WebSocket живёт в `ChatViewModel`, а не в сервисе.
   Полностью независимый от UI фоновый режим — это отдельный крупный рефакторинг
   (перенос соединения в сервис + `START_STICKY` + `BOOT_COMPLETED`), его стоит
   упомянуть в главе «дальнейшее развитие».
5. **Распознавание самой фразы — не офлайн.** Слово-триггер распознаётся
   локально, а следующая за ним фраза уходит в `SpeechRecognizer`, который на
   большинстве устройств использует облако Google. Полностью офлайновый вариант —
   заменить его на Vosk; тоже хорошая тема для «дальнейшего развития».
6. **Английская модель слова.** Используется встроенное `BuiltInKeyword.JARVIS`
   (английская модель). Русское «Джарвис» на неё срабатывает, но точность ниже,
   чем у обученного русского слова. Кастомное русское слово-триггер требует
   `.ppn` + русский `.pv` модельный файл с консоли Picovoice — отдельный
   маленький шаг, если понадобится.
