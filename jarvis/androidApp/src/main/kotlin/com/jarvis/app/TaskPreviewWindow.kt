package com.jarvis.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.protocol.ScreenFrame

/**
 * Маленькое окно с живым экраном ПК поверх переписки — видно, что агент делает
 * прямо сейчас, не дожидаясь конца шага.
 *
 * Кадры берутся из того же потока, что и полноэкранный «Экран ПК»
 * ([ChatViewModel.liveFrame]). Во время ИИ-задачи агент включает стрим сам
 * (пониженные 3 кадр/с и ширина 480 — это окошко в угол экрана, а не
 * полноэкранный просмотр), и гасит его вместе с задачей.
 *
 * Окно можно закрыть крестиком: пока идёт та же задача, оно не вернётся —
 * кадры продолжат приходить, но показывать их станет некому. Это сознательно:
 * человек закрыл окно, значит оно мешает.
 */
@Composable
fun TaskPreviewWindow(
    visible: Boolean,
    frame: ScreenFrame?,
    targetX: Int?,
    targetY: Int?,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        // Пока не пришёл первый кадр, окна нет: пустая рамка в углу только мешала бы.
        visible = visible && frame != null,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        val shown = frame ?: return@AnimatedVisibility

        // Размер окна выбирает человек: перетаскиванием за левый нижний угол.
        // rememberSaveable — чтобы размер пережил поворот экрана и возврат из
        // полноэкранного режима, иначе его приходилось бы подбирать заново.
        var widthDp by rememberSaveable { mutableStateOf(150f) }
        val density = LocalDensity.current

        Surface(
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.width(widthDp.dp),
        ) {
            Box {
                PreviewImage(shown, targetX, targetY, onExpand)

                // Уголок для растягивания. Тянем влево-вниз — окно растёт:
                // оно прижато к правому верхнему углу экрана, так что расти ему
                // естественно именно в эту сторону.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { _, drag ->
                                val deltaDp = with(density) { drag.x.toDp().value }
                                widthDp = (widthDp + deltaDp).coerceIn(110f, 320f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.OpenInFull,
                            contentDescription = "изменить размер окна",
                            tint = Color.White,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }

                // Подпись «в эфире» — чтобы окошко не путали с обычным скриншотом.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("● живой экран", color = Color.White, fontSize = 9.sp)
                }

                Row(Modifier.align(Alignment.TopEnd)) {
                    PreviewButton(Icons.Default.OpenInFull, "открыть во весь экран", onExpand)
                    // Крестик не просто прячет окно, а выключает стрим: иначе ПК
                    // продолжал бы снимать и слать кадры, которые никто не видит.
                    PreviewButton(Icons.Default.Close, "выключить живой экран", onClose)
                }
            }
        }
    }
}

@Composable
private fun PreviewImage(frame: ScreenFrame, targetX: Int?, targetY: Int?, onExpand: () -> Unit) {
    val bitmap = rememberDecodedFrame(frame.base64)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onExpand),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "живой экран ПК",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Метка цели: куда агент целится на этом шаге. Координаты уже переведены
        // агентом в систему кадра стрима (см. StepReporter на стороне ПК),
        // поэтому здесь остаётся только перевести их в доли ширины и высоты.
        if (targetX != null && targetY != null && frame.width > 0 && frame.height > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(0.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                TargetDot(
                    fractionX = targetX.toFloat() / frame.width,
                    fractionY = targetY.toFloat() / frame.height,
                )
            }
        }
    }
}

/** Точка, показывающая, куда агент целится: рисуется поверх кадра. */
@Composable
private fun TargetDot(fractionX: Float, fractionY: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val x = maxWidth * fractionX.coerceIn(0f, 1f)
        val y = maxHeight * fractionY.coerceIn(0f, 1f)
        Box(
            Modifier
                .padding(start = x - 5.dp, top = y - 5.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFCB6D3A))
                .border(1.dp, Color.White, RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun PreviewButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(3.dp)
            .size(22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(13.dp))
    }
}
