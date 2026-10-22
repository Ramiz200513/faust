# Шаг: управление на экране "Экран ПК" — прокрутка, клавиатура, зум

## Общий контекст

Сейчас на экране живого просмотра ПК (`ScreenViewScreen.kt`) есть только одно
действие — тап по картинке превращается в клик мышью на ПК (уже с учётом
даунскейла кадра, это чинили отдельным багфиксом ранее). Добавляем три вещи,
которые ты попросил:

1. **Прокрутка** — две кнопки (вверх/вниз) поверх изображения, шлют уже
   существующую команду `Scroll` (её не пришлось изобретать — `scroll` в
   whitelist и на агенте работает с самого начала проекта, через
   `robot.mouseWheel(dy)`).
2. **Маленькая клавиатура** — кнопка-переключатель "⌨" открывает узкую строку
   ввода внизу экрана; то, что напечатал, летит уже существующей командой
   `TypeText` (она у агента вставляется через буфер обмена + Ctrl+V — значит
   сработает именно в то поле на ПК, где сейчас фокус, то есть туда, куда до
   этого тапнули).
3. **Зум изображения** — pinch-to-zoom и панорамирование САМОЙ картинки на
   телефоне, чтобы точнее целиться в мелкие элементы. Это чисто визуально на
   телефоне, координаты клика по-прежнему считаются от исходного
   (неувеличенного) размера кадра — картинка увеличивается, а не разрешение
   стрима, так что во сколько раз ни увеличивай, курсор на ПК всё равно попадёт
   туда, куда целился.

**Важно: протокол, relay и агент трогать не пришлось вообще** — `Scroll` и
`TypeText` уже есть, уже в whitelist, уже работают (ими давно пользуется обычный
голосовой ввод). Всё изменение — это один файл в Android-приложении.

Два места, где я не уверен на 100% без реального теста на устройстве (Compose
и системная мышь у каждого чуть по-разному ведут себя), — оба явно отмечены
ниже и в чек-листе, с готовым planом Б, если что-то пойдёт не так:

- **Направление прокрутки.** `Scroll(dy)` уходит в `robot.mouseWheel(dy)`, а
  куда физически "крутит" положительное/отрицательное значение — иногда
  зависит от системных настроек Windows. Если кнопка "вверх" вдруг листает
  вниз — это одна цифра, смотри примечание у кода.
- **Координаты тапа при увеличении.** Я закладываю то, как обычно работает
  `Modifier.scale()`/`Modifier.offset{}` в Compose — тап должен по-прежнему
  приходить в исходных (неувеличенных) координатах картинки. Если на практике
  окажется иначе (клик при зуме промахивается) — ниже есть готовая правка на
  одну формулу.

## Промпт 1 — обновляем `ScreenViewScreen.kt`

Файл: `androidApp/src/main/kotlin/com/jarvis/app/ScreenViewScreen.kt`. Меняется
почти весь файл — проще заменить целиком, чем описывать десяток мелких правок.

Было — весь текущий файл (127 строк, начинается с `package com.jarvis.app` и
кончается закрывающей скобкой `LiveFrameImage`) — можешь свериться с тем, что
сейчас лежит на диске, ниже привожу его целиком для контекста:

```kotlin
package com.jarvis.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.protocol.Click
import com.jarvis.protocol.ScreenFrame
import com.jarvis.protocol.StartScreenStream
import com.jarvis.protocol.StopScreenStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenViewScreen(vm: ChatViewModel = viewModel(), onBack: () -> Unit) {
    val frame by vm.liveFrame.collectAsState()

    DisposableEffect(Unit) {
        vm.sendCommand(StartScreenStream(fps = 3, maxWidth = 800))
        onDispose { vm.sendCommand(StopScreenStream) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Экран ПК") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val currentFrame = frame
            if (currentFrame == null) {
                Text("подключаюсь…", color = Color.White)
            } else {
                LiveFrameImage(frame = currentFrame, onTap = { x, y -> vm.sendCommand(Click(x, y)) })
            }
        }
    }
}

@Composable
private fun LiveFrameImage(frame: ScreenFrame, onTap: (Int, Int) -> Unit) {
    val bitmap = remember(frame.base64) {
        val bytes = Base64.decode(frame.base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } ?: return

    var displayedSize by remember { mutableStateOf(IntSize.Zero) }

    Image(
        bitmap = bitmap,
        contentDescription = "экран ПК (живой)",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
            .onSizeChanged { displayedSize = it }
            .pointerInput(frame.width, frame.height) {
                detectTapGestures { offset ->
                    val w = displayedSize.width
                    val h = displayedSize.height
                    if (w == 0 || h == 0) return@detectTapGestures
                    val screenW = frame.screenWidth ?: frame.width
                    val screenH = frame.screenHeight ?: frame.height
                    val realX = (offset.x * screenW / w).toInt().coerceIn(0, screenW - 1)
                    val realY = (offset.y * screenH / h).toInt().coerceIn(0, screenH - 1)
                    onTap(realX, realY)
                }
            },
    )
}
```

Стало (полная замена файла):

```kotlin
package com.jarvis.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.protocol.Click
import com.jarvis.protocol.ScreenFrame
import com.jarvis.protocol.Scroll
import com.jarvis.protocol.StartScreenStream
import com.jarvis.protocol.StopScreenStream
import com.jarvis.protocol.TypeText
import kotlin.math.roundToInt

/**
 * Экран "Экран ПК": живой просмотр рабочего стола + управление —
 * тап (клик), прокрутка кнопками, маленькая клавиатура для текста и
 * pinch-to-zoom самой картинки (чисто визуально, координаты клика считаются
 * от исходного, неувеличенного размера кадра).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenViewScreen(vm: ChatViewModel = viewModel(), onBack: () -> Unit) {
    val frame by vm.liveFrame.collectAsState()
    var keyboardVisible by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        vm.sendCommand(StartScreenStream(fps = 3, maxWidth = 800))
        onDispose { vm.sendCommand(StopScreenStream) }
    }

    fun sendTyped() {
        if (textInput.isNotBlank()) {
            vm.sendCommand(TypeText(textInput))
            textInput = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Экран ПК") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val currentFrame = frame
            if (currentFrame == null) {
                Text("подключаюсь…", color = Color.White)
            } else {
                LiveFrameImage(frame = currentFrame, onTap = { x, y -> vm.sendCommand(Click(x, y)) })
            }

            // Кнопки прокрутки и переключатель клавиатуры — справа, поверх картинки.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp)
                    .background(Color(0x66000000), RoundedCornerShape(20.dp))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Если "вверх" на самом деле листает вниз — у тебя в Windows
                // перевёрнуто направление колеса; просто поменяй знаки местами.
                IconButton(onClick = { vm.sendCommand(Scroll(dy = -3)) }) {
                    Text("⬆", color = Color.White)
                }
                IconButton(onClick = { vm.sendCommand(Scroll(dy = 3)) }) {
                    Text("⬇", color = Color.White)
                }
                IconButton(onClick = { keyboardVisible = !keyboardVisible }) {
                    Text("⌨", color = if (keyboardVisible) Color.Yellow else Color.White)
                }
            }

            // Маленькая клавиатура: тапнул на ПК туда, где нужно печатать, включил
            // "⌨" — набрал текст здесь, он полетит через Ctrl+V в то поле на ПК,
            // где сейчас фокус (см. typeText в CommandExecutor).
            if (keyboardVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("текст для ПК…") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendTyped() }),
                    )
                    IconButton(onClick = { sendTyped() }) { Text("➤", color = Color.White) }
                }
            }
        }
    }
}

/**
 * Кадр стрима как Image + пересчёт тапа в координаты ПК (raw pixel mapping) +
 * pinch-to-zoom/пан самой картинки.
 *
 * Масштаб (scale) и смещение (offsetX/offsetY) — чисто визуальные Modifier'ы
 * (Modifier.scale/Modifier.offset), они не меняют РАЗМЕР layout-узла — поэтому
 * displayedSize (измеренный через onSizeChanged) и вся арифметика тапа ниже
 * остаются в исходных, неувеличенных координатах ровно как раньше. Так курсор
 * на ПК всегда попадает туда, куда целились, независимо от текущего зума.
 */
@Composable
private fun LiveFrameImage(frame: ScreenFrame, onTap: (Int, Int) -> Unit) {
    val bitmap = remember(frame.base64) {
        val bytes = Base64.decode(frame.base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } ?: return

    var displayedSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Image(
        bitmap = bitmap,
        contentDescription = "экран ПК (живой)",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
            .onSizeChanged { displayedSize = it }
            .scale(scale)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            // Пинч/пан — двумя пальцами. От 1x (по умолчанию) до 4x, дальше
            // мельче смысла нет — сам кадр всё равно ограничен maxWidth=800.
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 4f)
                    val maxX = (displayedSize.width * (newScale - 1) / 2f).coerceAtLeast(0f)
                    val maxY = (displayedSize.height * (newScale - 1) / 2f).coerceAtLeast(0f)
                    scale = newScale
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    if (newScale <= 1.01f) { // почти вернулись к 1x — довернём чисто
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            // Тап — клик, двойной тап — быстрый сброс зума.
            .pointerInput(frame.width, frame.height) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onTap = { offset ->
                        val w = displayedSize.width
                        val h = displayedSize.height
                        if (w == 0 || h == 0) return@detectTapGestures
                        // Если после реального теста окажется, что при зуме клики
                        // промахиваются — раскомментируй пересчёт под координаты
                        // экрана телефона и используй adjX/adjY вместо offset.x/y:
                        // val adjX = (offset.x - offsetX) / scale
                        // val adjY = (offset.y - offsetY) / scale
                        val screenW = frame.screenWidth ?: frame.width
                        val screenH = frame.screenHeight ?: frame.height
                        val realX = (offset.x * screenW / w).toInt().coerceIn(0, screenW - 1)
                        val realY = (offset.y * screenH / h).toInt().coerceIn(0, screenH - 1)
                        onTap(realX, realY)
                    },
                )
            },
    )
}
```

## Проверка

- [ ] Открываешь "Экран ПК" — картинка идёт как раньше, тап по-прежнему кликает
      в нужном месте (зумом ещё не пользовались).
- [ ] Кнопка "⬆"/"⬇" справа реально листает содержимое на ПК (например, открытую
      страницу в браузере). Если направление перепутано — поменяй знаки `-3`/`3`
      местами в коде, это не баг архитектуры, а просто локальная настройка колеса.
- [ ] Кнопка "⌨" открывает узкую строку ввода снизу; печатаешь и жмёшь ➤ (или
      Enter/"отправить" на клавиатуре телефона) — текст appears в том поле на ПК,
      куда до этого тапнул (проверь на текстовом поле в блокноте/браузере).
- [ ] Двумя пальцами увеличиваешь картинку (pinch) — она визуально растёт,
      можно двигать пальцем (пан) по увеличенному кадру.
- [ ] **Главная проверка**: при увеличении 2-3x тапаешь по конкретной кнопке на
      экране ПК (например, по крестику закрытия окна) — курсор на ПК должен
      попасть именно туда, а не в то место, где была бы эта кнопка без зума.
      Если промахивается — включи закомментированный пересчёт `adjX`/`adjY`
      в коде (описано в комментарии прямо над `onTap`).
- [ ] Двойной тап по картинке сбрасывает зум обратно к 1x.
- [ ] Уходишь с экрана "Экран ПК" и возвращаешься — зум сброшен (это ожидаемо:
      состояние живёт только пока composable на экране).
