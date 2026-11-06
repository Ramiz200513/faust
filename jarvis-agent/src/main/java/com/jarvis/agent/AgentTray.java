package com.jarvis.agent;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import com.jarvis.agent.vision.TrajectoryCache;

import static com.jarvis.agent.JarvisSwingTheme.*;

/**
 * Значок агента в системном трее и его меню.
 *
 * <p>Значок — логотип «Фауста» с точкой статуса связи (зелёная — подключено,
 * серая — нет связи, красная — ПК отвязан); рисуется в рантайме, файлов-иконок
 * нет. Меню — своё, тёмное, в цветах приложения: системное меню трея (AWT
 * PopupMenu) нельзя ни покрасить, ни снабдить значками, и рядом с остальными
 * окнами «Фауста» оно выглядело чужим. В шапке меню — статус и текущая задача.
 */
public class AgentTray implements RelayStatusListener {

    /** Статус, который показываем пользователю. */
    public enum Status {
        CONNECTING(new Color(0xB8ACA8), "подключение…"),
        CONNECTED(new Color(0x3FB950), "подключено"),
        DISCONNECTED(new Color(0x8A807C), "нет связи с релеем"),
        REJECTED(new Color(0xF85149), "ПК отвязан");

        final Color color;
        final String label;

        Status(Color color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    private static final String APP_NAME = "Faust Agent";

    private final Path logFile;
    private final Runnable onPairPhone;
    private final Runnable onResetPairing;
    private TrayIcon trayIcon;
    private volatile Status currentStatus = Status.CONNECTING;
    private volatile String connDetail;
    /** Что сейчас делает agent_task ("выполняю: <цель> (шаг N)"), или null — простаивает. */
    private volatile String taskLabel;
    /** Пункты «Научить навыку показом…» и «Выученные навыки…» — задаёт AgentMain. */
    private volatile Runnable onTeach = () -> { };
    private volatile Runnable onManageSkills = () -> { };
    /** Открытое меню — чтобы не открыть второе поверх. */
    private JDialog menuHost;

    /** Подключить обучение навыкам (SkillTeacher создаётся позже трея — ему нужен CommandExecutor). */
    public void setSkillActions(Runnable teach, Runnable manage) {
        this.onTeach = teach == null ? () -> { } : teach;
        this.onManageSkills = manage == null ? () -> { } : manage;
    }

    public AgentTray(Path logFile, Runnable onPairPhone, Runnable onResetPairing) {
        this.logFile = logFile;
        this.onPairPhone = onPairPhone;
        this.onResetPairing = onResetPairing;
    }

    /** Добавляет значок в трей. Ничего не делает, если трей на этой системе недоступен. */
    public void install() {
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray недоступен на этой системе — работаем без значка в трее.");
            return;
        }
        trayIcon = new TrayIcon(renderIcon(Status.CONNECTING), APP_NAME);
        trayIcon.setImageAutoSize(true);
        // Меню — по любому щелчку: правым, как принято, и левым — так его находят
        // те, кто не привык к контекстным меню трея.
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                Point at = e.getLocationOnScreen() != null ? e.getLocationOnScreen() : e.getPoint();
                SwingUtilities.invokeLater(() -> showMenu(at));
            }
        });
        applyStatus(Status.CONNECTING, null);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            System.err.println("Не удалось добавить значок в трей: " + e.getMessage());
        }
    }

    // --- меню ------------------------------------------------------------------

    /** Пункты меню — один список и для трея, и для предпросмотра картинкой. */
    private JPopupMenu buildMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(SURFACE_CONTAINER);
        menu.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(OUTLINE),
                new EmptyBorder(4, 0, 8, 0)));
        menu.add(header());
        menu.add(FaustMenu.divider());

        // Разделы с подписями — как меню приложения на телефоне: сразу видно,
        // где связь с телефоном, где навыки, а где служебное.
        menu.add(FaustMenu.section("Телефон и работа"));
        menu.add(FaustMenu.row(FaustMenu.Glyph.PHONE, PRIMARY, "Привязать телефон",
                "QR-код для приложения «Фауст»", null, onPairPhone));
        menu.add(FaustMenu.row(FaustMenu.Glyph.CODE, PRIMARY, "Проекты и git",
                "статус, коммит и push — и с телефона", null, ProjectsWindow::open));

        menu.add(FaustMenu.section("Навыки"));
        menu.add(FaustMenu.row(FaustMenu.Glyph.RECORD, PRIMARY, "Научить показом",
                "покажите раз — повторит с новыми данными", null, onTeach));
        int skills = SkillStore.all().size();
        menu.add(FaustMenu.row(FaustMenu.Glyph.SKILLS, PRIMARY, "Выученные навыки",
                skills == 0 ? "пока ни одного" : "посмотреть и удалить",
                skills == 0 ? null : String.valueOf(skills), onManageSkills));
        menu.add(FaustMenu.row(FaustMenu.Glyph.ERASE, ON_SURFACE_VARIANT, "Забыть выученные задачи",
                "память повторов без ИИ", null, this::forgetLearnedTasks));

        menu.add(FaustMenu.divider());
        menu.add(FaustMenu.row(FaustMenu.Glyph.LOG, ON_SURFACE_VARIANT, "Открыть журнал", null, null, this::openLog));
        menu.add(FaustMenu.row(FaustMenu.Glyph.GEAR, ON_SURFACE_VARIANT, "Настроить заново", null, null, onResetPairing));
        menu.add(FaustMenu.row(FaustMenu.Glyph.POWER, ERROR, "Выход", null, null, () -> System.exit(0)));
        return menu;
    }

    /** Шапка меню: логотип, имя, статус связи и текущая задача. */
    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBackground(SURFACE_CONTAINER);
        p.setBorder(new EmptyBorder(12, 16, 12, 18));
        p.add(new JLabel(FaustWindows.logo(42)), BorderLayout.WEST);
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        JLabel name = new JLabel("Фауст");
        name.setFont(font(Font.BOLD, 16f));
        name.setForeground(ON_BACKGROUND);
        text.add(name);
        text.add(javax.swing.Box.createVerticalStrut(3));
        JLabel status = new JLabel(currentStatus.label + (connDetail == null || connDetail.isBlank() ? "" : " · " + connDetail),
                FaustWindows.dot(currentStatus.color, 10), JLabel.LEFT);
        status.setIconTextGap(6);
        status.setFont(font(Font.PLAIN, 12f));
        status.setForeground(currentStatus == Status.CONNECTED ? ON_BACKGROUND : ON_SURFACE_VARIANT);
        text.add(status);
        if (taskLabel != null) {
            text.add(javax.swing.Box.createVerticalStrut(2));
            JLabel task = new JLabel(shorten(taskLabel, 42));
            task.setFont(font(Font.PLAIN, 12f));
            task.setForeground(PRIMARY);
            text.add(task);
        }
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    /**
     * Показать меню у значка. Swing-меню нужно окно-хозяин: берём невидимое
     * окошко в точке щелчка. Меню открывается над курсором (панель задач
     * обычно внизу), а если сверху не помещается — под ним.
     */
    private void showMenu(Point at) {
        if (menuHost != null) {
            menuHost.dispose();
            menuHost = null;
        }
        JPopupMenu menu = buildMenu();
        JDialog host = new JDialog();
        host.setUndecorated(true);
        host.setType(Window.Type.UTILITY);
        host.setAlwaysOnTop(true);
        host.setSize(1, 1);
        host.setLocation(at);
        menuHost = host;
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { host.dispose(); }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { host.dispose(); }
        });
        // Щелчок мимо меню — host теряет фокус, меню закрывается.
        host.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                menu.setVisible(false);
            }
        });
        host.setVisible(true);
        Dimension d = menu.getPreferredSize();
        GraphicsConfiguration gc = host.getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        java.awt.Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int x = Math.min(0, screen.x + screen.width - insets.right - at.x - d.width);
        int y = at.y - d.height >= screen.y + insets.top ? -d.height : 0;
        menu.show(host, x, y);
        host.toFront();
    }

    /** Меню картинкой — для UiPreview (без трея и без показа). */
    static JPopupMenu previewMenu() {
        AgentTray t = new AgentTray(Path.of("agent.log"), () -> { }, () -> { });
        t.currentStatus = Status.CONNECTED;
        t.taskLabel = "выполняю: закоммить и запушь демо (шаг 2)";
        JPopupMenu m = t.buildMenu();
        m.setSize(m.getPreferredSize());
        return m;
    }

    /** Спросить подтверждение и стереть все сохранённые траектории. */
    private void forgetLearnedTasks() {
        SwingUtilities.invokeLater(() -> {
            int count = TrajectoryCache.size();
            if (count == 0) {
                JOptionPane.showMessageDialog(null,
                        "Агент пока не выучил ни одной задачи.",
                        APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int answer = JOptionPane.showConfirmDialog(null,
                    "Забыть " + count + " выученн(ых) задач(и)? Следующий раз агент будет\n"
                            + "решать их заново через модель — медленнее, но с нуля.",
                    APP_NAME, JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION) {
                TrajectoryCache.forgetAll();
            }
        });
    }

    private void openLog() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(logFile.toFile());
            } else {
                System.err.println("Открытие файлов не поддерживается, путь к логу: " + logFile);
            }
        } catch (IOException e) {
            System.err.println("Не удалось открыть лог: " + e.getMessage());
        }
    }

    private void applyStatus(Status status, String detail) {
        this.currentStatus = status;
        this.connDetail = detail;
        if (trayIcon == null) return;
        trayIcon.setImage(renderIcon(status));
        updateTooltip();
    }

    private void updateTooltip() {
        if (trayIcon == null) return;
        StringBuilder tooltip = new StringBuilder(APP_NAME).append(": ").append(currentStatus.label);
        if (connDetail != null && !connDetail.isBlank()) {
            tooltip.append(" (").append(connDetail).append(")");
        }
        if (taskLabel != null) {
            tooltip.append(" — ").append(taskLabel);
        }
        trayIcon.setToolTip(tooltip.toString());
    }

    /** Показать в подсказке трея, что сейчас делает agent_task. */
    public void setTaskStatus(String label) {
        this.taskLabel = label;
        updateTooltip();
    }

    /** Задача закончилась — убрать отметку из подсказки трея. */
    public void clearTaskStatus() {
        this.taskLabel = null;
        updateTooltip();
    }

    /**
     * Значок: логотип на тёмной плитке и точка статуса в правом нижнем углу с
     * тёмной обводкой — чтобы точка читалась и на светлой, и на тёмной панели задач.
     */
    static BufferedImage renderIcon(Status status) {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FaustLogoPainter.paint(g, size / 2.0, size / 2.0, size, 0, 0, 1, 1, 1f, true);
        int r = 12;
        g.setColor(new Color(0x161312));
        g.fillOval(size - r - 1, size - r - 1, r + 1, r + 1);
        g.setColor(status.color);
        g.fillOval(size - r + 2, size - r + 2, r - 5, r - 5);
        g.setStroke(new BasicStroke(1f));
        g.dispose();
        return image;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // --- RelayStatusListener: колбэки из RelayClient, вызываются из фонового потока ---

    @Override
    public void onConnecting() {
        applyStatus(Status.CONNECTING, null);
    }

    @Override
    public void onConnected() {
        applyStatus(Status.CONNECTED, null);
    }

    @Override
    public void onDisconnected(String reason) {
        applyStatus(Status.DISCONNECTED, reason);
    }

    @Override
    public void onAuthRejected(String reason) {
        applyStatus(Status.REJECTED, reason);
        if (trayIcon != null) {
            trayIcon.displayMessage(APP_NAME,
                    "ПК отвязан на сервере — выбери «Настроить заново» в трее",
                    TrayIcon.MessageType.WARNING);
        }
    }
}
