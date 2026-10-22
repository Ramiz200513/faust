package com.jarvis.agent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.lang.reflect.InvocationTargetException;

import static com.jarvis.agent.JarvisSwingTheme.*;

/**
 * Окно "разрешить/отклонить" для опасных команд (agent_task, kill_process,
 * передача файлов) — человек за ПК должен явно согласиться, прежде чем команда,
 * пришедшая с телефона, реально выполнится. Если окно не тронули timeoutSeconds
 * секунд (ПК заблокирован, никого нет рядом) — по умолчанию ОТКАЗ: так безопаснее,
 * чем молча выполнить команду.
 *
 * <p>Вид — как карточки приложения на телефоне (FaustWindows): без серой рамки
 * Windows, с логотипом, целью задачи в отдельной карточке и полосой отсчёта,
 * которая тает и краснеет в последние секунды. Правила безопасности прежние:
 * по умолчанию (Enter) и по Esc — «Отклонить», закрыть окно молча нельзя.
 */
public final class ConfirmationGate {

    public enum Decision { ALLOWED, DENIED, TIMEOUT }

    private ConfirmationGate() {}

    /** Ширина текста в окне, px. */
    private static final int TEXT_WIDTH = 360;

    /**
     * Блокирует вызывающий поток, пока не ответят или не истечёт время.
     *
     * synchronized: команды с подтверждением приходят из двух потоков
     * (agentPool и confirmPool в RelayClient) — без блокировки два окна могли бы
     * открыться одно поверх другого. Так второе дождётся ответа на первое.
     * Звать НЕ из потока Swing (EDT): invokeAndWait оттуда бросает исключение.
     */
    public static synchronized Decision ask(String description, int timeoutSeconds) {
        Decision[] out = {Decision.DENIED};
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = show(description, timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            // Окно не удалось показать (например, нет графической сессии) — это отказ, а не "разрешено".
            System.err.println("ConfirmationGate: " + e.getCause());
        }
        return out[0];
    }

    /** Детали окна, которые нужны логике: кнопки и отсчёт. */
    record Parts(JComponent content, JButton allow, JButton deny, CountdownLabel countdown) {}

    /** Подпись и полоса отсчёта — меняются вместе. */
    record CountdownLabel(JLabel text, FaustWindows.CountdownBar bar) {
        void set(double secondsLeft, int total) {
            text.setText("Отклонится само через " + (int) Math.ceil(Math.max(0, secondsLeft)) + " с — если не ответить");
            bar.setFraction((float) (secondsLeft / total));
        }
    }

    /**
     * Содержимое окна. Первая строка описания, если это вопрос, становится
     * заголовком; остальное — в карточку: «Цель: «скачай отчёт и отправь Лере»».
     */
    static Parts build(String description, int timeoutSeconds) {
        String[] lines = description.strip().split("\n", 2);
        boolean firstIsQuestion = lines[0].strip().endsWith("?");
        String title = firstIsQuestion ? lines[0].strip() : "Разрешить действие на этом ПК?";
        String details = firstIsQuestion ? (lines.length > 1 ? lines[1].strip() : "") : description.strip();

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setBorder(new EmptyBorder(22, 24, 20, 24));

        // --- шапка: логотип, откуда запрос, вопрос ---
        JPanel head = new JPanel(new BorderLayout(14, 0));
        head.setOpaque(false);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(new JLabel(FaustWindows.logo(44)), BorderLayout.WEST);
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        titles.add(overline("Запрос с телефона", PRIMARY));
        titles.add(Box.createVerticalStrut(4));
        JLabel t = bodyLeft(title, TEXT_WIDTH - 58, ON_BACKGROUND, 16f);
        t.setFont(font(Font.BOLD, 16f));
        titles.add(t);
        head.add(titles, BorderLayout.CENTER);
        col.add(head);

        // --- карточка с целью: что именно агент будет делать ---
        if (!details.isEmpty()) {
            col.add(Box.createVerticalStrut(16));
            JComponent card = card(bodyLeft(details, TEXT_WIDTH - 28, ON_BACKGROUND, 13.5f), 14);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(card);
        }

        // --- отсчёт ---
        col.add(Box.createVerticalStrut(16));
        FaustWindows.CountdownBar bar = new FaustWindows.CountdownBar(TEXT_WIDTH);
        col.add(bar);
        col.add(Box.createVerticalStrut(6));
        JLabel left = new JLabel();
        left.setFont(font(Font.PLAIN, 11.5f));
        left.setForeground(ON_SURFACE_VARIANT);
        left.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(left);
        CountdownLabel countdown = new CountdownLabel(left, bar);
        countdown.set(timeoutSeconds, timeoutSeconds);

        // --- кнопки: главная справа, как принято в Windows 11 ---
        col.add(Box.createVerticalStrut(18));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton deny = outlinedButton("Отклонить");
        JButton allow = filledButton("Разрешить");
        buttons.add(Box.createHorizontalGlue());
        buttons.add(deny);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(allow);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, allow.getPreferredSize().height));
        col.add(buttons);
        return new Parts(col, allow, deny, countdown);
    }

    private static Decision show(String description, int timeoutSeconds) {
        Decision[] holder = {Decision.TIMEOUT};
        Parts parts = build(description, timeoutSeconds);
        JDialog[] ref = {null};
        // Esc — отказ, а не «закрыть и разрешить».
        JDialog dialog = FaustWindows.cardDialog("Faust — подтверждение", parts.content(), true, () -> {
            holder[0] = Decision.DENIED;
            ref[0].dispose();
        });
        ref[0] = dialog;
        dialog.setAlwaysOnTop(true);
        // Крестика нет, а Alt+F4 не должен молча решать за человека: решают кнопки и таймер.
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        parts.allow().addActionListener(e -> { holder[0] = Decision.ALLOWED; dialog.dispose(); });
        parts.deny().addActionListener(e -> { holder[0] = Decision.DENIED; dialog.dispose(); });
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        // Фокус по умолчанию — на "Отклонить": случайный Enter не должен разрешать команду.
        dialog.getRootPane().setDefaultButton(parts.deny());

        // Отсчёт плавный: 20 раз в секунду, по настоящим часам (а не по числу тиков таймера).
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Timer timer = new Timer(50, e -> {
            double left = (deadline - System.currentTimeMillis()) / 1000.0;
            parts.countdown().set(left, timeoutSeconds);
            if (left <= 0) {
                holder[0] = Decision.TIMEOUT;
                dialog.dispose();
            }
        });
        timer.start();

        Toolkit.getDefaultToolkit().beep(); // звук — легко пропустить попап, если не смотришь на экран
        dialog.toFront();
        dialog.setVisible(true); // блокирует до dispose()
        timer.stop();
        return holder[0];
    }
}
