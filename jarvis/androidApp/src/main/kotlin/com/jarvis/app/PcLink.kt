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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Связь с одним привязанным ПК на время одной команды — в том числе долгой
 * ИИ-задачи, когда команда идёт на НЕ выбранный в чате ПК («на рабочем
 * открой почту», «на всех ПК…»).
 *
 * Чем отличается от [RelayPairing.sendCommand]: пока команда выполняется,
 * сюда же приходят её шаги, план и вопросы агента ([run] отдаёт их в onEvent),
 * а через [send] по той же связи можно ответить на вопрос — ответ попадёт
 * именно тому ПК, который спросил, а не выбранному в чате.
 *
 * Чем отличается от [JarvisClient]: не переподключается и живёт ровно одну
 * команду. Держать постоянное соединение с каждым ПК ради редких команд
 * было бы лишней нагрузкой на батарею и релей.
 *
 * Релей шлёт этой связи всё, что делает её ПК, — и задачи, запущенные с
 * других телефонов или из чата. Поэтому наружу отдаём только сообщения со
 * своими requestId: команды и ответов на вопросы.
 */
class PcLink(val device: PairedDevice) {

    @Volatile
    private var ws: WebSocket? = null

    /** requestId всего, что ушло по этой связи: основной команды и ответов. */
    private val ownRequests = ConcurrentHashMap.newKeySet<String>()

    /**
     * Отправить [command] и ждать её итог (CommandResult с тем же
     * [requestId]) не дольше [timeoutMs]. Шаги, план, вопросы и результаты
     * ответов по ходу — в [onEvent] (из потока OkHttp).
     */
    suspend fun run(
        command: Command,
        timeoutMs: Long,
        requestId: String = UUID.randomUUID().toString(),
        onEvent: (JarvisMessage) -> Unit = {},
    ): CommandOutcome {
        ownRequests += requestId
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<CommandOutcome> { cont ->
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JarvisJson.encodeToString<JarvisMessage>(
                                Auth(
                                    requestId = UUID.randomUUID().toString(),
                                    deviceId = device.id,
                                    token = device.token,
                                    role = DeviceRole.PHONE,
                                )
                            )
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = try {
                            JarvisJson.decodeFromString<JarvisMessage>(text)
                        } catch (e: Exception) {
                            Log.e("PcLink", "не разобрал ответ: $text", e)
                            return
                        }
                        when {
                            msg is AuthResult && !msg.ok -> {
                                if (cont.isActive) cont.resume(CommandOutcome.Rejected(msg.reason ?: "вход отклонён"))
                                webSocket.close(1000, "done")
                            }
                            // Агент этого ПК не на связи — сразу говорим, а не ждём таймаут задачи.
                            msg is AuthResult && msg.agentOnline == false -> {
                                if (cont.isActive) cont.resume(CommandOutcome.Failed("ПК офлайн"))
                                webSocket.close(1000, "done")
                            }
                            msg is AuthResult -> webSocket.send(
                                JarvisJson.encodeToString<JarvisMessage>(CommandRequest(requestId, command))
                            )
                            msg is CommandResult && msg.requestId == requestId -> {
                                if (cont.isActive) cont.resume(CommandOutcome.Success(msg))
                                webSocket.close(1000, "done")
                            }
                            else -> if (msg.requestId in ownRequests) onEvent(msg)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("PcLink", "ошибка соединения с «${device.name}»", t)
                        if (cont.isActive) cont.resume(CommandOutcome.Failed(t.message ?: "ошибка соединения"))
                    }
                }
                val socket = http.newWebSocket(Request.Builder().url(httpUrl(device.relayUrl)).build(), listener)
                ws = socket
                cont.invokeOnCancellation { socket.cancel() }
            }
        }
        ws = null
        return outcome ?: CommandOutcome.Failed("нет ответа от «${device.name}» (офлайн или таймаут)")
    }

    /**
     * Отправить ещё одну команду по уже открытой связи — ответ на вопрос агента
     * или отмену. Итог придёт в onEvent [run]. false — связь уже закрыта.
     */
    fun send(command: Command, requestId: String = UUID.randomUUID().toString()): Boolean {
        val socket = ws ?: return false
        ownRequests += requestId
        return socket.send(JarvisJson.encodeToString<JarvisMessage>(CommandRequest(requestId, command)))
    }

    companion object {
        /**
         * Общий клиент на все связи. Пинг раз в 20 с: ИИ-задача идёт минутами, и
         * без него тихое соединение мог бы оборвать NAT роутера или мобильный оператор.
         */
        internal val http: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** OkHttp для веб-сокетов понимает схему http/https, а не ws/wss. */
        internal fun httpUrl(relayUrl: String) =
            relayUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
    }
}
