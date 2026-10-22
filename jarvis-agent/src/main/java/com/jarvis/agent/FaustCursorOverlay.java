package com.jarvis.agent;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import static com.jarvis.agent.Easing.clamp01;
import static com.jarvis.agent.Easing.easeInOutCubic;
import static com.jarvis.agent.Easing.easeOutCubic;
import static com.jarvis.agent.Easing.lerp;
import static com.jarvis.agent.Easing.progress;

/**
 * Вариант D — «курсор ИИ»: пока агент работает, за мышью ходит ореол с
 * логотипом и ярлыком «Фауст», а каждый клик агента отмечается сжатием дуг и
 * расходящимся медным кругом.
 *
 * <h2>Почему это не мешает кликам</h2>
 * Ореол стоит ПРЯМО под курсором — туда, куда Robot сейчас нажмёт. Поэтому
 * окно ореола прозрачно для мыши (click-through): клик проходит сквозь него в
 * программу под ним. Если Windows эту просьбу не выполнила, {@link AgentOverlays}
 * на время нажатия ореол уберёт.
 *
 * <h2>Курсор-призрак (браузерный режим)</h2>
 * В браузерном режиме Selenium кликает ВНУТРИ Chrome — настоящая мышь стоит на
 * месте, и без призрака действий агента на экране просто не видно. Поэтому по
 * {@link #glideTo} ореол отрывается от настоящей мыши и сам летит к элементу, по
 * которому агент кликает. Настоящей стрелки под ним нет — призрак рисует её сам.
 * Твоя мышь при этом свободна: ей можно пользоваться, пока агент работает.
 *
 * <h2>Про настоящий курсор</h2>
 * Системный курсор мы не подменяем: из Java это возможно только через
 * SetSystemCursor, и если агент упадёт, чужой курсор останется у пользователя
 * во всей системе. Ореол рисуется ВОКРУГ настоящей стрелки — Windows рисует
 * курсор поверх всех окон, так что стрелка остаётся видна.
 */
final class FaustCursorOverlay {

    // --- размеры (по спецификации) --------------------------------------------

    private static final int HALO_W = 180, HALO_H = 90;
    /** Центр ореола внутри окна — сюда совмещаем кончик курсора. */
    private static final int CX = HALO_W / 2, CY = HALO_H / 2;
    /** Размер логотипа ореола: сетка 100×100 в 64 px. */
    private static final double LOGO = 64;
    /** Подложка — круг радиусом 44 единицы сетки. */
    private static final double BACKING_RADIUS = 44;
    /** Точка радиусом 8 единиц вместо обычных 7. */
    private static final double DOT_SCALE = 8.0 / FaustLogoPainter.DOT_RADIUS;
    /** Ярлык «Фауст» справа снизу от центра. */
    private static final int LABEL_DX = 30, LABEL_DY = 22;

    private static final int BURST_WINDOW = 170;
    private static final double BURST_DIAMETER = 60;

    // --- тайминги, мс -----------------------------------------------------------

    private static final double FADE_MS = 250;
    private static final double SQUEEZE_IN_MS = 160, SQUEEZE_OUT_MS = 240;
    private static final double DOUBLE_DELAY_MS = 320;
    private static final double BURST_MS = 800;
    private static final int FRAME_MS = 16;

    // --- цвета ------------------------------------------------------------------

    private static final Color BACKING = new Color(30, 24, 23, 140);   // 0.55 × 255 ≈ 140
    private static final Color LABEL_BG = new Color(0x1E1817);
    private static final Color LABEL_TEXT = new Color(0xECDCCB);
    private static final Color BURST_FIRST = new Color(0xC8845A);
    private static final Color BURST_SECOND = new Color(0xECDCCB);
    /** Стрелка призрака: светлая с тёмной обводкой — видна и на светлых, и на тёмных страницах. */
    private static final Color ARROW_FILL = new Color(0xF3E7DA);
    private static final Color ARROW_EDGE = new Color(0x1E1817);

    // --- состояние (только поток Swing) ---------------------------------------

    private JWindow halo;
    private Timer timer;
    private long startNanos;
    private long endNanos;
    private boolean closed;
    /** Моменты начала «сжатий» дуг (System.nanoTime). Для двойного клика их два. */
    private final List<Long> squeezes = new ArrayList<>();
    private Point lastMouse;
    /** Нужна ли перерисовка: пока ничего не меняется, окно только двигаем. */
    private boolean animating = true;

    // --- режим призрака -----------------------------------------------------
    /** true — ореол летит сам по glideTo, а не следует за настоящей мышью. */
    private boolean ghost;
    /** Где призрак сейчас (центр ореола, координаты экрана). */
    private double ghostX, ghostY;
    /** Откуда и куда летит, когда начал и сколько лететь. */
    private double fromX, fromY, toX, toY;
    private long glideStartNanos;
    private double glideMs;
    private Point lastGhostPos;

    private final Font labelFont = JarvisSwingTheme.font(Font.BOLD, 12f);

    /** Показать ореол у текущего положения мыши. Вызывать из потока Swing. */
    void show() {
        halo = new JWindow();
        halo.setAlwaysOnTop(true);
        halo.setFocusableWindowState(false);
        halo.setAutoRequestFocus(false);
        halo.setBackground(new Color(0, 0, 0, 0));
        halo.setSize(HALO_W, HALO_H);
        halo.setContentPane(new HaloPanel());
        followMouse();
        halo.setVisible(true);
        // КРИТИЧНО: click-through. Иначе Robot кликал бы по самому ореолу.
        AgentOverlays.register(halo, true);

        startNanos = System.nanoTime();
        timer = new Timer(FRAME_MS, e -> tick());
        timer.start();
    }

    /** Задача закончилась: ореол тает за 250 мс. */
    void hide() {
        if (closed || endNanos != 0) return;
        endNanos = System.nanoTime();
    }

    /**
     * Агент вот-вот нажмёт мышь в (x, y). Вызывается ДО нажатия и в потоке
     * Swing с ожиданием (см. FaustOverlayController.onClick): к моменту
     * нажатия окно-всплеск уже должно быть прозрачным для мыши, иначе клик
     * попал бы в него.
     */
    void onClick(int x, int y, boolean doubleClick) {
        if (closed) return;
        long now = System.nanoTime();
        squeezes.add(now);
        new Burst(x, y, BURST_FIRST).start();
        if (doubleClick) {
            squeezes.add(now + (long) (DOUBLE_DELAY_MS * 1_000_000));
            // Второй круг другого цвета — через 320 мс. Одноразовый таймер.
            Timer later = new Timer((int) DOUBLE_DELAY_MS, e -> {
                if (!closed) new Burst(x, y, BURST_SECOND).start();
            });
            later.setRepeats(false);
            later.start();
        }
        animating = true;
    }

    /**
     * Оторваться от настоящей мыши и перелететь в (x, y) за durationMs
     * (easeInOutCubic — разгон и торможение, как у настоящей руки с мышью).
     * Вызывается из потока Swing; сам НЕ ждёт — ждёт вызывающий (контроллер),
     * если хочет, чтобы клик случился, когда призрак уже долетел.
     */
    void glideTo(int x, int y, double durationMs) {
        if (closed) return;
        if (!ghost) {
            // Первый полёт: стартуем оттуда, где ореол стоял — у настоящей мыши.
            ghost = true;
            ghostX = lastMouse != null ? lastMouse.x : x;
            ghostY = lastMouse != null ? lastMouse.y : y;
            animating = true;   // перерисовать: у призрака своя стрелка
        }
        fromX = ghostX;
        fromY = ghostY;
        toX = x;
        toY = y;
        glideMs = Math.max(0, durationMs);
        glideStartNanos = System.nanoTime();
        if (glideMs == 0) {           // без плавного полёта — сразу на место
            ghostX = x;
            ghostY = y;
            placeGhost();
        }
    }

    // =========================================================================
    //  Кадр
    // =========================================================================

    private void tick() {
        long now = System.nanoTime();
        if (endNanos != 0 && (now - endNanos) / 1_000_000.0 >= FADE_MS) {
            close();
            return;
        }
        if (ghost) moveGhost(now);
        else followMouse();

        // Сжатия старше 400 мс закончились — выкидываем, чтобы список не рос.
        squeezes.removeIf(s -> (now - s) / 1_000_000.0 > SQUEEZE_IN_MS + SQUEEZE_OUT_MS);

        // Перерисовываем, только когда картинка правда меняется: появление,
        // исчезновение или сжатие. Просто следовать за мышью — это перенос
        // окна, а не перерисовка, и он почти ничего не стоит.
        boolean fading = (now - startNanos) / 1_000_000.0 < FADE_MS || endNanos != 0;
        if (fading || !squeezes.isEmpty() || animating) {
            halo.repaint();
            animating = fading || !squeezes.isEmpty();
        }
    }

    /** Поставить окно так, чтобы центр ореола совпал с кончиком курсора. */
    private void followMouse() {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer == null) return;   // бывает на экране блокировки
        Point p = pointer.getLocation();
        if (p.equals(lastMouse)) return;   // мышь не двигалась — окно не трогаем
        lastMouse = p;
        halo.setLocation(p.x - CX, p.y - CY);
    }

    /** Сдвинуть призрака вдоль текущего перелёта. Время — от System.nanoTime(). */
    private void moveGhost(long now) {
        if (glideMs <= 0) return;
        double k = easeInOutCubic((now - glideStartNanos) / 1_000_000.0 / glideMs);
        ghostX = fromX + (toX - fromX) * k;
        ghostY = fromY + (toY - fromY) * k;
        placeGhost();
    }

    private void placeGhost() {
        Point p = new Point((int) Math.round(ghostX), (int) Math.round(ghostY));
        if (p.equals(lastGhostPos)) return;   // стоит на месте — окно не трогаем
        lastGhostPos = p;
        halo.setLocation(p.x - CX, p.y - CY);
    }

    private void close() {
        if (closed) return;
        closed = true;
        timer.stop();
        AgentOverlays.unregister(halo);
        halo.dispose();
    }

    /** Масштаб дуг: 1 → 0.6 за 160 мс → 1 за 240 мс. При нескольких сжатиях берём самое глубокое. */
    private double arcScale(long now) {
        double scale = 1;
        for (long s : squeezes) {
            double dt = (now - s) / 1_000_000.0;
            if (dt < 0) continue;   // второе сжатие двойного клика ещё не началось
            double v = dt < SQUEEZE_IN_MS
                    ? lerp(1, 0.6, easeOutCubic(dt / SQUEEZE_IN_MS))
                    : lerp(0.6, 1, easeOutCubic((dt - SQUEEZE_IN_MS) / SQUEEZE_OUT_MS));
            scale = Math.min(scale, v);
        }
        return scale;
    }

    // =========================================================================
    //  Рисование ореола
    // =========================================================================

    private final class HaloPanel extends JPanel {
        HaloPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                long now = System.nanoTime();
                double in = progress((now - startNanos) / 1_000_000.0, 0, FADE_MS);
                double out = endNanos == 0 ? 0 : progress((now - endNanos) / 1_000_000.0, 0, FADE_MS);
                g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(in * (1 - out))));

                double r = BACKING_RADIUS * LOGO / 100;   // 44 единицы сетки в пикселях

                // Мягкая тень подложки: несколько кругов, каждый шире и бледнее.
                for (int i = 4; i >= 1; i--) {
                    double rr = r + i * 2;
                    g2.setColor(new Color(0, 0, 0, 12));
                    g2.fill(new Ellipse2D.Double(CX - rr, CY - rr + 2, rr * 2, rr * 2));
                }
                g2.setColor(BACKING);
                g2.fill(new Ellipse2D.Double(CX - r, CY - r, r * 2, r * 2));

                FaustLogoPainter.paint(g2, CX, CY, LOGO, 0, 0, arcScale(now), DOT_SCALE, 1f, false);
                if (ghost) paintArrow(g2);

                paintLabel(g2);
            } finally {
                g2.dispose();
            }
        }

        /**
         * Стрелка призрака — привычный силуэт курсора, остриём ровно в центре
         * ореола (там же, где у настоящей мыши «горячая точка» клика). Рисуем
         * её только в режиме призрака: у настоящей мыши стрелку рисует Windows.
         */
        private void paintArrow(Graphics2D g2) {
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 17);
            arrow.lineTo(4.2, 13);
            arrow.lineTo(7.2, 19.5);
            arrow.lineTo(10, 18.3);
            arrow.lineTo(7.1, 12);
            arrow.lineTo(12.5, 12);
            arrow.closePath();

            Graphics2D a = (Graphics2D) g2.create();
            try {
                a.translate(CX, CY);
                // Мягкая тень на 1 px правее и ниже — отрывает стрелку от фона.
                a.translate(1, 1.5);
                a.setColor(new Color(0, 0, 0, 90));
                a.fill(arrow);
                a.translate(-1, -1.5);
                a.setColor(ARROW_FILL);
                a.fill(arrow);
                a.setColor(ARROW_EDGE);
                a.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                a.draw(arrow);
            } finally {
                a.dispose();
            }
        }

        /** Ярлык-«таблетка» «Фауст»: левый край на +30 px, середина по высоте на +22 px. */
        private void paintLabel(Graphics2D g2) {
            g2.setFont(labelFont);
            FontMetrics fm = g2.getFontMetrics();
            String text = "Фауст";
            int w = fm.stringWidth(text) + 10 * 2;
            int h = fm.getHeight() + 4 * 2;
            int x = CX + LABEL_DX;
            int y = CY + LABEL_DY - h / 2;
            g2.setColor(LABEL_BG);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
            g2.setColor(LABEL_TEXT);
            g2.drawString(text, x + 10, y + 4 + fm.getAscent());
        }
    }

    // =========================================================================
    //  Всплеск в точке клика — отдельное короткоживущее окно
    // =========================================================================

    /**
     * Медный круг, расходящийся от точки клика. На каждый клик — новое окно
     * 170×170, которое само закрывается через 800 мс. Отдельное окно, а не
     * рисунок в ореоле: ореол уезжает вместе с мышью, а всплеск должен
     * остаться там, где был клик.
     */
    private final class Burst {
        private final JWindow window = new JWindow();
        private final Color color;
        private Timer timer;
        private long startNanos;

        Burst(int x, int y, Color color) {
            this.color = color;
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setBackground(new Color(0, 0, 0, 0));
            window.setBounds(x - BURST_WINDOW / 2, y - BURST_WINDOW / 2, BURST_WINDOW, BURST_WINDOW);
            window.setContentPane(new JPanel() {
                { setOpaque(false); }

                @Override
                protected void paintComponent(Graphics g) {
                    paintBurst((Graphics2D) g);
                }
            });
        }

        void start() {
            window.setVisible(true);
            // Всплеск стоит ровно в точке клика — прозрачность для мыши обязательна.
            AgentOverlays.register(window, true);
            startNanos = System.nanoTime();
            timer = new Timer(FRAME_MS, e -> {
                if ((System.nanoTime() - startNanos) / 1_000_000.0 >= BURST_MS) {
                    timer.stop();
                    AgentOverlays.unregister(window);
                    window.dispose();
                } else {
                    window.repaint();
                }
            });
            timer.start();
        }

        /** Окружность 60 px: масштаб 0.4 → 2.6 и прозрачность 1 → 0 за 800 мс. */
        private void paintBurst(Graphics2D g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double p = easeOutCubic((System.nanoTime() - startNanos) / 1_000_000.0 / BURST_MS);
                double d = BURST_DIAMETER * lerp(0.4, 2.6, p);
                g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(1 - p)));
                g2.setColor(color);
                g2.setStroke(new BasicStroke(3f));
                double c = BURST_WINDOW / 2.0;
                g2.draw(new Ellipse2D.Double(c - d / 2, c - d / 2, d, d));
            } finally {
                g2.dispose();
            }
        }
    }
}
