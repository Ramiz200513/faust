package com.jarvis.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что сейчас делает wake-word. Пишет сервис, читает UI. */
enum class WakeState {
    OFF,        // выключен
    LOADING,    // первая распаковка модели из assets (45 МБ, только один раз)
    WAITING,    // ждём слово «Фауст»
    RECORDING,  // услышали триггер, ждём команду отдельной фразой
    ERROR,      // не смог стартовать (нет модели / микрофон занят)
}

/**
 * Мостик между [JarvisConnectionService] (слушает микрофон в фоне) и
 * [ChatViewModel] (владеет WebSocket-соединением и отправляет команды).
 *
 * Почему синглтон-object, а не bound-сервис: оба живут в ОДНОМ процессе, и
 * связать их нужно ровно двумя событиями — «распознал фразу» и «случилась
 * ошибка». Полноценный binder тут — лишние 50 строк ради того же результата.
 *
 * SharedFlow (а не StateFlow) для фраз — это события: каждую фразу нужно
 * обработать ровно один раз, а не хранить как «текущее значение».
 * extraBufferCapacity нужен, чтобы tryEmit не потерял событие, если подписчик
 * в этот момент занят.
 */
object WakeWordBus {

    private val _phrases = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Распознанные команды — ChatViewModel шлёт их как обычные сообщения. */
    val phrases: SharedFlow<String> = _phrases.asSharedFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Служебные строки для чата («модель не загрузилась» и т.п.). */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private val _state = MutableStateFlow(WakeState.OFF)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    /**
     * Временно отключить wake-word, не выключая совсем: пока пользователь сам
     * держит микрофон (кнопка 🎤), Vosk обязан отпустить AudioRecord — иначе
     * системный SpeechRecognizer не получит звук.
     */
    val suspended = MutableStateFlow(false)

    fun emitPhrase(text: String) { _phrases.tryEmit(text) }
    fun emitNote(text: String) { _notes.tryEmit(text) }
    fun setState(state: WakeState) { _state.value = state }
}
