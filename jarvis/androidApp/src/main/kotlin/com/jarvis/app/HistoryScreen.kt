package com.jarvis.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * История выполненных задач агента. Данные локальные (см. TaskHistoryRepository) —
 * сети этот экран не касается вообще.
 *
 * Каждая карточка сворачивается/разворачивается по нажатию: свёрнутая — итог
 * одной строкой, развёрнутая — весь трейс. Трейс бывает на 20 строк, показывать
 * его всегда означало бы список, по которому невозможно листать.
 * Оформление — как в чате: шапка в цвет фона, карточки surfaceContainer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
) {
    val items by vm.taskHistory.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Записи о ${items.size} задачах будут удалены с телефона.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("История задач", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
                    actions = {
                        if (items.isNotEmpty()) {
                            IconButton(onClick = { confirmClear = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Очистить историю")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "Пока пусто. Здесь появятся задачи агента — что просили, " +
                        "получилось ли и сколько шагов заняло.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            items(items, key = { it.id }) { record -> HistoryCard(record) }
        }
    }
}

/** Чем кончилась задача — отменённую отличаем от провальной по итоговой строке агента. */
private enum class Outcome(val label: String) { DONE("выполнено"), FAILED("не выполнено"), CANCELLED("отменено") }

private fun TaskRecord.outcome(): Outcome = when {
    completed -> Outcome.DONE
    summary.startsWith("задача отменена") || trace.any { it.contains("отменил пользователь") } -> Outcome.CANCELLED
    else -> Outcome.FAILED
}

@Composable
private fun Outcome.color(): Color = when (this) {
    Outcome.DONE -> onlineColor()
    Outcome.FAILED -> MaterialTheme.colorScheme.error
    Outcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun HistoryCard(record: TaskRecord) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(record.startedAtMs) {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(record.startedAtMs))
    }
    val outcome = record.outcome()

    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Бейдж-«пилюля» статуса: цветной текст на полупрозрачной подложке того же цвета.
                Surface(color = outcome.color().copy(alpha = 0.16f), shape = CircleShape) {
                    Text(
                        outcome.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = outcome.color(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                record.request.ifBlank { "(без текста запроса)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                record.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                record.trace.forEachIndexed { i, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(min = 18.dp),
                        )
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (record.trace.isNotEmpty()) {
                Text(
                    "показать ${record.trace.size} шаг(ов) ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
