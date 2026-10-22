package com.jarvis.agent.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.agent.FaustOverlayController;
import com.jarvis.agent.protocol.ResultData;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;

/**
 * Цикл computer-use агента для браузера (scope = BROWSER).
 *
 *   снимок страницы  ->  LLM решает действие  ->  Selenium выполняет  ->  повтор
 *
 * пока действие не станет "done" или не кончатся шаги. Ошибка на шаге не роняет
 * цикл: пишем её в трейс и на следующей итерации делаем свежий снимок.
 *
 * DOM-режим: агент «видит» не пиксели, а список интерактивных элементов —
 * это надёжнее и дешевле пиксельного vision. Пиксельный режим (scope=SCREEN) —
 * отдельная задача на потом.
 */
public class BrowserAgent {

    /**
     * JS в браузере: находит видимые интерактивные элементы, помечает каждый
     * атрибутом data-jarvis-idx и возвращает их список строкой JSON.
     * Пометка нужна, чтобы потом однозначно найти элемент по индексу,
     * который выбрала модель.
     */
    private static final String COLLECT_JS = """
        const sel = 'a,button,input,textarea,select,[role=button],[role=link],[role=tab],[onclick]';
        const out = [];
        let i = 0;
        for (const el of document.querySelectorAll(sel)) {
          const r = el.getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;                 // невидимый / нулевой размер
          if (r.bottom < 0 || r.top > window.innerHeight) continue;  // за пределами экрана по вертикали
          const st = getComputedStyle(el);
          if (st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') continue;
          el.setAttribute('data-jarvis-idx', i);
          let t = (el.innerText || el.value || el.getAttribute('aria-label')
                   || el.getAttribute('placeholder') || el.getAttribute('title') || '')
                   .replace(/\\s+/g, ' ').trim().slice(0, 120);
          out.push({idx: i, tag: el.tagName.toLowerCase(),
                    role: el.getAttribute('role') || el.getAttribute('type') || '',
                    text: t,
                    x: Math.round(r.left + r.width / 2),
                    y: Math.round(r.top + r.height / 2)});
          i++;
        }
        return JSON.stringify(out);
        """;

    /**
     * Отпечаток состояния страницы для {@link #settle(WebDriver)}: готовность
     * документа и число интерактивных элементов. Селектор тот же, что в
     * COLLECT_JS — меряем ровно то, что потом попадёт в снимок для модели.
     */
    private static final String SIGNATURE_JS = """
        return document.readyState + '|' + document.querySelectorAll(
            'a,button,input,textarea,select,[role=button],[role=link],[role=tab],[onclick]').length;
        """;

    /** Обязательная пауза после действия: браузер ещё не начал перерисовку. */
    private static final long MIN_SETTLE_MS = 150;
    /** Шаг опроса состояния страницы. */
    private static final long POLL_SETTLE_MS = 100;
    /** Потолок ожидания — столько же, сколько занимал прежний безусловный sleep. */
    private static final long MAX_SETTLE_MS = 1500;

    private final VisionAgent brain;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Окно браузера ЖИВЁТ МЕЖДУ ЗАДАЧАМИ и намеренно не закрывается в конце —
     * иначе пользователь не увидит результат (видео закроется сразу после
     * "готово"). Следующая задача переиспользует это же окно, поэтому окна не
     * плодятся. Закрывается только при выходе агента (shutdown hook ниже) или
     * явным вызовом {@link #closeBrowser()}.
     */
    private WebDriver driver;

    public BrowserAgent(VisionAgent brain) {
        this.brain = brain;
        // Агента могут закрыть из трея — тогда за собой надо прибрать chromedriver,
        // иначе останется висеть процесс без окна.
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeBrowser));
    }

    /** Живо ли окно с прошлой задачи. Пользователь мог закрыть его крестиком. */
    private boolean isDriverAlive() {
        if (driver == null) return false;
        try {
            driver.getWindowHandles();  // бросит исключение, если окно/сессия уже мертвы
            return true;
        } catch (Exception e) {
            driver = null;
            return false;
        }
    }

    /** Отдаёт живой браузер: переиспользует прошлый или поднимает новый. */
    private WebDriver browser() {
        if (isDriverAlive()) {
            return driver;
        }
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--start-maximized", "--disable-blink-features=AutomationControlled");
        opts.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        // Не даём Chrome умереть вместе с chromedriver, если агент перезапустится.
        opts.setExperimentalOption("detach", true);
        // Загрузки — в обычные «Загрузки» пользователя и без окна «Сохранить как»:
        // это окно Selenium не видит (оно системное), и задача на нём бы встала.
        // Папку берём ту же, за которой следит DownloadWatcher.
        opts.setExperimentalOption("prefs", java.util.Map.of(
                "download.default_directory", com.jarvis.agent.FileTools.downloadsDir().toString(),
                "download.prompt_for_download", false,
                "download.directory_upgrade", true));

        driver = new ChromeDriver(opts);
        // Одна секунда, а не пять. Этот таймаут тратится ТОЛЬКО когда элемента
        // нет: модель промахнулась индексом или страница уехала. Ждать в таком
        // случае бессмысленно — цикл всё равно восстанавливается новым снимком,
        // а пять секунд на каждом промахе складывались в заметную задержку.
        // Элемент, который был в снимке миллисекунды назад, за секунду найдётся.
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(1));
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        return driver;
    }

    /** Явно закрыть окно агента (выход из агента, пункт меню в трее). */
    public void closeBrowser() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignore) { /* уже закрыт */ }
            driver = null;
        }
    }

    /** Тот же цикл без внешнего лимита времени/колбэка шага — для ручных demo-запусков. */
    public ResultData.AgentReport run(String goal, int maxSteps) {
        return run(goal, maxSteps, Instant.now().plus(Duration.ofMinutes(3)), step -> { });
    }

    /** Без живого показа шагов — старое поведение, теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }

    /** Без отмены — прежняя пятипараметровая перегрузка, чтобы старые вызовы не ломались. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                      IntConsumer onStep, StepReporter stepReporter) {
        return run(goal, maxSteps, deadline, onStep, stepReporter, CancelSignal.NONE);
    }

    /**
     * Крутит цикл до maxSteps шагов (но не дольше deadline). Окно браузера
     * ПОСЛЕ ЗАВЕРШЕНИЯ ОСТАЁТСЯ ОТКРЫТЫМ — чтобы пользователь увидел результат
     * (например, играющее видео); следующая задача переиспользует это же окно.
     * @param onStep       колбэк перед каждым шагом (номер шага, начиная с 1) — используется,
     *                     например, чтобы показать в трее "выполняю: ... (шаг N)"
     * @param stepReporter колбэк ПОСЛЕ каждого шага — с картинкой и описанием действия,
     *                     для живого показа на телефоне
     * @param cancelSignal проверяется перед каждым шагом — отмена задачи с телефона
     * @return отчёт: достигнута ли цель, сколько шагов, полный трейс.
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                      IntConsumer onStep, StepReporter stepReporter,
                                      CancelSignal cancelSignal) {
        return run(goal, maxSteps, deadline, onStep, stepReporter, cancelSignal, Clarifications.none());
    }

    /** Сколько ждать недокачанные файлы после конца задачи. */
    private static final Duration DOWNLOAD_WAIT = Duration.ofSeconds(60);

    /**
     * То же, но модель может спросить человека (см. {@link Clarifications}).
     *
     * <p>Вокруг цикла — наблюдение за «Загрузками» (DownloadWatcher): если за
     * время задачи что-то скачалось, в трейс добавляется точный путь к файлу.
     * Его получит следующая подзадача плана — например, та, что отправляет
     * этот файл в мессенджере.
     *
     * @param questions вопросы человеку в этой задаче; ответы попадают в цель на всех следующих шагах
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter,
                                       CancelSignal cancelSignal, Clarifications questions) {
        com.jarvis.agent.DownloadWatcher downloads = com.jarvis.agent.DownloadWatcher.start();
        ResultData.AgentReport report = runLoop(goal, maxSteps, deadline, onStep, stepReporter, cancelSignal, questions);
        List<String> facts = downloads.finish(DOWNLOAD_WAIT, cancelSignal::isCancelled);
        if (facts.isEmpty()) return report;
        List<String> trace = new ArrayList<>(report.trace());
        trace.addAll(facts);
        return new ResultData.AgentReport(report.completed(), report.stepsTaken(), trace);
    }

    private ResultData.AgentReport runLoop(String goal, int maxSteps, Instant deadline,
                                           IntConsumer onStep, StepReporter stepReporter,
                                           CancelSignal cancelSignal, Clarifications questions) {
        int steps = Math.max(1, Math.min(maxSteps, 20)); // подстраховка от кривых значений
        List<String> trace = new ArrayList<>();
        boolean completed = false;

        if (SensitiveInputGuard.mentionsSensitive(goal)) {
            trace.add("стоп: цель похожа на работу с паролем или платёжными данными — отказываюсь для безопасности");
            return new ResultData.AgentReport(false, 0, trace); // до шагов дело не дошло
        }

        // Память агента: если такую задачу уже решали, сначала пробуем повторить
        // сохранённый путь вообще без обращений к модели. Не получилось —
        // молча уходим на обычный цикл ниже, как будто памяти и не было.
        Optional<TrajectoryCache.Match> remembered = TrajectoryCache.find(goal);
        if (remembered.isPresent()) {
            Optional<ResultData.AgentReport> replayed = replay(remembered.get(), stepReporter, cancelSignal, deadline);
            if (replayed.isPresent()) return replayed.get();
        }

        // Пишем траекторию по ходу дела: сохраним её в конце, только если
        // модель сама скажет «цель достигнута».
        List<TrajectoryCache.Step> recorded = new ArrayList<>();
        int llmCalls = 0;
        // Память о недоступных сайтах и о чтении — своя у каждой задачи.
        deadHosts.clear();
        readInThisRun = false;
        // Вопросы — по ЭТОЙ задаче, а не по всему плану: «Да» на весь план не
        // должно запрещать запомнить подзадачу «скачай архив».
        int askedAtStart = questions.asked();
        // Сколько шагов реально выполнено. Раньше в отчёт шёл trace.size(), но на
        // один шаг приходится несколько строк трейса (действие плюс пометки), и
        // число шагов получалось завышенным — для таблиц в дипломе это важно.
        int stepsDone = 0;
        // Копим, сколько всего простояли в ожидании страницы — это та величина,
        // ради которой settle() перестал быть безусловным sleep(1500).
        long waitedMs = 0;
        long startedAtMs = System.currentTimeMillis();
        String start = startUrlFor(goal);

        try {
            WebDriver driver = browser();

            try {
                navigate(driver, start);
                trace.add("открыт " + start);
            } catch (IllegalStateException e) {
                // Стартовый сайт недоступен — это не повод бросать задачу:
                // модель узнает об этом из трейса и пойдёт другим путём.
                trace.add("  промах: " + e.getMessage());
            }
            waitedMs += settle(driver);

            for (int step = 1; step <= steps; step++) {
                if (cancelSignal.isCancelled()) {
                    trace.add("стоп: задачу отменил пользователь");
                    break;
                }
                // Время, пока человек думал над вопросом, в лимит задачи не идёт.
                if (Instant.now().isAfter(questions.extend(deadline))) {
                    trace.add("стоп: превышен общий лимит времени задачи");
                    break;
                }
                onStep.accept(step);
                stepsDone++;

                long t0 = System.currentTimeMillis();
                List<PageElement> elements = snapshot(driver);
                // Цель вместе с ответами человека на прошлые вопросы.
                AgentAction action = brain.decideNext(questions.goalFor(goal), elements, trace);
                llmCalls++;
                long dt = System.currentTimeMillis() - t0;
                trace.add("шаг " + step + " (" + elements.size() + " элементов, " + dt + "мс): " + action);
                PageElement target = findElement(elements, action.index());
                String what = "navigate".equalsIgnoreCase(action.action()) ? action.url() : action.text();
                FaustOverlayController.onThought(
                        ActionPhrases.describe(action.action(), what, target == null ? null : target.text()),
                        action.reason());
                // Обоснование модели — второй строкой, телефон покажет его под действием.
                String why = action.reason() == null || action.reason().isBlank() || action.isDone()
                        ? "" : "\n" + action.reason();
                reportStep(stepReporter, driver, step, "шаг " + step + ": " + action + why, target);

                if (action.isDone()) {
                    // Не всякое done — успех: «отказываюсь», «недостижимо» — нет (DoneVerdict).
                    completed = action.succeeded();
                    trace.add("  стоп: " + action.reason());
                    break;
                }
                if (action.isAsk()) {
                    // На странице ничего не делаем — ждём ответа человека.
                    // В траекторию вопрос не пишется (stepFrom вернёт null).
                    trace.add("  " + questions.ask(action.text(), action.options(), cancelSignal));
                    continue;
                }
                try {
                    showGhost(driver, action, target);
                    String note = apply(driver, action);
                    if (note != null) trace.add("  " + note);
                    // Шаг записываем ТОЛЬКО после успешного выполнения: промахи
                    // повторять незачем.
                    TrajectoryCache.Step recordedStep = stepFrom(action, elements, target, goal);
                    if (recordedStep != null) recorded.add(recordedStep);
                    waitedMs += settle(driver);
                } catch (SecurityException e) {
                    // защита от ввода пароля/платёжных данных — останавливаемся, а не пропускаем шаг
                    trace.add("  стоп: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    // промах (элемент пропал, индекс не тот, страница уехала) —
                    // не падаем, просто фиксируем и идём на новый снимок
                    trace.add("  промах: " + e.getClass().getSimpleName()
                              + (e.getMessage() == null ? "" : " — " + firstLine(e.getMessage())));
                }
            }
        } catch (Exception e) {
            trace.add("сбой агента: " + e.getMessage());
        }
        // Задачу, в которой человека о чём-то спрашивали, в память НЕ пишем.
        // Повтор идёт без модели и без вопросов — значит, он молча повторил бы
        // и то, что в первый раз делалось только после «Да» (например, отправку
        // сообщения). Да и ответ мог относиться только к тому разу: «отправь
        // report.pdf», а в следующий раз нужен уже другой файл.
        // По той же логике не пишем и задачи, где агент ЧИТАЛ страницу: смысл
        // такой задачи — прочитанное значение (курс, цена), а повтор из памяти
        // прошёл бы те же клики, но значение уже никто бы не прочитал.
        if (completed && questions.asked() == askedAtStart && !readInThisRun) {
            TrajectoryCache.record(goal, start, recorded, System.currentTimeMillis() - startedAtMs, llmCalls);
        }
        trace.add(TrajectoryCache.LLM_CALLS_MARK + llmCalls
                  + ", ожидание страницы: " + (waitedMs / 1000.0) + " с");
        // driver.quit() здесь намеренно НЕТ: окно остаётся открытым, чтобы
        // пользователь увидел результат. Его переиспользует следующая задача.
        return new ResultData.AgentReport(completed, stepsDone, trace);
    }

    // --- повтор сохранённой траектории --------------------------------

    /**
     * Проигрывает сохранённый путь без единого обращения к модели.
     *
     * @return отчёт, если повтор дошёл до конца (или был прерван пользователем);
     *         {@link Optional#empty()}, если память устарела — тогда вызывающий
     *         код обязан отработать задачу обычным циклом.
     */
    private Optional<ResultData.AgentReport> replay(TrajectoryCache.Match match, StepReporter reporter,
                                                    CancelSignal cancelSignal, Instant deadline) {
        TrajectoryCache.Trajectory saved = match.trajectory();
        List<String> trace = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        long waitedMs = 0;

        try {
            WebDriver driver = browser();
            driver.get(saved.startUrl());
            trace.add("эту задачу уже решали — повторяю сохранённый путь, открыт " + saved.startUrl());
            FaustOverlayController.onMemory(true);
            FaustOverlayController.onThought("Повторяю выученный путь", "эту задачу уже решали — без модели");
            waitedMs += settle(driver);

            int step = 0;
            for (TrajectoryCache.Step s : saved.steps()) {
                if (cancelSignal.isCancelled() || Instant.now().isAfter(deadline)) {
                    trace.add("стоп: повтор прерван");
                    return Optional.of(new ResultData.AgentReport(false, step, trace));
                }
                step++;

                List<PageElement> elements = snapshot(driver);
                AgentAction action = resolve(s, elements, match.slot());
                if (action == null) {
                    // Страница изменилась — сохранённый путь больше не ведёт к цели.
                    trace.add("шаг " + step + ": элемент «" + s.label() + "» не найден, память устарела");
                    TrajectoryCache.forget(saved);
                    return Optional.empty();
                }

                PageElement target = findElement(elements, action.index());
                String line = "шаг " + step + " (из памяти): " + action;
                trace.add(line);
                reportStep(reporter, driver, step, line, target);

                try {
                    showGhost(driver, action, target);
                    apply(driver, action);
                } catch (SecurityException e) {
                    // Тот же рубеж, что и в обычном цикле: память не даёт обойти
                    // SensitiveInputGuard, потому что повтор идёт через тот же apply().
                    trace.add("  стоп: " + e.getMessage());
                    return Optional.of(new ResultData.AgentReport(false, step, trace));
                } catch (Exception e) {
                    trace.add("  промах на повторе: " + e.getClass().getSimpleName());
                    TrajectoryCache.forget(saved);
                    return Optional.empty();
                }
                waitedMs += settle(driver);
            }

            long dt = System.currentTimeMillis() - t0;
            TrajectoryCache.noteReplay(saved);
            trace.add(TrajectoryCache.REPLAY_MARK + " за " + dt + " мс, " + TrajectoryCache.LLM_CALLS_MARK
                      + "0, из них ожидание страницы: " + (waitedMs / 1000.0) + " с"
                      + " (в первый раз: " + saved.baselineMs() + " мс и "
                      + saved.baselineLlmCalls() + " обращен.)");
            return Optional.of(new ResultData.AgentReport(true, step, trace));

        } catch (Exception e) {
            // Что-то не так с самим браузером — это не вина памяти, отдаём
            // задачу обычному циклу, он поднимет окно заново.
            System.err.println("повтор траектории не удался: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Шаг траектории -> конкретное действие в ТЕКУЩЕМ снимке. null — элемент не найден. */
    private AgentAction resolve(TrajectoryCache.Step s, List<PageElement> elements, String slot) {
        return switch (s.action() == null ? "" : s.action()) {
            case "click" -> {
                PageElement e = matchElement(elements, s);
                yield e == null ? null : new AgentAction("click", e.index(), null, null, null, "из памяти");
            }
            // Слот — переменная часть цели: «…видео про котов…» -> «…видео про собак…».
            case "type" -> new AgentAction("type", null,
                    (s.textFromGoal() && slot != null) ? slot : s.text(), null, null, "из памяти");
            case "scroll" -> new AgentAction("scroll", null, null, s.amount(), null, "из памяти");
            case "navigate" -> new AgentAction("navigate", null, null, null, s.url(), "из памяти");
            default -> null;
        };
    }

    /**
     * Ищет в свежем снимке элемент, по которому кликали в прошлый раз:
     * сначала по подписи, затем — по позиции среди однотипных.
     *
     * Второй способ нужен именно для параметризованных задач: при другом
     * поисковом запросе заголовки результатов другие, а «первая ссылка в
     * списке» — та же самая.
     */
    private PageElement matchElement(List<PageElement> elements, TrajectoryCache.Step s) {
        List<PageElement> sameKind = elements.stream()
                .filter(e -> same(e.tag(), s.tag()) && same(e.role(), s.role()))
                .toList();

        String label = TrajectoryCache.norm(s.label());
        if (!label.isBlank()) {
            for (PageElement e : sameKind) {
                if (TrajectoryCache.norm(e.text()).equals(label)) return e;
            }
        }
        return s.ordinal() >= 0 && s.ordinal() < sameKind.size() ? sameKind.get(s.ordinal()) : null;
    }

    /** Выполненное действие -> шаг траектории. null для действий, которые нечего повторять. */
    private TrajectoryCache.Step stepFrom(AgentAction a, List<PageElement> elements, PageElement target, String goal) {
        String kind = a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "click" -> target == null ? null : new TrajectoryCache.Step(
                    "click", target.tag(), target.role(), target.text(), ordinalOf(elements, target),
                    null, null, null, false);
            case "type" -> new TrajectoryCache.Step(
                    "type", null, null, null, -1, a.text(), null, null, fromGoal(a.text(), goal));
            case "scroll" -> new TrajectoryCache.Step(
                    "scroll", null, null, null, -1, null, a.amount() == null ? 600 : a.amount(), null, false);
            case "navigate" -> new TrajectoryCache.Step(
                    "navigate", null, null, null, -1, null, null, a.url(), false);
            default -> null;
        };
    }

    /** Введённый текст дословно есть в цели -> это переменная часть задачи, а не константа. */
    private boolean fromGoal(String typed, String goal) {
        String t = TrajectoryCache.norm(typed);
        return !t.isBlank() && TrajectoryCache.norm(goal).contains(t);
    }

    /** Позиция элемента среди элементов с тем же тегом и ролью в этом же снимке. */
    private int ordinalOf(List<PageElement> elements, PageElement target) {
        int n = 0;
        for (PageElement e : elements) {
            if (e.index() == target.index()) return n;
            if (same(e.tag(), target.tag()) && same(e.role(), target.role())) n++;
        }
        return -1;
    }

    private boolean same(String a, String b) {
        return Objects.equals(a == null ? "" : a, b == null ? "" : b);
    }

    // --- снимок страницы ----------------------------------------------

    private List<PageElement> snapshot(WebDriver driver) {
        try {
            String json = (String) ((JavascriptExecutor) driver).executeScript(COLLECT_JS);
            List<PageElement> list = new ArrayList<>();
            for (JsonNode n : mapper.readTree(json)) {
                list.add(new PageElement(
                        n.get("idx").asInt(),
                        n.get("tag").asText(),
                        n.get("role").asText(""),
                        n.get("text").asText(""),
                        n.get("x").asInt(),
                        n.get("y").asInt()));
            }
            return list;
        } catch (Exception e) {
            return List.of(); // пустой снимок — модель получит подсказку «страница грузится»
        }
    }

    /**
     * Скриншот окна браузера ДЛЯ ПОКАЗА на телефоне — не для модели (той хватает
     * списка DOM-элементов, картинка ей не нужна и только замедлила бы запрос).
     * Ошибку скриншота не пробрасываем — шаг без картинки лучше, чем упавшая задача.
     */
    private void reportStep(StepReporter reporter, WebDriver driver, int step, String description, PageElement target) {
        if (!reporter.needsScreenshot()) {
            // Идёт живой стрим экрана — телефон и так видит браузер. Снимок через
            // Selenium стоит заметно дороже обычного (сотни миллисекунд на шаг),
            // так что пропускаем его целиком.
            //
            // Координаты цели не шлём: здесь они в CSS-пикселях вьюпорта, а
            // кадр стрима — это весь экран. Перевод одного в другое зависит от
            // положения окна браузера (BrowserAgent.elementOnScreen), и он пока
            // не проверен на живом Chrome — лучше без метки, чем метка не там.
            reporter.onStep(step, description, null, 0, 0, null, null);
            return;
        }
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            BufferedImage scaled = ImageUtil.scaleDown(img, 800);
            byte[] jpeg = ImageUtil.toJpeg(scaled, 0.5f);

            // target.x()/y() — CSS-пиксели вьюпорта (getBoundingClientRect), а скриншот
            // снят в физических пикселях и затем уменьшен. Масштаб считаем от ширины
            // вьюпорта в CSS-пикселях — так учитывается и DPI-масштабирование Windows
            // (125%/150%), и уменьшение до 800px. Если innerWidth узнать не вышло —
            // приближение «1 CSS-пиксель ≈ 1 пиксель скриншота».
            Integer tx = null, ty = null;
            if (target != null && img.getWidth() > 0) {
                double cssWidth = viewportCssWidth(driver, img.getWidth());
                double scale = scaled.getWidth() / cssWidth;
                tx = (int) Math.round(target.x() * scale);
                ty = (int) Math.round(target.y() * scale);
                if (tx < 0 || ty < 0 || tx >= scaled.getWidth() || ty >= scaled.getHeight()) {
                    tx = null; // центр элемента за краем кадра — лучше без маркера, чем маркер не там
                    ty = null;
                }
            }
            reporter.onStep(step, description, jpeg, scaled.getWidth(), scaled.getHeight(), tx, ty);
        } catch (Exception e) {
            reporter.onStep(step, description, null, 0, 0, null, null);
        }
    }

    /** window.innerWidth в CSS-пикселях; fallback — ширина скриншота (DPI 100%). */
    private double viewportCssWidth(WebDriver driver, int fallback) {
        try {
            Object w = ((JavascriptExecutor) driver).executeScript("return window.innerWidth;");
            if (w instanceof Number n && n.doubleValue() > 0) return n.doubleValue();
        } catch (Exception ignore) { /* нет JS — берём приближение */ }
        return fallback;
    }

    // --- курсор-призрак -----------------------------------------------

    /** Положение окна Chrome и его «окна просмотра» (где рисуется сама страница). */
    private static final String VIEWPORT_JS =
            "return [window.screenX, window.screenY, window.outerWidth, window.outerHeight,"
            + " window.innerWidth, window.innerHeight, window.devicePixelRatio];";

    /**
     * Показать курсор-призрак у элемента, по которому агент сейчас кликнет
     * (или в который начнёт печатать), и дождаться, пока он долетит (~350 мс).
     *
     * Зачем: Selenium кликает внутри Chrome, настоящая мышь не двигается — без
     * призрака на экране не видно, куда нажал ИИ. Призрак только рисуется:
     * клик по-прежнему делает Selenium, настоящая мышь остаётся у пользователя.
     */
    private void showGhost(WebDriver driver, AgentAction action, PageElement target) {
        // ghostWanted — дешёвая проверка «идёт ИИ-задача и ореол на экране»;
        // без неё мы бы зря ходили в браузер за координатами (демо-запуски и т.п.).
        if (target == null || !FaustOverlayController.ghostWanted()) return;
        String kind = action.action() == null ? "" : action.action().toLowerCase(Locale.ROOT);
        if (!kind.equals("click") && !kind.equals("type")) return;
        java.awt.Point p = elementOnScreen(driver, target);
        if (p == null) return;
        if (kind.equals("click")) FaustOverlayController.ghostClick(p.x, p.y);
        else FaustOverlayController.ghostMove(p.x, p.y);   // печать: подлететь к полю, без всплеска
    }

    /**
     * Где центр элемента на ЭКРАНЕ.
     *
     * Модель знает центр элемента в CSS-пикселях ОКНА ПРОСМОТРА — области, где
     * рисуется страница. До неё от угла экрана ещё: положение окна Chrome
     * (screenX/Y), рамка окна и всё, что над страницей, — вкладки, адресная
     * строка, панель закладок.
     * <pre>
     *   рамка сбоку = (outerWidth − innerWidth) / 2
     *   всё сверху  = outerHeight − innerHeight − рамка   (нижняя рамка = боковой)
     *   экран.x = screenX + рамка сбоку + x
     *   экран.y = screenY + всё сверху  + y
     * </pre>
     * При масштабе Windows 125% и Chrome, и Java считают в одних «логических»
     * пикселях, поэтому единицы сходятся без пересчёта.
     *
     * @return точка на экране или null, если посчитать не удалось или она
     *         оказалась за пределами экрана
     */
    private java.awt.Point elementOnScreen(WebDriver driver, PageElement e) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> m = (List<Number>) ((JavascriptExecutor) driver).executeScript(VIEWPORT_JS);
            double screenX = m.get(0).doubleValue(), screenY = m.get(1).doubleValue();
            double outerW = m.get(2).doubleValue(), outerH = m.get(3).doubleValue();
            double innerW = m.get(4).doubleValue(), innerH = m.get(5).doubleValue();
            double dpr = m.get(6).doubleValue();

            // devicePixelRatio = масштаб Windows × масштаб страницы (Ctrl+колесо).
            // Если страницу увеличили, Chrome отдаёт размеры окна в разных
            // единицах, и точка вышла бы неточной. Призрак мимо кнопки хуже, чем
            // никакого, — в этом случае просто не показываем. У агента свой чистый
            // профиль Chrome, масштаб там 100%.
            double osScale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
            if (Math.abs(dpr / osScale - 1) > 0.02) return null;

            double border = (outerW - innerW) / 2;
            double top = outerH - innerH - border;
            int x = (int) Math.round(screenX + border + e.x());
            int y = (int) Math.round(screenY + top + e.y());

            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            return screen.contains(x, y) ? new java.awt.Point(x, y) : null;
        } catch (Exception ex) {
            return null;   // призрак — украшение: не смогли посчитать — просто без него
        }
    }

    // --- выполнение действия ----------------------------------------

    /**
     * Текст страницы для действия read. Ищет строки с искомыми словами (и их
     * соседей — число курса часто стоит строкой ниже подписи); без слов —
     * начало основного содержимого. Возвращает не больше READ_MAX_CHARS.
     */
    private static final String READ_JS = """
        const q = (arguments[0] || '').toLowerCase().replace(/ё/g, 'е')
                    .split(/[^a-zа-я0-9]+/).filter(w => w.length > 2);
        const root = document.querySelector('main') || document.body;
        const lines = (root.innerText || '').split('\\n').map(s => s.replace(/\\s+/g, ' ').trim()).filter(Boolean);
        let out = [];
        if (q.length) {
          const hit = new Set();
          lines.forEach((l, i) => {
            const n = l.toLowerCase().replace(/ё/g, 'е');
            if (q.some(w => n.includes(w))) { hit.add(i); hit.add(i + 1); }
          });
          out = [...hit].filter(i => i < lines.length).sort((a, b) => a - b).map(i => lines[i]);
        }
        if (!out.length) out = lines;
        return out.join(' | ').slice(0, arguments[1]);
        """;
    private static final int READ_MAX_CHARS = 1500;

    /**
     * Сайты, которые в этой задаче не открылись по таймауту. Повторный переход
     * на них сразу получает отказ, а не ещё 30 секунд ожидания: на прогоне
     * 30.09 агент пять раз подряд ждал недоступный с этого ПК cbr.ru — две с
     * половиной минуты впустую. Сбрасывается в начале каждой задачи.
     */
    private final java.util.Set<String> deadHosts = new java.util.HashSet<>();
    /** Читали ли страницу в этой задаче — такую задачу нельзя сохранять в память траекторий. */
    private boolean readInThisRun;

    /** @return пометка для трейса (для read — сам прочитанный текст) или null */
    private String apply(WebDriver driver, AgentAction a) {
        switch (a.action() == null ? "" : a.action().toLowerCase()) {
            case "read" -> {
                readInThisRun = true;
                String text = (String) ((JavascriptExecutor) driver).executeScript(READ_JS,
                        a.text() == null ? "" : a.text(), READ_MAX_CHARS);
                // Текст страницы — это ДАННЫЕ (промпт предупреждает об этом отдельно).
                return "текст страницы" + (a.text() == null || a.text().isBlank() ? "" : " по «" + a.text() + "»")
                       + ": " + (text == null || text.isBlank() ? "(пусто — страница ещё грузится?)" : text);
            }
            case "click" -> locate(driver, a.index()).click();
            case "type" -> {
                // По контракту "type" не несёт index — пишем в поле, по которому
                // только что кликнули (активный элемент). Если модель всё же
                // указала index — используем его, так надёжнее.
                WebElement field = (a.index() != null)
                        ? locate(driver, a.index())
                        : driver.switchTo().activeElement();
                String text = a.text() == null ? "" : a.text();
                if (SensitiveInputGuard.mentionsSensitive(text) || SensitiveInputGuard.mentionsSensitive(fieldHint(field))) {
                    throw new SecurityException("похоже на пароль/платёжные данные — отказываюсь вводить");
                }
                try { field.clear(); } catch (Exception ignore) { /* не у всех полей есть clear */ }
                field.sendKeys(text);
                field.sendKeys(Keys.RETURN); // поиск обычно подтверждается Enter
            }
            case "scroll" -> {
                int dy = a.amount() == null ? 600 : a.amount(); // по контракту amount нет -> дефолт
                ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, arguments[0]);", dy);
            }
            case "navigate" -> navigate(driver, safeUrl(a.url()));
            default -> throw new IllegalArgumentException("неизвестное действие: " + a.action());
        }
        return null;
    }

    /**
     * Переход по адресу с понятной реакцией на недоступный сайт. Раньше модель
     * видела «промах: TimeoutException» и считала это случайностью — и шла на
     * тот же сайт снова. Теперь она получает прямой совет взять другой источник,
     * а повторный заход на тот же сайт отклоняется сразу.
     */
    private void navigate(WebDriver driver, String url) {
        String host = hostOf(url);
        if (deadHosts.contains(host)) {
            throw new IllegalStateException("сайт " + host + " уже не открылся в этой задаче — не пробуй его снова, "
                                            + "найди то же самое через поисковик или на другом сайте");
        }
        try {
            driver.get(url);
        } catch (org.openqa.selenium.TimeoutException e) {
            deadHosts.add(host);
            // Страница может продолжать грузиться в фоне — останавливаем, чтобы
            // следующий снимок был снят со спокойной страницы.
            try { ((JavascriptExecutor) driver).executeScript("window.stop();"); } catch (Exception ignore) { }
            throw new IllegalStateException("сайт " + host + " не открылся за 30 с — похоже, он недоступен "
                                            + "с этого компьютера; не открывай его снова, возьми другой источник");
        }
    }

    private static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return url;
        }
    }

    /** Элемент, на который указало действие модели (click/type с index) — для маркера на телефоне. null, если действие без index. */
    private PageElement findElement(List<PageElement> elements, Integer index) {
        if (index == null) return null;
        return elements.stream().filter(e -> e.index() == index).findFirst().orElse(null);
    }

    /** Находит элемент по метке, которую проставил COLLECT_JS. */
    private WebElement locate(WebDriver driver, Integer idx) {
        if (idx == null) throw new NoSuchElementException("модель не указала index");
        return driver.findElement(By.cssSelector("[data-jarvis-idx='" + idx + "']"));
    }

    /**
     * Пускаем модель только на http/https. Без этой проверки внушённый со
     * страницы адрес вида file:///C:/Users/... или javascript:... заставил бы
     * браузер агента читать локальные файлы или выполнять чужой скрипт.
     */
    private String safeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("модель не указала url для navigate");
        }
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new SecurityException("navigate разрешён только на http/https, а не на " + u);
        }
        return u;
    }

    /** Type/name/id/placeholder/aria-label поля одной строкой — для проверки на "похоже на пароль/карту". */
    private String fieldHint(WebElement field) {
        try {
            return String.join(" ",
                    safeAttr(field, "type"), safeAttr(field, "name"), safeAttr(field, "id"),
                    safeAttr(field, "placeholder"), safeAttr(field, "aria-label"));
        } catch (Exception e) {
            return ""; // элемент уже мог устареть (stale) — не блокируем из-за этого
        }
    }

    private String safeAttr(WebElement el, String name) {
        String v = el.getAttribute(name);
        return v == null ? "" : v;
    }

    // --- мелочи ----------------------------------------------------

    /** Первый сценарий: если цель про YouTube — стартуем оттуда, иначе с Google. */
    private String startUrlFor(String goal) {
        String g = goal == null ? "" : goal.toLowerCase();
        if (g.contains("youtube") || g.contains("ютуб") || g.contains("ютьюб")) {
            return "https://www.youtube.com/";
        }
        return "https://www.google.com/";
    }

    /** Пауза, чтобы SPA успела перерисоваться после действия. Позже заменим на явные ожидания. */
    /**
     * Ждёт, пока страница успокоится после действия, и возвращает, сколько
     * миллисекунд на это ушло.
     *
     * Раньше здесь стоял безусловный {@code Thread.sleep(1500)}. Он платил
     * полторы секунды даже там, где страница была готова через двести
     * миллисекунд — а на задаче в девять шагов это тринадцать секунд чистого
     * сна. Особенно обидно стало после появления {@link TrajectoryCache}:
     * на повторе обращений к модели нет вообще, и сон оказывался почти всем
     * временем выполнения.
     *
     * <p><b>Почему не только {@code readyState}.</b> В одностраничных
     * приложениях (YouTube — ровно такое) клик не перезагружает документ:
     * {@code readyState} остаётся {@code complete} всё время, и сам по себе
     * он не говорит ни о чём. Поэтому смотрим ещё и на КОЛИЧЕСТВО
     * интерактивных элементов: пока оно меняется, страница ещё
     * перерисовывается. Два одинаковых замера подряд — считаем, что успокоилась.
     *
     * <p>Потолок остался прежним (1.5 с), поэтому медленнее, чем раньше, не
     * станет ни в каком случае: в худшем варианте это тот же sleep(1500).
     */
    private long settle(WebDriver driver) {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + MAX_SETTLE_MS;

        // Небольшая обязательная пауза: сразу после клика браузер ещё не начал
        // перерисовку, и первый же замер показал бы «стабильно» по инерции.
        if (!pause(MIN_SETTLE_MS)) return System.currentTimeMillis() - startedAt;

        String previous = domSignature(driver);
        // Условие проверяет, успеет ли ЦЕЛИКОМ уложиться ещё один опрос:
        // иначе последний заход стабильно перебирал бы потолок на сотню
        // миллисекунд, и в худшем случае вышло бы дольше прежнего sleep(1500).
        while (System.currentTimeMillis() + POLL_SETTLE_MS < deadline) {
            if (!pause(POLL_SETTLE_MS)) break;
            String current = domSignature(driver);
            if (current != null && current.equals(previous) && current.startsWith("complete")) {
                break;
            }
            previous = current;
        }
        return System.currentTimeMillis() - startedAt;
    }

    /**
     * Дешёвый «отпечаток» состояния страницы: готовность документа плюс число
     * интерактивных элементов (тот же селектор, что у COLLECT_JS — значит
     * считаем ровно то, что потом увидит модель).
     *
     * null — снять не удалось (страница как раз перезагружается). Для
     * вызывающего это просто «ещё не стабильно»: сравнение с предыдущим
     * значением не сойдётся, и мы подождём ещё один интервал.
     */
    private String domSignature(WebDriver driver) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(SIGNATURE_JS);
            return result == null ? null : result.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** @return false, если поток прервали — тогда ждать дальше не надо. */
    private boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
