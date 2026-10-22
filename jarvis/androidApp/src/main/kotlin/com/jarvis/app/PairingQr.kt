package com.jarvis.app

import android.net.Uri

/** Что удалось вытащить из QR-кода на экране агента. */
data class PairingQrPayload(
    val code: String,
    /** Если агент зашил в QR адрес релея — используем его вместо текущих настроек. */
    val relayUrl: String? = null,
    val nameHint: String? = null,
    /** MAC-адрес ПК (для Wake-on-LAN), если агент зашил его в QR. */
    val mac: String? = null,
)

/**
 * Разбирает содержимое QR-кода агента. Ожидаемый формат (см. KDoc у
 * [com.jarvis.protocol.PairRequest] в :shared):
 *   jarvis-pair://pair?code=482931&relay=ws%3A%2F%2F192.168.0.5%3A8080%2Fws&name=...
 *
 * Если строка не похожа на этот формат, но целиком состоит из цифр —
 * считаем, что это просто код, отсканированный с обычной картинки-«шпаргалки»
 * (relay при этом остаётся тем, что уже настроен в приложении).
 * Любой другой мусор — null, паринг не начинаем.
 */
object PairingQr {
    private const val SCHEME = "jarvis-pair"

    fun parse(raw: String): PairingQrPayload? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.all { it.isDigit() }) return PairingQrPayload(code = trimmed)

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME) return null
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        if (code.isEmpty()) return null

        return PairingQrPayload(
            code = code,
            relayUrl = uri.getQueryParameter("relay")?.trim()?.takeIf { it.isNotEmpty() },
            nameHint = uri.getQueryParameter("name")?.trim()?.takeIf { it.isNotEmpty() },
            mac = uri.getQueryParameter("mac")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
