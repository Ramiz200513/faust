package com.jarvis.app

import android.content.Context
import com.jarvis.protocol.AgentScope
import com.jarvis.protocol.AgentTask
import com.jarvis.protocol.Command
import com.jarvis.protocol.KillProcess
import com.jarvis.protocol.LockScreen
import com.jarvis.protocol.OpenApp
import com.jarvis.protocol.OpenUrl
import com.jarvis.protocol.SetVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Какую команду шлёт пользовательский чип. Сознательно ограниченный набор —
 * те же примитивы, что уже в встроенных QUICK_ACTIONS (MainActivity.kt):
 * whitelist на релее их уже пропускает, CommandExecutor.java их уже умеет
 * выполнять, протокол трогать не нужно.
 */
@Serializable
enum class CustomCommandType(val label: String, val needsParam: Boolean, val paramHint: String) {
    LOCK("Заблокировать экран", false, ""),
    VOLUME("Громкость", true, "0-100"),
    OPEN_APP("Открыть приложение", true, "например, браузер"),
    OPEN_URL("Открыть сайт", true, "например, youtube.com"),
    KILL_PROCESS("Завершить процесс", true, "например, chrome.exe"),

    /**
     * Навык «рецептом»: человек СЛОВАМИ перечисляет шаги составной задачи
     * («1. запусти скрипт сервер диплома 2. открой Telegram 3. …»), а агент на
     * ПК выполняет их строго по порядку — планировщик агента видит в просьбе
     * нумерованный список и превращает его в план (правило РЕЦЕПТ). Новой
     * команды протокола не нужно: это обычный agent_task.
     */
    RECIPE("Навык: рецепт по шагам", true, "шаги, каждый с новой строки"),
}

/** Один пользовательский чип быстрой команды. */
@Serializable
data class CustomActionRecord(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val label: String,
    val type: CustomCommandType,
    /** Пусто для LOCK; для остальных типов — то, что ввёл пользователь (у RECIPE — шаги). */
    val param: String = "",
    /** Только для RECIPE: фразы через запятую, по которым навык запускается из чата и голосом. */
    val triggers: String = "",
)

/** Шаги рецепта без пустых строк и без нумерации, которую человек мог поставить сам. */
fun CustomActionRecord.recipeSteps(): List<String> =
    param.lines()
        .map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "").trim() }
        .filter { it.isNotEmpty() }

/**
 * Просьба агенту для рецепта: название навыка и нумерованные шаги. Именно
 * нумерованный список — признак рецепта для планировщика на ПК.
 */
fun CustomActionRecord.recipeGoal(): String = buildString {
    append("Навык «").append(label.trim()).append("» — выполни строго по шагам, по порядку:")
    recipeSteps().forEachIndexed { i, step -> append('\n').append(i + 1).append(". ").append(step) }
}

/** Собрать реальную команду протокола из записи чипа. */
fun CustomActionRecord.toCommand(): Command = when (type) {
    CustomCommandType.LOCK -> LockScreen
    CustomCommandType.VOLUME -> SetVolume(param.trim().toIntOrNull()?.coerceIn(0, 100) ?: 50)
    CustomCommandType.OPEN_APP -> OpenApp(param.trim())
    CustomCommandType.OPEN_URL -> OpenUrl(param.trim())
    CustomCommandType.KILL_PROCESS -> KillProcess(param.trim())
    CustomCommandType.RECIPE -> AgentTask(recipeGoal(), AgentScope.SCREEN, RECIPE_MAX_STEPS)
}

/** Лимит шагов агента для рецепта — как у составных задач на релее. */
private const val RECIPE_MAX_STEPS = 20

/** Привести фразу к виду для сравнения: регистр, ё, знаки препинания, обращение «Фауст». */
private fun normPhrase(s: String): String =
    s.lowercase().replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .removePrefix("фауст ")
        .trim()

/**
 * Какой рецепт просят этой фразой: «навык утро», «выполни навык утро» или
 * одна из фраз-триггеров («доброе утро»). Только точное совпадение после
 * нормализации — навык делает действия на ПК, «похоже на» тут не годится.
 */
fun List<CustomActionRecord>.matchRecipe(phrase: String): CustomActionRecord? {
    var p = normPhrase(phrase)
    for (prefix in listOf("выполни навык ", "запусти навык ", "навык ")) {
        if (p.startsWith(prefix)) { p = p.removePrefix(prefix).trim(); break }
    }
    if (p.isEmpty()) return null
    return firstOrNull { r ->
        r.type == CustomCommandType.RECIPE && (
            normPhrase(r.label) == p ||
                r.triggers.split(',').map(::normPhrase).any { it.isNotEmpty() && it == p }
            )
    }
}

/**
 * Пользовательские быстрые команды. Тот же файловый JSON-паттерн, что и
 * TaskHistoryRepository: DataStore тут не годится (это список записей, а
 * не пара ключ-значение), список маленький и меняется редко.
 */
class CustomActionsRepository(context: Context) {

    private val file = File(context.filesDir, "custom_actions.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _items = MutableStateFlow<List<CustomActionRecord>>(emptyList())
    /** В порядке добавления — предсказуемо для ряда чипов в чате. */
    val items: StateFlow<List<CustomActionRecord>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<CustomActionRecord>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun add(record: CustomActionRecord) = withContext(Dispatchers.IO) {
        val updated = (_items.value + record).take(MAX_ACTIONS)
        _items.value = updated
        persist(updated)
    }

    suspend fun update(record: CustomActionRecord) = withContext(Dispatchers.IO) {
        val updated = _items.value.map { if (it.id == record.id) record else it }
        _items.value = updated
        persist(updated)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val updated = _items.value.filterNot { it.id == id }
        _items.value = updated
        persist(updated)
    }

    private fun persist(list: List<CustomActionRecord>) {
        runCatching { file.writeText(json.encodeToString(list)) }
    }

    private companion object {
        /** Чтобы ряд чипов не растянулся бесконечно — это чипы, а не список настроек. */
        const val MAX_ACTIONS = 12
    }
}
