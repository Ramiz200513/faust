package com.jarvis.agent;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Как человек учит агента новому навыку показом — сценарий из пунктов трея.
 *
 * <pre>
 *   Трей → «Научить навыку показом…»
 *     → название («утро») и фразы-триггеры («доброе утро, начинаем работу»)
 *     → сверху панель «● Запись навыка»; человек делает задачу мышью
 *     → «Готово» → список записанных шагов → «Сохранить»
 *   Потом с телефона: «Фауст, навык утро» или «Фауст, доброе утро».
 * </pre>
 */
public final class SkillTeacher {

    private static final String TITLE = "Faust — навыки";

    /** Занят ли агент ИИ-задачей: тогда учить нельзя — его клики попали бы в запись. */
    private final BooleanSupplier agentBusy;
    private DemoRecorder recorder;
    private FaustRecordingOverlay panel;
    private String name;
    private List<String> triggers;

    public SkillTeacher(BooleanSupplier agentBusy) {
        this.agentBusy = agentBusy;
    }

    /** Пункт трея «Научить навыку показом…». */
    public void startInteractive() {
        SwingUtilities.invokeLater(() -> {
            if (recorder != null) {
                JOptionPane.showMessageDialog(null, "Запись уже идёт — закончите её кнопкой «Готово».", TITLE,
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (agentBusy.getAsBoolean()) {
                JOptionPane.showMessageDialog(null, "Агент сейчас выполняет задачу. Дождитесь её конца.", TITLE,
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JTextField nameField = new JTextField();
            JTextField triggersField = new JTextField();
            JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
            form.add(new javax.swing.JLabel("Название навыка (например: утро):"));
            form.add(nameField);
            form.add(new javax.swing.JLabel("Фразы для запуска через запятую (необязательно):"));
            form.add(triggersField);
            form.add(new javax.swing.JLabel("<html><br>После «ОК» сделайте задачу мышью и клавиатурой, как обычно.<br>"
                    + "Агент запомнит, ПО КАКИМ элементам вы нажимали и что ввели.<br>"
                    + "Пароли не записываются. Закончите кнопкой «Готово» на панели сверху.</html>"));
            int ok = JOptionPane.showConfirmDialog(null, form, "Научить навыку показом",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION || nameField.getText().isBlank()) return;

            name = nameField.getText().strip();
            triggers = Arrays.stream(triggersField.getText().split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();
            panel = new FaustRecordingOverlay(name, this::finish, this::cancel);
            recorder = new DemoRecorder(panel::update, panel::bounds);
            panel.show();
            recorder.start();
        });
    }

    /** «Готово» на панели записи. */
    private void finish() {
        DemoRecorder r = recorder;
        FaustRecordingOverlay p = panel;
        if (r == null) return;
        recorder = null;
        panel = null;
        // Остановка ждёт дочитывания последнего ввода — не в потоке Swing.
        new Thread(() -> {
            DemoRecorder.Recording rec = r.stop();
            SwingUtilities.invokeLater(() -> {
                p.close();
                review(rec);
            });
        }, "skill-finish").start();
    }

    /** «Отмена» на панели записи. */
    private void cancel() {
        if (recorder != null) recorder.cancel();
        if (panel != null) panel.close();
        recorder = null;
        panel = null;
    }

    /** Показать записанные шаги и сохранить навык, если человек согласен. */
    private void review(DemoRecorder.Recording rec) {
        if (rec.steps().isEmpty()) {
            JOptionPane.showMessageDialog(null, "Ничего не записано: ни одного нажатия по элементам окна.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < rec.descriptions().size(); i++) {
            list.append(i + 1).append(") ").append(rec.descriptions().get(i)).append('\n');
        }
        int answer = JOptionPane.showConfirmDialog(null,
                "Навык «" + name + "», шагов: " + rec.steps().size() + "\n\n" + list
                + "\nСохранить? Запуск с телефона: «Фауст, навык " + name + "»"
                + (triggers.isEmpty() ? "" : " или «" + triggers.get(0) + "»"),
                TITLE, JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        SkillStore.save(new SkillStore.Skill(name, triggers, rec.steps(), rec.descriptions(),
                System.currentTimeMillis(), rec.durationMs()));
    }

    /** Пункт трея «Выученные навыки…»: посмотреть и удалить. */
    public void manageInteractive() {
        SwingUtilities.invokeLater(() -> {
            List<SkillStore.Skill> all = SkillStore.all();
            if (all.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Агент пока не выучил ни одного навыка.\n"
                        + "Научить: трей → «Научить навыку показом…»", TITLE, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<String> labels = new ArrayList<>();
            for (SkillStore.Skill s : all) {
                labels.add(s.name() + " — шагов: " + s.steps().size()
                           + (s.triggers().isEmpty() ? "" : ", фразы: " + String.join(", ", s.triggers())));
            }
            Object choice = JOptionPane.showInputDialog(null, "Выберите навык, чтобы удалить его:", TITLE,
                    JOptionPane.PLAIN_MESSAGE, null, labels.toArray(), labels.get(0));
            if (choice == null) return;
            SkillStore.Skill chosen = all.get(labels.indexOf(choice.toString()));
            int sure = JOptionPane.showConfirmDialog(null, "Удалить навык «" + chosen.name() + "»?", TITLE,
                    JOptionPane.YES_NO_OPTION);
            if (sure == JOptionPane.YES_OPTION) SkillStore.remove(chosen.name());
        });
    }
}
