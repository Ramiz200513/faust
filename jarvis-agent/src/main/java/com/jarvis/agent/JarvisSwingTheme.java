package com.jarvis.agent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * Тема окон агента — та же палитра, что у мобильного приложения
 * (androidApp/.../Theme.kt, тёмный вариант по макету Jarvis Mobile App.pdf):
 * тёплый почти чёрный фон, мальва/пыльная роза на действиях, персиковый текст.
 *
 * Swing своей темы «как в Material» не умеет, поэтому здесь не Look-and-Feel,
 * а набор готовых деталей: кнопки-пилюли, поля со скруглением, карточки.
 * Окна собираются из них, а не из голых JButton/JTextField — иначе поверх
 * тёплого тёмного фона вылезают стандартные серые плашки Windows.
 */
final class JarvisSwingTheme {

    private JarvisSwingTheme() {}

    // --- палитра (дословно из DarkColors в Theme.kt) ---------------------

    static final Color BACKGROUND = new Color(0x161312);
    static final Color SURFACE_CONTAINER = new Color(0x211D1C);
    static final Color SURFACE_CONTAINER_HIGH = new Color(0x2A2524);
    static final Color ON_BACKGROUND = new Color(0xEDE1DD);
    static final Color ON_SURFACE_VARIANT = new Color(0xB8ACA8);
    static final Color PRIMARY = new Color(0xD29E9D);
    static final Color PRIMARY_CONTAINER = new Color(0xC79392);
    static final Color ON_PRIMARY_CONTAINER = new Color(0x2A1617);
    /** «В сети» / успех — зелёная, отдельно от розовой primary (см. onlineColor()). */
    static final Color TERTIARY = new Color(0x9FCB9F);
    static final Color ERROR = new Color(0xFFB4AB);
    static final Color OUTLINE = new Color(0x3D3634);

    private static final String FONT_FAMILY = "Segoe UI";
    /** Ширина текстовой колонки — уже окна на величину полей. */
    private static final int TEXT_WIDTH = 330;

    static Font font(int style, float size) {
        return new Font(FONT_FAMILY, style, Math.round(size));
    }

    /**
     * Шрифт, в котором точно есть нужный символ. В Segoe UI нет ни ✓, ни ◆ —
     * без этой проверки вместо значка рисуется пустой прямоугольник.
     */
    static Font glyphFont(int style, float size, char glyph) {
        Font preferred = font(style, size);
        if (preferred.canDisplay(glyph)) return preferred;
        for (String family : new String[] {"Segoe UI Symbol", "Segoe UI Emoji", "Dialog", "SansSerif"}) {
            Font alt = new Font(family, style, Math.round(size));
            if (alt.canDisplay(glyph)) return alt;
        }
        return preferred;
    }

    /** Крупный значок над заголовком карточки. */
    static JLabel glyph(String text, float size, Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(glyphFont(Font.PLAIN, size, text.charAt(0)));
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /**
     * Красит то, что Swing рисует сам и во что не пролезть конструктором:
     * JOptionPane (подтверждения, ошибки) и подсказки. Зовётся один раз при
     * старте агента — до создания первого окна.
     */
    static void applyGlobals() {
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("OptionPane.messageForeground", ON_BACKGROUND);
        UIManager.put("OptionPane.messageFont", font(Font.PLAIN, 13f));
        UIManager.put("OptionPane.buttonFont", font(Font.PLAIN, 13f));
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Panel.foreground", ON_BACKGROUND);
        UIManager.put("Label.foreground", ON_BACKGROUND);
        UIManager.put("Button.background", SURFACE_CONTAINER_HIGH);
        UIManager.put("Button.foreground", ON_BACKGROUND);
        UIManager.put("ToolTip.background", SURFACE_CONTAINER_HIGH);
        UIManager.put("ToolTip.foreground", ON_BACKGROUND);
        // Меню трея — своё, тёмное (см. AgentTray): те же цвета, что у окон.
        UIManager.put("PopupMenu.background", SURFACE_CONTAINER);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(OUTLINE));
        UIManager.put("MenuItem.background", SURFACE_CONTAINER);
        UIManager.put("MenuItem.foreground", ON_BACKGROUND);
        UIManager.put("MenuItem.selectionBackground", SURFACE_CONTAINER_HIGHEST);
        UIManager.put("MenuItem.selectionForeground", ON_BACKGROUND);
        UIManager.put("MenuItem.font", font(Font.PLAIN, 13f));
        UIManager.put("MenuItem.border", new EmptyBorder(7, 6, 7, 18));
        UIManager.put("MenuItem.borderPainted", Boolean.FALSE);
        UIManager.put("Separator.foreground", OUTLINE);
        UIManager.put("Separator.background", SURFACE_CONTAINER);
        UIManager.put("PopupMenuSeparator.foreground", OUTLINE);
        UIManager.put("PopupMenuSeparator.background", SURFACE_CONTAINER);
        UIManager.put("ScrollBar.thumb", SURFACE_CONTAINER_HIGHEST);
        UIManager.put("ScrollBar.track", BACKGROUND);
    }

    /** Ещё на ступень светлее — подсветка пункта меню под мышью. */
    static final Color SURFACE_CONTAINER_HIGHEST = new Color(0x342E2C);
    /** Самый тёмный — фон блоков кода и диффа. */
    static final Color SURFACE_LOWEST = new Color(0x110E0D);

    /**
     * Абзац с переносом по ширине и выравниванием по левому краю — для карточек,
     * где текст длинный (цель задачи, список файлов), и центрирование мешает читать.
     */
    static JLabel bodyLeft(String text, int width, Color color, float size) {
        Font font = font(Font.PLAIN, size);
        JLabel label = new JLabel();
        label.setFont(font);
        StringBuilder html = new StringBuilder("<html>");
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) html.append("<br>");
            html.append(wrap(escapeHtml(lines[i]), font, width));
        }
        label.setText(html.append("</html>").toString());
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** Текст для HTML-подписи Swing: угловые скобки и амперсанды — как есть, не как теги. */
    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Мелкая надпись-заголовок раздела капителью: «ЗАПРОС С ТЕЛЕФОНА». */
    static JLabel overline(String text, Color color) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(font(Font.BOLD, 11f));
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    // --- готовые детали ---------------------------------------------------

    /** Вертикальная колонка на тёплом фоне — основа любой карточки окна. */
    static JPanel column(int padding) {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBackground(BACKGROUND);
        panel.setBorder(new EmptyBorder(padding, padding, padding, padding));
        return panel;
    }

    static JLabel title(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(font(Font.BOLD, 18f));
        label.setForeground(ON_BACKGROUND);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /**
     * Абзац под заголовком: приглушённый цвет, строки разбиты по ширине
     * колонки. Переносы считаем сами по FontMetrics, а не отдаём CSS: ширина
     * в style HTML-рендерером JLabel здесь не соблюдается, и длинная
     * строка просто уезжает за край окна.
     *
     * @param text текст; "

" — пустая строка между абзацами
     */
    static JLabel body(String text) {
        Font font = font(Font.PLAIN, 13f);
        JLabel label = new JLabel();
        label.setFont(font);
        label.setText("<html><center>" + wrap(text, font, TEXT_WIDTH) + "</center></html>");
        label.setForeground(ON_SURFACE_VARIANT);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /** Разбивает текст на строки не шире maxWidth и склеивает через &lt;br&gt;. */
    private static String wrap(String text, Font font, int maxWidth) {
        FontMetrics metrics = new java.awt.Canvas().getFontMetrics(font);
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.split("\n\n", -1)) {
            if (out.length() > 0) out.append("<br><br>");
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0) {
                    out.append(line).append("<br>");
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            out.append(line);
        }
        return out.toString();
    }

    /** Строка статуса — пустая по умолчанию, цвет меняется на месте. */
    static JLabel status() {
        JLabel label = new JLabel(" ", SwingConstants.CENTER);
        label.setFont(font(Font.PLAIN, 12f));
        label.setForeground(ON_SURFACE_VARIANT);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /** Главное действие: залитая пилюля цвета primaryContainer. */
    static JButton filledButton(String text) {
        return new PillButton(text, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER, null);
    }

    /** Второстепенное действие: контур, без заливки. */
    static JButton outlinedButton(String text) {
        return new PillButton(text, null, PRIMARY, OUTLINE);
    }

    /** Третьестепенное («Позже», «Назад»): только текст. */
    static JButton textButton(String text) {
        return new PillButton(text, null, ON_SURFACE_VARIANT, null);
    }

    static JTextField field(String value, int width, boolean big) {
        JTextField field = new JTextField(value);
        field.setBackground(SURFACE_CONTAINER_HIGH);
        field.setForeground(ON_BACKGROUND);
        field.setCaretColor(PRIMARY);
        field.setHorizontalAlignment(big ? SwingConstants.CENTER : SwingConstants.LEFT);
        field.setFont(big ? font(Font.BOLD, 22f) : font(Font.PLAIN, 12f));
        field.setBorder(new RoundedBorder(OUTLINE, 14, big ? 8 : 6, 12));
        int height = big ? 44 : 32;
        field.setMaximumSize(new Dimension(width, height));
        field.setPreferredSize(new Dimension(width, height));
        field.setAlignmentX(Component.CENTER_ALIGNMENT);
        return field;
    }

    static void style(JComboBox<?> box) {
        box.setBackground(SURFACE_CONTAINER_HIGH);
        box.setForeground(ON_BACKGROUND);
        box.setFont(font(Font.PLAIN, 12f));
        box.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.setBorder(new RoundedBorder(OUTLINE, 12, 2, 8));
    }

    /**
     * Скруглённая плашка surfaceContainer — под QR-код и прочие «карточки»
     * (так же, как Surface(shape = RoundedCornerShape(16.dp)) в приложении).
     */
    static JPanel card(Component content, int padding) {
        JPanel panel = new RoundedPanel(SURFACE_CONTAINER, 16);
        panel.setLayout(new java.awt.BorderLayout());
        panel.setBorder(new EmptyBorder(padding, padding, padding, padding));
        panel.add(content, java.awt.BorderLayout.CENTER);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        return panel;
    }

    /** Отступ между элементами колонки. */
    static Component gap(int height) {
        return javax.swing.Box.createRigidArea(new Dimension(1, height));
    }

    // --- внутренности -----------------------------------------------------

    /** Кнопка-пилюля: заливка и/или контур, скругление на всю высоту. */
    private static final class PillButton extends JButton {
        private final Color fill;
        private final Color line;

        PillButton(String text, Color fill, Color foreground, Color line) {
            super(text);
            this.fill = fill;
            this.line = line;
            setForeground(foreground);
            setFont(font(Font.BOLD, 13f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(9, 20, 9, 20));
            setAlignmentX(Component.CENTER_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = getHeight();
            boolean pressed = getModel().isArmed() && getModel().isPressed();
            boolean hover = getModel().isRollover();

            if (fill != null) {
                Color c = isEnabled() ? shade(fill, pressed ? -0.18f : (hover ? 0.08f : 0f)) : dim(fill);
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } else if (hover && isEnabled()) {
                // Лёгкая подсветка вместо заливки — как ripple у контурных кнопок.
                g2.setColor(SURFACE_CONTAINER_HIGH);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            }
            if (line != null) {
                g2.setColor(isEnabled() ? line : dim(line));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override
        public Color getForeground() {
            Color base = super.getForeground();
            return (base != null && !isEnabled()) ? dim(base) : base;
        }
    }

    /** Панель со скруглёнными углами и собственной заливкой. */
    private static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final int arc;

        RoundedPanel(Color fill, int arc) {
            this.fill = fill;
            this.arc = arc;
            setOpaque(false);
            setBackground(fill);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Контур со скруглением для полей ввода. */
    private static final class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int arc;
        private final int padV;
        private final int padH;

        RoundedBorder(Color color, int arc, int padV, int padH) {
            this.color = color;
            this.arc = arc;
            this.padV = padV;
            this.padH = padH;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(padV, padH, padV, padH);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(padV, padH, padV, padH);
            return insets;
        }
    }

    /** Осветлить/затемнить цвет на долю (для наведения и нажатия). */
    private static Color shade(Color base, float amount) {
        if (amount == 0f) return base;
        int r = clamp(Math.round(base.getRed() * (1 + amount)));
        int g = clamp(Math.round(base.getGreen() * (1 + amount)));
        int b = clamp(Math.round(base.getBlue() * (1 + amount)));
        return new Color(r, g, b);
    }

    /** Приглушённый вариант — для выключенных кнопок. */
    private static Color dim(Color base) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 90);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Общая настройка окна: тёплый фон и отсутствие системной серости. */
    static void styleWindow(JComponent contentPane) {
        contentPane.setBackground(BACKGROUND);
        contentPane.setOpaque(true);
    }
}
