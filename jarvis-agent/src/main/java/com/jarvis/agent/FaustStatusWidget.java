package com.jarvis.agent;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Вариант C — статус-виджет «Фауст» в правом нижнем углу экрана: пока ИИ-агент
 * управляет компьютером, человек за ПК видит, что происходит и на каком шаге
 * задача, и может её остановить кнопкой «Стоп».
 *
 * <h2>Как устроен (коротко)</h2>
 * <ul>
 *   <li>{@link JWindow} — окно без рамки и заголовка. Фон прозрачный, поэтому
 *       видна только нарисованная нами скруглённая карточка.</li>
 *   <li>Весь рисунок — Java2D в {@link WidgetPanel#paintComponent}; логотип
 *       рисует общий {@link FaustLogoPainter}, как и во всех оверлеях.</li>
 *   <li>Анимация — {@link javax.swing.Timer}, который ~60 раз в секунду
 *       пересчитывает состояние и просит панель перерисоваться (repaint).</li>
 *   <li>После показа окно регистрируется в {@link AgentOverlays}: Windows
 *       прячет его от снимков экрана, а кликнуть по нему можно, но фокус оно
 *       не забирает (на нём кнопка «Стоп», поэтому сквозь него клики НЕ проходят).</li>
 * </ul>
 *
 * <h2>Про потоки — это важно</h2>
 * Swing однопоточный: трогать окна и компоненты можно ТОЛЬКО из потока EDT
 * (Event Dispatch Thread). А вызывают нас из потока агента. Поэтому каждый
 * публичный метод не делает работу сам, а «перекладывает» её в EDT через
 * {@link SwingUtilities#invokeLater}. Все поля ниже без volatile читаются и
 * пишутся только в EDT, и синхронизация им не нужна.
 */
public final class FaustStatusWidget {

    // =====================================================================
    //  Размеры (px) — по спецификации
    // =====================================================================

    private static final int W = 372;
    private static final int H = 96;
    /** Отступы от края рабочей области (то есть без панели задач). */
    private static final int MARGIN_RIGHT = 20;
    private static final int MARGIN_BOTTOM = 66;

    private static final int PAD = 14;          // внутренние отступы карточки
    private static final int CARD_RADIUS = 22;

    private static final int TILE = 56;         // плитка с логотипом
    private static final int TILE_RADIUS = 16;
    private static final int LOGO = 48;         // логотип внутри плитки

    private static final int STOP = 44;         // кнопка «Стоп»
    private static final int STOP_RADIUS = 12;
    private static final int STOP_SQUARE = 12;

    private static final int BAR_H = 3;         // полоска прогресса

    /** Насколько окно «въезжает» снизу при появлении. */
    private static final int SLIDE_PX = 40;
    /** На сколько пикселей уезжает/приезжает текст статуса при смене. */
    private static final int TEXT_SHIFT_PX = 8;

    // =====================================================================
    //  Цвета — по спецификации
    // =====================================================================

    private static final Color CARD_BG = new Color(0x1E1817);
    /** rgba(236,220,203,0.10): альфа 0.10 × 255 ≈ 26. */
    private static final Color CARD_BORDER = new Color(236, 220, 203, 26);
    private static final Color TILE_BG = new Color(0x2A211F);
    private static final Color CAPTION = new Color(0xA8968A);
    private static final Color STATUS = new Color(0xF3E7DA);
    private static final Color STOP_BG = new Color(0x3A2E2B);
    /** Чуть светлее — под курсором. В спецификации нет, но без отклика кнопка кажется неживой. */
    private static final Color STOP_BG_HOVER = new Color(0x4A3B37);
    private static final Color STOP_ICON = new Color(0xECDCCB);
    private static final Color BAR_TRACK = new Color(236, 220, 203, 26);
    private static final Color BAR_FILL = new Color(0xC8845A);

    // =====================================================================
    //  Длительности анимаций (мс)
    // =====================================================================

    /** ~60 кадров в секунду: 1000 / 60 ≈ 16 мс между кадрами. */
    private static final int FRAME_MS = 16;
    private static final double APPEAR_MS = 900;
    private static final double TEXT_MS = 250;
    /** THINKING: один полный оборот дуг. */
    private static final double TURN_MS = 1500;
    /** ACTING: один «вдох» точки 1 → 1.5 → 1. */
    private static final double PULSE_MS = 500;
    /** Полоска прогресса проходит слева направо и начинает заново. */
    private static final double BAR_MS = 3000;

    // =====================================================================
    //  Состояние
    // =====================================================================

    /**
     * Что сейчас делает агент — от этого зависит анимация логотипа.
     * THINKING — ждём ответ модели (дуги крутятся);
     * ACTING   — модель ответила, выполняем клик/ввод (дуги стоят, пульсирует точка).
     */
    private enum Mode { THINKING, ACTING }

    private static JWindow window;
    private static WidgetPanel panel;
    private static Timer timer;
    /** Поддерживает ли система прозрачные окна (на Windows 10/11 — да). */
    private static boolean translucent;

    /** Хотим ли мы, чтобы виджет был на экране. Читается и из чужих потоков — поэтому Atomic. */
    private static final AtomicBoolean wanted = new AtomicBoolean(false);

    private static Mode mode = Mode.THINKING;
    private static Runnable onStop = () -> { };
    /**
     * Показывать ли кнопку «Стоп». Для ИИ-задачи — да. Для обычной команды
     * («открой телеграм») — нет: она выполняется мгновенно, останавливать нечего.
     */
    private static boolean showStop = true;
    private static boolean stopPressed;
    private static boolean stopHover;

    /** Прогресс появления: 0 — спрятан, 1 — на месте. Анимация тянет его к wanted. */
    private static double appear;
    /** Угол поворота дуг в градусах; растёт только в режиме THINKING. */
    private static double arcAngle;
    /** Время предыдущего кадра — чтобы считать, сколько прошло (System.nanoTime). */
    private static long lastFrameNanos;
    /** Когда начался режим ACTING — от этого момента считаем фазу пульсации. */
    private static long modeStartNanos;
    /** Когда начался текущий проход полоски прогресса. */
    private static long barStartNanos;

    private static String statusText = "";
    private static String previousText = "";
    private static long textChangeNanos;

    /** Базовая (конечная) позиция окна — считается при каждом показе. */
    private static int baseX, baseY;
    /** Последний применённый сдвиг — чтобы не двигать окно без надобности. */
    private static int lastOffset = Integer.MIN_VALUE;

    private FaustStatusWidget() {}

    // =====================================================================
    //  Публичный интерфейс — его вызывает FaustOverlayController
    // =====================================================================

    /**
     * Показать виджет.
     *
     * @param stopAction  что делать при нажатии «Стоп» — то же, что cancel_task.
     *                    null — кнопки «Стоп» не будет (короткая карточка обычной команды)
     * @param initialText с какого текста начать. Виджет может появиться не в
     *                    начале задачи, а после заставки (вариант A), — тогда
     *                    сюда приходит уже текущий шаг, а не «Запускаю задачу».
     */
    public static void show(Runnable stopAction, String initialText) {
        wanted.set(true);
        SwingUtilities.invokeLater(() -> {
            if (!ensureWindow()) return;          // «безголовая» среда без экрана — молча ничего
            showStop = stopAction != null;
            onStop = showStop ? stopAction : () -> { };
            stopPressed = false;
            stopHover = false;
            barStartNanos = System.nanoTime();
            statusText = initialText == null ? "" : initialText;
            previousText = "";
            textChangeNanos = 0;
            placeWindow();
            setOnScreen(true);
            startTimer();
        });
    }

    /** Агент ждёт ответ модели: дуги начинают вращаться. Работает и до показа — запомнит текст. */
    public static void thinking(String text) {
        SwingUtilities.invokeLater(() -> {
            mode = Mode.THINKING;
            changeText(text);
        });
    }

    /** Модель ответила, агент кликает/печатает: дуги встают, точка пульсирует. */
    public static void acting(String text) {
        SwingUtilities.invokeLater(() -> {
            if (mode != Mode.ACTING) modeStartNanos = System.nanoTime();
            mode = Mode.ACTING;
            changeText(text);
        });
    }

    /** Убрать виджет (конец задачи, ошибка или отмена). Уезжает вниз и тает. */
    public static void hide() {
        wanted.set(false);
        SwingUtilities.invokeLater(FaustStatusWidget::startTimer);   // анимация сама доведёт до конца
    }

    // =====================================================================
    //  Внутренности
    // =====================================================================

    /** Создать окно при первом показе. false — экрана нет (сервер, тесты). */
    private static boolean ensureWindow() {
        if (window != null) return true;
        if (GraphicsEnvironment.isHeadless()) return false;

        // Сначала узнаём, умеет ли система прозрачные окна: конструктор
        // панели читает этот флаг, поэтому порядок строк здесь важен.
        translucent = AgentOverlays.translucencySupported();
        panel = new WidgetPanel();

        window = new JWindow();
        window.setAlwaysOnTop(true);
        // Окно НЕ получает фокус клавиатуры. Иначе, если бы пользователь ткнул
        // в виджет, фокус ушёл бы к нему — и Robot, печатающий через Ctrl+V,
        // вставлял бы текст сюда, а не в нужное поле.
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setSize(W, H);
        window.setContentPane(panel);
        // Прозрачный фон: окно — прямоугольник, а карточка скруглённая. Без
        // прозрачности по углам торчали бы тёмные квадраты.
        if (translucent) window.setBackground(new Color(0, 0, 0, 0));

        // Таймер анимации «тикает» в EDT, поэтому внутри tick() можно смело
        // трогать окно и поля.
        timer = new Timer(FRAME_MS, e -> tick());
        return true;
    }

    /**
     * Правый нижний угол РАБОЧЕЙ области. getMaximumWindowBounds() — это экран
     * за вычетом панели задач, так что виджет не заедет на неё.
     */
    private static void placeWindow() {
        Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        baseX = work.x + work.width - W - MARGIN_RIGHT;
        baseY = work.y + work.height - H - MARGIN_BOTTOM;
        // Базовая точка могла измениться (панель задач переехала) — сбрасываем
        // запомненный сдвиг, чтобы окно гарантированно встало на новое место.
        lastOffset = Integer.MIN_VALUE;
        applyAppearPosition();
    }

    private static void startTimer() {
        if (timer == null) return;
        lastFrameNanos = System.nanoTime();
        if (!timer.isRunning()) timer.start();
    }

    /** Один кадр анимации: пересчитать всё, что движется, и перерисовать. */
    private static void tick() {
        long now = System.nanoTime();
        double dtMs = (now - lastFrameNanos) / 1_000_000.0;
        lastFrameNanos = now;

        // 1. Появление/исчезновение: тянем appear к 1 или к 0.
        double target = wanted.get() ? 1 : 0;
        double step = dtMs / APPEAR_MS;
        appear = target > appear ? Math.min(target, appear + step) : Math.max(target, appear - step);
        applyAppearPosition();

        // 2. Вращение дуг — только пока ждём модель.
        if (mode == Mode.THINKING) {
            arcAngle = (arcAngle + 360.0 * dtMs / TURN_MS) % 360.0;
        }

        // 3. Полностью спрятались — выключаем окно и таймер, чтобы не есть
        //    процессор, пока виджет не нужен.
        if (appear <= 0 && !wanted.get()) {
            setOnScreen(false);
            timer.stop();
            return;
        }
        panel.repaint();
    }

    /**
     * Сдвиг окна по вертикали во время появления. easeOutBack даёт «лёгкий
     * перелёт»: окно проскакивает чуть выше своего места и возвращается. При
     * исчезновении appear идёт от 1 к 0 — та же кривая в обратную сторону.
     */
    private static void applyAppearPosition() {
        if (window == null) return;
        int offset = (int) Math.round(SLIDE_PX * (1 - Easing.easeOutBack(appear, 1.3)));
        // Двигаем окно, только если позиция правда поменялась: перемещение
        // прозрачного окна для Windows дорогое, а 60 раз в секунду впустую —
        // это замеренные лишние проценты процессора.
        if (offset == lastOffset) return;
        lastOffset = offset;
        window.setLocation(baseX, baseY + offset);
    }

    /** Показать/спрятать окно и сообщить об этом AgentOverlays. */
    private static void setOnScreen(boolean visible) {
        if (window == null) return;
        if (visible) {
            window.setVisible(true);
            // После показа у окна появился HWND — теперь можно попросить
            // Windows не показывать его на снимках и не отдавать ему фокус.
            AgentOverlays.register(window, false);
        } else {
            AgentOverlays.unregister(window);
            window.setVisible(false);
        }
    }

    /** Новый текст статуса: старый уедет вверх и растает, новый выедет снизу. */
    private static void changeText(String text) {
        String clean = text == null ? "" : text.replace('\n', ' ').trim();
        if (clean.equals(statusText)) return;       // тот же текст — не дёргаем анимацию зря
        previousText = statusText;
        statusText = clean;
        textChangeNanos = System.nanoTime();
    }

    /** Кнопку «Стоп» нажали. Повторные нажатия игнорируем. */
    private static void pressStop() {
        if (stopPressed) return;
        stopPressed = true;
        changeText("Останавливаю…");
        System.out.println("Задачу остановили кнопкой «Стоп» на виджете");
        onStop.run();
    }

    // =====================================================================
    //  Рисование
    // =====================================================================

    /** Вся карточка — одна панель, которая рисует себя сама. */
    private static final class WidgetPanel extends JPanel {

        private final Font captionFont = JarvisSwingTheme.font(Font.BOLD, 11f);
        private final Font statusFont = JarvisSwingTheme.font(Font.BOLD, 16f);

        // Раскладка по вертикали:
        //   y = 14        — верх плитки (внутренний отступ)
        //   y = 14..70    — плитка 56 px; кнопка «Стоп» центрирована по ней
        //   y = 79..82    — полоска прогресса
        //   y = 82..96    — нижний отступ 14 px
        private final Rectangle stopRect = new Rectangle(W - PAD - STOP, PAD + TILE / 2 - STOP / 2, STOP, STOP);

        WidgetPanel() {
            // Прозрачная панель: фон рисуем сами, в paintComponent.
            setOpaque(!translucent);
            if (!translucent) setBackground(CARD_BG);

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    boolean over = showStop && stopRect.contains(e.getPoint());
                    if (over != stopHover) {
                        stopHover = over;
                        setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    }
                }
                @Override public void mouseExited(MouseEvent e) {
                    stopHover = false;
                }
                @Override public void mouseReleased(MouseEvent e) {
                    // Реагируем на ОТПУСКАНИЕ, как обычная кнопка: нажал, увёл
                    // курсор в сторону, отпустил — действие не срабатывает.
                    if (showStop && stopRect.contains(e.getPoint())) pressStop();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            // Рисуем в копии Graphics: трансформации и прозрачность, которые мы
            // будем менять, не должны «протечь» в рисование других компонентов.
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                // Прозрачность всей карточки при появлении/исчезновении. Всё,
                // что нарисуем ниже, будет умножено на этот коэффициент.
                if (translucent) {
                    g2.setComposite(AlphaComposite.SrcOver.derive((float) Easing.easeOutCubic(appear)));
                }

                long now = System.nanoTime();
                paintCard(g2);
                paintTile(g2, now);
                paintTexts(g2, now);
                paintStopButton(g2);
                paintProgress(g2, now);
            } finally {
                g2.dispose();
            }
        }

        /** Скруглённая карточка с тонкой обводкой. */
        private void paintCard(Graphics2D g2) {
            // RoundRectangle2D принимает ДИАМЕТР скругления, поэтому радиус × 2.
            g2.setColor(CARD_BG);
            g2.fill(new RoundRectangle2D.Double(0, 0, W, H, CARD_RADIUS * 2, CARD_RADIUS * 2));
            // Обводку рисуем со сдвигом на полпикселя, иначе линия толщиной 1
            // ляжет между пикселями и размажется на два.
            g2.setColor(CARD_BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, CARD_RADIUS * 2, CARD_RADIUS * 2));
        }

        /** Плитка слева с логотипом. Сам логотип рисует общий FaustLogoPainter. */
        private void paintTile(Graphics2D g2, long now) {
            int tileX = PAD;
            int tileY = contentCenterY() - TILE / 2;
            g2.setColor(TILE_BG);
            g2.fill(new RoundRectangle2D.Double(tileX, tileY, TILE, TILE, TILE_RADIUS * 2, TILE_RADIUS * 2));

            // Точка «дышит» только в ACTING: sin на полупериоде идёт 0 → 1 → 0,
            // значит масштаб 1 → 1.5 → 1 за PULSE_MS.
            double dotScale = 1;
            if (mode == Mode.ACTING) {
                double phase = ((now - modeStartNanos) / 1_000_000.0 % PULSE_MS) / PULSE_MS;
                dotScale = 1 + 0.5 * Math.sin(Math.PI * phase);
            }
            // Плитку виджет рисует сам (у него свой цвет #2A211F), поэтому withTile=false.
            // В режиме ACTING угол arcAngle не растёт (см. tick) — дуги стоят.
            FaustLogoPainter.paint(g2, tileX + TILE / 2.0, tileY + TILE / 2.0, LOGO,
                    0, arcAngle, 1, dotScale, 1f, false);
        }

        /** Подпись «ФАУСТ · ИИ-АГЕНТ» и строка статуса со сменой через «уезд». */
        private void paintTexts(Graphics2D g2, long now) {
            int textX = PAD + TILE + PAD;
            // Без кнопки «Стоп» текст может занять и её место.
            int textRight = showStop ? stopRect.x - 12 : W - PAD;
            int textWidth = textRight - textX;

            FontMetrics capFm = g2.getFontMetrics(captionFont);
            FontMetrics stFm = g2.getFontMetrics(statusFont);

            // Две строки центрируем по вертикали относительно плитки.
            int gap = 4;
            int block = capFm.getHeight() + gap + stFm.getHeight();
            int top = contentCenterY() - block / 2;
            int captionBaseline = top + capFm.getAscent();
            int statusBaseline = top + capFm.getHeight() + gap + stFm.getAscent();

            g2.setFont(captionFont);
            g2.setColor(CAPTION);
            g2.drawString("ФАУСТ · ИИ-АГЕНТ", textX, captionBaseline);

            // Строку статуса обрезаем прямоугольником: уезжающий текст не должен
            // залезать на кнопку «Стоп» и на подпись над ним.
            Shape oldClip = g2.getClip();
            g2.clipRect(textX, captionBaseline + capFm.getDescent(), textWidth, stFm.getHeight() + gap + 2);
            g2.setFont(statusFont);

            double p = textChangeNanos == 0 ? 1
                    : Easing.easeOutCubic((now - textChangeNanos) / 1_000_000.0 / TEXT_MS);
            if (p < 1 && !previousText.isEmpty()) {
                // Старый текст: вверх на 8 px и растворяется.
                drawFaded(g2, fit(previousText, stFm, textWidth), textX,
                        statusBaseline - (int) Math.round(TEXT_SHIFT_PX * p), 1 - p);
            }
            // Новый текст: снизу на 8 px и проявляется.
            drawFaded(g2, fit(statusText, stFm, textWidth), textX,
                    statusBaseline + (int) Math.round(TEXT_SHIFT_PX * (1 - p)), p);

            g2.setClip(oldClip);
        }

        /** Нарисовать текст с дополнительной прозрачностью поверх общей. */
        private void drawFaded(Graphics2D g2, String text, int x, int y, double alpha) {
            java.awt.Composite before = g2.getComposite();
            FaustLogoPainter.multiplyAlpha(g2, (float) Easing.clamp01(alpha));
            g2.setColor(STATUS);
            g2.drawString(text, x, y);
            g2.setComposite(before);
        }

        /** Кнопка «Стоп»: скруглённый квадрат, внутри — значок «стоп». */
        private void paintStopButton(Graphics2D g2) {
            if (!showStop) return;
            g2.setColor(stopHover && !stopPressed ? STOP_BG_HOVER : STOP_BG);
            g2.fill(new RoundRectangle2D.Double(stopRect.x, stopRect.y, STOP, STOP, STOP_RADIUS * 2, STOP_RADIUS * 2));

            // После нажатия значок бледнеет: кнопка сработала, жать ещё раз незачем.
            Color icon = stopPressed
                    ? new Color(STOP_ICON.getRed(), STOP_ICON.getGreen(), STOP_ICON.getBlue(), 90)
                    : STOP_ICON;
            g2.setColor(icon);
            int sx = stopRect.x + (STOP - STOP_SQUARE) / 2;
            int sy = stopRect.y + (STOP - STOP_SQUARE) / 2;
            g2.fillRect(sx, sy, STOP_SQUARE, STOP_SQUARE);
        }

        /**
         * Полоска внизу. Это НЕ прогресс задачи — сколько шагов она займёт,
         * заранее неизвестно. Это «пульс»: бегунок заполняет полосу за 3 с и
         * начинает заново, показывая, что агент жив и работает.
         */
        private void paintProgress(Graphics2D g2, long now) {
            int x = PAD;
            int y = H - PAD - BAR_H;
            int width = W - PAD * 2;

            g2.setColor(BAR_TRACK);
            g2.fill(new RoundRectangle2D.Double(x, y, width, BAR_H, BAR_H, BAR_H));

            double frac = ((now - barStartNanos) / 1_000_000.0 % BAR_MS) / BAR_MS;
            g2.setColor(BAR_FILL);
            g2.fill(new RoundRectangle2D.Double(x, y, width * frac, BAR_H, BAR_H, BAR_H));
        }

        /** Вертикальный центр основной строки (плитка, текст, кнопка). */
        private int contentCenterY() {
            return stopRect.y + STOP / 2;
        }

        /** Обрезать строку с «…», если не влезает по ширине. */
        private String fit(String text, FontMetrics fm, int maxWidth) {
            if (fm.stringWidth(text) <= maxWidth) return text;
            String ellipsis = "…";
            int end = text.length();
            while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) end--;
            return text.substring(0, end) + ellipsis;
        }
    }
}
