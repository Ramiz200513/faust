package com.jarvis.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.protocol.FileEntry
import com.jarvis.protocol.LockScreen
import com.jarvis.protocol.OpenApp
import com.jarvis.protocol.SetVolume
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Экраны приложения; переключаемся простым состоянием. */
private enum class AppScreen { CHAT, DEVICES, SCREEN_VIEW, HISTORY, MULTI_PC, CUSTOM_ACTIONS, PROJECTS }

/**
 * Подсказка пустого чата: иконка, заголовок, пример и фраза, которая уйдёт.
 * Тексты — то, что «Фауст» понимает без ИИ-агента или быстро (быстрые и
 * составные команды разбираются правилами и своей моделью за доли секунды).
 */
private data class Suggestion(val icon: ImageVector, val title: String, val phrase: String)

private val SUGGESTIONS = listOf(
    Suggestion(Icons.Default.GraphicEq, "Два дела", "сделай потише и открой ютуб"),
    Suggestion(Icons.Default.PhotoCamera, "Скриншот", "скриншот"),
    Suggestion(Icons.Default.MusicNote, "Трек вперёд", "следующий трек"),
    Suggestion(Icons.Default.Memory, "Процессы", "покажи процессы"),
)

/** Одна быстрая команда: кнопка-чип, которая сразу шлёт Command, без поля ввода. */
private data class QuickAction(val icon: ImageVector, val label: String, val run: (ChatViewModel) -> Unit)

private val QUICK_ACTIONS = listOf(
    QuickAction(Icons.Default.Lock, "Блокировка") { it.sendCommand(LockScreen) },
    QuickAction(Icons.Default.PauseCircle, "Пауза") { it.sendCommand(com.jarvis.protocol.QuickAction("play_pause")) },
    QuickAction(Icons.AutoMirrored.Filled.VolumeOff, "Без звука") { it.sendCommand(SetVolume(0)) },
    QuickAction(Icons.AutoMirrored.Filled.VolumeUp, "Звук 50%") { it.sendCommand(SetVolume(50)) },
    QuickAction(Icons.Default.Language, "Браузер") { it.sendCommand(OpenApp("браузер")) },
    QuickAction(Icons.Default.ContentPaste, "Буфер → ПК") { it.sendPhoneClipboardToPc() },
    QuickAction(Icons.Default.ContentCopy, "Буфер ← ПК") { it.requestPcClipboard() },
)

/** The single Activity. It just hosts the Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                val context = LocalContext.current
                val deviceRepo = remember { DeviceRepository(context) }
                // null, пока DataStore ещё не прочитан; после первого чтения —
                // список привязанных ПК (может быть пустым). Пусто -> экран
                // привязки; как только появится хотя бы один ПК, это же
                // collectAsState само переключит экран на чат — без явного сигнала.
                val devices by deviceRepo.devices.collectAsState(initial = null)

                when (val list = devices) {
                    null -> {}
                    else -> if (list.isEmpty()) {
                        PairingScreen()
                    } else {
                        var screen by remember { mutableStateOf(AppScreen.CHAT) }
                        // Crossfade вместо мгновенной подмены: переход между
                        // разделами перестаёт выглядеть как моргание.
                        Crossfade(targetState = screen, label = "screen") { current ->
                        when (current) {
                            AppScreen.CHAT -> ChatScreen(
                                onOpenDevices = { screen = AppScreen.DEVICES },
                                onOpenScreenView = { screen = AppScreen.SCREEN_VIEW },
                                onOpenHistory = { screen = AppScreen.HISTORY },
                                onOpenMultiPc = { screen = AppScreen.MULTI_PC },
                                onOpenCustomActions = { screen = AppScreen.CUSTOM_ACTIONS },
                                onOpenProjects = { screen = AppScreen.PROJECTS },
                            )
                            AppScreen.DEVICES -> DevicesScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.SCREEN_VIEW -> ScreenViewScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.HISTORY -> HistoryScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.MULTI_PC -> MultiPcScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.CUSTOM_ACTIONS -> CustomActionsScreen(onBack = { screen = AppScreen.CHAT })
                            AppScreen.PROJECTS -> ProjectsScreen(onBack = { screen = AppScreen.CHAT })
                        }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onOpenDevices: () -> Unit = {},
    onOpenScreenView: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenMultiPc: () -> Unit = {},
    onOpenCustomActions: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
) {
    // Observe the ViewModel's state. When it changes, this recomposes.
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    val activeDevice by vm.activeDevice.collectAsState()
    val unauthorized by vm.unauthorized.collectAsState()
    val repairing by vm.repairing.collectAsState()
    val wakeWordEnabled by vm.wakeWordEnabled.collectAsState()
    val wakeState by vm.wakeState.collectAsState()
    val customActions by vm.customActions.collectAsState()
    // Кадр живого экрана — тот же поток, что у полноэкранного «Экрана ПК».
    val liveFrame by vm.liveFrame.collectAsState()
    // Включено ли окошко живого экрана (кнопка в шапке).
    val livePreview by vm.livePreview.collectAsState()
    // Открытый вопрос ИИ-агента: пока он есть, поле ввода — это поле ответа.
    val openQuestion by vm.openQuestion.collectAsState()
    // Что показывать на экране ПК — переключатели в настройках.
    val xrayEnabled by vm.xrayEnabled.collectAsState()
    val planPanelEnabled by vm.planPanelEnabled.collectAsState()
    // Идёт ИИ-задача: сказанное — поправка к ней («нет, не Пете, а Васе»).
    val activeTaskId by vm.activeTaskId.collectAsState()
    // Фото, прикреплённое к следующей просьбе.
    val pendingPhoto by vm.pendingPhoto.collectAsState()
    // Открытый видео-отчёт задачи.
    val report by vm.report.collectAsState()

    // Сторож обрыва: агент сам гасит показ через 10 минут, да и поток может
    // прерваться при переподключении к релею. Без этого окошко просто застывало
    // бы на последнем кадре, ничего не объясняя.
    LaunchedEffect(livePreview) {
        while (livePreview) {
            delay(4000)
            vm.restartStreamIfSilent()
        }
    }

    val context = LocalContext.current

    // Голосовой модуль: распознавание речи + озвучка ответов.
    // Создаётся один раз на время показа экрана, освобождается в onDispose.
    val voice = remember { VoiceController(context) }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }

    // Флаги из VoiceController — это Compose State, поэтому кнопки сами
    // перерисовываются, когда микрофон включается/выключается.
    val isListening by voice.isListening
    val ttsEnabled by voice.ttsEnabled
    val voiceLevel by voice.voiceLevel

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }

    // «Подключено», если статус содержит галочку (её ставит AuthResult.ok).
    val connected = status.contains("✓")

    val scope = rememberCoroutineScope()
    // Отложенная авто-отправка распознанной фразы — чтобы успеть её поправить.
    var pendingSpeechJob by remember { mutableStateOf<Job?>(null) }

    // Единая точка отправки: и текст из поля, и распознанная речь идут сюда —
    // тот же путь, что был раньше (vm.send → NaturalLanguage).
    fun submit(text: String) {
        pendingSpeechJob?.cancel()
        pendingSpeechJob = null
        val t = text.trim()
        if (t.isEmpty()) return
        vm.send(t)
        input = ""
    }

    // Распознали фразу: показываем её в поле на 1.5 с (можно поправить), затем
    // отправляем то, что в поле на тот момент — с учётом правок пользователя.
    fun onSpeechText(text: String) {
        input = text
        pendingSpeechJob?.cancel()
        pendingSpeechJob = scope.launch {
            delay(1500)
            submit(input)
        }
    }

    // Пока пользователь сам держит микрофон, Vosk обязан его отпустить — два
    // потребителя AudioRecord одновременно не работают. Обёртка ставит и
    // снимает флаг WakeWordBus.suspended вокруг ручного распознавания.
    fun startManualListening() {
        WakeWordBus.suspended.value = true
        voice.startListening(
            onResult = { text -> WakeWordBus.suspended.value = false; onSpeechText(text) },
            onError = { msg -> WakeWordBus.suspended.value = false; vm.note(msg) },
        )
    }

    // Диалог запроса разрешения RECORD_AUDIO. Результат прилетает в лямбду.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startManualListening()
        else vm.note("нет доступа к микрофону — голосовой ввод недоступен")
    }

    // Отдельный запрос того же разрешения, но для включения wake-word: у него
    // другая реакция на выдачу (включить прослушивание, а не начать запись).
    val wakeWordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setWakeWordEnabled(true)
        else vm.note("без доступа к микрофону wake-word не работает")
    }

    fun onWakeWordChange(on: Boolean) {
        if (!on) { vm.setWakeWordEnabled(false); return }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.setWakeWordEnabled(true)
        else wakeWordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Разрешение на уведомления — на Android 13+/TIRAMISU это runtime-разрешение,
    // без него notifyAgentTaskFinished() просто тихо ничего не покажет (не критично,
    // сама задача на ПК всё равно выполнится). Просим сразу, как и с микрофоном.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* отказ ни на что не влияет — просто не будет уведомления */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Свернули/вернули приложение -> ChatViewModel решает, поднимать ли
    // foreground-сервис и когда слать уведомление о готовности задачи.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAppBackgrounded()
                Lifecycle.Event.ON_START -> vm.onAppForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Нажали кнопку микрофона.
    fun onMicClick() {
        if (isListening) {                 // уже слушаем — второй тап останавливает
            voice.stopListening()
            WakeWordBus.suspended.value = false   // вернуть wake-word микрофон
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startManualListening()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listState = rememberLazyListState()
    // Auto-scroll to the newest message.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Озвучиваем последний ответ ПК (скриншоты пропускаем).
    //
    // Голосовой диалог: если это ВОПРОС агента, после того как телефон его
    // договорит, микрофон включается сам — человеку остаётся просто сказать
    // «Да». Распознанное уходит тем же путём, что и напечатанное (onSpeechText ->
    // submit -> vm.send), а send, видя открытый вопрос, отправит это ответом.
    LaunchedEffect(messages.size) {
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (last.author != Author.PC || last.imageBase64 != null) return@LaunchedEffect
        val card = last.question
        if (card == null) {
            voice.speak(last.text)
            return@LaunchedEffect
        }
        val hint = if (card.options.any { it.equals("да", ignoreCase = true) }) " Скажите «да» или «нет»." else ""
        voice.speak(last.text + hint) {
            val stillOpen = vm.openQuestion.value?.id == last.id
            val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (stillOpen && micAllowed && !voice.isListening.value) startManualListening()
        }
    }

    // --- Фото к просьбе: «телефон как глаза» ---------------------------------
    // Снимок или картинка из галереи уменьшаются (PhotoAttach) и ждут просьбы:
    // «сохрани в конспект по базам данных и отправь в группу».
    fun attachFrom(uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val b64 = PhotoAttach.encodeForPc(context, uri)
            if (b64 == null) vm.note("не удалось прочитать фото") else vm.attachPhoto(b64)
        }
    }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) attachFrom(cameraUri)
    }
    fun openCamera() {
        val uri = PhotoAttach.newCameraUri(context)
        cameraUri = uri
        takePicture.launch(uri)
    }
    // Разрешение CAMERA объявлено в манифесте (для QR) — а раз объявлено, Android
    // не даст вызвать даже чужую камеру, пока его не выдали. Поэтому спрашиваем.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else vm.note("без доступа к камере можно выбрать фото из галереи")
    }
    fun onCameraClick() {
        val granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> attachFrom(uri) }

    report?.let { state ->
        TaskReportDialog(
            state = state,
            onClose = { vm.closeReport() },
            onSaveGif = { vm.saveReportGif(it) },
        )
    }

    // Повторная привязка отвязанного ПК — тот же сканер, что на других экранах.
    val launchRepairScanner = rememberQrScanLauncher(
        onScanned = { raw -> vm.repairByQr(raw) },
        onDenied = { vm.note(it) },
    )
    // Привязка прошла — очищаем состояние диалога/попытки, дальше ChatViewModel
    // сам переподключится (это тот же механизм, что при смене активного ПК).
    LaunchedEffect(repairing) {
        if (repairing is PairingUiState.Done) vm.resetRepairState()
    }

    if (showSettings) {
        SettingsDialog(
            device = activeDevice,
            wakeWordEnabled = wakeWordEnabled,
            onWakeWordChange = { on -> onWakeWordChange(on) },
            ttsEnabled = ttsEnabled,
            onTtsToggle = { voice.toggleTts() },
            xrayEnabled = xrayEnabled,
            onXrayChange = { vm.setXrayEnabled(it) },
            planPanelEnabled = planPanelEnabled,
            onPlanPanelChange = { vm.setPlanPanelEnabled(it) },
            onSaveRelayUrl = { vm.updateRelayUrl(it) },
            onUnpair = {
                activeDevice?.let { vm.unpair(it.id) }
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showFiles) {
        FileTransferDialog(vm = vm, onDismiss = { showFiles = false })
    }

    // Разделы вынесены в выдвижное меню. Раньше все семь жили кнопками в шапке:
    // семь IconButton — это 336dp при ширине телефона около 360dp, так что на
    // заголовок со строкой статуса места уже не оставалось. Плюс в меню у
    // каждого раздела есть подпись, а не только картинка, которую надо угадывать.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                onOpenDevices = { closeDrawerThen(onOpenDevices) },
                onOpenMultiPc = { closeDrawerThen(onOpenMultiPc) },
                onOpenScreenView = { closeDrawerThen(onOpenScreenView) },
                onOpenFiles = { closeDrawerThen { showFiles = true } },
                onOpenHistory = { closeDrawerThen(onOpenHistory) },
                onOpenCustomActions = { closeDrawerThen(onOpenCustomActions) },
                onOpenProjects = { closeDrawerThen(onOpenProjects) },
                onOpenSettings = { closeDrawerThen { showSettings = true } },
                deviceName = activeDevice?.name,
                statusText = status.removeSuffix(" ✓"),
                statusColor = statusDotColor(status, unauthorized),
            )
        },
    ) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        // Шапка: знак «Фауста», имя и строка «ПК · статус». Нажатие на неё —
                        // подключиться/отключиться: раньше для этого была отдельная плашка
                        // под шапкой, которая занимала место во всей ленте.
                        var connMenu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { connMenu = true }
                                    .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                FaustLogo(30.dp, withTile = true)
                                Column {
                                    Text(
                                        "Фауст",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        StatusDot(statusDotColor(status, unauthorized), pulsing = connected && busy)
                                        Text(
                                            listOfNotNull(activeDevice?.name, status.removeSuffix(" ✓")).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            DropdownMenu(expanded = connMenu, onDismissRequest = { connMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (connected) "Отключиться от ПК" else "Подключиться к ПК") },
                                    onClick = { connMenu = false; if (connected) vm.disconnect() else vm.connect() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Устройства…") },
                                    onClick = { connMenu = false; onOpenDevices() },
                                )
                            }
                        }
                    },
                    // Шапка в цвет фона, как в макете — от чата её отделяет только линия снизу.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Разделы")
                        }
                    },
                    actions = {
                        // Видимый признак, что телефон слушает.
                        WakeStateIndicator(wakeState)
                        // Живой экран ПК в окошке поверх переписки: видно, что
                        // делает агент, прямо во время задачи. Включается только
                        // по нажатию — кадры идут, пока окно открыто.
                        IconButton(onClick = { vm.toggleLivePreview() }) {
                            Icon(
                                if (livePreview) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = if (livePreview) "Выключить живой экран"
                                                     else "Показать живой экран ПК",
                                tint = if (livePreview) MaterialTheme.colorScheme.primary
                                       else LocalContentColor.current,
                            )
                        }
                        // Настройки — то, за чем тянутся чаще всего
                        // (озвучка ответов, wake-word), поэтому они здесь.
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // --- Плашка связи — только когда что-то не так: ПК отвязан или нет связи. ---
            // Когда всё хорошо, статус и так виден в шапке, а лента занимает весь экран.
            AnimatedVisibility(visible = unauthorized || !connected) {
                Surface(
                    color = if (unauthorized) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (unauthorized) "Этот ПК отвязан — привяжите заново"
                                else "Нет связи с ${activeDevice?.name?.let { "«$it»" } ?: "ПК"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (unauthorized) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (unauthorized) {
                            Button(onClick = { launchRepairScanner() }, shape = CircleShape) { Text("QR") }
                        } else {
                            FilledTonalButton(onClick = { vm.connect() }, shape = CircleShape) { Text("Подключить") }
                        }
                    }
                }
            }

            // --- Message list (+ подсказка на пустом чате) ---
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        // animateItem работает только вместе с key: без него
                        // Compose не знает, какой элемент куда переехал.
                        val plan = msg.plan
                        when {
                            // Вопрос ИИ-агента — карточка с кнопками вместо обычного пузыря.
                            msg.question != null ->
                                QuestionBubble(msg, onAnswer = { vm.answerQuestion(msg.id, it) }, Modifier.animateItem())
                            // План многоэтапной задачи — чек-лист, обновляется на месте.
                            plan != null -> PlanBubble(plan, Modifier.animateItem())
                            else -> MessageBubble(msg, Modifier.animateItem(), onOpenReport = { vm.openReport(it) })
                        }
                    }
                }

                // Заставка пустого чата — ПОСЛЕ списка, то есть поверх него: список
                // растянут на весь экран, и под ним карточки-подсказки не получали нажатий.
                if (messages.none { it.author != Author.SYSTEM }) {
                    EmptyChatHint(
                        onSuggestion = { submit(it) },
                        onOpenProjects = onOpenProjects,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // Живой экран ПК во время ИИ-задачи: маленькое окно в углу, поверх
                // переписки. Кадры шлёт агент сам, пока идёт задача. Метка цели
                // берётся из последнего шага — так видно не только что происходит
                // на экране, но и куда агент собирается нажать.
                val lastStep = messages.lastOrNull { it.agentStep != null }
                TaskPreviewWindow(
                    visible = livePreview,
                    frame = liveFrame,
                    targetX = lastStep?.targetX,
                    targetY = lastStep?.targetY,
                    onExpand = onOpenScreenView,
                    onClose = { vm.toggleLivePreview() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                )

                // Нажали 🎤 — поверх чата крупный шар, который «говорит» вместе с голосом.
                if (isListening) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ListeningIndicator(level = voiceLevel, onStop = { onMicClick() })
                    }
                }
            }

            // --- Быстрые команды: один ряд, шлют сразу, без поля ввода ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // «Стоп» — только пока что-то выполняется: постоянная, почти
                // всегда бесполезная кнопка только занимала бы место и путала.
                AnimatedVisibility(
                    visible = busy,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    ActionChip(Icons.Default.Stop, "Стоп", accent = MaterialTheme.colorScheme.error) { vm.cancelTask() }
                }
                QUICK_ACTIONS.forEach { action ->
                    ActionChip(action.icon, action.label) { action.run(vm) }
                }
                // Свои команды человека — с его эмодзи: их он придумал сам.
                customActions.forEach { action ->
                    ActionChip(null, "${action.emoji} ${action.label}") { vm.runCustomAction(action) }
                }
            }

            // --- Прикреплённое фото: ждёт просьбы, что с ним сделать ---
            pendingPhoto?.let { b64 ->
                val thumb = rememberDecodedFrame(b64)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        thumb?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "прикреплённое фото",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        }
                        Text(
                            "Фото прикреплено — скажите, что с ним сделать",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        IconButton(onClick = { vm.clearPhoto() }) {
                            Icon(Icons.Default.Close, contentDescription = "Убрать фото")
                        }
                    }
                }
            }

            // --- Поле ввода: одна капсула, внутри — микрофон и отправка ---
            // Рамка — когда сказанное уйдёт не новой командой: ответом на вопрос
            // агента или поправкой к идущей задаче.
            val inputSpecial = openQuestion != null || (activeTaskId != null && pendingPhoto == null)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                border = if (inputSpecial) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            ) {
                Row(
                    Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Фото к просьбе: снять сейчас или взять из галереи.
                    var attachMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { attachMenu = true }) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = "Прикрепить фото к просьбе",
                                tint = if (pendingPhoto != null) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Сфотографировать") },
                                onClick = { attachMenu = false; onCameraClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Выбрать из галереи") },
                                onClick = {
                                    attachMenu = false
                                    pickImage.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            )
                        }
                    }
                    // Микрофон: подсветка, пока идёт запись — повторный тап останавливает.
                    IconButton(onClick = { onMicClick() }) {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "Остановить запись" else "Сказать команду",
                            tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextField(
                        // Поле многострочное (длинная просьба переносится), но Enter —
                        // это «отправить», а не новая строка: так привыкли в мессенджерах.
                        value = input,
                        onValueChange = { v -> if (v.endsWith("\n")) submit(v.trimEnd()) else input = v },
                        // Пока агент ждёт ответа, всё введённое уйдёт ему как ответ
                        // (ChatViewModel.send) — подсказка говорит об этом прямо.
                        placeholder = { Text(when {
                            isListening -> "слушаю…"
                            openQuestion != null -> "ответ для Фауста…"
                            pendingPhoto != null -> "что сделать с фото?"
                            // Задача идёт: напечатанное поправит её, «стоп» — остановит.
                            activeTaskId != null -> "поправка к задаче… («стоп» — отменить)"
                            else -> "Скажите или напишите команду"
                        }) },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { submit(input) }
                        ),
                    )
                    // Отправка появляется, когда есть что отправлять: пустая капсула не
                    // выглядит формой, которую надо заполнить, а главный жест — голос.
                    AnimatedVisibility(
                        visible = input.isNotBlank(),
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally(),
                    ) {
                        FilledIconButton(
                            onClick = { submit(input) },
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * Выдвижное меню чата — единственное место, где перечислены разделы приложения.
 *
 * До этого все они висели кнопками-эмодзи в шапке: семь штук не помещались по
 * ширине, отличались друг от друга только картинкой (без подписи «📡» не
 * угадаешь) и ничего не говорили TalkBack. Здесь у каждого есть и иконка, и
 * название.
 */
@Composable
private fun ChatDrawer(
    onOpenDevices: () -> Unit,
    onOpenMultiPc: () -> Unit,
    onOpenScreenView: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCustomActions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    deviceName: String? = null,
    statusText: String = "",
    statusColor: Color = Color.Gray,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        // Шапка: знак и имя, под ними — карточка ПК, которым сейчас управляет телефон.
        Row(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FaustLogo(44.dp, withTile = true)
            Column {
                Text("Фауст", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("ассистент для вашего ПК", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            onClick = onOpenDevices,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(deviceName ?: "ПК не выбран", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                        Text(statusText, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Text("сменить", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(10.dp))

        DrawerSection("Компьютер")
        DrawerLink(Icons.Default.DesktopWindows, "Экран ПК", onOpenScreenView)
        DrawerLink(Icons.Default.Folder, "Файлы", onOpenFiles)
        DrawerLink(Icons.Default.Devices, "Мульти-ПК", onOpenMultiPc)

        DrawerSection("Работа")
        DrawerLink(Icons.Default.Code, "Проекты (git)", onOpenProjects)
        DrawerLink(Icons.Default.Bolt, "Свои команды и навыки", onOpenCustomActions)
        DrawerLink(Icons.Default.History, "История задач", onOpenHistory)

        DrawerSection("Приложение")
        DrawerLink(Icons.Default.Settings, "Настройки", onOpenSettings)
    }
}

/** Подпись раздела меню мелкой капителью. */
@Composable
private fun DrawerSection(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        // подпись рядом, дублировать незачем
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * Что сейчас делает движок слова-триггера. Раньше рисовалось эмодзи (⏳/👂/🎙);
 * иконка выравнивается по остальной шапке и получает подпись для TalkBack —
 * для незрячего пользователя «слушаю ли я сейчас» особенно важно.
 */
@Composable
private fun WakeStateIndicator(state: WakeState) {
    val (icon, description) = when (state) {
        WakeState.LOADING -> Icons.Default.HourglassEmpty to "Готовлю распознавание речи"
        WakeState.WAITING -> Icons.Default.Hearing to "Жду слово «Фауст»"
        WakeState.RECORDING -> Icons.Default.Mic to "Слушаю команду"
        else -> return
    }
    Icon(
        icon,
        contentDescription = description,
        tint = if (state == WakeState.RECORDING) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 4.dp).size(20.dp),
    )
}

/**
 * Чип-«пилюля» из макета: тёмная заливка, тонкая рамка, светлый текст.
 * [accent] — для особых действий («Стоп»): красит текст и рамку.
 */
@Composable
private fun PillChip(text: String, accent: Color? = null, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = accent ?: MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.6f) ?: MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(36.dp),
    )
}

/** Цвет точки статуса в шапке: зелёный — подключено, красный — отвязан/офлайн/ошибка, иначе нейтральный. */
@Composable
private fun statusDotColor(status: String, unauthorized: Boolean): Color = when {
    unauthorized -> MaterialTheme.colorScheme.error
    status.contains("✓") -> onlineColor()
    status.contains("офлайн") || status.contains("отвязан") ||
        status.contains("нет связи") || status.contains("ошибка") -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

/**
 * Диалог настроек активного ПК: адрес релея (можно поправить, например при
 * смене сети) и отвязка. Токена здесь больше нет — он не то, что пользователь
 * должен вводить руками, это внутренняя деталь привязки по QR/коду.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    device: PairedDevice?,
    wakeWordEnabled: Boolean,
    onWakeWordChange: (Boolean) -> Unit,
    ttsEnabled: Boolean,
    onTtsToggle: () -> Unit,
    xrayEnabled: Boolean,
    onXrayChange: (Boolean) -> Unit,
    planPanelEnabled: Boolean,
    onPlanPanelChange: (Boolean) -> Unit,
    onSaveRelayUrl: (String) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(device?.relayUrl) { mutableStateOf(device?.relayUrl.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device?.name ?: "Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("адрес релея (ws://…)") }, singleLine = true,
                )
                Text(
                    "Сменилась сеть? Отсканируй QR заново.",
                    style = MaterialTheme.typography.labelSmall,
                )

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Просыпаться по «Фауст»", Modifier.weight(1f))
                    Switch(checked = wakeWordEnabled, onCheckedChange = onWakeWordChange)
                }
                Text(
                    "Распознавание полностью на телефоне, без интернета. " +
                        "В сеть уходит только сама команда — то, что сказано после «Фауст».",
                    style = MaterialTheme.typography.labelSmall,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Озвучивать ответы ПК", Modifier.weight(1f))
                    Switch(checked = ttsEnabled, onCheckedChange = { onTtsToggle() })
                }

                HorizontalDivider()

                // Что показывать на экране ПК во время ИИ-задачи. Меняется сразу,
                // и посреди задачи тоже: выключенное исчезает с экрана тут же.
                Text("На экране ПК", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Рентген: как ИИ видит окно", Modifier.weight(1f))
                    Switch(checked = xrayEnabled, onCheckedChange = onXrayChange)
                }
                Text(
                    "Рамки и номера элементов, из которых модель выбирает, куда нажать.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("План и мысли агента", Modifier.weight(1f))
                    Switch(checked = planPanelEnabled, onCheckedChange = onPlanPanelChange)
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = onUnpair,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Отвязать этот ПК") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSaveRelayUrl(url); onDismiss() }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private val TRANSFER_FOLDERS = listOf(
    "downloads" to "Загрузки", "documents" to "Документы",
    "desktop" to "Рабочий стол", "pictures" to "Изображения",
)

/**
 * Список файлов в одной из разрешённых папок ПК + скачать/отправить.
 * folder — это те же строки-алиасы, что понимает open_app (см. AppCatalog на агенте).
 */
@Composable
fun FileTransferDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val files by vm.fileList.collectAsState()
    var folder by remember { mutableStateOf(TRANSFER_FOLDERS.first().first) }
    val context = LocalContext.current

    // Обновляем список при открытии диалога и при смене вкладки-папки.
    LaunchedEffect(folder) { vm.listFiles(folder) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = readPickedFile(context, uri)
        if (picked == null) {
            vm.note("не удалось прочитать файл (или он больше 5 МБ)")
        } else {
            val (name, base64) = picked
            vm.uploadFile(folder, name, base64)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(16.dp).width(320.dp)) {
                Text("Файлы на ПК", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    TRANSFER_FOLDERS.forEach { (key, label) ->
                        FilterChip(
                            selected = folder == key,
                            onClick = { folder = key },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text("пусто (или ещё грузится)", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(files, key = { it.name }) { f ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${f.name} (${f.sizeBytes / 1024} КБ)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.downloadFile(folder, f.name) }) { Text("⬇") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { pickLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⬆ отправить файл с телефона") }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
            }
        }
    }
}

/**
 * One chat bubble: right & tinted for ME, left & teal-tinted for the PC.
 * SYSTEM lines are not a "reply" from anyone — they get a small centered
 * note instead of a full-width bubble ([SystemNote]).
 */
@Composable
fun MessageBubble(msg: ChatMessage, modifier: Modifier = Modifier, onOpenReport: ((String) -> Unit)? = null) {
    if (msg.author == Author.SYSTEM) {
        SystemNote(msg.text, modifier)
        return
    }

    val mine = msg.author == Author.ME
    // Свои — розовые (primaryContainer), ответы ПК — тёмная карточка в цвет темы:
    // яркие кремовые пузыри на почти чёрном фоне слепили и спорили с моими.
    val bubbleColor =
        if (mine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (mine) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    // «Хвостик» чата: у своего сообщения срезан нижний правый угол, у чужого — левый.
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 4.dp,
        bottomEnd = if (mine) 4.dp else 20.dp,
    )
    // Свой пузырь светлый в обеих темах — на нём читается только тёмно-красный;
    // на тёмной карточке ПК — обычный цвет ошибки темы.
    val errorOnBubble = if (mine) Color(0xFF9A1B16) else MaterialTheme.colorScheme.error

    // Открыт ли скриншот на весь экран.
    var zoomOpen by remember { mutableStateOf(false) }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = shape,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Шаг agent_task: маленький заголовок «ИИ-агент · шаг N», а в тексте —
                // только само действие, без повтора «🔎 шаг N:».
                val stepNo = msg.agentStep
                if (stepNo != null) {
                    AgentStepHeader(stepNo, aiming = msg.targetX != null && msg.targetY != null, contentColor = contentColor)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = if (stepNo != null) msg.text.removePrefix("🔎 ").removePrefix("шаг $stepNo: ") else msg.text,
                    style = if (stepNo != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                )

                // A screenshot arrives as base64 — decode and show it.
                msg.imageBase64?.let { b64 ->
                    // Не в композиции: скриншот бывает на 1280px, и его
                    // декодирование на главном потоке подтормаживало прокрутку
                    // ленты сообщений.
                    val bitmap = rememberDecodedFrame(b64)
                    bitmap?.let {
                        Spacer(Modifier.height(8.dp))
                        val imageShape = RoundedCornerShape(12.dp)
                        // Box ровно по размеру картинки (aspectRatio) — тогда Canvas с маркером
                        // через matchParentSize совпадает с изображением пиксель в пиксель.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1))
                                .clip(imageShape)
                                .border(1.dp, contentColor.copy(alpha = 0.12f), imageShape)
                                .clickable { zoomOpen = true },
                        ) {
                            Image(
                                bitmap = it,
                                contentDescription = "скриншот экрана ПК (нажми, чтобы открыть)",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Точка прицела ИИ-агента — только у шагов agent_task (AgentStep),
                            // у обычных скриншотов (msg.targetX == null) не рисуется вообще.
                            if (msg.targetX != null && msg.targetY != null) {
                                AimMarker(msg.targetX, msg.targetY, it, Modifier.matchParentSize())
                            }
                            // Подсказка, что картинку можно открыть на весь экран.
                            Surface(
                                color = Color.Black.copy(alpha = 0.45f),
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                            ) {
                                Text(
                                    "⤢",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (zoomOpen) {
                            ZoomableScreenshotDialog(
                                bitmap = it,
                                targetX = msg.targetX,
                                targetY = msg.targetY,
                                onDismiss = { zoomOpen = false },
                            )
                        }
                    }
                }

                // Итог ИИ-задачи — «Видео-отчёт»: кадры шагов с мыслями модели.
                val reportId = msg.reportTaskId
                if (reportId != null && onOpenReport != null) {
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = { onOpenReport(reportId) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Видео-отчёт", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = timeLabel(msg.sentAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f),
                    )
                    // Индикатор статуса команды — только у моих сообщений.
                    msg.progress?.let { p ->
                        // Пока команда «выполняется…», раз в секунду обновляем таймер;
                        // долгие agent_task-задачи (до ~3 мин) не должны выглядеть
                        // зависшими — счётчик секунд явно показывает, что работа идёт.
                        var elapsedSec by remember(msg.id) {
                            mutableStateOf((System.currentTimeMillis() - msg.sentAtMs) / 1000)
                        }
                        LaunchedEffect(msg.id, p) {
                            if (p == CommandProgress.RUNNING) {
                                while (true) {
                                    elapsedSec = (System.currentTimeMillis() - msg.sentAtMs) / 1000
                                    delay(1000)
                                }
                            }
                        }
                        Text(
                            text = "· " + p.label(elapsedSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (p) {
                                CommandProgress.ERROR, CommandProgress.DENIED -> errorOnBubble
                                else -> contentColor.copy(alpha = 0.7f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Вопрос ИИ-агента посреди задачи. Выглядит как реплика ПК, но выделен другим
 * цветом и рамкой: это единственное место в ленте, где агент ЖДЁТ человека,
 * и оно не должно теряться среди карточек шагов.
 *
 * Ответить можно двумя способами: кнопкой-вариантом или своими словами в поле
 * ввода внизу (голосом тоже). После ответа кнопки исчезают, а в карточке
 * остаётся, что ответили и дошёл ли ответ до агента.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionBubble(msg: ChatMessage, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val card = msg.question ?: return

    // Часы для обратного отсчёта: агент ждёт ограниченное время, и человеку
    // полезно видеть, сколько осталось. Тикают только пока вопрос открыт.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(msg.id, card.state) {
        while (card.state == QuestionState.WAITING) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val open = card.isOpen(now)
    val secondsLeft = card.expiresAtMs?.let { ((it - now) / 1000).coerceAtLeast(0) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // Тот же «хвостик» слева, что у реплик ПК: вопрос задаёт компьютер.
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp),
            border = BorderStroke(if (open) 1.5.dp else 1.dp,
                if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            // С диффом карточке нужна вся ширина: строки кода длинные.
            modifier = Modifier.widthIn(max = if (msg.text.contains("\n@@")) 520.dp else 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.QuestionAnswer, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text("Фауст спрашивает", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                // Вопрос перед коммитом несёт дифф — он рисуется как в редакторе кода.
                DiffAwareText(msg.text)

                when {
                    open -> {
                        if (card.options.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            // FlowRow: варианты переносятся на новую строку, а не
                            // уезжают за край — имён контактов может быть три-четыре.
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Первый вариант — главное действие («Да», «Повторить»), остальные — вторичные.
                                card.options.forEachIndexed { i, option ->
                                    if (i == 0) Button(onClick = { onAnswer(option) }) { Text(option) }
                                    else OutlinedButton(onClick = { onAnswer(option) }) { Text(option) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = (if (card.options.isEmpty()) "ответьте в поле ниже" else "или своими словами в поле ниже") +
                                (secondsLeft?.let { " · осталось $it с" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                        )
                    }
                    card.state == QuestionState.SENDING -> QuestionStatus("отправляю ответ «${card.answer}»…")
                    card.state == QuestionState.ANSWERED -> QuestionStatus("✓ агент получил ответ «${card.answer}»")
                    // Сюда же попадает WAITING с истёкшим временем — до того, как
                    // ChatViewModel успеет перевести карточку в EXPIRED.
                    else -> QuestionStatus(
                        if (card.answer != null) "ответ «${card.answer}» не дошёл — агент уже не ждал"
                        else "вопрос закрыт — агент продолжил без ответа"
                    )
                }
            }
        }
    }
}

/**
 * План многоэтапной задачи: какие подзадачи есть, какая идёт сейчас, какие
 * готовы. Приходит заново при каждом изменении и перерисовывается на месте —
 * по нему видно, где агент находится в длинной задаче, не листая шаги.
 */
@Composable
internal fun PlanBubble(plan: com.jarvis.protocol.AgentPlan, modifier: Modifier = Modifier) {
    val done = plan.steps.count { it.status == com.jarvis.protocol.PlanItemStatus.DONE }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    // Перестроенный план помечаем явно: человеку важно знать,
                    // что агент поменял подход, а не просто продолжил.
                    text = "План · $done из ${plan.steps.size}" +
                        if (plan.revision > 1) " · перестроен" else "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                plan.steps.forEachIndexed { i, item ->
                    PlanRow(i + 1, item)
                    if (i < plan.steps.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** Одна строка чек-листа: значок состояния, название, итог мелким шрифтом. */
@Composable
private fun PlanRow(number: Int, item: com.jarvis.protocol.PlanItem) {
    val (mark, strong) = when (item.status) {
        com.jarvis.protocol.PlanItemStatus.DONE -> "✓" to false
        com.jarvis.protocol.PlanItemStatus.RUNNING -> "▶" to true
        com.jarvis.protocol.PlanItemStatus.FAILED -> "✗" to false
        com.jarvis.protocol.PlanItemStatus.SKIPPED -> "–" to false
        com.jarvis.protocol.PlanItemStatus.PENDING -> "$number." to false
    }
    // Пропущенное и ещё не начатое — приглушённо; идущее сейчас — жирным.
    val faded = item.status == com.jarvis.protocol.PlanItemStatus.PENDING ||
        item.status == com.jarvis.protocol.PlanItemStatus.SKIPPED
    val color = LocalContentColor.current.copy(alpha = if (faded) 0.6f else 1f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.widthIn(min = 18.dp))
        Column {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            )
            item.note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
            }
        }
    }
}

/** Строка состояния под вопросом после ответа. */
@Composable
private fun QuestionStatus(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
}

/** Системная строка (например, «подключаюсь к…») — скромная центрированная заметка, не реплика. */
@Composable
private fun SystemNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = CircleShape,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private fun timeLabel(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

/** Текст индикатора для каждого состояния команды; elapsedSec — только для RUNNING. */
private fun CommandProgress.label(elapsedSec: Long = 0): String = when (this) {
    CommandProgress.SENT -> "отправлено"
    CommandProgress.RUNNING -> "выполняется… ${elapsedSec} с"
    CommandProgress.DONE -> "готово ✓"
    CommandProgress.ERROR -> "ошибка"
    CommandProgress.DENIED -> "отклонено"
}

/**
 * Скриншот на весь экран. Жесты:
 *  • щипок — масштаб (1×…5×)
 *  • перетаскивание (когда приближено) — сдвиг
 *  • двойной тап — приблизить / вернуть 1×
 * Закрытие — кнопка «✕», тап мимо или кнопка «Назад».
 */
@Composable
fun ZoomableScreenshotDialog(
    bitmap: ImageBitmap,
    onDismiss: () -> Unit,
    targetX: Int? = null,
    targetY: Int? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // aspectRatio вписывает Box в экран с пропорциями картинки, а graphicsLayer
            // зумит картинку и маркер вместе — маркер не «уплывает» при щипке.
            Box(
                Modifier
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "скриншот на весь экран",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (targetX != null && targetY != null) {
                    AimMarker(targetX, targetY, bitmap, Modifier.matchParentSize())
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text("✕ закрыть", color = Color.White)
            }
        }
    }
}

/**
 * Точка прицела ИИ-агента поверх скриншота. targetX/targetY — в пикселях самого
 * bitmap; переводим их в ДОЛЮ от размера картинки, поэтому неважно, во сколько
 * раз Compose отрисовал изображение крупнее/мельче исходного (тот же приём, что
 * в зуме живого экрана). Пульсирующее кольцо + белая обводка — чтобы маркер
 * читался и на светлых, и на тёмных страницах.
 */
@Composable
private fun AimMarker(targetX: Int, targetY: Int, bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "aim").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "aimPulse",
    )
    val red = Color(0xFFFF3B30)
    Canvas(modifier) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@Canvas
        val center = Offset(
            x = targetX.toFloat() / bitmap.width * size.width,
            y = targetY.toFloat() / bitmap.height * size.height,
        )
        val core = 4.dp.toPx()
        val ring = 11.dp.toPx()
        // расходящаяся «волна» — видно, что агент целится именно сюда
        drawCircle(
            red, radius = ring + pulse * 14.dp.toPx(), center = center,
            alpha = 0.45f * (1f - pulse), style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(red, radius = ring, center = center, alpha = 0.25f)
        drawCircle(Color.White, radius = ring, center = center, alpha = 0.9f, style = Stroke(width = 1.5.dp.toPx()))
        // перекрестие с разрывом в центре
        val gap = core + 3.dp.toPx()
        val arm = ring + 6.dp.toPx()
        val w = 1.5.dp.toPx()
        drawLine(red, Offset(center.x - arm, center.y), Offset(center.x - gap, center.y), strokeWidth = w)
        drawLine(red, Offset(center.x + gap, center.y), Offset(center.x + arm, center.y), strokeWidth = w)
        drawLine(red, Offset(center.x, center.y - arm), Offset(center.x, center.y - gap), strokeWidth = w)
        drawLine(red, Offset(center.x, center.y + gap), Offset(center.x, center.y + arm), strokeWidth = w)
        drawCircle(Color.White, radius = core + 1.dp.toPx(), center = center)
        drawCircle(red, radius = core, center = center)
    }
}

/** Заголовок пузыря-шага agent_task: «ИИ-агент · шаг N», с пометкой, если есть точка прицела. */
@Composable
private fun AgentStepHeader(step: Int, aiming: Boolean, contentColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            color = contentColor.copy(alpha = 0.10f),
            contentColor = contentColor,
            shape = CircleShape,
        ) {
            Text(
                "ИИ-агент · шаг $step",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        if (aiming) {
            Text(
                "🎯 цель",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Пустой чат: знак «Фауста» с мягким свечением, приветствие и карточки-подсказки.
 * Карточка сразу отправляет свою фразу — первый опыт без набора текста.
 */
@Composable
private fun EmptyChatHint(onSuggestion: (String) -> Unit, onOpenProjects: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 460.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Свечение под знаком — радиальный градиент цвета primary.
            Box(
                Modifier.size(132.dp).background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent)
                    ),
                    CircleShape,
                )
            )
            FaustLogo(76.dp, withTile = true, breathing = true)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Чем помочь?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Скажите «Фауст» или нажмите микрофон. Можно сразу несколько дел: «сделай потише и открой ютуб».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        // Сетка 2×2 карточек-подсказок.
        SUGGESTIONS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    SuggestionCard(s.icon, s.title, "«${s.phrase}»", Modifier.weight(1f)) { onSuggestion(s.phrase) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        SuggestionCard(Icons.Default.Code, "Проекты и git", "коммит, push, правка кода — с телефона",
            Modifier.fillMaxWidth(), onClick = onOpenProjects)
    }
}

@Composable
private fun SuggestionCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2)
            }
        }
    }
}

/**
 * Чип быстрой команды: иконка + подпись в капсуле. [accent] — для особых
 * действий («Стоп»): красит иконку, текст и рамку.
 */
@Composable
private fun ActionChip(icon: ImageVector?, label: String, accent: Color? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = accent?.copy(alpha = 0.12f) ?: MaterialTheme.colorScheme.surfaceContainer,
        shape = CircleShape,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.5f) ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = if (icon != null) 12.dp else 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accent ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = accent ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Точка статуса связи; [pulsing] — мягко пульсирует, пока ПК выполняет команду. */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
    } else 1f
    Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}
