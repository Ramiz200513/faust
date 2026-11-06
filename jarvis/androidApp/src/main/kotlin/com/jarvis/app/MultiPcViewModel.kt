package com.jarvis.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.protocol.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Состояние отправки команды одному ПК из списка «Мульти-ПК». */
enum class BroadcastState { IDLE, SENDING, OK, ERROR }

/** Одна строка экрана «Мульти-ПК»: привязанный ПК + отмечен ли он + результат последней рассылки. */
data class MultiPcRow(
    val device: PairedDevice,
    val selected: Boolean,
    val state: BroadcastState = BroadcastState.IDLE,
    val detail: String? = null,
    /** Что ИИ-агент этого ПК делает прямо сейчас (последний шаг) — только во время задачи. */
    val step: String? = null,
    /** Вопрос агента этого ПК, который ждёт ответа, — с кнопками прямо в строке. */
    val question: AgentQuestion? = null,
)

/** Перенос файла с ПК на ПК с этого экрана: что сейчас происходит. */
data class TransferUi(val running: Boolean, val text: String, val ok: Boolean? = null)

/**
 * Состояние экрана «Мульти-ПК»: выбор нескольких уже привязанных ПК и
 * рассылка одной и той же быстрой команды всем выбранным сразу.
 *
 * В отличие от чата (который всегда говорит с ОДНИМ активным ПК через
 * постоянный JarvisClient), тут на каждый выбранный ПК открывается своё
 * короткое соединение через RelayPairing.sendCommand — параллельно,
 * независимо друг от друга: зависший или офлайн-ПК не блокирует остальные,
 * у каждого свой результат в списке.
 *
 * Кроме быстрых команд — ИИ-задача на все отмеченные ПК сразу ([runTask]):
 * у каждого ПК свой агент, свой ход и свои вопросы, а в строке видно, что
 * он делает сейчас. И перенос файла с одного ПК на другой ([transfer]).
 */
class MultiPcViewModel(app: Application) : AndroidViewModel(app) {

    private val devicesRepo = DeviceRepository(app)

    private val _rows = MutableStateFlow<List<MultiPcRow>>(emptyList())
    val rows: StateFlow<List<MultiPcRow>> = _rows.asStateFlow()

    private val _transfer = MutableStateFlow<TransferUi?>(null)
    val transfer: StateFlow<TransferUi?> = _transfer.asStateFlow()

    /** Связи с ПК, на которых сейчас идёт ИИ-задача: id ПК -> связь (по ней — ответы). */
    private val links = ConcurrentHashMap<String, PcLink>()

    init {
        viewModelScope.launch {
            devicesRepo.devices.collect { list ->
                // Список ПК мог обновиться (добавили/удалили) — не теряем отметки
                // и результаты уже выбранных строк.
                val prevById = _rows.value.associateBy { it.device.id }
                _rows.value = list.map { d ->
                    prevById[d.id]?.copy(device = d) ?: MultiPcRow(device = d, selected = false)
                }
            }
        }
    }

    fun toggle(deviceId: String) {
        _rows.value = _rows.value.map {
            if (it.device.id == deviceId) it.copy(selected = !it.selected) else it
        }
    }

    /** Отметить/снять все разом (кнопка «Все»/«Снять» в шапке). */
    fun selectAll(select: Boolean) {
        _rows.value = _rows.value.map { it.copy(selected = select) }
    }

    private fun updateRow(deviceId: String, transform: (MultiPcRow) -> MultiPcRow) =
        _rows.update { list -> list.map { if (it.device.id == deviceId) transform(it) else it } }

    /** Итог команды -> состояние и подпись строки. */
    private fun rowOutcome(outcome: CommandOutcome): Pair<BroadcastState, String> = when (outcome) {
        is CommandOutcome.Success -> {
            val ok = outcome.result.status == CommandStatus.OK
            (if (ok) BroadcastState.OK else BroadcastState.ERROR) to
                (outcome.result.message ?: if (ok) "готово" else outcome.result.status.name)
        }
        is CommandOutcome.Rejected -> BroadcastState.ERROR to outcome.reason
        is CommandOutcome.Failed -> BroadcastState.ERROR to outcome.message
    }

    /** Отправить команду всем отмеченным ПК параллельно, независимо друг от друга. */
    fun broadcast(command: Command) {
        val targets = _rows.value.filter { it.selected }
        if (targets.isEmpty()) return

        _rows.value = _rows.value.map {
            if (it.selected) it.copy(state = BroadcastState.SENDING, detail = null, step = null, question = null) else it
        }

        viewModelScope.launch {
            val outcomes = targets
                .map { row -> async { row.device.id to RelayPairing.sendCommand(row.device, command) } }
                .awaitAll()
                .toMap()

            _rows.value = _rows.value.map { row ->
                val outcome = outcomes[row.device.id] ?: return@map row
                val (state, detail) = rowOutcome(outcome)
                row.copy(state = state, detail = detail)
            }
        }
    }

    /**
     * Одна просьба — на все отмеченные ПК, как если бы её сказали в чате
     * каждого: «открой расписание», «найди в загрузках отчёт и открой». Фраза
     * разбирается релеем отдельно для каждого ПК, у каждого свой агент.
     *
     * Каждый ПК подтверждает ИИ-задачу у себя (как и всегда) — разослать
     * задачу на чужой компьютер без ведома того, кто за ним сидит, нельзя.
     */
    fun runTask(text: String) {
        val phrase = text.trim()
        val targets = _rows.value.filter { it.selected }
        if (phrase.isEmpty() || targets.isEmpty()) return
        for (row in targets) {
            val id = row.device.id
            updateRow(id) { it.copy(state = BroadcastState.SENDING, detail = null, step = "отправляю…", question = null) }
            val link = PcLink(row.device)
            links[id] = link
            viewModelScope.launch {
                val outcome = link.run(NaturalLanguage(phrase), TASK_TIMEOUT_MS) { msg -> onTaskEvent(id, msg) }
                links.remove(id)
                val (state, detail) = rowOutcome(outcome)
                updateRow(id) { it.copy(state = state, detail = detail, step = null, question = null) }
            }
        }
    }

    /** Шаг, план или вопрос от агента ПК [deviceId] по ходу задачи. */
    private fun onTaskEvent(deviceId: String, msg: JarvisMessage) {
        when (msg) {
            // Только первая строка: вторая — рассуждение модели, в строку списка не влезет.
            is AgentStep -> updateRow(deviceId) { it.copy(step = "шаг ${msg.step}: ${msg.description.lineSequence().first()}") }
            is AgentPlan -> {
                val running = msg.steps.firstOrNull { it.status == PlanItemStatus.RUNNING }
                val done = msg.steps.count { it.status == PlanItemStatus.DONE }
                updateRow(deviceId) {
                    it.copy(step = "план $done/${msg.steps.size}" + (running?.let { r -> ": ${r.title}" } ?: ""))
                }
            }
            is AgentQuestion -> updateRow(deviceId) { it.copy(question = msg) }
            // Ответ на вопрос принят (или уже не нужен) — кнопки убираем.
            is CommandResult -> updateRow(deviceId) { it.copy(question = null) }
            else -> {}
        }
    }

    /** Ответ на вопрос агента ПК [deviceId] — по связи именно с этим ПК. */
    fun answer(deviceId: String, answer: String) {
        val row = _rows.value.firstOrNull { it.device.id == deviceId } ?: return
        val q = row.question ?: return
        val sent = links[deviceId]?.send(AnswerQuestion(q.questionId, answer)) == true
        updateRow(deviceId) {
            it.copy(question = null, step = if (sent) "ответ «$answer» отправлен" else "задача уже закончилась")
        }
    }

    /** «Стоп» на все ПК, где идёт задача с этого экрана. */
    fun cancelTasks() {
        links.values.forEach { it.send(CancelTask) }
    }

    /** Перенести файл, похожий на [query], с [fromId] на [toId] (см. [PcTransfer]). */
    fun transfer(fromId: String, toId: String, query: String) {
        val devices = _rows.value.map { it.device }
        val from = devices.firstOrNull { it.id == fromId } ?: return
        val to = devices.firstOrNull { it.id == toId } ?: return
        if (from.id == to.id || query.isBlank()) return
        _transfer.value = TransferUi(running = true, text = "начинаю…")
        viewModelScope.launch {
            val result = PcTransfer.run(from, to, query.trim(), onNote = { _transfer.value = TransferUi(true, it) })
            _transfer.value = TransferUi(running = false, text = result.text, ok = result.ok)
        }
    }

    fun dismissTransfer() {
        if (_transfer.value?.running != true) _transfer.value = null
    }

    private companion object {
        /** Итог ИИ-задачи ждём с запасом — свой предел времени есть у самого агента. */
        const val TASK_TIMEOUT_MS = 15 * 60_000L
    }
}
