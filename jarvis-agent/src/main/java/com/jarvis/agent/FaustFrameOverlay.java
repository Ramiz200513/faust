package com.jarvis.agent;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static com.jarvis.agent.Easing.clamp01;
import static com.jarvis.agent.Easing.easeOutBack;
import static com.jarvis.agent.Easing.easeOutCubic;
import static com.jarvis.agent.Easing.progress;

/**
 * Вариант B — «живая рамка»: пока агент работает, по краю экрана светится
 * тёплое свечение, по периметру бегут два светящихся «хвоста», а сверху висит
 * плашка «Фауст управляет компьютером» с кнопкой «Остановить».
 *
 * <h2>Почему четыре окна, а не одно</h2>
 * Одно прозрачное окно на весь экран Java перерисовывает ЦЕЛИКОМ. Поэтому
 * рамка собрана из четырёх полос глубиной 120 px вдоль краёв, как в
 * спецификации, и середина экрана в перерисовке не участвует вовсе.
 *
 * <h2>Как полосы стыкуются без швов</h2>
 * Каждая полоса рисует ОБЩУЮ картину рамки в координатах экрана, сдвинув
 * начало координат на своё положение (translate(-x, -y)), и видит только свой
 * кусок. Поэтому углы сходятся идеально.
 *
 * <h2>Производительность — что замерено и что из этого следует</h2>
 * Замер показал: цена прозрачного окна почти не зависит от числа пикселей и
 * почти целиком определяется тем, СКОЛЬКО РАЗ В СЕКУНДУ его обновляют. Полоса
 * 1536×120 на 60 кадрах — ~17% ядра, и даже линия 1536×4 — ~8%. На 60 кадрах
 * рамка съедала ~40% ядра при цели 5–10%.
 *
 * Поэтому полосы перерисовываются реже — 10 раз в секунду по умолчанию
 * (overlay.frameFps в agent.properties). Глаз этого
 * почти не замечает: «дыхание» свечения медленное (3.2 с), а хвосты бегущей
 * линии длинные и мягкие. Плашка при этом въезжает с полной плавностью: её
 * движение — это перенос окна (setLocation), а не перерисовка.
 *
 * Ещё одно сбережение: свечение от кадра к кадру не меняет формы, только
 * яркость. Каждая полоса рисует его ОДИН раз в картинку-кэш, а в кадре
 * просто выводит с нужной прозрачностью.
 */
final class FaustFrameOverlay {

    // --- размеры и тайминги (по спецификации) --------------------------------

    /** Глубина полос свечения от края внутрь. */
    private static final int D = 120;
    /** Ширина бегущей линии у самого края. */
    private static final int LINE = 4;
    /** Длина одного отрезка бегущей линии. */
    private static final int SEG = 6;
    private static final int COPPER_DEPTH = 56;
    private static final int ROSE_DEPTH = 120;

    private static final double BREATH_MS = 3200;   // дыхание свечения
    private static final double RUN_MS = 6000;      // полный оборот «хвостов»
    private static final double FADE_MS = 400;      // появление/исчезновение рамки
    private static final double PILL_SLIDE_MS = 1000;
    private static final int PILL_SLIDE_PX = 70;
    private static final int PILL_TOP = 22;

    /** Таймер тикает 60 раз в секунду: этого хватает для плавного въезда плашки. */
    private static final int FRAME_MS = 16;
    /**
     * А полосы и плашку ПЕРЕРИСОВЫВАЕМ реже — overlay.frameFps раз в секунду
     * (по умолчанию 10, см. AgentSettings.OVERLAY_FRAME_FPS). Замер нагрузки
     * одной рамки: 60 к/с ~27% ядра, 20 ~16%, 15 ~14%, 10 ~7–13%.
     */
    private static final int DEFAULT_FPS = 10;

    // --- цвета ----------------------------------------------------------------

    private static final Color COPPER = new Color(0xC8845A);
    private static final Color ROSE = new Color(0xC38F8F);
    private static final Color PILL_BG = new Color(0x1E1817);
    private static final Color PILL_BORDER = new Color(236, 220, 203, 31);   // 0.12 × 255 ≈ 31
    private static final Color PILL_TEXT = new Color(0xF3E7DA);
    private static final Color BUTTON_BG = new Color(0x3A2E2B);
    private static final Color BUTTON_BG_HOVER = new Color(0x4A3B37);
    private static final Color BUTTON_TEXT = new Color(0xECDCCB);

    /**
     * Опорные точки цвета бегущей линии: положение p (0..1) по кругу, цвет и
     * прозрачность. Между опорами цвет плавно смешивается. «Прозрачные» опоры
     * делают разрывы — так из одной линии получаются два хвоста.
     */
    private static final double[] STOP_POS = {0.00, 0.12, 0.24, 0.38, 0.50, 0.64, 0.76, 0.88, 1.00};
    private static final int[] STOP_RGB = {0xECDCCB, 0xC38F8F, 0xC8845A, 0, 0, 0xECDCCB, 0xC38F8F, 0xC8845A, 0xECDCCB};
    private static final double[] STOP_ALPHA = {1, 1, 1, 0, 0, 1, 1, 1, 1};

    // --- состояние (только поток Swing) ---------------------------------------

    private final Rectangle screen;
    private final Runnable stopAction;
    /** Все отрезки бегущей линии по периметру. Считаются один раз. */
    private final List<Segment> segments = new ArrayList<>();
    private final List<Strip> strips = new ArrayList<>();

    private JWindow pill;
    private PillPanel pillPanel;
    private Timer timer;
    private long startNanos;
    private long endNanos;
    private boolean closed;
    private int lastPillY = Integer.MIN_VALUE;
    private long lastRepaintNanos;
    /** Интервал между перерисовками полос. Читается из настроек при создании рамки. */
    private final long repaintEveryNanos;

    FaustFrameOverlay(Runnable stopAction) {
        this.stopAction = stopAction;
        this.screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        int fps = AgentSettings.number(AgentSettings.OVERLAY_FRAME_FPS, DEFAULT_FPS, 1, 60);
        this.repaintEveryNanos = 1_000_000_000L / fps;
        buildSegments();
    }

    /** Показать рамку и плашку. Вызывать из потока Swing. */
    void show() {
        int x = screen.x, y = screen.y, w = screen.width, h = screen.height;

        // Четыре полосы: верх и низ во всю ширину, бока — между ними.
        addStrip(new Rectangle(x, y, w, D));
        addStrip(new Rectangle(x, y + h - D, w, D));
        addStrip(new Rectangle(x, y + D, D, h - 2 * D));
        addStrip(new Rectangle(x + w - D, y + D, D, h - 2 * D));

        // Плашка — последней, чтобы свечение верхней полосы не легло поверх неё.
        pillPanel = new PillPanel();
        pill = newOverlayWindow(pillPanel.windowWidth, pillPanel.windowHeight);
        pill.setContentPane(pillPanel);
        placePill(0);
        pill.setVisible(true);
        // С кнопкой: кликнуть можно, фокус не забирает. Сквозь неё клики НЕ проходят.
        AgentOverlays.register(pill, false);

        startNanos = System.nanoTime();
        timer = new Timer(FRAME_MS, e -> tick());
        timer.start();
    }

    /** Задача закончилась: рамка тает за 400 мс, плашка уезжает вверх. */
    void hide() {
        if (closed || endNanos != 0) return;
        endNanos = System.nanoTime();
    }

    private void addStrip(Rectangle bounds) {
        Strip strip = new Strip(bounds);
        strip.window = newOverlayWindow(bounds.width, bounds.height);
        strip.window.setLocation(bounds.x, bounds.y);
        strip.window.setContentPane(new StripPanel(strip));
        strip.window.setVisible(true);
        // Декоративная: клики проходят насквозь, на снимках не видна.
        AgentOverlays.register(strip.window, true);
        strips.add(strip);
    }

    private static JWindow newOverlayWindow(int w, int h) {
        JWindow win = new JWindow();
        win.setAlwaysOnTop(true);
        win.setFocusableWindowState(false);   // Robot не должен печатать в оверлей
        win.setAutoRequestFocus(false);
        win.setBackground(new Color(0, 0, 0, 0));
        win.setSize(w, h);
        return win;
    }

    // =========================================================================
    //  Время
    // =========================================================================

    private double elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private double sinceEndMs() {
        return endNanos == 0 ? 0 : (System.nanoTime() - endNanos) / 1_000_000.0;
    }

    /** Общая прозрачность рамки: проявление в начале и исчезновение по onTaskEnd. */
    private double fade() {
        double in = easeOutCubic(progress(elapsedMs(), 0, FADE_MS));
        double out = endNanos == 0 ? 0 : easeOutCubic(progress(sinceEndMs(), 0, FADE_MS));
        return in * (1 - out);
    }

    /** Дыхание свечения: синусоида между 0.55 и 1.0 с периодом 3.2 с, умноженная на fade. */
    private double glowAlpha() {
        double breath = 0.775 + 0.225 * Math.sin(2 * Math.PI * elapsedMs() / BREATH_MS);
        return breath * fade();
    }

    private void tick() {
        if (endNanos != 0 && sinceEndMs() >= FADE_MS) {
            close();
            return;
        }
        // Въезд плашки — каждый тик (60 раз в секунду): это только перенос
        // окна, он почти ничего не стоит.
        placePill(elapsedMs());

        // А перерисовка — реже, overlay.frameFps раз в секунду. Время считаем
        // по часам, а не по числу тиков: таймер Swing не обещает точных 16 мс.
        long now = System.nanoTime();
        if (now - lastRepaintNanos < repaintEveryNanos) return;
        lastRepaintNanos = now;
        for (Strip strip : strips) strip.window.repaint();
        pill.repaint();
    }

    private void close() {
        if (closed) return;
        closed = true;
        timer.stop();
        for (Strip strip : strips) disposeWindow(strip.window);
        disposeWindow(pill);
    }

    private static void disposeWindow(JWindow w) {
        AgentOverlays.unregister(w);
        w.dispose();
    }

    // =========================================================================
    //  1. Полосы: свечение (кэш) + бегущая линия
    // =========================================================================

    /**
     * Полоса-окно: картинка свечения, нарисованная один раз при полной
     * яркости, и отрезки бегущей линии, которые в эту полосу попадают.
     */
    private final class Strip {
        final Rectangle bounds;
        JWindow window;
        final BufferedImage glow;
        final List<Segment> mySegments = new ArrayList<>();

        Strip(Rectangle bounds) {
            this.bounds = bounds;
            // ARGB_PRE («предумноженная» прозрачность) — тот же формат, что у
            // буфера прозрачного окна в Swing. Тогда вывод картинки в кадре —
            // быстрое копирование, а не попиксельный перевод из формата в формат.
            this.glow = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = glow.createGraphics();
            g.translate(-bounds.x, -bounds.y);   // рисуем в координатах экрана
            paintGlow(g);
            g.dispose();
            for (Segment s : segments) {
                if (s.rect.intersects(bounds)) mySegments.add(s);
            }
        }
    }

    private final class StripPanel extends JPanel {
        private final Strip strip;

        StripPanel(Strip strip) {
            this.strip = strip;
            setOpaque(false);   // иначе Swing залил бы окно фоном и прозрачность пропала бы
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                double fade = fade();

                // 1. Свечение: готовая картинка с прозрачностью «дыхания».
                g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(glowAlpha())));
                g2.drawImage(strip.glow, 0, 0, null);

                // 2. Бегущая линия: фаза растёт на 360° за 6 с, цвет отрезка
                //    зависит от того, где он относительно «головы» хвоста.
                g2.setComposite(AlphaComposite.SrcOver.derive((float) fade));
                g2.translate(-strip.bounds.x, -strip.bounds.y);
                double phase = (elapsedMs() / RUN_MS * 360) % 360;
                for (Segment s : strip.mySegments) {
                    double p = (((s.angleDeg - phase) % 360) + 360) % 360 / 360;
                    int argb = colorAt(p);
                    if (argb == 0) continue;
                    g2.setColor(new Color(argb, true));
                    g2.fill(s.rect);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Свечение от всех четырёх краёв сразу. Каждая полоса вызывает это для себя,
     * но видит только свой кусок — поэтому углы, где встречаются свечение
     * сверху и сбоку, сходятся без шва.
     *
     * Два слоя, как в спецификации: узкий медный (0.55 → 0 за 56 px) и широкий
     * розовый (0.28 → 0 за 120 px).
     */
    private void paintGlow(Graphics2D g) {
        int x = screen.x, y = screen.y, w = screen.width, h = screen.height;
        glowFromEdge(g, x, y, 0, 1, new Rectangle(x, y, w, ROSE_DEPTH));                       // верх
        glowFromEdge(g, x, y + h, 0, -1, new Rectangle(x, y + h - ROSE_DEPTH, w, ROSE_DEPTH));  // низ
        glowFromEdge(g, x, y, 1, 0, new Rectangle(x, y, ROSE_DEPTH, h));                       // лево
        glowFromEdge(g, x + w, y, -1, 0, new Rectangle(x + w - ROSE_DEPTH, y, ROSE_DEPTH, h));  // право
    }

    /**
     * Два градиента от точки на краю (ex, ey) внутрь по направлению (dx, dy).
     * GradientPaint плавно переходит от первого цвета ко второму между двумя
     * точками, а дальше второй точки держит второй цвет — у нас это прозрачность.
     */
    private static void glowFromEdge(Graphics2D g, int ex, int ey, int dx, int dy, Rectangle area) {
        g.setPaint(new GradientPaint(ex, ey, withAlpha(ROSE, 0.28),
                ex + dx * ROSE_DEPTH, ey + dy * ROSE_DEPTH, withAlpha(ROSE, 0)));
        g.fill(area);
        g.setPaint(new GradientPaint(ex, ey, withAlpha(COPPER, 0.55),
                ex + dx * COPPER_DEPTH, ey + dy * COPPER_DEPTH, withAlpha(COPPER, 0)));
        g.fill(area);
    }

    // =========================================================================
    //  2. Бегущая линия по периметру
    // =========================================================================

    /** Отрезок линии и его угол от центра экрана — угол задаёт цвет отрезка. */
    private record Segment(Rectangle rect, double angleDeg) {}

    /** Нарезать периметр на отрезки по 6 px и запомнить угол каждого. Один раз. */
    private void buildSegments() {
        int x = screen.x, y = screen.y, w = screen.width, h = screen.height;
        for (int i = 0; i < w; i += SEG) {
            addSegment(new Rectangle(x + i, y, Math.min(SEG, w - i), LINE));             // верх
            addSegment(new Rectangle(x + i, y + h - LINE, Math.min(SEG, w - i), LINE));  // низ
        }
        for (int j = 0; j < h; j += SEG) {
            addSegment(new Rectangle(x, y + j, LINE, Math.min(SEG, h - j)));             // лево
            addSegment(new Rectangle(x + w - LINE, y + j, LINE, Math.min(SEG, h - j)));  // право
        }
    }

    private void addSegment(Rectangle r) {
        double sx = r.getCenterX() - screen.getCenterX();
        double sy = r.getCenterY() - screen.getCenterY();
        // atan2 даёт угол направления из центра экрана на отрезок, от -180° до 180°.
        segments.add(new Segment(r, Math.toDegrees(Math.atan2(sy, sx))));
    }

    /**
     * Цвет в точке p (0..1) между опорами. Смешиваем в «предумноженном» виде
     * (цвет × прозрачность): тогда переход к прозрачной опоре выглядит как
     * плавное угасание того же цвета, а не как посерение через чёрный.
     *
     * @return ARGB или 0, если в этой точке линия полностью прозрачна
     */
    private static int colorAt(double p) {
        int i = 0;
        while (i < STOP_POS.length - 2 && p > STOP_POS[i + 1]) i++;
        double k = clamp01((p - STOP_POS[i]) / (STOP_POS[i + 1] - STOP_POS[i]));
        double a0 = STOP_ALPHA[i], a1 = STOP_ALPHA[i + 1];
        double a = a0 + (a1 - a0) * k;
        if (a <= 0.004) return 0;
        int c0 = STOP_RGB[i], c1 = STOP_RGB[i + 1];
        int r = channel(c0 >> 16, c1 >> 16, a0, a1, k, a);
        int g = channel(c0 >> 8, c1 >> 8, a0, a1, k, a);
        int b = channel(c0, c1, a0, a1, k, a);
        return ((int) Math.round(a * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int c0, int c1, double a0, double a1, double k, double a) {
        double pre = (c0 & 0xFF) * a0 + ((c1 & 0xFF) * a1 - (c0 & 0xFF) * a0) * k;
        return (int) Math.max(0, Math.min(255, Math.round(pre / a)));
    }

    // =========================================================================
    //  3. Плашка сверху с кнопкой «Остановить»
    // =========================================================================

    /** Сдвиг плашки: выезжает сверху на 70 px за 1 с, в конце уезжает обратно. */
    private void placePill(double t) {
        double shown = endNanos == 0
                ? easeOutBack(progress(t, 0, PILL_SLIDE_MS))
                : easeOutBack(1 - progress(sinceEndMs(), 0, FADE_MS));
        int y = (int) Math.round(screen.y + PILL_TOP - PillPanel.SHADOW + (-PILL_SLIDE_PX) * (1 - shown));
        if (y == lastPillY) return;    // окно на месте — не двигаем зря
        lastPillY = y;
        pill.setLocation(screen.x + (screen.width - pillPanel.windowWidth) / 2, y);
    }

    private final class PillPanel extends JPanel {
        /** Поле вокруг плашки под тень: окно больше самой плашки. */
        static final int SHADOW = 14;
        static final int H = 44;
        static final int LOGO = 30;

        final Font textFont = JarvisSwingTheme.font(Font.BOLD, 14f);
        final Font buttonFont = JarvisSwingTheme.font(Font.BOLD, 13f);
        final String text = "Фауст управляет компьютером";
        final String buttonText = "Остановить";

        final int windowWidth, windowHeight, pillWidth;
        final Rectangle button;
        boolean hover, pressed;

        PillPanel() {
            setOpaque(false);
            // Размеры текста нужны ещё до показа окна, а FontMetrics дают только
            // у «живого» Graphics — берём его у временной картинки 1×1.
            Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            probe.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics tf = probe.getFontMetrics(textFont);
            FontMetrics bf = probe.getFontMetrics(buttonFont);
            probe.dispose();

            int buttonW = bf.stringWidth(buttonText) + 14 * 2;   // отступы 8×14
            int buttonH = bf.getHeight() + 8 * 2;
            int inset = (H - buttonH) / 2;                       // одинаково сверху, снизу и справа
            int textX = 14 + LOGO + 10;
            int buttonX = textX + tf.stringWidth(text) + 16;
            pillWidth = buttonX + buttonW + inset;

            button = new Rectangle(SHADOW + buttonX, SHADOW + inset, buttonW, buttonH);
            windowWidth = pillWidth + SHADOW * 2;
            windowHeight = H + SHADOW * 2 + 4;   // +4: тень смещена вниз

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    boolean over = button.contains(e.getPoint());
                    if (over != hover) {
                        hover = over;
                        setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        // Плашка перерисовывается всего ~10 раз в секунду —
                        // подсветку под курсором показываем сразу, не ждём.
                        repaint();
                    }
                }
                @Override public void mouseExited(MouseEvent e) {
                    if (hover) {
                        hover = false;
                        repaint();
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (!pressed && button.contains(e.getPoint())) {
                        pressed = true;
                        repaint();   // кнопка сразу бледнеет — нажатие принято
                        System.out.println("Задачу остановили кнопкой «Остановить» на рамке");
                        stopAction.run();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.SrcOver.derive((float) fade()));

                double r = H;   // диаметр скругления = высоте -> полукруглые торцы, «таблетка»

                // Тень: несколько слоёв, каждый шире предыдущего — края мягкие.
                for (int i = 4; i >= 1; i--) {
                    g2.setColor(new Color(0, 0, 0, 14));
                    g2.fill(new RoundRectangle2D.Double(SHADOW - i * 2.5, SHADOW - i * 2.5 + 4,
                            pillWidth + i * 5, H + i * 5, r + i * 5, r + i * 5));
                }

                g2.setColor(PILL_BG);
                g2.fill(new RoundRectangle2D.Double(SHADOW, SHADOW, pillWidth, H, r, r));
                g2.setColor(PILL_BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Double(SHADOW + 0.5, SHADOW + 0.5, pillWidth - 1, H - 1, r, r));

                // Мини-логотип «дышит»: (1 - cos) / 2 плавно идёт 0 → 1 → 0 за 1.6 с.
                double k = (1 - Math.cos(2 * Math.PI * elapsedMs() / 1600)) / 2;
                FaustLogoPainter.paint(g2, SHADOW + 14 + LOGO / 2.0, SHADOW + H / 2.0, LOGO,
                        0, 0, 1 + 0.08 * k, 1 + 0.45 * k, 1f, false);

                g2.setFont(textFont);
                g2.setColor(PILL_TEXT);
                FontMetrics tf = g2.getFontMetrics();
                int baseline = SHADOW + (H - tf.getHeight()) / 2 + tf.getAscent();
                g2.drawString(text, SHADOW + 14 + LOGO + 10, baseline);

                // Кнопка — та же «таблетка», поменьше. После нажатия бледнеет.
                g2.setColor(hover && !pressed ? BUTTON_BG_HOVER : BUTTON_BG);
                g2.fill(new RoundRectangle2D.Double(button.x, button.y, button.width, button.height,
                        button.height, button.height));
                g2.setFont(buttonFont);
                FontMetrics bf = g2.getFontMetrics();
                g2.setColor(pressed ? withAlpha(BUTTON_TEXT, 0.4) : BUTTON_TEXT);
                g2.drawString(buttonText, button.x + 14,
                        button.y + (button.height - bf.getHeight()) / 2 + bf.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** Тот же цвет с прозрачностью alpha (0..1). */
    private static Color withAlpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * clamp01(alpha)));
    }
}
