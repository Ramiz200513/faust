package com.jarvis.agent;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import static com.jarvis.agent.JarvisSwingTheme.*;

/**
 * Детали меню трея в стиле приложения на телефоне.
 *
 * Раньше значками служили символы шрифта (☎, </>, ●, ★, ↺, ≡): разного
 * размера и толщины, в случайных цветах, а в некоторых шрифтах Windows их нет
 * вовсе. Здесь значки нарисованы векторами в одном стиле — линия одной
 * толщины, цвет темы, плашка-подложка, — как иконки Material на телефоне.
 */
final class FaustMenu {

    private FaustMenu() {}

    /** Значки меню: рисуются в квадрате 18×18 линией одной толщины. */
    enum Glyph { PHONE, CODE, RECORD, SKILLS, ERASE, LOG, GEAR, POWER }

    /** Значок в плашке 30×30: подложка в цвет значка, сам значок по центру. */
    static Icon tileIcon(Glyph glyph, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
                g2.fill(new RoundRectangle2D.Double(x, y, 30, 30, 10, 10));
                g2.translate(x + 6, y + 6);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g2, glyph);
                g2.dispose();
            }

            @Override public int getIconWidth() { return 30; }
            @Override public int getIconHeight() { return 30; }
        };
    }

    private static void draw(Graphics2D g, Glyph glyph) {
        switch (glyph) {
            case PHONE -> {
                g.draw(new RoundRectangle2D.Double(4.5, 1.5, 9, 15, 3, 3));
                g.draw(new Line2D.Double(8, 13.5, 10, 13.5));
            }
            case CODE -> {
                Path2D l = new Path2D.Double();
                l.moveTo(6, 4.5); l.lineTo(1.5, 9); l.lineTo(6, 13.5);
                Path2D r = new Path2D.Double();
                r.moveTo(12, 4.5); r.lineTo(16.5, 9); r.lineTo(12, 13.5);
                g.draw(l);
                g.draw(r);
                g.draw(new Line2D.Double(10.2, 3.5, 7.8, 14.5));
            }
            case RECORD -> {
                g.draw(new Ellipse2D.Double(2, 2, 14, 14));
                g.fill(new Ellipse2D.Double(5.5, 5.5, 7, 7));
            }
            case SKILLS -> {
                // Искра: четырёхлучевая звезда и маленькая рядом — «выученное».
                g.fill(sparkle(8, 9.5, 7));
                g.fill(sparkle(14.5, 3.5, 3));
            }
            case ERASE -> {
                g.draw(new Arc2D.Double(2.5, 2.5, 13, 13, 100, 290, Arc2D.OPEN));
                Path2D head = new Path2D.Double();
                head.moveTo(6.5, 1); head.lineTo(7.8, 3.6); head.lineTo(5, 4.6);
                g.draw(head);
            }
            case LOG -> {
                g.draw(new RoundRectangle2D.Double(3.5, 1.5, 11, 15, 3, 3));
                g.draw(new Line2D.Double(6.5, 6, 11.5, 6));
                g.draw(new Line2D.Double(6.5, 9, 11.5, 9));
                g.draw(new Line2D.Double(6.5, 12, 9.5, 12));
            }
            case GEAR -> {
                g.draw(new Ellipse2D.Double(4.5, 4.5, 9, 9));
                g.draw(new Ellipse2D.Double(7.3, 7.3, 3.4, 3.4));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(9 + Math.cos(a) * 5.4, 9 + Math.sin(a) * 5.4,
                            9 + Math.cos(a) * 7.6, 9 + Math.sin(a) * 7.6));
                }
            }
            case POWER -> {
                g.draw(new Arc2D.Double(2.5, 3, 13, 13, 125, 290, Arc2D.OPEN));
                g.draw(new Line2D.Double(9, 1.5, 9, 8.5));
            }
        }
    }

    private static Path2D sparkle(double cx, double cy, double r) {
        Path2D p = new Path2D.Double();
        double k = r * 0.28;
        p.moveTo(cx, cy - r);
        p.quadTo(cx + k, cy - k, cx + r, cy);
        p.quadTo(cx + k, cy + k, cx, cy + r);
        p.quadTo(cx - k, cy + k, cx - r, cy);
        p.quadTo(cx - k, cy - k, cx, cy - r);
        p.closePath();
        return p;
    }

    /**
     * Пункт меню: значок в плашке, название и пояснение мелким шрифтом, справа —
     * необязательная метка (например, число навыков). Наведение — мягкая
     * скруглённая подсветка, а не синяя полоса во всю ширину.
     */
    static JMenuItem row(Glyph glyph, Color color, String title, String subtitle, String badge, Runnable action) {
        JMenuItem item = new JMenuItem(title) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g2.setColor(SURFACE_CONTAINER);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (getModel().isArmed()) {
                    g2.setColor(new Color(PRIMARY.getRed(), PRIMARY.getGreen(), PRIMARY.getBlue(), 30));
                    g2.fill(new RoundRectangle2D.Double(6, 2, getWidth() - 12, getHeight() - 4, 12, 12));
                }
                Icon icon = tileIcon(glyph, color);
                int iy = (getHeight() - icon.getIconHeight()) / 2;
                icon.paintIcon(this, g2, 14, iy);

                int tx = 14 + icon.getIconWidth() + 12;
                Font titleFont = font(Font.PLAIN, 13.5f);
                Font subFont = font(Font.PLAIN, 11.5f);
                FontMetrics tf = g2.getFontMetrics(titleFont);
                FontMetrics sf = g2.getFontMetrics(subFont);
                int textBlock = tf.getAscent() + (subtitle == null ? 0 : 2 + sf.getAscent());
                int ty = (getHeight() - textBlock) / 2 + tf.getAscent();
                g2.setFont(titleFont);
                // Полное имя: внутри Swing-компонента ERROR — это константа ImageObserver.
                g2.setColor(color == JarvisSwingTheme.ERROR ? JarvisSwingTheme.ERROR : ON_BACKGROUND);
                g2.drawString(title, tx, ty);
                if (subtitle != null) {
                    g2.setFont(subFont);
                    g2.setColor(ON_SURFACE_VARIANT);
                    g2.drawString(subtitle, tx, ty + 2 + sf.getAscent());
                }
                if (badge != null) {
                    Font bf = font(Font.BOLD, 11f);
                    FontMetrics bm = g2.getFontMetrics(bf);
                    int bw = bm.stringWidth(badge) + 14;
                    int bx = getWidth() - 16 - bw;
                    int by = (getHeight() - 20) / 2;
                    g2.setColor(new Color(PRIMARY.getRed(), PRIMARY.getGreen(), PRIMARY.getBlue(), 40));
                    g2.fill(new RoundRectangle2D.Double(bx, by, bw, 20, 20, 20));
                    g2.setColor(PRIMARY);
                    g2.setFont(bf);
                    g2.drawString(badge, bx + 7, by + (20 + bm.getAscent() - bm.getDescent()) / 2);
                }
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(310, subtitle == null ? 40 : 48);
            }
        };
        item.setOpaque(false);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Подпись раздела мелкими заглавными — как в меню приложения на телефоне. */
    static JComponent section(String title) {
        return new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g2.setColor(SURFACE_CONTAINER);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setFont(font(Font.BOLD, 10.5f));
                g2.setColor(ON_SURFACE_VARIANT);
                g2.drawString(title.toUpperCase(), 18, getHeight() - 7);
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(310, 26);
            }
        };
    }

    /** Тонкая линия между блоками — с отступами по краям, не во всю ширину. */
    static JComponent divider() {
        return new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(SURFACE_CONTAINER);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(OUTLINE);
                g.fillRect(14, getHeight() / 2, getWidth() - 28, 1);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(310, 11);
            }
        };
    }
}
