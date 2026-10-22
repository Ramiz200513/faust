package com.jarvis.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Одна выполненная задача агента. Ровно то, что уже приходит в AgentReport,
 * плюс текст запроса пользователя (его AgentReport не содержит — берём из
 * сообщения чата с тем же requestId) и момент отправки.
 */
@Serializable
data class TaskRecord(
    val id: String,
    val startedAtMs: Long,
    /** Что попросил пользователь — фраза из чата. */
    val request: String,
    val completed: Boolean,
    val stepsTaken: Int,
    /** Итоговая строка от агента («цель достигнута за 4 шаг(ов), 22 с»). */
    val summary: String,
    /** Пошаговый трейс — то, что агент писал по ходу работы. */
    val trace: List<String>,
)

/**
 * История задач на самом телефоне. Протокол не трогаем: всё нужное уже
 * приходит в CommandResult, вопрос только в том, чтобы это не выбрасывать.
 *
 * Хранилище — один JSON-файл во внутренней памяти приложения. DataStore тут
 * не подходит: он для небольших пар «ключ-значение», а трейсы растут.
 * Держим последние [MAX_RECORDS] записей — файл не должен расти бесконечно.
 */
class TaskHistoryRepository(context: Context) {

    private val file = File(context.filesDir, "task_history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _items = MutableStateFlow<List<TaskRecord>>(emptyList())
    /** Новые записи сверху. */
    val items: StateFlow<List<TaskRecord>> = _items.asStateFlow()

    /** Прочитать файл. Поломанный/устаревший файл — не повод падать, просто начинаем с пустой истории. */
    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<TaskRecord>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun add(record: TaskRecord) = withContext(Dispatchers.IO) {
        val updated = (listOf(record) + _items.value).take(MAX_RECORDS)
        _items.value = updated
        runCatching { file.writeText(json.encodeToString(updated)) }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _items.value = emptyList()
        runCatching { file.delete() }
        Unit
    }

    private companion object {
        const val MAX_RECORDS = 50
    }
}
