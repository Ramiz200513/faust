Jarvis — шаг: живой просмотр работы vision-агента (13 сен. 2026)

Фича 1 из 5, которые запланировали (дальше по очереди: быстрые команды, буфер обмена,
передача файлов, фон+уведомления — отдельными промптами, каждую отдельно проверяем).

Вставляй по одному промпту своим ИИ-агентам, порядок важен: протокол → релей → агент →
приложение.

## Общий контекст (для всех четырёх)

**Что делаем**: сейчас `agent_task` (vision-агент) работает "вслепую" для пользователя — он
шлёт команду, потом ждёт и видит только итоговый отчёт («готово за 7 шагов»), а что агент
делал все эти секунды — не видно. Хотим, чтобы каждый шаг (что агент увидел, что решил
сделать) прилетал на телефон СРАЗУ, по мере выполнения — в чате будет появляться по одной
карточке на шаг: маленький скриншот + подпись вроде «шаг 3: click(512, 300)». Это и есть тот
самый вау-эффект «видно, как думает AI-агент», который отличает тему диплома от пульта ДУ.

**Проверено чтением кода 13 сен**: `agent_task` крутит цикл в `ScreenAgent.run(...)` (пиксели,
scope=SCREEN) или `BrowserAgent.run(...)` (DOM, scope=BROWSER) — оба цикла целиком СИНХРОННЫЕ,
за шаг: снимок/DOM-список → запрос к LLM → действие → повтор. У обоих уже есть параметр-колбэк
`IntConsumer onStep`, но он вызывается ДО шага и только с номером шага — им пользуется трей
("выполняю: ... (шаг N)"), для телефона он бесполезен: ни текста, ни картинки. `ScreenAgent`
на каждом шаге и так снимает экран (`Shot` с `jpeg/width/height`) — эту же картинку можно
переслать на телефон бесплатно. `BrowserAgent` картинку не снимает вообще (работает по DOM), ей
для живого показа нужно добавить отдельный скриншот окна браузера через Selenium
`TakesScreenshot`.

**Как встраиваем**: добавляем новый тип сообщения `AgentStep` в протокол (шлётся ПОКА задача
ещё выполняется, до финального `CommandResult`) и новый интерфейс-колбэк `StepReporter` в
агенте (соседствует с уже существующим `onStep`, но несёт данные шага, а не просто номер).
`CommandExecutor` уже умеет слать сообщения "от себя" в обход `execute()` — именно так работает
`ScreenStreamer` (получает `Consumer<JarvisMessage> sender` в конструкторе и шлёт кадры сам).
Тот же приём используем и здесь.

**Важно про релей**: он не пересылает байты как есть — десериализует сообщение от агента в свой
Java-класс и собирает заново для телефона (`forwardToPhones` в `JarvisWebSocketHandler`), причём
делает это через `switch` с явным перечислением типов, а НЕ обобщённо для любого `JarvisMessage`
(это уже дважды всплывало на этом проекте — легко забыть добавить новый `case`). Если не
добавить `AgentStep` туда явно, шаги будут падать в `default -> log.warn(...)` и на телефон
никогда не дойдут, хотя агент их честно отправит.

---

## Промпт 1 — протокол (3 файла)

Открой подряд:
- `C:\diplom\jarvis\shared\src\commonMain\kotlin\com\jarvis\protocol\Protocol.kt`
- `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\protocol\JarvisMessage.java`
- `C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\protocol\JarvisMessage.java`

В `Protocol.kt` добавить новый класс сразу после `data class ScreenFrame(...)`:
```kotlin
/**
 * Один шаг работы vision-агента (agent_task), отправляется ПОКА задача ещё
 * выполняется — до финального CommandResult. requestId — тот же, что у
 * исходного agent_task, чтобы телефон мог связать шаги с задачей.
 */
@Serializable
@SerialName("agent_step")
data class AgentStep(
    override val requestId: String,
    val step: Int,
    /** Человекочитаемое описание действия, например "click(512, 300)" или "done: цель достигнута". */
    val description: String,
    /** Что видел агент перед этим действием — может отсутствовать, если снимок не удался. */
    val screenshotBase64: String? = null,
    val screenshotFormat: ImageFormat? = null,
    val screenshotWidth: Int? = null,
    val screenshotHeight: Int? = null,
) : JarvisMessage
```

В обоих `JarvisMessage.java` (релей и агент — сейчас байт-в-байт одинаковые) внести 3 правки:

1. В `@JsonSubTypes` добавить строку (после `screen_frame`):
```java
        @JsonSubTypes.Type(value = JarvisMessage.AgentStep.class, name = "agent_step"),
```

2. В `permits` добавить `JarvisMessage.AgentStep` в список (после `JarvisMessage.ScreenFrame`).

3. Добавить новый record сразу после `record ScreenFrame(...)`:
```java
    record AgentStep(String requestId, int step, String description,
                     String screenshotBase64, ImageFormat screenshotFormat,
                     Integer screenshotWidth, Integer screenshotHeight) implements JarvisMessage {}
```

### Проверка
`./gradlew :shared:compileKotlin`, сборка релея и агента — без ошибок (новый тип, ничего
существующего не меняли).

---

## Промпт 2 — Релей (переслать AgentStep на телефон)

Файл: `C:\diplom\jarvis-relay\src\main\java\com\jarvis\relay\ws\JarvisWebSocketHandler.java`,
метод `handleTextMessage`, блок `switch (msg)`. Сейчас:
```java
        switch (msg) {
            case JarvisMessage.Ping p ->
                    send(session, new JarvisMessage.Pong(p.requestId()));

            case JarvisMessage.CommandRequest req ->
                    handleCommand(session, role, req);

            // Results and screen frames come from the agent -> forward to phone.
            case JarvisMessage.CommandResult r -> forwardToPhones(session, role, r);
            case JarvisMessage.ScreenFrame f  -> forwardToPhones(session, role, f);

            default -> log.warn("Unexpected message from {}: {}", role, msg);
        }
```
Добавить ещё один `case` рядом с `ScreenFrame`:
```java
            case JarvisMessage.CommandResult r -> forwardToPhones(session, role, r);
            case JarvisMessage.ScreenFrame f  -> forwardToPhones(session, role, f);
            case JarvisMessage.AgentStep s    -> forwardToPhones(session, role, s);

            default -> log.warn("Unexpected message from {}: {}", role, msg);
```

### Проверка
Собрать релей — без ошибок. Функционально проверится вместе с Промптом 3 (без агента, который
шлёт `AgentStep`, эту ветку не потрогать).

---

## Промпт 3 — Агент (слать шаги по мере выполнения)

### 3.1 Новый файл `StepReporter.java`
`C:\diplom\jarvis-agent\src\main\java\com\jarvis\agent\vision\StepReporter.java`:
```java
package com.jarvis.agent.vision;

/**
 * Сообщает об одном завершённом шаге computer-use цикла — чтобы телефон мог
 * показать процесс работы агента в реальном времени, а не только итог.
 * screenshotJpeg может быть null (не удалось снять/сжать) — репортер обязан
 * пережить это молча, а не бросать исключение.
 */
public interface StepReporter {
    void onStep(int step, String description, byte[] screenshotJpeg, int width, int height);

    /** Заглушка для мест, где живой показ не нужен (demo-запуски). */
    StepReporter NONE = (step, description, jpeg, w, h) -> { };
}
```

### 3.2 `ScreenAgent.java` — новая перегрузка + вызов репортера
Сейчас:
```java
    /** Тот же цикл без внешнего лимита времени/колбэка шага — для ручных demo-запусков. */
    public ResultData.AgentReport run(String goal, int maxSteps) {
        return run(goal, maxSteps, Instant.now().plus(Duration.ofMinutes(3)), step -> { });
    }

    /**
     * Крутит цикл до maxSteps шагов (но не дольше deadline).
     * @param onStep колбэк перед каждым шагом (номер шага, начиная с 1) — используется,
     *               например, чтобы показать в трее "выполняю: ... (шаг N)"
     * @return отчёт: достигнута ли цель, сколько шагов, полный трейс.
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
```
Поменять на (добавляем перегрузку с `StepReporter`, старая делегирует в неё с `NONE`):
```java
    /** Тот же цикл без внешнего лимита времени/колбэка шага — для ручных demo-запусков. */
    public ResultData.AgentReport run(String goal, int maxSteps) {
        return run(goal, maxSteps, Instant.now().plus(Duration.ofMinutes(3)), step -> { });
    }

    /** Без живого показа шагов — старое поведение (трей и т.п.), теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }

    /**
     * Крутит цикл до maxSteps шагов (но не дольше deadline).
     * @param onStep       колбэк перед каждым шагом (номер шага, начиная с 1) — используется,
     *                     например, чтобы показать в трее "выполняю: ... (шаг N)"
     * @param stepReporter колбэк ПОСЛЕ каждого шага — с картинкой и описанием действия,
     *                     для живого показа на телефоне
     * @return отчёт: достигнута ли цель, сколько шагов, полный трейс.
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter) {
```
Дальше внутри тела метода (сигнатура которого мы только что расширили) — после строки, где
формируется текст шага в трейс, добавить вызов репортера. Сейчас:
```java
            long dt = System.currentTimeMillis() - t0;
            trace.add("шаг " + step + " (экран " + shot.width + "x" + shot.height + ", " + dt + "мс): " + action);

            if (action.isDone()) {
```
Поменять на:
```java
            long dt = System.currentTimeMillis() - t0;
            trace.add("шаг " + step + " (экран " + shot.width + "x" + shot.height + ", " + dt + "мс): " + action);
            stepReporter.onStep(step, "шаг " + step + ": " + action, shot.jpeg, shot.width, shot.height);

            if (action.isDone()) {
```

### 3.3 `BrowserAgent.java` — та же перегрузка + свой скриншот для показа

DOM-режим картинку не снимает (модели она не нужна), поэтому для показа на телефоне добавляем
отдельный скриншот окна — Selenium умеет это через `TakesScreenshot`. Добавить импорты в начало
файла:
```java
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
```

Сигнатуры — тот же приём, что в 3.2. Сейчас:
```java
    /** Тот же цикл без внешнего лимита времени/колбэка шага — для ручных demo-запусков. */
    public ResultData.AgentReport run(String goal, int maxSteps) {
        return run(goal, maxSteps, Instant.now().plus(Duration.ofMinutes(3)), step -> { });
    }

    /**
     * Крутит цикл до maxSteps шагов (но не дольше deadline). Окно браузера
     * ПОСЛЕ ЗАВЕРШЕНИЯ ОСТАЁТСЯ ОТКРЫТЫМ — чтобы пользователь увидел результат
     * (например, играющее видео); следующая задача переиспользует это же окно.
     * @param onStep колбэк перед каждым шагом (номер шага, начиная с 1) — используется,
     *               например, чтобы показать в трее "выполняю: ... (шаг N)"
     * @return отчёт: достигнута ли цель, сколько шагов, полный трейс.
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
```
Поменять на:
```java
    /** Тот же цикл без внешнего лимита времени/колбэка шага — для ручных demo-запусков. */
    public ResultData.AgentReport run(String goal, int maxSteps) {
        return run(goal, maxSteps, Instant.now().plus(Duration.ofMinutes(3)), step -> { });
    }

    /** Без живого показа шагов — старое поведение, теперь делегирует ниже. */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline, IntConsumer onStep) {
        return run(goal, maxSteps, deadline, onStep, StepReporter.NONE);
    }

    /**
     * Крутит цикл до maxSteps шагов (но не дольше deadline). Окно браузера
     * ПОСЛЕ ЗАВЕРШЕНИЯ ОСТАЁТСЯ ОТКРЫТЫМ — чтобы пользователь увидел результат
     * (например, играющее видео); следующая задача переиспользует это же окно.
     * @param onStep       колбэк перед каждым шагом (номер шага, начиная с 1) — используется,
     *                     например, чтобы показать в трее "выполняю: ... (шаг N)"
     * @param stepReporter колбэк ПОСЛЕ каждого шага — с картинкой и описанием действия,
     *                     для живого показа на телефоне
     * @return отчёт: достигнута ли цель, сколько шагов, полный трейс.
     */
    public ResultData.AgentReport run(String goal, int maxSteps, Instant deadline,
                                       IntConsumer onStep, StepReporter stepReporter) {
```
Дальше внутри тела — после строки с `trace.add(...)` про шаг, добавить вызов скриншота +
репортера. Сейчас:
```java
                long dt = System.currentTimeMillis() - t0;
                trace.add("шаг " + step + " (" + elements.size() + " элементов, " + dt + "мс): " + action);

                if (action.isDone()) {
```
Поменять на:
```java
                long dt = System.currentTimeMillis() - t0;
                trace.add("шаг " + step + " (" + elements.size() + " элементов, " + dt + "мс): " + action);
                reportStep(stepReporter, driver, step, "шаг " + step + ": " + action);

                if (action.isDone()) {
```
И добавить новый приватный метод (например, рядом с `snapshot(...)`):
```java
    /**
     * Скриншот окна браузера ДЛЯ ПОКАЗА на телефоне — не для модели (той хватает
     * списка DOM-элементов, картинка ей не нужна и только замедлила бы запрос).
     * Ошибку скриншота не пробрасываем — шаг без картинки лучше, чем упавшая задача.
     */
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
```

### 3.4 `CommandExecutor.java` — собрать AgentStep и отправить через sender

Добавить импорт: `import com.jarvis.agent.vision.StepReporter;`

Сейчас конструктор не сохраняет `sender` как поле (он уходит только в `ScreenStreamer`):
```java
    private final ScreenStreamer streamer;
    ...
    public CommandExecutor(AgentConfig config, TokenStore tokenStore, AgentTray tray,
                           Consumer<JarvisMessage> sender) throws AWTException {
        this.robot = new Robot();
        this.tray = tray;
        this.brain = new GeminiVisionAgent(new RelayLlmClient(config, tokenStore));
        this.browserAgent = new BrowserAgent(brain);
        this.screenAgent = new ScreenAgent(brain);
        this.streamer = new ScreenStreamer(robot, sender, busyGoal);
    }
```
Поменять на (сохраняем `sender` полем — он ещё нужен для шагов агента):
```java
    private final ScreenStreamer streamer;
    /** Для AgentStep — шаги agent_task шлются сами по себе, как и кадры стрима. */
    private final Consumer<JarvisMessage> sender;
    ...
    public CommandExecutor(AgentConfig config, TokenStore tokenStore, AgentTray tray,
                           Consumer<JarvisMessage> sender) throws AWTException {
        this.robot = new Robot();
        this.tray = tray;
        this.brain = new GeminiVisionAgent(new RelayLlmClient(config, tokenStore));
        this.browserAgent = new BrowserAgent(brain);
        this.screenAgent = new ScreenAgent(brain);
        this.streamer = new ScreenStreamer(robot, sender, busyGoal);
        this.sender = sender;
    }
```

В `runAgentTask(...)` сейчас:
```java
        long start = System.currentTimeMillis();
        ResultData.AgentReport report = switch (task.scope()) {
            case BROWSER -> browserAgent.run(goal, maxSteps, deadline, onStep);
            case SCREEN -> screenAgent.run(goal, maxSteps, deadline, onStep);
        };
```
Поменять на:
```java
        StepReporter stepReporter = (step, description, jpeg, w, h) -> {
            String b64 = jpeg == null ? null : Base64.getEncoder().encodeToString(jpeg);
            sender.accept(new JarvisMessage.AgentStep(
                    requestId, step, description,
                    b64, jpeg == null ? null : ImageFormat.JPEG,
                    jpeg == null ? null : w, jpeg == null ? null : h));
        };

        long start = System.currentTimeMillis();
        ResultData.AgentReport report = switch (task.scope()) {
            case BROWSER -> browserAgent.run(goal, maxSteps, deadline, onStep, stepReporter);
            case SCREEN -> screenAgent.run(goal, maxSteps, deadline, onStep, stepReporter);
        };
```

### Проверка
Собрать и запустить агент. `demo`-классы (`ScreenAgentDemo`, `VisionAgentDemo`) не трогали —
они используют 2-арг `run(goal, maxSteps)`, который как был, так и остался.

---

## Промпт 4 — Приложение (показать AgentStep в чате)

Файл: `C:\diplom\jarvis\androidApp\src\main\kotlin\com\jarvis\app\ChatViewModel.kt`, метод
`handle(msg: JarvisMessage)`. Сейчас:
```kotlin
            is ScreenFrame -> {
                if (msg.seq != null) {
                    ...
                } else {
                    ...
                    add(ChatMessage(author = Author.PC, text = "📸 скриншот", imageBase64 = msg.base64))
                }
            }
            is AuthResult -> {
```
Добавить между ними новую ветку:
```kotlin
            is ScreenFrame -> {
                if (msg.seq != null) {
                    ...
                } else {
                    ...
                    add(ChatMessage(author = Author.PC, text = "📸 скриншот", imageBase64 = msg.base64))
                }
            }
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
            is AuthResult -> {
```
(Многоточия — это уже существующий код внутри веток `ScreenFrame`, его трогать не нужно, важно
только куда вставить новую ветку `AgentStep`.)

### Проверка (финальная, по всем четырём промптам)
1. Пересобрать и переустановить все три части (relay/agent можно не переустанавливать —
   релей просто перезапускается в Docker, агент — обычным way).
2. Дать голосом/текстом команду на agent_task (например «найди на youtube что-нибудь про
   котиков и открой первое видео», scope=BROWSER по умолчанию).
3. В чате должны появляться карточки по мере выполнения — каждая с маленьким скриншотом и
   подписью вроде «🔎 шаг 2: click(3)», а не только один итоговый ответ в конце.
4. То же самое для scope=SCREEN (например, попроси через `agent_task` с явным scope, если есть
   такая возможность в UI/промпте) — картинки должны быть чуть крупнее (реальный экран, не окно
   браузера) и идти от `ScreenAgent`.
5. Отключить Wi-Fi/закрыть чат посреди задачи, снова открыть — старые шаги остались в чате
   (это просто сообщения), не потерялись; ничего не падает.
6. Задача с ошибкой/недостижимой целью — последняя карточка «шаг N: done: <причина>» приходит,
   и следом обычный итоговый `CommandResult` — дублирования/путаницы в чате быть не должно.
