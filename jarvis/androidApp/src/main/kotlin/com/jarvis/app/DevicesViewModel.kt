package com.jarvis.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Одна строка списка устройств: привязанный ПК + выбран ли он активным + статус. */
data class DeviceUi(
    val device: PairedDevice,
    val isActive: Boolean,
    val status: DeviceOnlineStatus = DeviceOnlineStatus.UNKNOWN,
    val checking: Boolean = false,
)

/**
 * Состояние экрана «Устройства»: список привязанных ПК с их онлайн-статусом,
 * выбор активного и сама привязка нового (по коду или по QR).
 */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val devicesRepo = DeviceRepository(app)
    private val settings = SettingsRepository(app)
    private val pairingController = PairingController(viewModelScope, devicesRepo)

    private val _devices = MutableStateFlow<List<DeviceUi>>(emptyList())
    val devices: StateFlow<List<DeviceUi>> = _devices.asStateFlow()

    val pairing: StateFlow<PairingUiState> = pairingController.pairing

    init {
        viewModelScope.launch {
            combine(devicesRepo.devices, devicesRepo.activeDeviceId) { list, activeId ->
                list.map { d -> DeviceUi(device = d, isActive = d.id == activeId) }
            }.collect { freshList ->
                // При обновлении списка (добавили/удалили устройство) не теряем уже
                // известные статусы онлайн — иначе весь список на секунду мигал бы "неизвестно".
                val prevStatuses = _devices.value.associate { it.device.id to it.status }
                _devices.value = freshList.map {
                    it.copy(status = prevStatuses[it.device.id] ?: DeviceOnlineStatus.UNKNOWN)
                }
                refreshStatuses()
            }
        }
    }

    /** Опросить релей по каждому устройству параллельно. Сетевые ошибки — это просто UNKNOWN, не крах. */
    fun refreshStatuses() {
        val toCheck = _devices.value
        if (toCheck.isEmpty()) return
        viewModelScope.launch {
            _devices.value = _devices.value.map { it.copy(checking = true) }
            val results = toCheck
                .map { ui -> async { ui.device.id to RelayPairing.checkStatus(ui.device) } }
                .awaitAll()
                .toMap()
            _devices.value = _devices.value.map {
                it.copy(status = results[it.device.id] ?: it.status, checking = false)
            }
        }
    }

    fun selectActive(deviceId: String) {
        viewModelScope.launch { devicesRepo.setActive(deviceId) }
    }

    fun remove(deviceId: String) {
        viewModelScope.launch { devicesRepo.remove(deviceId) }
    }

    /** Привязать по коду, введённому руками — на текущем адресе релея из настроек. */
    fun pairByCode(code: String) {
        viewModelScope.launch { pairingController.pairByCode(code, settings.relayUrl.first()) }
    }

    /** Привязать по содержимому QR: там может быть просто код или код+адрес релея. */
    fun pairByQr(raw: String) {
        viewModelScope.launch { pairingController.pairByQr(raw, settings.relayUrl.first()) }
    }

    /** Закрыть диалог привязки / сбросить ошибку перед новой попыткой. */
    fun resetPairingState() = pairingController.resetPairingState()
}
