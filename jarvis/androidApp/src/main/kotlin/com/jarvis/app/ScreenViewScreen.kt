package com.jarvis.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.protocol.Click
import com.jarvis.protocol.ScreenFrame
import com.jarvis.protocol.Scroll
import com.jarvis.protocol.StartScreenStream
import com.jarvis.protocol.StopScreenStream
import com.jarvis.protocol.TypeText

/**
 * Экран "Экран ПК": живой просмотр рабочего стола + управление —
 * тап (клик), прокрутка кнопками, маленькая клавиатура для текста и
 * pinch-to-zoom самой картинки (чисто визуально, координаты клика считаются
 * от исходного, неувеличенного размера кадра).
 *
 * Своей ViewModel нет: [ChatViewModel] и так держит соединение с релеем и
 * копит кадры стрима в liveFrame (см. ChatViewModel.handle -> ScreenFrame),
 * заводить вторую копию того же состояния было бы дублированием. vm = viewModel()
 * здесь и в ChatScreen — Compose вернёт ОДИН И ТОТ ЖЕ объект, пока оба экрана
 * живут в одном Activity (общий ViewModelStoreOwner).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenViewScreen(vm: ChatViewModel = viewModel(), onBack: () -> Unit) {
    val frame by vm.liveFrame.collectAsState()
    val zoom = remember { ZoomState() }
    var keyboardVisible by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    // Вход на экран — просим стрим, выход (в том числе системная кнопка "назад")
    // — отпускаем. Останавливать стрим напрямую здесь нельзя: его может смотреть
    // ещё и окошко в чате, и тогда оно застыло бы на последнем кадре. Решает
    // ChatViewModel — стрим живёт, пока его хочет хоть один экран.
    DisposableEffect(Unit) {
        vm.acquireStream(ChatViewModel.HOLDER_FULLSCREEN, maxWidth = 800)
        onDispose { vm.releaseStream(ChatViewModel.HOLDER_FULLSCREEN) }
    }

    fun sendTyped() {
        if (textInput.isNotBlank()) {
            vm.sendCommand(TypeText(textInput))
            textInput = ""
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Экран ПК", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
                // Увеличенная картинка не должна рисоваться поверх шапки.
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val currentFrame = frame
            if (currentFrame == null) {
                Text("подключаюсь…", color = Color.White)
            } else {
                LiveFrameImage(
                    frame = currentFrame,
                    zoom = zoom,
                    onTap = { x, y -> vm.sendCommand(Click(x, y)) },
                )
            }

            // Кнопки прокрутки, клавиатура и сброс зума — справа, поверх картинки.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // robot.mouseWheel: отрицательное — вверх, положительное — вниз.
                // Если у тебя в Windows колесо перевёрнуто и "вверх" листает вниз —
                // просто поменяй знаки местами.
                ControlButton("⬆") { vm.sendCommand(Scroll(dy = -3)) }
                ControlButton("⬇") { vm.sendCommand(Scroll(dy = 3)) }
                ControlButton("⌨", active = keyboardVisible) { keyboardVisible = !keyboardVisible }
                // Сброс зума кнопкой, а не двойным тапом: detectTapGestures с onDoubleTap
                // задерживает КАЖДЫЙ одиночный тап (ждёт второй), и двойной клик на ПК
                // стал бы невозможен. Кнопка видна, только когда картинка увеличена.
                if (zoom.zoomed) {
                    ControlButton("1×") { zoom.reset() }
                }
            }

            // Маленькая клавиатура: тапнул на ПК туда, где нужно печатать, включил
            // "⌨" — набрал текст здесь, он полетит через Ctrl+V в то поле на ПК,
            // где сейчас фокус (см. typeText в CommandExecutor).
            if (keyboardVisible) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() } // сразу поднять клавиатуру телефона

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            singleLine = true,
                            shape = CircleShape,
                            placeholder = { Text("текст для ПК…") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendTyped() }),
                        )
                        FilledIconButton(
                            onClick = { sendTyped() },
                            enabled = textInput.isNotBlank(),
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Text("➤")
                        }
                    }
                }
            }
        }
    }
}

/** Кнопка боковой панели управления; active — подсветка включённого режима (клавиатура). */
@Composable
private fun ControlButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

/**
 * Зум картинки. Живёт на уровне экрана (а не внутри LiveFrameImage), чтобы
 * кнопка «1×» на боковой панели могла его сбросить. Уход с экрана сбрасывает
 * зум — состояние живёт, пока экран показан.
 */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    /** Сдвиг в пикселях экрана телефона (graphicsLayer.translation, не масштабируется). */
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
}

/**
 * Кадр стрима как Image + пересчёт тапа в координаты ПК (raw pixel mapping) +
 * pinch-to-zoom/пан самой картинки.
 *
 * Кадр с ПК имеет реальный размер frame.width x frame.height пикселей.
 * Modifier.aspectRatio заставляет Image иметь ТОЧНО такое же соотношение сторон,
 * как у кадра — значит, изображение занимает весь узел без чёрных полос, и
 * коэффициент масштаба одинаков по всей площади:
 *   realX = tapX * screenW / displayedSize.width   (так же по Y)
 *
 * Зум и тап разнесены по разным узлам:
 *  • пинч/пан ловит внешний Box — он НЕ увеличивается, поэтому координаты жеста
 *    стабильны (детектор внутри увеличенного слоя дёргался бы: его система
 *    координат менялась бы прямо во время жеста);
 *  • тап ловит сама картинка ПОСЛЕ graphicsLayer — Compose переводит позицию
 *    касания через обратную матрицу слоя, поэтому offset приходит в исходных,
 *    неувеличенных координатах картинки, и формула выше работает при любом зуме.
 */
@Composable
private fun LiveFrameImage(frame: ScreenFrame, zoom: ZoomState, onTap: (Int, Int) -> Unit) {
    // Декодирование идёт в фоне, а до его окончания показывается предыдущий
    // кадр — см. rememberDecodedFrame. Раньше это делалось в композиции, то
    // есть на главном потоке, пять раз в секунду.
    val bitmap = rememberDecodedFrame(frame.base64) ?: return

    // Размер, который картинка заняла на экране телефона БЕЗ зума (в пикселях) —
    // onSizeChanged стоит до graphicsLayer, поэтому зум на него не влияет.
    var displayedSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Двумя пальцами — зум от 1x до 4x (дальше смысла нет: сам кадр
                // ограничен maxWidth=800), одним пальцем по увеличенной — пан.
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newScale = (zoom.scale * gestureZoom).coerceIn(1f, 4f)
                    zoom.scale = newScale
                    if (newScale <= 1.01f) {
                        // почти вернулись к 1x — довернём чисто, без остаточного сдвига
                        zoom.offsetX = 0f
                        zoom.offsetY = 0f
                    } else {
                        // Масштаб — от центра, поэтому край картинки уходит за край
                        // на size * (scale - 1) / 2 — ровно столько и разрешаем сдвигать.
                        val maxX = displayedSize.width * (newScale - 1f) / 2f
                        val maxY = displayedSize.height * (newScale - 1f) / 2f
                        zoom.offsetX = (zoom.offsetX + pan.x).coerceIn(-maxX, maxX)
                        zoom.offsetY = (zoom.offsetY + pan.y).coerceIn(-maxY, maxY)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "экран ПК (живой)",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
                .onSizeChanged { displayedSize = it }
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                }
                .pointerInput(frame.width, frame.height) {
                    // Без onDoubleTap: иначе каждый тап ждал бы ~300 мс второго.
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
}
