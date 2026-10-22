package com.jarvis.agent;

import javax.swing.SwingUtilities;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Учёт всех окон «Фауст», висящих поверх экрана, и страховка на случай,
 * когда Windows не смогла выполнить нашу просьбу.
 *
 * <h2>Как должно работать в норме</h2>
 * Каждое окно-оверлей после показа регистрируется здесь ({@link #register}):
 * <ul>
 *   <li>просим Windows не показывать его на снимках экрана
 *       ({@link WinOverlayUtil#excludeFromCapture}) — тогда vision-агент его не видит;</li>
 *   <li>декоративные окна делаем прозрачными для мыши
 *       ({@link WinOverlayUtil#makeClickThrough}) — клики агента проходят сквозь них.</li>
 * </ul>
 * Если обе просьбы выполнены, ничего больше делать не нужно, и окна не моргают.
 *
 * <h2>Страховка</h2>
 * Просьба могла не сработать: не загрузилась JNA, старая Windows или ты сам
 * выключил overlay.excludeFromCapture, чтобы записать демо. На этот случай
 * снимки экрана и действия мышью идут через {@link #capture} и
 * {@link #mouseAction}. Они на долю секунды убирают ТОЛЬКО те окна, которые
 * иначе помешали бы:
 * <ul>
 *   <li>для снимка — окна, которые Windows не исключила из захвата;</li>
 *   <li>для клика — окна, которые ловят мышь (с кнопкой «Стоп» или без
 *       click-through), и только если клик приходится прямо на них.</li>
 * </ul>
 */
public final class AgentOverlays {

    /** Сколько ждать, пока Windows перерисует экран под убранными окнами. */
    private static final long COMPOSITOR_MS = 60;

    /** Что известно про одно окно-оверлей. */
    private record Entry(Window window, boolean clickThrough, boolean excludedFromCapture) {}

    /**
     * CopyOnWriteArrayList — список, который безопасно читать из одного потока
     * (агента), пока другой (Swing) в него пишет. Окон тут единицы, поэтому
     * копирование при каждой записи ничего не стоит.
     */
    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    private AgentOverlays() {}

    /** Действие без результата, которое может бросить исключение (клик, прокрутка). */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    // =====================================================================
    //  Регистрация окон — вызывают сами оверлеи, из потока Swing
    // =====================================================================

    /**
     * Подготовить только что ПОКАЗАННОЕ окно (после setVisible(true) — раньше
     * у окна ещё нет HWND) и запомнить, что получилось.
     *
     * @param decorative true — окно только рисует, клики должны проходить сквозь
     *                   него; false — на окне есть кнопка, по нему можно кликнуть,
     *                   но фокус оно забирать не должно
     */
    static void register(Window w, boolean decorative) {
        boolean clickThrough;
        if (decorative) {
            // Получилось ли сделать окно прозрачным для мыши — от этого
            // зависит, придётся ли прятать его перед кликами агента.
            clickThrough = WinOverlayUtil.makeClickThrough(w);
        } else {
            WinOverlayUtil.makeNoActivate(w);
            // На окне кнопка — оно ловит мышь всегда, так и задумано.
            clickThrough = false;
        }
        boolean excluded = AgentSettings.flag(AgentSettings.OVERLAY_EXCLUDE_FROM_CAPTURE, true)
                && WinOverlayUtil.excludeFromCapture(w);
        entries.removeIf(e -> e.window() == w);
        entries.add(new Entry(w, clickThrough, excluded));
    }

    /** Окно спрятано или закрыто навсегда — больше за ним не следим. */
    static void unregister(Window w) {
        entries.removeIf(e -> e.window() == w);
    }

    /**
     * Умеет ли система прозрачные окна. Если нет, полноэкранное затемнение,
     * рамка и ореол превратились бы в непрозрачные прямоугольники, закрывающие
     * экран, — такие оверлеи в этом случае просто не показываются.
     */
    static boolean translucencySupported() {
        if (GraphicsEnvironment.isHeadless()) return false;
        GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        return screen.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
    }

    // =====================================================================
    //  Страховка для снимков и кликов — вызывает агент, из своего потока
    // =====================================================================

    /**
     * Сделать снимок экрана так, чтобы наши окна в него не попали.
     *
     * @param capture сам снимок, например {@code () -> robot.createScreenCapture(r)}
     */
    public static <T> T capture(Callable<T> capture) throws Exception {
        return withHidden(e -> !e.excludedFromCapture(), capture);
    }

    /**
     * Нажатие мыши в точке (x, y) так, чтобы оно не попало в наше окно.
     * Убираются только окна, которые ловят мышь и лежат прямо под точкой, —
     * клик в другом месте экрана ничего не спрячет и не заставит моргать.
     */
    public static void mouseAction(int x, int y, Action action) throws Exception {
        withHidden(e -> !e.clickThrough() && e.window().getBounds().contains(x, y), () -> {
            action.run();
            return null;
        });
    }

    /** То же, но в точке, где мышь стоит сейчас (для прокрутки колесом). */
    public static void mouseActionHere(Action action) throws Exception {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        Point p = pointer == null ? new Point(-1, -1) : pointer.getLocation();
        mouseAction(p.x, p.y, action);
    }

    /** Условие «это окно надо убрать» — своё для снимков и для кликов. */
    private interface Filter {
        boolean mustHide(Entry e);
    }

    private static <T> T withHidden(Filter filter, Callable<T> action) throws Exception {
        // Какие окна прячем, решаем в потоке Swing: только там можно
        // спрашивать окно, показано ли оно.
        List<Window> hidden = new ArrayList<>();
        runOnEdtNow(() -> {
            for (Entry e : entries) {
                if (e.window().isShowing() && filter.mustHide(e)) {
                    e.window().setVisible(false);
                    hidden.add(e.window());
                }
            }
        });
        if (hidden.isEmpty()) return action.call();   // обычный случай — ничего не мешает

        try {
            // Окно убрали, но Windows перерисовывает экран не мгновенно:
            // без паузы снимок иногда успевает поймать окно ещё на месте.
            Thread.sleep(COMPOSITOR_MS);
            return action.call();
        } finally {
            // Возвращаем только те окна, что всё ещё нужны: пока мы снимали,
            // оверлей мог закончить анимацию и закрыться сам.
            runOnEdtNow(() -> {
                for (Window w : hidden) {
                    boolean stillWanted = entries.stream().anyMatch(e -> e.window() == w);
                    if (stillWanted && w.isDisplayable()) w.setVisible(true);
                }
            });
        }
    }

    /**
     * Выполнить в потоке Swing (EDT) и ДОЖДАТЬСЯ окончания.
     *
     * Обычный {@code invokeLater} ставит задачу в очередь «на потом», а нам
     * нужно, чтобы окно было убрано к моменту снимка — поэтому invokeAndWait.
     * Если мы уже в EDT, invokeAndWait вызывать нельзя (поток ждал бы сам
     * себя вечно), поэтому в этом случае просто выполняем сразу.
     */
    static void runOnEdtNow(Runnable task) {
        try {
            if (SwingUtilities.isEventDispatchThread()) task.run();
            else SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            // Оверлеи — украшение: из-за них нельзя срывать снимок или клик агента.
            System.err.println("оверлеи: " + e.getMessage());
        }
    }
}
