# Вау-фича: точка прицела ИИ-агента прямо на скриншоте

## Общий контекст

Сейчас, пока работает `agent_task`, телефон уже получает живые шаги
(`AgentStep`: номер шага, текстовое описание вроде «шаг 3: click(512, 300)»
и уменьшенный скриншот) — это появилось ещё в фиче «живой показ шагов».
Но координаты клика видны только текстом внутри описания — глазами не
видно, куда именно целится модель. Хотим красный маркер прямо на картинке
в момент, когда модель решает, куда кликнуть — то есть буквально видно,
как ИИ смотрит на экран и целится.

Проверил по коду — это не новая архитектура, а именно «протащить то, что
уже посчитано, ещё на один шаг дальше»:

- В **пиксельном режиме** (`scope=SCREEN`, `ScreenAgent.java`) модель и так
  возвращает `ScreenAction(action, x, y, ...)` — координаты уже в пикселях
  ТОГО ЖЕ уменьшенного скриншота, который уходит в `AgentStep`. Взять
  неоткуда — уже готово, просто сейчас `action.x()/action.y()` никуда не
  передаются дальше `toString()`.
- В **браузерном режиме** (`scope=BROWSER`, `BrowserAgent.java`) модель
  вместо координат называет `index` элемента из списка `PageElement`, а у
  `PageElement` уже есть свои `x, y` — правда, в CSS-пикселях страницы
  (`getBoundingClientRect()`), а скриншот для показа снимается отдельно и
  затем уменьшается (`ImageUtil.scaleDown`). Тут нужен один пересчёт
  масштаба (см. Промпт 4) — с честной оговоркой: пересчёт исходит из того,
  что 1 CSS-пиксель ≈ 1 пиксель скриншота ДО уменьшения, что верно почти
  всегда, но может немного разъехаться при масштабировании экрана в
  Windows (125%/150% DPI) — стоит один раз проверить вживую перед защитой,
  как и с похожими оговорками в предыдущих шагах (скролл, зум).

Дальше — обычный проброс через протокол: `AgentStep` получает два новых
необязательных поля `targetX`/`targetY` (в тех же пикселях, что и
`screenshotWidth`/`screenshotHeight` в этом же сообщении). Релею менять
ничего не нужно — `JarvisWebSocketHandler.forwardToPhones` пересылает
`AgentStep` как есть, не разбирая поля. На телефоне — маленький `Canvas`
поверх уже существующей картинки в чате, который переводит пиксель
скриншота в реальный размер, в котором Compose нарисовал картинку (тот же
приём «пересчёт координат независимо от визуального масштаба», что уже
использовался для зума живого экрана).

Порядок промптов обязателен — почти везде правки цепляются друг за
друга: протокол (в ТРЁХ местах, как обычно) → `StepReporter` → два агента
→ `CommandExecutor` → приложение.

---

## Промпт 1 — протокол: `AgentStep` получает точку прицела

Меняем в ТРЁХ местах — это инвариант проекта (общий Kotlin-модуль зеркалится
в двух Java-копиях побайтово одинаковых полей).

### 1.1. `shared/src/commonMain/kotlin/com/jarvis/protocol/Protocol.kt`

```kotlin
// было
@Serializable
@SerialName("agent_step")
data class AgentStep(
    override val requestId: String,
    val step: Int,
    val description: String,
    val screenshotBase64: String? = null,
    val screenshotFormat: ImageFormat? = null,
    val screenshotWidth: Int? = null,
    val screenshotHeight: Int? = null,
) : JarvisMessage
// стало
@Serializable
@SerialName("agent_step")
data class AgentStep(
    override val requestId: String,
    val step: Int,
    val description: String,
    val screenshotBase64: String? = null,
    val screenshotFormat: ImageFormat? = null,
    val screenshotWidth: Int? = null,
    val screenshotHeight: Int? = null,
    /** Точка, куда целится модель — в пикселях СКРИНШОТА из этого же
     *  сообщения. null — шаг без цели (type/scroll/done) или координаты
     *  не удалось сопоставить с картинкой. */
    val targetX: Int? = null,
    val targetY: Int? = null,
) : JarvisMessage
```

### 1.2. `jarvis-agent/src/main/java/com/jarvis/agent/protocol/JarvisMessage.java`

```java
// было
    record AgentStep(String requestId, int step, String description,
                     String screenshotBase64, ImageFormat screenshotFormat,
                     Integer screenshotWidth, Integer screenshotHeight) implements JarvisMessage {}
// стало
    record AgentStep(String requestId, int step, String description,
                     String screenshotBase64, ImageFormat screenshotFormat,
                     Integer screenshotWidth, Integer screenshotHeight,
                     Integer targetX, Integer targetY) implements JarvisMessage {}
```

### 1.3. `jarvis-relay/src/main/java/com/jarvis/relay/protocol/JarvisMessage.java`

Ровно та же правка, что в 1.2 — файл побайтово тот же класс, только другой
пакет.

---

## Промпт 2 — `StepReporter.java`: два новых параметра

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/vision/StepReporter.java`

```java
// было
public interface StepReporter {
    void onStep(int step, String description, byte[] screenshotJpeg, int width, int height);

    /** Заглушка для мест, где живой показ не нужен (demo-запуски). */
    StepReporter NONE = (step, description, jpeg, w, h) -> { };
}
// стало
public interface StepReporter {
    /**
     * targetX/targetY — точка, куда целится модель, в пикселях ТОГО ЖЕ
     * screenshotJpeg (не реального экрана!). null, если шаг без цели
     * (type/scroll/done) или координаты не удалось определить.
     */
    void onStep(int step, String description, byte[] screenshotJpeg, int width, int height,
                Integer targetX, Integer targetY);

    /** Заглушка для мест, где живой показ не нужен (demo-запуски). */
    StepReporter NONE = (step, description, jpeg, w, h, tx, ty) -> { };
}
```

---

## Промпт 3 — `ScreenAgent.java`: координаты клика уже посчитаны, просто передаём

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/vision/ScreenAgent.java`

```java
// было
            trace.add("шаг " + step + " (экран " + shot.width + "x" + shot.height + ", " + dt + "мс): " + action);
            stepReporter.onStep(step, "шаг " + step + ": " + action, shot.jpeg, shot.width, shot.height);
// стало
            trace.add("шаг " + step + " (экран " + shot.width + "x" + shot.height + ", " + dt + "мс): " + action);
            // action.x()/y() уже в пикселях shot.jpeg (см. javadoc ScreenAction) — ничего пересчитывать не нужно.
            stepReporter.onStep(step, "шаг " + step + ": " + action, shot.jpeg, shot.width, shot.height,
                    action.x(), action.y());
```

(Для `type`/`scroll`/`done` `action.x()`/`action.y()` и так `null` — `ScreenAction` заполняет их только при `click`.)

---

## Промпт 4 — `BrowserAgent.java`: найти элемент по index и пересчитать масштаб

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/vision/BrowserAgent.java`

**4.1.** В основном цикле — найти элемент, на который указало действие, и
передать его в `reportStep`:

```java
// было
                long dt = System.currentTimeMillis() - t0;
                trace.add("шаг " + step + " (" + elements.size() + " элементов, " + dt + "мс): " + action);
                reportStep(stepReporter, driver, step, "шаг " + step + ": " + action);
// стало
                long dt = System.currentTimeMillis() - t0;
                trace.add("шаг " + step + " (" + elements.size() + " элементов, " + dt + "мс): " + action);
                PageElement target = findElement(elements, action.index());
                reportStep(stepReporter, driver, step, "шаг " + step + ": " + action, target);
```

**4.2.** Сам метод — пересчёт из CSS-пикселей страницы в пиксели
уменьшенного скриншота:

```java
// было
    private void reportStep(StepReporter reporter, WebDriver driver, int step, String description) {
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            BufferedImage scaled = ImageUtil.scaleDown(img, 800);
            byte[] jpeg = ImageUtil.toJpeg(scaled, 0.5f);
            reporter.onStep(step, description, jpeg, scaled.getWidth(), scaled.getHeight());
        } catch (Exception e) {
            reporter.onStep(step, description, null, 0, 0);
        }
    }
// стало
    private void reportStep(StepReporter reporter, WebDriver driver, int step, String description, PageElement target) {
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            BufferedImage scaled = ImageUtil.scaleDown(img, 800);
            byte[] jpeg = ImageUtil.toJpeg(scaled, 0.5f);

            // target.x()/y() — CSS-пиксели страницы (getBoundingClientRect), скриншот же
            // снят в пикселях окна и затем уменьшен. Приближение: 1 CSS-пиксель ≈ 1 пиксель
            // скриншота ДО уменьшения (ломается при DPI-масштабировании Windows ≠ 100%).
            Integer tx = null, ty = null;
            if (target != null && img.getWidth() > 0) {
                double scale = (double) scaled.getWidth() / img.getWidth();
                tx = (int) Math.round(target.x() * scale);
                ty = (int) Math.round(target.y() * scale);
            }
            reporter.onStep(step, description, jpeg, scaled.getWidth(), scaled.getHeight(), tx, ty);
        } catch (Exception e) {
            reporter.onStep(step, description, null, 0, 0, null, null);
        }
    }
```

**4.3.** Новый маленький хелпер — добавить рядом с `locate()`:

```java
    /** Элемент, на который указало действие модели (click/type с index) — для маркера на телефоне. null, если действие без index. */
    private PageElement findElement(List<PageElement> elements, Integer index) {
        if (index == null) return null;
        return elements.stream().filter(e -> e.index() == index).findFirst().orElse(null);
    }
```

---

## Промпт 5 — `CommandExecutor.java`: пробросить координаты в `AgentStep`

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/CommandExecutor.java`

```java
// было
        StepReporter stepReporter = (step, description, jpeg, w, h) -> {
            String b64 = jpeg == null ? null : Base64.getEncoder().encodeToString(jpeg);
            sender.accept(new JarvisMessage.AgentStep(
                    requestId, step, description,
                    b64, jpeg == null ? null : ImageFormat.JPEG,
                    jpeg == null ? null : w, jpeg == null ? null : h));
        };
// стало
        StepReporter stepReporter = (step, description, jpeg, w, h, tx, ty) -> {
            String b64 = jpeg == null ? null : Base64.getEncoder().encodeToString(jpeg);
            sender.accept(new JarvisMessage.AgentStep(
                    requestId, step, description,
                    b64, jpeg == null ? null : ImageFormat.JPEG,
                    jpeg == null ? null : w, jpeg == null ? null : h,
                    tx, ty));
        };
```

---

## Промпт 6 — приложение: `ChatMessage` несёт точку прицела

Файл: `androidApp/src/main/kotlin/com/jarvis/app/ChatViewModel.kt`

**6.1.** Новые поля в `ChatMessage` (рядом с `imageBase64`/`requestId` —
вставить после `requestId`, сохранив остальные поля класса без изменений):

```kotlin
// было
    val imageBase64: String? = null,
    /** requestId команды — чтобы сопоставить ответ с этим сообщением. */
    val requestId: String? = null,
// стало
    val imageBase64: String? = null,
    /** requestId команды — чтобы сопоставить ответ с этим сообщением. */
    val requestId: String? = null,
    /** Точка на imageBase64, куда целится vision-агент — только у шагов agent_task. */
    val targetX: Int? = null,
    val targetY: Int? = null,
```

**6.2.** Передать координаты при разборе `AgentStep`:

```kotlin
// было
            is AgentStep -> {
                // Промежуточный шаг agent_task — просто новая карточка в чате, без
                // обновления progress (за это отвечает финальный CommandResult с тем
                // же requestId, он придёт позже).
                add(ChatMessage(
                    author = Author.PC,
                    text = "🔎 ${msg.description}",
                    imageBase64 = msg.screenshotBase64,
                ))
            }
// стало
            is AgentStep -> {
                // Промежуточный шаг agent_task — просто новая карточка в чате, без
                // обновления progress (за это отвечает финальный CommandResult с тем
                // же requestId, он придёт позже).
                add(ChatMessage(
                    author = Author.PC,
                    text = "🔎 ${msg.description}",
                    imageBase64 = msg.screenshotBase64,
                    targetX = msg.targetX,
                    targetY = msg.targetY,
                ))
            }
```

---

## Промпт 7 — приложение: маркер поверх скриншота в пузыре чата

Файл: `androidApp/src/main/kotlin/com/jarvis/app/MainActivity.kt`

Оборачиваем существующую картинку в `Box` и рисуем `Canvas` поверх неё —
это тот же приём независимости от визуального масштаба, что уже
использовался для зума живого экрана (`ScreenViewScreen.kt`): координата
переводится в ДОЛЮ от размера картинки, а не в абсолютные пиксели, поэтому
не важно, во сколько раз Compose отрисовал изображение крупнее/мельче
исходного.

```kotlin
// было
                msg.imageBase64?.let { b64 ->
                    val bitmap = remember(b64) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                    bitmap?.let {
                        Spacer(Modifier.height(6.dp))
                        Image(
                            bitmap = it,
                            contentDescription = "скриншот экрана ПК (нажми, чтобы открыть)",
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { zoomOpen = true },
                        )
                        if (zoomOpen) {
                            ZoomableScreenshotDialog(bitmap = it, onDismiss = { zoomOpen = false })
                        }
                    }
                }
// стало
                msg.imageBase64?.let { b64 ->
                    val bitmap = remember(b64) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                    bitmap?.let {
                        Spacer(Modifier.height(6.dp))
                        Box {
                            Image(
                                bitmap = it,
                                contentDescription = "скриншот экрана ПК (нажми, чтобы открыть)",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { zoomOpen = true },
                            )
                            // Точка прицела ИИ-агента — только у шагов agent_task (AgentStep),
                            // у обычных скриншотов (msg.targetX == null) не рисуется вообще.
                            if (msg.targetX != null && msg.targetY != null) {
                                Canvas(Modifier.matchParentSize()) {
                                    val point = Offset(
                                        x = msg.targetX.toFloat() / it.width * size.width,
                                        y = msg.targetY.toFloat() / it.height * size.height,
                                    )
                                    drawCircle(color = Color(0xFFFF3B30), radius = 18f, center = point, alpha = 0.35f)
                                    drawCircle(color = Color(0xFFFF3B30), radius = 6f, center = point)
                                }
                            }
                        }
                        if (zoomOpen) {
                            ZoomableScreenshotDialog(bitmap = it, onDismiss = { zoomOpen = false })
                        }
                    }
                }
```

Если в файле ещё нет этих импортов — добавить:
```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
```
(`Color`, `Box`, `Modifier` в файле уже точно есть — используются рядом.)

---

## Проверка

- [ ] Собирается: `shared`, `jarvis-agent`, `jarvis-relay`, `androidApp` — везде, где правили протокол, три копии `AgentStep` совпадают по набору полей.
- [ ] Голосом дать задачу в БРАУЗЕРЕ (scope по умолчанию) — например «найди на ютубе смешных котят» — в чате на каждом шаге, где модель кликает по конкретному элементу, поверх скриншота появляется красный маркер примерно там, где реально произошёл клик (сверить на глаз с тем, что модель нажала).
- [ ] Задача с `agent_task` и `scope: screen` (если есть быстрый способ её вызвать — например через отладочный `ScreenAgentDemo`, или голосовой фразой, которая явно просит вне браузера) — маркер должен появляться ТОЧНО там, где кликает `java.awt.Robot` (тут пересчёта нет, координаты 1-в-1 из ответа модели).
- [ ] Шаги `type`/`scroll`/`done` — маркер НЕ появляется (targetX/targetY на них null), картинка показывается как раньше.
- [ ] Старый скриншот от команды `screenshot` (не agent_task) — маркер тоже не появляется, ничего не сломалось для обычных скриншотов.
- [ ] Если на ПК включено масштабирование экрана Windows (125%/150%) — проверить, не съезжает ли маркер в браузерном режиме; если съезжает — это ожидаемая, уже задокументированная в коде погрешность (CSS-пиксели vs пиксели скриншота), можно либо принять как есть для демо на своём ПК с 100% масштабом, либо доработать отдельно.
- [ ] Открыть скриншот на весь экран (`ZoomableScreenshotDialog`, по тапу) — маркер там не обязателен (в этот диалог его не добавляли), картинка открывается как раньше.
