package com.jarvis.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the phone can ask the computer to do.
 *
 * Three layers live here on purpose:
 *  1. [NaturalLanguage] — the raw spoken phrase. Only the relay handles it:
 *     it asks the LLM to turn it into one of the concrete commands below,
 *     checks that command against the whitelist, then forwards it.
 *  2. Concrete system commands ([SetVolume], [Shutdown], ...) — the safe,
 *     enumerable actions the agent knows how to perform directly.
 *  3. [AgentTask] and the low-level primitives ([Click], [TypeText], ...) —
 *     the computer-use layer, where a vision model drives mouse/keyboard.
 *
 * A concrete command is what actually reaches the agent. Keeping the set
 * closed (sealed) is also the security model: the whitelist is a set of
 * these types, and anything the LLM proposes outside it is rejected.
 */
@Serializable
sealed interface Command {
    /** Stable key used by the whitelist and the audit log. */
    val name: String
}

// --- Layer 1: natural language (relay-only) --------------------------------

@Serializable
@SerialName("nl")
data class NaturalLanguage(val text: String) : Command {
    override val name: String get() = "nl"
}

// --- Layer 2: concrete system commands -------------------------------------

@Serializable
@SerialName("set_volume")
data class SetVolume(
    /** 0..100 */
    val level: Int,
) : Command {
    override val name: String get() = "set_volume"
}

@Serializable
@SerialName("shutdown")
data class Shutdown(
    /** Delay before powering off; 0 = now. */
    val delaySeconds: Int = 0,
) : Command {
    override val name: String get() = "shutdown"
}

@Serializable
@SerialName("reboot")
data class Reboot(val delaySeconds: Int = 0) : Command {
    override val name: String get() = "reboot"
}

@Serializable
@SerialName("lock_screen")
data object LockScreen : Command {
    override val name: String get() = "lock_screen"
}

@Serializable
@SerialName("kill_process")
data class KillProcess(val processName: String) : Command {
    override val name: String get() = "kill_process"
}

@Serializable
@SerialName("list_processes")
data object ListProcesses : Command {
    override val name: String get() = "list_processes"
}

@Serializable
@SerialName("open_url")
data class OpenUrl(val url: String) : Command {
    override val name: String get() = "open_url"
}

@Serializable
@SerialName("open_app")
data class OpenApp(val appName: String) : Command {
    override val name: String get() = "open_app"
}

@Serializable
@SerialName("screenshot")
data object Screenshot : Command {
    override val name: String get() = "screenshot"
}

// --- Layer 3: computer-use / vision agent ----------------------------------

/**
 * A high-level goal for the vision agent, e.g. "поставь лайк на этом видео".
 * The agent loops: capture screen/DOM -> ask the model for the next
 * [Click]/[TypeText]/[Scroll] -> execute -> re-capture, until done.
 */
@Serializable
@SerialName("agent_task")
data class AgentTask(
    val goal: String,
    /** Restrict the agent to the browser (DOM, reliable) or the whole screen. */
    val scope: AgentScope = AgentScope.BROWSER,
    /** Safety cap on how many steps the loop may take. */
    val maxSteps: Int = 15,
) : Command {
    override val name: String get() = "agent_task"
}

@Serializable
enum class AgentScope {
    @SerialName("browser") BROWSER,
    @SerialName("screen") SCREEN,
}

/** Low-level input primitives the agent executes via java.awt.Robot. */
@Serializable
@SerialName("click")
data class Click(val x: Int, val y: Int) : Command {
    override val name: String get() = "click"
}

@Serializable
@SerialName("type_text")
data class TypeText(val text: String) : Command {
    override val name: String get() = "type_text"
}

@Serializable
@SerialName("scroll")
data class Scroll(val dx: Int = 0, val dy: Int) : Command {
    override val name: String get() = "scroll"
}

// --- Layer 4: live screen streaming (reuses ScreenFrame.seq to tag stream frames) ---

@Serializable
@SerialName("start_screen_stream")
data class StartScreenStream(val fps: Int, val maxWidth: Int) : Command {
    override val name: String get() = "start_screen_stream"
}

@Serializable
@SerialName("stop_screen_stream")
data object StopScreenStream : Command {
    override val name: String get() = "stop_screen_stream"
}

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

// --- Layer 6: передача файлов (те же папки, что уже разрешены open_app) ----

@Serializable
@SerialName("list_files")
data class ListFiles(val folder: String) : Command {
    override val name: String get() = "list_files"
}

@Serializable
@SerialName("send_file_to_phone")
data class SendFileToPhone(val folder: String, val fileName: String) : Command {
    override val name: String get() = "send_file_to_phone"
}

@Serializable
@SerialName("send_file_to_pc")
data class SendFileToPc(val folder: String, val fileName: String, val base64: String) : Command {
    override val name: String get() = "send_file_to_pc"
}

// --- Layer 7: управление уже запущенной задачей ----------------------------

/**
 * Прервать выполняющийся [AgentTask]. Без параметров: одновременно всё равно
 * выполняется только одна задача (busyGoal в CommandExecutor), поэтому
 * указывать, какую именно, не нужно.
 *
 * ВАЖНО: агент обязан обрабатывать её вне потока, занятого задачей — см.
 * RelayClient.agentPool, иначе команда не будет прочитана до конца задачи.
 */
@Serializable
@SerialName("cancel_task")
data object CancelTask : Command {
    override val name: String get() = "cancel_task"
}

/**
 * Ответ человека на [AgentQuestion]. Команда, а не отдельный тип сообщения —
 * намеренно: так ответ проходит тот же путь, что и любая команда телефона,
 * то есть через белый список и журнал аудита релея.
 *
 * Как и [CancelTask], агент обрабатывает её прямо в потоке чтения сокета:
 * поток задачи в этот момент занят — он как раз ждёт этот ответ.
 */
/**
 * Включить или выключить то, что показывается на экране ПК во время ИИ-задачи.
 *
 * [option] — строго из короткого списка, который знает агент:
 *  - "xray" — «рентген»: рамки и номера элементов окна, как их видит модель;
 *  - "plan" — панель «План и мысли».
 * Список закрытый намеренно: команда меняет настройки агента, и через неё
 * нельзя дотянуться, например, до подтверждения опасных команд.
 */
@Serializable
@SerialName("set_display_option")
data class SetDisplayOption(val option: String, val enabled: Boolean) : Command {
    override val name: String get() = "set_display_option"
}

@Serializable
@SerialName("answer_question")
data class AnswerQuestion(
    /** id из [AgentQuestion.questionId]. */
    val questionId: String,
    /** Выбранный вариант или свой текст. */
    val answer: String,
) : Command {
    override val name: String get() = "answer_question"
}

// --- Layer 8: быстрые действия и составные просьбы --------------------------

/**
 * Мгновенное действие: пауза/продолжить, следующий/предыдущий трек, громкость
 * на N процентов относительно текущей, свернуть все окна.
 *
 * [action] — строго из [ACTIONS]; [amount] нужен только для "volume_delta"
 * (со знаком: +20 — громче, -20 — тише). Список закрытый: агент выполняет
 * только известные действия, как и с [SetDisplayOption].
 */
@Serializable
@SerialName("quick_action")
data class QuickAction(val action: String, val amount: Int = 0) : Command {
    override val name: String get() = "quick_action"

    companion object {
        val ACTIONS = setOf("play_pause", "next_track", "prev_track", "volume_delta", "minimize_all")
    }
}

/**
 * Несколько простых команд из одной фразы: «сделай потише и открой ютуб».
 * Релей собирает её, только если КАЖДАЯ часть — простая команда (не задача
 * ИИ-агенту); агент выполняет по очереди и отвечает одним итогом. Белый список
 * проверяет каждую команду внутри.
 */
@Serializable
@SerialName("sequence")
data class Sequence(val commands: List<Command>) : Command {
    override val name: String get() = "sequence"

    companion object {
        const val MAX = 4
    }
}

// --- Layer 9: проекты (git) ---------------------------------------------------

/**
 * Список разрешённых git-репозиториев на ПК и их состояние — для экрана
 * «Проекты». Только чтение: ответ — [RepoList]. Что делать с проектом
 * (pull, коммит, правка кода), телефон просит обычной задачей агенту.
 */
@Serializable
@SerialName("dev_repos")
data object DevRepos : Command {
    override val name: String get() = "dev_repos"
}
