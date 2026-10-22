package com.jarvis.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.protocol.RepoInfo

/**
 * «Проекты» — git-репозитории на ПК и всё, что с ними можно сделать с телефона.
 *
 * Каждый проект — карточка: ветка, что изменено, что не отправлено, последний
 * коммит. Кнопки — обычные задачи агенту с готовой формулировкой («git pull
 * демо», «правка в демо: …»): агент узнаёт их без модели и выполняет по готовому
 * плану. Поле «Что поменять в коде» отдаёт задачу агенту для кода.
 *
 * Сверху — «Ход задачи»: план, вопрос с подсвеченным диффом и итог последней
 * задачи с этого экрана. Подтвердить коммит можно прямо здесь, не уходя в чат;
 * в чате та же задача видна целиком, как любая другая.
 *
 * Пути к папкам телефон не видит и не задаёт: список проектов ведёт человек на
 * ПК (файл repos.txt), это граница безопасности.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
) {
    val repos by vm.repos.collectAsState()
    val note by vm.reposNote.collectAsState()
    val loading by vm.reposLoading.collectAsState()
    val busy by vm.busy.collectAsState()
    val messages by vm.messages.collectAsState()
    val lastRequest by vm.lastDevRequest.collectAsState()
    var branchFor by remember { mutableStateOf<RepoInfo?>(null) }

    LaunchedEffect(Unit) { vm.refreshRepos() }

    branchFor?.let { repo ->
        BranchDialog(
            repo = repo,
            onDismiss = { branchFor = null },
            onConfirm = { name ->
                branchFor = null
                vm.runDevTask("🌿 ${repo.name}: ветка $name", "создай ветку $name в ${repo.name}")
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Проекты", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    actions = {
                        if (loading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 16.dp).size(20.dp))
                        } else {
                            IconButton(onClick = { vm.refreshRepos() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
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
        // Всё, что относится к последней задаче с этого экрана: реплика, план, вопрос, итог.
        val activity = lastRequest?.let { id -> messages.filter { it.requestId == id } }.orEmpty()

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (activity.isNotEmpty()) {
                item(key = "activity") {
                    TaskActivity(activity, onAnswer = { id, answer -> vm.answerQuestion(id, answer) })
                }
            }
            note?.let { text -> item(key = "note") { InfoCard(text) } }
            val list = repos
            when {
                list == null && loading -> item(key = "loading") { LoadingHint() }
                list != null && list.isEmpty() -> item(key = "empty") { EmptyProjectsHint() }
                list != null -> items(list, key = { it.name }) { repo ->
                    RepoCard(
                        repo = repo,
                        enabled = !busy,
                        run = { shown, goal -> vm.runDevTask(shown, goal) },
                        onBranch = { branchFor = repo },
                    )
                }
            }
        }
    }
}

/** Ход последней задачи: запрос, план, вопрос (с диффом и кнопками), итог. */
@Composable
private fun TaskActivity(messages: List<ChatMessage>, onAnswer: (String, String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val request = messages.firstOrNull { it.author == Author.ME }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ход задачи", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                request?.progress?.let { ProgressBadge(it) }
            }
            request?.let {
                Text(it.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            messages.forEach { m ->
                when {
                    m.question != null -> QuestionBubble(m, onAnswer = { answer -> onAnswer(m.id, answer) })
                    m.plan != null -> PlanBubble(m.plan)
                    m.author == Author.PC && m.agentStep == null -> ResultNote(m.text)
                }
            }
        }
    }
}

@Composable
private fun ProgressBadge(progress: CommandProgress) {
    val (label, color) = when (progress) {
        CommandProgress.SENT, CommandProgress.RUNNING -> "выполняется" to MaterialTheme.colorScheme.primary
        CommandProgress.DONE -> "готово" to onlineColor()
        CommandProgress.ERROR -> "не получилось" to MaterialTheme.colorScheme.error
        CommandProgress.DENIED -> "запрещено" to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.16f), shape = CircleShape) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** Итог задачи — отдельной плашкой, моноширинно для строк вроде «a1b2c3d Порт 8090». */
@Composable
private fun ResultNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoCard(
    repo: RepoInfo,
    enabled: Boolean,
    run: (shown: String, goal: String) -> Unit,
    onBranch: () -> Unit,
) {
    var showFiles by remember(repo.name) { mutableStateOf(false) }
    var task by remember(repo.name) { mutableStateOf("") }
    val name = repo.name
    val dirty = repo.changed.isNotEmpty()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // --- шапка: название и ветка ---
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountTree, contentDescription = "ветка", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(repo.branch, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (repo.error != null) {
                Text("⚠ ${repo.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }

            // --- состояние: изменения, не отправлено, не скачано ---
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!dirty && repo.ahead == 0 && repo.behind == 0) StateChip("✓ всё сохранено", onlineColor())
                if (dirty) StateChip("изменено: ${repo.changed.size}", Color(0xFFE3B341)) { showFiles = !showFiles }
                if (repo.ahead > 0) StateChip("↑ не отправлено: ${repo.ahead}", MaterialTheme.colorScheme.primary)
                if (repo.behind > 0) StateChip("↓ на сервере новее: ${repo.behind}", MaterialTheme.colorScheme.tertiary)
                if (!repo.hasRemote) StateChip("без сервера", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            repo.lastCommit?.let {
                Text("последний коммит: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            AnimatedVisibility(showFiles && dirty) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repo.changed.forEach { line -> ChangedFile(line) }
                }
            }

            // --- действия: главное — залитая кнопка, остальное — компактные чипы ---
            if (dirty || repo.ahead > 0) {
                Button(
                    enabled = enabled,
                    onClick = {
                        if (dirty) run("✅ $name: коммит и push", "закоммить и запушь $name")
                        else run("⬆ $name: push", "git push $name")
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        dirty && repo.hasRemote -> "Коммит и push"
                        dirty -> "Коммит"
                        else -> "Отправить на сервер"
                    })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repo.hasRemote) ProjectChip(Icons.Default.CloudDownload, "Pull", enabled) { run("⬇ $name: pull", "git pull $name") }
                ProjectChip(Icons.Default.History, "История", enabled) { run("📜 $name: история", "история $name") }
                ProjectChip(Icons.Default.AccountTree, "Ветка", enabled, onClick = onBranch)
                if (dirty) {
                    ProjectChip(Icons.AutoMirrored.Filled.Undo, "Откатить", enabled, accent = MaterialTheme.colorScheme.error) {
                        run("↩ $name: откатить правки", "откати изменения в $name")
                    }
                }
            }

            // --- задача для агента для кода ---
            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                enabled = enabled,
                placeholder = { Text("Что поменять в коде? Например: порт web на 8090") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                trailingIcon = {
                    IconButton(
                        enabled = enabled && task.isNotBlank(),
                        onClick = {
                            val t = task.trim()
                            task = ""
                            run("✏️ $name: $t", "правка в $name: $t")
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить агенту для кода") }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Агент поправит файлы, покажет дифф и спросит: коммит и push, только коммит или нет.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Компактное действие проекта: иконка и подпись в капсуле. */
@Composable
private fun ProjectChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean,
                        accent: Color? = null, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(
            labelColor = accent ?: MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = accent ?: MaterialTheme.colorScheme.primary,
        ),
    )
}

/** Строка «M docker-compose.yml»: код состояния git цветом, имя — моноширинно. */
@Composable
private fun ChangedFile(line: String) {
    val code = line.substringBefore(' ')
    val file = line.substringAfter(' ')
    val (label, color) = when {
        code == "??" || code.contains('A') -> "+" to Color(0xFF3FB950)
        code.contains('D') -> "−" to Color(0xFFF85149)
        code.contains('R') -> "→" to MaterialTheme.colorScheme.tertiary
        else -> "~" to Color(0xFFE3B341)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text(file, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StateChip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = CircleShape,
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun BranchDialog(repo: RepoInfo, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    // Те же правила, что проверит git на ПК (check-ref-format): без пробелов и «..».
    val valid = name.isNotBlank() && !name.contains(' ') && !name.contains("..") && !name.startsWith("-")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ветка в «${repo.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сейчас: ${repo.branch}. Есть такая ветка — перейду на неё, нет — создам от текущей.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    singleLine = true,
                    placeholder = { Text("feature/port-8090") },
                    isError = name.isNotEmpty() && !valid,
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name) }) { Text("Перейти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun InfoCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun LoadingHint() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyProjectsHint() {
    InfoCard(
        "Проектов пока нет. На компьютере откройте файл\n" +
            "%USERPROFILE%\\.jarvis-agent\\repos.txt\n" +
            "и добавьте строку «название = путь к папке проекта», например:\n" +
            "диплом = C:\\Users\\я\\projects\\diploma\n\n" +
            "Агент работает только с проектами из этого списка — это сделано для безопасности."
    )
}
