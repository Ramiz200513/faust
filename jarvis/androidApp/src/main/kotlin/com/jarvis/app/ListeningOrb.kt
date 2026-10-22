package com.jarvis.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Экран «идёт запись голоса»: шар из макета, который реагирует на речь, + подсказка.
 * Тап по шару останавливает запись — то же, что повторный тап по 🎤.
 *
 * @param level громкость голоса 0…1 (VoiceController.voiceLevel)
 */
@Composable
fun ListeningIndicator(level: Float, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ListeningOrb(
            level = level,
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .clickable(onClick = onStop),
        )
        Text(
            "Говорите…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "нажмите на шар, чтобы остановить",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Розовое ядро и два полупрозрачных кольца с тонкой обводкой — как на макете.
 *
 * Анимация «как будто кто-то говорит» строится от реальной громкости голоса:
 *  • ядро и кольца раздуваются пропорционально громкости;
 *  • от ядра наружу бегут волны — в тишине еле заметные, при речи яркие и толще;
 *  • в тишине шар не замирает, а слегка «дышит», чтобы было видно, что микрофон включён.
 * Рисуем на Canvas: несколько кругов за кадр дешевле вложенных Box-ов.
 */
@Composable
fun ListeningOrb(level: Float, modifier: Modifier = Modifier) {
    // onRmsChanged скачет рывками — пружина сглаживает их в живое, «упругое» движение.
    val voice by animateFloatAsState(
        targetValue = level,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "voice",
    )

    val transition = rememberInfiniteTransition(label = "listening")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave",
    )

    val ringFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val ringStroke = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val rose = MaterialTheme.colorScheme.primaryContainer
    // Градиент ядра: светлее сверху-слева, темнее снизу-справа — как на макете.
    val roseLight = lerp(rose, Color.White, 0.10f)
    val roseDark = lerp(rose, Color.Black, 0.22f)

    Canvas(modifier) {
        val v = voice.coerceIn(0f, 1f) // пружина может перелететь за 1 — радиусы не должны
        val c = center
        val maxR = size.minDimension / 2f
        val sw = 1.dp.toPx()

        val coreR = maxR * (0.36f + 0.03f * breath + 0.12f * v)

        // Внешнее и среднее кольца — дышат в противофазе и раздуваются от голоса.
        val outerR = maxR * (0.72f + 0.04f * breath + 0.24f * v) - sw
        drawCircle(color = ringFill.copy(alpha = 0.45f), radius = outerR, center = c)
        drawCircle(color = ringStroke, radius = outerR, center = c, style = Stroke(sw))

        val midR = maxR * (0.54f + 0.03f * (1f - breath) + 0.18f * v)
        drawCircle(color = ringFill.copy(alpha = 0.55f), radius = midR, center = c)
        drawCircle(color = ringStroke, radius = midR, center = c, style = Stroke(sw))

        // Волны от ядра к краю: две, со сдвигом на полпериода, гаснут по мере удаления.
        for (i in 0 until 2) {
            val p = (wave + i * 0.5f) % 1f
            val r = coreR + (maxR - sw - coreR) * p
            val alpha = (1f - p) * (0.08f + 0.55f * v)
            drawCircle(
                color = rose.copy(alpha = alpha),
                radius = r,
                center = c,
                style = Stroke(sw * (1f + 2.5f * v)),
            )
        }

        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(roseLight, roseDark),
                start = Offset(c.x - coreR, c.y - coreR),
                end = Offset(c.x + coreR, c.y + coreR),
            ),
            radius = coreR,
            center = c,
        )
    }
}
