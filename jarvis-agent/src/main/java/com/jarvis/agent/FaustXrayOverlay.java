package com.jarvis.agent;

import com.jarvis.agent.vision.UiaElement;

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
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import static com.jarvis.agent.Easing.clamp01;
import static com.jarvis.agent.Easing.easeOutCubic;
import static com.jarvis.agent.Easing.progress;

/**
 * «Рентген» — показывает, КАК агент видит окно программы.
 *
 * <p>В режиме UI Automation модель получает не картинку, а пронумерованный
 * список элементов: {@code [12] <button> "Отправить"}. Здесь этот самый
 * список рисуется поверх окна: рамка вокруг каждого элемента и его номер.
 * Перед кликом выбранная моделью рамка вспыхивает — и зритель видит, что
 * агент не «угадал пиксель», а выбрал элемент из списка, который ему дала
 * Windows. Это главная научная идея режима UIA, показанная глазами.
 *
 * <h2>Жизненный цикл на одном шаге</h2>
 * <pre>
 *   снимок окна       -> setElements  (рамки проявляются за 250 мс; модель «смотрит»)
 *   модель ответила   -> setTarget    (выбранная рамка вспыхивает)
 *   действие сделано  -> clear        (окно сейчас изменится — старые рамки убираем)
 * </pre>
 *
 * <h2>Цена</h2>
 * Окно на весь экран, а прозрачные окна в Java дорого перерисовывать. Поэтому
 * таймер тикает ТОЛЬКО пока что-то движется (проявление, вспышка) — меньше
 * секунды на шаг, — а остальное время окно просто висит и ничего не стоит.
 * Как и все оверлеи, окно прозрачно для мыши и скрыто от снимков экрана:
 * модель свои же рамки никогда не увидит.
 */
final class FaustXrayOverlay {

    private static final Color COPPER = new Color(0xC8845A);
    private static final Color ROSE = new Color(0xE0A0A0);
    private static final Color TAG_BG = new Color(0x1E1817);
    private static final Color TAG_TEXT = new Color(0xF3E7DA);
    private static final Font TAG_FONT = new Font("Segoe UI", Font.BOLD, 11);
    private static final Font LABEL_FONT = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font CAPTION_FONT = new Font("Segoe UI", Font.PLAIN, 13);

    private static final double FADE_IN_MS = 250;
    private static final double FADE_OUT_MS = 180;
    private static final double PULSE_MS = 900;
    private static final int FRAME_MS = 33;   // 30 кадров/с — только пока идёт анимация

    /** Одна рамка: номер из списка модели, прямоугольник, подпись. */
    private record Box(int index, Rectangle r, String name) {}

    private final Rectangle screen;
    private final JWindow window;
    private final Timer timer;

    private List<Box> boxes = List.of();
    private String windowTitle = "";
    private int target = -1;
    private long shownAt;
    private long targetAt;
    private long hiddenAt;   // 0 — не прячемся

    FaustXrayOverlay() {
        screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setBounds(screen);
        window.setContentPane(new Canvas());
        timer = new Timer(FRAME_MS, e -> tick());
    }

    /** Новый снимок окна: показать все элементы. Вызывать из потока Swing. */
    void setElements(String title, List<UiaElement> elements) {
        List<Box> list = new ArrayList<>();
        for (UiaElement e : elements) {
            Rectangle r = new Rectangle(e.x(), e.y(), e.width(), e.height());
            if (r.width < 4 || r.height < 4 || !screen.intersects(r)) continue;
            list.add(new Box(e.index(), r, e.name()));
        }
        boxes = list;
        windowTitle = title == null ? "" : title;
        target = -1;
        hiddenAt = 0;
        shownAt = System.nanoTime();
        if (!window.isVisible()) {
            window.setVisible(true);
            // Декоративное: клики насквозь, на снимках не видно (см. AgentOverlays).
            AgentOverlays.register(window, true);
        }
        timer.start();
    }

    /** Модель выбрала элемент: его рамка вспыхивает, остальные тускнеют. */
    void setTarget(int index) {
        target = index;
        targetAt = System.nanoTime();
        timer.start();
        window.repaint();
    }

    /** Действие выполнено — окно программы сейчас изменится, старые рамки убираем. */
    void clear() {
        if (boxes.isEmpty() || hiddenAt != 0) return;
        hiddenAt = System.nanoTime();
        timer.start();
    }

    /** Задача закончилась. */
    void close() {
        timer.stop();
        AgentOverlays.unregister(window);
        window.dispose();
    }

    private static double sinceMs(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000.0;
    }

    private void tick() {
        window.repaint();
        boolean fadingIn = sinceMs(shownAt) < FADE_IN_MS;
        boolean pulsing = target >= 0 && sinceMs(targetAt) < PULSE_MS;
        boolean fadingOut = hiddenAt != 0 && sinceMs(hiddenAt) < FADE_OUT_MS;
        if (hiddenAt != 0 && !fadingOut) {
            boxes = List.of();
            hiddenAt = 0;
            window.repaint();
        }
        if (!fadingIn && !pulsing && !fadingOut) timer.stop();   // движения нет — таймер не нужен
    }

    private final class Canvas extends JPanel {
        Canvas() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (boxes.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.translate(-screen.x, -screen.y);   // рисуем в координатах экрана

                double fade = easeOutCubic(progress(sinceMs(shownAt), 0, FADE_IN_MS));
                if (hiddenAt != 0) fade *= 1 - clamp01(sinceMs(hiddenAt) / FADE_OUT_MS);

                Box chosen = null;
                for (Box b : boxes) {
                    if (b.index() == target) {
                        chosen = b;
                        continue;
                    }
                    // Когда модель уже выбрала, остальные рамки уходят на второй план.
                    double a = fade * (target >= 0 ? 0.30 : 0.85);
                    paintBox(g2, b, a);
                }
                if (chosen != null) paintTarget(g2, chosen, fade);
                paintCaption(g2, fade);
            } finally {
                g2.dispose();
            }
        }

        private void paintBox(Graphics2D g2, Box b, double alpha) {
            g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(alpha)));
            g2.setColor(COPPER);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(new RoundRectangle2D.Double(b.r().x, b.r().y, b.r().width, b.r().height, 6, 6));
            paintTag(g2, b, String.valueOf(b.index()), TAG_BG, TAG_TEXT, TAG_FONT);
        }

        /** Выбранный элемент: заливка, пульсирующая толстая рамка и подпись «▶ Отправить». */
        private void paintTarget(Graphics2D g2, Box b, double fade) {
            double t = clamp01(sinceMs(targetAt) / PULSE_MS);
            // Два «удара сердца» за время вспышки, потом ровное свечение.
            double beat = t < 1 ? Math.abs(Math.sin(t * Math.PI * 2)) : 0.4;
            Rectangle r = b.r();
            int grow = (int) Math.round(4 * beat);

            g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(0.20 * fade)));
            g2.setColor(COPPER);
            g2.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 8, 8));

            g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(fade)));
            g2.setColor(ROSE);
            g2.setStroke(new BasicStroke(2.5f + 2.5f * (float) beat));
            g2.draw(new RoundRectangle2D.Double(r.x - grow, r.y - grow, r.width + 2 * grow, r.height + 2 * grow, 10, 10));

            // Без значков вроде ▶: в Segoe UI их может не оказаться, и вместо
            // стрелки на экране был бы квадратик. Слова надёжнее.
            String label = "выбор модели: [" + b.index() + "] " + shortName(b.name());
            paintTag(g2, b, label, COPPER, Color.WHITE, LABEL_FONT);
        }

        /** Номер элемента — маленькая плашка над левым верхним углом рамки. */
        private void paintTag(Graphics2D g2, Box b, String text, Color bg, Color fg, Font font) {
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int w = fm.stringWidth(text) + 8, h = fm.getHeight();
            int x = b.r().x;
            int y = b.r().y - h - 1;
            if (y < screen.y) y = b.r().y + 1;   // у верхнего края экрана — внутрь рамки
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, 6, 6));
            g2.setColor(fg);
            g2.drawString(text, x + 4, y + fm.getAscent());
        }

        /** Пояснение для зрителей в левом нижнем углу. */
        private void paintCaption(Graphics2D g2, double fade) {
            String text = "Так ИИ видит окно «" + shortName(windowTitle) + "»: " + boxes.size()
                          + " элементов от Windows, выбирает номер";
            g2.setFont(CAPTION_FONT);
            FontMetrics fm = g2.getFontMetrics();
            int w = fm.stringWidth(text) + 28, h = fm.getHeight() + 14;
            int x = screen.x + 24, y = screen.y + screen.height - h - 70;
            g2.setComposite(AlphaComposite.SrcOver.derive((float) clamp01(0.92 * fade)));
            g2.setColor(TAG_BG);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
            g2.setColor(TAG_TEXT);
            g2.drawString(text, x + 14, y + 7 + fm.getAscent());
        }
    }

    private static String shortName(String name) {
        if (name == null) return "";
        int comma = name.indexOf(',');
        String s = (comma > 0 ? name.substring(0, comma) : name).replace("‎", "").strip();
        return s.length() <= 40 ? s : s.substring(0, 39) + "…";
    }
}
