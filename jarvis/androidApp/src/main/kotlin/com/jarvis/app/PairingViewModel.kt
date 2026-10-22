package com.jarvis.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Экран первого запуска: устройств ещё нет (см. [DeviceRepository.devices]),
 * привязываем первый ПК — сканом QR или кодом+адресом вручную. Как только
 * привязка пройдёт, [DeviceRepository] получит новую запись и [MainActivity]
 * сам переключится на чат — этот экран ни о чём его не уведомляет напрямую.
 *
 * Обмен кода на токен и его сохранение — общий с экраном "Устройства" код
 * в [PairingController], тут только его обёртка под нужды этого экрана.
 */
class PairingViewModel(app: Application) : AndroidViewModel(app) {

    private val devicesRepo = DeviceRepository(app)
    private val controller = PairingController(viewModelScope, devicesRepo)

    val pairing: StateFlow<PairingUiState> = controller.pairing

    fun pairByQr(raw: String, fallbackRelayUrl: String) = controller.pairByQr(raw, fallbackRelayUrl)

    fun pairByCode(code: String, relayUrl: String) = controller.pairByCode(code, relayUrl)

    fun resetPairingState() = controller.resetPairingState()
}
