package com.jarvis.agent;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;

import static com.jarvis.agent.AgentSettings.OVERLAY_CURSOR;
import static com.jarvis.agent.AgentSettings.OVERLAY_FRAME;
import static com.jarvis.agent.AgentSettings.OVERLAY_INTRO;
import static com.jarvis.agent.AgentSettings.OVERLAY_SMOOTH_MOUSE;
import static com.jarvis.agent.AgentSettings.OVERLAY_WIDGET;

/**
 * Единая точка входа для всех анимаций «Фауст». CommandExecutor, ScreenStreamer,
 * BrowserAgent и ScreenAgent знают только про этот класс, а не про отдельные
 * оверлеи: что показывать, в каком порядке и когда убрать — решается здесь.
 *
 * <h2>Три случая — три набора анимаций</h2>
 * <pre>
 *   Обычная команда («открой телеграм», громкость, файлы)
 *       onCommand   — на 2.5 с всплывает виджет «Открываю Telegram», без «Стоп»
 *
 *   «Экран ПК» — телефон управляет компьютером тапами (стрим экрана)
 *       onRemoteControlStart — рамка (B) + ореол у курсора (D)
 *       onClick     — всплеск в точке каждого тапа
 *       onRemoteControlEnd   — всё исчезает
 *
 *   ИИ-задача (agent_task)
 *       onTaskStart — рамка (B) + ореол (D) + заставка (A), потом виджет (C)
 *       onThinking / onStep — режим и текст виджета
 *       onClick / ghostClick — всплеск; в браузере — курсор-призрак
 *       onTaskEnd   — всё исчезает (вызывается в finally, в том числе при отмене)
 * </pre>
 * Сеансы не пересекаются: ИИ-задача и стрим экрана взаимно исключают друг
 * друга ещё в CommandExecutor (общий busyGoal).
 *
 * <h2>Потоки</h2>
 * Методы вызывают из потока агента, а трогать окна можно только из потока
 * Swing. Поэтому каждый метод перекладывает работу туда через invokeLater.
 * Исключения — {@link #onClick} и {@link #ghostMove}: они ЖДУТ, потому что сразу
 * после них агент нажмёт мышь (или Selenium кликнет) в ту же точку.
 */
public final class FaustOverlayController {

    /** Что сейчас происходит — от этого зависит, какие анимации показывать. */
    private enum Session { NONE, TASK, REMOTE }

    /** Читают и поток агента (moveMouse, ghostMove), и Swing — поэтому volatile. */
    private static volatile Session session = Session.NONE;
    /** Есть ли на экране ореол курсора — без него курсору-призраку лететь некуда. */
    private static volatile boolean cursorShown;
    /** Что делать по «Стоп»/«Остановить» во время ИИ-задачи — задаёт CommandExecutor. */
    private static volatile Runnable taskStopAction = () -> { };

    // Поля ниже — только из потока Swing.
    private static FaustIntroOverlay intro;
    private static FaustFrameOverlay frame;
    private static FaustCursorOverlay cursor;
    private static boolean widgetEnabled;
    /** «Рентген» — рамки элементов, которые видит модель (режим UIA). */
    private static FaustXrayOverlay xray;
    /** Панель «План и мысли» справа. */
    private static FaustPlanOverlay planPanel;
    /** Таймер, который прячет короткую карточку обычной команды. */
    private static Timer commandCardTimer;
    /**
     * Последний текст статуса. Виджет может появиться не сразу, а после
     * заставки, — тогда он покажет текущий шаг, а не устаревшее «Запускаю задачу».
     */
    private static String lastStatus = "Запускаю задачу";

    /** Сколько висит карточка обычной команды. */
    private static final int COMMAND_CARD_MS = 2500;
    /** Плавное движение мыши и полёт призрака: столько шагов за столько миллисекунд. */
    private static final int SMOOTH_STEPS = 20;
    private static final long SMOOTH_MS = 350;

    private FaustOverlayController() {}

    /**
     * Один раз при создании CommandExecutor: что делать, когда пользователь
     * нажал «Стоп» на виджете или «Остановить» на рамке во время ИИ-задачи.
     * Это то же самое, что команда cancel_task с телефона.
     */
    public static void install(Runnable onStop) {
        taskStopAction = onStop == null ? () -> { } : onStop;
    }

    private static void requestTaskStop() {
        log("остановка ИИ-задачи с экрана ПК");
        taskStopAction.run();
    }

    // =====================================================================
    //  1. Обычная команда
    // =====================================================================

    /**
     * С телефона пришла обычная команда — коротко показать, что ПК её выполняет.
     * Во время ИИ-задачи или «Экрана ПК» ничего не делаем: там свои анимации.
     *
     * @param text понятная человеку фраза: «Открываю Telegram», «Громкость 30%»
     */
    public static void onCommand(String text) {
        if (session != Session.NONE || text == null) return;
        SwingUtilities.invokeLater(() -> {
            if (session != Session.NONE || !AgentSettings.flag(OVERLAY_WIDGET, true)) return;
            log("onCommand — " + text);
            // null вместо действия «Стоп»: мгновенную команду останавливать нечего.
            FaustStatusWidget.show(null, text);
            FaustStatusWidget.acting(text);
            // Новая команда до того, как карточка спряталась, продлевает её показ.
            if (commandCardTimer != null) commandCardTimer.stop();
            commandCardTimer = new Timer(COMMAND_CARD_MS, e -> {
                if (session == Session.NONE) FaustStatusWidget.hide();
            });
            commandCardTimer.setRepeats(false);
            commandCardTimer.start();
        });
    }

    // =====================================================================
    //  2. «Экран ПК» — телефон управляет компьютером
    // =====================================================================

    /**
     * Телефон открыл «Экран ПК» и управляет компьютером тапами.
     *
     * @param stopRemote что делать по кнопке «Остановить» на плашке — прекратить
     *                   управление с телефона (см. ScreenStreamer.stopByUser)
     */
    public static void onRemoteControlStart(Runnable stopRemote) {
        if (session == Session.REMOTE) return;   // стрим перезапустили — оверлеи уже на экране
        session = Session.REMOTE;
        SwingUtilities.invokeLater(() -> {
            log("onRemoteControlStart");
            hideCommandCard();
            boolean translucent = AgentOverlays.translucencySupported();
            if (translucent && AgentSettings.flag(OVERLAY_FRAME, true)) {
                frame = new FaustFrameOverlay(stopRemote);
                frame.show();
            }
            showCursor(translucent);
        });
    }

    /** Телефон закрыл «Экран ПК» (или стрим остановился сам) — убрать анимации. */
    public static void onRemoteControlEnd() {
        if (session != Session.REMOTE) return;
        session = Session.NONE;
        SwingUtilities.invokeLater(() -> {
            log("onRemoteControlEnd");
            hideAll();
        });
    }

    // =====================================================================
    //  3. ИИ-задача
    // =====================================================================

    public static void onTaskStart(String goal) {
        session = Session.TASK;
        SwingUtilities.invokeLater(() -> {
            log("onTaskStart — " + goal);
            lastStatus = "Запускаю задачу";
            // Карточка предыдущей обычной команды не должна висеть поверх заставки.
            hideCommandCard();
            FaustStatusWidget.hide();

            // Без прозрачных окон затемнение, рамка и ореол превратились бы в
            // непрозрачные прямоугольники, закрывающие экран. Виджету это не
            // страшно — у него есть запасной непрозрачный вид.
            boolean translucent = AgentOverlays.translucencySupported();
            widgetEnabled = AgentSettings.flag(OVERLAY_WIDGET, true);

            if (translucent && AgentSettings.flag(OVERLAY_FRAME, true)) {
                frame = new FaustFrameOverlay(FaustOverlayController::requestTaskStop);
                frame.show();
            }
            showCursor(translucent);
            // Прошлая панель могла ещё доживать свои секунды после конца задачи.
            if (planPanel != null) planPanel.close();
            planPanel = translucent && AgentSettings.flag(AgentSettings.OVERLAY_PLAN, true)
                    ? new FaustPlanOverlay(goal) : null;
            if (translucent && AgentSettings.flag(OVERLAY_INTRO, true)) {
                // Виджет покажется, когда логотип заставки долетит до угла.
                intro = new FaustIntroOverlay(widgetEnabled, FaustOverlayController::showTaskWidget);
                intro.start();
            } else {
                showTaskWidget();
            }
        });
    }

    /** Агент пошёл к модели за следующим шагом («думает»). */
    public static void onThinking(String text) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            lastStatus = text;
            FaustStatusWidget.thinking(text);
        });
    }

    /** Модель ответила, агент выполняет шаг. text — уже понятная человеку фраза. */
    public static void onStep(String text) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            log("onStep — " + text);
            lastStatus = text;
            FaustStatusWidget.acting(text);
        });
    }

    /** Задача закончилась — успешно, с ошибкой или отменой. Вызывать в finally. */
    public static void onTaskEnd() {
        if (session != Session.TASK) return;
        session = Session.NONE;
        SwingUtilities.invokeLater(() -> {
            log("onTaskEnd");
            if (intro != null) intro.finish();
            intro = null;
            hideAll();
            if (widgetEnabled) FaustStatusWidget.hide();
            if (xray != null) xray.close();
            xray = null;
            // Панель плана не исчезает сразу: зритель должен успеть увидеть
            // итог — все галочки и результат проверки.
            FaustPlanOverlay closing = planPanel;
            if (closing != null) {
                Timer t = new Timer(PLAN_LINGER_MS, e -> {
                    closing.close();
                    if (planPanel == closing) planPanel = null;
                });
                t.setRepeats(false);
                t.start();
            }
        });
    }

    /** Сколько панель плана висит после конца задачи. */
    private static final int PLAN_LINGER_MS = 4000;

    // =====================================================================
    //  «Рентген» и панель «План и мысли»
    // =====================================================================

    /**
     * Агент снял окно программы: показать, КАК он его видит — рамки и номера
     * элементов, ровно тот список, что уйдёт модели.
     */
    public static void onSeen(String windowTitle, java.util.List<com.jarvis.agent.vision.UiaElement> elements) {
        if (session != Session.TASK || elements == null || elements.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            if (session != Session.TASK || !AgentSettings.flag(AgentSettings.OVERLAY_XRAY, true)
                    || !AgentOverlays.translucencySupported()) return;
            if (xray == null) xray = new FaustXrayOverlay();
            xray.setElements(windowTitle, elements);
        });
    }

    /** Модель выбрала элемент с этим номером — его рамка вспыхивает. */
    public static void onChosen(int index) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            if (xray != null) xray.setTarget(index);
        });
    }

    /** Действие выполнено, окно сейчас изменится — рамки убрать до следующего снимка. */
    public static void onSeenDone() {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            if (xray != null) xray.clear();
        });
    }

    /** План задачи изменился (новый, шаг начался/закончился, перестроен). */
    public static void onPlan(java.util.List<com.jarvis.agent.protocol.JarvisMessage.PlanItem> items, int revision) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            if (planPanel != null) planPanel.setPlan(items, revision);
        });
    }

    /**
     * Что агент делает и зачем.
     *
     * @param action человеческая фраза («Нажимаю «Отправить»»)
     * @param reason обоснование модели из её ответа (поле reason), может быть null
     */
    public static void onThought(String action, String reason) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            if (planPanel != null) planPanel.setThought(action, reason);
        });
    }

    /**
     * Проверка результата подзадачи.
     *
     * @param state run — идёт; ok — подтверждено; fail — опровергнуто; unknown — понять не удалось
     */
    public static void onVerify(String state, String text) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            if (planPanel != null) planPanel.setVerify(state, text);
        });
    }

    /**
     * С телефона переключили «рентген» или панель плана. Выключенное убираем
     * сразу; включённое появится само — рентген со следующим снимком окна,
     * панель плана со следующей задачей.
     */
    public static void onDisplayOptionsChanged() {
        SwingUtilities.invokeLater(() -> {
            if (xray != null && !AgentSettings.flag(AgentSettings.OVERLAY_XRAY, true)) {
                xray.close();
                xray = null;
            }
            if (planPanel != null && !AgentSettings.flag(AgentSettings.OVERLAY_PLAN, true)) {
                planPanel.close();
                planPanel = null;
            }
        });
    }

    /** Задача (или её план) выполняется из памяти, без обращений к модели. */
    public static void onMemory(boolean fromMemory) {
        if (session != Session.TASK) return;
        SwingUtilities.invokeLater(() -> {
            if (planPanel != null) planPanel.setMemory(fromMemory);
        });
    }

    /** Показать виджет задачи — сразу или по окончании заставки. */
    private static void showTaskWidget() {
        // Заставка могла долететь уже после конца задачи — тогда виджет не нужен.
        if (widgetEnabled && session == Session.TASK) {
            FaustStatusWidget.show(FaustOverlayController::requestTaskStop, lastStatus);
        }
    }

    // =====================================================================
    //  Общее для сеансов
    // =====================================================================

    private static void showCursor(boolean translucent) {
        if (translucent && AgentSettings.flag(OVERLAY_CURSOR, true)) {
            cursor = new FaustCursorOverlay();
            cursor.show();
            cursorShown = true;
        }
    }

    /** Убрать рамку и ореол (они доиграют исчезновение и закроются сами). */
    private static void hideAll() {
        if (frame != null) frame.hide();
        if (cursor != null) cursor.hide();
        frame = null;
        cursor = null;
        cursorShown = false;
    }

    private static void hideCommandCard() {
        if (commandCardTimer != null) {
            commandCardTimer.stop();
            commandCardTimer = null;
        }
    }

    // =====================================================================
    //  Клики и движение
    // =====================================================================

    /**
     * Сейчас в (x, y) будет нажата мышь (ИИ-агентом или тапом с телефона).
     * Вызывать ПЕРЕД нажатием.
     *
     * Ждём, пока всплеск будет создан (invokeAndWait внутри runOnEdtNow): его
     * окно стоит ровно в точке клика и к моменту нажатия обязано уже быть
     * прозрачным для мыши.
     */
    public static void onClick(int x, int y, boolean doubleClick) {
        if (session == Session.NONE) return;   // вне сеанса — без анимации
        AgentOverlays.runOnEdtNow(() -> {
            log("onClick — (" + x + ", " + y + ")" + (doubleClick ? " двойной" : ""));
            if (cursor != null) cursor.onClick(x, y, doubleClick);
        });
    }

    /**
     * Есть ли смысл показывать курсор-призрак: идёт ИИ-задача и ореол на
     * экране. BrowserAgent спрашивает это ДО того, как считать экранные
     * координаты элемента, — чтобы не делать лишний запрос в браузер зря.
     */
    public static boolean ghostWanted() {
        return session == Session.TASK && cursorShown;
    }

    /**
     * Курсор-призрак перелетает к (x, y). Ждёт окончания полёта (~350 мс), чтобы
     * клик Selenium случился, когда призрак уже на месте, а не раньше.
     * Настоящая мышь не двигается.
     */
    public static void ghostMove(int x, int y) {
        if (!ghostWanted()) return;
        long ms = AgentSettings.flag(OVERLAY_SMOOTH_MOUSE, true) ? SMOOTH_MS : 0;
        boolean[] flying = {false};
        AgentOverlays.runOnEdtNow(() -> {
            if (cursor != null) {
                cursor.glideTo(x, y, ms);
                flying[0] = true;
            }
        });
        if (flying[0] && ms > 0) sleepQuietly(ms + 20);   // +20 мс — один-два кадра на «приземление»
    }

    /** Призрак долетел до (x, y) и «нажал»: сжатие дуг и медный всплеск. */
    public static void ghostClick(int x, int y) {
        if (!ghostWanted()) return;
        ghostMove(x, y);
        onClick(x, y, false);
    }

    /**
     * Передвинуть НАСТОЯЩУЮ мышь в (x, y) перед кликом Robot.
     *
     * Во время ИИ-задачи и при включённом overlay.smoothMouse мышь не
     * «телепортируется», а плавно летит к цели — на демонстрации видно, куда
     * направился ИИ, и ореол летит вместе с ней. Тапы с телефона («Экран ПК»)
     * перемещают мышь мгновенно: там важнее отклик.
     *
     * Выполняется в потоке ВЫЗЫВАЮЩЕГО (агента) и занимает ~350 мс.
     */
    public static void moveMouse(Robot robot, int x, int y) {
        if (session == Session.TASK && AgentSettings.flag(OVERLAY_SMOOTH_MOUSE, true)) {
            smoothMove(robot, x, y);
        } else {
            robot.mouseMove(x, y);
        }
    }

    /**
     * 20 шагов за 350 мс по easeInOutCubic от текущего положения до цели.
     *
     * Время каждого шага считаем от старта (System.nanoTime), а не просто
     * «спим по 17 мс»: сон бывает длиннее запрошенного, и ошибки копились бы.
     * Так последний шаг всегда приходится ровно на 350 мс.
     */
    static void smoothMove(Robot robot, int toX, int toY) {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer == null) {           // экран заблокирован — просто ставим мышь на место
            robot.mouseMove(toX, toY);
            return;
        }
        Point from = pointer.getLocation();
        long start = System.nanoTime();
        for (int i = 1; i <= SMOOTH_STEPS; i++) {
            double k = Easing.easeInOutCubic((double) i / SMOOTH_STEPS);
            robot.mouseMove((int) Math.round(Easing.lerp(from.x, toX, k)),
                            (int) Math.round(Easing.lerp(from.y, toY, k)));
            long due = start + SMOOTH_MS * 1_000_000L * i / SMOOTH_STEPS;
            long waitMs = (due - System.nanoTime()) / 1_000_000L;
            if (waitMs > 0 && !sleepQuietly(waitMs)) {
                robot.mouseMove(toX, toY);   // прервали — всё равно доводим до цели
                return;
            }
        }
    }

    /** @return false, если поток прервали. */
    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void log(String message) {
        System.out.println("Фауст-оверлеи: " + message);
    }
}
