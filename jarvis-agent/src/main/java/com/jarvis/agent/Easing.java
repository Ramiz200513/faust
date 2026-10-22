package com.jarvis.agent;

/**
 * Кривые анимации («easing»). Все принимают t от 0 до 1 — долю прошедшего
 * времени — и возвращают долю пройденного ПУТИ.
 *
 * Зачем они нужны: если двигать предмет равномерно (путь = время), движение
 * выглядит механическим. В жизни вещи разгоняются и тормозят — эти формулы
 * это и изображают.
 *
 * Значения t за пределами [0, 1] прижимаются к краям, так что вызывающему
 * коду не нужно следить, не «перелетело» ли время.
 */
public final class Easing {

    private Easing() {}

    /** Плавный разгон и плавное торможение — для перелётов из точки в точку. */
    public static double easeInOutCubic(double t) {
        t = clamp01(t);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Быстрый старт, мягкая остановка — для появлений и затуханий. */
    public static double easeOutCubic(double t) {
        double u = 1 - clamp01(t);
        return 1 - u * u * u;
    }

    /**
     * Как easeOutCubic, но с «перелётом»: значение проскакивает чуть дальше 1
     * и возвращается. Коэффициент 1.70158 — классический (перелёт ~10%).
     */
    public static double easeOutBack(double t) {
        return easeOutBack(t, 1.70158);
    }

    /** easeOutBack с заданной силой перелёта: меньше коэффициент — мягче перелёт. */
    public static double easeOutBack(double t, double overshoot) {
        double c3 = overshoot + 1;
        double u = clamp01(t) - 1;
        return 1 + c3 * u * u * u + overshoot * u * u;
    }

    /** Прижать к диапазону [0, 1]. */
    public static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * Доля прохождения отрезка времени [from, to] в момент t (все в мс).
     * До начала — 0, после конца — 1. На ней построены все таймлайны оверлеев:
     * «от 840 до 1190 мс раздвинуть дуги» = progress(t, 840, 1190).
     */
    public static double progress(double t, double from, double to) {
        if (to <= from) return t >= to ? 1 : 0;
        return clamp01((t - from) / (to - from));
    }

    /** Линейная интерполяция: a при k = 0, b при k = 1. */
    public static double lerp(double a, double b, double k) {
        return a + (b - a) * k;
    }
}
