package com.jarvis.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who sent a chat line — controls bubble alignment and color. */
enum class Author { ME, PC, SYSTEM }

/**
 * Жизненный цикл отправленной команды — для индикатора под сообщением.
 *  SENT     — ушло на релей
 *  RUNNING  — релей/агент, похоже, взялись за дело (эвристика по времени)
 *  DONE     — CommandResult со статусом OK
 *  ERROR    — ERROR / TIMEOUT
 *  DENIED   — whitelist отклонил
 */
enum class CommandProgress { SENT, RUNNING, DONE, ERROR, DENIED }

/**
 * Где сейчас вопрос ИИ-агента:
 *  WAITING  — агент ждёт ответа, кнопки активны
 *  SENDING  — ответ ушёл, ждём подтверждения от агента
 *  ANSWERED — агент принял ответ
 *  EXPIRED  — агент больше не ждёт (время вышло, задача кончилась или отменена)
 */
enum class QuestionState { WAITING, SENDING, ANSWERED, EXPIRED }

/** Что нужно карточке-вопросу сверх обычного текста сообщения. */
data class QuestionCard(
    val questionId: String,
    /** Варианты-кнопки; пусто — только свой ответ в поле ввода. */
    val options: List<String>,
    val state: QuestionState = QuestionState.WAITING,
    /** Что ответил пользователь — показывается в карточке после ответа. */
    val answer: String? = null,
    /** Когда агент перестанет ждать; null — агент не сообщил. */
    val expiresAtMs: Long? = null,
) {
    /** Можно ли ещё отвечать — с учётом часов, а не только состояния. */
    fun isOpen(nowMs: Long = System.currentTimeMillis()): Boolean =
        state == QuestionState.WAITING && (expiresAtMs == null || nowMs < expiresAtMs)
}

/** One line in the chat. imageBase64 is set only for screenshots. */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val imageBase64: String? = null,
    /** requestId команды — чтобы сопоставить ответ с этим сообщением. */
    val requestId: String? = null,
    /** Точка на imageBase64, куда целится vision-агент — только у шагов agent_task. */
    val targetX: Int? = null,
    val targetY: Int? = null,
    /** Номер шага agent_task (AgentStep) — чтобы пузырь показал «ИИ-агент · шаг N». */
    val agentStep: Int? = null,
    /** Статус выполнения; null для не-команд (системных строк, ответов ПК). */
    val progress: CommandProgress? = null,
    /** Момент отправки — точка отсчёта для таймера «выполняется… N с». */
    val sentAtMs: Long = System.currentTimeMillis(),
    /** Не null — это вопрос ИИ-агента (AgentQuestion), и пузырь рисуется карточкой с кнопками. */
    val question: QuestionCard? = null,
    /**
     * Не null — это план многоэтапной задачи (AgentPlan): карточка-чек-лист.
     * Одна на задачу: новые версии плана заменяют её содержимое на месте.
     */
    val plan: AgentPlan? = null,
    /**
     * Не null — это итог ИИ-задачи с таким requestId: под ним кнопка «Видео-отчёт»
     * (кадры шагов с мыслями модели, их хранит ПК).
     */
    val reportTaskId: String? = null,
)

/** Что сейчас с видео-отчётом, открытым на весь экран. */
sealed interface ReportState {
    data class Loading(val taskId: String) : ReportState
    data class Ready(val report: TaskReport) : ReportState
    data class Failed(val taskId: String, val reason: String) : ReportState
}

/**
 * Holds all UI state and talks to the relay through JarvisClient.
 *
 * Подключается только к активному [PairedDevice] из [DeviceRepository] —
 * отдельного "тестового" пути без привязанного ПК больше нет ([MainActivity]
 * не показывает чат, пока не привязан хотя бы один ПК).
 *
 * AndroidViewModel (а не просто ViewModel) — чтобы был Context для DataStore.
 * State is exposed as StateFlow, which Compose observes: when the list changes,
 * the UI recomposes automatically.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val client = JarvisClient()
    private val devicesRepo = DeviceRepository(app)
    private val pairingController = PairingController(viewModelScope, devicesRepo)
    private val settings = SettingsRepository(app)
    private val history = TaskHistoryRepository(app)
    private val customActionsRepo = CustomActionsRepository(app)

    /** История задач — читает экран «История». */
    val taskHistory: StateFlow<List<TaskRecord>> = history.items

    /** Пользовательские быстрые команды — читают ряд чипов в чате и экран «Свои команды». */
    val customActions: StateFlow<List<CustomActionRecord>> = customActionsRepo.items

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * Есть ли сейчас незавершённая команда — по этому флагу UI показывает
     * кнопку «Стоп». Вычисляется из самих сообщений, чтобы не заводить второй
     * источник правды: статус уже живёт в ChatMessage.progress.
     */
    val busy: StateFlow<Boolean> = _messages
        .map { list ->
            list.any { it.progress == CommandProgress.SENT || it.progress == CommandProgress.RUNNING }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Открытый вопрос ИИ-агента, если он есть (последний). Пока он открыт, поле
     * ввода превращается в поле ответа: напечатанное или сказанное голосом
     * уходит агенту как ответ, а не как новая команда (см. [send]).
     */
    val openQuestion: StateFlow<ChatMessage?> = _messages
        .map { list -> list.lastOrNull { it.question?.isOpen() == true } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * requestId отправленного ответа -> id карточки-вопроса. Результат команды
     * answer_question не показывается отдельным пузырём «ответ передан агенту»:
     * он только переключает состояние самой карточки. ConcurrentHashMap —
     * потому что пишем из главного потока, а читаем из потока OkHttp.
     */
    private val answerRequests = ConcurrentHashMap<String, String>()

    /** Все привязанные ПК — по их именам разбирается «на рабочем …». */
    private val pairedDevices: StateFlow<List<PairedDevice>> = devicesRepo.devices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Идущие команды на других (не выбранном) ПК: requestId -> связь с тем ПК.
     * По ней же уходят ответы на вопросы этих задач и «стоп».
     */
    private val remoteLinks = ConcurrentHashMap<String, PcLink>()

    /**
     * requestId всего, что ушло через [PcLink]. Если такая связь ведёт к
     * выбранному ПК (перенос файла с него), постоянное соединение чата тоже
     * получит эти ответы — их оно должно пропустить, иначе файл, который
     * переносится с ПК на ПК, заодно сохранился бы в «Загрузки» телефона.
     */
    private val linkRequests = ConcurrentHashMap.newKeySet<String>()

    /**
     * requestId ИИ-задачи, которая сейчас выполняется на ПК, или null.
     *
     * Узнаём не по отправке (фраза уходит на релей как текст, и заранее не
     * известно, станет ли она задачей агенту), а по первому знаку от самой
     * задачи — шагу, плану или вопросу. Пока она идёт, всё, что человек
     * напечатал или сказал, — это поправка к ней, а не новая команда
     * (см. [send]): агент всё равно занят и новую задачу не взял бы.
     */
    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId.asStateFlow()

    /** Фото, прикреплённое к следующей просьбе (JPEG в base64), или null. */
    private val _pendingPhoto = MutableStateFlow<String?>(null)
    val pendingPhoto: StateFlow<String?> = _pendingPhoto.asStateFlow()

    /** Видео-отчёт, открытый на весь экран, или null. */
    private val _report = MutableStateFlow<ReportState?>(null)
    val report: StateFlow<ReportState?> = _report.asStateFlow()

    /** requestId запросов кадров отчёта — их итог не пузырь в ленте, а экран отчёта. */
    private val reportRequests = ConcurrentHashMap.newKeySet<String>()

    private val _status = MutableStateFlow("не подключено")
    val status: StateFlow<String> = _status.asStateFlow()

    // Последний кадр ЖИВОГО стрима (seq != null). В чат не попадает — иначе
    // при 2-5 кадрах в секунду список сообщений мгновенно забился бы. Экран
    // "Экран ПК" читает именно этот StateFlow, а не messages.
    private val _liveFrame = MutableStateFlow<ScreenFrame?>(null)
    val liveFrame: StateFlow<ScreenFrame?> = _liveFrame.asStateFlow()

    /**
     * Включено ли маленькое окно «живой экран» поверх переписки (кнопка в шапке).
     * Оно же решает, идёт ли стрим: пока окно выключено, ПК кадры не шлёт.
     */
    private val _livePreview = MutableStateFlow(false)
    val livePreview: StateFlow<Boolean> = _livePreview.asStateFlow()

    /**
     * Кто сейчас хочет видеть экран ПК: окошко в чате, полноэкранный «Экран ПК»
     * или оба сразу.
     *
     * Зачем считать владельцев, а не просто слать start/stop. Раньше каждый
     * экран управлял стримом сам, и выход из полноэкранного режима слал
     * StopScreenStream — даже если окошко в чате оставалось включённым. Стрим
     * замолкал, а окошко застывало на последнем кадре, пока в полноэкранный
     * режим не зайдёшь снова (он снова слал start). Теперь стрим живёт, пока
     * его хочет хоть кто-то.
     */
    private val streamHolders = mutableSetOf<String>()

    companion object {
        /** Что во время задачи значит «остановись», а не поправку. */
        private val STOP_PHRASES = setOf(
            "стоп", "отмена", "отмени", "отменить", "хватит", "остановись", "прекрати", "стой",
            "фауст стоп", "стоп стоп", "отмени задачу",
        )

        /** Окошко живого экрана поверх переписки. */
        const val HOLDER_PREVIEW = "preview"
        /** Полноэкранный «Экран ПК». */
        const val HOLDER_FULLSCREEN = "fullscreen"
        /** Дольше этого без кадров — считаем, что стрим оборвался (опорный кадр идёт раз в 3 с). */
        private const val STREAM_SILENCE_MS = 12_000L
        /**
         * Сколько ждать итога команды на другом ПК. С запасом над самой долгой
         * ИИ-задачей: у агента свой предел времени, и итог он пришлёт сам.
         */
        private const val REMOTE_TASK_TIMEOUT_MS = 15 * 60_000L
    }

    /** Ширина кадра, которую просит каждый из владельцев: берём наибольшую. */
    private val streamWidths = mutableMapOf<String, Int>()

    /** Запросить стрим для экрана [holder] («preview» или «fullscreen»). */
    @Synchronized
    fun acquireStream(holder: String, maxWidth: Int) {
        streamHolders.add(holder)
        streamWidths[holder] = maxWidth
        // Перезапуск с наибольшей запрошенной шириной: полноэкранному нужна
        // картинка крупнее, чем окошку, а агент отдаёт один поток на всех.
        sendCommand(StartScreenStream(fps = 3, maxWidth = streamWidths.values.max()))
        lastFrameAtMs = System.currentTimeMillis()
    }

    /** Экран [holder] больше не смотрит. Стрим останавливается, когда ушёл последний. */
    @Synchronized
    fun releaseStream(holder: String) {
        streamHolders.remove(holder)
        streamWidths.remove(holder)
        if (streamHolders.isEmpty()) {
            sendCommand(StopScreenStream)
            _liveFrame.value = null
        } else {
            // Остался кто-то с меньшими запросами — вернём поток к его ширине,
            // чтобы не гонять лишние пиксели после выхода из полноэкранного.
            sendCommand(StartScreenStream(fps = 3, maxWidth = streamWidths.values.max()))
        }
    }

    /**
     * Кнопка «живой экран»: включить или выключить окошко поверх переписки.
     *
     * Ширина кадра скромнее полноэкранной (480 против 800): это окно размером
     * с ладонь, и кадры снимает та же машина, которой агент в этот момент
     * может управлять.
     */
    fun toggleLivePreview() {
        if (_livePreview.value) {
            _livePreview.value = false
            releaseStream(HOLDER_PREVIEW)
        } else {
            _livePreview.value = true
            acquireStream(HOLDER_PREVIEW, maxWidth = 480)
        }
    }

    /** Когда пришёл последний кадр — по нему видно, что стрим замолчал. */
    @Volatile
    private var lastFrameAtMs = 0L

    /**
     * Не замолчал ли стрим. Агент сам останавливает показ через 10 минут, а ещё
     * поток может оборваться при переподключении к релею — в обоих случаях
     * окошко просто застыло бы на последнем кадре, ничего не объясняя.
     *
     * Молчание больше [STREAM_SILENCE_MS] — это точно обрыв: даже на полностью
     * неподвижном экране агент шлёт опорный кадр раз в 3 секунды.
     */
    @Synchronized
    fun restartStreamIfSilent() {
        if (streamHolders.isEmpty()) return
        if (System.currentTimeMillis() - lastFrameAtMs < STREAM_SILENCE_MS) return
        sendCommand(StartScreenStream(fps = 3, maxWidth = streamWidths.values.max()))
        lastFrameAtMs = System.currentTimeMillis()
    }

    // Список файлов из последнего ListFiles — читает диалог "Файлы на ПК".
    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList: StateFlow<List<FileEntry>> = _fileList.asStateFlow()

    // Активное устройство — то, на которое уходят команды.
    private val _activeDevice = MutableStateFlow<PairedDevice?>(null)
    val activeDevice: StateFlow<PairedDevice?> = _activeDevice.asStateFlow()

    // Релей явно отверг токен этого ПК (AuthResult.ok=false) — значит, ПК
    // отвязан на релее и нужно привязать его заново, а не ждать реконнекта.
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    // Повторная привязка отвязанного ПК (сканом QR) — тот же общий контроллер,
    // что и на экранах "Устройства"/первого запуска.
    val repairing: StateFlow<PairingUiState> = pairingController.pairing

    // Известен ли агент выбранного ПК офлайн — используем, чтобы не ждать
    // таймаут при каждой отправке, а сразу показать понятное сообщение.
    private var activeAgentOffline = false

    // Свёрнуто ли приложение — влияет на то, поднимать ли foreground-сервис и
    // слать ли уведомление о завершении agent_task (см. MainActivity: ON_STOP/ON_START).
    private var isBackgrounded = false

    // Wake-word: включён ли. Значение живёт в DataStore, здесь — зеркало для
    // переключателя в настройках.
    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    /** Что сейчас делает движок слова-триггера — для индикатора в шапке. */
    val wakeState: StateFlow<WakeState> = WakeWordBus.state

    // «Рентген» и панель плана на экране ПК — переключатели в настройках.
    // Хранятся на телефоне: он и есть «пульт», и при каждом подключении
    // сообщает ПК, что показывать (см. syncDisplayOptions).
    private val _xrayEnabled = MutableStateFlow(true)
    val xrayEnabled: StateFlow<Boolean> = _xrayEnabled.asStateFlow()
    private val _planPanelEnabled = MutableStateFlow(true)
    val planPanelEnabled: StateFlow<Boolean> = _planPanelEnabled.asStateFlow()

    /**
     * requestId служебных команд, ответ на которые в чат не нужен (синхронизация
     * настроек показа при подключении): иначе каждый вход в приложение добавлял
     * бы в переписку «рентген включён на ПК».
     */
    private val silentRequests = ConcurrentHashMap.newKeySet<String>()

    // --- Проекты (git) ---------------------------------------------------------

    /** Репозитории на ПК для экрана «Проекты»; null — ещё не загружены. */
    private val _repos = MutableStateFlow<List<RepoInfo>?>(null)
    val repos: StateFlow<List<RepoInfo>?> = _repos.asStateFlow()

    /** Пояснение на экране «Проекты» (ПК офлайн, список пуст…); null — всё в порядке. */
    private val _reposNote = MutableStateFlow<String?>(null)
    val reposNote: StateFlow<String?> = _reposNote.asStateFlow()

    private val _reposLoading = MutableStateFlow(false)
    val reposLoading: StateFlow<Boolean> = _reposLoading.asStateFlow()

    /**
     * requestId последней задачи, запущенной с экрана «Проекты»: по нему экран
     * показывает её ход (план, вопрос с диффом, итог), не уводя человека в чат.
     */
    private val _lastDevRequest = MutableStateFlow<String?>(null)
    val lastDevRequest: StateFlow<String?> = _lastDevRequest.asStateFlow()

    /** Запросы списка проектов — их ответ не пишется в чат, а обновляет экран. */
    private val repoRequests = ConcurrentHashMap.newKeySet<String>()
    /** Задачи с экрана «Проекты»: по их итогу карточки проектов обновляются сами. */
    private val devTasks = ConcurrentHashMap.newKeySet<String>()

    /** Запросить у ПК список проектов и их состояние (только чтение, без сети на ПК). */
    fun refreshRepos() {
        if (activeAgentOffline) {
            _reposNote.value = "ПК сейчас офлайн — включите агент на компьютере"
            return
        }
        val id = UUID.randomUUID().toString()
        repoRequests.add(id)
        _reposLoading.value = true
        client.send(CommandRequest(requestId = id, command = DevRepos))
    }

    /**
     * Действие с проектом: «git pull демо», «правка в демо: …». Уходит задачей
     * агенту напрямую, без разбора фразы на релее: экран и так знает, что нужно.
     * В чате появляется как обычная реплика — вся история остаётся в одном месте.
     */
    fun runDevTask(shownText: String, goal: String) {
        if (activeAgentOffline) {
            _reposNote.value = "ПК сейчас офлайн — действие не отправлено"
            return
        }
        val id = sendTracked(shownText, AgentTask(goal = goal, scope = AgentScope.SCREEN, maxSteps = 20))
        devTasks.add(id)
        _lastDevRequest.value = id
    }

    init {
        // Фразы, распознанные фоновым сервисом после слова «Фауст», уходят
        // ровно тем же путём, что и набранные руками (send -> NaturalLanguage).
        viewModelScope.launch { history.load() }
        viewModelScope.launch { customActionsRepo.load() }
        viewModelScope.launch { WakeWordBus.phrases.collect { send(it) } }
        // Служебные сообщения wake-word — просто строкой в чат.
        viewModelScope.launch { WakeWordBus.notes.collect { note(it) } }
        // Восстанавливаем настройку между запусками: если wake-word был включён,
        // поднимаем сервис сразу при старте (приложение в этот момент на
        // переднем плане — значит Android 12+ разрешит старт foreground-сервиса).
        viewModelScope.launch {
            _wakeWordEnabled.value = settings.wakeWordEnabled.first()
            if (_wakeWordEnabled.value) JarvisConnectionService.wakeWord(getApplication(), true)
        }
        viewModelScope.launch {
            _xrayEnabled.value = settings.xrayEnabled.first()
            _planPanelEnabled.value = settings.planPanelEnabled.first()
        }
        viewModelScope.launch {
            val activeDeviceFlow = combine(devicesRepo.devices, devicesRepo.activeDeviceId) { devices, id ->
                devices.find { it.id == id }
            }

            _activeDevice.value = activeDeviceFlow.first()
            connect()

            // Реагируем и на смену выбранного ПК на экране "Устройства", и на
            // обновление токена/адреса того же ПК (повторный скан QR, правка
            // адреса в настройках) — оба случая меняют объект PairedDevice.
            activeDeviceFlow.drop(1).collect { newActive ->
                if (newActive != _activeDevice.value) {
                    _activeDevice.value = newActive
                    activeAgentOffline = false
                    _unauthorized.value = false
                    connect()
                }
            }
        }
    }

    /** Подключиться к активному устройству. */
    fun connect() {
        val device = _activeDevice.value
        if (device == null) {
            _status.value = "нет привязанного ПК"
            return
        }
        _unauthorized.value = false
        // Строку «подключаюсь к …» в ленту больше не пишем: статус связи и имя ПК
        // всегда видны в шапке, а в ленте строка висела и после подключения.
        // При смене ПК отметка нужна — команды дальше уйдут на другой компьютер.
        if (_messages.value.any { it.author != Author.SYSTEM }) {
            add(ChatMessage(author = Author.SYSTEM, text = "дальше — «${device.name}»"))
        }
        client.connect(
            url = device.relayUrl,
            token = device.token,
            deviceId = device.id,
            onMessage = ::handle,
            onStatus = { _status.value = it },
        )
    }

    fun disconnect() = client.disconnect()

    /** Сменить адрес релея активного ПК (например, при смене сети) и переподключиться. */
    fun updateRelayUrl(url: String) {
        val device = _activeDevice.value ?: return
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed == device.relayUrl) return
        viewModelScope.launch { devicesRepo.addAndActivate(device.copy(relayUrl = trimmed)) }
    }

    /** Отвязать активный ПК (кнопка в настройках). */
    fun unpair(deviceId: String) {
        viewModelScope.launch {
            client.disconnect()
            devicesRepo.remove(deviceId)
        }
    }

    /**
     * Привязать заново отвязанный ПК сканом QR — [PairingController.pairByQr]
     * сохранит новый токен под тем же id, а слушатель в [init] сам подключится
     * с ним (тот же механизм, что при смене активного устройства).
     */
    fun repairByQr(raw: String) {
        val fallbackRelay = _activeDevice.value?.relayUrl ?: SettingsRepository.DEFAULT_URL
        pairingController.pairByQr(raw, fallbackRelay)
    }

    fun resetRepairState() = pairingController.resetPairingState()

    /** User typed a phrase and hit send. */
    fun send(text: String) {
        if (text.isBlank()) return
        // Агент задал вопрос и ждёт — то, что человек напечатал или сказал,
        // это ответ, а не новая команда. Иначе «Маше» ушло бы на релей как
        // фраза для разбора и превратилось бы в непонятную команду.
        // (Вопрос может быть и от другого ПК — ответ уйдёт тому, кто спросил.)
        val question = openQuestion.value
        if (question != null) {
            answerQuestion(question.id, text.trim())
            return
        }
        // «Стоп», пока идут задачи на других ПК, — остановить их. Задача на
        // выбранном ПК останавливается своим путём ниже.
        if (_activeTaskId.value == null && remoteLinks.isNotEmpty() && isStopPhrase(text)) {
            remoteLinks.values.forEach { it.send(CancelTask) }
            note("отменяю задачи на других ПК — агенты остановятся после текущего шага")
            return
        }
        // «На рабочем …», «на всех ПК …», «перекинь … с домашнего на рабочий» —
        // фраза адресована не (только) выбранному ПК. Раньше проверки «выбранный
        // ПК офлайн»: другие ПК от этого не зависят.
        if (_pendingPhoto.value == null && _activeTaskId.value == null && routeToNamedPcs(text.trim())) return
        // Известно, что агент офлайн — не ждём таймаут от релея, а сразу
        // говорим понятным языком, что происходит и что делать.
        if (activeAgentOffline) {
            val name = _activeDevice.value?.name
            note(
                if (name != null) "ПК «$name» сейчас офлайн — включите агент на компьютере и повторите"
                else "ПК сейчас офлайн — команда не отправлена"
            )
            return
        }
        // К просьбе прикреплено фото — это задача с фото («сохрани в конспект и
        // отправь в группу»): на ПК уходят и слова, и снимок одной командой.
        val photo = _pendingPhoto.value
        if (photo != null) {
            _pendingPhoto.value = null
            sendTracked("📷 ${text.trim()}", PhotoTask(text.trim(), photo), imageBase64 = photo)
            return
        }
        // Задача уже идёт — сказанное относится к ней: «стоп» её останавливает,
        // всё остальное — поправка («нет, не Пете, а Васе»).
        if (_activeTaskId.value != null) {
            if (isStopPhrase(text)) cancelTask() else sendCorrection(text.trim())
            return
        }
        // «Навык утро» / «доброе утро» — рецепт, который человек записал словами
        // в «Своих командах». Его шаги знает только телефон, поэтому разбираем
        // здесь, а не на релее: на ПК уходит готовая просьба с нумерованными шагами.
        val recipe = customActions.value.matchRecipe(text)
        if (recipe != null) {
            sendTracked(text, recipe.toCommand())
            return
        }
        // Send as a natural-language command; the relay turns it into a real one.
        sendTracked(text, NaturalLanguage(text))
    }

    // --- Несколько ПК ------------------------------------------------------------

    /**
     * Если фраза называет ПК («на рабочем …», «на всех …», «… с домашнего на
     * рабочий») — выполнить её там и вернуть true. false — адреса нет, фраза
     * идёт обычным путём на выбранный ПК.
     *
     * Разбор адреса — на телефоне (PcAddressing в :shared): только он знает
     * имена привязанных ПК. Сама просьба без адреса дальше разбирается как
     * обычно — релеем и своей моделью, уже на нужном ПК.
     */
    private fun routeToNamedPcs(text: String): Boolean {
        val devices = pairedDevices.value
        if (devices.size < 2) return false // один ПК — адресовать некого
        val route = PcAddressing.route(text, devices.map { PcName(it.id, it.name) })
        val active = _activeDevice.value
        when (route) {
            PcRoute.Active -> return false
            is PcRoute.One -> {
                val device = devices.first { it.id == route.pcId }
                if (device.id == active?.id) runOnActive("→ «${device.name}»: ${route.rest}", route.rest)
                else runOnRemote(device, "→ «${device.name}»: ${route.rest}", NaturalLanguage(route.rest))
            }
            is PcRoute.All -> {
                // Одна реплика — что сказал человек, под ней по строке на каждый ПК:
                // у каждого свой ход, свои вопросы и свой итог.
                add(ChatMessage(author = Author.ME, text = text))
                for (device in devices) {
                    if (device.id == active?.id) runOnActive("→ «${device.name}»: ${route.rest}", route.rest)
                    else runOnRemote(device, "→ «${device.name}»: ${route.rest}", NaturalLanguage(route.rest))
                }
            }
            is PcRoute.Transfer -> {
                val from = devices.first { it.id == route.fromId }
                val to = devices.first { it.id == route.toId }
                runTransfer(text, from, to, route.fileQuery)
            }
        }
        return true
    }

    /** Просьба к выбранному ПК, но уже без адреса — обычным путём через постоянное соединение. */
    private fun runOnActive(shown: String, rest: String) {
        if (activeAgentOffline) {
            note("ПК «${_activeDevice.value?.name}» сейчас офлайн — там не выполнено")
            return
        }
        sendTracked(shown, NaturalLanguage(rest))
    }

    /**
     * Команда на НЕ выбранный ПК — через свою связь [PcLink] на время команды.
     * Шаги, план, вопросы и итог идут в ту же ленту и тем же обработчиком
     * [handle], что и у выбранного ПК, только с именем ПК впереди.
     */
    private fun runOnRemote(device: PairedDevice, shown: String, command: Command) {
        val requestId = UUID.randomUUID().toString()
        add(ChatMessage(author = Author.ME, text = shown, requestId = requestId, progress = CommandProgress.SENT))
        val link = PcLink(device)
        remoteLinks[requestId] = link
        linkRequests += requestId
        viewModelScope.launch {
            delay(600)
            updateProgress(requestId) { if (it == CommandProgress.SENT) CommandProgress.RUNNING else it }
        }
        viewModelScope.launch {
            val outcome = link.run(command, REMOTE_TASK_TIMEOUT_MS, requestId) { handle(it, from = device) }
            remoteLinks.remove(requestId)
            val result = when (outcome) {
                is CommandOutcome.Success -> outcome.result
                else -> CommandResult(requestId, CommandStatus.ERROR, PcTransfer.describe(outcome))
            }
            handle(result, from = device)
        }
    }

    /** «Перекинь отчёт с домашнего на рабочий»: ход — служебными строками, итог — ответом под репликой. */
    private fun runTransfer(text: String, from: PairedDevice, to: PairedDevice, query: String) {
        val requestId = UUID.randomUUID().toString()
        add(ChatMessage(author = Author.ME, text = text, requestId = requestId, progress = CommandProgress.RUNNING))
        viewModelScope.launch {
            val result = PcTransfer.run(from, to, query, onNote = { note("🔁 $it") }, register = { linkRequests += it })
            updateProgress(requestId) { if (result.ok) CommandProgress.DONE else CommandProgress.ERROR }
            add(ChatMessage(author = Author.PC, text = (if (result.ok) "✅ " else "") + result.text, requestId = requestId))
        }
    }

    /**
     * Чип «Своих команд». Рецепт показываем в чате как реплику — у него будет
     * план, вопросы и итог, им нужно к чему-то прикрепиться. Остальные чипы —
     * мгновенные команды, как раньше, без реплики.
     */
    fun runCustomAction(record: CustomActionRecord) {
        if (record.type == CustomCommandType.RECIPE) {
            if (activeAgentOffline) {
                note("ПК сейчас офлайн — навык не запущен")
                return
            }
            sendTracked("${record.emoji} Навык «${record.label}»", record.toCommand())
        } else {
            sendCommand(record.toCommand())
        }
    }

    /** Реплика в чате + команда с тем же requestId: ответы, план и вопросы агента прикрепятся к ней. */
    private fun sendTracked(shownText: String, command: Command, imageBase64: String? = null): String {
        val requestId = UUID.randomUUID().toString()
        add(
            ChatMessage(
                author = Author.ME,
                text = shownText,
                imageBase64 = imageBase64,
                requestId = requestId,
                progress = CommandProgress.SENT,
            )
        )
        client.send(CommandRequest(requestId = requestId, command = command))
        // Эвристика «выполняется»: если через 0.6 с ответа ещё нет — считаем,
        // что команду приняли в работу.
        viewModelScope.launch {
            delay(600)
            updateProgress(requestId) { if (it == CommandProgress.SENT) CommandProgress.RUNNING else it }
        }
        return requestId
    }

    /**
     * Показать служебную строку в чате (например, ошибку голосового ввода).
     * Сеть не задействует — это чисто локальное сообщение для пользователя.
     */
    fun note(text: String) = add(ChatMessage(author = Author.SYSTEM, text = text))

    /**
     * Отправить готовую конкретную команду напрямую, минуя NaturalLanguage —
     * для команд, которые не нужно проговаривать/показывать в чате как
     * реплику (StartScreenStream/StopScreenStream, Click с экрана "Экран ПК").
     */
    fun sendCommand(command: Command) {
        client.send(CommandRequest(requestId = UUID.randomUUID().toString(), command = command))
    }

    /** Кнопка «📋⬆»: буфер обмена телефона -> на ПК. */
    fun sendPhoneClipboardToPc() {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(getApplication())?.toString()
        if (text.isNullOrBlank()) {
            note("буфер обмена телефона пуст")
            return
        }
        sendCommand(SetClipboard(text))
        note("буфер обмена телефона отправлен на ПК")
    }

    /** Кнопка «📋⬇»: запросить буфер обмена ПК — придёт CommandResult с ClipboardText (см. handle). */
    fun requestPcClipboard() = sendCommand(GetClipboard)

    /** Кладём полученный от ПК текст в буфер обмена самого телефона — готово для вставки где угодно. */
    private fun copyToPhoneClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("jarvis", text))
    }

    /**
     * Включить/выключить постоянное прослушивание слова «Фауст».
     * Разрешение RECORD_AUDIO спрашивает UI ДО вызова этого метода (ViewModel
     * не умеет показывать системные диалоги).
     */
    fun setWakeWordEnabled(on: Boolean) {
        _wakeWordEnabled.value = on
        viewModelScope.launch {
            settings.saveWakeWordEnabled(on)
            JarvisConnectionService.wakeWord(getApplication(), on)
            note(
                if (on) "wake-word включён — скажи «Фауст, открой ютуб»"
                else "wake-word выключен"
            )
        }
    }

    /** Переключатель «Рентген на ПК» в настройках. */
    fun setXrayEnabled(on: Boolean) {
        _xrayEnabled.value = on
        viewModelScope.launch { settings.saveXrayEnabled(on) }
        sendCommand(SetDisplayOption("xray", on))   // ответ «рентген включён на ПК» — в чат
    }

    /** Переключатель «Панель плана на ПК» в настройках. */
    fun setPlanPanelEnabled(on: Boolean) {
        _planPanelEnabled.value = on
        viewModelScope.launch { settings.savePlanPanelEnabled(on) }
        sendCommand(SetDisplayOption("plan", on))
    }

    /**
     * Сообщить ПК, что показывать, — молча, при каждом подключении. Так
     * переключатель на телефоне и то, что на экране ПК, не расходятся, даже
     * если ПК перезапускали или его настройки правили руками.
     */
    private fun syncDisplayOptions() {
        for (cmd in listOf(SetDisplayOption("xray", _xrayEnabled.value), SetDisplayOption("plan", _planPanelEnabled.value))) {
            val id = UUID.randomUUID().toString()
            silentRequests.add(id)
            client.send(CommandRequest(requestId = id, command = cmd))
        }
    }

    /**
     * Прервать выполняющуюся задачу. Остановка не мгновенная: агент проверяет
     * флаг между шагами, а прервать текущий запрос к модели нельзя — поэтому
     * честно предупреждаем пользователя, а не делаем вид, что всё встало сразу.
     */
    fun cancelTask() {
        sendCommand(CancelTask)
        note("отменяю задачу — агент остановится после текущего шага")
    }

    /**
     * Поправка к идущей задаче. Видна в ленте своей репликой с пометкой ✏️, а
     * ответ агента («поправка принята — перестраиваю план») придёт под ней.
     * Задачу заново не отправляем: агент сохранит сделанное и перестроит
     * только остаток плана.
     */
    fun sendCorrection(text: String) {
        if (text.isBlank()) return
        sendTracked("✏️ $text", CorrectTask(text))
    }

    /** «стоп», «отмена», «хватит» — во время задачи это не поправка, а отмена. */
    private fun isStopPhrase(text: String): Boolean =
        text.lowercase().replace(Regex("[^\\p{L} ]"), "").trim() in STOP_PHRASES

    /** Фото готово к отправке — уйдёт вместе со следующей просьбой. */
    fun attachPhoto(base64: String) {
        _pendingPhoto.value = base64
    }

    fun clearPhoto() {
        _pendingPhoto.value = null
    }

    /** Открыть видео-отчёт задачи: ПК пришлёт кадры (TaskReport). */
    fun openReport(taskId: String) {
        _report.value = ReportState.Loading(taskId)
        val requestId = UUID.randomUUID().toString()
        reportRequests.add(requestId)
        client.send(CommandRequest(requestId = requestId, command = GetTaskReport(taskId)))
    }

    /** Сохранить отчёт GIF-анимацией в «Загрузки» — чтобы переслать. Ответ — обычный FileTransfer. */
    fun saveReportGif(taskId: String) {
        sendCommand(GetTaskReport(taskId, asGif = true))
        note("запросил GIF у ПК — сохраню в Загрузки")
    }

    fun closeReport() {
        _report.value = null
    }

    /**
     * Ответить на вопрос ИИ-агента — кнопкой в карточке или текстом из поля
     * ввода (см. [send]). Ответ уходит командой AnswerQuestion; её результат
     * не рисуется отдельным пузырём, а переключает состояние карточки.
     */
    fun answerQuestion(messageId: String, answer: String) {
        if (answer.isBlank()) return
        val cardMessage = _messages.value.firstOrNull { it.id == messageId } ?: return
        val card = cardMessage.question ?: return
        if (!card.isOpen()) {
            // Агент уже не ждёт — не шлём впустую, а честно говорим почему.
            // Если это была новая команда, её надо просто отправить ещё раз.
            expireQuestion(messageId)
            note("вопрос уже неактуален — если это была новая команда, отправьте её ещё раз")
            return
        }
        val requestId = UUID.randomUUID().toString()
        answerRequests[requestId] = messageId
        updateQuestion(messageId) { it.copy(state = QuestionState.SENDING, answer = answer) }
        // Ответ — ещё и реплика в переписке: так диалог читается естественно
        // («Кому отправить?» — «Лере и Маше»), как в обычном мессенджере.
        add(ChatMessage(author = Author.ME, text = answer))
        // Спросил другой ПК — ответ уходит по его связи, а не на выбранный ПК
        // (тот про этот вопрос ничего не знает и ответил бы «нет такого вопроса»).
        val link = cardMessage.requestId?.let { remoteLinks[it] }
        if (link != null) {
            linkRequests += requestId
            if (!link.send(AnswerQuestion(card.questionId, answer), requestId)) {
                answerRequests.remove(requestId)
                expireQuestion(messageId)
                note("связь с «${link.device.name}» уже закрыта — ответ не отправлен")
            }
            return
        }
        client.send(CommandRequest(requestId = requestId, command = AnswerQuestion(card.questionId, answer)))
    }

    private fun expireQuestion(messageId: String) =
        updateQuestion(messageId) { if (it.state == QuestionState.ANSWERED) it else it.copy(state = QuestionState.EXPIRED) }

    /** Изменить карточку-вопрос в сообщении [messageId]. Атомарно: пишут два потока (UI и OkHttp). */
    private fun updateQuestion(messageId: String, transform: (QuestionCard) -> QuestionCard) {
        _messages.update { list ->
            list.map { m -> if (m.id == messageId && m.question != null) m.copy(question = transform(m.question)) else m }
        }
    }

    /**
     * Кладём завершённую задачу в историю. Текст запроса и момент отправки берём
     * из сообщения чата с тем же requestId — сам AgentReport их не содержит.
     */
    private fun saveToHistory(result: CommandResult, report: AgentReport) {
        val request = _messages.value
            .firstOrNull { it.author == Author.ME && it.requestId == result.requestId }
        viewModelScope.launch {
            history.add(
                TaskRecord(
                    id = result.requestId,
                    startedAtMs = request?.sentAtMs ?: System.currentTimeMillis(),
                    request = request?.text.orEmpty(),
                    completed = report.completed,
                    stepsTaken = report.stepsTaken,
                    summary = resultText(result),
                    trace = report.trace,
                )
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    fun addCustomAction(record: CustomActionRecord) {
        viewModelScope.launch { customActionsRepo.add(record) }
    }

    fun updateCustomAction(record: CustomActionRecord) {
        viewModelScope.launch { customActionsRepo.update(record) }
    }

    fun removeCustomAction(id: String) {
        viewModelScope.launch { customActionsRepo.remove(id) }
    }

    fun listFiles(folder: String) = sendCommand(ListFiles(folder))
    fun downloadFile(folder: String, fileName: String) = sendCommand(SendFileToPhone(folder, fileName))
    fun uploadFile(folder: String, fileName: String, base64: String) = sendCommand(SendFileToPc(folder, fileName, base64))

    /** Экран свернули (ON_STOP жизненного цикла) — держим соединение foreground-
     *  сервисом, только если есть незавершённая команда; иначе сервис не нужен. */
    fun onAppBackgrounded() {
        isBackgrounded = true
        if (hasPendingCommands()) JarvisConnectionService.taskKeepAlive(getApplication(), true)
    }

    /**
     * Экран вернули на передний план (ON_START) — keep-alive задачи больше не
     * нужен. ВАЖНО: именно taskKeepAlive(false), а не полная остановка сервиса —
     * иначе выключился бы и wake-word. Сервис сам себя остановит, если не
     * осталось ни одной причины жить.
     */
    fun onAppForegrounded() {
        isBackgrounded = false
        JarvisConnectionService.taskKeepAlive(getApplication(), false)
    }

    private fun hasPendingCommands(): Boolean =
        _messages.value.any { it.progress == CommandProgress.SENT || it.progress == CommandProgress.RUNNING }

    /** Декодирует пришедший от агента файл и сохраняет в Загрузки телефона. */
    private fun saveDownloadedFile(fileName: String, base64: String) {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val ok = saveToPhoneDownloads(getApplication(), fileName, bytes)
        note(if (ok) "файл «$fileName» сохранён в Загрузки" else "не удалось сохранить «$fileName»")
    }

    /**
     * Handle a message coming back from the relay.
     *
     * [from] — не null, если сообщение пришло по связи с другим (не выбранным)
     * ПК ([runOnRemote]). Тогда в ленте впереди имя этого ПК, а задача не
     * превращает поле ввода в «поправку» и «стоп»: они ушли бы на выбранный ПК.
     */
    private fun handle(msg: JarvisMessage, from: PairedDevice? = null) {
        // Ответ на команду, которую телефон послал через отдельную связь (перенос
        // файла с выбранного ПК), — его уже обработал тот, кто посылал.
        if (from == null && msg.requestId in linkRequests) return
        val pc = if (from != null) "🖥 ${from.name} · " else ""
        when (msg) {
            is CommandResult -> {
                // Служебная синхронизация настроек — в чат не пишем.
                if (silentRequests.remove(msg.requestId)) return
                // Список проектов — не реплика, а данные для экрана «Проекты».
                if (repoRequests.remove(msg.requestId)) {
                    _reposLoading.value = false
                    val data = msg.data
                    if (data is RepoList) {
                        _repos.value = data.repos
                        _reposNote.value = if (data.repos.isEmpty()) msg.message else null
                    } else {
                        _reposNote.value = msg.message ?: "ПК не ответил списком проектов"
                    }
                    return
                }
                // Кадры видео-отчёта — не реплика, а содержимое экрана отчёта.
                if (reportRequests.remove(msg.requestId)) {
                    val data = msg.data
                    _report.update { current ->
                        // Экран успели закрыть — ничего не открываем заново.
                        if (current == null) null
                        else if (data is TaskReport) ReportState.Ready(data)
                        else ReportState.Failed((current as? ReportState.Loading)?.taskId.orEmpty(),
                            msg.message ?: "ПК не прислал отчёт")
                    }
                    return
                }
                // Задача кончилась — поле ввода снова для новых команд, а не для поправок.
                if (_activeTaskId.value == msg.requestId) _activeTaskId.value = null
                // Итог задачи потерялся (например, при обрыве связи), и телефон всё
                // ещё считает её идущей. Агент на поправку ответил, что задачи нет, —
                // верим ему, иначе всё сказанное и дальше уходило бы поправками в никуда.
                if (msg.status == CommandStatus.ERROR && msg.message?.startsWith("сейчас нечего поправлять") == true) {
                    _activeTaskId.value = null
                }
                // Задача с экрана «Проекты» закончилась — карточки проектов устарели.
                if (devTasks.remove(msg.requestId)) refreshRepos()
                // Результат нашего же ответа на вопрос — отдельного пузыря не
                // нужно, только переключаем карточку: принят / уже неактуален.
                val answeredCardId = answerRequests.remove(msg.requestId)
                if (answeredCardId != null) {
                    if (msg.status == CommandStatus.OK) {
                        updateQuestion(answeredCardId) { it.copy(state = QuestionState.ANSWERED) }
                    } else {
                        expireQuestion(answeredCardId)
                        note("агент уже не ждал ответа: ${msg.message ?: "вопрос неактуален"}")
                    }
                    return
                }
                // Задача закончилась — её вопросы, на которые так и не ответили,
                // больше никто не ждёт; кнопки на них должны погаснуть.
                _messages.value
                    .filter { it.requestId == msg.requestId && it.question?.state == QuestionState.WAITING }
                    .forEach { expireQuestion(it.id) }

                val outcome = when (msg.status) {
                    CommandStatus.OK -> CommandProgress.DONE
                    CommandStatus.DENIED -> CommandProgress.DENIED
                    CommandStatus.ERROR, CommandStatus.TIMEOUT -> CommandProgress.ERROR
                }
                updateProgress(msg.requestId) { outcome }
                // Часть команд несёт данные, которые нужно применить СВЕРХ обычного текста
                // в чате (буфер обмена -> в буфер телефона, список файлов -> в диалог, файл -> сохранить).
                when (val data = msg.data) {
                    is ClipboardText -> copyToPhoneClipboard(data.text)
                    // Список файлов другого ПК не должен подменить диалог «Файлы» выбранного.
                    is FileList -> if (from == null) _fileList.value = data.entries
                    is FileTransfer -> saveDownloadedFile(data.fileName, data.base64)
                    is AgentReport -> {
                        saveToHistory(msg, data)
                        if (isBackgrounded) notifyAgentTaskFinished(getApplication(), resultText(msg))
                    }
                    else -> {}
                }
                // Незавершённых команд больше нет — эта причина держать сервис отпала
                // (wake-word, если включён, продолжит жить сам по себе).
                if (isBackgrounded && !hasPendingCommands()) {
                    JarvisConnectionService.taskKeepAlive(getApplication(), false)
                }
                // requestId у итога — чтобы экран «Проекты» нашёл итог своей задачи.
                add(ChatMessage(
                    author = Author.PC,
                    text = pc + resultText(msg),
                    requestId = msg.requestId,
                    // Итог ИИ-задачи — под ним кнопка «Видео-отчёт». Отчёт хранит
                    // тот ПК, что выполнял, а кнопка спрашивает выбранный — поэтому
                    // у задач с других ПК её пока нет.
                    reportTaskId = if (msg.data is AgentReport && from == null) msg.requestId else null,
                ))
            }
            is ScreenFrame -> {
                if (msg.seq != null) {
                    // Кадр живого стрима — не сообщение чата, а просто "текущая картинка".
                    _liveFrame.value = msg
                    lastFrameAtMs = System.currentTimeMillis()
                } else {
                    // Одиночный скриншот (Command.Screenshot) — как раньше, в чат.
                    // CommandResult — источник истины о статусе; если он уже пришёл
                    // с ошибкой/отказом, кадр (даже придя позже) не должен её стирать.
                    updateProgress(msg.requestId) {
                        if (it == CommandProgress.ERROR || it == CommandProgress.DENIED) it
                        else CommandProgress.DONE
                    }
                    add(ChatMessage(author = Author.PC, text = "📸 скриншот", imageBase64 = msg.base64))
                }
            }
            is AgentStep -> {
                if (from == null) markTaskActive(msg.requestId)
                // Промежуточный шаг agent_task — просто новая карточка в чате, без
                // обновления progress (за это отвечает финальный CommandResult с тем
                // же requestId, он придёт позже).
                add(ChatMessage(
                    author = Author.PC,
                    // Вторая строка описания — обоснование модели («зачем»), см. агент.
                    text = "$pc🔎 ${msg.description.replace("\n", "\n💭 ")}",
                    imageBase64 = msg.screenshotBase64,
                    targetX = msg.targetX,
                    targetY = msg.targetY,
                    agentStep = msg.step,
                ))
            }
            is AgentPlan -> {
                if (from == null) markTaskActive(msg.requestId)
                // Агент шлёт план целиком при каждом изменении. Карточка у задачи
                // одна: если она уже есть — меняем содержимое на месте, а не
                // добавляем в ленту пятую копию того же списка.
                var replaced = false
                _messages.update { list ->
                    list.map { m ->
                        if (m.plan != null && m.requestId == msg.requestId) {
                            replaced = true
                            m.copy(plan = msg)
                        } else m
                    }
                }
                if (!replaced) {
                    add(ChatMessage(
                        author = Author.PC,
                        // Этот текст и озвучит TTS при появлении карточки.
                        text = "${pc}План из ${msg.steps.size} шагов",
                        requestId = msg.requestId,
                        plan = msg,
                    ))
                }
            }
            is AgentQuestion -> {
                if (from == null) markTaskActive(msg.requestId)
                // В локальную переменную: smart cast свойства класса из другого
                // модуля (:shared) Kotlin не разрешает.
                val timeoutSec = msg.timeoutSeconds
                val expiresAt = timeoutSec?.let { System.currentTimeMillis() + it * 1000L }
                val message = ChatMessage(
                    author = Author.PC,
                    text = pc + msg.question,
                    // requestId задачи — по нему карточка погаснет, когда придёт итог задачи.
                    requestId = msg.requestId,
                    question = QuestionCard(
                        questionId = msg.questionId,
                        options = msg.options,
                        expiresAtMs = expiresAt,
                    ),
                )
                add(message)
                // Приложение свёрнуто — без уведомления человек о вопросе не узнает,
                // а агент тем временем простоит до таймаута и решит «ответа нет».
                if (isBackgrounded) notifyAgentQuestion(getApplication(), msg.question)
                // Агент перестанет ждать сам — карточка должна погаснуть в тот же
                // момент, а не висеть с активными кнопками до конца задачи.
                if (timeoutSec != null) {
                    viewModelScope.launch {
                        delay(timeoutSec * 1000L)
                        updateQuestion(message.id) {
                            if (it.state == QuestionState.WAITING) it.copy(state = QuestionState.EXPIRED) else it
                        }
                    }
                }
            }
            is AuthResult -> {
                val deviceName = _activeDevice.value?.name
                _status.value = when {
                    !msg.ok -> {
                        activeAgentOffline = false
                        _unauthorized.value = true
                        if (deviceName != null) "ПК «$deviceName» отвязан" else "этот ПК отвязан"
                    }
                    // Токен верный, но агент этого ПК сейчас не подключён к релею —
                    // явно и понятно об этом говорим, вместо неясного "агент не в сети".
                    msg.agentOnline == false -> {
                        activeAgentOffline = true
                        if (deviceName != null) "ПК «$deviceName» офлайн" else "ПК офлайн"
                    }
                    else -> {
                        activeAgentOffline = false
                        // ПК на связи — сообщаем ему, что показывать на экране.
                        syncDisplayOptions()
                        "подключено ✓"
                    }
                }
            }
            else -> { /* ping/pong etc. — ignore in the UI */ }
        }
    }

    /**
     * Короткий текст для чата (и для TTS — он озвучивает именно это поле).
     * message от релея/агента обычно уже короткий итог; если его нет, а есть
     * структурные данные (список процессов, отчёт агента) — сворачиваем их в
     * одну строку, а не показываем/озвучиваем длинный список целиком.
     */
    private fun resultText(msg: CommandResult): String = msg.message ?: when (val data = msg.data) {
        is ProcessList -> "процессов: ${data.processes.size}"
        is AgentReport -> if (data.completed) "готово за ${data.stepsTaken} шагов"
            else "не завершено за ${data.stepsTaken} шагов"
        else -> msg.status.name
    }

    /**
     * Знак от ИИ-задачи (шаг, план, вопрос) — значит, она идёт. Только если её
     * реплика ещё ждёт итога: шаг, опоздавший после итога, не должен снова
     * превратить поле ввода в «поправку».
     */
    private fun markTaskActive(requestId: String) {
        val pending = _messages.value.any {
            it.author == Author.ME && it.requestId == requestId &&
                (it.progress == CommandProgress.SENT || it.progress == CommandProgress.RUNNING)
        }
        if (pending) _activeTaskId.value = requestId
    }

    /** Обновить индикатор у сообщения-команды с данным requestId. */
    private fun updateProgress(
        requestId: String,
        transform: (CommandProgress?) -> CommandProgress?,
    ) {
        _messages.value = _messages.value.map { m ->
            if (m.author == Author.ME && m.requestId == requestId)
                m.copy(progress = transform(m.progress))
            else m
        }
    }

    private fun add(m: ChatMessage) {
        // update, а не value = value + m: сообщения добавляют два потока (UI и
        // OkHttp), и при «прочитал — дописал — записал» одно из двух могло
        // потеряться. С вопросами это стало заметнее: ответ и карточка
        // меняются почти одновременно.
        _messages.update { it + m }
    }
}
