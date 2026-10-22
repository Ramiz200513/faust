package com.jarvis.agent;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

import static com.jarvis.agent.JarvisSwingTheme.*;

/**
 * Современные окна агента: без системной рамки Windows, со скруглёнными углами,
 * мягкой тенью и тонкой обводкой — как карточки в приложении на телефоне.
 *
 * <h2>Как это сделано</h2>
 * Окно прозрачное целиком ({@code setBackground} с нулевой альфой — Windows это
 * умеет), а «карточку» со скруглением и тенью рисует сама панель. Так края
 * сглажены: окно, обрезанное по форме ({@code setShape}), получило бы
 * зубчатые углы. Раз рамки нет, окно перетаскивается за любое пустое место,
 * а Esc работает как «закрыть» — у каждого окна это своё действие.
 */
final class FaustWindows {

    private FaustWindows() {}

    /** Ширина тени вокруг карточки, px. */
    static final int SHADOW = 14;
    /** Скругление карточки окна. */
    static final int RADIUS = 22;

    /**
     * Окно-карточка.
     *
     * @param title   заголовок окна для панели задач и Alt+Tab (на самом окне его не видно)
     * @param content содержимое — обычно колонка из JarvisSwingTheme
     * @param onEsc   что делать по Esc (для подтверждения — «отклонить»)
     */
    static JDialog cardDialog(String title, JComponent content, boolean modal, Runnable onEsc) {
        JDialog dialog = new JDialog((Frame) null, title, modal);
        dialog.setUndecorated(true);
        dialog.setBackground(new Color(0, 0, 0, 0));
        CardSurface surface = new CardSurface();
        surface.add(content, BorderLayout.CENTER);
        dialog.setContentPane(surface);
        draggable(surface, dialog);
        if (onEsc != null) {
            surface.registerKeyboardAction(e -> onEsc.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
        return dialog;
    }

    /**
     * Тёмный заголовок окна Windows (Windows 10 20H1+ и 11) — в тон окну, а не
     * белая системная полоса над тёмным содержимым. Делается вызовом
     * DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE). На старой Windows
     * или не на Windows вызов просто ничего не делает.
     */
    static void darkTitleBar(java.awt.Window window) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
        Runnable apply = () -> {
            try {
                com.sun.jna.Pointer hwnd = com.sun.jna.Native.getWindowPointer(window);
                if (hwnd == null) return;
                com.sun.jna.ptr.IntByReference on = new com.sun.jna.ptr.IntByReference(1);
                // 20 — DWMWA_USE_IMMERSIVE_DARK_MODE; 19 — то же в ранних сборках Windows 10.
                if (Dwm.INSTANCE.DwmSetWindowAttribute(new com.sun.jna.platform.win32.WinDef.HWND(hwnd), 20, on, 4) != 0) {
                    Dwm.INSTANCE.DwmSetWindowAttribute(new com.sun.jna.platform.win32.WinDef.HWND(hwnd), 19, on, 4);
                }
            } catch (Throwable ignored) {
                // нет dwmapi или JNA — останется обычный заголовок, это не ошибка
            }
        };
        if (window.isDisplayable()) {
            apply.run();
        } else {
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowOpened(java.awt.event.WindowEvent e) {
                    apply.run();
                }
            });
        }
    }

    private interface Dwm extends com.sun.jna.win32.StdCallLibrary {
        Dwm INSTANCE = com.sun.jna.Native.load("dwmapi", Dwm.class);

        int DwmSetWindowAttribute(com.sun.jna.platform.win32.WinDef.HWND hwnd, int attribute,
                                  com.sun.jna.ptr.IntByReference value, int size);
    }

    /** Перетаскивать окно без рамки за любое место фона. */
    static void draggable(JComponent handle, java.awt.Window window) {
        Point[] grab = {null};
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                grab[0] = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grab[0] == null) return;
                Point at = window.getLocation();
                window.setLocation(at.x + e.getX() - grab[0].x, at.y + e.getY() - grab[0].y);
            }
        };
        handle.addMouseListener(drag);
        handle.addMouseMotionListener(drag);
    }

    /** Фон окна: тень, скруглённая карточка, тонкая обводка, едва заметный блик сверху. */
    static final class CardSurface extends JPanel {
        CardSurface() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(new EmptyBorder(SHADOW, SHADOW, SHADOW, SHADOW));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            // Тень — несколько расширяющихся полупрозрачных контуров: дёшево и мягко.
            for (int i = SHADOW; i > 0; i--) {
                float a = 0.22f * (1f - (float) i / SHADOW) * (1f - (float) i / SHADOW);
                g2.setColor(new Color(0f, 0f, 0f, a));
                g2.fill(new RoundRectangle2D.Float(SHADOW - i, SHADOW - i + 3, w - 2 * (SHADOW - i), h - 2 * (SHADOW - i),
                        RADIUS + i, RADIUS + i));
            }
            RoundRectangle2D card = new RoundRectangle2D.Float(SHADOW, SHADOW, w - 2 * SHADOW, h - 2 * SHADOW, RADIUS, RADIUS);
            g2.setColor(BACKGROUND);
            g2.fill(card);
            // Блик: верх карточки чуть светлее — даёт объём без явного градиента.
            g2.setClip(card);
            g2.setPaint(new java.awt.GradientPaint(0, SHADOW, new Color(255, 240, 235, 14), 0, SHADOW + 90,
                    new Color(255, 240, 235, 0)));
            g2.fillRect(SHADOW, SHADOW, w - 2 * SHADOW, 90);
            g2.setClip(null);
            g2.setColor(OUTLINE);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(SHADOW + 0.5f, SHADOW + 0.5f, w - 2 * SHADOW - 1, h - 2 * SHADOW - 1,
                    RADIUS, RADIUS));
            g2.dispose();
        }
    }

    /** Логотип «Фауст» как иконка (для шапок окон и меню). */
    static Icon logo(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                FaustLogoPainter.paint((Graphics2D) g, x + size / 2.0, y + size / 2.0, size, 0, 0, 1, 1, 1f, true);
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /** Цветная точка — статус в меню и карточках. */
    static Icon dot(Color color, int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
                g2.fillOval(x, y, size, size);
                g2.setColor(color);
                g2.fillOval(x + size / 4, y + size / 4, size / 2, size / 2);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /** Значок-символ (✓, ⟳, ⌫…) фиксированной ширины — чтобы пункты меню стояли ровной колонкой. */
    static Icon glyphIcon(String glyph, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(glyphFont(Font.PLAIN, 14f, glyph.charAt(0)));
                g2.setColor(color);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                g2.drawString(glyph, x + (18 - fm.stringWidth(glyph)) / 2, y + (18 + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 18;
            }

            @Override
            public int getIconHeight() {
                return 18;
            }
        };
    }

    /**
     * Полоса обратного отсчёта: тает слева направо, в последние секунды
     * краснеет. Перерисовывается плавно (см. {@link #setFraction}), а не рывком
     * раз в секунду — так видно, что окно «живое».
     */
    static final class CountdownBar extends JComponent {
        private float fraction = 1f;

        CountdownBar(int width) {
            setPreferredSize(new Dimension(width, 4));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(SURFACE_CONTAINER_HIGH);
            g2.fillRoundRect(0, 0, w, h, h, h);
            g2.setColor(fraction < 0.25f ? JarvisSwingTheme.ERROR : PRIMARY);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.fillRoundRect(0, 0, Math.round(w * fraction), h, h, h);
            g2.dispose();
        }
    }

    /** Пилюля со значком слева (значки рисуем сами: в Segoe UI нет ни ✓, ни ⎇). */
    static JComponent chip(String text, Color color, Icon icon) {
        JComponent c = chip(text, color);
        ((javax.swing.JLabel) c).setIcon(icon);
        ((javax.swing.JLabel) c).setIconTextGap(5);
        return c;
    }

    /** Значок ветки git: два кружка и ответвление. */
    static Icon branchIcon(Color color) {
        return painted(12, (g2, x, y) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 3, y + 3, x + 3, y + 10);
            g2.drawOval(x + 1, y, 4, 4);
            g2.drawOval(x + 7, y + 3, 4, 4);
            g2.draw(new java.awt.geom.QuadCurve2D.Float(x + 9, y + 7, x + 9, y + 10, x + 3, y + 10));
        });
    }

    /** Галочка. */
    static Icon checkIcon(Color color) {
        return painted(12, (g2, x, y) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawPolyline(new int[]{x + 1, x + 4, x + 11}, new int[]{y + 6, y + 9, y + 2}, 3);
        });
    }

    private interface Painter {
        void paint(Graphics2D g2, int x, int y);
    }

    private static Icon painted(int size, Painter p) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                p.paint(g2, x, y);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /** «Пилюля»-метка: цветной текст на полупрозрачной подложке того же цвета (как на телефоне). */
    static JComponent chip(String text, Color color) {
        javax.swing.JLabel label = new javax.swing.JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 38));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setFont(font(Font.BOLD, 11.5f));
        label.setForeground(color);
        label.setBorder(new EmptyBorder(3, 10, 3, 10));
        return label;
    }
}
