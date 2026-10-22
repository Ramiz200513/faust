package com.jarvis.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.protocol.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/**
 * WebSocket-клиент приложения на OkHttp. Шлёт/принимает те же JarvisMessage,
 * что релей и агент (типы из :shared), поэтому телефон и бэкенд не разъедутся.
 *
 * Держит соединение живым: если сокет оборвался (onFailure / onClosed), сам
 * переподключается с нарастающей паузой 1 → 2 → 4 → 8 → 16 → 30 → 30 с…
 * Пауза сбрасывается в 1 с после успешного входа (AuthResult.ok).
 *
 * Отправка сообщений (send) не изменилась — её можно звать прямо из UI.
 */
class JarvisClient {

    private val http = OkHttpClient()
    private var ws: WebSocket? = null

    private var url = ""
    private var token = ""
    private var deviceId = ""
    private var onMessage: (JarvisMessage) -> Unit = {}
    private var onStatus: (String) -> Unit = {}

    // --- Состояние автопереподключения ---

    // true, пока пользователь хочет быть на связи (между connect() и disconnect()).
    private var wantConnected = false

    // true, если релей отверг токен (AuthResult.ok=false) — сразу вслед за
    // этим релей закрывает сокет (код 1003), но переподключаться с тем же
    // токеном бессмысленно, а onClosed не должен затирать статус "вход отклонён".
    private var authRejected = false

    // Отложенные задачи запускаем на главном потоке — Handler это гарантирует.
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectPending = false
    private var nextDelayMs = START_DELAY_MS

    fun connect(
        url: String,
        token: String,
        deviceId: String,
        onMessage: (JarvisMessage) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        this.url = url
        this.token = token
        this.deviceId = deviceId
        this.onMessage = onMessage
        this.onStatus = onStatus

        wantConnected = true
        authRejected = false
        nextDelayMs = START_DELAY_MS          // новый цикл — задержку в начало
        cancelPendingReconnect()
        openSocket()
    }

    /** Осознанно отключиться: гасим сокет и больше не переподключаемся. */
    fun disconnect() {
        wantConnected = false
        cancelPendingReconnect()
        ws?.close(1000, "user disconnect")
        ws = null
        onStatus("отключено")
    }

    /** Отправить сообщение. OkHttp сам сериализует очередь отправки. */
    fun send(msg: JarvisMessage) {
        ws?.send(JarvisJson.encodeToString(msg))
    }

    // ---------------------------------------------------------------------

    private fun openSocket() {
        // Закрываем прежний сокет, если остался, чтобы не плодить соединения.
        ws?.cancel()

        // OkHttp для веб-сокетов использует схему http/https, а не ws/wss.
        val httpUrl = url
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")

        onStatus("подключение…")
        val request = Request.Builder().url(httpUrl).build()
        ws = http.newWebSocket(request, listener)
    }

    /** Запланировать переподключение через текущую паузу и удвоить её. */
    private fun scheduleReconnect() {
        if (!wantConnected || reconnectPending) return
        reconnectPending = true

        val delay = nextDelayMs
        onStatus("переподключение… (через ${delay / 1000} с)")
        reconnectHandler.postDelayed({
            reconnectPending = false
            if (wantConnected) openSocket()
        }, delay)

        // Следующая попытка — вдвое дольше, но не больше MAX_DELAY_MS.
        nextDelayMs = (nextDelayMs * 2).coerceAtMost(MAX_DELAY_MS)
    }

    private fun cancelPendingReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectPending = false
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Первое сообщение — авторизация (релей этого ждёт).
            val auth = Auth(
                requestId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                token = token,
                role = DeviceRole.PHONE,
                protocolVersion = PROTOCOL_VERSION,
            )
            webSocket.send(JarvisJson.encodeToString<JarvisMessage>(auth))
            onStatus("вход…")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JarvisJson.decodeFromString<JarvisMessage>(text)
            } catch (e: Exception) {
                Log.e("JarvisClient", "не разобрал сообщение: $text", e)
                return
            }
            // Успешный вход = связь реально установлена: сбрасываем задержку,
            // чтобы следующий обрыв снова начинал с 1 с.
            if (msg is AuthResult) {
                if (msg.ok) {
                    nextDelayMs = START_DELAY_MS
                    onStatus("подключено ✓")
                } else {
                    // Токен отвергнут — это не временный обрыв связи, повторные
                    // попытки с тем же токеном ничего не изменят.
                    authRejected = true
                    wantConnected = false
                    cancelPendingReconnect()
                    onStatus("вход отклонён")
                }
            }
            onMessage(msg)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("JarvisClient", "ошибка соединения", t)
            if (wantConnected) {
                onStatus("нет связи: ${t.message ?: "ошибка"}")
                scheduleReconnect()
            } else {
                onStatus("ошибка: ${t.message}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (wantConnected) scheduleReconnect()
            else if (!authRejected) onStatus("отключено")
            // При authRejected статус "вход отклонён" уже выставлен выше и его
            // не нужно перезатирать безликим "отключено".
        }
    }

    private companion object {
        const val START_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
    }
}
