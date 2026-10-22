package com.jarvis.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Version of the wire protocol. Client, relay and agent must agree on it.
 * Bump on any breaking change to message shapes so old builds can refuse
 * to talk to incompatible ones instead of failing silently.
 */
const val PROTOCOL_VERSION: Int = 1

/**
 * Every message on the wire is a [JarvisMessage]. It is a sealed hierarchy,
 * so kotlinx.serialization writes a "type" discriminator automatically and
 * the receiver can `when`-match exhaustively.
 *
 * Flow of a spoken command:
 *   phone  --CommandRequest(NaturalLanguage("сделай потише"))-->  relay
 *   relay  (asks the LLM, gets a concrete Command, checks whitelist)
 *   relay  --CommandRequest(SetVolume(30))-->  agent
 *   agent  --CommandResult(OK)-->  relay  -->  phone
 */
@Serializable
sealed interface JarvisMessage {
    /** Correlates a request with its result. Generate a UUID per request. */
    val requestId: String
}

/** Device authenticates to the relay right after the socket opens. */
@Serializable
@SerialName("auth")
data class Auth(
    override val requestId: String,
    val deviceId: String,
    val token: String,
    val role: DeviceRole,
    val protocolVersion: Int = PROTOCOL_VERSION,
) : JarvisMessage

@Serializable
@SerialName("auth_result")
data class AuthResult(
    override val requestId: String,
    val ok: Boolean,
    val reason: String? = null,
    /**
     * Только для role=PHONE: онлайн ли сейчас агент того ПК, за который
     * отвечает этот deviceId/token (агент держит своё соединение с релеем).
     * null — релей ещё не умеет это сообщать (старая версия) или это не
     * авторизация телефона; тогда UI считает состояние неизвестным, а не
     * офлайн. false — токен верный, но агент сейчас не подключён к релею.
     */
    val agentOnline: Boolean? = null,
) : JarvisMessage

/**
 * Привязка нового ПК по одноразовому коду, который агент показывает на
 * экране (текстом и/или QR). Шлётся ПЕРВЫМ сообщением в соединении вместо
 * [Auth] — до этого момента у телефона ещё нет постоянного токена для
 * данного ПК. Релей находит ПК, ожидающий этот код, и в ответ присылает
 * [PairResult] с постоянными deviceId/token, которые телефон дальше
 * подставляет в [Auth] (role = PHONE) при обычных подключениях.
 *
 * Формат QR-кода (что агент кодирует на экране, чтобы не вводить код руками):
 *   jarvis-pair://pair?code=<одноразовый код>&relay=<url релея, url-encoded>&name=<имя ПК, url-encoded>
 * Поле relay необязательно (если его нет — используется текущий адрес
 * релея, настроенный в приложении); name — просто подсказка для UI.
 */
@Serializable
@SerialName("pair_request")
data class PairRequest(
    override val requestId: String,
    /** Одноразовый код с экрана агента, например "482931". */
    val code: String,
) : JarvisMessage

@Serializable
@SerialName("pair_result")
data class PairResult(
    override val requestId: String,
    val ok: Boolean,
    /** Заполнены только при ok=true — их и сохраняет телефон. */
    val deviceId: String? = null,
    val deviceToken: String? = null,
    /** Человекочитаемое имя ПК для списка устройств, например "Рабочий ПК". */
    val deviceName: String? = null,
    /** Причина отказа при ok=false, например "код истёк" или "код не найден". */
    val reason: String? = null,
) : JarvisMessage

/** A command to run — see the [Command] hierarchy for the concrete kinds. */
@Serializable
@SerialName("command_request")
data class CommandRequest(
    override val requestId: String,
    val command: Command,
) : JarvisMessage

/** The outcome of a [CommandRequest]. */
@Serializable
@SerialName("command_result")
data class CommandResult(
    override val requestId: String,
    val status: CommandStatus,
    /** Human-readable detail, e.g. an error reason. */
    val message: String? = null,
    /** Optional structured payload, e.g. the process list. */
    val data: ResultData? = null,
) : JarvisMessage

/** One screen frame the agent sends back (screenshot or a live-stream tile). */
@Serializable
@SerialName("screen_frame")
data class ScreenFrame(
    override val requestId: String,
    val format: ImageFormat,
    /** Base64-encoded image bytes. */
    val base64: String,
    /** Размер САМОЙ картинки в base64 (после сжатия для трафика) — НЕ для пересчёта кликов. */
    val width: Int,
    val height: Int,
    /**
     * Реальное разрешение экрана ПК (до сжатия) — по нему, а не по width/height,
     * нужно пересчитывать координаты тапа в координаты клика. null — старый агент,
     * который их ещё не шлёт.
     */
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    /** Frame index when streaming; null for a one-off screenshot. */
    val seq: Int? = null,
) : JarvisMessage

/**
 * Один шаг работы vision-агента (agent_task), отправляется ПОКА задача ещё
 * выполняется — до финального CommandResult. requestId — тот же, что у
 * исходного agent_task, чтобы телефон мог связать шаги с задачей.
 */
@Serializable
@SerialName("agent_step")
data class AgentStep(
    override val requestId: String,
    val step: Int,
    /** Человекочитаемое описание действия, например "click(512, 300)" или "done: цель достигнута". */
    val description: String,
    /** Что видел агент перед этим действием — может отсутствовать, если снимок не удался. */
    val screenshotBase64: String? = null,
    val screenshotFormat: ImageFormat? = null,
    val screenshotWidth: Int? = null,
    val screenshotHeight: Int? = null,
    /** Точка, куда целится модель — в пикселях СКРИНШОТА из этого же
     *  сообщения. null — шаг без цели (type/scroll/done) или координаты
     *  не удалось сопоставить с картинкой. */
    val targetX: Int? = null,
    val targetY: Int? = null,
) : JarvisMessage

/**
 * Вопрос ИИ-агента человеку посреди задачи (agent_task).
 *
 * Зачем: раньше у агента было только два исхода неясной ситуации — угадать
 * или сдаться («цель недостижима»). Теперь он может спросить: какой из двух
 * похожих контактов нужен, какой файл отправить, можно ли нажать «Отправить».
 *
 * Поток:
 *   агент  --AgentQuestion(questionId)-->  релей  -->  телефон (карточка с кнопками)
 *   телефон --CommandRequest(AnswerQuestion(questionId, "Да"))--> релей --> агент
 * Пока ответа нет, цикл агента стоит на месте (время ожидания не съедает
 * лимит задачи). Нет ответа за [timeoutSeconds] — агент продолжает сам и
 * считает молчание отказом для необратимых действий.
 *
 * requestId — тот же, что у исходного agent_task: телефон показывает вопрос
 * в ленте именно этой задачи, как и её шаги ([AgentStep]).
 */
@Serializable
@SerialName("agent_question")
data class AgentQuestion(
    override val requestId: String,
    /** Случайный id вопроса — по нему агент узнаёт, на какой вопрос пришёл ответ. */
    val questionId: String,
    /** Текст вопроса, как его сформулировала модель. */
    val question: String,
    /**
     * Готовые варианты ответа для кнопок, например ["Да", "Нет"] или имена
     * найденных контактов. Пустой список — отвечать своими словами.
     */
    val options: List<String> = emptyList(),
    /** Сколько агент будет ждать ответа; null — агент не сообщил. */
    val timeoutSeconds: Int? = null,
) : JarvisMessage

/**
 * План многоэтапной задачи — чек-лист для телефона.
 *
 * Большую просьбу («скачай файл и разошли его Лере и Маше в WhatsApp») агент
 * сначала разбивает на подзадачи, каждая — в одной программе. Телефон
 * показывает их списком и видит, какая идёт сейчас и какие уже готовы.
 *
 * Агент присылает план ЦЕЛИКОМ при каждом изменении (подзадача началась,
 * закончилась, план перестроен). Так телефону не нужно собирать состояние из
 * кусочков: последний пришедший [AgentPlan] с этим requestId и есть правда.
 */
@Serializable
@SerialName("agent_plan")
data class AgentPlan(
    /** Тот же requestId, что у исходного agent_task. */
    override val requestId: String,
    val steps: List<PlanItem>,
    /** Номер версии плана: 1 — исходный, 2+ — перестроен после неудачи или ответа человека. */
    val revision: Int = 1,
) : JarvisMessage

/** Одна подзадача плана. */
@Serializable
data class PlanItem(
    /** Что делается, человеческим языком: «Скачать файл с сайта», «Открыть WhatsApp». */
    val title: String,
    val status: PlanItemStatus = PlanItemStatus.PENDING,
    /** Итог или причина неудачи — короткой строкой; null, пока подзадача не закончилась. */
    val note: String? = null,
)

@Serializable
enum class PlanItemStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    /** Выброшена при перестройке плана или не понадобилась. */
    @SerialName("skipped") SKIPPED,
}

/** Keep-alive both ways. Relay pings; peers answer with [Pong]. */
@Serializable
@SerialName("ping")
data class Ping(override val requestId: String) : JarvisMessage

@Serializable
@SerialName("pong")
data class Pong(override val requestId: String) : JarvisMessage

@Serializable
enum class DeviceRole {
    @SerialName("phone") PHONE,
    @SerialName("agent") AGENT,
}

@Serializable
enum class ImageFormat {
    @SerialName("jpeg") JPEG,
    @SerialName("png") PNG,
}

/**
 * The single configured [Json] instance the whole app should use.
 * The same settings must be mirrored on the Java relay (Jackson/Gson)
 * so both sides read the same discriminator and ignore unknown fields.
 */
val JarvisJson: Json = Json {
    classDiscriminator = "type"   // matches @SerialName tags above
    encodeDefaults = true          // keep protocolVersion etc. on the wire
    ignoreUnknownKeys = true       // forward-compat: new fields don't break old builds
    prettyPrint = false
}
