package com.jarvis.agent.vision;

import com.jarvis.agent.AgentOverlays;
import com.jarvis.agent.FaustOverlayController;
import com.jarvis.agent.protocol.ResultData;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Цикл computer-use агента ВНЕ браузера (scope = SCREEN): управление ЛЮБОЙ
 * программой на экране.
 *
 * <h2>Два режима зрения, выбираются сами</h2>
 * На каждом шаге агент сначала спрашивает у Windows, из чего состоит активное
 * окно ({@link UiaSnapshot}):
 *
 * <ul>
 *   <li><b>режим UI Automation</b> — если Windows отдала осмысленный список
 *       элементов. Модель получает короткий пронумерованный перечень
 *       ({@code [5] <button> "Поиск сообщений"}) и отвечает номером, а клик
 *       идёт точно в центр элемента. Это то же самое, что DOM-режим
 *       {@link BrowserAgent}, только для обычных программ;</li>
 *   <li><b>пиксельный режим</b> — запасной путь, если списка нет или он пустой
 *       (программа рисует интерфейс сама: игры, нестандартные движки). Модель
 *       получает скриншот и целится по координатам «на глаз» — медленнее и
 *       заметно менее точно, но работает везде.</li>
 * </ul>
 *
 * Выбор делается на каждом шаге заново, а не один раз на задачу: агент может
 * начать в проводнике (список есть), открыть программу без разметки (списка
 * нет) и продолжить по пикселям.
 *
 * Ошибка на шаге (модель промахнулась, окно закрылось, фокус не тот) не
 * роняет цикл — она попадает в трейс, и на следующей итерации агент просто
 * смотрит на экран заново и пробует снова.
 */
public class ScreenAgent {

    private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    /** Тот же лимит ширины, что и у обычного screenshot-а в CommandExecutor — экономим на трафике к модели. */
    private static final int MAX_IMAGE_WIDTH = 1280;

    /** После open_app ждём дольше обычного: программе нужно время, чтобы открыться. */
    private static final long OPEN_APP_EXTRA_MS = 1500;
    /** Клик ближе этого к недавнему клику считается повтором того же действия. */
    private static final int REPEAT_RADIUS_PX = 25;
    /** После вставки файла мессенджер читает его и рисует предпросмотр — это дольше вставки текста. */
    private static final long PASTE_FILE_EXTRA_MS = 1200;

    /**
     * Как открыть программу по названию. Даёт CommandExecutor — тот же
     * механизм, что у команды open_app: зашитые программы + всё из «Пуска».
     */
    @FunctionalInterface
    public interface AppOpener {
        /** @return настоящее название открытой программы; бросает исключение, если не нашлась */
        String open(String name) throws Exception;
    }

    private final VisionAgent brain;
    private final Robot robot;
    private final UiaSnapshot uia = new UiaSnapshot();
    private AppOpener appOpener;

    public ScreenAgent(VisionAgent brain) throws AWTException {
        this.brain = brain;
        this.robot = new Robot();
    }

    /** Подключить открытие программ. Без него действие open_app вернёт понятную ошибку. */
    public void setAppOpener(AppOpener opener) {
        this.appOpener = opener;
    }

    /** Тот же цикл без внешнего лимита времени/колбэка шага — для ручных demo-запусков. */
    public ResultData.AgentReport run(String goal, int maxSteps) {
        return run(goal, maxSteps, Instant.now().plus(Duration.ofMinutes(3)), step -> { });
    }

    /** Без живого показа шагов — старое поведение (трей и т.п.), теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }

    /** Без отмены — прежняя пятипараметровая перегрузка, чтобы старые вызовы не ломались. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                      IntConsumer onStep, StepReporter stepReporter) {
        return run(goal, maxSteps, deadline, onStep, stepReporter, CancelSignal.NONE);
    }

    /** Без вопросов человеку — прежняя шестипараметровая перегрузка (demo-запуски). */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                      IntConsumer onStep, StepReporter stepReporter,
                                      CancelSignal cancelSignal) {
        return run(goal, maxSteps, deadline, onStep, stepReporter, cancelSignal, Clarifications.none());
    }

    /**
     * Крутит цикл до maxSteps шагов (но не дольше deadline).
     * @param onStep       колбэк перед каждым шагом (номер шага, начиная с 1) — используется,
     *                     например, чтобы показать в трее "выполняю: ... (шаг N)"
     * @param stepReporter колбэк ПОСЛЕ каждого шага — с картинкой и описанием действия,
     *                     для живого показа на телефоне
     * @param cancelSignal проверяется перед каждым шагом — отмена задачи с телефона
     * @param questions    вопросы человеку в этой задаче: через него модель спрашивает,
     *                     а ответы попадают в цель на всех следующих шагах
     * @return отчёт: достигнута ли цель, сколько шагов, полный трейс.
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter,
                                       CancelSignal cancelSignal, Clarifications questions) {
        int steps = Math.max(1, Math.min(maxSteps, 20)); // подстраховка от кривых значений, как в BrowserAgent
        List<String> trace = new ArrayList<>();
        boolean completed = false;
        /** Три последних клика в пикселях экрана — чтобы заметить хождение по кругу. */
        List<int[]> recentClicks = new ArrayList<>();
        /** То же для режима UIA, но по подписям элементов, а не по координатам. */
        List<String> recentUiaClicks = new ArrayList<>();
        /** Заголовок окна на прошлом шаге — чтобы заметить, что окно сменилось. */
        String previousWindow = null;
        /**
         * Сколько шагов агент реально выполнил. Считать строки трейса нельзя:
         * на один шаг их приходится несколько (само действие плюс пометки вроде
         * «клик по ...», «окно сменилось»). В журнале прогона из 12 шагов
         * значилось 18 — такие числа испортили бы таблицы в дипломе.
         */
        int stepsDone = 0;

        if (SensitiveInputGuard.mentionsSensitive(goal)) {
            trace.add("стоп: цель похожа на работу с паролем или платёжными данными — отказываюсь для безопасности");
            return new ResultData.AgentReport(false, 0, trace); // до шагов дело не дошло
        }

        // Память: если эту задачу в программе уже решали, сначала пробуем
        // повторить выученный путь вообще без модели (как BrowserAgent в браузере).
        // Не получилось — молча уходим на обычный цикл ниже.
        Optional<TrajectoryCache.Match> remembered = TrajectoryCache.findUia(goal);
        if (remembered.isPresent()) {
            Optional<ResultData.AgentReport> replayed = replayUia(remembered.get(), questions, stepReporter,
                    cancelSignal, deadline);
            if (replayed.isPresent()) return replayed.get();
            trace.add("память устарела — решаю заново с моделью");
        }
        // Вопросы считаем по ЭТОЙ задаче: в плане объект вопросов общий на все
        // подзадачи, и «Да» на весь план не должно запрещать запоминать подзадачу.
        int askedAtStart = questions.asked();
        Recorder rec = new Recorder();
        long startedAtMs = System.currentTimeMillis();

        for (int step = 1; step <= steps; step++) {
            if (cancelSignal.isCancelled()) {
                trace.add("стоп: задачу отменил пользователь");
                break;
            }
            // Лимит сдвигается на время, которое человек думал над вопросами:
            // агент не должен проигрывать по времени из-за того, что спросил.
            if (Instant.now().isAfter(questions.extend(deadline))) {
                trace.add("стоп: превышен общий лимит времени задачи");
                break;
            }
            onStep.accept(step);
            stepsDone++;

            // Сначала пробуем спросить у Windows, из чего состоит активное окно.
            // Если ответ содержательный — работаем по списку элементов, это
            // быстрее и точнее пикселей. Если нет — ниже пойдёт обычный путь.
            UiaSnapshot.Result window = com.jarvis.agent.AgentSettings.flag(
                    com.jarvis.agent.AgentSettings.UIA_ENABLED, true)
                    ? uia.captureForegroundWindow()
                    : UiaSnapshot.Result.EMPTY;
            if (window.isUsable()) {
                // Смена окна — главный признак того, что предыдущее действие
                // сработало не так, как задумано (например, Enter в поиске открыл
                // первый попавшийся результат, а не нужный). Без этой строчки
                // модель видела в истории только свои действия и не понимала,
                // что оказалась не там: в журнале так потерялось 6 шагов.
                if (previousWindow != null && !previousWindow.equals(window.windowTitle())) {
                    trace.add("  окно сменилось: было «" + previousWindow
                              + "», стало «" + window.windowTitle() + "»");
                }
                previousWindow = window.windowTitle();

                // «Рентген»: пока модель думает, зритель видит тот же список
                // элементов, что и она, — рамками поверх окна.
                FaustOverlayController.onSeen(window.windowTitle(), window.elements());
                StepOutcome outcome = uiaStep(step, goal, window, trace, stepReporter, recentUiaClicks,
                                              questions, cancelSignal, rec);
                if (outcome == StepOutcome.STOP) break;
                if (outcome == StepOutcome.DONE) {
                    completed = true;
                    break;
                }
                continue;
            }

            Shot shot;
            try {
                shot = captureShot();
            } catch (Exception e) {
                // не удалось снять экран — не падаем, пробуем на следующем шаге
                trace.add("шаг " + step + ": не удалось сделать скриншот — " + e.getMessage());
                continue;
            }

            long t0 = System.currentTimeMillis();
            // goalFor: цель вместе с ответами человека — иначе ответ «отправь Лере
            // и Маше» через несколько шагов уехал бы за край истории.
            ScreenAction action = brain.decideNextOnScreen(questions.goalFor(goal),
                    shot.jpeg, shot.width, shot.height, trace);
            long dt = System.currentTimeMillis() - t0;
            rec.llmCalls++;
            // Пиксельный шаг в память не записать: координаты «на глаз» не
            // переносятся на следующий раз. Такую задачу не запоминаем.
            rec.recordable = false;
            trace.add("шаг " + step + " (экран " + shot.width + "x" + shot.height + ", " + dt + "мс): " + action);
            FaustOverlayController.onThought(ActionPhrases.describe(action.action(), action.text(), null), action.reason());
            if (stepReporter.needsScreenshot()) {
                // action.x()/y() уже в пикселях shot.jpeg (см. javadoc ScreenAction) — ничего пересчитывать не нужно.
                stepReporter.onStep(step, "шаг " + step + ": " + action, shot.jpeg, shot.width, shot.height,
                        action.x(), action.y());
            } else {
                // Идёт живой стрим: картинку телефон уже получил, второй раз те же
                // пиксели не шлём. Снимок здесь всё равно сделан — он нужен модели,
                // а вот координаты переводим в пиксели экрана, как просит контракт
                // StepReporter для этого случая.
                stepReporter.onStep(step, "шаг " + step + ": " + action, null, 0, 0,
                        action.x() == null ? null : (int) Math.round(action.x() * shot.scale),
                        action.y() == null ? null : (int) Math.round(action.y() * shot.scale));
            }

            if (action.isDone()) {
                // Не всякое done — успех: «отказываюсь», «недостижимо» — нет (DoneVerdict).
                completed = action.succeeded();
                trace.add("  стоп: " + action.reason());
                break;
            }
            if (action.isAsk()) {
                // Вопрос человеку: ничего на экране не делаем, ждём ответа и
                // кладём его в историю — модель увидит его на следующем шаге.
                trace.add("  " + questions.ask(action.text(), action.options(), cancelSignal));
                continue;
            }

            // Пометка о повторе — до выполнения: модель увидит её в «УЖЕ СДЕЛАНО» на
            // следующем шаге и поймёт, что этот путь не работает. Так агент не ходит
            // по кругу минутами (в журнале было: панель задач, окно, панель задач...).
            String repeat = repeatNote(action, shot.scale, recentClicks);
            if (repeat != null) trace.add("  " + repeat);

            try {
                String note = apply(action, shot.scale);
                if (note != null) trace.add("  " + note);
                settle();
                if ("open_app".equalsIgnoreCase(action.action())) sleep(OPEN_APP_EXTRA_MS);
            } catch (SecurityException e) {
                // защита от ввода пароля/платёжных данных — останавливаемся, а не пропускаем шаг
                trace.add("  стоп: " + e.getMessage());
                break;
            } catch (Exception e) {
                // промах (мимо кнопки, координаты устарели, фокус не тот) — не падаем,
                // фиксируем в трейсе и на следующем шаге снимаем экран заново
                trace.add("  промах: " + e.getClass().getSimpleName()
                          + (e.getMessage() == null ? "" : " — " + e.getMessage()));
            }
        }
        // Запоминаем путь, только если он целиком пройден по списку элементов
        // (не по пикселям), без вопросов человеку в этой задаче и с успехом.
        if (completed && rec.recordable && !rec.steps.isEmpty() && questions.asked() == askedAtStart) {
            TrajectoryCache.recordUia(goal, rec.steps, System.currentTimeMillis() - startedAtMs, rec.llmCalls);
        }
        trace.add(TrajectoryCache.LLM_CALLS_MARK + rec.llmCalls);
        return new ResultData.AgentReport(completed, stepsDone, trace);
    }

    // --- память траекторий в программах ----------------------------------------

    /**
     * Метка в пути к файлу: на её место при повторе подставляется файл,
     * скачанный В ЭТОТ раз. Иначе память отправила бы старый архив
     * «(6).zip» вместо только что скачанного «(7).zip».
     */
    static final String DOWNLOADED = "{скачанный файл}";
    /**
     * «скачан файл C:\...\Hello-World-master (7).zip (351 Б)». Конец пути —
     * по размеру в скобках с единицей измерения: номер копии «(7)» тоже в
     * скобках, и без единицы путь обрывался бы на нём (так и было, поймал тест).
     */
    private static final java.util.regex.Pattern DOWNLOAD_FACT =
            java.util.regex.Pattern.compile("скачан файл (.+?) \\(\\d+(?:\\.\\d+)? (?:Б|КБ|МБ)\\)");

    /** Что накапливается по ходу задачи для записи в память. */
    private static final class Recorder {
        final List<TrajectoryCache.Step> steps = new ArrayList<>();
        boolean recordable = true;
        int llmCalls;
    }

    /** Выполненное в режиме UIA действие -> шаг траектории; null — повторять нечего. */
    private static TrajectoryCache.Step stepFrom(AgentAction a, UiaElement target, String goal, Clarifications q) {
        String kind = a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "click" -> target == null ? null
                    : new TrajectoryCache.Step("click", target.controlType(), "uia", target.name(), -1, null, null, null, false);
            case "type" -> new TrajectoryCache.Step("type", null, null, null, -1, a.text(), null, null,
                    !TrajectoryCache.norm(a.text()).isBlank() && TrajectoryCache.norm(goal).contains(TrajectoryCache.norm(a.text())));
            case "key", "open_app" -> new TrajectoryCache.Step(kind, null, null, null, -1, a.text(), null, null, false);
            case "scroll" -> new TrajectoryCache.Step("scroll", null, null, null, -1, null, a.amount(), null, false);
            case "paste_file", "extract_archive" -> new TrajectoryCache.Step(kind, null, null, null, -1,
                    withPlaceholder(a.text(), q.taskContext()), null, null, false);
            default -> null; // find_file, ask, done — повторять нечего
        };
    }

    /** Путь к только что скачанному файлу — в метку, остальное как есть. */
    static String withPlaceholder(String text, String context) {
        if (text == null || context == null) return text;
        String[] parts = text.split("\\|", 2);
        if (!parts[0].isBlank() && context.contains(parts[0].strip())) {
            return DOWNLOADED + (parts.length > 1 ? "|" + parts[1] : "");
        }
        return text;
    }

    /** Метка -> путь, скачанный в этот раз (последний факт в контексте плана); null — факта нет. */
    static String resolvePlaceholder(String text, String context) {
        if (text == null || !text.contains(DOWNLOADED)) return text;
        if (context == null) return null;
        java.util.regex.Matcher m = DOWNLOAD_FACT.matcher(context);
        String last = null;
        while (m.find()) last = m.group(1).strip();
        return last == null ? null : text.replace(DOWNLOADED, last);
    }

    /**
     * Повтор выученного пути БЕЗ единого обращения к модели.
     *
     * <p>Элемент ищется в свежем снимке по типу и подписи: сначала точное
     * совпадение, затем по первой части подписи — у чата в Telegram подпись
     * «Избранное, Ramiz, В сети, …, вчера в 10:40» меняется с каждым
     * сообщением, а «Избранное» остаётся. Если кандидатов несколько или ни
     * одного — повтор прерывается: кликнуть «примерно туда» в мессенджере
     * значит рискнуть отправить файл не тому человеку.
     *
     * @return отчёт, если путь пройден целиком; пусто — память устарела
     */
    private Optional<ResultData.AgentReport> replayUia(TrajectoryCache.Match match, Clarifications questions,
                                                       StepReporter reporter, CancelSignal cancel, Instant deadline) {
        TrajectoryCache.Trajectory saved = match.trajectory();
        return replaySteps(saved.steps(), match.slot(),
                "эту задачу в программе уже решали — повторяю выученный путь без модели",
                saved.baselineMs(), saved.baselineLlmCalls(),
                () -> TrajectoryCache.forget(saved), () -> TrajectoryCache.noteReplay(saved), i -> { },
                questions, reporter, cancel, deadline);
    }

    /**
     * Навык, которому человек научил агента показом (SkillStore).
     *
     * <p>Сначала — дословный повтор записанных шагов, без модели, тем же кодом,
     * что повтор из памяти. Если окно изменилось и повторить дословно нельзя,
     * записанные шаги становятся для модели ИНСТРУКЦИЕЙ: «человек показал так:
     * 1) … 2) …» — и она доделывает навык сама. Показ работает и как готовый
     * сценарий, и как туториал.
     *
     * @param onStepStart номер шага навыка (с 0), который сейчас начинается, — для чек-листа
     */
    public ResultData.AgentReport runSkill(String name, List<TrajectoryCache.Step> skillSteps, List<String> descriptions,
                                           long recordedMs, int maxSteps, Instant deadline, IntConsumer onStep,
                                           StepReporter reporter, CancelSignal cancel, Clarifications questions,
                                           IntConsumer onStepStart) {
        Optional<ResultData.AgentReport> replayed = replaySteps(skillSteps, null,
                "навык «" + name + "», выученный показом, — повторяю записанные шаги без модели",
                recordedMs, 0, () -> { }, () -> { }, onStepStart, questions, reporter, cancel, deadline);
        if (replayed.isPresent()) return replayed.get();

        StringBuilder tutorial = new StringBuilder("Выполни навык «").append(name)
                .append("». Человек показал его так:\n");
        for (int i = 0; i < descriptions.size(); i++) {
            tutorial.append("  ").append(i + 1).append(") ").append(descriptions.get(i)).append('\n');
        }
        tutorial.append("Часть шагов уже могла выполниться — посмотри на экран и продолжи с нужного места. ")
                .append("Повторяй действия человека по смыслу, даже если окно выглядит немного иначе.");
        FaustOverlayController.onThought("Доделываю навык по показу", "дословно повторить не вышло — следую показанным шагам");
        return run(tutorial.toString(), maxSteps, deadline, onStep, reporter, cancel, questions);
    }

    /**
     * Проиграть шаги без модели — общее для памяти траекторий и навыков.
     *
     * @param onStale   что сделать, если повтор не удался (память устарела)
     * @param onSuccess что сделать после успешного повтора
     */
    private Optional<ResultData.AgentReport> replaySteps(List<TrajectoryCache.Step> stepsToReplay, String slot,
                                                         String header, long baselineMs, int baselineLlmCalls,
                                                         Runnable onStale, Runnable onSuccess, IntConsumer onStepStart,
                                                         Clarifications questions, StepReporter reporter,
                                                         CancelSignal cancel, Instant deadline) {
        List<String> trace = new ArrayList<>();
        trace.add(header);
        FaustOverlayController.onMemory(true);
        long t0 = System.currentTimeMillis();
        int step = 0;
        try {
            for (TrajectoryCache.Step s : stepsToReplay) {
                if (cancel.isCancelled() || Instant.now().isAfter(questions.extend(deadline))) {
                    trace.add("стоп: повтор прерван");
                    return Optional.of(new ResultData.AgentReport(false, step, trace));
                }
                onStepStart.accept(step);
                step++;
                String line = "шаг " + step + " (из памяти): ";
                switch (s.action()) {
                    case "click" -> {
                        UiaSnapshot.Result w = null;
                        UiaElement e = null;
                        // Окно могло ещё не дорисоваться после прошлого шага — даём ему шанс.
                        for (int attempt = 0; attempt < 3 && e == null; attempt++) {
                            if (attempt > 0) sleep(500);
                            w = uia.captureForegroundWindow();
                            e = matchElement(w, s);
                        }
                        if (e == null) {
                            trace.add(line + "элемент «" + s.label() + "» не найден — память устарела");
                            onStale.run();
                            return Optional.empty();
                        }
                        FaustOverlayController.onSeen(w.windowTitle(), w.elements());
                        FaustOverlayController.onChosen(e.index());
                        FaustOverlayController.onThought(ActionPhrases.describe("click", null, e.name()),
                                "повторяю выученный путь — без модели");
                        AgentAction a = new AgentAction("click", e.index(), null, null, null, "из памяти");
                        trace.add(line + a + " по «" + e.name() + "»");
                        reportStep(step, a, e, reporter, "шаг " + step + " (из памяти): " + a);
                        clickAt(e.centerX(), e.centerY());
                    }
                    case "type" -> {
                        String text = s.textFromGoal() && slot != null ? slot : s.text();
                        if (SensitiveInputGuard.mentionsSensitive(text)) throw new SecurityException("похоже на пароль");
                        guardAgainstShell(com.jarvis.agent.WindowFocus.foregroundTitle(), text);
                        FaustOverlayController.onThought(ActionPhrases.describe("type", text, null), "повторяю выученный путь — без модели");
                        trace.add(line + "type(\"" + text + "\")");
                        typeText(text);
                    }
                    case "key" -> {
                        trace.add(line + "key(" + s.text() + ")");
                        pressKeys(s.text());
                    }
                    case "open_app" -> {
                        if (appOpener == null) return Optional.empty();
                        FaustOverlayController.onThought(ActionPhrases.describe("open_app", s.text(), null), "повторяю выученный путь — без модели");
                        trace.add(line + "open_app(\"" + s.text() + "\") — " + appOpener.open(s.text()));
                        sleep(OPEN_APP_EXTRA_MS);
                    }
                    case "scroll" -> {
                        int amount = s.amount() == null ? 3 : s.amount();
                        AgentOverlays.mouseActionHere(() -> robot.mouseWheel(amount));
                        trace.add(line + "scroll(" + amount + ")");
                    }
                    case "paste_file", "extract_archive" -> {
                        String spec = resolvePlaceholder(s.text(), questions.taskContext());
                        if (spec == null) {
                            trace.add(line + "не знаю, какой файл скачан в этот раз — решаю заново с моделью");
                            return Optional.empty();
                        }
                        FaustOverlayController.onThought(ActionPhrases.describe(s.action(), spec, null), "повторяю выученный путь — без модели");
                        String note = "paste_file".equals(s.action()) ? pasteFile(spec) : com.jarvis.agent.FileTools.extractZip(spec);
                        trace.add(line + s.action() + " — " + note);
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
                settleUia();
                FaustOverlayController.onSeenDone();
            }
        } catch (SecurityException e) {
            trace.add("  стоп: " + e.getMessage());
            return Optional.of(new ResultData.AgentReport(false, step, trace));
        } catch (Exception e) {
            // Что-то пошло не так на повторе — не страшно: отдаём задачу модели.
            System.err.println("повтор из памяти прерван: " + e.getMessage());
            return Optional.empty();
        }
        long dt = System.currentTimeMillis() - t0;
        onSuccess.run();
        trace.add(TrajectoryCache.REPLAY_MARK + " за " + dt + " мс, " + TrajectoryCache.LLM_CALLS_MARK
                  + "0 (в первый раз: " + baselineMs + " мс и " + baselineLlmCalls + " обращен.)");
        trace.add("  стоп: выполнено из памяти за " + (dt / 1000) + " с");
        return Optional.of(new ResultData.AgentReport(true, step, trace));
    }

    /** Элемент для повтора: тот же тип и та же подпись (целиком или её первая часть), и только один такой. */
    private static UiaElement matchElement(UiaSnapshot.Result w, TrajectoryCache.Step s) {
        if (w == null || s.label() == null) return null;
        List<UiaElement> sameType = w.elements().stream()
                .filter(e -> e.controlType() != null && e.controlType().equals(s.tag()))
                .toList();
        for (UiaElement e : sameType) {
            if (e.name().equals(s.label())) return e;
        }
        String head = ActionPhrases.firstPart(s.label());
        List<UiaElement> byHead = sameType.stream()
                .filter(e -> ActionPhrases.firstPart(e.name()).equalsIgnoreCase(head))
                .toList();
        return byHead.size() == 1 ? byHead.get(0) : null;
    }

    /** Чем кончился один шаг: продолжаем цикл, цель достигнута, или останавливаемся без успеха. */
    private enum StepOutcome { CONTINUE, DONE, STOP }

    /**
     * Один шаг в режиме UI Automation: модель выбирает элемент по номеру из
     * списка, который отдала Windows, и агент кликает точно в его центр.
     *
     * @param recentClicks подписи элементов, по которым уже кликали, — чтобы заметить хождение по кругу
     * @param questions    вопросы человеку в этой задаче (см. {@link Clarifications})
     */
    private StepOutcome uiaStep(int step, String goal, UiaSnapshot.Result window,
                                List<String> trace, StepReporter stepReporter,
                                List<String> recentClicks, Clarifications questions,
                                CancelSignal cancelSignal, Recorder rec) {
        long t0 = System.currentTimeMillis();
        AgentAction action = brain.decideNextInWindow(questions.goalFor(goal), window.windowTitle(),
                window.elements(), trace);
        long dt = System.currentTimeMillis() - t0;
        rec.llmCalls++;
        trace.add("шаг " + step + " (UIA, окно \"" + window.windowTitle() + "\", "
                  + window.elements().size() + " элементов, " + dt + "мс): " + action);

        UiaElement target = findElement(window, action.index());
        // Модель выбрала: рамка элемента вспыхивает, а в панели — что и зачем.
        if (target != null) FaustOverlayController.onChosen(target.index());
        FaustOverlayController.onThought(
                ActionPhrases.describe(action.action(), action.text(), target == null ? null : target.name()),
                action.reason());
        reportStep(step, action, target, stepReporter);

        if (action.isDone()) {
            trace.add("  стоп: " + action.reason());
            return action.succeeded() ? StepOutcome.DONE : StepOutcome.STOP;
        }
        if (action.isAsk()) {
            trace.add("  " + questions.ask(action.text(), action.options(), cancelSignal));
            return StepOutcome.CONTINUE;
        }

        // Та же защита от хождения по кругу, что и в пиксельном режиме, но
        // сравниваем не координаты, а подписи: в списке элементов они устойчивее.
        if ("click".equalsIgnoreCase(action.action()) && target != null) {
            if (recentClicks.contains(target.name())) {
                trace.add("  повтор: по «" + target.name() + "» уже кликали, и цель не достигнута"
                          + " — нужен другой способ (поиск в программе, клавиши, другая программа)");
            }
            recentClicks.add(target.name());
            if (recentClicks.size() > 3) recentClicks.remove(0);
        }

        try {
            String note = applyInWindow(action, target, window);
            if (note != null) trace.add("  " + note);
            // В память — только то, что выполнилось без ошибки: промахи повторять незачем.
            TrajectoryCache.Step recorded = stepFrom(action, target, goal, questions);
            if (recorded != null) rec.steps.add(recorded);
            settleUia();
            if ("open_app".equalsIgnoreCase(action.action())) sleep(OPEN_APP_EXTRA_MS);
        } catch (SecurityException e) {
            trace.add("  стоп: " + e.getMessage());
            return StepOutcome.STOP;
        } catch (Exception e) {
            trace.add("  промах: " + e.getClass().getSimpleName()
                      + (e.getMessage() == null ? "" : " — " + e.getMessage()));
        } finally {
            // Окно сейчас изменится — рамки «рентгена» от прошлого снимка убираем.
            FaustOverlayController.onSeenDone();
        }
        return StepOutcome.CONTINUE;
    }

    /** Подпись элемента, в котором стоял курсор ввода на момент снимка, или null. */
    private String focusedFieldName(UiaSnapshot.Result window) {
        return window.elements().stream()
                .filter(UiaElement::hasFocus)
                .map(UiaElement::name)
                .findFirst().orElse(null);
    }

    /** Элемент с таким номером в текущем снимке, или null, если модель назвала несуществующий. */
    private UiaElement findElement(UiaSnapshot.Result window, Integer index) {
        if (index == null) return null;
        return window.elements().stream().filter(e -> e.index() == index).findFirst().orElse(null);
    }

    /**
     * Живой показ шага на телефоне. В режиме UIA скриншот модели не нужен, но
     * пользователю он нужен по-прежнему — поэтому снимаем его здесь, локально,
     * и без отправки в модель (дорого только последнее).
     */
    private void reportStep(int step, AgentAction action, UiaElement target, StepReporter reporter) {
        // Обоснование модели — второй строкой: телефон покажет его под действием.
        // Первая строка остаётся прежней («шаг N: click(12)») — по ней описание
        // шага переводится в фразу для виджета на ПК (CommandExecutor.describeStep).
        String description = "шаг " + step + ": " + action
                + (action.reason() == null || action.reason().isBlank() || action.isDone() ? "" : "\n" + action.reason());
        reportStep(step, action, target, reporter, description);
    }

    private void reportStep(int step, AgentAction action, UiaElement target, StepReporter reporter, String description) {
        if (reporter == StepReporter.NONE) return;
        if (!reporter.needsScreenshot()) {
            // Идёт живой стрим — картинку шага не снимаем вовсе, телефон видит
            // экран и так. Координаты цели всё равно шлём: по ним телефон
            // рисует свою метку поверх живого кадра.
            reporter.onStep(step, description, null, 0, 0,
                    target == null ? null : target.centerX(),
                    target == null ? null : target.centerY());
            return;
        }
        try {
            Shot shot = captureShot();
            Integer tx = null, ty = null;
            if (target != null) {
                // координаты элемента даны в пикселях экрана, а телефон показывает
                // уменьшенную картинку — переводим в её систему координат
                tx = (int) Math.round(target.centerX() / shot.scale);
                ty = (int) Math.round(target.centerY() / shot.scale);
            }
            reporter.onStep(step, description, shot.jpeg, shot.width, shot.height, tx, ty);
        } catch (Exception e) {
            // живой показ — не повод ронять задачу
            reporter.onStep(step, description, null, 0, 0, null, null);
        }
    }

    /**
     * Выполнить действие, выбранное моделью в режиме UIA.
     * @return пометка для трейса или null
     */
    private String applyInWindow(AgentAction a, UiaElement target, UiaSnapshot.Result window) throws Exception {
        switch (a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT)) {
            case "open_app" -> {
                if (appOpener == null) throw new IllegalStateException("открывать программы здесь нельзя");
                if (a.text() == null || a.text().isBlank()) throw new IllegalArgumentException("не указано, какую программу открыть");
                return "открыто: " + appOpener.open(a.text());
            }
            case "key" -> {
                pressKeys(a.text());
                return "нажато: " + a.text();
            }
            case "click" -> {
                if (target == null) {
                    throw new IllegalArgumentException("элемента с номером " + a.index() + " нет в текущем списке");
                }
                if (target.isPassword()) {
                    // Второй рубеж защиты вдобавок к SensitiveInputGuard: здесь
                    // о поле пароля говорит сама Windows, а не наши эвристики.
                    throw new SecurityException("элемент «" + target.name() + "» — поле пароля, не трогаю");
                }
                clickAt(target.centerX(), target.centerY());
                return "клик по «" + target.name() + "»";
            }
            case "type" -> {
                String text = a.text() == null ? "" : a.text();
                if (SensitiveInputGuard.mentionsSensitive(text)) {
                    throw new SecurityException("похоже на пароль/платёжные данные — отказываюсь вводить");
                }
                guardAgainstShell(window.windowTitle(), text);
                typeText(text);
                // Куда именно ушёл текст — важнее, чем сам факт ввода. В журнале
                // был прогон, где агент напечатал сообщение в поле поиска вместо
                // поля сообщения и не заметил этого до самого конца задачи.
                String field = focusedFieldName(window);
                return field == null ? "введено: «" + text + "»"
                                     : "введено «" + text + "» в поле «" + field + "»";
            }
            case "scroll" -> {
                int amount = a.amount() == null ? 3 : a.amount();
                AgentOverlays.mouseActionHere(() -> robot.mouseWheel(amount));
            }
            case "find_file" -> {
                return findFile(a.text());
            }
            case "extract_archive" -> {
                requireFileTools();
                return com.jarvis.agent.FileTools.extractZip(a.text());
            }
            case "paste_file" -> {
                String pasted = pasteFile(a.text());
                // Как и у type: куда именно ушла вставка — важнее самого факта.
                String field = focusedFieldName(window);
                return field == null ? pasted : pasted + " в «" + field + "»";
            }
            default -> throw new IllegalArgumentException("неизвестное действие: " + a.action());
        }
        return null;
    }

    // --- файлы (этап 3): поиск и вставка без окна «Открыть» ---------------------

    /** Найти файл по части имени в разрешённых папках — результат модель прочтёт в трейсе. */
    private String findFile(String query) {
        requireFileTools();
        if (query == null || query.isBlank()) throw new IllegalArgumentException("не сказано, какой файл искать");
        return com.jarvis.agent.FileTools.describeFound(query, com.jarvis.agent.FileTools.find(query));
    }

    /**
     * Вставить файл туда, где сейчас фокус: в поле сообщения мессенджера он
     * прикрепится, в окно Проводника — скопируется. Заменяет путь через
     * скрепку и системное окно «Открыть» (5–8 шагов, каждый с риском промаха).
     */
    private String pasteFile(String path) {
        requireFileTools();
        java.nio.file.Path file = com.jarvis.agent.FileTools.checkAllowed(path);
        com.jarvis.agent.FileTools.copyToClipboard(file);
        pressPaste();
        // Программе нужно время, чтобы прочитать файл и показать предпросмотр.
        sleep(PASTE_FILE_EXTRA_MS);
        return "вставлен файл «" + file.getFileName() + "» (" + com.jarvis.agent.FileTools.sizeLabel(file)
               + ") — программа могла показать окно с файлом, смотри новый список элементов";
    }

    /**
     * Печатать в терминал и печатать команды нельзя (см. ShellGuard). Это
     * SecurityException, а не обычный промах: цикл остановится, как и при
     * попытке ввести пароль, — такие попытки не должны повторяться «другим способом».
     */
    private static void guardAgainstShell(String windowTitle, String text) {
        if (ShellGuard.isShellWindow(windowTitle)) {
            throw new SecurityException("окно «" + windowTitle + "» — терминал, печатать в него агенту нельзя");
        }
        if (ShellGuard.looksLikeCommand(text)) {
            throw new SecurityException("текст похож на команду запуска — агенту нельзя: " + ShellGuard.HINT);
        }
    }

    private static void requireFileTools() {
        if (!com.jarvis.agent.AgentSettings.flag(com.jarvis.agent.AgentSettings.FILES_ENABLED, true)) {
            throw new IllegalArgumentException("действия с файлами выключены в настройках (files.enabled)");
        }
    }

    /**
     * Не повторяет ли агент клик, который уже был недавно (в пределах 25 px от
     * одного из трёх последних)? Если да — вернём пометку для трейса. Сам клик
     * не запрещаем: иногда повтор нужен (кнопка не сработала с первого раза).
     */
    private String repeatNote(ScreenAction a, double scale, List<int[]> recentClicks) {
        if (!"click".equalsIgnoreCase(a.action()) || a.x() == null || a.y() == null) return null;
        int x = (int) Math.round(a.x() * scale), y = (int) Math.round(a.y() * scale);
        boolean repeated = recentClicks.stream().anyMatch(p ->
                Math.hypot(p[0] - x, p[1] - y) <= REPEAT_RADIUS_PX * scale);
        recentClicks.add(new int[]{x, y});
        if (recentClicks.size() > 3) recentClicks.remove(0);
        return repeated
                ? "повтор: сюда уже кликали, и цель не достигнута — нужен другой способ (open_app, поиск, клавиши)"
                : null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- снимок экрана ---------------------------------------------

    /** scale — во сколько раз картинка меньше реального экрана (нужно, чтобы вернуть координаты модели в "реальные" пиксели). */
    private record Shot(byte[] jpeg, int width, int height, double scale) {}

    private Shot captureShot() throws Exception {
        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        // Оверлеи «Фауст» висят поверх всех окон. Windows исключает их из
        // снимков (excludeFromCapture); если не смогла — AgentOverlays на миг
        // их уберёт. Модель не должна видеть их как часть интерфейса.
        BufferedImage full = AgentOverlays.capture(() -> robot.createScreenCapture(screenRect));
        BufferedImage scaled = ImageUtil.scaleDown(full, MAX_IMAGE_WIDTH);
        byte[] jpeg = ImageUtil.toJpeg(scaled, 0.5f);
        double scale = (double) full.getWidth() / scaled.getWidth(); // >= 1
        return new Shot(jpeg, scaled.getWidth(), scaled.getHeight(), scale);
    }

    // --- выполнение действия ----------------------------------------

    /** @return пометка для трейса (например, что именно открылось) или null */
    private String apply(ScreenAction a, double scale) throws Exception {
        switch (a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT)) {
            case "open_app" -> {
                if (appOpener == null) throw new IllegalStateException("открывать программы здесь нельзя");
                if (a.text() == null || a.text().isBlank()) throw new IllegalArgumentException("не указано, какую программу открыть");
                // Настоящее название («Telegram», а не «телеграм») — в трейс: модель
                // на следующем шаге увидит, что открылось именно то, что нужно.
                return "открыто: " + appOpener.open(a.text());
            }
            case "key" -> pressKeys(a.text());
            case "click" -> click(a, scale);
            case "type" -> {
                String text = a.text() == null ? "" : a.text();
                if (SensitiveInputGuard.mentionsSensitive(text)) {
                    throw new SecurityException("похоже на пароль/платёжные данные — отказываюсь вводить");
                }
                // В пиксельном режиме заголовка окна в снимке нет — спрашиваем Windows.
                guardAgainstShell(com.jarvis.agent.WindowFocus.foregroundTitle(), text);
                typeText(text);
            }
            // Колесо крутит окно под курсором — курсор не должен оказаться над нашим окном.
            case "scroll" -> AgentOverlays.mouseActionHere(() -> robot.mouseWheel(a.dy() == null ? 3 : a.dy()));
            case "find_file" -> {
                return findFile(a.text());
            }
            case "extract_archive" -> {
                requireFileTools();
                return com.jarvis.agent.FileTools.extractZip(a.text());
            }
            case "paste_file" -> {
                return pasteFile(a.text());
            }
            default -> throw new IllegalArgumentException("неизвестное действие: " + a.action());
        }
        return null;
    }

    // --- клавиши ------------------------------------------------------

    private static final Map<String, Integer> NAMED_KEYS = Map.ofEntries(
            Map.entry("enter", KeyEvent.VK_ENTER), Map.entry("return", KeyEvent.VK_ENTER),
            Map.entry("esc", KeyEvent.VK_ESCAPE), Map.entry("escape", KeyEvent.VK_ESCAPE),
            Map.entry("tab", KeyEvent.VK_TAB), Map.entry("space", KeyEvent.VK_SPACE),
            Map.entry("backspace", KeyEvent.VK_BACK_SPACE), Map.entry("delete", KeyEvent.VK_DELETE),
            Map.entry("up", KeyEvent.VK_UP), Map.entry("down", KeyEvent.VK_DOWN),
            Map.entry("left", KeyEvent.VK_LEFT), Map.entry("right", KeyEvent.VK_RIGHT),
            Map.entry("home", KeyEvent.VK_HOME), Map.entry("end", KeyEvent.VK_END),
            Map.entry("pageup", KeyEvent.VK_PAGE_UP), Map.entry("pagedown", KeyEvent.VK_PAGE_DOWN));

    private static final Map<String, Integer> MODIFIERS = Map.of(
            "ctrl", KeyEvent.VK_CONTROL, "control", KeyEvent.VK_CONTROL,
            "shift", KeyEvent.VK_SHIFT, "alt", KeyEvent.VK_ALT);

    /**
     * Нажать клавишу или сочетание вида "enter", "ctrl+f", "ctrl+shift+t".
     * Модификаторы зажимаются по порядку, отпускаются в обратном — как пальцами.
     *
     * Запрещено: Alt+F4 (закрыть окно — агент не должен закрывать чужие
     * программы) и клавиша Win (меню «Пуск» и системные сочетания — для
     * открытия программ есть open_app).
     */
    private void pressKeys(String combo) {
        if (combo == null || combo.isBlank()) throw new IllegalArgumentException("не указано, какую клавишу нажать");
        String c = combo.toLowerCase(Locale.ROOT).replace(" ", "");
        if (c.equals("alt+f4") || c.contains("win") || c.contains("meta")) {
            throw new IllegalArgumentException("сочетание «" + combo + "» агенту запрещено");
        }
        List<Integer> held = new ArrayList<>();
        Integer main = null;
        for (String part : c.split("\\+")) {
            if (MODIFIERS.containsKey(part)) held.add(MODIFIERS.get(part));
            else main = keyCode(part);
        }
        if (main == null) throw new IllegalArgumentException("не понял клавишу «" + combo + "»");
        for (int k : held) robot.keyPress(k);
        robot.keyPress(main);
        robot.keyRelease(main);
        for (int i = held.size() - 1; i >= 0; i--) robot.keyRelease(held.get(i));
    }

    /** "enter" -> VK_ENTER, "f" -> VK_F, "5" -> VK_5, "f5" -> VK_F5; null — не знаем такой. */
    private static Integer keyCode(String name) {
        if (NAMED_KEYS.containsKey(name)) return NAMED_KEYS.get(name);
        if (name.length() == 1) {
            char ch = name.charAt(0);
            if (ch >= 'a' && ch <= 'z') return KeyEvent.VK_A + (ch - 'a');
            if (ch >= '0' && ch <= '9') return KeyEvent.VK_0 + (ch - '0');
        }
        if (name.matches("f([1-9]|1[0-2])")) return KeyEvent.VK_F1 + Integer.parseInt(name.substring(1)) - 1;
        return null;
    }

    /** Координаты модели даны в системе уменьшенной картинки — переводим их обратно в реальные пиксели экрана. */
    private void click(ScreenAction a, double scale) throws Exception {
        if (a.x() == null || a.y() == null) {
            throw new IllegalArgumentException("модель не указала координаты (x,y) для click");
        }
        clickAt((int) Math.round(a.x() * scale), (int) Math.round(a.y() * scale));
    }

    /** Клик по точке экрана — общий путь для обоих режимов зрения. */
    private void clickAt(int x, int y) throws Exception {
        // 1. Мышь плавно летит к цели (если overlay.smoothMouse) — на демо видно,
        //    куда направился ИИ, и ореол курсора летит вместе с ней.
        FaustOverlayController.moveMouse(robot, x, y);
        // 2. ДО нажатия: дуги ореола сжимаются, в точке клика — медный круг.
        FaustOverlayController.onClick(x, y, false);
        // 3. Само нажатие. Модель не видит наши окна (они убраны из снимков) и
        //    может нацелиться прямо в виджет или плашку — там у программ часто
        //    кнопки «ОК»/«Сохранить». Такое окно на миг уберётся, иначе клик
        //    пришёлся бы в него, а то и в «Стоп»: агент отменил бы сам себя.
        AgentOverlays.mouseAction(x, y, () -> {
            robot.mouseMove(x, y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        });
    }

    /** Печатает через буфер обмена + Ctrl/Cmd+V — как и обычный CommandExecutor.typeText, работает с юникодом. */
    private void typeText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        pressPaste();
    }

    /** Ctrl+V (Cmd+V на Mac) — общий для вставки текста и файла. */
    private void pressPaste() {
        int mod = OS.contains("mac") ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(mod);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(mod);
    }

    /** Пауза, чтобы система/приложение успели отреагировать на действие перед новым скриншотом. */
    private void settle() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Пауза для режима UIA — заметно короче пиксельной.
     *
     * <p>1200 мс в пиксельном режиме были нужны, потому что сразу после паузы
     * делался скриншот: снимешь рано — поймаешь окно в середине анимации. Здесь
     * следом идёт снимок дерева, а он сам занимает 0.2–0.6 с, и окно успевает
     * прийти в себя за это время. Лишнее ожидание тут — это чистая потеря на
     * каждом шаге.
     *
     * <p>Значение можно поднять через {@code uia.settleMs} в agent.properties,
     * если попадётся программа, которая перестраивает окно медленнее.
     */
    private void settleUia() {
        sleep(com.jarvis.agent.AgentSettings.number("uia.settleMs", 400, 0, 5000));
    }
}
