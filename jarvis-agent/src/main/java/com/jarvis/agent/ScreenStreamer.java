package com.jarvis.agent;

import com.jarvis.agent.protocol.ImageFormat;
import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.vision.ImageUtil;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Живой экран: снимает экран по таймеру и САМ шлёт кадры на релей через
 * callback, не дожидаясь новых команд. Этим он отличается от обычного
 * screenshot в {@link CommandExecutor}, который возвращает один кадр как
 * результат команды.
 *
 * Каждый кадр — это ScreenFrame с seq (0, 1, 2, ...), по которому телефон
 * отличает кадр стрима от одиночного скриншота (у того seq = null), и с
 * requestId той команды StartScreenStream, что открыла сессию.
 */
public class ScreenStreamer {

    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 5;
    private static final int MIN_WIDTH = 160;
    private static final int MAX_WIDTH = 960;
    /** Качество JPEG — то же, что у обычного скриншота. */
    private static final float JPEG_QUALITY = 0.5f;
    /** Автостоп: непрерывный стрим сажает батарею телефона и жрёт трафик. */
    private static final Duration MAX_DURATION = Duration.ofMinutes(10);
    /** Как часто кадр уходит, даже если картинка не менялась (см. worthSending). */
    private static final long KEYFRAME_INTERVAL_MS = 3000;

    /** Что показываем в «агент занят», пока идёт стрим. */
    static final String BUSY_LABEL = "показ экрана";

    private final Robot robot;
    private final Consumer<JarvisMessage> sender;
    /** Общий с agent_task флаг занятости: стрим и задача не могут идти одновременно. */
    private final AtomicReference<String> busyGoal;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "screen-streamer");
        t.setDaemon(true); // не держим JVM при выходе из агента
        return t;
    });

    /** Текущая сессия стрима; null — стрим не идёт. */
    private volatile Session session;
    /**
     * Человек за ПК нажал «Остановить» на плашке: управление с телефона
     * прекращено. Тапы и ввод с телефона отклоняются, пока телефон заново не
     * откроет «Экран ПК» (тогда start() сбросит флаг).
     */
    private volatile boolean pausedByUser;

    public ScreenStreamer(Robot robot, Consumer<JarvisMessage> sender, AtomicReference<String> busyGoal) {
        this.robot = robot;
        this.sender = sender;
        this.busyGoal = busyGoal;
    }

    public boolean isRunning() {
        return session != null;
    }

    /**
     * Во сколько раз кадр стрима меньше настоящего экрана, или 0, если стрима
     * нет. Нужно, чтобы перевести координаты цели агента (они в пикселях
     * экрана) в координаты картинки, которую видит телефон.
     */
    public double frameScale() {
        Session s = session;
        if (s == null || s.lastFrameWidth <= 0) return 0;
        return (double) s.lastFrameWidth / Toolkit.getDefaultToolkit().getScreenSize().width;
    }

    /**
     * Запускает стрим. Если стрим уже идёт — старая сессия останавливается, и
     * начинается новая (одновременно живёт только одна). Возвращает false,
     * если агент занят задачей agent_task — тогда ничего не меняется.
     */
    public synchronized boolean start(String requestId, int fps, int maxWidth) {
        // Идёт ли сейчас ИИ-задача (флаг занят не нами).
        String busy = busyGoal.get();
        boolean taskRunning = busy != null && !BUSY_LABEL.equals(busy);

        // Во время задачи стрим ей не конкурент, а зритель: человек нажал на
        // телефоне «живой экран», чтобы видеть, что агент делает. Флаг занятости
        // при этом не трогаем — он принадлежит задаче.
        if (!taskRunning && session == null && !busyGoal.compareAndSet(null, BUSY_LABEL)) {
            return false;
        }

        // Оверлеи переключаем в режим «управление с телефона» только когда стрим
        // сам по себе: во время задачи на экране уже идут её анимации, и
        // перебивать их нельзя.
        boolean ownsOverlays = !taskRunning;
        startSession(requestId, fps, maxWidth, ownsOverlays);
        if (ownsOverlays) {
            // Телефон управляет компьютером — рамка и ореол у курсора, чтобы человек
            // за ПК видел это. «Остановить» на плашке прекращает управление.
            FaustOverlayController.onRemoteControlStart(this::stopByUser);
        }
        return true;
    }

    private boolean startSession(String requestId, int fps, int maxWidth, boolean ownsOverlays) {
        cancelSession();

        int safeFps = clamp(fps, MIN_FPS, MAX_FPS);
        int safeWidth = clamp(maxWidth, MIN_WIDTH, MAX_WIDTH);
        long periodMs = 1000L / safeFps;

        Session started = new Session(requestId, safeWidth);
        started.ownsOverlays = ownsOverlays;
        started.frames = scheduler.scheduleAtFixedRate(
                () -> capture(started), 0, periodMs, TimeUnit.MILLISECONDS);
        // Останавливаем именно СВОЮ сессию: если к моменту срабатывания стрим
        // успели перезапустить, старый таймер не должен гасить новую сессию.
        started.autoStop = scheduler.schedule(
                () -> stopIfCurrent(started), MAX_DURATION.toMillis(), TimeUnit.MILLISECONDS);
        session = started;
        pausedByUser = false;
        System.out.println((ownsOverlays ? "Стрим экрана запущен: " : "Живой экран поверх задачи: ")
                           + safeFps + " кадр/с, ширина " + safeWidth);
        return true;
    }

    /**
     * Забрать оверлеи себе, если стрим ещё идёт.
     *
     * Нужно ровно в одном месте — когда кончилась ИИ-задача, поверх которой
     * человек включил живой экран. Анимации задачи убираются вместе с ней, а
     * стрим продолжается: без этого вызова с экрана ПК исчезла бы рамка, то
     * есть единственный признак, что телефон всё ещё видит экран.
     */
    public synchronized void takeOverOverlays() {
        Session s = session;
        if (s == null || s.ownsOverlays) return;
        s.ownsOverlays = true;
        busyGoal.compareAndSet(null, BUSY_LABEL);
        FaustOverlayController.onRemoteControlStart(this::stopByUser);
    }

    /** Останавливает стрим и освобождает агента. Безопасно вызывать, когда стрима нет. */
    public synchronized void stop() {
        if (session == null) return;
        boolean ownedOverlays = session.ownsOverlays;
        cancelSession();
        busyGoal.compareAndSet(BUSY_LABEL, null);
        System.out.println("Стрим экрана остановлен");
        // Сюда приходят все пути остановки: команда с телефона, автостоп через
        // 10 минут и кнопка «Остановить» — анимации убираются в любом случае.
        // Но только свои: если стрим шёл поверх ИИ-задачи, её анимации не наши,
        // и гасить их здесь нельзя — задача ещё идёт.
        if (ownedOverlays) FaustOverlayController.onRemoteControlEnd();
    }

    /** Кнопка «Остановить» на плашке: прекратить стрим и перестать слушаться тапов. */
    private void stopByUser() {
        pausedByUser = true;
        System.out.println("Управление с телефона остановлено кнопкой на ПК");
        stop();
    }

    /** Остановил ли человек за ПК управление с телефона (см. pausedByUser). */
    public boolean isPausedByUser() {
        return pausedByUser;
    }

    private synchronized void stopIfCurrent(Session s) {
        if (session == s) stop();
    }

    private void cancelSession() {
        if (session == null) return;
        session.frames.cancel(false);
        session.autoStop.cancel(false);
        session = null;
    }

    /**
     * Один тик: снимок -> даунскейл до ширины сессии -> JPEG -> base64 -> отправка.
     * Ошибку одного кадра гасим: сеть/экран могут моргнуть, весь стрим из-за
     * этого ронять не стоит (иначе scheduleAtFixedRate молча отменит задачу).
     */
    private void capture(Session s) {
        if (session != s) return; // сессию успели сменить между тиками
        try {
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            // Стрим и agent_task не идут одновременно (общий busyGoal), но оверлеи
            // ещё доигрывают исчезновение после задачи — в первые кадры стрима
            // они попадать не должны.
            BufferedImage raw = AgentOverlays.capture(() -> robot.createScreenCapture(screen));
            BufferedImage img = ImageUtil.scaleDown(raw, s.maxWidth);
            byte[] jpeg = ImageUtil.toJpeg(img, JPEG_QUALITY);
            if (!worthSending(s, jpeg)) return;
            String b64 = Base64.getEncoder().encodeToString(jpeg);
            // Снимок и сжатие — около 60 мс (замерено: 40 мс сам снимок, 4 мс
            // масштабирование, 14 мс JPEG), то есть заметная часть периода тика,
            // но далеко не весь он. За это время стрим могли остановить или
            // перезапустить: без этой проверки телефон получил бы лишний кадр
            // уже остановленной сессии.
            if (session != s) return;
            s.lastFrameWidth = img.getWidth();
            // screen.* (а не размер снятого изображения): mouseMove работает в той же
            // логической системе координат, что возвращает Toolkit.getScreenSize(), —
            // при масштабировании дисплея снимок может прийти крупнее в device-пикселях.
            sender.accept(new JarvisMessage.ScreenFrame(
                    s.requestId, ImageFormat.JPEG, b64, img.getWidth(), img.getHeight(),
                    screen.width, screen.height,
                    s.seq.getAndIncrement()));
        } catch (Exception e) {
            System.err.println("кадр стрима не отправлен: " + e.getMessage());
        }
    }

    /**
     * Стоит ли вообще отправлять этот кадр.
     *
     * Когда на экране ничего не перерисовывается, соседние кадры выходят
     * побайтово одинаковыми (JPEG-кодирование детерминировано, а снимок
     * {@link Robot} не включает курсор мыши) — такой кадр телефону слать
     * незачем: он потратит на него трафик и декодирование впустую.
     *
     * <p><b>Насколько это помогает — замерено, и меньше, чем кажется.</b> На
     * живом рабочем столе «ничего не происходит» почти не бывает: мигающий
     * текстовый курсор или часы в трее меняют десятки пикселей из 360 000
     * (0,01–0,06%), и этого уже достаточно, чтобы JPEG отличался. На
     * шестисекундной пробе отсеялось 4 кадра из 18. Реально выигрыш есть там,
     * где экран действительно замер: открытый документ, пауза в видео,
     * рабочий стол без активного поля ввода.
     *
     * <p><b>Почему сравнение побайтовое, а не с допуском.</b> Напрашивается
     * считать кадр неизменившимся, если различается меньше, скажем, 0,1%
     * пикселей — тогда мигающий курсор перестал бы плодить кадры. Но по тем же
     * замерам мигание курсора (порядка сотни пикселей) неотличимо по объёму от
     * НАБРАННОГО СИМВОЛА. А на этом экране пользователь печатает (кнопка
     * «клавиатура»), и «ваш текст появится через три секунды» — цена,
     * несопоставимая с экономией пары кадров.
     *
     * <p>Раз в {@link #KEYFRAME_INTERVAL_MS} кадр уходит в любом случае: иначе
     * зритель, подключившийся к уже идущему стриму на неподвижном экране,
     * не увидел бы вообще ничего.
     *
     * Вызывается только из потока планировщика (он однопоточный), поэтому
     * поля сессии читаются и пишутся без синхронизации.
     */
    private boolean worthSending(Session s, byte[] jpeg) {
        long now = System.currentTimeMillis();
        // Длина + хэш: совпадение обоих у соседних кадров практически означает
        // тот же самый кадр, а случайное совпадение стоит всего лишь одного
        // пропущенного кадра — не ошибки.
        int hash = Arrays.hashCode(jpeg);
        boolean changed = hash != s.lastHash || jpeg.length != s.lastLength;
        if (!changed && now - s.lastSentAtMs < KEYFRAME_INTERVAL_MS) {
            return false;
        }
        s.lastHash = hash;
        s.lastLength = jpeg.length;
        s.lastSentAtMs = now;
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Одна сессия стрима: свой requestId, своя нумерация кадров и свои таймеры. */
    private static final class Session {
        final String requestId;
        final int maxWidth;
        final AtomicInteger seq = new AtomicInteger(0);
        ScheduledFuture<?> frames;
        ScheduledFuture<?> autoStop;
        /**
         * Эта сессия переключила оверлеи в режим «управление с телефона» — значит,
         * ей же их и возвращать. Стрим, открытый поверх идущей ИИ-задачи, оверлеи
         * не трогает: там уже показываются анимации самой задачи.
         */
        boolean ownsOverlays;

        // Последний ОТПРАВЛЕННЫЙ кадр — чтобы не слать его же ещё раз.
        // Длина -1 гарантирует, что самый первый кадр отличается от «ничего».
        int lastHash;
        int lastLength = -1;
        long lastSentAtMs;
        /** Размер последнего отправленного кадра — см. ScreenStreamer.frameScale(). */
        volatile int lastFrameWidth;

        Session(String requestId, int maxWidth) {
            this.requestId = requestId;
            this.maxWidth = maxWidth;
        }
    }
}
