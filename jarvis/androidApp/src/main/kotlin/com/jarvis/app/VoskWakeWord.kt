package com.jarvis.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Wake-word и распознавание команды на одном офлайновом движке Vosk.
 *
 * Идея простая: Vosk непрерывно превращает звук в текст, а мы ищем в этом
 * тексте слово «фауст». Отдельного движка слова-триггера нет — и вместе с
 * ним нет всей возни с передачей микрофона от одного движка другому.
 *
 * Два поддержанных сценария:
 *  1) «фауст открой ютуб» — одной фразой; всё после триггера = команда.
 *  2) «фауст» … пауза … «открой ютуб» — после триггера взводим окно на
 *     [ARMED_WINDOW_MS], и следующая фраза считается командой.
 *
 * Класс ничего не знает ни про сервис, ни про UI — только колбэки.
 * Все колбэки Vosk приходят на главном потоке, дополнительной синхронизации
 * не требуется.
 */
class VoskWakeWord(
    private val context: Context,
    private val onPhrase: (String) -> Unit,
    private val onStateChange: (WakeState) -> Unit,
    private val onNote: (String) -> Unit,
) : RecognitionListener {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    /** Хотим ли слушать. Нужен, потому что распаковка модели асинхронная:
     *  пользователь может выключить wake-word, пока она ещё распаковывается. */
    private var wantRunning = false

    /** До какого момента следующая фраза считается командой (сценарий 2). */
    private var armedUntilMs = 0L

    // -----------------------------------------------------------------

    fun start() {
        wantRunning = true
        val ready = model
        if (ready != null) {
            launchService(ready)
            return
        }
        // Первый запуск: копируем модель из assets во внутреннее хранилище —
        // нативный код Vosk не умеет читать файлы прямо из APK. Занимает
        // несколько секунд и делается ровно один раз (маркер — файл uuid).
        onStateChange(WakeState.LOADING)
        StorageService.unpack(
            context, ASSET_DIR, TARGET_DIR,
            { loaded ->
                model = loaded
                if (wantRunning) launchService(loaded)
            },
            { e ->
                Log.w(TAG, "модель не распаковалась", e)
                onNote("не удалось подготовить модель распознавания: ${e.message}")
                onStateChange(WakeState.ERROR)
            },
        )
    }

    private fun launchService(m: Model) {
        if (speech != null) return // уже слушаем
        try {
            val rec = Recognizer(m, SAMPLE_RATE).also { recognizer = it }
            speech = SpeechService(rec, SAMPLE_RATE).also { it.startListening(this) }
            onStateChange(WakeState.WAITING)
        } catch (e: Exception) {
            Log.w(TAG, "не удалось запустить SpeechService", e)
            onNote("не удалось занять микрофон: ${e.message}")
            releaseService()
            onStateChange(WakeState.ERROR)
        }
    }

    /** Отпустить микрофон, но сохранить загруженную модель (быстрый рестарт). */
    fun stop() {
        wantRunning = false
        armedUntilMs = 0L
        releaseService()
        onStateChange(WakeState.OFF)
    }

    private fun releaseService() {
        speech?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        speech = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }

    /** Полностью освободить всё, включая модель. Обязательно в Service.onDestroy(). */
    fun release() {
        stop()
        model?.let { runCatching { it.close() } }
        model = null
    }

    // -----------------------------------------------------------------
    //  RecognitionListener — колбэки Vosk
    // -----------------------------------------------------------------

    /** Промежуточная гипотеза: используем только чтобы раньше зажечь индикатор. */
    override fun onPartialResult(hypothesis: String?) {
        val text = normalize(field(hypothesis, "partial"))
        if (text.isNotBlank() && triggerEnd(text) >= 0) onStateChange(WakeState.RECORDING)
    }

    /** Финал фразы (после паузы) — основной путь. */
    override fun onResult(hypothesis: String?) = handle(field(hypothesis, "text"))

    /** Финал при остановке потока — обрабатываем так же. */
    override fun onFinalResult(hypothesis: String?) = handle(field(hypothesis, "text"))

    override fun onError(exception: Exception?) {
        Log.w(TAG, "ошибка Vosk", exception)
        onNote("wake-word: ошибка распознавания")
        onStateChange(WakeState.ERROR)
    }

    override fun onTimeout() { /* таймаут SpeechService не используем */ }

    // -----------------------------------------------------------------

    private fun handle(raw: String) {
        val text = normalize(raw)
        if (text.isBlank()) return

        val end = triggerEnd(text)
        val now = System.currentTimeMillis()

        when {
            // Сценарий 1: услышали триггер в этой же фразе.
            end >= 0 -> {
                val tail = text.substring(end).trim(' ', ',', '.', '!', '?')
                if (tail.isNotEmpty()) {
                    armedUntilMs = 0L
                    onStateChange(WakeState.WAITING)
                    onPhrase(tail)
                } else {
                    // Сказали только «Фауст» — ждём команду следующей фразой.
                    armedUntilMs = now + ARMED_WINDOW_MS
                    onStateChange(WakeState.RECORDING)
                }
            }
            // Сценарий 2: триггер был в предыдущей фразе и окно ещё не истекло.
            now < armedUntilMs -> {
                armedUntilMs = 0L
                onStateChange(WakeState.WAITING)
                onPhrase(text)
            }
            // Обычная посторонняя речь — игнорируем. Именно здесь проходит
            // граница приватности: всё, что не после «Фауст», никуда не уходит.
            else -> onStateChange(WakeState.WAITING)
        }
    }

    /** Достать поле из JSON-ответа Vosk ({"text":"…"} / {"partial":"…"}). */
    private fun field(hypothesis: String?, name: String): String =
        hypothesis?.let { runCatching { JSONObject(it).optString(name) }.getOrNull() }.orEmpty()

    /** Нижний регистр, ё→е, долой пунктуацию и двойные пробелы. */
    private fun normalize(text: String): String = text
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * @return индекс первого символа ПОСЛЕ слова-триггера, или -1.
     *
     * Вариантов написания несколько, потому что маленькая модель вполне может
     * услышать «фаус» или «фа уст» — заставлять пользователя выговаривать
     * ровно один вариант было бы жестоко.
     */
    private fun triggerEnd(text: String): Int {
        for (w in TRIGGERS) {
            val i = text.indexOf(w)
            if (i >= 0) return i + w.length
        }
        return -1
    }

    private companion object {
        const val TAG = "VoskWakeWord"
        const val SAMPLE_RATE = 16000.0f
        /** Папка модели в assets (см. Промпт 0). */
        const val ASSET_DIR = "model-ru"
        /** Куда Vosk распакует её внутри приложения. */
        const val TARGET_DIR = "model"
        /** Сколько ждать команду после одинокого «Фауст». */
        const val ARMED_WINDOW_MS = 8_000L

        /**
         * ПОРЯДОК ЗНАЧИМ: [triggerEnd] возвращает первый подошедший вариант,
         * поэтому более короткий вариант, являющийся началом более длинного,
         * обязан идти ПОСЛЕ него. Иначе «фаус» сработает внутри «фауст», и
         * командой посчитается «т открой ютуб».
         */
        val TRIGGERS = listOf(
            "фаустт", "фаусд", "фаузт", "фаост",
            "фауст", "фа уст",   // маленькая модель иногда разбивает слово надвое
            "фаус", "фауз",
            "faust",             // на случай, если модель отдала латиницу
        )
    }
}
