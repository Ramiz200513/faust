package com.jarvis.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Раскодировать кадр (base64 -> JPEG -> [ImageBitmap]) ВНЕ главного потока.
 *
 * Раньше это делалось прямо в `remember { ... }`, то есть в композиции — а она
 * идёт на главном потоке. Для одиночного скриншота в чате это подтормаживание
 * при прокрутке, а для живого экрана — пять декодирований JPEG в секунду на
 * том же потоке, который рисует интерфейс. Отсюда рывки, которых по нагрузке
 * на ПК быть не должно: узким местом был телефон, а не агент.
 *
 * Пока новый кадр декодируется, на экране ОСТАЁТСЯ ПРЕДЫДУЩИЙ. Это важно:
 * если просто отдавать null до готовности, при 5 кадрах в секунду картинка
 * мигала бы пустотой между кадрами — стало бы хуже, чем было.
 *
 * @return кадр для показа или null, пока не раскодирован самый первый.
 */
@Composable
fun rememberDecodedFrame(base64: String?): ImageBitmap? {
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(base64) {
        if (base64 == null) {
            shown = null
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.Default) {
            // Битый/обрезанный кадр — не повод ронять экран: просто оставляем
            // на месте предыдущий и ждём следующего.
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) shown = decoded
    }

    return shown
}
