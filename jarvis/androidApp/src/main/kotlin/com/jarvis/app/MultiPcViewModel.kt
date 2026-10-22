package com.jarvis.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.protocol.Command
import com.jarvis.protocol.CommandStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние отправки команды одному ПК из списка «Мульти-ПК». */
enum class BroadcastState { IDLE, SENDING, OK, ERROR }

/** Одна строка экрана «Мульти-ПК»: привязанный ПК + отмечен ли он + результат последней рассылки. */
data class MultiPcRow(
    val device: PairedDevice,
    val selected: Boolean,
    val state: BroadcastState = BroadcastState.IDLE,
    val detail: String? = null,
)

/**
 * Состояние экрана «Мульти-ПК»: выбор нескольких уже привязанных ПК и
 * рассылка одной и той же быстрой команды всем выбранным сразу.
 *
 * В отличие от чата (который всегда говорит с ОДНИМ активным ПК через
 * постоянный JarvisClient), тут на каждый выбранный ПК открывается своё
 * короткое соединение через RelayPairing.sendCommand — параллельно,
 * независимо друг от друга: зависший или офлайн-ПК не блокирует остальные,
 * у каждого свой результат в списке.
 */
class MultiPcViewModel(app: Application) : AndroidViewModel(app) {

    private val devicesRepo = DeviceRepository(app)

    private val _rows = MutableStateFlow<List<MultiPcRow>>(emptyList())
    val rows: StateFlow<List<MultiPcRow>> = _rows.asStateFlow()

    init {
        viewModelScope.launch {
            devicesRepo.devices.collect { list ->
                // Список ПК мог обновиться (добавили/удалили) — не теряем отметки
                // и результаты уже выбранных строк.
                val prevById = _rows.value.associateBy { it.device.id }
                _rows.value = list.map { d ->
                    val prev = prevById[d.id]
                    MultiPcRow(
                        device = d,
                        selected = prev?.selected ?: false,
                        state = prev?.state ?: BroadcastState.IDLE,
                        detail = prev?.detail,
                    )
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

    /** Отправить команду всем отмеченным ПК параллельно, независимо друг от друга. */
    fun broadcast(command: Command) {
        val targets = _rows.value.filter { it.selected }
        if (targets.isEmpty()) return

        _rows.value = _rows.value.map {
            if (it.selected) it.copy(state = BroadcastState.SENDING, detail = null) else it
        }

        viewModelScope.launch {
            val outcomes = targets
                .map { row -> async { row.device.id to RelayPairing.sendCommand(row.device, command) } }
                .awaitAll()
                .toMap()

            _rows.value = _rows.value.map { row ->
                val outcome = outcomes[row.device.id] ?: return@map row
                val (state, detail) = when (outcome) {
                    is CommandOutcome.Success -> {
                        val ok = outcome.result.status == CommandStatus.OK
                        (if (ok) BroadcastState.OK else BroadcastState.ERROR) to
                            (outcome.result.message ?: if (ok) "готово" else outcome.result.status.name)
                    }
                    is CommandOutcome.Rejected -> BroadcastState.ERROR to outcome.reason
                    is CommandOutcome.Failed -> BroadcastState.ERROR to outcome.message
                }
                row.copy(state = state, detail = detail)
            }
        }
    }
}
