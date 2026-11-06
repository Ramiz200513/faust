package com.jarvis.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.protocol.ReportFrame
import kotlinx.coroutines.delay

/**
 * Видео-отчёт о задаче: «почему ты так сделал».
 *
 * Кадры приходят с ПК уже с отметками — куда нажал агент, какой элемент окна
 * выбрал и что при этом думала модель (см. TaskReportRecorder на ПК). Здесь
 * они просто проигрываются как слайд-шоу: само, по кнопкам или ползунком.
 * Почему не настоящее видео: кадры и так идут раз в несколько секунд (шаг
 * агента), а листать их по одному — удобнее, чем перематывать ролик.
 *
 * Тот же отчёт можно сохранить GIF-анимацией и переслать — например,
 * преподавателю как доказательство, что агент сделал всё сам.
 */
@Composable
fun TaskReportDialog(
    state: ReportState,
    onClose: () -> Unit,
    onSaveGif: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть отчёт")
                    }
                    Text(
                        "Видео-отчёт",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (state is ReportState.Ready) {
                        IconButton(onClick = { onSaveGif(state.report.taskId) }) {
                            Icon(Icons.Default.Download, contentDescription = "Сохранить GIF в Загрузки")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (state) {
                    is ReportState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("ПК собирает кадры…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is ReportState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.reason, color = MaterialTheme.colorScheme.error)
                    }
                    is ReportState.Ready -> ReportPlayer(state.report.goal, state.report.frames)
                }
            }
        }
    }
}

@Composable
private fun ReportPlayer(goal: String, frames: List<ReportFrame>) {
    if (frames.isEmpty()) {
        Text("В отчёте нет кадров", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }

    // Автопроигрывание: титул и итог держатся дольше, чтобы успеть прочитать.
    // Те же паузы, что у GIF на ПК, — отчёт везде «идёт» одинаково.
    LaunchedEffect(playing, index) {
        if (!playing) return@LaunchedEffect
        val step = frames[index].step
        delay(if (step == 0) 2500L else if (step < 0) 4000L else 1800L)
        if (index < frames.lastIndex) index++ else playing = false
    }

    val frame = frames[index]
    Text(
        "«$goal»",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
    )
    Spacer(Modifier.height(12.dp))
    val bitmap = rememberDecodedFrame(frame.base64)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                // Подпись кадра — для TalkBack: на самой картинке она нарисована и не читается.
                contentDescription = frame.caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator()
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        when {
            frame.step == 0 -> "Начало"
            frame.step < 0 -> "Итог"
            else -> "Шаг ${frame.step}"
        } + " · кадр ${index + 1} из ${frames.size}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    Text(frame.caption, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))

    if (frames.size > 1) {
        Slider(
            value = index.toFloat(),
            onValueChange = { playing = false; index = it.toInt().coerceIn(0, frames.lastIndex) },
            valueRange = 0f..frames.lastIndex.toFloat(),
            steps = (frames.size - 2).coerceAtLeast(0),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { playing = false; if (index > 0) index-- }) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий кадр")
        }
        FilledIconButton(onClick = {
            // Дошли до конца — «играть» начинает сначала, как у обычного плеера.
            if (!playing && index == frames.lastIndex) index = 0
            playing = !playing
        }) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Пауза" else "Смотреть",
            )
        }
        IconButton(onClick = { playing = false; if (index < frames.lastIndex) index++ }) {
            Icon(Icons.Default.SkipNext, contentDescription = "Следующий кадр")
        }
    }
}
