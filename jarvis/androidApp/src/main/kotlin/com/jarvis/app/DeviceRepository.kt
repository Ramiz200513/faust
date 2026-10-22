package com.jarvis.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.deviceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "devices")

/** Один привязанный ПК: то, что телефон получил при паринге и хранит долговременно. */
@Serializable
data class PairedDevice(
    /** Совпадает с deviceId агента — им же телефон представляется в Auth. */
    val id: String,
    /** Имя для списка на экране, например "Рабочий ПК". */
    val name: String,
    /** Постоянный токен, выданный релеем при паринге. */
    val token: String,
    /**
     * Адрес релея, через который этот ПК был привязан. Храним отдельно для
     * каждого устройства (а не один общий), потому что разные ПК могут
     * сидеть за разными релеями/сетями — при переключении активного
     * устройства телефон должен стучаться именно туда, где оно живёт.
     */
    val relayUrl: String,
    /**
     * MAC-адрес сетевой карты ПК — для Wake-on-LAN. null у ПК, привязанных
     * до появления этого поля (kotlinx.serialization подставит null при
     * чтении старой сохранённой записи) или привязанных по ручному коду
     * без QR — для них кнопка "разбудить" просто неактивна.
     */
    val mac: String? = null,
)

/**
 * Список привязанных ПК и id активного — того, на который уходят команды из
 * чата. Хранится отдельно от [SettingsRepository] (там — адрес релея), чтобы
 * не путать «куда стучимся» и «за какой ПК стучимся».
 */
class DeviceRepository(private val context: Context) {

    private object Keys {
        val DEVICES = stringPreferencesKey("devices_json")
        val ACTIVE_ID = stringPreferencesKey("active_device_id")
    }

    // Preferences DataStore хранит только примитивы, поэтому список пишем
    // одной JSON-строкой — так же, как relayUrl в SettingsRepository.
    val devices: Flow<List<PairedDevice>> = context.deviceDataStore.data.map { prefs ->
        val raw = prefs[Keys.DEVICES] ?: return@map emptyList()
        runCatching { Json.decodeFromString<List<PairedDevice>>(raw) }.getOrDefault(emptyList())
    }

    val activeDeviceId: Flow<String?> = context.deviceDataStore.data.map { it[Keys.ACTIVE_ID] }

    /** Добавить новый ПК (или обновить токен/имя, если id уже есть) и сделать его активным. */
    suspend fun addAndActivate(device: PairedDevice) {
        context.deviceDataStore.edit { prefs ->
            val current = prefs[Keys.DEVICES]
                ?.let { runCatching { Json.decodeFromString<List<PairedDevice>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = current.filterNot { it.id == device.id } + device
            prefs[Keys.DEVICES] = Json.encodeToString(updated)
            prefs[Keys.ACTIVE_ID] = device.id
        }
    }

    suspend fun remove(deviceId: String) {
        context.deviceDataStore.edit { prefs ->
            val current = prefs[Keys.DEVICES]
                ?.let { runCatching { Json.decodeFromString<List<PairedDevice>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            prefs[Keys.DEVICES] = Json.encodeToString(current.filterNot { it.id == deviceId })
            // Если удалили активное устройство — сбрасываем выбор, чтобы не
            // ссылаться на несуществующий ПК.
            if (prefs[Keys.ACTIVE_ID] == deviceId) prefs.remove(Keys.ACTIVE_ID)
        }
    }

    suspend fun setActive(deviceId: String) {
        context.deviceDataStore.edit { prefs -> prefs[Keys.ACTIVE_ID] = deviceId }
    }
}
