package com.jarvis.app

import android.util.Log
import com.jarvis.protocol.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.coroutines.resume

/** Итог попытки привязать ПК по одноразовому коду. */
sealed interface PairOutcome {
    data class Success(val device: PairedDevice) : PairOutcome
    /** Релей код опознал, но отклонил (истёк, уже использован и т.п.). */
    data class Rejected(val reason: String) : PairOutcome
    /** Сеть, таймаут или непонятный ответ — не про сам код. */
    data class Failed(val message: String) : PairOutcome
}

/** Итог проверки «на связи ли агент за этим устройством прямо сейчас». */
enum class DeviceOnlineStatus { ONLINE, OFFLINE, UNAUTHORIZED, UNKNOWN }

/** Итог отправки одной команды уже привязанному ПК (см. sendCommand). */
sealed interface CommandOutcome {
    data class Success(val result: CommandResult) : CommandOutcome
    /** Релей ответил, но отверг вход (например, ПК успели отвязать). */
    data class Rejected(val reason: String) : CommandOutcome
    /** Сеть, таймаут или ПК офлайн — не про саму команду. */
    data class Failed(val message: String) : CommandOutcome
}

/**
 * Короткие одноразовые соединения с релеем: привязка ПК по коду и проверка
 * онлайн-статуса конкретного устройства. В отличие от [JarvisClient] эти
 * соединения не переподключаются и не хранят состояние — открылись,
 * дождались одного ответа (или истёк таймаут), закрылись.
 */
object RelayPairing {

    private val http = OkHttpClient()

    /** Обменять одноразовый код на постоянный токен ПК. */
    suspend fun pair(relayUrl: String, code: String, timeoutMs: Long = 12_000): PairOutcome {
        val msg = withTimeoutOrNull(timeoutMs) {
            oneShot(relayUrl) { ws, requestId ->
                ws.send(JarvisJson.encodeToString<JarvisMessage>(PairRequest(requestId, code)))
            }
        } ?: return PairOutcome.Failed("нет ответа от релея (нет сети или таймаут)")

        return when {
            msg is PairResult && msg.ok -> {
                val id = msg.deviceId
                val token = msg.deviceToken
                if (id == null || token == null) {
                    PairOutcome.Failed("релей подтвердил код, но не прислал deviceId/token")
                } else {
                    PairOutcome.Success(
                        PairedDevice(
                            id = id,
                            name = msg.deviceName?.takeIf { it.isNotBlank() } ?: id,
                            token = token,
                            relayUrl = relayUrl,
                        )
                    )
                }
            }
            msg is PairResult -> PairOutcome.Rejected(msg.reason ?: "код отклонён")
            else -> PairOutcome.Failed("релей прислал неожиданный ответ")
        }
    }

    /** Быстро проверить, на связи ли агент за уже привязанным устройством. */
    suspend fun checkStatus(device: PairedDevice, timeoutMs: Long = 6_000): DeviceOnlineStatus {
        val msg = withTimeoutOrNull(timeoutMs) {
            oneShot(device.relayUrl) { ws, requestId ->
                ws.send(
                    JarvisJson.encodeToString<JarvisMessage>(
                        Auth(
                            requestId = requestId,
                            deviceId = device.id,
                            token = device.token,
                            role = DeviceRole.PHONE,
                        )
                    )
                )
            }
        } ?: return DeviceOnlineStatus.UNKNOWN

        return when {
            msg !is AuthResult -> DeviceOnlineStatus.UNKNOWN
            !msg.ok -> DeviceOnlineStatus.UNAUTHORIZED
            msg.agentOnline == false -> DeviceOnlineStatus.OFFLINE
            else -> DeviceOnlineStatus.ONLINE // true или null (старый релей) — считаем, что на связи
        }
    }

    /**
     * Авторизоваться под уже привязанным ПК, отправить ОДНУ команду и
     * дождаться CommandResult. Как и [checkStatus] — короткое соединение
     * без переподключений; используется для одновременной рассылки одной
     * команды нескольким ПК сразу (экран «Мульти-ПК»), где держать
     * постоянный JarvisClient на каждый ПК было бы избыточно.
     *
     * Сама связь — [PcLink]: та же, что у ИИ-задач на другом ПК, только без
     * событий по ходу (быстрой команде они не нужны).
     */
    suspend fun sendCommand(device: PairedDevice, command: Command, timeoutMs: Long = 15_000): CommandOutcome =
        PcLink(device).run(command, timeoutMs)

    /**
     * Открыть сокет, отправить первое сообщение через [onOpen] и вернуть
     * первое входящее [JarvisMessage] (или null при ошибке). Сокет
     * закрывается в любом случае — успех, ошибка или отмена корутины.
     */
    private suspend fun oneShot(
        relayUrl: String,
        onOpen: (WebSocket, requestId: String) -> Unit,
    ): JarvisMessage? = suspendCancellableCoroutine { cont ->
        val requestId = UUID.randomUUID().toString()
        // OkHttp для веб-сокетов использует схему http/https, а не ws/wss.
        val httpUrl = relayUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen(webSocket, requestId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try {
                    JarvisJson.decodeFromString<JarvisMessage>(text)
                } catch (e: Exception) {
                    Log.e("RelayPairing", "не разобрал ответ: $text", e)
                    null
                }
                if (cont.isActive) cont.resume(msg)
                webSocket.close(1000, "done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RelayPairing", "ошибка соединения", t)
                if (cont.isActive) cont.resume(null)
            }
        }

        val ws = http.newWebSocket(Request.Builder().url(httpUrl).build(), listener)
        cont.invokeOnCancellation { ws.cancel() }
    }
}
