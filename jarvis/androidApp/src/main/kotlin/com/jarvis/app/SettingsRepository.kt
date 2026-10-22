package com.jarvis.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Один DataStore на всё приложение. Делегат `preferencesDataStore` сам создаёт
// файл settings.preferences_pb в каталоге приложения и переиспользует его.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Запоминает последний использованный адрес релея между запусками —
 * подсказка для ручного ввода при привязке (когда его нет в самом QR).
 * Постоянные токены здесь больше не хранятся — они живут в [DeviceRepository]
 * привязанным к конкретному ПК ([PairedDevice.token]).
 *
 * Читать: собирать [relayUrl] (Flow — обновится, если значение поменяли).
 * Писать: [saveRelayUrl] в корутине.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val RELAY_URL = stringPreferencesKey("relay_url")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val XRAY_ENABLED = booleanPreferencesKey("pc_xray_enabled")
        val PLAN_PANEL_ENABLED = booleanPreferencesKey("pc_plan_panel_enabled")
    }

    /** «Рентген» на экране ПК — рамки элементов, которые видит ИИ. По умолчанию включён. */
    val xrayEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.XRAY_ENABLED] ?: true }

    /** Панель «План и мысли» на экране ПК. По умолчанию включена. */
    val planPanelEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.PLAN_PANEL_ENABLED] ?: true }

    suspend fun saveXrayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.XRAY_ENABLED] = enabled }
    }

    suspend fun savePlanPanelEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.PLAN_PANEL_ENABLED] = enabled }
    }

    val relayUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.RELAY_URL] ?: DEFAULT_URL }

    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.WAKE_WORD_ENABLED] ?: false }

    suspend fun saveRelayUrl(relayUrl: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RELAY_URL] = relayUrl.trim() }
    }

    suspend fun saveWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    companion object {
        const val DEFAULT_URL = "ws://10.0.2.2:8080/ws" // 10.0.2.2 = хост из эмулятора
    }
}
