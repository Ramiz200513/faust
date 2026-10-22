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
 *     «Фауст» и отдаём команду в ChatViewModel через [WakeWordBus].
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
            else -> "Слушаю «Фауст»…"
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

        /** Включить/выключить постоянное прослушивание слова «Фауст». */
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
