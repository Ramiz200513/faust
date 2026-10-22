package com.jarvis.agent;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Панель «● Запись навыка» сверху экрана, пока человек показывает агенту задачу.
 *
 * <p>Два смысла сразу. Практический: кнопки «Готово» и «Отмена». И доверие:
 * пока агент записывает действия человека, это ВИДНО — никакой скрытой
 * записи. Красная пульсирующая точка — как у диктофона.
 *
 * <p>Окно можно нажать, но фокус оно не забирает: человек продолжает работать
 * в своей программе. Клики по нему DemoRecorder не записывает (см. bounds()).
 */
final class FaustRecordingOverlay {

    private static final int W = 600;
    private static final int H = 64;
    private static final Color BG = new Color(0x1E1817);
    private static final Color BORDER = new Color(0xE07A6A);
    private static final Color TEXT = new Color(0xF3E7DA);
    private static final Color DIM = new Color(0xA8968A);
    private static final Color RED = new Color(0xE0483A);
    private static final Color COPPER = new Color(0xC8845A);
    private static final Color BUTTON = new Color(0x3A2E2B);
    private static final Font TITLE = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font SMALL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font BTN = new Font("Segoe UI", Font.BOLD, 13);

    private final JWindow window = new JWindow();
    private final Timer timer;
    private final long startedAt = System.nanoTime();
    private final Rectangle doneButton = new Rectangle(W - 206, 14, 96, 36);
    private final Rectangle cancelButton = new Rectangle(W - 102, 14, 88, 36);
    private final String skillName;
    private volatile int count;
    private volatile String last = "делайте задачу мышью и клавиатурой";

    FaustRecordingOverlay(String skillName, Runnable onDone, Runnable onCancel) {
        this.skillName = skillName;
        Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setBounds(work.x + (work.width - W) / 2, work.y + 14, W, H);
        Panel panel = new Panel();
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (doneButton.contains(e.getPoint())) onDone.run();
                else if (cancelButton.contains(e.getPoint())) onCancel.run();
            }
        });
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        window.setContentPane(panel);
        timer = new Timer(100, e -> window.repaint());   // только ради пульса точки
    }

    void show() {
        window.setVisible(true);
        // С кнопками: нажать можно, фокус не забирает, на снимках агента не видно.
        AgentOverlays.register(window, false);
        timer.start();
    }

    /** Записан новый шаг (или пропущен — тогда text объясняет почему). Можно из любого потока. */
    void update(int count, String text) {
        this.count = count;
        this.last = text;
        SwingUtilities.invokeLater(window::repaint);
    }

    /** Где окно на экране — клики сюда записывать нельзя. */
    Rectangle bounds() {
        return window.getBounds();
    }

    void close() {
        timer.stop();
        AgentOverlays.unregister(window);
        window.dispose();
    }

    private final class Panel extends JPanel {
        Panel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(BG);
                g.fill(new RoundRectangle2D.Double(0, 0, W - 1, H - 1, H, H));
                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Double(0, 0, W - 1, H - 1, H, H));

                // Пульсирующая красная точка — «идёт запись».
                double t = (System.nanoTime() - startedAt) / 1e9;
                double r = 6 + 2 * Math.abs(Math.sin(t * Math.PI));
                g.setColor(RED);
                g.fill(new Ellipse2D.Double(28 - r, H / 2.0 - r, 2 * r, 2 * r));

                g.setFont(TITLE);
                g.setColor(TEXT);
                g.drawString("Запись навыка «" + cut(skillName, 24) + "» · шагов: " + count, 48, 28);
                g.setFont(SMALL);
                g.setColor(DIM);
                g.drawString(cut(last, 52), 48, 47);

                button(g, doneButton, "Готово", COPPER, Color.WHITE);
                button(g, cancelButton, "Отмена", BUTTON, TEXT);
            } finally {
                g.dispose();
            }
        }

        private void button(Graphics2D g, Rectangle r, String text, Color bg, Color fg) {
            g.setColor(bg);
            g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 18, 18));
            g.setFont(BTN);
            g.setColor(fg);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
