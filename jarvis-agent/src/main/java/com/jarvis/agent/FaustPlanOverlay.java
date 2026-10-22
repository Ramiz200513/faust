package com.jarvis.agent;

import com.jarvis.agent.protocol.JarvisMessage;

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
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import static com.jarvis.agent.Easing.clamp01;
import static com.jarvis.agent.Easing.easeOutCubic;
import static com.jarvis.agent.Easing.progress;

/**
 * Панель «План и мысли» справа на экране ПК во время ИИ-задачи.
 *
 * <pre>
 *   ПЛАН · 2 из 4                       ИЗ ПАМЯТИ
 *   ✓ Скачать архив
 *       скачан файл Hello-World-master.zip
 *   ● Открыть Telegram          (пульсирует — идёт сейчас)
 *   ○ Отправить файл в Избранное
 *   ───────────────────────────────
 *   ДУМАЮ: открываю чат, чтобы прикрепить файл
 *   Нажимаю «Избранное»
 *   ПРОВЕРКА: ✓ в чате видно отправленный файл
 * </pre>
 *
 * Многоэтапность — суть темы диплома, и здесь она видна без объяснений:
 * какие шаги придумал агент, где он сейчас, что думает и как проверил себя.
 * «Думаю» — это настоящее обоснование модели (поле reason в её ответе), а не
 * заготовленная фраза.
 *
 * <p>Окно небольшое и перерисовывается только при изменениях плюс ~8 раз в
 * секунду ради пульсации текущего шага. Прозрачно для мыши и скрыто от
 * снимков экрана, как все оверлеи.
 */
final class FaustPlanOverlay {

    private static final int W = 360;
    private static final int PAD = 16;
    private static final int MARGIN_RIGHT = 20;
    private static final int TOP = 92;          // ниже плашки рамки «Фауст управляет компьютером»
    private static final int ROW_GAP = 6;

    private static final Color BG = new Color(0x1E1817);
    private static final Color BORDER = new Color(236, 220, 203, 30);
    private static final Color TITLE = new Color(0xF3E7DA);
    private static final Color DIM = new Color(0xA8968A);
    private static final Color COPPER = new Color(0xC8845A);
    private static final Color ROSE = new Color(0xE0A0A0);
    private static final Color GREEN = new Color(0x8CC084);
    private static final Color RED = new Color(0xE07A6A);
    private static final Color AMBER = new Color(0xF0C060);

    private static final Font CAPTION = new Font("Segoe UI", Font.BOLD, 11);
    private static final Font ROW = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font ROW_BOLD = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font NOTE = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font THOUGHT = new Font("Segoe UI", Font.ITALIC, 13);

    private static final Graphics2D PROBE =
            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();

    private static final double FADE_MS = 300;
    private static final int FRAME_MS = 125;   // 8 к/с — хватает для мягкой пульсации точки

    private final Rectangle work;
    private final JWindow window;
    private final Timer timer;
    private final long shownAt = System.nanoTime();

    // --- что показываем (только поток Swing) ---------------------------------
    private String goal = "";
    private List<JarvisMessage.PlanItem> items = List.of();
    private int revision = 1;
    private boolean fromMemory;
    private String thought = "";
    private String action = "";
    /** Текст последней проверки; null — проверок ещё не было. */
    private String verify;
    /** run / ok / fail / unknown — см. FaustOverlayController.onVerify. */
    private String verifyState;

    FaustPlanOverlay(String goal) {
        this.goal = goal == null ? "" : goal;
        work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setContentPane(new Panel());
        timer = new Timer(FRAME_MS, e -> window.repaint());
        relayout();
        window.setVisible(true);
        AgentOverlays.register(window, true);
        timer.start();
    }

    void setPlan(List<JarvisMessage.PlanItem> items, int revision) {
        this.items = List.copyOf(items);
        this.revision = revision;
        relayout();
    }

    void setThought(String action, String reason) {
        this.action = action == null ? "" : action;
        this.thought = reason == null ? "" : reason;
        relayout();
    }

    void setMemory(boolean fromMemory) {
        this.fromMemory = fromMemory;
        window.repaint();
    }

    void setVerify(String state, String text) {
        this.verifyState = state;
        this.verify = text;
        relayout();
    }

    void close() {
        timer.stop();
        AgentOverlays.unregister(window);
        window.dispose();
    }

    // --- раскладка ------------------------------------------------------------

    /** Высота зависит от содержимого — пересчитываем при каждом изменении. */
    private void relayout() {
        int h = measure(null);
        // Не выше, чем до виджета «Фауст» в правом нижнем углу.
        int maxH = work.height - TOP - 180;
        window.setBounds(work.x + work.width - W - MARGIN_RIGHT, work.y + TOP, W, Math.min(h, Math.max(160, maxH)));
        window.repaint();
    }

    /**
     * Один проход и для измерения высоты (g == null), и для рисования —
     * так размер окна никогда не разойдётся с тем, что в нём нарисовано.
     */
    private int measure(Graphics2D g) {
        // Для измерения нужна любая графика с теми же шрифтами — берём
        // постоянную крошечную картинку: у окна до показа графики ещё нет.
        Graphics2D mg = g != null ? g : PROBE;
        int y = PAD;
        int textW = W - 2 * PAD;

        // Заголовок
        String header = items.isEmpty() ? "ЗАДАЧА"
                : "ПЛАН · " + done() + " из " + items.size() + (revision > 1 ? " · ПЕРЕСТРОЕН" : "");
        y += text(g, mg, header, CAPTION, COPPER, PAD, y, textW, 1);
        if (fromMemory && g != null) {
            g.setFont(CAPTION);
            String badge = "ИЗ ПАМЯТИ";
            int bw = g.getFontMetrics().stringWidth(badge) + 12;
            g.setColor(AMBER);
            g.fill(new RoundRectangle2D.Double(W - PAD - bw, PAD - 3, bw, 18, 9, 9));
            g.setColor(BG);
            g.drawString(badge, W - PAD - bw + 6, PAD + g.getFontMetrics().getAscent() - 2);
        }
        y += 6;

        if (items.isEmpty()) {
            y += text(g, mg, goal, ROW, TITLE, PAD, y, textW, 3);
        } else {
            for (JarvisMessage.PlanItem it : items) {
                int rowTop = y;
                boolean running = it.status() == JarvisMessage.PlanItemStatus.RUNNING;
                Color c = switch (it.status()) {
                    case DONE -> TITLE;
                    case RUNNING -> TITLE;
                    case FAILED -> RED;
                    default -> DIM;
                };
                y += text(g, mg, it.title(), running ? ROW_BOLD : ROW, c, PAD + 22, y, textW - 22, 2);
                if (g != null) icon(g, it.status(), PAD + 7, rowTop + 9);
                if (it.note() != null && it.status() != JarvisMessage.PlanItemStatus.PENDING) {
                    y += text(g, mg, it.note(), NOTE, DIM, PAD + 22, y, textW - 22, 1);
                }
                y += ROW_GAP;
            }
        }

        // Разделитель и «мысли»
        if (!thought.isEmpty() || !action.isEmpty() || verify != null) {
            y += 4;
            if (g != null) {
                g.setColor(BORDER);
                g.fillRect(PAD, y, textW, 1);
            }
            y += 10;
            if (!thought.isEmpty()) {
                y += text(g, mg, "ДУМАЮ", CAPTION, COPPER, PAD, y, textW, 1);
                y += text(g, mg, thought, THOUGHT, TITLE, PAD, y, textW, 3);
                y += 4;
            }
            if (!action.isEmpty()) {
                y += text(g, mg, action, ROW_BOLD, ROSE, PAD, y, textW, 2);
            }
            if (verify != null) {
                y += 6;
                String head = switch (verifyState == null ? "" : verifyState) {
                    case "ok" -> "ПРОВЕРКА ПРОЙДЕНА";
                    case "fail" -> "ПРОВЕРКА НЕ ПРОЙДЕНА";
                    case "unknown" -> "ПРОВЕРИТЬ НЕ УДАЛОСЬ";
                    default -> "ПРОВЕРЯЮ РЕЗУЛЬТАТ";
                };
                Color hc = switch (verifyState == null ? "" : verifyState) {
                    case "ok" -> GREEN;
                    case "fail" -> RED;
                    default -> AMBER;
                };
                y += text(g, mg, head, CAPTION, hc, PAD, y, textW, 1);
                y += text(g, mg, verify, NOTE, TITLE, PAD, y, textW, 2);
            }
        }
        return y + PAD;
    }

    private int done() {
        return (int) items.stream().filter(i -> i.status() == JarvisMessage.PlanItemStatus.DONE).count();
    }

    /**
     * Текст с переносом по словам, не больше maxLines строк (лишнее — «…»).
     * @return занятая высота
     */
    private static int text(Graphics2D g, Graphics2D mg, String s, Font font, Color color,
                            int x, int y, int width, int maxLines) {
        if (s == null || s.isBlank()) return 0;
        FontMetrics fm = mg.getFontMetrics(font);
        List<String> lines = wrap(s.replaceAll("\\s+", " ").strip(), fm, width, maxLines);
        if (g != null) {
            g.setFont(font);
            g.setColor(color);
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(lines.get(i), x, y + fm.getAscent() + i * fm.getHeight());
            }
        }
        return lines.size() * fm.getHeight();
    }

    private static List<String> wrap(String s, FontMetrics fm, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
            if (out.size() == maxLines) break;
        }
        if (out.size() < maxLines && !line.isEmpty()) out.add(line.toString());
        if (out.size() == maxLines && fm.stringWidth(String.join(" ", out)) < fm.stringWidth(s)) {
            String last = out.get(maxLines - 1);
            while (!last.isEmpty() && fm.stringWidth(last + "…") > width) last = last.substring(0, last.length() - 1);
            out.set(maxLines - 1, last + "…");
        }
        return out;
    }

    /** Значок состояния — фигурами, а не символами шрифта (✓ в Segoe UI может не оказаться). */
    private void icon(Graphics2D g, JarvisMessage.PlanItemStatus status, int cx, int cy) {
        switch (status) {
            case DONE -> {
                g.setColor(GREEN);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Double();
                p.moveTo(cx - 5, cy);
                p.lineTo(cx - 1, cy + 4);
                p.lineTo(cx + 6, cy - 4);
                g.draw(p);
            }
            case RUNNING -> {
                // Пульсирующая точка: радиус дышит с периодом ~1 с.
                double t = (System.nanoTime() - shownAt) / 1e9;
                double r = 4 + 1.5 * Math.sin(t * 2 * Math.PI);
                g.setColor(ROSE);
                g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            }
            case FAILED -> {
                g.setColor(RED);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                g.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
            }
            case SKIPPED -> {
                g.setColor(DIM);
                g.setStroke(new BasicStroke(2f));
                g.drawLine(cx - 4, cy, cx + 4, cy);
            }
            default -> {
                g.setColor(DIM);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Ellipse2D.Double(cx - 4, cy - 4, 8, 8));
            }
        }
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
                double fade = easeOutCubic(progress((System.nanoTime() - shownAt) / 1e6, 0, FADE_MS));
                g.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(0.94 * fade)));
                g.setColor(BG);
                g.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 20, 20));
                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 20, 20));
                g.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(fade)));
                measure(g);
            } finally {
                g.dispose();
            }
        }
    }
}
