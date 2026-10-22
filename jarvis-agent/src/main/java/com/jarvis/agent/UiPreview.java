package com.jarvis.agent;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Предпросмотр окон агента картинками — без показа на экране.
 *
 * Зачем: проверить вид окна (отступы, переносы, цвета) можно, только посмотрев
 * на него. Показывать окно на рабочем столе человека ради этого нельзя — оно
 * перехватит фокус, а окно подтверждения ещё и пищит. Здесь окно собирается
 * и рисуется в PNG; фон под ним — как рабочий стол, чтобы была видна тень.
 *
 * <pre>
 *   java -cp "build/install/jarvis-agent/lib/*" com.jarvis.agent.UiPreview &lt;папка&gt;
 * </pre>
 */
public final class UiPreview {

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "ui-preview");
        Files.createDirectories(out);
        JarvisSwingTheme.applyGlobals();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ConfirmationGate.Parts p = ConfirmationGate.build(
                        "Разрешить автономному агенту действовать на этом ПК?\n"
                        + "Цель: «скачай отчёт за неделю с сайта кафедры и отправь его Лере в Telegram»", 20);
                p.countdown().set(13.4, 20);
                save(FaustWindows.cardDialog("preview", p.content(), false, null), out.resolve("confirm.png"));

                ConfirmationGate.Parts kill = ConfirmationGate.build("Завершить процессы, содержащие «chrome»?", 20);
                kill.countdown().set(3.2, 20);
                save(FaustWindows.cardDialog("preview", kill.content(), false, null), out.resolve("confirm-kill.png"));

                save(AgentTray.previewMenu(), out.resolve("tray-menu.png"));
                PairPhoneDialog pair = PairPhoneDialog.preview();
                pair.pack();
                save((JComponent) pair.getContentPane(), out.resolve("pair-phone.png"));
                pair.dispose();
                ProjectsWindow w = new ProjectsWindow();
                w.reload();
                save(w.dialog(), out.resolve("projects.png"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("готово: " + out.toAbsolutePath());
        System.exit(0);
    }

    /** Нарисовать окно (не показывая) поверх «рабочего стола» и сохранить. */
    static void save(JDialog dialog, Path file) throws Exception {
        dialog.pack();
        save(dialog.getContentPane() instanceof JComponent c ? c : null, file);
        dialog.dispose();
    }

    static void save(JComponent c, Path file) throws Exception {
        Dimension size = c.getPreferredSize();
        c.setSize(size);
        layout(c);
        BufferedImage img = new BufferedImage(size.width + 40, size.height + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x3A4A5E), img.getWidth(), img.getHeight(), new Color(0x1E2633)));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.translate(20, 20);
        c.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", new File(file.toString()));
    }

    private static void layout(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) for (java.awt.Component ch : k.getComponents()) layout(ch);
    }
}
