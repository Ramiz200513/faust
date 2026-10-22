package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Голос: распознавание речи (речь → текст) и озвучка ответов (текст → речь).
 *
 * Это НЕ ViewModel и сети не касается. Живёт ровно столько, сколько показан
 * экран: создаётся в Compose через `remember {}` и освобождается в `onDispose {}`.
 *
 * Наружу отдаёт два флага-состояния ([isListening], [ttsEnabled]) как Compose
 * [State] — благодаря этому кнопки в UI сами перерисовываются, когда микрофон
 * включается/выключается или меняется режим озвучки.
 */
class VoiceController(private val context: Context) {

    // mutableStateOf(...) — «наблюдаемая» переменная Compose. Снаружи отдаём
    // только для чтения (State), менять её может лишь сам VoiceController.
    private val _isListening = mutableStateOf(false)
    val isListening: State<Boolean> = _isListening

    private val _ttsEnabled = mutableStateOf(true)
    val ttsEnabled: State<Boolean> = _ttsEnabled

    /**
     * Громкость голоса во время записи, 0…1 — для анимированного шара
     * ([ListeningOrb]). Приходит из onRmsChanged ~10–20 раз в секунду.
     */
    private val _voiceLevel = mutableFloatStateOf(0f)
    val voiceLevel: State<Float> = _voiceLevel

    // ---------------------------------------------------------------------
    //  Распознавание речи
    // ---------------------------------------------------------------------

    // SpeechRecognizer нужно создавать на главном потоке. Делаем это лениво —
    // при первом нажатии микрофона, — чтобы на устройствах без распознавания
    // приложение спокойно стартовало.
    private var recognizer: SpeechRecognizer? = null

    /** Есть ли на устройстве движок распознавания речи. */
    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Начать слушать микрофон.
     *
     * @param onResult получит распознанный текст (одну финальную фразу).
     * @param onError  получит понятное сообщение об ошибке.
     *
     * Оба колбэка вызываются на главном потоке. Если распознавание недоступно
     * или разрешения нет — просто зовём [onError], без исключений и краша.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isRecognitionAvailable()) {
            onError("на устройстве нет распознавания речи")
            return
        }
        if (_isListening.value) return // уже слушаем

        val rec = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
            override fun onBeginningOfSpeech() {}
            // rmsdB у распознавателя Google примерно от -2 (тишина) до 10 (громко) —
            // приводим к 0…1, чтобы шар не зависел от этих «магических» чисел.
            override fun onRmsChanged(rmsdB: Float) {
                _voiceLevel.floatValue = ((rmsdB + 1f) / 10f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _isListening.value = false; _voiceLevel.floatValue = 0f }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                _isListening.value = false
                _voiceLevel.floatValue = 0f
                onError(describeError(error))
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                _voiceLevel.floatValue = 0f
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) onResult(text) else onError("не расслышал, повтори")
            }
        })

        // Настраиваем распознавание на русский и на свободную речь (не команды).
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            rec.startListening(intent)
            // Показываем шар сразу по нажатию, не дожидаясь onReadyForSpeech
            // (на части устройств он приходит с заметной задержкой).
            _isListening.value = true
        } catch (e: Exception) {
            _isListening.value = false
            onError("не удалось запустить микрофон: ${e.message}")
        }
    }

    /** Досрочно прекратить прослушивание (например, повторный тап по микрофону). */
    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
        _voiceLevel.floatValue = 0f
    }

    /** Числовые коды ошибок SpeechRecognizer → текст для чата. */
    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ошибка записи звука"
        SpeechRecognizer.ERROR_CLIENT -> "ошибка распознавания"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет доступа к микрофону"
        SpeechRecognizer.ERROR_NETWORK -> "нет сети для распознавания"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "таймаут сети распознавания"
        SpeechRecognizer.ERROR_NO_MATCH -> "не расслышал, повтори"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "распознавание сейчас занято"
        SpeechRecognizer.ERROR_SERVER -> "ошибка сервера распознавания"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "не услышал речь"
        else -> "ошибка распознавания (код $code)"
    }

    // ---------------------------------------------------------------------
    //  Озвучка ответов (TextToSpeech)
    // ---------------------------------------------------------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /**
     * Что сделать, когда фраза договорена, — по id фразы. Нужно голосовому
     * диалогу: микрофон включается ПОСЛЕ вопроса агента, а не поверх него
     * (иначе распознавание услышало бы собственный голос телефона).
     */
    private val afterSpeech = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        // Конструктор TextToSpeech асинхронный: движок инициализируется в фоне,
        // и лямбда-колбэк сообщает результат.
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "русский голос не установлен — будет голос по умолчанию")
                }
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    // Колбэки приходят не на главном потоке — а микрофон
                    // (SpeechRecognizer) можно трогать только с главного.
                    override fun onDone(utteranceId: String?) {
                        val action = utteranceId?.let { afterSpeech.remove(it) } ?: return
                        mainHandler.post(action)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { afterSpeech.remove(it) }
                    }
                })
            } else {
                Log.w(TAG, "TextToSpeech не инициализировался (status=$status)")
            }
        }
    }

    /**
     * Проговорить текст — если озвучка включена и движок готов. Иначе тихо выходим.
     * Озвучиваем только короткий итог: длинные ответы (например, если однажды
     * в message попадёт что-то объёмное) обрезаем, а не зачитываем целиком.
     *
     * @param onDone вызвать, когда фраза договорена (на главном потоке). Если
     *               озвучка выключена — не вызывается вовсе: молча включать
     *               микрофон человеку, который выбрал тишину, было бы странно.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!_ttsEnabled.value || !ttsReady || text.isBlank()) return
        val spoken = if (text.length > MAX_SPOKEN_CHARS) text.take(MAX_SPOKEN_CHARS) + "…" else text
        val id = "jarvis-" + System.nanoTime()
        if (onDone != null) afterSpeech[id] = onDone
        // QUEUE_FLUSH — прервать предыдущую фразу и сказать новую.
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Переключить озвучку вкл/выкл. Возвращает новое состояние. */
    fun toggleTts(): Boolean {
        _ttsEnabled.value = !_ttsEnabled.value
        if (!_ttsEnabled.value) tts?.stop() // сразу замолчать
        return _ttsEnabled.value
    }

    // ---------------------------------------------------------------------

    /** Освободить оба движка. Обязательно вызывать из `onDispose {}`. */
    fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "VoiceController"
        const val MAX_SPOKEN_CHARS = 200
    }
}
