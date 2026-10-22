package com.jarvis.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Outcome of a command. DENIED means the whitelist rejected it. */
@Serializable
enum class CommandStatus {
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("denied") DENIED,
    @SerialName("timeout") TIMEOUT,
}

/**
 * Optional structured payload returned with a [CommandResult].
 * Sealed so each command that returns data has a typed shape.
 */
@Serializable
sealed interface ResultData

@Serializable
@SerialName("process_list")
data class ProcessList(val processes: List<ProcessInfo>) : ResultData

@Serializable
data class ProcessInfo(
    val pid: Long,
    val name: String,
)

/** Результат [GetClipboard]: содержимое буфера обмена ПК. */
@Serializable
@SerialName("clipboard_text")
data class ClipboardText(val text: String) : ResultData

/** Result of an [AgentTask]: what the vision agent ended up doing. */
@Serializable
@SerialName("agent_report")
data class AgentReport(
    val completed: Boolean,
    val stepsTaken: Int,
    /** Short trace for the audit log and for showing the user. */
    val trace: List<String> = emptyList(),
) : ResultData

@Serializable
data class FileEntry(val name: String, val sizeBytes: Long)

/** Результат [ListFiles]. */
@Serializable
@SerialName("file_list")
data class FileList(val entries: List<FileEntry>) : ResultData

/** Результат [SendFileToPhone]: содержимое файла. */
@Serializable
@SerialName("file_transfer")
data class FileTransfer(val fileName: String, val base64: String, val sizeBytes: Long) : ResultData

/** Результат [DevRepos]: разрешённые репозитории на ПК. */
@Serializable
@SerialName("repo_list")
data class RepoList(val repos: List<RepoInfo>) : ResultData

/**
 * Один репозиторий — без пути на диске (телефону он не нужен).
 *
 * [ahead] — сколько коммитов не отправлено на сервер, [behind] — сколько
 * на сервере не скачано (по последним данным ПК, без запроса к серверу).
 */
@Serializable
data class RepoInfo(
    val name: String,
    val branch: String,
    val changed: List<String> = emptyList(),
    val ahead: Int = 0,
    val behind: Int = 0,
    val lastCommit: String? = null,
    val hasRemote: Boolean = false,
    val error: String? = null,
)
