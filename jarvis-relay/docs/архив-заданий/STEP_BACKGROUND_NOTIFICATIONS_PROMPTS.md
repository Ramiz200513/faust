# Шаг 5/5: работа в фоне + уведомление о завершении задачи

Последняя фича из списка. После неё — переходи к `FURTHER_DEVELOPMENT_ROADMAP.md`,
там дальнейшие идеи развития проекта для диплома.

## Общий контекст

Сейчас приложение никак не реагирует на то, что пользователь его свернул. Если
в этот момент выполняется `agent_task` (может идти 1-3 минуты — вижн-агент
кликает по экрану/DOM пошагово), два риска:

1. Android может «убить» процесс приложения (agressive battery management),
   и WebSocket-соединение оборвётся — команда потеряется, ответ не придёт.
2. Даже если процесс жив, пользователь вернётся в приложение и узнает
   результат только тогда, когда сам откроет чат — никакого пуш-уведомления.

Решение — стандартный Android-паттерн: пока есть незавершённая команда и
приложение свёрнуто, поднимаем **foreground-сервис** (повышает приоритет
процесса, ОС его не убивает так агрессивно) с тихим постоянным уведомлением
«держим связь с ПК». Когда финальный `CommandResult` приходит с `AgentReport`
и приложение всё ещё свёрнуто — показываем отдельное, обычное (не тихое)
уведомление «задача выполнена», по тапу на которое открывается приложение.

Важно: сервис **не владеет** WebSocket-соединением — это по-прежнему делает
`JarvisClient` внутри `ChatViewModel` (он живёт, пока жив `ViewModel`, а не
пока жива `Activity`). Сервис нужен только чтобы поднять приоритет процесса
на время, пока `ViewModel` работает в фоне. Протокол/relay/агент **не
трогаем вообще** — это чисто фронтовая фича на телефоне, использующая уже
существующий `CommandProgress` (`SENT`/`RUNNING`) как признак «есть
незавершённая команда».

Все правки — только в `androidApp`. Два новых файла + три отредактированных.

---

## Промпт 1 — новый файл `JarvisConnectionService.kt`

Создай `androidApp/src/main/kotlin/com/jarvis/app/JarvisConnectionService.kt`:

```kotlin
package com.jarvis.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Минимальный foreground-сервис: НЕ владеет WebSocket-соединением (это по-прежнему
 * ChatViewModel/JarvisClient), а только поднимает приоритет процесса, пока
 * пользователь свернул приложение с незавершённой командой — иначе Android может
 * убить процесс, и agent_task оборвётся, а уведомление о готовности не придёт.
 *
 * Живёт от onAppBackgrounded() до onAppForegrounded() либо пока не пропали
 * незавершённые команды — см. ChatViewModel.onAppBackgrounded/onAppForegrounded.
 */
class JarvisConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification(this))
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, JarvisConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisConnectionService::class.java))
        }
    }
}
```

`startForegroundService` + `startForeground` внутри `onStartCommand` — обязательная
пара с Android 8+: если не вызвать `startForeground` в течение нескольких секунд,
система убьёт сервис с исключением `ForegroundServiceDidNotStartInTimeException`.

---

## Промпт 2 — новый файл `Notifications.kt`

Создай `androidApp/src/main/kotlin/com/jarvis/app/Notifications.kt`:

```kotlin
package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

// Два канала: тихий «держим связь» (пока сервис жив) и обычный «задача готова»
// (разовое, можно смахнуть). Разделены, чтобы пользователь мог заглушить первый
// в настройках Android, не потеряв уведомления о готовности задач.
private const val CHANNEL_ONGOING = "jarvis_ongoing"
private const val CHANNEL_TASK_DONE = "jarvis_task_done"
private const val NOTIFICATION_TASK_DONE_ID = 2001

/** Создание каналов идемпотентно — Android сам не пересоздаёт существующий. */
private fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ONGOING, "Соединение с ПК", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Держит связь с ПК, пока приложение свёрнуто" }
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_TASK_DONE, "Задача выполнена", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Агент на ПК закончил работу" }
    )
}

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

/** Отдельное уведомление: agent_task завершился, пока приложение было свёрнуто. */
fun notifyAgentTaskFinished(context: Context, text: String) {
    ensureChannels(context)
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pendingIntent = launchIntent?.let {
        PendingIntent.getActivity(
            context, 0, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_TASK_DONE)
        .setContentTitle("Jarvis: задача выполнена")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()
    context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_TASK_DONE_ID, notification)
}
```

Иконки — встроенные системные drawable (`android.R.drawable...`), не свои: в
манифесте у приложения вообще нет `android:icon`, поэтому не выдумываем
несуществующий ресурс, а используем то, что гарантированно есть в любой версии
Android.

---

## Промпт 3 — `ChatViewModel.kt`: отслеживание фона + вызов сервиса/уведомления

**3.1.** Добавь поле рядом с `activeAgentOffline` (после строки
`private var activeAgentOffline = false`):

```kotlin
    // Свёрнуто ли приложение — влияет на то, поднимать ли foreground-сервис и
    // слать ли уведомление о завершении agent_task (см. MainActivity: ON_STOP/ON_START).
    private var isBackgrounded = false
```

**3.2.** Добавь три функции — например, сразу после `uploadFile(...)` и перед
`saveDownloadedFile(...)`:

```kotlin
    /** Экран свернули (ON_STOP жизненного цикла) — держим соединение foreground-
     *  сервисом, только если есть незавершённая команда; иначе сервис не нужен. */
    fun onAppBackgrounded() {
        isBackgrounded = true
        if (hasPendingCommands()) JarvisConnectionService.start(getApplication())
    }

    /** Экран вернули на передний план (ON_START) — сервис больше не нужен. */
    fun onAppForegrounded() {
        isBackgrounded = false
        JarvisConnectionService.stop(getApplication())
    }

    private fun hasPendingCommands(): Boolean =
        _messages.value.any { it.progress == CommandProgress.SENT || it.progress == CommandProgress.RUNNING }
```

**3.3.** Расширь обработку результата в `handle()`: сейчас там

```kotlin
                when (val data = msg.data) {
                    is ClipboardText -> copyToPhoneClipboard(data.text)
                    is FileList -> _fileList.value = data.entries
                    is FileTransfer -> saveDownloadedFile(data.fileName, data.base64)
                    else -> {}
                }
                add(ChatMessage(author = Author.PC, text = resultText(msg)))
```

замени на:

```kotlin
                when (val data = msg.data) {
                    is ClipboardText -> copyToPhoneClipboard(data.text)
                    is FileList -> _fileList.value = data.entries
                    is FileTransfer -> saveDownloadedFile(data.fileName, data.base64)
                    is AgentReport -> if (isBackgrounded) notifyAgentTaskFinished(getApplication(), resultText(msg))
                    else -> {}
                }
                // Незавершённых команд больше нет — фоновый сервис свою роль выполнил.
                if (isBackgrounded && !hasPendingCommands()) JarvisConnectionService.stop(getApplication())
                add(ChatMessage(author = Author.PC, text = resultText(msg)))
```

`notifyAgentTaskFinished`/`JarvisConnectionService` — из того же пакета
`com.jarvis.app`, импорт не нужен.

---

## Промпт 4 — `MainActivity.kt`: разрешение на уведомления + отслеживание жизненного цикла

**4.1.** Добавь импорты (рядом с остальными в начале файла):

```kotlin
import android.os.Build
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
```

**4.2.** В `ChatScreen`, сразу после блока с `micPermissionLauncher` (после его
закрывающей `}`, перед `fun onMicClick() { ... }` — не обязательно именно там,
главное — до `Scaffold`), добавь:

```kotlin
    // Разрешение на уведомления — на Android 13+/TIRAMISU это runtime-разрешение,
    // без него notifyAgentTaskFinished() просто тихо ничего не покажет (не критично,
    // сама задача на ПК всё равно выполнится). Просим сразу, как и с микрофоном.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* отказ ни на что не влияет — просто не будет уведомления */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Свернули/вернули приложение -> ChatViewModel решает, поднимать ли
    // foreground-сервис и когда слать уведомление о готовности задачи.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAppBackgrounded()
                Lifecycle.Event.ON_START -> vm.onAppForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

Используется `androidx.compose.ui.platform.LocalLifecycleOwner`, а не отдельная
зависимость `lifecycle-process` — она уже доступна через `compose-ui`, который
и так подключён (`libs.compose.ui`), поэтому новых зависимостей в
`build.gradle.kts` для этого не нужно (только для `NotificationCompat`, см.
следующий пункт).

---

## Промпт 5 — `AndroidManifest.xml`: разрешения и сервис

**5.1.** После блока с `CAMERA`/`uses-feature` (перед `<queries>`) добавь:

```xml
    <!-- Разрешения для фонового сервиса, который держит соединение с ПК, пока
         приложение свёрнуто, и для уведомления о завершении agent_task. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

**5.2.** Внутри `<application>`, сразу после закрывающего `</activity>`,
добавь объявление сервиса:

```xml
        <service
            android:name=".JarvisConnectionService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
```

`foregroundServiceType="dataSync"` — самый точный из стандартных типов для
«поддерживаем сетевое соединение с внешним устройством в фоне» (Android 14+
требует явно указать тип, иначе `startForeground` упадёт с исключением).

---

## Промпт 6 — `build.gradle.kts` (androidApp): зависимость на `NotificationCompat`

`NotificationChannel`/`NotificationManager` — часть платформенного SDK,
доп. зависимость не нужна. А вот `androidx.core.app.NotificationCompat`
(используется в `Notifications.kt`, чтобы не собирать `Notification.Builder`
вручную с оглядкой на версию Android) явно в зависимостях сейчас не значится.
Добавь в блок `dependencies { ... }`, рядом с `datastore-preferences`:

```kotlin
    // NotificationCompat/NotificationChannel — уведомление о завершении задачи агента
    implementation("androidx.core:core-ktx:1.13.1")
```

(Тот же стиль, что уже используется в файле для `okhttp` и
`datastore-preferences` — прямые координаты, а не через `libs.versions.toml`,
т.к. его нет в подключённой папке.)

---

## Проверка (пройди по пунктам после того, как всё применишь)

1. Сборка компилируется — новые файлы `JarvisConnectionService.kt` и
   `Notifications.kt`, плюс правки в `ChatViewModel.kt`/`MainActivity.kt`/
   `AndroidManifest.xml`/`build.gradle.kts`.
2. При первом запуске (после установки) должно появиться системное окно с
   запросом разрешения на уведомления (Android 13+) — соглашаешься.
3. Дай ПК длинную задачу (`agent_task` с несколькими шагами — например,
   «найди на ютубе российские мемы и поставь лайк на первом видео», раз она
   уже гоняет вижн-агента несколько шагов) и сразу сверни приложение (кнопка
   «Домой», не «Назад» — «Назад» может убить Activity вместо сворачивания).
4. В шторке уведомлений должна появиться тихая закреплённая иконка «Jarvis —
   держим связь с ПК…» — значит foreground-сервис поднялся.
5. Дождись завершения задачи на ПК — должно прилететь отдельное (не тихое)
   уведомление «Jarvis: задача выполнена» с текстом результата; тихое
   «держим связь» при этом должно исчезнуть (сервис сам остановился, т.к.
   незавершённых команд больше нет).
6. Тапни по уведомлению «задача выполнена» — должно открыть приложение на
   экране чата, где виден финальный результат.
7. Проверь короткую команду (например «поставь громкость 50%») — сверни
   сразу после отправки, разверни через секунду: уведомление о фоновой связи
   либо вообще не должно успеть показаться, либо тут же пропасть — короткие
   команды не должны навсегда «подвешивать» тихое уведомление.
8. Если разрешение на уведомления отклонить на шаге 2 — приложение не должно
   падать ни при сворачивании, ни при завершении задачи: просто не будет
   визуального уведомления, вся остальная логика (foreground-сервис,
   сохранение результата в чат) работает как обычно.
