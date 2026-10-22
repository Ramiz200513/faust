package com.jarvis.agent;

import com.jarvis.agent.protocol.ResultData;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import static com.jarvis.agent.JarvisSwingTheme.*;

/**
 * Окно «Проекты» на ПК — то же, что экран «Проекты» в приложении: разрешённые
 * git-репозитории, их ветка, изменения, что не отправлено, последний коммит.
 *
 * Здесь же человек видит, где лежит список проектов (repos.txt), и может
 * открыть его в один щелчок: дописать проект — единственный способ дать
 * агенту новую папку (модель этого сделать не может).
 */
final class ProjectsWindow {

    private static final int WIDTH = 440;
    private static final Color AMBER = new Color(0xE3B341);
    private static final Color GREEN = new Color(0x3FB950);

    private final JPanel list = new JPanel();
    private final JDialog dialog;

    ProjectsWindow() {
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);

        JPanel col = new JPanel(new BorderLayout(0, 14));
        col.setOpaque(false);
        col.setBorder(new EmptyBorder(20, 22, 18, 22));

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        head.add(new JLabel(FaustWindows.logo(40)), BorderLayout.WEST);
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        titles.add(overline("git", PRIMARY));
        JLabel t = new JLabel("Проекты");
        t.setFont(font(Font.BOLD, 18f));
        t.setForeground(ON_BACKGROUND);
        titles.add(t);
        titles.add(bodyLeft("Агент делает коммиты, push и правки кода только здесь.", WIDTH - 90,
                ON_SURFACE_VARIANT, 12f));
        head.add(titles, BorderLayout.CENTER);
        col.add(head, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(WIDTH, 120));
        col.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton editList = textButton("Открыть repos.txt");
        JButton refresh = outlinedButton("Обновить");
        JButton close = filledButton("Готово");
        buttons.add(editList);
        buttons.add(refresh);
        buttons.add(close);
        col.add(buttons, BorderLayout.SOUTH);

        dialog = FaustWindows.cardDialog("Faust — проекты", col, false, () -> closeDialog());
        editList.addActionListener(e -> openFile(RepoCatalog.file()));
        refresh.addActionListener(e -> reload());
        close.addActionListener(e -> closeDialog());
        this.scroll = scroll;
    }

    private final JScrollPane scroll;

    private void closeDialog() {
        dialog.dispose();
    }

    JDialog dialog() {
        return dialog;
    }

    /** Открыть окно (из меню трея). Состояние git читается в фоне — окно не подвисает. */
    static void open() {
        SwingUtilities.invokeLater(() -> {
            ProjectsWindow w = new ProjectsWindow();
            w.reload();
            w.dialog.pack();
            w.dialog.setLocationRelativeTo(null);
            w.dialog.setVisible(true);
        });
    }

    /** Перечитать проекты и их состояние. */
    void reload() {
        RepoCatalog.ensureFile();
        List<RepoCatalog.Repo> repos = RepoCatalog.all();
        List<ResultData.RepoInfo> infos = new ArrayList<>();
        for (RepoCatalog.Repo r : repos) infos.add(GitTool.info(r.name(), r.path()));
        list.removeAll();
        if (infos.isEmpty()) {
            list.add(card(bodyLeft("Проектов пока нет. Нажмите «Открыть repos.txt» и добавьте строку\n"
                    + "название = путь к папке проекта", WIDTH - 40, ON_SURFACE_VARIANT, 13f), 16));
        }
        for (int i = 0; i < infos.size(); i++) {
            if (i > 0) list.add(Box.createVerticalStrut(10));
            list.add(repoCard(infos.get(i), repos.get(i)));
        }
        // Окно растёт по числу проектов, но не выше ~3,5 карточек — дальше прокрутка.
        int h = Math.min(list.getPreferredSize().height + 4, 470);
        scroll.setPreferredSize(new Dimension(WIDTH, Math.max(h, 90)));
        list.revalidate();
        list.repaint();
        if (dialog.isDisplayable()) dialog.pack();
    }

    private JComponent repoCard(ResultData.RepoInfo info, RepoCatalog.Repo repo) {
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(info.name());
        name.setFont(font(Font.BOLD, 15f));
        name.setForeground(ON_BACKGROUND);
        top.add(name, BorderLayout.CENTER);
        top.add(FaustWindows.chip(info.branch(), ON_SURFACE_VARIANT, FaustWindows.branchIcon(ON_SURFACE_VARIANT)), BorderLayout.EAST);
        inner.add(top);
        inner.add(Box.createVerticalStrut(8));

        if (info.error() != null) {
            inner.add(bodyLeft("⚠ " + info.error(), WIDTH - 40, ERROR, 12f));
        } else {
            // Отступы только МЕЖДУ метками: у FlowLayout они есть и слева от первой,
            // и ряд съезжал вправо относительно текста карточки.
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)) {
                @Override
                public java.awt.Insets getInsets() {
                    return new java.awt.Insets(0, -6, 0, 0);
                }
            };
            chips.setOpaque(false);
            chips.setAlignmentX(Component.LEFT_ALIGNMENT);
            boolean dirty = !info.changed().isEmpty();
            if (!dirty && info.ahead() == 0 && info.behind() == 0) chips.add(FaustWindows.chip("всё сохранено", GREEN, FaustWindows.checkIcon(GREEN)));
            if (dirty) chips.add(FaustWindows.chip("изменено: " + info.changed().size(), AMBER));
            if (info.ahead() > 0) chips.add(FaustWindows.chip("↑ не отправлено: " + info.ahead(), PRIMARY));
            if (info.behind() > 0) chips.add(FaustWindows.chip("↓ на сервере новее: " + info.behind(), TERTIARY));
            if (!info.hasRemote()) chips.add(FaustWindows.chip("без сервера", ON_SURFACE_VARIANT));
            chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, chips.getPreferredSize().height));
            inner.add(chips);
            if (info.lastCommit() != null) {
                inner.add(Box.createVerticalStrut(8));
                inner.add(bodyLeft("последний коммит: " + info.lastCommit(), WIDTH - 40, ON_SURFACE_VARIANT, 12f));
            }
            if (dirty) {
                inner.add(Box.createVerticalStrut(6));
                StringBuilder files = new StringBuilder();
                for (int i = 0; i < Math.min(4, info.changed().size()); i++) {
                    if (i > 0) files.append("\n");
                    files.append(info.changed().get(i));
                }
                if (info.changed().size() > 4) files.append("\n… и ещё ").append(info.changed().size() - 4);
                JLabel f = bodyLeft(files.toString(), WIDTH - 40, ON_SURFACE_VARIANT, 12f);
                f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                inner.add(f);
            }
        }
        inner.add(Box.createVerticalStrut(10));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton folder = outlinedButton("Открыть папку");
        folder.addActionListener(e -> openFile(repo.path()));
        actions.add(folder);
        inner.add(actions);

        JPanel card = card(inner, 14);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(WIDTH, card.getPreferredSize().height));
        return card;
    }

    private static void openFile(java.nio.file.Path p) {
        try {
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) {
            System.err.println("не удалось открыть " + p + ": " + e.getMessage());
        }
    }
}
