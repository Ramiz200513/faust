package com.jarvis.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.protocol.LockScreen
import com.jarvis.protocol.OpenUrl
import com.jarvis.protocol.SetVolume

/**
 * Экран «Мульти-ПК»: отметить несколько из уже привязанных ПК и разослать
 * им одну и ту же быструю команду разом. См. MultiPcViewModel — каждому
 * отмеченному ПК уходит независимое короткое соединение.
 *
 * Плюс ИИ-задача на все отмеченные ПК (в строке каждого — его текущий шаг и
 * его вопросы) и перенос файла с одного ПК на другой. То же доступно в чате
 * голосом: «на всех ПК …», «на рабочем …», «перекинь отчёт с домашнего на рабочий».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPcScreen(onBack: () -> Unit, vm: MultiPcViewModel = viewModel()) {
    val rows by vm.rows.collectAsState()
    val transfer by vm.transfer.collectAsState()
    var showUrlDialog by remember { mutableStateOf(false) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }

    val selectedCount = rows.count { it.selected }
    val sending = rows.any { it.state == BroadcastState.SENDING }

    if (showUrlDialog) {
        UrlDialog(
            onConfirm = { url -> showUrlDialog = false; vm.broadcast(OpenUrl(url)) },
            onDismiss = { showUrlDialog = false },
        )
    }
    if (showTaskDialog) {
        TaskDialog(
            count = selectedCount,
            onConfirm = { text -> showTaskDialog = false; vm.runTask(text) },
            onDismiss = { showTaskDialog = false },
        )
    }
    if (showTransferDialog) {
        TransferDialog(
            devices = rows.map { it.device },
            onConfirm = { from, to, query -> showTransferDialog = false; vm.transfer(from, to, query) },
            onDismiss = { showTransferDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мульти-ПК") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { vm.selectAll(selectedCount < rows.size) }) {
                            Text(if (selectedCount < rows.size) "Все" else "Снять")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (rows.size < 2) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Нужно хотя бы два привязанных ПК.\nПривяжите ещё один на экране «Устройства».",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.device.id }) { row ->
                    MultiPcRowView(
                        row = row,
                        onToggle = { vm.toggle(row.device.id) },
                        onAnswer = { vm.answer(row.device.id, it) },
                    )
                }
            }

            // Ход переноса файла — карточкой над кнопками, пока её не закроют.
            transfer?.let { t ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.running) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            (if (t.ok == true) "✓ " else if (t.ok == false) "✗ " else "🔁 ") + t.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!t.running) TextButton(onClick = vm::dismissTransfer) { Text("OK") }
                    }
                }
            }

            HorizontalDivider()

            // ИИ-задача и перенос файла — второй ряд над быстрыми командами.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sending) {
                    OutlinedButton(onClick = vm::cancelTasks, modifier = Modifier.weight(1f)) { Text("⏹ Стоп") }
                } else {
                    Button(
                        onClick = { showTaskDialog = true },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("🤖 Задача") }
                }
                OutlinedButton(
                    onClick = { showTransferDialog = true },
                    enabled = transfer?.running != true,
                    modifier = Modifier.weight(1f),
                ) { Text("🔁 Файл с ПК на ПК") }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.broadcast(LockScreen) },
                    enabled = selectedCount > 0 && !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("🔒 Блок.") }
                OutlinedButton(
                    onClick = { vm.broadcast(SetVolume(0)) },
                    enabled = selectedCount > 0 && !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("🔇 Тише") }
                OutlinedButton(
                    onClick = { showUrlDialog = true },
                    enabled = selectedCount > 0 && !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("🌐 Сайт") }
            }
        }
    }
}

@Composable
private fun MultiPcRowView(row: MultiPcRow, onToggle: () -> Unit, onAnswer: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = row.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(row.device.name, style = MaterialTheme.typography.bodyLarge)
                when (row.state) {
                    BroadcastState.SENDING -> Text(
                        row.step ?: "отправка…",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                    )
                    BroadcastState.OK -> Text(
                        "✓ ${row.detail ?: "готово"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = onlineColor(),
                    )
                    BroadcastState.ERROR -> Text(
                        "✗ ${row.detail ?: "ошибка"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    BroadcastState.IDLE -> {}
                }
                // Агент этого ПК спрашивает — отвечаем прямо в строке, ему одному.
                row.question?.let { q ->
                    Spacer(Modifier.height(6.dp))
                    Text("❓ ${q.question}", style = MaterialTheme.typography.bodyMedium)
                    val options = q.options.ifEmpty { listOf("Да", "Нет") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.take(3).forEach { option ->
                            FilledTonalButton(onClick = { onAnswer(option) }) { Text(option, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDialog(count: Int, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Задача на $count ПК") },
        text = {
            Column {
                Text(
                    "Каждый ПК выполнит просьбу сам и попросит подтверждения у себя.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("открой расписание") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Отправить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Откуда, куда и какой файл. В чате то же самое: «перекинь отчёт с домашнего на рабочий». */
@Composable
private fun TransferDialog(
    devices: List<PairedDevice>,
    onConfirm: (fromId: String, toId: String, query: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromId by remember { mutableStateOf(devices.getOrNull(0)?.id) }
    var toId by remember { mutableStateOf(devices.getOrNull(1)?.id) }
    var query by remember { mutableStateOf("") }
    val valid = fromId != null && toId != null && fromId != toId && query.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Файл с ПК на ПК") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Откуда", style = MaterialTheme.typography.labelLarge)
                DeviceChips(devices, fromId) { fromId = it }
                Text("Куда (в «${PcTransfer.TARGET_FOLDER}»)", style = MaterialTheme.typography.labelLarge)
                DeviceChips(devices, toId) { toId = it }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Какой файл") },
                    placeholder = { Text("отчёт по практике") },
                    singleLine = true,
                )
                Text(
                    "Ищу в папках: ${PcTransfer.SEARCH_FOLDERS.joinToString(", ")}. Оба ПК спросят подтверждение.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(fromId!!, toId!!, query) }, enabled = valid) { Text("Перенести") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChips(devices: List<PairedDevice>, selected: String?, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        devices.forEach { d ->
            FilterChip(selected = d.id == selected, onClick = { onSelect(d.id) }, label = { Text(d.name) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть сайт на выбранных ПК") },
        text = {
            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(url) }) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
