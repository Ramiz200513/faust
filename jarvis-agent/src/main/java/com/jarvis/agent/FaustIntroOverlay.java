package com.jarvis.agent;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import static com.jarvis.agent.Easing.clamp01;
import static com.jarvis.agent.Easing.easeInOutCubic;
import static com.jarvis.agent.Easing.easeOutBack;
import static com.jarvis.agent.Easing.easeOutCubic;
import static com.jarvis.agent.Easing.lerp;
import static com.jarvis.agent.Easing.progress;

/**
 * Вариант A — заставка «Пробуждение» в начале ИИ-задачи.
 *
 * Экран темнеет, в центре раскрывается логотип, от него расходятся три
 * «радиоволны», появляется подпись «Фауст на связи», и логотип улетает в
 * правый верхний угол. Дальше либо уступает место статус-виджету, либо
 * остаётся маленьким значком до конца задачи.
 *
 * <h2>Почему это не мешает агенту</h2>
 * Агент начинает работать сразу, не дожидаясь конца заставки. Поэтому окно
 * заставки прозрачно для мыши (клики агента проходят сквозь затемнение) и
 * исключено из снимков экрана (модель затемнения не видит). Оба свойства
 * ставит {@link AgentOverlays#register}.
 *
 * <h2>Как читать этот класс</h2>
 * Вся заставка — функция от времени t (мс с начала). В каждом кадре мы не
 * «двигаем» предметы понемногу, а заново вычисляем, где каждый из них должен
 * быть в момент t. Так анимация не зависит от того, сколько кадров успело
 * отрисоваться: даже если компьютер притормозил, в момент 1000 мс всё будет
 * ровно там, где задумано.
 */
final class FaustIntroOverlay {

    // --- таймлайн, мс (по спецификации) ------------------------------------

    private static final double DIM_IN_END = 560;
    private static final double DIM_OUT_START = 3850, DIM_OUT_END = 4620;
    private static final double DIM_ALPHA = 0.62;

    private static final double TILE_IN_END = 700;        // 0.55 → 1.05, появление
    private static final double TILE_SETTLE_END = 1050;   // 1.05 → 1.0

    private static final double SPREAD_START = 840, SPREAD_PEAK = 1190, SPREAD_END = 1750;
    private static final double SPREAD_UNITS = 6;

    private static final double[] WAVE_STARTS = {1120, 1820, 2520};
    private static final float[] WAVE_STROKES = {5f, 4f, 3f};
    private static final double WAVE_MS = 1540;
    /** Радиус дуг волны при масштабе 1 — как у дуг в плитке 180 px (30 × 1.8). */
    private static final double WAVE_RADIUS_PX = 54;

    private static final double CAPTION_IN_START = 980, CAPTION_IN_END = 1540;
    private static final double CAPTION_OUT_START = 3500, CAPTION_OUT_END = 3920;

    private static final double FLY_START = 3850, FLY_END = 4690;
    private static final double FLY_SCALE = 0.28;          // 180 × 0.28 ≈ 50 px

    private static final double HANDOFF_FADE_MS = 200;     // значок тает, появляется виджет
    private static final double END_FADE_MS = 300;         // исчезновение по onTaskEnd
    private static final double ICON_PULSE_MS = 1600;

    // --- размеры и цвета ------------------------------------------------------

    private static final double TILE_PX = 180;
    private static final int ICON_WINDOW = 120;            // окно после полёта
    private static final Color DIM = new Color(15, 11, 10);
    private static final Color TITLE = new Color(0xF3E7DA);
    private static final Color SUBTITLE = new Color(0xC9B8A9);

    private static final int FRAME_MS = 16;

    // --- состояние (только поток Swing) ---------------------------------------

    private final Rectangle screen;
    /** Центр заставки C и точка, куда улетает логотип. */
    private final double cx, cy, cornerX, cornerY;
    /** Уступить место виджету (true) или остаться значком (false). */
    private final boolean handoffToWidget;
    private final Runnable onHandoff;

    private JWindow window;
    private Timer timer;
    private long startNanos;
    /** Когда пришёл onTaskEnd; 0 — ещё не приходил. */
    private long endNanos;
    private boolean handedOff;
    private boolean shrunk;
    private boolean closed;

    private final Font titleFont = JarvisSwingTheme.font(Font.BOLD, 30f);
    private final Font subtitleFont = JarvisSwingTheme.font(Font.PLAIN, 15f);

    /**
     * @param handoffToWidget показать ли после заставки статус-виджет
     * @param onHandoff       что вызвать в момент передачи (показывает виджет)
     */
    FaustIntroOverlay(boolean handoffToWidget, Runnable onHandoff) {
        this.handoffToWidget = handoffToWidget;
        this.onHandoff = onHandoff;
        // Весь экран целиком, а не рабочая область: затемнение должно лечь и
        // на панель задач.
        this.screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        this.cx = screen.x + screen.width / 2.0;
        this.cy = screen.y + screen.height / 2.0 - 30;
        this.cornerX = screen.x + screen.width - 60;
        this.cornerY = screen.y + 60;
    }

    /** Начать заставку. Вызывать из потока Swing. */
    void start() {
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setBackground(new Color(0, 0, 0, 0));   // прозрачный фон окна
        window.setBounds(screen);
        window.setContentPane(new IntroPanel());
        window.setVisible(true);
        // Только ПОСЛЕ показа: до этого у окна нет HWND.
        AgentOverlays.register(window, true);

        startNanos = System.nanoTime();
        timer = new Timer(FRAME_MS, e -> tick());
        timer.start();
    }

    /** Задача закончилась: всё, что сейчас на экране, тает за 300 мс. */
    void finish() {
        if (closed || endNanos != 0) return;
        endNanos = System.nanoTime();
    }

    private double elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    /** Один кадр: решить, не пора ли что-то переключить, и перерисовать. */
    private void tick() {
        double t = elapsedMs();

        // Исчезновение по onTaskEnd закончилось — закрываем окно.
        if (endNanos != 0 && endFade() <= 0) {
            close();
            return;
        }

        if (t >= FLY_END && endNanos == 0) {
            if (handoffToWidget) {
                // Логотип долетел: зовём виджет и за 200 мс растворяемся.
                if (!handedOff) {
                    handedOff = true;
                    onHandoff.run();
                }
                if (t >= FLY_END + HANDOFF_FADE_MS) {
                    close();
                    return;
                }
            } else if (!shrunk) {
                // Остаёмся значком. Окно на весь экран больше не нужно, а
                // перерисовывать его 60 раз в секунду дорого — ужимаем до
                // 120×120 вокруг значка.
                shrunk = true;
                window.setBounds((int) Math.round(cornerX - ICON_WINDOW / 2.0),
                        (int) Math.round(cornerY - ICON_WINDOW / 2.0), ICON_WINDOW, ICON_WINDOW);
            }
        }
        window.repaint();
    }

    /** Множитель прозрачности при исчезновении по onTaskEnd: 1 → 0 за 300 мс. */
    private double endFade() {
        if (endNanos == 0) return 1;
        return 1 - progress((System.nanoTime() - endNanos) / 1_000_000.0, 0, END_FADE_MS);
    }

    private void close() {
        if (closed) return;
        closed = true;
        timer.stop();
        AgentOverlays.unregister(window);
        window.dispose();
    }

    // =========================================================================
    //  Рисование: каждый метод отвечает за один пункт спецификации
    // =========================================================================

    private final class IntroPanel extends JPanel {
        IntroPanel() {
            setOpaque(false);   // иначе Swing залил бы окно фоном и прозрачность пропала бы
        }

        @Override
        protected void paintComponent(Graphics g) {
            // super.paintComponent НЕ вызываем: для непрозрачной панели он
            // заливает фон, а нам нужен прозрачный.
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // Рисуем в координатах ЭКРАНА: сдвигаем начало координат на
                // положение окна. Тогда после того, как окно ужмётся до значка,
                // рисование не нужно пересчитывать — окно просто «видит» кусок.
                g2.translate(-window.getX(), -window.getY());
                g2.setComposite(AlphaComposite.SrcOver.derive((float) endFade()));

                double t = elapsedMs();
                paintDim(g2, t);
                paintWaves(g2, t);
                paintTile(g2, t);
                paintCaption(g2, t);
            } finally {
                g2.dispose();
            }
        }
    }

    /** 1. Затемнение всего экрана: 0 → 0.62 за 0–560 мс, затем 0.62 → 0 за 3850–4620 мс. */
    private void paintDim(Graphics2D g2, double t) {
        double rise = easeInOutCubic(progress(t, 0, DIM_IN_END));
        double fall = easeInOutCubic(progress(t, DIM_OUT_START, DIM_OUT_END));
        double a = DIM_ALPHA * rise * (1 - fall);
        if (a <= 0) return;
        g2.setColor(withAlpha(DIM, a));
        g2.fill(screen);
    }

    /**
     * 2, 3, 5, 7, 8. Плитка-логотип: появление, «приветствие» дугами, пульс
     * точки, полёт в угол и, в конце, либо растворение, либо значок с пульсом.
     */
    private void paintTile(Graphics2D g2, double t) {
        // 2. Появление: масштаб 0.55 → 1.05 с перелётом (easeOutBack), затем 1.05 → 1.0.
        double appear = progress(t, 0, TILE_IN_END);
        double scale = t < TILE_IN_END
                ? lerp(0.55, 1.05, easeOutBack(appear))
                : lerp(1.05, 1.0, easeOutCubic(progress(t, TILE_IN_END, TILE_SETTLE_END)));
        // У easeOutBack бывают значения больше 1 — прозрачность больше 1 не бывает.
        double alpha = clamp01(easeOutBack(appear));

        // 7. Полёт в угол: позиция и масштаб плавно переходят к значку.
        double fly = easeInOutCubic(progress(t, FLY_START, FLY_END));
        double x = lerp(cx, cornerX, fly);
        double y = lerp(cy, cornerY, fly);
        scale *= lerp(1.0, FLY_SCALE, fly);

        // 8а. Передали управление виджету — растворяемся за 200 мс.
        if (handoffToWidget) alpha *= 1 - progress(t, FLY_END, FLY_END + HANDOFF_FADE_MS);
        if (alpha <= 0) return;

        double size = TILE_PX * scale;
        paintShadow(g2, x, y, size, alpha);
        FaustLogoPainter.paint(g2, x, y, size, spread(t), 0, 1, dotScale(t), (float) alpha, true);
    }

    /** 3. «Приветствие»: дуги раздвигаются на 6 единиц (840–1190 мс) и сходятся обратно к 1750 мс. */
    private double spread(double t) {
        if (t < SPREAD_PEAK) return SPREAD_UNITS * easeInOutCubic(progress(t, SPREAD_START, SPREAD_PEAK));
        return SPREAD_UNITS * (1 - easeInOutCubic(progress(t, SPREAD_PEAK, SPREAD_END)));
    }

    /**
     * 5. Точка «вздрагивает» с каждой волной: 1 → 1.6 за 210 мс → 1 за 280 мс.
     * 8б. После полёта, если остались значком, — спокойный пульс 1 → 1.4 → 1 за 1.6 с.
     */
    private double dotScale(double t) {
        double s = 1;
        for (double start : WAVE_STARTS) {
            double dt = t - start;
            if (dt >= 0 && dt < 210) s = Math.max(s, lerp(1, 1.6, easeOutCubic(dt / 210)));
            else if (dt >= 210 && dt < 490) s = Math.max(s, lerp(1.6, 1, easeInOutCubic((dt - 210) / 280)));
        }
        if (!handoffToWidget && t >= FLY_END) {
            // (1 - cos) / 2 плавно идёт 0 → 1 → 0 за период — это и есть пульс.
            double phase = (t - FLY_END) / ICON_PULSE_MS;
            s = 1 + 0.4 * (1 - Math.cos(2 * Math.PI * phase)) / 2;
        }
        return s;
    }

    /**
     * Мягкая тень под плиткой. В спецификации — один полупрозрачный
     * прямоугольник со смещением 30 px вниз и альфой 0.35. Один прямоугольник
     * с резким краем выглядел бы как вторая плитка, поэтому рисуем несколько
     * слоёв, каждый чуть больше предыдущего: в середине они складываются в те
     * же 0.35, а к краю тень плавно сходит на нет — так выглядит размытие.
     */
    private void paintShadow(Graphics2D g2, double x, double y, double size, double alpha) {
        int layers = 6;
        double perLayer = 0.35 / layers;
        double offset = 30 * size / TILE_PX;
        for (int i = layers - 1; i >= 0; i--) {
            double grow = i * size * 0.03;
            double s = size + grow * 2;
            double radius = 48 * s / 100;   // скругление плитки — 24 единицы сетки, диаметр 48
            g2.setColor(withAlpha(Color.BLACK, perLayer * alpha));
            g2.fill(new RoundRectangle2D.Double(x - s / 2, y - s / 2 + offset, s, s, radius, radius));
        }
    }

    /**
     * 4. Три волны-эха: те же дуги вокруг центра, растут в 2.7 раза и тают.
     * Толщина линии НЕ растёт вместе с волной (5, 4 и 3 px), поэтому рисуем их
     * через paintArcsOnly — там толщина задаётся в пикселях.
     */
    private void paintWaves(Graphics2D g2, double t) {
        for (int i = 0; i < WAVE_STARTS.length; i++) {
            double p = (t - WAVE_STARTS[i]) / WAVE_MS;
            if (p < 0 || p >= 1) continue;
            double scale = lerp(1, 2.7, easeOutCubic(p));
            double alpha = 0.9 * (1 - p);
            FaustLogoPainter.paintArcsOnly(g2, cx, cy, WAVE_RADIUS_PX * scale, WAVE_STROKES[i], (float) alpha);
        }
    }

    /** 6. Подпись под логотипом: выезжает снизу на 14 px и проявляется, потом тает. */
    private void paintCaption(Graphics2D g2, double t) {
        double in = easeOutCubic(progress(t, CAPTION_IN_START, CAPTION_IN_END));
        double out = easeOutCubic(progress(t, CAPTION_OUT_START, CAPTION_OUT_END));
        double alpha = in * (1 - out);
        if (alpha <= 0) return;
        double shift = 14 * (1 - in);

        Graphics2D c = (Graphics2D) g2.create();
        try {
            FaustLogoPainter.multiplyAlpha(c, (float) alpha);

            FontMetrics titleFm = c.getFontMetrics(titleFont);
            FontMetrics subFm = c.getFontMetrics(subtitleFont);
            String title = "Фауст на связи";
            String subtitle = "ИИ-агент получил управление с телефона";

            // «На 150 px ниже C» — это верх первой строки.
            double top = cy + 150 + shift;
            c.setFont(titleFont);
            c.setColor(TITLE);
            c.drawString(title, (float) (cx - titleFm.stringWidth(title) / 2.0), (float) (top + titleFm.getAscent()));

            double subTop = top + titleFm.getHeight() + 10;
            c.setFont(subtitleFont);
            c.setColor(SUBTITLE);
            c.drawString(subtitle, (float) (cx - subFm.stringWidth(subtitle) / 2.0), (float) (subTop + subFm.getAscent()));
        } finally {
            c.dispose();
        }
    }

    /** Тот же цвет с прозрачностью alpha (0..1). */
    private static Color withAlpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * clamp01(alpha)));
    }
}
