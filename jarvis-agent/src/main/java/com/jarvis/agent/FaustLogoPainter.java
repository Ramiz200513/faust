package com.jarvis.agent;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Рисует логотип «Фауст» — две дуги «( )» и точку в центре — в любом месте,
 * любого размера и в любой фазе анимации. Им пользуются все оверлеи, поэтому
 * логотип везде одинаковый.
 *
 * <h2>Сетка 100×100</h2>
 * Логотип описан в «сетке» 100×100 с центром (50, 50): так размеры задаются
 * круглыми числами (радиус дуг 30, толщина 9...). Чтобы нарисовать его
 * размером sizePx пикселей, координаты умножаются на sizePx / 100 — это
 * делает {@link Graphics2D#scale}, и дальше можно писать прямо в единицах сетки.
 */
public final class FaustLogoPainter {

    public static final Color TILE = new Color(0x1E1817);
    public static final Color ARC_LEFT = new Color(0xECDCCB);
    public static final Color ARC_RIGHT = new Color(0xC38F8F);
    public static final Color DOT = new Color(0xC8845A);

    /** Радиус дуг и их толщина в единицах сетки. */
    public static final double ARC_RADIUS = 30;
    public static final float ARC_STROKE = 9f;
    /** Радиус точки в единицах сетки (при dotScale = 1). */
    public static final double DOT_RADIUS = 7;

    private FaustLogoPainter() {}

    /**
     * Нарисовать логотип.
     *
     * @param g              куда рисовать (сам g не портится — работаем в копии)
     * @param cx             центр логотипа на экране, px
     * @param cy             центр логотипа на экране, px
     * @param sizePx         размер логотипа (сторона квадрата сетки), px
     * @param arcSpreadUnits насколько раздвинуть дуги: левую влево, правую вправо (единицы сетки)
     * @param arcRotationDeg поворот обеих дуг вокруг центра, градусы (по часовой)
     * @param arcScale       масштаб обеих дуг вокруг центра (1 — как есть)
     * @param dotScale       масштаб точки (1 — радиус 7 единиц)
     * @param alpha          общая непрозрачность 0..1
     * @param withTile       рисовать ли под логотипом тёмную скруглённую плитку
     */
    public static void paint(Graphics2D g, double cx, double cy, double sizePx,
                             double arcSpreadUnits, double arcRotationDeg, double arcScale,
                             double dotScale, float alpha, boolean withTile) {
        if (alpha <= 0 || sizePx <= 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            multiplyAlpha(g2, alpha);

            // Переносим начало координат так, чтобы точка сетки (50,50) попала
            // в (cx, cy), и масштабируем: дальше все числа — в единицах сетки.
            double k = sizePx / 100.0;
            g2.translate(cx - 50 * k, cy - 50 * k);
            g2.scale(k, k);

            if (withTile) {
                // Скругление 24 единицы; RoundRectangle2D принимает ДИАМЕТР, то есть 48.
                g2.setColor(TILE);
                g2.fill(new RoundRectangle2D.Double(0, 0, 100, 100, 48, 48));
            }

            // Поворот и масштаб дуг — вокруг центра сетки. Точка их не получает:
            // запоминаем преобразование и вернём его перед рисованием точки.
            var beforeArcs = g2.getTransform();
            g2.rotate(Math.toRadians(arcRotationDeg), 50, 50);
            g2.translate(50, 50);
            g2.scale(arcScale, arcScale);
            g2.translate(-50, -50);

            g2.setStroke(new BasicStroke(ARC_STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            drawArcPair(g2, 50, 50, ARC_RADIUS, arcSpreadUnits);

            g2.setTransform(beforeArcs);
            double r = DOT_RADIUS * dotScale;
            g2.setColor(DOT);
            g2.fill(new Ellipse2D.Double(50 - r, 50 - r, r * 2, r * 2));
        } finally {
            g2.dispose();
        }
    }

    /**
     * Только две дуги, в пикселях и с ПОСТОЯННОЙ толщиной линии. Нужно для
     * «волн-эха» в заставке: волна растёт, а линия при этом не толстеет — при
     * обычном масштабировании через scale() толщина росла бы вместе с дугой.
     *
     * @param radiusPx радиус дуг в пикселях
     * @param strokePx толщина линии в пикселях
     */
    public static void paintArcsOnly(Graphics2D g, double cx, double cy,
                                     double radiusPx, float strokePx, float alpha) {
        if (alpha <= 0 || radiusPx <= 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            multiplyAlpha(g2, alpha);
            g2.setStroke(new BasicStroke(strokePx, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            drawArcPair(g2, cx, cy, radiusPx, 0);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Сами дуги. Arc2D задаётся квадратом, в который вписана окружность, и
     * углами: отсчёт от «3 часов» ПРОТИВ часовой стрелки, второе число — длина
     * дуги, а не конечный угол. Левая: от 125° на 110° (до 235°), правая: от -55°
     * на 110° (до 55°).
     */
    private static void drawArcPair(Graphics2D g2, double cx, double cy, double r, double spread) {
        double d = r * 2;
        g2.setColor(ARC_LEFT);
        g2.draw(new Arc2D.Double(cx - r - spread, cy - r, d, d, 125, 110, Arc2D.OPEN));
        g2.setColor(ARC_RIGHT);
        g2.draw(new Arc2D.Double(cx - r + spread, cy - r, d, d, -55, 110, Arc2D.OPEN));
    }

    /**
     * Умножить текущую прозрачность на alpha. Именно умножить, а не заменить:
     * если снаружи уже задана прозрачность (окно тает целиком), логотип должен
     * таять вместе с ним, а не становиться снова непрозрачным.
     */
    static void multiplyAlpha(Graphics2D g2, float alpha) {
        float base = g2.getComposite() instanceof AlphaComposite ac ? ac.getAlpha() : 1f;
        g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, base * alpha))));
    }
}
