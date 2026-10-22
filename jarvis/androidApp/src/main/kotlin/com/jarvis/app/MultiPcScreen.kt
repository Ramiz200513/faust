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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPcScreen(onBack: () -> Unit, vm: MultiPcViewModel = viewModel()) {
    val rows by vm.rows.collectAsState()
    var showUrlDialog by remember { mutableStateOf(false) }

    val selectedCount = rows.count { it.selected }
    val sending = rows.any { it.state == BroadcastState.SENDING }

    if (showUrlDialog) {
        UrlDialog(
            onConfirm = { url -> showUrlDialog = false; vm.broadcast(OpenUrl(url)) },
            onDismiss = { showUrlDialog = false },
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
                    MultiPcRowView(row = row, onToggle = { vm.toggle(row.device.id) })
                }
            }

            HorizontalDivider()

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
private fun MultiPcRowView(row: MultiPcRow, onToggle: () -> Unit) {
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
                    BroadcastState.SENDING -> Text("отправка…", style = MaterialTheme.typography.labelMedium)
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
            }
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
