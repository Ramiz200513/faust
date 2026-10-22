package com.jarvis.agent;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Демонстрация всех анимаций «Фауст» без релея, телефона и ключа модели:
 * проигрывает три сценария прямо на этом экране.
 *
 * <pre>
 *   1. Обычная команда («открой телеграм»)       — карточка на 2.5 с
 *   2. «Экран ПК»: телефон управляет компьютером — рамка, ореол, всплески от тапов
 *   3. ИИ-задача в браузере                       — заставка, рамка, виджет, курсор-призрак
 * </pre>
 *
 * Вызывает FaustOverlayController ровно так же, как это делают CommandExecutor,
 * ScreenStreamer и BrowserAgent во время настоящей работы. Поэтому демо
 * показывает не отдельную «картинку для презентации», а тот самый код.
 *
 * <h2>Безопасность</h2>
 * Демо НИЧЕГО НЕ НАЖИМАЕТ и не двигает твою мышь: по экрану летает только
 * курсор-призрак, клики — только анимация.
 *
 * <h2>Режим record</h2>
 * По умолчанию оверлеи исключены из захвата экрана — их не видят OBS, Zoom и
 * «Ножницы». С аргументом record исключение выключается, и анимации можно
 * записать на видео или показать в демонстрации экрана.
 */
public final class FaustOverlayDemo {

    private FaustOverlayDemo() {}

    public static void main(String[] args) throws Exception {
        boolean record = args.length > 0 && args[0].equalsIgnoreCase("record");
        if (record) {
            // Системное свойство важнее agent.properties (см. AgentSettings.flag):
            // настоящие настройки агента не трогаем.
            System.setProperty("faust." + AgentSettings.OVERLAY_EXCLUDE_FROM_CAPTURE, "false");
        }

        System.out.println();
        System.out.println("  Демонстрация анимаций «Фауст» (~35 секунд)");
        System.out.println("  " + (record
                ? "Режим записи: анимации видны в OBS, Zoom и на скриншотах."
                : "Анимации скрыты от записи экрана. Для OBS/Zoom — режим записи."));
        System.out.println("  Демо ничего не нажимает и не двигает твою мышь.");
        System.out.println();

        AtomicBoolean stopped = new AtomicBoolean(false);
        FaustOverlayController.install(() -> stopped.set(true));
        Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();

        // --- 1. Обычная команда --------------------------------------------------
        System.out.println("  1/3  Обычная команда: «открой телеграм»");
        FaustOverlayController.onCommand("Открываю Telegram");
        pause(3200, stopped);

        // --- 2. «Экран ПК» -------------------------------------------------------------
        System.out.println("  2/3  «Экран ПК»: телефон управляет компьютером");
        AtomicBoolean remoteStopped = new AtomicBoolean(false);
        FaustOverlayController.onRemoteControlStart(() -> remoteStopped.set(true));
        pause(1600, remoteStopped);
        double[][] taps = {{0.62, 0.35}, {0.40, 0.55}, {0.70, 0.62}};
        for (double[] t : taps) {
            if (remoteStopped.get()) break;
            FaustOverlayController.onClick(at(work, t[0], true), at(work, t[1], false), false);
            pause(1100, remoteStopped);
        }
        FaustOverlayController.onRemoteControlEnd();
        pause(900, new AtomicBoolean(false));

        // --- 3. ИИ-задача в браузере -------------------------------------------------
        System.out.println("  3/3  ИИ-задача: «найди на YouTube видео про котов»");
        String[] steps = {"Открываю youtube.com", "Нажимаю", "Печатаю «видео про котов»", "Нажимаю", "Готово"};
        // Куда летит призрак на каждом шаге (доли рабочей области); null — не летит.
        double[][] points = {null, {0.50, 0.14}, {0.50, 0.14}, {0.30, 0.42}, null};
        boolean[] isClick = {false, true, false, true, false};

        FaustOverlayController.onTaskStart("Демонстрация: найди на YouTube видео про котов");
        pause(5200, stopped);   // заставка: ~4.7 с, потом появляется виджет

        for (int i = 0; i < steps.length && !stopped.get(); i++) {
            FaustOverlayController.onThinking("Думаю · шаг " + (i + 1) + " из " + steps.length);
            pause(1600, stopped);
            if (stopped.get()) break;

            FaustOverlayController.onStep(steps[i]);
            if (points[i] != null) {
                int x = at(work, points[i][0], true), y = at(work, points[i][1], false);
                // Ровно то, что делает BrowserAgent перед кликом Selenium.
                if (isClick[i]) FaustOverlayController.ghostClick(x, y);
                else FaustOverlayController.ghostMove(x, y);
            }
            pause(1300, stopped);
        }

        if (stopped.get()) System.out.println("  Остановлено кнопкой на экране.");
        FaustOverlayController.onTaskEnd();
        pause(1500, new AtomicBoolean(false));   // дать всему доиграть исчезновение
        System.out.println("  Готово.");
        System.exit(0);   // окна Swing держат программу живой — выходим явно
    }

    /** Координата в доле рабочей области. */
    private static int at(Rectangle work, double fraction, boolean horizontal) {
        return horizontal ? work.x + (int) (work.width * fraction) : work.y + (int) (work.height * fraction);
    }

    /** Подождать ms миллисекунд, но выйти раньше, если нажали «Стоп». */
    private static void pause(long ms, AtomicBoolean stopped) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !stopped.get()) {
            Thread.sleep(50);
        }
    }
}
