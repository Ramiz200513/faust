package com.jarvis.agent;

import com.jarvis.agent.vision.MentorSession;
import com.jarvis.agent.vision.UiaElement;

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
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.jarvis.agent.Easing.clamp01;
import static com.jarvis.agent.Easing.easeOutBack;
import static com.jarvis.agent.Easing.easeOutCubic;
import static com.jarvis.agent.Easing.progress;

/**
 * Что видит и слышит человек в режиме наставника — реализация {@link MentorSession.View}.
 *
 * <h2>Два окна, как у рамки «Фауст управляет компьютером»</h2>
 * <ul>
 *   <li><b>Подсказка</b> — во весь экран, клики проходят НАСКВОЗЬ (иначе человек не смог бы
 *       нажать ту самую кнопку, на которую мы показываем). Лёгкое затемнение всего экрана,
 *       кроме нужного элемента («прожектор»), пульсирующая рамка вокруг него, дугообразная
 *       стрелка и «пузырь» с подписью «Шаг 2 из 5 · Нажмите «Специальные возможности»».</li>
 *   <li><b>Панель</b> внизу — с кнопками «Повтори · Сделай сам · Стоп». Её можно нажать, но
 *       фокус она не забирает: окно программы остаётся активным. Клики по ней наставник не
 *       считает шагами урока ({@link #panel()}).</li>
 * </ul>
 * Голос ПК ({@link PcVoice}) читает каждый шаг.
 *
 * <p>Координаты элементов UI Automation уже логические (как у «рентгена»), рисуем как есть.
 * Только основной монитор — известное ограничение, как у остальных оверлеев.
 *
 * <p>Все методы можно звать из любого потока: окна трогаются только в потоке Swing.
 */
public final class FaustMentorView implements MentorSession.View {

    // Палитра «Фауста» (как у остальных оверлеев).
    private static final Color BG = new Color(0x1E1817);
    private static final Color TEXT = new Color(0xF3E7DA);
    private static final Color DIM = new Color(0xA8968A);
    private static final Color COPPER = new Color(0xC8845A);
    private static final Color ROSE = new Color(0xE0A0A0);
    private static final Color GREEN = new Color(0x8CC084);
    private static final Color BUTTON = new Color(0x3A2E2B);
    private static final Color BUTTON_HOVER = new Color(0x4A3B37);
    private static final Font STEP_FONT = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font CAPTION_FONT = new Font("Segoe UI", Font.BOLD, 20);
    private static final Font PANEL_FONT = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font BTN_FONT = new Font("Segoe UI", Font.BOLD, 13);

    private static final int FRAME_MS = 33;
    private static final double APPEAR_MS = 420;
    private static final double WRONG_MS = 600;
    private static final double DONE_MS = 3200;
    /** Затемнение вокруг элемента: заметно, но программа хорошо видна. */
    private static final float SPOT_DIM = 0.32f;

    private final Rectangle screen;
    private final Consumer<String> onControl;
    private final PcVoice voice;

    // Поля ниже — только из потока Swing.
    private JWindow overlay;
    private JWindow panelWindow;
    private Timer timer;
    private Rectangle target;           // null — подсказка без элемента
    private String caption = "";
    private String stepText = "";
    private long shownAt;
    private long wrongAt;
    private long doneAt;                // 0 — не «готово»
    private String panelText = "Фауст подсказывает";
    private int hoverButton = -1;
    private final List<Rectangle> buttons = new ArrayList<>();
    private static final String[] BUTTON_WORDS = {"Повтори", "Сделай сам", "Стоп"};
    private volatile Rectangle panelBounds;

    /**
     * @param onControl куда передавать нажатия кнопок панели («Повтори», «Сделай сам», «Стоп»)
     * @param voice     голос ПК (null — молча)
     */
    public FaustMentorView(Consumer<String> onControl, PcVoice voice) {
        this.screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        this.onControl = onControl;
        this.voice = voice;
    }

    // --- MentorSession.View ------------------------------------------------------------------

    @Override
    public void start(String task) {
        SwingUtilities.invokeLater(() -> {
            createWindows();
            panelText = "Фауст подсказывает: " + cut(task, 48);
            panelWindow.repaint();
        });
        say("Хорошо, покажу. Делайте каждый шаг сами, а я подскажу, куда нажать.");
    }

    @Override
    public void point(UiaElement t, String caption, String say, int step, Integer total) {
        Rectangle r = new Rectangle(t.x(), t.y(), t.width(), t.height());
        show(r, caption, step, total);
        say(say);
    }

    @Override
    public void hint(String caption, String say, int step, Integer total) {
        show(null, caption, step, total);
        say(say);
    }

    /** Модель думает над следующим шагом: подпись без стрелки и без голоса (голос был бы назойлив). */
    @Override
    public void thinking(String caption, int step, Integer total) {
        show(null, caption, step, total);
    }

    @Override
    public void wrong(String say) {
        SwingUtilities.invokeLater(() -> {
            wrongAt = System.nanoTime();
            if (timer != null) timer.start();
        });
        say(say);
    }

    @Override
    public void done(String caption, String say) {
        SwingUtilities.invokeLater(() -> {
            target = null;
            this.caption = caption;
            stepText = "";
            doneAt = System.nanoTime();
            shownAt = doneAt;
            panelText = caption;
            if (overlay != null) overlay.repaint();
            if (panelWindow != null) panelWindow.repaint();
        });
        say(say);
    }

    @Override
    public void end() {
        // «Готово» должно успеть повисеть, поэтому окна закрываются с задержкой.
        long linger = doneAt != 0 ? (long) DONE_MS : 0;
        Timer closer = new Timer((int) Math.max(1, linger), e -> closeWindows());
        closer.setRepeats(false);
        SwingUtilities.invokeLater(closer::start);
    }

    @Override
    public Rectangle panel() {
        return panelBounds;
    }

    // --- окна ---------------------------------------------------------------------------------

    private void show(Rectangle r, String caption, int step, Integer total) {
        SwingUtilities.invokeLater(() -> {
            createWindows();
            target = r;
            this.caption = caption == null ? "" : caption;
            stepText = "Шаг " + step + (total == null ? "" : " из " + total);
            panelText = "Фауст подсказывает · " + stepText.toLowerCase();
            doneAt = 0;
            shownAt = System.nanoTime();
            timer.start();
            overlay.repaint();
            panelWindow.repaint();
        });
    }

    private void createWindows() {
        if (overlay != null) return;
        overlay = new JWindow();
        overlay.setAlwaysOnTop(true);
        overlay.setFocusableWindowState(false);
        overlay.setAutoRequestFocus(false);
        overlay.setBackground(new Color(0, 0, 0, 0));
        overlay.setBounds(screen);
        overlay.setContentPane(new HintCanvas());
        overlay.setVisible(true);
        // Декоративное: клики насквозь — человек нажимает кнопку программы под подсказкой.
        AgentOverlays.register(overlay, true);

        Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int w = 620, h = 64;
        panelWindow = new JWindow();
        panelWindow.setAlwaysOnTop(true);
        panelWindow.setFocusableWindowState(false);
        panelWindow.setAutoRequestFocus(false);
        panelWindow.setBackground(new Color(0, 0, 0, 0));
        panelWindow.setBounds(work.x + (work.width - w) / 2, work.y + work.height - h - 18, w, h);
        PanelCanvas pc = new PanelCanvas(w, h);
        panelWindow.setContentPane(pc);
        panelWindow.setVisible(true);
        // С кнопками: нажать можно, фокус не забирает.
        AgentOverlays.register(panelWindow, false);
        panelBounds = panelWindow.getBounds();

        timer = new Timer(FRAME_MS, e -> {
            if (overlay != null) overlay.repaint();
        });
        timer.start();
    }

    private void closeWindows() {
        if (timer != null) timer.stop();
        if (overlay != null) {
            AgentOverlays.unregister(overlay);
            overlay.dispose();
            overlay = null;
        }
        if (panelWindow != null) {
            AgentOverlays.unregister(panelWindow);
            panelWindow.dispose();
            panelWindow = null;
        }
        panelBounds = null;
        // Закрытие голоса ждёт процесс до 2 с — не в потоке Swing, иначе замрёт интерфейс.
        if (voice != null) {
            Thread t = new Thread(voice::close, "pc-voice-close");
            t.setDaemon(true);
            t.start();
        }
    }

    private void say(String text) {
        if (voice != null && text != null && !text.isBlank()) voice.say(text);
    }

    private static double sinceMs(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000.0;
    }

    // --- подсказка: прожектор, рамка, стрелка, пузырь ------------------------------------------

    private final class HintCanvas extends JPanel {
        HintCanvas() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.translate(-screen.x, -screen.y);
                double appear = easeOutCubic(progress(sinceMs(shownAt), 0, APPEAR_MS));
                if (doneAt != 0) {
                    double fade = 1 - clamp01((sinceMs(doneAt) - (DONE_MS - 600)) / 600);
                    paintBubble(g, null, caption, "", GREEN, appear * fade, true);
                    return;
                }
                if (caption.isEmpty()) return;
                // «Не сюда» — короткое покачивание всей подсказки.
                double wrong = wrongAt == 0 ? 1 : clamp01(sinceMs(wrongAt) / WRONG_MS);
                int shake = wrong < 1 ? (int) Math.round(Math.sin(wrong * Math.PI * 6) * 10 * (1 - wrong)) : 0;
                g.translate(shake, 0);

                Rectangle t = target;
                if (t != null) {
                    // Прожектор: тёмная пелена везде, кроме элемента (с запасом).
                    Area dim = new Area(screen);
                    Rectangle hole = new Rectangle(t.x - 8, t.y - 8, t.width + 16, t.height + 16);
                    dim.subtract(new Area(new RoundRectangle2D.Double(hole.x, hole.y, hole.width, hole.height, 14, 14)));
                    g.setComposite(AlphaComposite.SrcOver.derive((float) (SPOT_DIM * appear)));
                    g.setColor(Color.BLACK);
                    g.fill(dim);

                    // Пульсирующая рамка вокруг элемента.
                    double beat = Math.abs(Math.sin(sinceMs(shownAt) / 1000.0 * Math.PI));
                    int grow = (int) Math.round(3 + 4 * beat);
                    g.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(appear)));
                    g.setColor(wrong < 1 ? new Color(0xE07A6A) : ROSE);
                    g.setStroke(new BasicStroke(3f + 2f * (float) beat));
                    g.draw(new RoundRectangle2D.Double(t.x - grow, t.y - grow, t.width + 2 * grow, t.height + 2 * grow, 14, 14));
                }
                paintBubble(g, t, caption, stepText, COPPER, appear, false);
            } finally {
                g.dispose();
            }
        }

        /** Пузырь с подписью; если есть элемент — рядом с ним и со стрелкой к нему. */
        private void paintBubble(Graphics2D g, Rectangle t, String text, String step, Color accent,
                                 double appear, boolean big) {
            g.setFont(CAPTION_FONT);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(text, fm, 460);
            int lineH = fm.getHeight();
            int w = 0;
            for (String l : lines) w = Math.max(w, fm.stringWidth(l));
            w += 40;
            int h = lines.size() * lineH + (step.isEmpty() ? 26 : 46);
            Point at = bubbleSpot(t, w, h, big);
            double pop = easeOutBack(clamp01(appear));
            int dy = (int) Math.round((1 - pop) * 14);

            g.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(appear)));
            if (t != null) paintArrow(g, at, w, h, t, accent);

            RoundRectangle2D box = new RoundRectangle2D.Double(at.x, at.y + dy, w, h, 22, 22);
            g.setColor(new Color(0, 0, 0, 70));
            g.fill(new RoundRectangle2D.Double(at.x + 2, at.y + dy + 5, w, h, 22, 22));   // тень
            g.setColor(BG);
            g.fill(box);
            g.setColor(accent);
            g.setStroke(new BasicStroke(2f));
            g.draw(box);

            int y = at.y + dy + 18;
            if (!step.isEmpty()) {
                g.setFont(STEP_FONT);
                g.setColor(accent);
                g.drawString(step, at.x + 20, y + g.getFontMetrics().getAscent() - 2);
                y += 22;
            }
            g.setFont(CAPTION_FONT);
            g.setColor(TEXT);
            for (String l : lines) {
                g.drawString(l, at.x + 20, y + fm.getAscent());
                y += lineH;
            }
        }

        /**
         * Куда поставить пузырь: справа от элемента, если там есть место, иначе слева, ниже
         * или выше; без элемента — сверху по центру. Всегда целиком на экране.
         */
        private Point bubbleSpot(Rectangle t, int w, int h, boolean big) {
            if (t == null) {
                return new Point(screen.x + (screen.width - w) / 2,
                        big ? screen.y + (screen.height - h) / 2 : screen.y + 90);
            }
            int gap = 90;
            int x, y;
            if (t.x + t.width + gap + w < screen.x + screen.width) {
                x = t.x + t.width + gap;
                y = t.y + t.height / 2 - h / 2;
            } else if (t.x - gap - w > screen.x) {
                x = t.x - gap - w;
                y = t.y + t.height / 2 - h / 2;
            } else if (t.y + t.height + gap + h < screen.y + screen.height) {
                x = t.x + t.width / 2 - w / 2;
                y = t.y + t.height + gap;
            } else {
                x = t.x + t.width / 2 - w / 2;
                y = t.y - gap - h;
            }
            x = Math.max(screen.x + 12, Math.min(x, screen.x + screen.width - w - 12));
            y = Math.max(screen.y + 12, Math.min(y, screen.y + screen.height - h - 90));
            return new Point(x, y);
        }

        /** Дугообразная стрелка от пузыря к элементу; наконечник слегка «кивает». */
        private void paintArrow(Graphics2D g, Point at, int w, int h, Rectangle t, Color color) {
            double cxB = at.x + w / 2.0, cyB = at.y + h / 2.0;
            double cxT = t.getCenterX(), cyT = t.getCenterY();
            // Начало — на краю пузыря, конец — чуть не доходя до рамки элемента.
            double sx = cxB < cxT ? at.x + w : (cxB > cxT + t.width / 2.0 ? at.x : cxB);
            double sy = cxB < cxT || cxB > cxT + t.width / 2.0 ? cyB : (cyB < cyT ? at.y + h : at.y);
            double ex = cxT + (sx < cxT ? -t.width / 2.0 - 12 : (sx > cxT ? t.width / 2.0 + 12 : 0));
            double ey = cyT + (Math.abs(sx - cxT) < t.width / 2.0 ? (sy < cyT ? -t.height / 2.0 - 12 : t.height / 2.0 + 12) : 0);
            double bob = Math.sin(System.nanoTime() / 1e9 * Math.PI * 1.6) * 5;
            double dx = ex - sx, dy = ey - sy;
            double len = Math.max(1, Math.hypot(dx, dy));
            ex += dx / len * bob;
            ey += dy / len * bob;
            // Изгиб: контрольная точка сдвинута поперёк линии.
            double mx = (sx + ex) / 2 - dy * 0.25, my = (sy + ey) / 2 + dx * 0.25;
            g.setColor(color);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new QuadCurve2D.Double(sx, sy, mx, my, ex, ey));
            // Наконечник по касательной в конце кривой.
            double ang = Math.atan2(ey - my, ex - mx);
            Path2D head = new Path2D.Double();
            head.moveTo(ex, ey);
            head.lineTo(ex - 18 * Math.cos(ang - 0.45), ey - 18 * Math.sin(ang - 0.45));
            head.lineTo(ex - 18 * Math.cos(ang + 0.45), ey - 18 * Math.sin(ang + 0.45));
            head.closePath();
            g.fill(head);
        }
    }

    // --- панель с кнопками ------------------------------------------------------------------

    private final class PanelCanvas extends JPanel {
        private final int w;
        private final int h;

        PanelCanvas(int w, int h) {
            this.w = w;
            this.h = h;
            setOpaque(false);
            int bx = w - 14;
            int[] widths = {104, 126, 78};
            buttons.clear();
            for (int i = widths.length - 1; i >= 0; i--) {
                bx -= widths[i];
                buttons.add(0, new Rectangle(bx, 14, widths[i], 36));
                bx -= 8;
            }
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int hit = hit(e.getPoint());
                    if (hit != hoverButton) {
                        hoverButton = hit;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverButton = -1;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    int hit = hit(e.getPoint());
                    if (hit >= 0 && onControl != null) onControl.accept(BUTTON_WORDS[hit]);
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private int hit(Point p) {
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).contains(p)) return i;
            return -1;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(BG);
                g.fill(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, h, h));
                g.setColor(doneAt != 0 ? GREEN : COPPER);
                g.draw(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, h, h));
                g.setFont(PANEL_FONT);
                g.setColor(TEXT);
                g.drawString(cut(panelText, 40), 26, h / 2 + g.getFontMetrics().getAscent() / 2 - 2);
                for (int i = 0; i < buttons.size(); i++) {
                    Rectangle r = buttons.get(i);
                    boolean stop = i == buttons.size() - 1;
                    g.setColor(hoverButton == i ? BUTTON_HOVER : (i == 1 ? COPPER : BUTTON));
                    g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 18, 18));
                    g.setFont(BTN_FONT);
                    g.setColor(i == 1 ? Color.WHITE : (stop ? new Color(0xE07A6A) : TEXT));
                    FontMetrics fm = g.getFontMetrics();
                    String s = BUTTON_WORDS[i];
                    g.drawString(s, r.x + (r.width - fm.stringWidth(s)) / 2,
                            r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
                }
                g.setColor(DIM);
            } finally {
                g.dispose();
            }
        }
    }

    // --- текст --------------------------------------------------------------------------------

    /** Перенос по словам в заданную ширину. */
    static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).split(" ")) {
            String probe = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(probe) > width && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out.isEmpty() ? List.of("") : out;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
