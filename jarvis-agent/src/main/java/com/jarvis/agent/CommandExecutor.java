package com.jarvis.agent;

import com.jarvis.agent.llm.RelayLlmClient;
import com.jarvis.agent.plan.GeminiTaskPlanner;
import com.jarvis.agent.plan.PlanRunner;
import com.jarvis.agent.plan.PlanStep;
import com.jarvis.agent.plan.TaskPlanner;
import com.jarvis.agent.protocol.*;
import com.jarvis.agent.vision.BrowserAgent;
import com.jarvis.agent.vision.CancelSignal;
import com.jarvis.agent.vision.Clarifications;
import com.jarvis.agent.vision.GeminiVisionAgent;
import com.jarvis.agent.vision.ImageUtil;
import com.jarvis.agent.vision.RunJournal;
import com.jarvis.agent.vision.ScreenAgent;
import com.jarvis.agent.vision.SensitiveInputGuard;
import com.jarvis.agent.vision.StepReporter;
import com.jarvis.agent.vision.TrajectoryCache;
import com.jarvis.agent.vision.UserAsker;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Does the actual work on the PC. Given a Command from the relay, it drives
 * java.awt.Robot / ProcessBuilder / ProcessHandle and returns one or more
 * messages to send back (a CommandResult, plus a ScreenFrame for screenshots).
 *
 * Everything here runs with the rights of the logged-in user — the agent can
 * do only what that user can, which is part of the safety story for the thesis.
 */
public class CommandExecutor {

    /** Жёсткий потолок шагов agent_task — независимо от того, что попросила LLM в команде. */
    private static final int MAX_AGENT_STEPS = 20;
    /** Жёсткий потолок общего времени на одну задачу agent_task. */
    private static final Duration AGENT_TASK_TIMEOUT = Duration.ofMinutes(3);
    /** Сколько ждать ответа на диалог подтверждения (ConfirmationGate), прежде чем считать это отказом. */
    private static final int CONFIRM_TIMEOUT_SECONDS = 20;

    private final Robot robot;
    private final AgentTray tray; // может быть null (например, в тестах) — тогда статус в трей не пишем
    /** Чем занят агент сейчас (цель agent_task или «показ экрана»); null — свободен. */
    private final AtomicReference<String> busyGoal = new AtomicReference<>();
    /**
     * Попросили ли отменить текущую задачу. Сбрасывается в начале каждой новой
     * задачи, взводится командой cancel_task (которая приходит в ДРУГОМ потоке —
     * отсюда Atomic, а не обычный boolean).
     */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /**
     * Спрашивать ли подтверждение на самом ПК перед опасными командами
     * (agent_task, kill_process, передача файлов) — см. ConfirmationGate.
     * Выключается флагом confirmSensitiveCommands=false в agent.properties.
     *
     * Читается на каждую команду, а не один раз при запуске: иначе правка
     * agent.properties не действовала бы до перезапуска агента, хотя весь
     * остальной файл настроек подхватывается сразу.
     */
    private boolean confirmSensitive() {
        return AgentSettings.confirmSensitiveCommands();
    }
    /** Живой экран: шлёт кадры сам, поэтому ему нужен sender, а не возврат из execute(). */
    private final ScreenStreamer streamer;
    /** Для AgentStep — шаги agent_task шлются сами по себе, как и кадры стрима. */
    private final Consumer<JarvisMessage> sender;
    /** Вопросы ИИ-агента на телефон и ответы на них (см. PhoneQuestions). */
    private final PhoneQuestions phoneQuestions;

    /**
     * Живут между задачами, а не создаются на каждую: BrowserAgent держит окно
     * браузера открытым после выполнения (чтобы был виден результат) и
     * переиспользует его в следующей задаче.
     */
    private final GeminiVisionAgent brain;
    /** Общий клиент модели — по его счётчику видно, сколько обращений к ИИ стоила задача. */
    private final RelayLlmClient llmClient;
    private final BrowserAgent browserAgent;
    private final ScreenAgent screenAgent;
    /** Разбивает большую просьбу на подзадачи (этап 2) — через тот же прокси релея, что и brain. */
    private final TaskPlanner planner;
    /** Проверяет «готово» каждой подзадачи плана: файл на диске и отдельный взгляд модели на окно. */
    private final com.jarvis.agent.plan.StepVerifier verifier;
    /** Память планов: та же просьба во второй раз идёт без планировщика (см. PlanMemory). */
    private final com.jarvis.agent.plan.PlanMemory planMemory = new com.jarvis.agent.plan.PlanMemory.File();

    /**
     * Чек-лист навыка: шаги, показанные человеком, — на телефон и на панель ПК.
     *
     * @param current номер шага, который сейчас идёт (с 0); равен числу шагов — всё готово
     */
    private void publishSkillProgress(String requestId, List<String> descriptions, int current) {
        List<JarvisMessage.PlanItem> items = new ArrayList<>();
        for (int i = 0; i < descriptions.size(); i++) {
            JarvisMessage.PlanItemStatus st = i < current ? JarvisMessage.PlanItemStatus.DONE
                    : i == current ? JarvisMessage.PlanItemStatus.RUNNING : JarvisMessage.PlanItemStatus.PENDING;
            items.add(new JarvisMessage.PlanItem(descriptions.get(i), st, null));
        }
        sender.accept(new JarvisMessage.AgentPlan(requestId, items, 1));
        FaustOverlayController.onPlan(items, 1);
    }

    /**
     * Итог последней подзадачи плана — то, что человеку важнее всего услышать:
     * «отправлено: main → origin/main», «процесс gradle завершился через 3 мин».
     * Итог задачи озвучивается на телефоне, поэтому строка короткая.
     */
    static String resultNote(ResultData.AgentReport report) {
        String last = null;
        for (String line : report.trace()) {
            String t = line.strip();
            if (t.startsWith("итог подзадачи:")) last = t.substring("итог подзадачи:".length()).strip();
        }
        if (last == null || last.isEmpty() || last.equals("готово")) return "";
        return "\nитог: " + (last.length() > 160 ? last.substring(0, 159) + "…" : last);
    }

    /**
     * «⚡ из памяти» в итог задачи: во сколько раз быстрее, чем в первый раз.
     * Это видно и в чате, и голосом — итог на телефоне озвучивается.
     */
    private static String memoryNote(ResultData.AgentReport report, long planBaselineMs) {
        if (planBaselineMs > 0) {
            return "\n⚡ из памяти — в первый раз задача заняла " + (planBaselineMs / 1000) + " с";
        }
        boolean replayed = report.trace().stream().anyMatch(l -> l.contains(TrajectoryCache.REPLAY_MARK));
        return replayed ? "\n⚡ выполнено из памяти, без обращений к модели" : "";
    }

    private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

    /**
     * @param sender куда слать сообщения, которые агент отправляет САМ, без
     *               запроса от телефона (кадры живого экрана) — это тот же
     *               send(), которым RelayClient шлёт Pong и результаты команд.
     */
    public CommandExecutor(AgentConfig config, TokenStore tokenStore, AgentTray tray,
                           Consumer<JarvisMessage> sender) throws AWTException {
        this.robot = new Robot(); // controls mouse/keyboard/screen
        this.tray = tray;
        RelayLlmClient relayLlm = new RelayLlmClient(config, tokenStore);
        this.llmClient = relayLlm;
        this.brain = new GeminiVisionAgent(relayLlm);
        this.planner = new GeminiTaskPlanner(relayLlm, AppCatalog::installedAppNames, ScriptCatalog::names,
                RepoCatalog::names).withLists(RecipientLists::all);
        this.verifier = new com.jarvis.agent.plan.ResultVerifier(
                prompt -> relayLlm.generate(prompt, null),
                () -> new com.jarvis.agent.vision.UiaSnapshot().captureForegroundWindow());
        this.browserAgent = new BrowserAgent(brain);
        this.screenAgent = new ScreenAgent(brain);
        // Пиксельный агент открывает программы тем же точным способом, что и
        // команда open_app («Пуск» + зашитые программы), а не кликом по значку.
        this.screenAgent.setAppOpener(this::openAndFocus);
        this.streamer = new ScreenStreamer(robot, sender, busyGoal);
        this.sender = sender;
        this.phoneQuestions = new PhoneQuestions(sender);
        // Кнопки «Стоп» на оверлеях делают ровно то же, что команда cancel_task
        // с телефона: поднимают флаг, который цикл агента проверяет между шагами.
        FaustOverlayController.install(() -> cancelRequested.set(true));
    }

    /** Run one command, return the messages to send back to the relay. */
    public List<JarvisMessage> execute(String requestId, Command command) {
        // Любая обычная команда с телефона — короткая карточка «Фауст» на ПК,
        // чтобы человек за компьютером видел: это пришло с телефона, а не глюк.
        // Для ИИ-задачи и «Экрана ПК» describeCommand вернёт null — у них свои анимации.
        FaustOverlayController.onCommand(describeCommand(command));
        try {
            return switch (command) {
                case Command.OpenUrl c        -> ok(requestId, openUrl(c.url()));
                case Command.Screenshot c     -> screenshot(requestId);
                case Command.ListProcesses c  -> listProcesses(requestId);
                case Command.KillProcess c    -> confirmedThen(requestId,
                        "Завершить процессы, содержащие «" + c.processName() + "»?",
                        () -> killProcess(c.processName()));
                case Command.LockScreen c     -> ok(requestId, lockScreen());
                case Command.Click c          -> remoteControl(requestId, () -> click(c.x(), c.y()));
                case Command.TypeText c        -> remoteControl(requestId, () -> typeText(c.text()));
                case Command.Scroll c         -> remoteControl(requestId, () -> scroll(c.dy()));
                case Command.OpenApp c        -> openApp(requestId, c.appName());
                case Command.SetVolume c      -> ok(requestId, setVolume(c.level()));
                case Command.QuickAction c    -> ok(requestId, quickAction(c));
                case Command.Sequence c       -> sequence(requestId, c);
                case Command.DevRepos c       -> devRepos(requestId);
                case Command.AgentTask c      -> agentTask(requestId, c);
                case Command.CancelTask c     -> cancelTask(requestId);
                case Command.AnswerQuestion c -> answerQuestion(requestId, c);
                case Command.CorrectTask c    -> correctTask(requestId, c);
                case Command.PhotoTask c      -> photoTask(requestId, c);
                case Command.GetTaskReport c  -> getTaskReport(requestId, c);
                case Command.SetDisplayOption c -> setDisplayOption(requestId, c);
                case Command.StartScreenStream c -> startScreenStream(requestId, c);
                case Command.StopScreenStream c  -> stopScreenStream(requestId);
                case Command.GetClipboard c       -> getClipboard(requestId);
                case Command.SetClipboard c       -> ok(requestId, setClipboard(c.text()));
                case Command.ListFiles c          -> listFiles(requestId, c.folder());
                case Command.SendFileToPhone c    -> confirmedList(requestId,
                        "Отправить файл «" + c.fileName() + "» из папки «" + c.folder() + "» на телефон?",
                        () -> sendFileToPhone(requestId, c.folder(), c.fileName()));
                case Command.SendFileToPc c       -> confirmedThen(requestId,
                        "Сохранить присланный файл «" + c.fileName() + "» в папку «" + c.folder() + "» на этом ПК?",
                        () -> receiveFileFromPhone(c.folder(), c.fileName(), c.base64()));
                default                        -> error(requestId, "команда не поддерживается агентом");
            };
        } catch (Exception e) {
            return error(requestId, "ошибка выполнения: " + e.getMessage());
        }
    }

    // --- проекты (git) ---------------------------------------------------------

    /**
     * Экран «Проекты» на телефоне: разрешённые репозитории и их состояние.
     * Только чтение и без путей на диске — телефону они не нужны. Сервер не
     * опрашивается (никакого fetch): ответ мгновенный и без сети.
     */
    private List<JarvisMessage> devRepos(String requestId) {
        List<ResultData.RepoInfo> repos = new ArrayList<>();
        for (RepoCatalog.Repo r : RepoCatalog.all()) repos.add(GitTool.info(r.name(), r.path()));
        String msg = repos.isEmpty()
                ? "разрешённых репозиториев нет — добавьте строку «название = путь» в " + RepoCatalog.file()
                : "проектов: " + repos.size();
        return List.of(new JarvisMessage.CommandResult(requestId, CommandStatus.OK, msg, new ResultData.RepoList(repos)));
    }

    // --- быстрые действия и списки команд ------------------------------------

    /**
     * Что можно выполнять внутри списка команд. Релей это уже проверил белым
     * списком, но агент проверяет сам: список не должен стать способом обойти
     * подтверждение ИИ-задачи или запустить вложенный список.
     */
    private static final java.util.Set<String> IN_SEQUENCE = java.util.Set.of(
            "set_volume", "quick_action", "open_url", "open_app", "screenshot", "lock_screen",
            "list_processes", "kill_process");

    /**
     * «Сделай потише и открой ютуб» — простые команды по очереди, итог одним
     * сообщением: «1) громкость 30%  2) открыт youtube.com». Ошибка одной
     * команды не отменяет остальные: они независимы (громкость не зависит от
     * того, открылся ли сайт), а итог честно покажет, что не вышло.
     */
    private List<JarvisMessage> sequence(String requestId, Command.Sequence seq) {
        List<Command> cmds = seq.commands() == null ? List.of() : seq.commands();
        if (cmds.isEmpty() || cmds.size() > Command.Sequence.MAX) {
            return error(requestId, "в списке должно быть от 1 до " + Command.Sequence.MAX + " команд");
        }
        List<JarvisMessage> out = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        boolean allOk = true;
        for (int i = 0; i < cmds.size(); i++) {
            Command c = cmds.get(i);
            if (c == null || !IN_SEQUENCE.contains(c.name())) {
                lines.add((i + 1) + ") " + (c == null ? "пусто" : c.name()) + " — в списке не выполняется");
                allOk = false;
                continue;
            }
            for (JarvisMessage m : execute(requestId, c)) {
                if (m instanceof JarvisMessage.CommandResult r) {
                    lines.add((i + 1) + ") " + r.message());
                    allOk &= r.status() == CommandStatus.OK;
                } else {
                    out.add(m); // снимок экрана и т.п. — как есть
                }
            }
        }
        out.add(new JarvisMessage.CommandResult(requestId, allOk ? CommandStatus.OK : CommandStatus.ERROR,
                String.join("\n", lines), null));
        return out;
    }

    /** Виртуальные клавиши Windows — у Java Robot медиаклавиш нет. */
    private static final int VK_MEDIA_NEXT = 0xB0, VK_MEDIA_PREV = 0xB1, VK_MEDIA_PLAY_PAUSE = 0xB3,
            VK_LWIN = 0x5B, VK_M = 0x4D;

    private String quickAction(Command.QuickAction c) throws Exception {
        String action = c.action() == null ? "" : c.action();
        return switch (action) {
            case "play_pause" -> { keys(VK_MEDIA_PLAY_PAUSE); yield "пауза / продолжить"; }
            case "next_track" -> { keys(VK_MEDIA_NEXT); yield "следующий трек"; }
            case "prev_track" -> { keys(VK_MEDIA_PREV); yield "предыдущий трек"; }
            // Win+M сворачивает всё и, в отличие от Win+D, не разворачивает обратно при повторе.
            case "minimize_all" -> { keys(VK_LWIN, VK_M); yield "окна свёрнуты"; }
            case "volume_delta" -> changeVolume(c.amount());
            default -> throw new IllegalArgumentException("неизвестное быстрое действие: " + action);
        };
    }

    /**
     * Нажать сочетание клавиш через WinAPI keybd_event: все по порядку вниз,
     * потом в обратном порядке вверх. Медиаклавиши Windows отдаёт тому
     * плееру, который сейчас играет (браузер, Spotify, Яндекс Музыка), — не
     * важно, какое окно впереди.
     */
    private static void keys(int... vks) {
        if (!OS.contains("win")) throw new UnsupportedOperationException("быстрые действия — только для Windows");
        for (int vk : vks) Keys.U32.keybd_event((byte) vk, (byte) 0, 0, null);
        for (int i = vks.length - 1; i >= 0; i--) Keys.U32.keybd_event((byte) vks[i], (byte) 0, 2 /* KEYEVENTF_KEYUP */, null);
    }

    /** keybd_event из user32.dll: в готовом User32 из jna-platform её нет, объявляем сами. */
    private interface Keys extends com.sun.jna.win32.StdCallLibrary {
        Keys U32 = com.sun.jna.Native.load("user32", Keys.class);

        void keybd_event(byte bVk, byte bScan, int dwFlags, com.sun.jna.Pointer dwExtraInfo);
    }

    // --- живой экран ------------------------------------------------------

    /**
     * Ответ на команду — только «стрим запущен»; сами кадры летят отдельными
     * сообщениями из ScreenStreamer, а не возвратом из execute().
     */
    private List<JarvisMessage> startScreenStream(String requestId, Command.StartScreenStream c) {
        if (!streamer.start(requestId, c.fps(), c.maxWidth())) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        return ok(requestId, "показываю экран");
    }

    private List<JarvisMessage> stopScreenStream(String requestId) {
        streamer.stop();
        return ok(requestId, "показ экрана остановлен");
    }

    /**
     * Отмена текущей задачи. Выполняется в потоке чтения сокета (не в потоке
     * задачи) и делает ровно одно: взводит флаг. Сам цикл агента проверяет его
     * между шагами и выходит — поэтому фактическая остановка занимает до одного
     * шага, обычно несколько секунд.
     */
    private List<JarvisMessage> cancelTask(String requestId) {
        String goal = busyGoal.get();
        if (goal == null) {
            return ok(requestId, "сейчас нечего отменять");
        }
        cancelRequested.set(true);
        return ok(requestId, "отменяю: " + goal);
    }

    /**
     * Ответ человека на вопрос ИИ-агента. Как и отмена, выполняется в потоке
     * чтения сокета: поток задачи в это время спит в PhoneQuestions.ask и ждёт
     * именно этого вызова.
     *
     * Телефон по результату понимает, дошёл ли ответ: «неактуален» значит, что
     * агент уже перестал ждать (истекло время, задачу отменили или она кончилась).
     */
    private List<JarvisMessage> answerQuestion(String requestId, Command.AnswerQuestion c) {
        return phoneQuestions.answer(c.questionId(), c.answer())
                ? ok(requestId, "ответ передан агенту")
                : error(requestId, "вопрос уже неактуален");
    }

    // --- поправка на ходу, фото с телефона, видео-отчёт -----------------------

    /**
     * Вопросы и поправки идущей задачи. Поток задачи заводит их в начале
     * runAgentTask, а поправку кладёт сюда поток чтения сокета — отсюда volatile.
     */
    private volatile Clarifications activeQuestions;

    /**
     * Поправка к идущей задаче («нет, не Пете, а Васе»). Как и отмена, работает
     * в потоке чтения сокета и только передаёт поправку — что с ней делать,
     * решает поток задачи:
     * <ul>
     *   <li>задача по плану — текущая подзадача прерывается после ближайшего
     *       шага, и хвост плана перестраивается с учётом поправки (PlanRunner);</li>
     *   <li>задача в одной программе — поправка попадает в цель, и модель увидит
     *       её уже на следующем шаге (Clarifications.goalFor).</li>
     * </ul>
     */
    private List<JarvisMessage> correctTask(String requestId, Command.CorrectTask c) {
        Clarifications q = activeQuestions;
        if (busyGoal.get() == null || q == null) {
            return error(requestId, "сейчас нечего поправлять — отправьте это новой задачей");
        }
        if (!q.addCorrection(c.text())) return error(requestId, "пустая поправка");
        FaustOverlayController.onThinking("Поправка с телефона: " + c.text().strip());
        return ok(requestId, "поправка принята: «" + c.text().strip() + "» — перестраиваю план");
    }

    /** Сколько текста с фото вставлять в цель: остальное модель всё равно не удержит в голове. */
    private static final int PHOTO_TEXT_MAX = 2000;

    /**
     * Задача с фотографией с телефона: «сохрани в конспект по базам данных и
     * отправь в группу» + снимок доски.
     *
     * <p>Фото само по себе модели-исполнителю не передаётся: она видит экран ПК,
     * а не телефон. Поэтому сначала код делает то, что умеет надёжно:
     * <ol>
     *   <li>сохраняет фото в «Изображения\Фауст» — теперь это обычный файл, и
     *       find_file/paste_file прикрепят его в любом мессенджере;</li>
     *   <li>один раз просит модель переписать текст с фото (через релей —
     *       ключ ИИ остаётся там) и кладёт текст в буфер обмена;</li>
     *   <li>отдаёт обычной agent_task цель, где всё это записано фактами:
     *       путь к файлу и сам текст. Дальше — план, подтверждение, вопросы,
     *       как у любой задачи.</li>
     * </ol>
     */
    private List<JarvisMessage> photoTask(String requestId, Command.PhotoTask c) throws IOException {
        // Занят — не сохраняем фото и не тратим запрос к ИИ впустую.
        if (busyGoal.get() != null) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        if (c.text() == null || c.text().isBlank()) return error(requestId, "что сделать с фото? — добавьте просьбу");
        byte[] jpeg;
        try {
            jpeg = Base64.getDecoder().decode(c.imageBase64());
        } catch (IllegalArgumentException e) {
            return error(requestId, "фото повреждено при передаче");
        }
        if (jpeg.length == 0 || jpeg.length > FileCatalog.MAX_FILE_BYTES) {
            return error(requestId, "фото пустое или больше 5 МБ");
        }
        Path folder = FileCatalog.resolveFolder("Изображения")
                .orElseThrow(() -> new IOException("не нашлась папка «Изображения»"))
                .resolve("Фауст");
        Files.createDirectories(folder);
        String name = "фото-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".jpg";
        Path saved = folder.resolve(name);
        Files.write(saved, jpeg);

        if (tray != null) tray.setTaskStatus("читаю фото с телефона");
        FaustOverlayController.onCommand("Читаю фото с телефона");
        PhotoText recognized = recognizePhoto(jpeg);
        if (recognized.text() != null) setClipboard(recognized.text());

        return agentTask(requestId, new Command.AgentTask(photoGoal(c.text(), saved, recognized),
                AgentScope.SCREEN, MAX_AGENT_STEPS));
    }

    /** Что модель увидела на фото: текст (null — текста нет) и одна фраза описания. */
    record PhotoText(String text, String description) {}

    private PhotoText recognizePhoto(byte[] jpeg) {
        try {
            return parsePhotoReply(llmClient.generate(PHOTO_PROMPT, jpeg));
        } catch (Exception e) {
            // Без распознавания задача всё равно выполнима: файл с фото на месте.
            return new PhotoText(null, null);
        }
    }

    static final String PHOTO_PROMPT = """
            Это фото, которое человек снял телефоном (доска, конспект, документ, экран).
            1) Перепиши ВЕСЬ текст с фото дословно, сохраняя строки, нумерацию и формулы.
               Ничего не добавляй и не исправляй. Если текста нет — напиши ровно: НЕТ ТЕКСТА
            2) Последней строкой напиши: ОПИСАНИЕ: <одно короткое предложение, что на фото>
            """;

    /** Разбор ответа модели на PHOTO_PROMPT. Не private — ради тестов. */
    static PhotoText parsePhotoReply(String reply) {
        if (reply == null || reply.isBlank()) return new PhotoText(null, null);
        String text = reply.strip();
        String description = null;
        int at = text.lastIndexOf("ОПИСАНИЕ:");
        if (at >= 0) {
            description = text.substring(at + "ОПИСАНИЕ:".length()).strip();
            text = text.substring(0, at).strip();
        }
        if (text.isEmpty() || text.toUpperCase(java.util.Locale.ROOT).startsWith("НЕТ ТЕКСТА")) text = null;
        if (text != null && text.length() > PHOTO_TEXT_MAX) text = text.substring(0, PHOTO_TEXT_MAX) + "…";
        return new PhotoText(text, description == null || description.isEmpty() ? null : description);
    }

    /**
     * Цель задачи с фото: просьба человека + факты, которые установил код.
     * Факты — в том же виде, что у скачанных файлов (DownloadWatcher): точный путь
     * важнее слов модели, его исполнитель и вставит в мессенджер.
     */
    static String photoGoal(String request, Path saved, PhotoText photo) {
        StringBuilder sb = new StringBuilder(request.strip());
        sb.append("\n\nФОТО С ТЕЛЕФОНА уже сохранено на этом ПК: ").append(saved)
          .append(" (файл «").append(saved.getFileName()).append("» в папке «Изображения\\Фауст»; ")
          .append("чтобы приложить его к сообщению — find_file по имени и paste_file).");
        if (photo.description() != null) sb.append("\nНа фото: ").append(photo.description());
        if (photo.text() != null) {
            sb.append("\nТЕКСТ С ФОТО (распознан и уже лежит в буфере обмена — в документ его можно ")
              .append("вставить Ctrl+V, пока буфер не занят файлом):\n«").append(photo.text()).append("»");
        } else {
            sb.append("\nТекста на фото не найдено.");
        }
        return sb.toString();
    }

    /**
     * Видео-отчёт о законченной задаче: кадры (TaskReport) — посмотреть в
     * приложении, или GIF (FileTransfer) — сохранить в «Загрузки» и переслать.
     * Только чтение: файлы берутся из папки отчётов, имя папки — из очищенного id.
     */
    private List<JarvisMessage> getTaskReport(String requestId, Command.GetTaskReport c) throws IOException {
        if (c.asGif()) {
            Optional<Path> gif = TaskReportRecorder.gif(c.taskId());
            if (gif.isEmpty()) return error(requestId, "видео-отчёта этой задачи нет на ПК");
            long size = Files.size(gif.get());
            if (size > FileCatalog.MAX_FILE_BYTES) {
                return error(requestId, "GIF слишком большой (" + (size / 1024 / 1024) + " МБ) — посмотрите отчёт в приложении");
            }
            String name = "фауст-отчёт-" + TaskReportRecorder.safeName(c.taskId()).substring(0,
                    Math.min(8, TaskReportRecorder.safeName(c.taskId()).length())) + ".gif";
            return List.of(new JarvisMessage.CommandResult(requestId, CommandStatus.OK, "GIF-отчёт отправлен",
                    new ResultData.FileTransfer(name, Base64.getEncoder().encodeToString(Files.readAllBytes(gif.get())),
                            size)));
        }
        Optional<ResultData.TaskReport> report = TaskReportRecorder.load(c.taskId());
        if (report.isEmpty()) return error(requestId, "видео-отчёта этой задачи нет на ПК");
        return List.of(new JarvisMessage.CommandResult(requestId, CommandStatus.OK,
                "видео-отчёт: " + report.get().frames().size() + " кадров", report.get()));
    }

    /**
     * Что показывать на экране ПК — переключатели из настроек телефона.
     *
     * <p>Разрешены только эти ключи. Команда пишет в agent.properties, и если
     * бы ключ приходил с телефона как есть, через неё можно было бы выключить,
     * например, подтверждение опасных команд (confirmSensitiveCommands).
     */
    private static final java.util.Map<String, String> DISPLAY_OPTIONS = java.util.Map.of(
            "xray", AgentSettings.OVERLAY_XRAY,
            "plan", AgentSettings.OVERLAY_PLAN);

    private List<JarvisMessage> setDisplayOption(String requestId, Command.SetDisplayOption c) {
        String key = c.option() == null ? null : DISPLAY_OPTIONS.get(c.option());
        if (key == null) return error(requestId, "такой настройки показа нет: " + c.option());
        try {
            AgentSettings.save(key, String.valueOf(c.enabled()));
        } catch (IOException e) {
            return error(requestId, "не удалось сохранить настройку: " + e.getMessage());
        }
        // Выключили посреди задачи — убрать с экрана сразу, а не в конце.
        FaustOverlayController.onDisplayOptionsChanged();
        String what = "xray".equals(c.option()) ? "рентген" : "панель плана";
        return ok(requestId, what + (c.enabled() ? " включён на ПК" : " выключен на ПК"));
    }

    /**
     * Как агент спрашивает человека в рамках задачи requestId: вопрос уходит на
     * телефон, а на экране ПК виджет и трей показывают, что агент не завис, а ждёт.
     */
    private UserAsker askerFor(String requestId, String goal) {
        return (question, options, cancel) -> {
            if (tray != null) tray.setTaskStatus("ждёт ответа на телефоне: " + question);
            FaustOverlayController.onThinking("Жду ответа с телефона");
            int timeoutSec = AgentSettings.number(AgentSettings.ASK_TIMEOUT_SEC, 120, 10, 600);
            String answer = phoneQuestions.ask(requestId, question, options, timeoutSec, cancel);
            if (tray != null) tray.setTaskStatus("выполняю: " + goal);
            FaustOverlayController.onStep(answer == null ? "Ответа нет" : "Ответ получен");
            return answer;
        };
    }

    // --- подтверждение опасных команд на ПК -------------------------------

    /** confirmSensitive выключен (agent.properties) -> сразу ALLOWED, без диалога. */
    private ConfirmationGate.Decision askConfirmation(String description) {
        return confirmSensitive()
                ? ConfirmationGate.ask(description, CONFIRM_TIMEOUT_SECONDS)
                : ConfirmationGate.Decision.ALLOWED;
    }

    /** Команда обычно возвращает просто текст (как ok(...)) — сперва подтверждение, потом действие. */
    private List<JarvisMessage> confirmedThen(String requestId, String description, ThrowingSupplier<String> action)
            throws Exception {
        return confirmedList(requestId, description, () -> ok(requestId, action.get()));
    }

    /** Команда сама строит список сообщений (как sendFileToPhone) — сперва подтверждение, потом действие. */
    private List<JarvisMessage> confirmedList(String requestId, String description,
                                               ThrowingSupplier<List<JarvisMessage>> action) throws Exception {
        return switch (askConfirmation(description)) {
            case ALLOWED -> action.get();
            case DENIED -> error(requestId, "отклонено на ПК");
            case TIMEOUT -> error(requestId, "не подтверждено на ПК за " + CONFIRM_TIMEOUT_SECONDS + " с — отклонено");
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Связь с релеем пропала — кадры отправлять некуда, стрим гасим. */
    public void onDisconnected() {
        streamer.stop();
    }

    // --- vision agent (browser/screen computer-use) ---------------------

    /**
     * Единая точка входа agent_task: гарантирует, что одновременно выполняется
     * только одна задача (повторный вызов, пока эта не закончилась, -> ERROR
     * "агент занят"), и снимает busy-флаг/статус в трее по завершении
     * независимо от исхода.
     */
    /** Занят ли агент ИИ-задачей или показом экрана — нужно обучению навыкам. */
    public boolean isBusy() {
        return busyGoal.get() != null;
    }

    private List<JarvisMessage> agentTask(String requestId, Command.AgentTask task) {
        if (DemoRecorder.isRecording()) {
            return error(requestId, "на ПК сейчас идёт запись навыка — дождитесь её конца");
        }
        if (!busyGoal.compareAndSet(null, task.goal())) {
            return error(requestId, "агент занят: выполняется \"" + busyGoal.get() + "\"");
        }
        // Сбрасываем ПОСЛЕ захвата busyGoal: иначе отмена, пришедшая в момент
        // между двумя задачами, могла бы погасить уже следующую.
        cancelRequested.set(false);
        try {
            if (tray != null) tray.setTaskStatus("ждёт подтверждения: " + task.goal());
            ConfirmationGate.Decision decision = askConfirmation(
                    "Разрешить автономному агенту действовать на этом ПК?\nЦель: «" + task.goal() + "»");
            if (decision != ConfirmationGate.Decision.ALLOWED) {
                return error(requestId, decision == ConfirmationGate.Decision.DENIED
                        ? "отклонено на ПК"
                        : "не подтверждено на ПК за " + CONFIRM_TIMEOUT_SECONDS + " с — отклонено");
            }
            // Дедлайн задачи считается внутри runAgentTask — время на диалог его не съедает.
            return runAgentTask(requestId, task);
        } finally {
            busyGoal.set(null);
            if (tray != null) tray.clearTaskStatus();
        }
    }

    /**
     * Computer-use агент. Крутит цикл «снимок -> LLM -> действие» и
     * возвращает CommandResult с ResultData.AgentReport (трейс шагов), плюс
     * финальный ScreenFrame — снимок результата, который телефон покажет
     * рядом с итогом. BROWSER — по DOM-элементам (надёжнее, дешевле), SCREEN —
     * по пикселям всего экрана через Robot (медленнее и менее точно, зато
     * работает в любой программе, не только в браузере).
     *
     * Вызывается в отдельном потоке (RelayClient.agentPool), поэтому сокет
     * продолжает читаться всё время задачи — и cancel_task доходит, пока она идёт.
     */
    private List<JarvisMessage> runAgentTask(String requestId, Command.AgentTask task) {
        if (!brain.isConfigured()) {
            return error(requestId, "агент не привязан к релею — vision-агент недоступен");
        }

        int maxSteps = Math.max(1, Math.min(task.maxSteps(), MAX_AGENT_STEPS));
        String goal = task.goal();
        boolean planEnabled = AgentSettings.flag(AgentSettings.PLAN_ENABLED, true);
        // По плану задача идёт через несколько программ, и трёх минут ей мало:
        // одна отправка файла в мессенджере — уже 6–8 шагов. У каждой подзадачи
        // при этом свои 3 минуты (см. PlanRunner.Limits).
        Instant deadline = Instant.now().plus(planEnabled
                ? Duration.ofMinutes(AgentSettings.number(AgentSettings.PLAN_MAX_MINUTES, 10, 1, 30))
                : AGENT_TASK_TIMEOUT);

        // Сквозная нумерация шагов. Каждая подзадача плана считает шаги с 1, а
        // телефон должен видеть «шаг 7», а не третий подряд «шаг 1». stepOffset —
        // сколько шагов сделали прошлые подзадачи; phase — «2/5», какая подзадача
        // идёт сейчас (null — задача без плана). Массивы из одного элемента —
        // потому что лямбды ниже могут менять только такие «ячейки».
        int[] stepOffset = {0};
        String[] phase = {null};
        // Видео-отчёт задачи: кадры шагов с отметками и мыслями модели (null —
        // выключен в agent.properties: report.enabled=false).
        TaskReportRecorder video = TaskReportRecorder.start(requestId, goal, robot);

        IntConsumer onStep = localStep -> {
            int step = localStep + stepOffset[0];
            String where = phase[0] == null ? "" : ", подзадача " + phase[0];
            if (tray != null) tray.setTaskStatus("выполняю: " + goal + " (шаг " + step + where + ")");
            // onStep вызывается ДО снимка экрана и запроса к модели — то есть
            // сейчас агент «думает». Виджет крутит дуги и пишет номер шага.
            FaustOverlayController.onThinking(phase[0] == null
                    ? "Думаю · шаг " + step + " из " + maxSteps
                    : "Подзадача " + phase[0] + " · шаг " + step);
        };

        StepReporter stepReporter = new StepReporter() {
            /**
             * Слать ли картинку с каждым шагом.
             *
             * <p>Пока на телефоне включён живой экран — не слать: та же картинка
             * уже там, а снимать её отдельно стоит времени на каждом шаге (в
             * браузерном режиме — сотни миллисекунд через Selenium) и удваивает
             * трафик на одни и те же пиксели.
             *
             * <p>Живой экран выключен — всё как раньше, скриншот на каждом шаге.
             */
            @Override
            public boolean needsScreenshot() {
                return !streamer.isRunning();
            }

            @Override
            public void onStep(int localStep, String localDescription, byte[] jpeg, int w, int h,
                               Integer tx, Integer ty) {
                // Номер шага — сквозной по всем подзадачам плана (см. stepOffset).
                // В тексте описания тоже: «шаг 2: click(5)» -> «шаг 7: click(5)»,
                // иначе телефон не узнал бы свой префикс и показал бы его дважды.
                int step = localStep + stepOffset[0];
                String description = stepOffset[0] == 0 ? localDescription
                        : localDescription.replaceFirst("^шаг " + localStep + "(?=\\D|$)", "шаг " + step);
                // Модель ответила, сейчас будет клик/ввод: дуги встают, точка
                // пульсирует, а в строке статуса — что именно агент делает.
                FaustOverlayController.onStep(describeStep(description));
                // В отчёт — до пересчёта координат под стрим: у отчёта свой контракт,
                // тот же, что у StepReporter (без картинки — пиксели экрана).
                if (video != null) video.onStep(step, description, jpeg, tx, ty, phase[0]);

                Integer targetX = tx, targetY = ty;
                if (jpeg == null && tx != null && ty != null) {
                    // Координаты пришли в пикселях экрана (см. StepReporter):
                    // переводим их в систему координат кадра стрима, который
                    // сейчас видит телефон.
                    double scale = streamer.frameScale();
                    if (scale > 0) {
                        targetX = (int) Math.round(tx * scale);
                        targetY = (int) Math.round(ty * scale);
                    } else {
                        targetX = null; // кадров ещё не было - метку рисовать не на чем
                        targetY = null;
                    }
                }

                String b64 = jpeg == null ? null : Base64.getEncoder().encodeToString(jpeg);
                sender.accept(new JarvisMessage.AgentStep(
                        requestId, step, description,
                        b64, jpeg == null ? null : ImageFormat.JPEG,
                        jpeg == null ? null : w, jpeg == null ? null : h,
                        targetX, targetY));
            }
        };

        CancelSignal cancelSignal = cancelRequested::get;
        // Вопросы человеку — свои на каждую задачу: лимит, память ответов и
        // время ожидания считаются заново.
        Clarifications questions = new Clarifications(askerFor(requestId, goal));
        // Через них же приходят поправки с телефона (correctTask).
        activeQuestions = questions;
        // Исполнителю подзадачи плана поправка — тоже повод остановиться: дальше
        // по старому плану идти нельзя, нужен новый хвост (его строит PlanRunner).
        // В задаче без плана такого сигнала нет: там поправка просто попадает в
        // цель, и модель учтёт её следующим шагом, не начиная заново.
        CancelSignal subtaskCancel = () -> cancelRequested.get() || questions.hasPendingCorrection();

        long start = System.currentTimeMillis();
        int llmCallsBefore = llmClient.calls();
        // Анимации «Фауст»: заставка, рамка, курсор, виджет — что включено в
        // agent.properties. Какие именно — решает контроллер, не мы.
        FaustOverlayController.onTaskStart(goal);
        ResultData.AgentReport report = null;
        // Сведения о плане для журнала прогонов — цифры для таблиц диплома.
        java.util.Map<String, Object> journalExtra = new java.util.LinkedHashMap<>();
        journalExtra.put("mode", "single");
        int subtasks = 0;
        /** Сколько задача шла в первый раз, если сейчас её план взят из памяти; 0 — не из памяти. */
        long memoryBaselineMs = 0;
        try {
            AgentScope scope = task.scope();
            // Задача уже есть в памяти траекторий — план не нужен: повтор идёт
            // вообще без обращений к модели, и запрос к планировщику испортил бы
            // именно то, ради чего память заведена.
            // То же для задачи, выученной в программе (память UIA): без плана, в режиме SCREEN.
            if (TrajectoryCache.findUia(goal).isPresent()) scope = AgentScope.SCREEN;

            // Навык, которому человек научил агента показом («навык утро», «доброе
            // утро»): записанные шаги — без плана и без модели. Если дословно
            // повторить не выйдет (окно изменилось), показ станет инструкцией для
            // модели — см. ScreenAgent.runSkill.
            // С переменными («отправь Маше расписание» по показу «отправь Пете отчёт»)
            // — те же шаги, но с новыми данными (см. SkillTemplate).
            java.util.Optional<SkillStore.Found> skill = SkillStore.find(goal);
            if (skill.isPresent()) {
                SkillStore.Skill s = skill.get().skill();
                SkillTemplate.Binding binding = skill.get().binding();
                List<String> shown = skill.get().shownDescriptions();
                journalExtra.put("mode", "skill");
                journalExtra.put("skill", s.name());
                if (binding != null) journalExtra.put("skillValues", binding.values().values());
                FaustOverlayController.onMemory(true);
                IntConsumer progress = i -> publishSkillProgress(requestId, shown, i);
                progress.accept(0);
                report = screenAgent.runSkill(s.name(), s.steps(), shown, s.recordedMs(), maxSteps,
                        Instant.now().plus(AGENT_TASK_TIMEOUT), onStep, stepReporter, cancelSignal, questions, progress,
                        binding);
                if (report.completed()) progress.accept(shown.size());
            }

            // «Запусти скрипт сервер диплома» — просьба прямо называет доверенный
            // скрипт: план из одного шага, без планировщика, но с подтверждением.
            List<PlanStep> preset = null;
            String askedScript = report == null ? ScriptCatalog.requestedName(goal) : null;
            if (askedScript != null) {
                java.util.Optional<String> script = ScriptCatalog.resolve(askedScript);
                if (script.isPresent()) {
                    preset = List.of(PlanStep.runScript(script.get()));
                } else {
                    List<String> known = ScriptCatalog.names();
                    report = new ResultData.AgentReport(false, 0, List.of(
                            "стоп: нет доверенного скрипта «" + askedScript + "»; "
                            + (known.isEmpty() ? "папка скриптов пуста" : "есть: " + String.join(", ", known))
                            + " (" + ScriptCatalog.folder() + ")"));
                }
            }
            // «git status демо», «запушь демо» — операция git названа прямо: план
            // без планировщика (без интернета и за доли секунды). Подтверждение
            // push всё равно спрашивается — внутри шага, с перечнем коммитов.
            if (report == null && preset == null) {
                List<PlanStep> dev = DevTools.preset(goal);
                if (dev != null) preset = dev;
            }
            // «Фауст, продолжи» — оборванный план с места сбоя (см. ResumeStore).
            com.jarvis.agent.plan.ResumeStore.Saved resumeFrom = null;
            if (report == null && preset == null && com.jarvis.agent.plan.ResumeStore.isResumeRequest(goal)) {
                java.util.Optional<com.jarvis.agent.plan.ResumeStore.Saved> saved = com.jarvis.agent.plan.ResumeStore.load();
                if (saved.isEmpty()) {
                    report = new ResultData.AgentReport(false, 0, List.of(
                            "стоп: продолжать нечего — оборванных задач за последние 3 часа нет"));
                } else {
                    resumeFrom = saved.get();
                    preset = resumeFrom.remaining();
                    journalExtra.put("resumed", true);
                }
            }
            if (report == null && (preset != null || planEnabled
                    && TrajectoryCache.find(goal).isEmpty() && TrajectoryCache.findUia(goal).isEmpty())) {
                FaustOverlayController.onThinking(preset != null ? "Выполняю по готовому плану" : "Составляю план");
                if (tray != null) tray.setTaskStatus((preset != null ? "по готовому плану: " : "составляю план: ") + goal);

                PlanRunner.SubtaskExecutor runSubtask = (step, subDeadline) -> {
                    ResultData.AgentReport r = step.kind() == PlanStep.Kind.BROWSER
                            ? browserAgent.run(step.goal(), maxSteps, subDeadline, onStep, stepReporter, subtaskCancel, questions)
                            : screenAgent.run(step.goal(), maxSteps, subDeadline, onStep, stepReporter, subtaskCancel, questions);
                    stepOffset[0] += r.stepsTaken();
                    return r;
                };
                PlanRunner.PlanListener toPhone = (items, revision) -> {
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).status() == JarvisMessage.PlanItemStatus.RUNNING) {
                            phase[0] = (i + 1) + "/" + items.size();
                        }
                    }
                    sender.accept(new JarvisMessage.AgentPlan(requestId, items, revision));
                    // И на экран ПК — панель «План и мысли».
                    FaustOverlayController.onPlan(items, revision);
                };
                PlanRunner.Limits limits = new PlanRunner.Limits(
                        AgentSettings.number(AgentSettings.PLAN_MAX_SUBTASKS, 8, 1, 12),
                        AgentSettings.number(AgentSettings.PLAN_MAX_REPLANS, 2, 0, 5),
                        3,
                        AGENT_TASK_TIMEOUT);

                // Проверку результата подзадач можно выключить (plan.verify=false):
                // это лишний запрос к модели на каждую подзадачу в программе.
                com.jarvis.agent.plan.StepVerifier base =
                        AgentSettings.flag(AgentSettings.PLAN_VERIFY, true) ? verifier : com.jarvis.agent.plan.StepVerifier.NONE;
                // Та же проверка, но видимая: на панели «проверяю…» и затем итог.
                com.jarvis.agent.plan.StepVerifier stepVerifier = (step, summary) -> {
                    if (step.check() == null && step.expectFile() == null) return base.verify(step, summary);
                    FaustOverlayController.onVerify("run", step.title());
                    com.jarvis.agent.plan.StepVerifier.Check c = base.verify(step, summary);
                    if (c != null) {
                        FaustOverlayController.onVerify(c.ok() == null ? "unknown" : c.ok() ? "ok" : "fail", c.reason());
                    }
                    return c;
                };
                PlanRunner.Outcome outcome = new PlanRunner(planner, runSubtask, this::openAndFocus, toPhone,
                        questions, cancelSignal, limits, stepVerifier)
                        .withMemory(planMemory, () -> FaustOverlayController.onMemory(true))
                        .withScripts(ScriptCatalog::run)
                        // Встроенный редактор кода ходит в модель тем же путём, что весь агент, — через релей.
                        .withDev(new DevTools(prompt -> llmClient.generate(prompt, null)))
                        .withWaitDefault(AgentSettings.number("plan.waitMinutes", 10, 1, 60))
                        .withPreset(preset)
                        .withResume(resumeFrom)
                        .withResumeSaving(true)
                        .run(resumeFrom != null ? resumeFrom.goal() : goal, task.scope(), deadline);

                if (outcome instanceof PlanRunner.Outcome.Finished f) {
                    report = f.report();
                    subtasks = f.subtasks();
                    journalExtra.put("mode", "plan");
                    journalExtra.put("subtasks", f.subtasks());
                    journalExtra.put("replans", f.replans());
                    journalExtra.put("plannerCalls", f.plannerCalls());
                    journalExtra.put("checksPassed", f.checksPassed());
                    journalExtra.put("checksFailed", f.checksFailed());
                    journalExtra.put("planFromMemory", f.planFromMemory());
                    if (f.planFromMemory()) memoryBaselineMs = f.baselineMs();
                } else if (outcome instanceof PlanRunner.Outcome.Direct d) {
                    // План не понадобился (одна программа) или планировщик
                    // недоступен — дальше ровно прежний путь, в выбранном режиме.
                    scope = d.scope();
                    journalExtra.put("mode", "direct");
                    journalExtra.put("plannerCalls", d.plannerCalls());
                }
            }
            if (report == null) {
                // Прежний путь: один цикл. Свои 3 минуты отсчитываются от этого
                // момента, а не от начала задачи, — планирование их не съедает.
                Instant single = Instant.now().plus(AGENT_TASK_TIMEOUT).minusMillis(questions.waitedMs());
                report = switch (scope) {
                    case BROWSER -> browserAgent.run(goal, maxSteps, single, onStep, stepReporter, cancelSignal, questions);
                    case SCREEN -> screenAgent.run(goal, maxSteps, single, onStep, stepReporter, cancelSignal, questions);
                };
            }
        } finally {
            // Задача кончилась — поправки к ней больше не принимаем.
            activeQuestions = null;
            // Убрать анимации обязательно, в том числе если задача упала или её
            // отменили: висящая на экране «вечная» анимация пугала бы сильнее,
            // чем её отсутствие. Поэтому — в finally.
            FaustOverlayController.onTaskEnd();
            // Живой экран, включённый с телефона поверх задачи, задачу переживает.
            // Тогда рамку «телефон видит этот экран» должен показать уже он сам —
            // вместе с анимациями задачи она только что исчезла.
            if (streamer.isRunning()) streamer.takeOverOverlays();
        }
        long durationMs = System.currentTimeMillis() - start;
        int llmCalls = llmClient.calls() - llmCallsBefore;
        journalExtra.put("llmCalls", llmCalls);
        // Поправки по ходу — своей цифрой: «сколько раз человек вмешался сам».
        if (questions.correctionsCount() > 0) journalExtra.put("corrections", questions.correctionsCount());
        RunJournal.write(goal, task.scope(), maxSteps, report, durationMs,
                questions.asked(), questions.answeredCount(), questions.waitedMs(), journalExtra);

        boolean cancelled = cancelRequested.get();
        CommandStatus status = report.completed() ? CommandStatus.OK : CommandStatus.ERROR;
        String msg = (cancelled ? "задача отменена"
                : report.completed() ? "цель достигнута" : "цель не достигнута")
                + " за " + report.stepsTaken() + " шаг(ов), " + (durationMs / 1000) + " с"
                + (subtasks > 0 ? ", подзадач: " + subtasks : "")
                // Вопросы — отдельной цифрой: в таблицах диплома «сколько раз
                // агенту понадобился человек» — самостоятельная метрика.
                + (questions.asked() > 0 ? ", вопросов: " + questions.asked() : "")
                // Цена задачи: сколько раз агент спросил ИИ. «Без обращений к ИИ» —
                // это git, навыки и выученное: работает и без интернета.
                + (llmCalls == 0 ? ", без обращений к ИИ" : ", обращений к ИИ: " + llmCalls)
                + resultNote(report)
                // «Учится на глазах»: во второй раз — сравнение с первым прямо в итоге
                // (и голосом на телефоне: итог озвучивается).
                + memoryNote(report, memoryBaselineMs)
                // Что скачалось — сразу в итог: человеку на телефоне важно знать,
                // где лежит файл, а не искать это в трейсе.
                + report.trace().stream()
                        .map(String::strip)
                        .filter(line -> line.startsWith(DownloadWatcher.FACT_MARK))
                        .map(line -> "\n" + line.substring(DownloadWatcher.FACT_MARK.length()))
                        .collect(java.util.stream.Collectors.joining());

        // Итоговый кадр и GIF — до ответа телефону: кнопка «Видео-отчёт» под
        // итогом должна сработать сразу, а не через секунду, когда GIF допишется.
        if (video != null) video.finish(report.completed(), msg.lines().findFirst().orElse(msg));

        // Долгая задача заканчивается двумя сообщениями с одним requestId: сначала
        // картинка (что видно на экране сейчас), затем итог — телефон показывает оба.
        List<JarvisMessage> result = new ArrayList<>();
        try {
            result.add(captureFrame(requestId));
        } catch (Exception e) {
            // Не удалось снять финальный кадр — не роняем результат задачи из-за этого.
        }
        result.add(new JarvisMessage.CommandResult(requestId, status, msg, report));
        return result;
    }

    // --- concrete actions ------------------------------------------------

    private String openUrl(String url) throws Exception {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(url));
        } else {
            // Fallback per OS if Desktop isn't available (e.g. some Linux setups).
            String[] cmd = OS.contains("win") ? new String[]{"cmd", "/c", "start", url}
                    : OS.contains("mac") ? new String[]{"open", url}
                    : new String[]{"xdg-open", url};
            new ProcessBuilder(cmd).start();
        }
        return "открыл " + url;
    }

    /**
     * Запускает программу/папку из {@link AppCatalog} — сознательно НЕ произвольный
     * путь с диска, а только то, что есть в словаре (граница безопасности).
     */
    private List<JarvisMessage> openApp(String requestId, String appName) {
        try {
            return ok(requestId, "запущено: " + launchApp(appName));
        } catch (IllegalArgumentException e) {
            return error(requestId, e.getMessage());   // программу не нашли — текст уже понятный
        } catch (Exception e) {
            return error(requestId, "не удалось запустить «" + appName + "»: " + e.getMessage());
        }
    }

    /**
     * Найти программу по названию и открыть. Общий путь для команды open_app с
     * телефона и для действия open_app пиксельного агента.
     *
     * @return настоящее название того, что открылось (сказали «телеграм» — вернём «Telegram»)
     * @throws IllegalArgumentException такой программы нет ни в словаре, ни в «Пуске»
     */
    String launchApp(String appName) throws Exception {
        Optional<AppCatalog.Target> target = AppCatalog.resolve(appName);
        if (target.isEmpty()) {
            throw new IllegalArgumentException("не знаю такую программу «" + appName + "», могу: "
                    + AppCatalog.describeKnown());
        }
        switch (target.get()) {
            case AppCatalog.Target.Program p -> new ProcessBuilder(p.executable()).start();
            case AppCatalog.Target.Folder f -> new ProcessBuilder("explorer.exe", f.path().toString()).start();
            case AppCatalog.Target.DefaultBrowser ignored -> openUrl("about:blank");
            case AppCatalog.Target.StartMenuApp a -> launchStartMenuApp(a);
        }
        // Для записи из «Пуска» — её настоящее название: пользователь сказал
        // «телеграм», а открылся «Telegram» — пусть видит, что именно.
        return target.get() instanceof AppCatalog.Target.StartMenuApp a ? a.displayName() : appName;
    }

    /**
     * Открыть программу И вывести её окно вперёд — для ИИ-агента.
     *
     * <p>Одного запуска мало: если программа уже работала, Windows оставляет
     * впереди прежнее окно (на прогоне 30.09 после «Открыть Telegram» впереди
     * остался Chrome). Исполнитель следующего шага смотрит на активное окно —
     * значит, оно обязано быть тем, что открыли. Если вывести не удалось,
     * об этом прямо написано в итоге: модель увидит, где она на самом деле.
     */
    String openAndFocus(String appName) throws Exception {
        // Решение открыть программу здесь принимает МОДЕЛЬ — и терминал ей не
        // положен (см. ShellGuard: на прогоне 30.09 агент открыл PowerShell и
        // выполнил команду). Проверяем и то, что попросили, и то, во что это
        // название разрешится в «Пуске»: «синее окно» может оказаться PowerShell.
        Optional<AppCatalog.Target> target = AppCatalog.resolve(appName);
        String resolvedName = target.map(t -> t instanceof AppCatalog.Target.StartMenuApp a ? a.displayName()
                : t instanceof AppCatalog.Target.Program p ? p.executable() : "").orElse("");
        if (com.jarvis.agent.vision.ShellGuard.isForbiddenApp(appName)
                || com.jarvis.agent.vision.ShellGuard.isForbiddenApp(resolvedName)) {
            throw new IllegalArgumentException("«" + appName + "»: " + com.jarvis.agent.vision.ShellGuard.HINT);
        }
        String opened = launchApp(appName);
        // У подпапки («Документы\ФаустТест») в заголовке Проводника только
        // последнее имя — «ФаустТест — проводник»; ищем окно по нему.
        String focusName = opened.contains("\\") || opened.contains("/")
                ? opened.substring(Math.max(opened.lastIndexOf('\\'), opened.lastIndexOf('/')) + 1)
                : opened;
        String title = WindowFocus.bringToFront(focusName);
        return title != null
                ? opened + " — окно «" + title + "» на переднем плане"
                : opened + " — но окно не вышло вперёд, сейчас активно «" + WindowFocus.foregroundTitle() + "»";
    }

    /**
     * Открывает запись из меню «Пуск». Обычный путь — shell:AppsFolder, тот же
     * механизм, которым «Пуск» открывает найденное в собственном поиске: он
     * одинаково работает и для .exe, и для магазинных приложений.
     *
     * Исключение — записи, у которых идентификатором служит прямой путь к файлу
     * (так Get-StartApps отдаёт, например, Spotify и Steam): внутри
     * shell:AppsFolder такой путь не разбирается, поэтому запускаем напрямую.
     * Границу безопасности это не двигает — путь пришёл из «Пуска», а не от LLM.
     */
    private void launchStartMenuApp(AppCatalog.Target.StartMenuApp app) throws Exception {
        if (isExistingFile(app.appId())) {
            new ProcessBuilder(app.appId()).start();
        } else {
            new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + app.appId()).start();
        }
    }

    /** Идентификатор оказался путём к существующему файлу? Кидаться исключением тут нельзя: у записей вида "steam://..." Path.of не разберётся. */
    private boolean isExistingFile(String appId) {
        try {
            return Files.isRegularFile(Path.of(appId));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Скрипт точной установки громкости через Core Audio
     * (IAudioEndpointVolume::SetMasterVolumeLevelScalar). Только ASCII: файл
     * пишется в UTF-8 без BOM, а PowerShell 5.1 читает .ps1 в системной кодировке,
     * и кириллица в комментариях превратилась бы в мусор.
     */
    private static final String VOLUME_SCRIPT = """
param([int]$Level, [string]$Mode = 'set')
$ErrorActionPreference = 'Stop'
Add-Type -Language CSharp @'
using System;
using System.Runtime.InteropServices;

[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioEndpointVolume {
    int RegisterControlChangeNotify(IntPtr pNotify);
    int UnregisterControlChangeNotify(IntPtr pNotify);
    int GetChannelCount(out uint pnChannelCount);
    int SetMasterVolumeLevel(float fLevelDB, IntPtr pguidEventContext);
    int SetMasterVolumeLevelScalar(float fLevel, IntPtr pguidEventContext);
    int GetMasterVolumeLevel(out float pfLevelDB);
    int GetMasterVolumeLevelScalar(out float pfLevel);
    int SetChannelVolumeLevel(uint nChannel, float fLevelDB, IntPtr pguidEventContext);
    int SetChannelVolumeLevelScalar(uint nChannel, float fLevel, IntPtr pguidEventContext);
    int GetChannelVolumeLevel(uint nChannel, out float pfLevelDB);
    int GetChannelVolumeLevelScalar(uint nChannel, out float pfLevel);
    int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, IntPtr pguidEventContext);
    int GetMute([MarshalAs(UnmanagedType.Bool)] out bool pbMute);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDevice {
    int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams,
                 [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
}

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IntPtr ppDevices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint);
}

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
public class MMDeviceEnumerator { }

public static class JarvisVolume {
    public static void Set(int percent) {
        IMMDeviceEnumerator en = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
        IMMDevice dev;
        Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0, 0, out dev));
        Guid iid = typeof(IAudioEndpointVolume).GUID;
        object o;
        Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o));
        IAudioEndpointVolume vol = (IAudioEndpointVolume)o;
        Marshal.ThrowExceptionForHR(vol.SetMute(false, IntPtr.Zero));
        Marshal.ThrowExceptionForHR(vol.SetMasterVolumeLevelScalar(percent / 100f, IntPtr.Zero));
    }
    // «Громче на 20»: прочитать текущий уровень и сдвинуть его. Возвращает новый уровень.
    public static int Change(int delta) {
        IMMDeviceEnumerator en = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
        IMMDevice dev;
        Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0, 0, out dev));
        Guid iid = typeof(IAudioEndpointVolume).GUID;
        object o;
        Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o));
        IAudioEndpointVolume vol = (IAudioEndpointVolume)o;
        float cur;
        Marshal.ThrowExceptionForHR(vol.GetMasterVolumeLevelScalar(out cur));
        int now = (int)Math.Round(cur * 100);
        int next = Math.Max(0, Math.Min(100, now + delta));
        Set(next);
        return next;
    }
}
'@
if ($Mode -eq 'delta') { [JarvisVolume]::Change($Level) } else { [JarvisVolume]::Set($Level) }
""";

    /**
     * Точная громкость одним вызовом Core Audio.
     *
     * Прежний способ — 50 нажатий "тише" до нуля, затем level/2 нажатий "громче"
     * через SendKeys — был недетерминированным: Windows склеивает быстро идущие
     * подряд нажатия клавиш громкости, и если терялась часть "громче", звук так
     * и оставался выключенным (шаг "сделай тише" превращался в полное отключение).
     * Здесь промежуточного нуля нет вообще: значение выставляется сразу, плюс
     * снимается mute — иначе на заглушённом ПК громкость менялась бы беззвучно.
     *
     * Скрипт кладём во временный .ps1: C# с COM-интерфейсами содержит кавычки и
     * переносы строк, которые не пережили бы передачу через -Command.
     */
    private String setVolume(int level) throws Exception {
        if (!OS.contains("win")) {
            throw new UnsupportedOperationException("set_volume реализован только для Windows");
        }
        int target = Math.max(0, Math.min(100, level));
        runVolumeScript(target, "set");
        return "громкость выставлена на " + target + "%";
    }

    /** «Громче на 20»: сдвиг от ТЕКУЩЕЙ громкости (её знает только Windows, поэтому считает скрипт). */
    private String changeVolume(int delta) throws Exception {
        if (!OS.contains("win")) {
            throw new UnsupportedOperationException("громкость реализована только для Windows");
        }
        int d = Math.max(-100, Math.min(100, delta));
        String out = runVolumeScript(d, "delta").strip();
        String last = out.isEmpty() ? "" : out.lines().reduce((a, b) -> b).orElse("").strip();
        return "громкость " + (d >= 0 ? "+" : "−") + Math.abs(d) + "%"
               + (last.matches("\\d{1,3}") ? ", теперь " + last + "%" : "");
    }

    /** Запустить скрипт громкости; вернуть его вывод. */
    private static String runVolumeScript(int value, String mode) throws Exception {
        Path script = Files.createTempFile("jarvis-volume", ".ps1");
        try {
            Files.writeString(script, VOLUME_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    // Параметры ПО ИМЕНИ: позиционное «-5» PowerShell принимает за имя
                    // параметра, и скрипт молча выставлял громкость 0 (поймано проверкой 19.10).
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                    "-Level", String.valueOf(value), "-Mode", mode)
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("PowerShell не успел изменить громкость за 20с");
            }
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new IllegalStateException("не удалось изменить громкость: " + output.trim());
            }
            return output;
        } finally {
            Files.deleteIfExists(script);
        }
    }

    /**
     * Содержимое буфера обмена ПК -> телефону. Не текст (картинка, файл и т.п.)
     * или похоже на пароль/карту (SensitiveInputGuard, как у vision-агента) —
     * отказываем, а не пересылаем вслепую.
     */
    private List<JarvisMessage> getClipboard(String requestId) {
        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            return error(requestId, "буфер обмена ПК пуст или это не текст");
        }
        if (SensitiveInputGuard.mentionsSensitive(text)) {
            return error(requestId, "похоже на пароль/платёжные данные — не пересылаю буфер обмена");
        }
        return List.of(new JarvisMessage.CommandResult(
                requestId, CommandStatus.OK, "буфер обмена ПК отправлен", new ResultData.ClipboardText(text)));
    }

    /** Текст с телефона -> в буфер обмена ПК (как typeText, но без вставки в поле). */
    private String setClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        return "буфер обмена ПК обновлён";
    }

    // --- передача файлов --------------------------------------------------

    private List<JarvisMessage> listFiles(String requestId, String folderName) {
        Optional<Path> folder = FileCatalog.resolveFolder(folderName);
        if (folder.isEmpty()) {
            return error(requestId, "не знаю такую папку «" + folderName + "»");
        }
        try {
            List<ResultData.FileEntry> entries = FileCatalog.list(folder.get()).stream()
                    .map(e -> new ResultData.FileEntry(e.name(), e.sizeBytes()))
                    .toList();
            return List.of(new JarvisMessage.CommandResult(
                    requestId, CommandStatus.OK, entries.size() + " файл(ов)", new ResultData.FileList(entries)));
        } catch (IOException e) {
            return error(requestId, "не удалось прочитать папку: " + e.getMessage());
        }
    }

    private List<JarvisMessage> sendFileToPhone(String requestId, String folderName, String fileName) {
        Optional<Path> folder = FileCatalog.resolveFolder(folderName);
        if (folder.isEmpty()) {
            return error(requestId, "не знаю такую папку «" + folderName + "»");
        }
        Optional<Path> file = FileCatalog.resolveFile(folder.get(), fileName);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            return error(requestId, "файл «" + fileName + "» не найден");
        }
        try {
            long size = Files.size(file.get());
            if (size > FileCatalog.MAX_FILE_BYTES) {
                return error(requestId, "файл слишком большой (" + (size / 1024 / 1024) + " МБ, лимит 5 МБ)");
            }
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file.get()));
            return List.of(new JarvisMessage.CommandResult(
                    requestId, CommandStatus.OK, "файл «" + fileName + "» отправлен",
                    new ResultData.FileTransfer(fileName, b64, size)));
        } catch (IOException e) {
            return error(requestId, "не удалось прочитать файл: " + e.getMessage());
        }
    }

    /** Файл с телефона -> в разрешённую папку ПК. Исключения ловит общий catch в execute(). */
    private String receiveFileFromPhone(String folderName, String fileName, String base64) throws IOException {
        Optional<Path> folder = FileCatalog.resolveFolder(folderName);
        if (folder.isEmpty()) {
            throw new IllegalArgumentException("не знаю такую папку «" + folderName + "»");
        }
        Optional<Path> file = FileCatalog.resolveFile(folder.get(), fileName);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("недопустимое имя файла «" + fileName + "»");
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length > FileCatalog.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("файл слишком большой (лимит 5 МБ)");
        }
        Files.write(file.get(), bytes);
        return "файл «" + fileName + "» сохранён в " + folderName;
    }

    private List<JarvisMessage> screenshot(String requestId) throws Exception {
        List<JarvisMessage> msgs = new ArrayList<>();
        msgs.add(captureFrame(requestId));
        msgs.add(new JarvisMessage.CommandResult(requestId, CommandStatus.OK, "скриншот отправлен", null));
        return msgs;
    }

    /** Снимает экран и упаковывает в ScreenFrame — общая часть screenshot() и agentTask(). */
    private JarvisMessage.ScreenFrame captureFrame(String requestId) throws Exception {
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        // Снимок для телефона — без наших оверлеев: пользователь просил экран
        // своего ПК, а не служебную анимацию поверх него.
        BufferedImage full = AgentOverlays.capture(() -> robot.createScreenCapture(screen));
        BufferedImage img = ImageUtil.scaleDown(full, 1280); // ограничиваем ширину -> меньше вес
        byte[] jpeg = ImageUtil.toJpeg(img, 0.5f);           // JPEG качество 50%
        String b64 = Base64.getEncoder().encodeToString(jpeg);
        return new JarvisMessage.ScreenFrame(
                requestId, ImageFormat.JPEG, b64, img.getWidth(), img.getHeight(),
                screen.width, screen.height, null);
    }

    private List<JarvisMessage> listProcesses(String requestId) {
        List<ResultData.ProcessInfo> procs = ProcessHandle.allProcesses()
                .map(p -> new ResultData.ProcessInfo(
                        p.pid(),
                        p.info().command().orElse("?")))
                .limit(100)   // cap so the payload stays small
                .toList();
        return List.of(new JarvisMessage.CommandResult(
                requestId, CommandStatus.OK, null, new ResultData.ProcessList(procs)));
    }

    private String killProcess(String name) {
        long killed = ProcessHandle.allProcesses()
                .filter(p -> p.info().command().orElse("").toLowerCase(Locale.ROOT)
                        .contains(name.toLowerCase(Locale.ROOT)))
                .peek(ProcessHandle::destroy)
                .count();
        return "завершено процессов: " + killed;
    }

    private String lockScreen() throws Exception {
        String[] cmd = OS.contains("win")
                ? new String[]{"rundll32.exe", "user32.dll,LockWorkStation"}
                : OS.contains("mac")
                ? new String[]{"pmset", "displaysleepnow"}
                : new String[]{"loginctl", "lock-session"};
        new ProcessBuilder(cmd).start();
        return "экран заблокирован";
    }

    private String click(int x, int y) throws Exception {
        // Вне ИИ-задачи (тап на экране «Экран ПК» телефона) moveMouse двигает
        // мышь мгновенно, а onClick ничего не рисует — анимации только для ИИ.
        FaustOverlayController.moveMouse(robot, x, y);
        FaustOverlayController.onClick(x, y, false);
        // Если под точкой окно с кнопкой (виджет, плашка) — на миг уберётся,
        // иначе тап попал бы в него, а не в программу под ним.
        AgentOverlays.mouseAction(x, y, () -> {
            robot.mouseMove(x, y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        });
        return "клик по (" + x + ", " + y + ")";
    }

    /** Types text by putting it on the clipboard and pasting — handles unicode. */
    private String typeText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        int paste = OS.contains("mac") ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(paste);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(paste);
        return "напечатано: " + text;
    }

    private String scroll(int dy) throws Exception {
        // Колесо прокручивает окно ПОД курсором — если курсор над нашим окном
        // с кнопкой, прокрутка ушла бы в никуда. Поэтому оно на миг уберётся.
        AgentOverlays.mouseActionHere(() -> robot.mouseWheel(dy));
        return "прокрутка " + dy;
    }

    /**
     * Тап, прокрутка или ввод с экрана «Экран ПК» телефона. Если человек за ПК
     * нажал «Остановить» на плашке, управление с телефона прекращено: команду
     * не выполняем и объясняем телефону почему.
     */
    private List<JarvisMessage> remoteControl(String requestId, java.util.concurrent.Callable<String> action)
            throws Exception {
        if (streamer.isPausedByUser()) {
            return error(requestId, "управление с телефона остановлено на ПК кнопкой «Остановить» — "
                    + "откройте «Экран ПК» заново");
        }
        return ok(requestId, action.call());
    }

    /**
     * Команда с телефона -> короткая фраза для карточки на экране ПК, или null,
     * если карточка не нужна: у ИИ-задачи и «Экрана ПК» свои анимации, а
     * служебные команды (отмена, стрим) показывать незачем.
     */
    static String describeCommand(Command command) {
        return switch (command) {
            case Command.OpenApp c         -> "Открываю " + capitalize(c.appName());
            case Command.OpenUrl c         -> "Открываю " + hostOf(c.url());
            case Command.SetVolume c       -> "Громкость " + c.level() + "%";
            case Command.LockScreen c      -> "Блокирую экран";
            case Command.KillProcess c     -> "Закрываю " + c.processName();
            case Command.Screenshot c      -> "Снимаю экран";
            case Command.ListProcesses c   -> "Смотрю, что запущено";
            case Command.GetClipboard c    -> "Отправляю буфер обмена на телефон";
            case Command.SetClipboard c    -> "Принимаю буфер обмена с телефона";
            case Command.ListFiles c       -> "Открываю папку «" + c.folder() + "»";
            case Command.SendFileToPhone c -> "Отправляю «" + c.fileName() + "» на телефон";
            case Command.SendFileToPc c    -> "Принимаю «" + c.fileName() + "»";
            case Command.QuickAction c     -> describeQuick(c);
            // У списка карточку показывает каждая команда внутри — своя на каждый шаг.
            default                        -> null;
        };
    }

    private static String describeQuick(Command.QuickAction c) {
        return switch (c.action() == null ? "" : c.action()) {
            case "play_pause" -> "Пауза / продолжить";
            case "next_track" -> "Следующий трек";
            case "prev_track" -> "Предыдущий трек";
            case "minimize_all" -> "Сворачиваю окна";
            case "volume_delta" -> "Громкость " + (c.amount() >= 0 ? "+" : "−") + Math.abs(c.amount()) + "%";
            default -> null;
        };
    }

    /** «телеграм» -> «Телеграм»: в карточке с большой буквы смотрится аккуратнее. */
    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.trim();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** https://www.youtube.com/watch?v=... -> youtube.com */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url.contains("://") ? url : "https://" + url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "сайт";
        }
    }

    /**
     * Описание шага от агента -> короткая фраза для виджета на экране ПК.
     *
     * Агент описывает шаг технически: «шаг 3: click(12)», «шаг 4: type("котики")»,
     * «шаг 1 (из памяти): navigate(https://youtube.com)». Для человека за ПК
     * «click(12)» ничего не значит, а в строку виджета влезает ~20 символов.
     * Сам текст описания НЕ меняем — он же уходит на телефон, а телефон мы
     * здесь не трогаем; переводим только то, что показываем на ПК.
     */
    static String describeStep(String description) {
        if (description == null) return "";
        // Вторая строка — обоснование модели для телефона; фразу для виджета
        // строим только по первой, иначе «type(...)» разбирался бы неверно.
        int nl = description.indexOf('\n');
        if (nl >= 0) description = description.substring(0, nl);
        // Всё до первого «: » — это «шаг N» или «шаг N (из памяти)»; дальше — действие.
        int colon = description.indexOf(": ");
        String action = colon >= 0 ? description.substring(colon + 2) : description;

        if (action.startsWith("click")) return "Нажимаю";
        if (action.startsWith("ask(")) return "Спрашиваю на телефоне";
        if (action.startsWith("find_file(")) return "Ищу файл";
        if (action.startsWith("read(")) return "Читаю страницу";
        if (action.startsWith("extract_archive(")) return "Распаковываю архив";
        if (action.startsWith("paste_file(")) return "Прикрепляю файл";
        if (action.startsWith("scroll")) return "Прокручиваю";
        if (action.startsWith("done")) return "Готово";
        if (action.startsWith("type(\"")) {
            // type("текст") -> Печатаю «текст». Снимаем обёртку type(" слева и ") справа.
            String text = action.substring(6, Math.max(6, action.length() - 2));
            return "Печатаю «" + text + "»";
        }
        if (action.startsWith("navigate(")) {
            String url = action.substring(9, Math.max(9, action.length() - 1));
            try {
                String host = URI.create(url).getHost();
                return "Открываю " + (host == null ? url : host.replaceFirst("^www\\.", ""));
            } catch (Exception e) {
                return "Открываю страницу";
            }
        }
        return action;
    }

    // --- small helpers to build result messages --------------------------

    private List<JarvisMessage> ok(String requestId, String message) {
        return List.of(new JarvisMessage.CommandResult(requestId, CommandStatus.OK, message, null));
    }

    private List<JarvisMessage> error(String requestId, String message) {
        return List.of(new JarvisMessage.CommandResult(requestId, CommandStatus.ERROR, message, null));
    }
}
