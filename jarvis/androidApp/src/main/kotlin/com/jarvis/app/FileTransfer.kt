package com.jarvis.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64

/** Тот же лимит, что и на агенте (FileCatalog.MAX_FILE_BYTES) — проверяем на телефоне заранее,
 *  чтобы не гнать base64 через сеть только затем, чтобы агент его отверг. */
const val MAX_TRANSFER_BYTES = 5L * 1024 * 1024

/**
 * Читает файл, выбранный через системный пикер (content:// Uri): имя + base64.
 * null — не удалось прочитать или файл больше лимита.
 */
fun readPickedFile(context: Context, uri: Uri): Pair<String, String>? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
    } ?: uri.lastPathSegment ?: "file"

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > MAX_TRANSFER_BYTES) return null

    return name to Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * Сохраняет файл, пришедший с ПК, в публичную папку "Загрузки" телефона через MediaStore
 * (без разрешения на запись во внешнее хранилище).
 *
 * MediaStore.Downloads появился только в Android 10 (API 29), а minSdk проекта — 24,
 * поэтому версию проверяем явно: без этой проверки на Android 7-9 был бы не отказ,
 * а падение приложения при обращении к EXTERNAL_CONTENT_URI.
 */
fun saveToPhoneDownloads(context: Context, fileName: String, bytes: ByteArray): Boolean = try {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        false
    } else {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false
        else {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            true
        }
    }
} catch (e: Exception) {
    false
}
