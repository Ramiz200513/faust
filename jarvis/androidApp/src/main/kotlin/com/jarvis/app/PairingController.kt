package com.jarvis.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние попытки привязки — одно на весь экран, где идёт привязка. */
sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object InProgress : PairingUiState
    data class Error(val message: String) : PairingUiState
    data object Done : PairingUiState
}

/**
 * Общая логика "обменять код/QR на постоянный токен и сохранить ПК в
 * [DeviceRepository]". Используется на экране первого запуска ([PairingViewModel])
 * и на экране "Устройства" ([DevicesViewModel]) — сам обмен с релеем
 * ([RelayPairing.pair]) и сохранение результата не должны дублироваться.
 *
 * [scope] — корутин-скоп вызывающей ViewModel (обычно viewModelScope): этим
 * контроллер живёт не дольше своего владельца, отдельного жизненного цикла
 * у него нет.
 */
class PairingController(
    private val scope: CoroutineScope,
    private val devicesRepo: DeviceRepository,
) {
    private val _pairing = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    /** Привязать по коду, введённому руками — адрес релея тоже задаётся явно. */
    fun pairByCode(code: String, relayUrl: String) {
        scope.launch { pair(code, relayUrl) }
    }

    /**
     * Привязать по содержимому QR. Если в самом QR есть адрес релея —
     * используем его, иначе берём [fallbackRelayUrl] (текущий/последний
     * известный адрес).
     */
    fun pairByQr(raw: String, fallbackRelayUrl: String) {
        val payload = PairingQr.parse(raw)
        if (payload == null) {
            _pairing.value = PairingUiState.Error("не распознан QR-код")
            return
        }
        scope.launch { pair(payload.code, payload.relayUrl ?: fallbackRelayUrl, payload.mac) }
    }

    private suspend fun pair(code: String, relayUrl: String, mac: String? = null) {
        val trimmedCode = code.trim()
        val trimmedUrl = relayUrl.trim()
        if (trimmedCode.isEmpty()) {
            _pairing.value = PairingUiState.Error("введите код")
            return
        }
        if (trimmedUrl.isEmpty()) {
            _pairing.value = PairingUiState.Error("укажите адрес релея")
            return
        }
        _pairing.value = PairingUiState.InProgress
        when (val outcome = RelayPairing.pair(trimmedUrl, trimmedCode)) {
            is PairOutcome.Success -> {
                devicesRepo.addAndActivate(outcome.device.copy(mac = mac))
                _pairing.value = PairingUiState.Done
            }
            is PairOutcome.Rejected -> _pairing.value = PairingUiState.Error(outcome.reason)
            is PairOutcome.Failed -> _pairing.value = PairingUiState.Error(outcome.message)
        }
    }

    /** Закрыть диалог/экран привязки или сбросить ошибку перед новой попыткой. */
    fun resetPairingState() {
        _pairing.value = PairingUiState.Idle
    }
}
