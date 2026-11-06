package com.jarvis.app

import com.jarvis.protocol.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/** Итог переноса файла: удалось ли и что сказать человеку. */
data class TransferResult(val ok: Boolean, val text: String)

/**
 * «Перекинь отчёт с домашнего на рабочий» — файл с одного ПК на другой.
 *
 * Телефон здесь — координатор, а не релей. Причина в устройстве привязки:
 * у телефона свой токен к каждому ПК, а релей не знает, что эти два ПК
 * принадлежат одному человеку, — значит, и решать «можно ли отдать файл с
 * А на Б» может только телефон, у которого есть доступ к обоим. Новых
 * команд в протокол не нужно: это уже существующие list_files,
 * send_file_to_phone и send_file_to_pc, просто по очереди на двух ПК.
 *
 * Безопасность — та же, что при ручной передаче: каждый ПК сам спрашивает
 * у себя подтверждение (отдать файл / принять файл), лимит 5 МБ, только
 * разрешённые папки. На ПК-получателе файл кладётся в «Загрузки», а не в
 * ту же папку, что на источнике: так перенос никогда не перезапишет
 * документ с тем же именем в «Документах».
 */
object PcTransfer {

    /** Где искать файл — разрешённые агенту папки (AppCatalog на ПК). */
    val SEARCH_FOLDERS = listOf("Рабочий стол", "Документы", "Загрузки")

    /** Куда класть на ПК-получателе. */
    const val TARGET_FOLDER = "Загрузки"

    /** Список папки — быстро; отдача и приём ждут ещё подтверждения на ПК (до 20 с). */
    private const val LIST_TIMEOUT_MS = 15_000L
    private const val CONFIRMED_TIMEOUT_MS = 60_000L

    /**
     * Перенести файл, похожий на [query], с [from] на [to]. [onNote] — что
     * сейчас происходит (для ленты чата); [register] — requestId каждой
     * отправленной команды: чат по ним не путает эти ответы со своими.
     */
    suspend fun run(
        from: PairedDevice,
        to: PairedDevice,
        query: String,
        onNote: (String) -> Unit,
        register: (String) -> Unit = {},
    ): TransferResult {
        suspend fun call(device: PairedDevice, command: Command, timeoutMs: Long): CommandOutcome {
            val id = UUID.randomUUID().toString()
            register(id)
            return PcLink(device).run(command, timeoutMs, requestId = id)
        }

        // 1. Найти файл: все папки источника параллельно.
        onNote("ищу «$query» на «${from.name}»…")
        val listings = coroutineScope {
            SEARCH_FOLDERS.map { folder ->
                async { folder to call(from, ListFiles(folder), LIST_TIMEOUT_MS) }
            }.awaitAll()
        }
        val found = mutableListOf<Pair<String, String>>() // папка -> имя файла
        var firstError: String? = null
        for ((folder, outcome) in listings) {
            val result = (outcome as? CommandOutcome.Success)?.result
            val list = result?.data as? FileList
            if (list == null) {
                firstError = firstError ?: describe(outcome)
                continue
            }
            for (name in PcAddressing.matchFiles(query, list.entries.map { it.name })) found += folder to name
        }
        if (found.isEmpty()) {
            // Ни одна папка не открылась — дело не в файле, а в связи с ПК.
            if (listings.none { (it.second as? CommandOutcome.Success)?.result?.data is FileList }) {
                return TransferResult(false, "не получилось открыть папки на «${from.name}»: ${firstError ?: "нет ответа"}")
            }
            return TransferResult(false,
                "на «${from.name}» нет файла, похожего на «$query» (искал: ${SEARCH_FOLDERS.joinToString(", ")})")
        }
        if (found.size > 1) {
            val shown = found.take(5).joinToString("; ") { (folder, name) -> "$name ($folder)" }
            return TransferResult(false, "нашёл несколько: $shown — скажите точнее, какой")
        }
        val (folder, fileName) = found.single()

        // 2. Забрать с источника — там всплывёт подтверждение.
        onNote("нашёл «$fileName» в «$folder» — подтвердите отправку на «${from.name}»")
        val sent = call(from, SendFileToPhone(folder, fileName), CONFIRMED_TIMEOUT_MS)
        val file = ((sent as? CommandOutcome.Success)?.result?.data as? FileTransfer)
            ?: return TransferResult(false, "«${from.name}» не отдал файл: ${describe(sent)}")

        // 3. Положить на получатель — там своё подтверждение.
        onNote("передаю на «${to.name}» — подтвердите сохранение там")
        val saved = call(to, SendFileToPc(TARGET_FOLDER, file.fileName, file.base64), CONFIRMED_TIMEOUT_MS)
        val ok = (saved as? CommandOutcome.Success)?.result?.status == CommandStatus.OK
        if (!ok) return TransferResult(false, "«${to.name}» не принял файл: ${describe(saved)}")

        val kb = (file.sizeBytes + 1023) / 1024
        return TransferResult(true,
            "«${file.fileName}» ($kb КБ) перенесён: «${from.name}» → «${to.name}», папка «$TARGET_FOLDER»")
    }

    /** Понятная причина неудачи одной команды. */
    fun describe(outcome: CommandOutcome): String = when (outcome) {
        is CommandOutcome.Success -> outcome.result.message ?: outcome.result.status.name
        is CommandOutcome.Rejected -> outcome.reason
        is CommandOutcome.Failed -> outcome.message
    }
}
