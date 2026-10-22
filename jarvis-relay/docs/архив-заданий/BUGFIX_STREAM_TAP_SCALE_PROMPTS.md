Jarvis — фикс: тап по стриму экрана попадает не туда (13 сен. 2026)

Вставляй по одному промпту своим ИИ-агентам в соответствующих подпроектах, порядок важен:
протокол → агент → приложение (протокол трогают все, логику — только агент и приложение).

## Общий контекст (для всех трёх)

**Баг**: в экране «Экран ПК» тап по правому краю телефона попадает примерно на середину
экрана ноутбука, а не на реальный край.

**Причина** (проверено чтением кода 13 сен): `ScreenFrame.width`/`height` — это размер уже
СЖАТОЙ картинки, которую стримит агент (`ScreenStreamer` даунскейлит экран до `maxWidth`,
например 800px, чтобы не грузить сеть). Приложение в `ScreenViewScreen.kt` берёт тап на экране
телефона и пересчитывает его в координаты клика по формуле `offset * frame.width / displayedSize`
— то есть пересчитывает В КООРДИНАТЫ СЖАТОЙ КАРТИНКИ (0..800), а не в реальные координаты
экрана ПК (0..1920 или сколько там). А агент в `CommandExecutor.click(x, y)` берёт эти x/y и
подаёт их в `robot.mouseMove(x, y)` НАПРЯМУЮ, как будто это уже настоящие пиксели экрана.
Разница между 800 и, скажем, 1920 и даёт эффект «полэкрана» — ровно то, что ты видишь.

**Фикс**: `ScreenFrame` получает два новых поля — `screenWidth`/`screenHeight` — это РЕАЛЬНОЕ
разрешение экрана ПК (до сжатия), отдельно от `width`/`height` (размер картинки). Приложение
считает координаты тапа через `screenWidth`/`screenHeight`, а не через `width`/`height`.
Меняется протокол (значит — 3 места синхронно, инвариант проекта), потом агент (заполняет
новые поля), потом приложение (использует их для пересчёта).

Поля делаем nullable с дефолтом `null` (как уже сделано для `seq`) — если где-то останется
старая сборка, ничего не упадёт, просто тап будет неточным по-старому, а не крашем.

---

## Промпт 1 — протокол (3 файла, все три получают одинаковые 2 поля)

Открой подряд:
- `C:\diplom\jarvis\shared\src\commonMain\kotlin\com\jarvis\protocol\Protocol.kt`
- `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\protocol\JarvisMessage.java`
- `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\protocol\JarvisMessage.java`

В `Protocol.kt` в `data class ScreenFrame` сейчас:
```kotlin
data class ScreenFrame(
    override val requestId: String,
    val format: ImageFormat,
    /** Base64-encoded image bytes. */
    val base64: String,
    val width: Int,
    val height: Int,
    /** Frame index when streaming; null for a one-off screenshot. */
    val seq: Int? = null,
) : JarvisMessage
```
Поменять на:
```kotlin
data class ScreenFrame(
    override val requestId: String,
    val format: ImageFormat,
    /** Base64-encoded image bytes. */
    val base64: String,
    /** Размер САМОЙ картинки в base64 (после сжатия для трафика) — НЕ для пересчёта кликов. */
    val width: Int,
    val height: Int,
    /**
     * Реальное разрешение экрана ПК (до сжатия) — по нему, а не по width/height,
     * нужно пересчитывать координаты тапа в координаты клика. null — старый агент,
     * который их ещё не шлёт.
     */
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    /** Frame index when streaming; null for a one-off screenshot. */
    val seq: Int? = null,
) : JarvisMessage
```

В обоих `JarvisMessage.java` (релей и агент — там сейчас байт-в-байт одинаковый код) сейчас:
```java
record ScreenFrame(String requestId, ImageFormat format, String base64,
                   int width, int height, Integer seq) implements JarvisMessage {}
```
Поменять на:
```java
record ScreenFrame(String requestId, ImageFormat format, String base64,
                   int width, int height,
                   Integer screenWidth, Integer screenHeight,
                   Integer seq) implements JarvisMessage {}
```
Порядок полей — как в этом примере (совпадает с новым порядком в Kotlin), это важно: ниже, в
Промпте 2, конструктор вызывается позиционно, аргументы должны идти в том же порядке.

**Важно, почему это нужно менять и на релее тоже**, а не только в агенте и приложении: релей
не пересылает сырые байты JSON как есть — он десериализует `ScreenFrame` в свой собственный
Java-класс и сериализует заново, когда шлёт его телефону (смотри `forwardToPhones(...)` в
`JarvisWebSocketHandler`). Если у релея в записи нет полей `screenWidth`/`screenHeight`, они
молча потеряются при пересылке, даже если агент их честно отправил — фикс не заработает.

### Проверка
Собрать все три модуля (`./gradlew :shared:compileKotlin`, релей и агент — обычная сборка) —
ошибок компиляции быть не должно (поля добавлены с дефолтом, ничего не сломано).

---

## Промпт 2 — Агент (заполнить новые поля реальными размерами экрана)

Файл: `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\ScreenStreamer.java`, метод
`capture(Session s)`. Сейчас:
```java
Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
BufferedImage img = ImageUtil.scaleDown(robot.createScreenCapture(screen), s.maxWidth);
String b64 = Base64.getEncoder().encodeToString(ImageUtil.toJpeg(img, JPEG_QUALITY));
if (session != s) return;
sender.accept(new JarvisMessage.ScreenFrame(
        s.requestId, ImageFormat.JPEG, b64, img.getWidth(), img.getHeight(),
        s.seq.getAndIncrement()));
```
`screen` уже содержит настоящее разрешение экрана (до `scaleDown`) — просто прокинь его в
новые поля:
```java
sender.accept(new JarvisMessage.ScreenFrame(
        s.requestId, ImageFormat.JPEG, b64, img.getWidth(), img.getHeight(),
        screen.width, screen.height,
        s.seq.getAndIncrement()));
```

Файл: `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\CommandExecutor.java`, метод
`captureFrame(...)` (используется и для обычного `/screenshot`, и как финальный кадр
`agent_task` — тапа по нему пока нет, но пусть поле будет заполнено везде одинаково, для
единообразия и на будущее). Сейчас:
```java
Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
BufferedImage full = robot.createScreenCapture(screen);
BufferedImage img = ImageUtil.scaleDown(full, 1280);
byte[] jpeg = ImageUtil.toJpeg(img, 0.5f);
String b64 = Base64.getEncoder().encodeToString(jpeg);
return new JarvisMessage.ScreenFrame(
        requestId, ImageFormat.JPEG, b64, img.getWidth(), img.getHeight(), null);
```
Поменять последнюю строку на:
```java
return new JarvisMessage.ScreenFrame(
        requestId, ImageFormat.JPEG, b64, img.getWidth(), img.getHeight(),
        screen.width, screen.height, null);
```

### Проверка
Собрать агент, запустить, включить показ экрана с телефона — в логе агента (или просто по
поведению) ошибок нет; в остальном поведение не меняется, пока приложение не обновлено (новые
поля просто едут в JSON).

---

## Промпт 3 — Приложение (считать тап по реальным размерам экрана, не по картинке)

Файл: `C:\diplom\jarvis\androidApp\src\main\kotlin\com\jarvis\app\ScreenViewScreen.kt`, функция
`LiveFrameImage`, блок `detectTapGestures`. Сейчас:
```kotlin
detectTapGestures { offset ->
    val w = displayedSize.width
    val h = displayedSize.height
    if (w == 0 || h == 0) return@detectTapGestures
    val realX = (offset.x * frame.width / w).toInt().coerceIn(0, frame.width - 1)
    val realY = (offset.y * frame.height / h).toInt().coerceIn(0, frame.height - 1)
    onTap(realX, realY)
}
```
Поменять на пересчёт по `screenWidth`/`screenHeight` с фоллбеком на `width`/`height`, если
агент ещё старый (значения null):
```kotlin
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
```
Модификатор `.aspectRatio(frame.width.toFloat() / frame.height.toFloat())` выше по коду трогать
не нужно — он просто задаёт пропорции рамки на экране, а даунскейл сохраняет соотношение
сторон, так что оно совпадает с реальным экраном ПК с точностью до округления.

### Проверка (финальная, по всем трём промптам)
1. Пересобрать и переустановить все три части.
2. Открыть «Экран ПК» на телефоне, тапнуть точно в левый верхний угол картинки на телефоне —
   курсор на ноутбуке должен встать в (0, 0) или очень близко.
3. Тапнуть в правый нижний угол картинки на телефоне — курсор должен встать у самого края
   экрана ноутбука (а не на середине, как было).
4. Тапнуть в центр — курсор в центре экрана ноутбука.
5. Проверить на ноутбуке с другим разрешением/масштабом Windows, если есть возможность —
   пересчёт не должен зависеть от того, каким было `maxWidth` при запуске стрима.
