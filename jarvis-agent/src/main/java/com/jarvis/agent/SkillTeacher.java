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
    /** Навыки изменились — сообщить релею новые фразы (SkillSync). */
    private final Runnable onChanged;
    private DemoRecorder recorder;
    private FaustRecordingOverlay panel;
    private String name;
    private List<String> triggers;

    public SkillTeacher(BooleanSupplier agentBusy, Runnable onChanged) {
        this.agentBusy = agentBusy;
        this.onChanged = onChanged;
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

    /**
     * Показать записанные шаги и сохранить навык, если человек согласен.
     *
     * Здесь же — пример фразы с данными из показа («отправь Пете отчёт»). По
     * нему агент находит в показе переменные (SkillTemplate) и дальше узнаёт
     * просьбы с другими данными: «отправь Маше расписание».
     */
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
        javax.swing.JTextArea steps = new javax.swing.JTextArea(list.toString().strip());
        steps.setEditable(false);
        steps.setRows(Math.min(12, rec.descriptions().size()));
        JTextField example = new JTextField();
        JPanel form = new JPanel(new java.awt.BorderLayout(0, 8));
        form.add(new javax.swing.JLabel("Навык «" + name + "», шагов: " + rec.steps().size()), java.awt.BorderLayout.NORTH);
        form.add(new javax.swing.JScrollPane(steps), java.awt.BorderLayout.CENTER);
        JPanel bottom = new JPanel(new GridLayout(0, 1, 4, 4));
        bottom.add(new javax.swing.JLabel("<html>Как вы будете просить это голосом — <b>с теми же данными</b>, что в показе?<br>"
                + "Например: <i>отправь Пете отчёт</i>. Агент сам найдёт, что в показе можно менять.<br>"
                + "Необязательно: без примера навык запускается по названию.</html>"));
        bottom.add(example);
        form.add(bottom, java.awt.BorderLayout.SOUTH);

        int answer = JOptionPane.showConfirmDialog(null, form, "Сохранить навык?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;

        String phrase = example.getText().strip();
        SkillTemplate.Generalized g = phrase.isEmpty() ? null
                : SkillTemplate.generalize(phrase, rec.steps(), rec.descriptions());
        if (g != null && g.hasSlots()) {
            SkillStore.save(new SkillStore.Skill(name, triggers, g.steps(), g.descriptions(),
                    System.currentTimeMillis(), rec.durationMs(), g.template(), g.slots()));
            onChanged.run();
            StringBuilder found = new StringBuilder();
            for (SkillTemplate.Slot s : g.slots()) {
                found.append("  • «").append(s.said()).append('»');
                if (s.inflected()) found.append(" (в показе — «").append(s.shown()).append("»)");
                found.append('\n');
            }
            JOptionPane.showMessageDialog(null, "Навык «" + name + "» сохранён.\n\nАгент нашёл в показе, что можно менять:\n"
                    + found + "\nТеперь с телефона можно сказать «" + String.join(" ", g.template()).replaceAll("\\{\\d+}", "…")
                    + "» с другими данными — шаги повторятся без обращений к ИИ.", TITLE, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Слова фразы в показе не встретились — фраза становится обычным триггером.
        List<String> all = new ArrayList<>(triggers);
        if (!phrase.isEmpty()) all.add(phrase);
        SkillStore.save(new SkillStore.Skill(name, all, rec.steps(), rec.descriptions(),
                System.currentTimeMillis(), rec.durationMs()));
        onChanged.run();
        JOptionPane.showMessageDialog(null, "Навык «" + name + "» сохранён."
                + (phrase.isEmpty() ? "" : "\nВ показе не нашлось слов из фразы — она будет запускать навык как есть.")
                + "\nЗапуск с телефона: «Фауст, навык " + name + "»"
                + (all.isEmpty() ? "" : " или «" + all.get(0) + "»"), TITLE, JOptionPane.INFORMATION_MESSAGE);
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
                           + (s.hasTemplate() ? ", фраза: «" + s.templateText() + "»" : "")
                           + (s.triggers().isEmpty() ? "" : ", фразы: " + String.join(", ", s.triggers())));
            }
            Object choice = JOptionPane.showInputDialog(null, "Выберите навык, чтобы удалить его:", TITLE,
                    JOptionPane.PLAIN_MESSAGE, null, labels.toArray(), labels.get(0));
            if (choice == null) return;
            SkillStore.Skill chosen = all.get(labels.indexOf(choice.toString()));
            int sure = JOptionPane.showConfirmDialog(null, "Удалить навык «" + chosen.name() + "»?", TITLE,
                    JOptionPane.YES_NO_OPTION);
            if (sure == JOptionPane.YES_OPTION && SkillStore.remove(chosen.name())) onChanged.run();
        });
    }
}
