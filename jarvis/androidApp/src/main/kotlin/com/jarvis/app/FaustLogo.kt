package com.jarvis.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Цвета логотипа — те же, что у агента на ПК (FaustLogoPainter.java). */
private val TILE = Color(0xFF1E1817)
private val ARC_LEFT = Color(0xFFECDCCB)
private val ARC_RIGHT = Color(0xFFC38F8F)
private val DOT = Color(0xFFC8845A)

/**
 * Логотип «Фауста»: две дуги — «скобки» вокруг точки. Та же геометрия, что у
 * агента на ПК (сетка 100×100: радиус дуг 30, толщина 9, точка 7), поэтому
 * знак на телефоне и на компьютере один и тот же.
 *
 * @param withTile    тёмная скруглённая плитка под знаком (как иконка приложения)
 * @param breathing   точка мягко «дышит» — для заставки пустого чата и ожидания
 */
@Composable
fun FaustLogo(size: Dp, modifier: Modifier = Modifier, withTile: Boolean = false, breathing: Boolean = false) {
    val pulse = if (breathing) {
        val t = rememberInfiniteTransition(label = "logo")
        t.animateFloat(
            initialValue = 0.85f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dot",
        ).value
    } else 1f
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        if (withTile) {
            drawRoundRect(TILE, size = this.size, cornerRadius = CornerRadius(24 * k, 24 * k))
        }
        val r = 30 * k
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(r * 2, r * 2)
        // Углы Compose отсчитываются ПО часовой стрелке (в Java — против):
        // левая дуга 125°…235° против часовой — это -235° с размахом 110°.
        drawArc(ARC_LEFT, -235f, 110f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(9 * k))
        drawArc(ARC_RIGHT, -55f, 110f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(9 * k))
        drawCircle(DOT, radius = 7 * k * pulse, center = center)
    }
}
