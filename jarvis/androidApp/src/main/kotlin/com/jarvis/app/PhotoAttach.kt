package com.jarvis.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Фото для задачи «телефон как глаза»: снимок доски или конспекта, который
 * уходит на ПК вместе с просьбой (PhotoTask).
 *
 * Зачем уменьшать: снимок камеры — 4000×3000 и 3–6 МБ. Столько не нужно ни
 * модели, которая читает текст (ей хватает ~1600 px по большей стороне), ни
 * релею (лимит сообщения), ни агенту (лимит файла 5 МБ). После уменьшения и
 * JPEG 85 % выходит 250–500 КБ — текст на доске при этом читается.
 */
object PhotoAttach {

    /** Больше этого по большей стороне фото не отправляем. */
    private const val MAX_SIDE = 1600

    /**
     * Куда камера сохраняет новый снимок. Файл во внутреннем кэше приложения,
     * наружу он отдаётся только камере — через FileProvider (см. манифест), а не
     * прямым путём: прямые пути к файлам другим приложениям Android запрещает.
     */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        // Старые снимки больше не нужны — уже уехали на ПК или отменены.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    }

    /**
     * Прочитать фото (с камеры или из галереи), повернуть как надо, уменьшить и
     * вернуть JPEG в base64; null — не получилось прочитать.
     */
    suspend fun encodeForPc(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            // 1. Сначала только размеры — чтобы не держать в памяти 12-мегапиксельную картинку целиком.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

            // 2. Читаем уже уменьшенной в sample раз (дёшево по памяти).
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null

            // 3. Камеры пишут снимок «как держали сенсор» и кладут поворот в EXIF.
            //    Без учёта поворота доска приехала бы на ПК боком — и модель
            //    читала бы текст хуже.
            val degrees = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
            val matrix = Matrix().apply {
                postScale(scale, scale)
                if (degrees != 0) postRotate(degrees.toFloat())
            }
            val ready = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

            val out = ByteArrayOutputStream()
            ready.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun exifRotation(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
