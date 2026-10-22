package com.jarvis.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * Экран «Свои команды»: список пользовательских чипов (CustomActionsRepository)
 * + добавление/редактирование/удаление. Сами чипы рендерятся в чате
 * (MainActivity.kt) рядом со встроенными QUICK_ACTIONS — этот экран только
 * управляет списком, повторяет оформление HistoryScreen.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomActionsScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
) {
    val items by vm.customActions.collectAsState()
    var editing by remember { mutableStateOf<CustomActionRecord?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        ActionEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false; editing = null },
            onSave = { record ->
                if (editing == null) vm.addCustomAction(record) else vm.updateCustomAction(record)
                showEditor = false
                editing = null
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Свои команды", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
                    actions = {
                        IconButton(onClick = { editing = null; showEditor = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Новая команда")
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
                    "Пока нет своих команд. Нажмите «+» в шапке, чтобы добавить чип — " +
                        "он появится в чате рядом со встроенными быстрыми командами.",
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
            items(items, key = { it.id }) { record ->
                ActionCard(
                    record = record,
                    onEdit = { editing = record; showEditor = true },
                    onDelete = { vm.removeCustomAction(record.id) },
                )
            }
        }
    }
}

@Composable
private fun ActionCard(record: CustomActionRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onEdit,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(record.emoji.ifBlank { "•" }, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(record.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        // Рецепт — несколько строк шагов: в карточке только их число и фразы запуска.
                        record.type == CustomCommandType.RECIPE ->
                            "Навык · шагов: ${record.recipeSteps().size}" +
                                (if (record.triggers.isBlank()) "" else " · «${record.triggers}»")
                        record.type.needsParam -> "${record.type.label}: ${record.param}"
                        else -> record.type.label
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить команду")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionEditorDialog(
    initial: CustomActionRecord?,
    onDismiss: () -> Unit,
    onSave: (CustomActionRecord) -> Unit,
) {
    var emoji by remember { mutableStateOf(initial?.emoji ?: "") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: CustomCommandType.LOCK) }
    var param by remember { mutableStateOf(initial?.param ?: "") }
    var triggers by remember { mutableStateOf(initial?.triggers ?: "") }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    val isRecipe = type == CustomCommandType.RECIPE

    val valid = label.isNotBlank() && (!type.needsParam || param.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая команда" else "Изменить команду") },
        text = {
            // С рецептом форма выше экрана маленького телефона — прокручиваем.
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it.take(2) },
                    label = { Text("Эмодзи") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = type.label, onValueChange = {}, readOnly = true,
                        label = { Text("Команда") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        CustomCommandType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    type = option
                                    if (!option.needsParam) param = ""
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                if (isRecipe) {
                    // Рецепт: шаги словами, по одному в строке — агент на ПК выполнит
                    // их по порядку. Пример в подсказке — тот самый «утренний» сценарий.
                    OutlinedTextField(
                        value = param, onValueChange = { param = it },
                        label = { Text(type.paramHint) },
                        placeholder = {
                            Text("запусти скрипт сервер диплома\nоткрой Telegram\nнапиши в Избранное «сервер поднят»")
                        },
                        singleLine = false, minLines = 4, maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = triggers, onValueChange = { triggers = it },
                        label = { Text("Фразы запуска через запятую") },
                        placeholder = { Text("доброе утро, начинаем работу") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Запуск: чип в чате, «навык ${label.trim().ifBlank { "…" }}» или одна из фраз. " +
                            "Перед необратимыми шагами и скриптами агент спросит подтверждение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (type.needsParam) {
                    OutlinedTextField(
                        value = param, onValueChange = { param = it },
                        label = { Text(type.paramHint) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CustomActionRecord(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            emoji = emoji.trim().ifBlank { "⚡" },
                            label = label.trim(),
                            type = type,
                            param = param.trim(),
                            triggers = if (isRecipe) triggers.trim() else "",
                        )
                    )
                },
            ) { Text(if (initial == null) "Добавить" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
