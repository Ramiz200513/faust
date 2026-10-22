# Приложение для ПК «из коробки»: окно приветствия + привязка

## Общий контекст

Задача: человек скачивает агента (позже — тот самый `.msi`-инсталлятор из
`DEPLOY_PUBLIC_RELAY_PROMPTS.md`), запускает его, и у него сразу «все
возможности» — единственное, что остаётся сделать руками, это привязать
телефон.

Разобрался, что уже есть, прежде чем что-то менять — оказалось, ключевая
часть уже готова и работает:

- **Привязка ТЕЛЕФОНА к уже привязанному ПК — уже полностью самообслуживание.**
  `PairPhoneDialog.java` (окно «Привязать телефон» из трея) сам получает
  одноразовый код через `POST /api/agent/phone-pairing-code`, авторизуясь
  собственными `deviceId`/`token` агента (см. `AgentPairingController.java`
  + `AgentAuthService.java`, оба уже в проекте) — админский секрет для этого
  не нужен вообще. Показывает код текстом и QR. Ничего менять не нужно.
- **Привязка ПК (первый запуск, ещё нет токена) — сейчас чисто CLI.**
  `AgentMain` берёт код одноразовой привязки вторым аргументом командной
  строки (`gradlew run --args="ws://... <код>"`), либо (если код не передан
  и токена ещё нет) `RelayClient` просто печатает в консоль просьбу
  перезапуститься с кодом — окна для этого нет. Сам код по-прежнему должен
  выписать администратор релея через `POST /api/pairing-codes` (защищён
  `jarvis.admin-token`) — это единственный секрет, не пускающий кого попало
  регистрировать себя «агентом» под видом чужого ПК, и убирать его из
  протокола в рамках диплома не будем (тот же компромисс, что уже принят
  для `DEPLOY_PUBLIC_RELAY_PROMPTS.md`: «регистрация нового ПК всё ещё
  требует админ-токен — приемлемо для дипломной демонстрации»). Меняем
  только то, КАК человек вводит этот код — не консоль, а окно.

Итог: делаем `FirstRunWizard.java` — окно в три шага (`CardLayout`):
1. **Приветствие** — короткое описание, что умеет Jarvis.
2. **Код подключения** — поле ввода (тот же одноразовый код, что раньше шёл
   аргументом командной строки), скрытое по умолчанию поле «адрес сервера»
   для нетипичных случаев (свой релей, тестовый стенд). По нажатию
   «Подключить» окно само создаёт `RelayClient` с этим кодом — используя
   ровно тот же путь авторизации, что уже отлажен для CLI-режима — и ждёт
   ответа релея.
3. **Готово** — как только `RelayClient` подтвердил привязку (получен и
   сохранён постоянный токен), сразу кнопка «📱 Привязать телефон»,
   которая открывает уже существующий `PairPhoneDialog`.

`AgentMain` показывает это окно ТОЛЬКО когда токена ещё нет и код не
передан аргументом — обычный повторный запуск уже привязанного ПК его не
увидит вообще, идёт как раньше.

Заодно — раз уж окно привязки появилось — добавляем в трей пункт
«Настроить заново» (сброс токена), чтобы было куда отправить человека,
если ПК отвязали на сервере (сообщение об этом в трее раньше отправляло
к `start-jarvis.bat`, этого скрипта в GUI-сценарии для конечного
пользователя не будет).

**Если вы уже применяли `DEPLOY_PUBLIC_RELAY_PROMPTS.md`**: та часть этого
документа, что называется `AgentSetupDialog.java`, решала ту же задачу
более простым (одна форма, без приветствия) окном — этот промпт её
заменяет. Если `AgentSetupDialog.java` уже создан, просто не создавайте
его (или удалите) и не меняйте `AgentMain`/`AgentTray`/`TokenStore` по
инструкциям ИЗ ТОГО документа для этих трёх файлов — берите версии из
этого промпта. Остальное в `DEPLOY_PUBLIC_RELAY_PROMPTS.md` (Caddy,
docker-compose для сервера, смена `DEFAULT_RELAY_URL`, инсталлятор через
jpackage) никак не пересекается и применяется независимо.

Порядок промптов обязателен: `TokenStore` → `RelayClient` → `AgentTray` →
новый `FirstRunWizard` → `AgentMain` (последний использует все остальные).

---

## Промпт 1 — `TokenStore.java`: сброс привязки

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/TokenStore.java`

Добавить метод после `saveDeviceId()`:

```java
    /** Сохранить deviceId (не затирая токен). */
    public void saveDeviceId(String deviceId) {
        write(KEY_DEVICE_ID, deviceId);
    }

    /** Стереть токен и deviceId — используется пунктом трея «Настроить заново»:
     *  агент как будто ни разу не привязывался, следующий запуск снова
     *  покажет окно приветствия. */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("Не удалось удалить " + file + ": " + e.getMessage());
        }
    }
```

(Импорты `Files`/`IOException` в этом файле уже есть.)

---

## Промпт 2 — `RelayClient.java`: сообщать о сбое ПЕРВОГО подключения

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/RelayClient.java`

Сейчас если релей недоступен уже на первой попытке (DNS, connection
refused и т.п.), `connect()` тихо планирует реконнект, ничего не сообщая
`statusListener` — раньше это было не страшно (трей просто продолжал
показывать «подключение…»), но в окне приветствия человек должен увидеть
осмысленное сообщение, а не бесконечную вращалку. Один метод, одна строка:

```java
    private void connect() {
        System.out.println("Connecting to " + relayUri + " ...");
        statusListener.onConnecting();
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(relayUri, new Listener())
                .exceptionally(err -> {          // connect failed -> retry later
                    statusListener.onDisconnected(err.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }
```

(Добавлена ровно одна строка — `statusListener.onDisconnected(err.getMessage());`.)

---

## Промпт 3 — `AgentTray.java`: пункт «Настроить заново»

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AgentTray.java`

**3.1.** Второй колбэк в конструктор:

```java
// было
    private final Path logFile;
    private final Runnable onPairPhone;
    private TrayIcon trayIcon;
// стало
    private final Path logFile;
    private final Runnable onPairPhone;
    private final Runnable onResetPairing;
    private TrayIcon trayIcon;
```

```java
// было
    public AgentTray(Path logFile, Runnable onPairPhone) {
        this.logFile = logFile;
        this.onPairPhone = onPairPhone;
    }
// стало
    public AgentTray(Path logFile, Runnable onPairPhone, Runnable onResetPairing) {
        this.logFile = logFile;
        this.onPairPhone = onPairPhone;
        this.onResetPairing = onResetPairing;
    }
```

**3.2.** Пункт меню — между «Открыть лог» и разделителем:

```java
// было
        MenuItem logItem = new MenuItem("Открыть лог");
        logItem.addActionListener(e -> openLog());
        menu.add(logItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("Выход");
// стало
        MenuItem logItem = new MenuItem("Открыть лог");
        logItem.addActionListener(e -> openLog());
        menu.add(logItem);

        MenuItem resetItem = new MenuItem("Настроить заново");
        resetItem.addActionListener(e -> onResetPairing.run());
        menu.add(resetItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("Выход");
```

**3.3.** Сообщение об отвязке больше не ссылается на CLI-скрипт:

```java
// было
            trayIcon.displayMessage(APP_NAME,
                    "ПК отвязан на сервере — запусти start-jarvis.bat для перепривязки",
                    TrayIcon.MessageType.WARNING);
// стало
            trayIcon.displayMessage(APP_NAME,
                    "ПК отвязан на сервере — выбери «Настроить заново» в трее",
                    TrayIcon.MessageType.WARNING);
```

---

## Промпт 4 — новый файл `FirstRunWizard.java`

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/FirstRunWizard.java` (новый)

```java
package com.jarvis.agent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Окно первого запуска: показывается, когда агент ещё ни разу не привязан
 * (нет сохранённого токена) и код привязки не передан аргументом командной
 * строки. Три шага в одном окне (CardLayout):
 *   1. приветствие;
 *   2. ввод одноразового кода (тот же код, что раньше вводили только через
 *      аргумент командной строки) — по кнопке сам создаёт RelayClient и
 *      слушает его статус;
 *   3. готово — сразу кнопка «Привязать телефон» (существующий PairPhoneDialog).
 *
 * Код по-прежнему нужен — единственный секрет, не дающий кому попало
 * зарегистрировать "свой" ПК под видом агента; администратор релея
 * выписывает его владельцу нового ПК заранее (POST /api/pairing-codes).
 * Это окно не убирает этот шаг, а даёт для него интерфейс вместо CLI.
 */
public class FirstRunWizard extends JDialog {

    private final AgentConfig config;
    private final TokenStore tokenStore;
    private final CommandExecutor executor;
    private final AgentTray tray;
    private final AtomicReference<RelayClient> clientRef;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private JTextField codeField;
    private JTextField relayUrlField;
    private JLabel statusLabel;
    private JButton connectButton;
    private volatile boolean paired = false;

    /** Точка входа: показывает окно в потоке Swing. Безопасно звать из любого потока. */
    public static void show(AgentConfig config, TokenStore tokenStore, CommandExecutor executor,
                             AgentTray tray, AtomicReference<RelayClient> clientRef) {
        SwingUtilities.invokeLater(() ->
                new FirstRunWizard(config, tokenStore, executor, tray, clientRef).setVisible(true));
    }

    private FirstRunWizard(AgentConfig config, TokenStore tokenStore, CommandExecutor executor,
                            AgentTray tray, AtomicReference<RelayClient> clientRef) {
        super((Frame) null, "Добро пожаловать в Jarvis", true);
        this.config = config;
        this.tokenStore = tokenStore;
        this.executor = executor;
        this.tray = tray;
        this.clientRef = clientRef;

        // Закрывать окно (крестиком) до успешной привязки — значит выйти из
        // агента совсем, иначе он останется висеть в трее без токена и без
        // способа его получить. После привязки крестик просто закрывает окно.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (paired) {
                    dispose();
                    return;
                }
                int choice = JOptionPane.showConfirmDialog(FirstRunWizard.this,
                        "Закрыть без привязки? Агент завершится.", "Jarvis",
                        JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
            }
        });

        cardPanel.add(buildWelcomeCard(), "welcome");
        cardPanel.add(buildCodeCard(), "code");
        setContentPane(cardPanel);
        setSize(420, 380);
        setLocationRelativeTo(null);
    }

    // --- Шаг 1: приветствие -------------------------------------------------

    private JPanel buildWelcomeCard() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel("Добро пожаловать в Jarvis!");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel body = wrappedLabel("Jarvis позволяет управлять этим компьютером с телефона: "
                + "голосом, текстом, видеть экран и передавать файлы. "
                + "Чтобы начать, нужно один раз подключить этот компьютер к серверу.");

        JButton next = new JButton("Далее →");
        next.setAlignmentX(Component.CENTER_ALIGNMENT);
        next.addActionListener(e -> cards.show(cardPanel, "code"));

        panel.add(title);
        panel.add(Box.createVerticalStrut(16));
        panel.add(body);
        panel.add(Box.createVerticalGlue());
        panel.add(next);
        return panel;
    }

    // --- Шаг 2: код подключения ----------------------------------------------

    private JPanel buildCodeCard() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel("Код подключения");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel body = wrappedLabel("Введите одноразовый код, который вам дал администратор "
                + "сервера (6 цифр, действует ограниченное время).");

        codeField = new JTextField();
        codeField.setAlignmentX(Component.CENTER_ALIGNMENT);
        codeField.setMaximumSize(new Dimension(200, 32));
        codeField.setHorizontalAlignment(JTextField.CENTER);
        codeField.setFont(codeField.getFont().deriveFont(Font.BOLD, 18f));

        JPanel advanced = buildAdvancedPanel();

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        connectButton = new JButton("Подключить");
        connectButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        connectButton.addActionListener(e -> attemptConnect());
        getRootPane().setDefaultButton(connectButton);

        panel.add(title);
        panel.add(Box.createVerticalStrut(12));
        panel.add(body);
        panel.add(Box.createVerticalStrut(12));
        panel.add(codeField);
        panel.add(Box.createVerticalStrut(8));
        panel.add(advanced);
        panel.add(Box.createVerticalGlue());
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(connectButton);
        return panel;
    }

    /** Свёрнутое по умолчанию поле адреса релея — трогать нужно редко (свой сервер, тестовый стенд). */
    private JPanel buildAdvancedPanel() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setAlignmentX(Component.CENTER_ALIGNMENT);

        relayUrlField = new JTextField(config.relayUrl());
        relayUrlField.setMaximumSize(new Dimension(320, 28));
        relayUrlField.setAlignmentX(Component.CENTER_ALIGNMENT);
        relayUrlField.setVisible(false);

        JButton toggle = new JButton("Адрес сервера ▸");
        toggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        toggle.addActionListener(e -> {
            boolean show = !relayUrlField.isVisible();
            relayUrlField.setVisible(show);
            toggle.setText(show ? "Адрес сервера ▾" : "Адрес сервера ▸");
            pack();
        });

        wrap.add(toggle);
        wrap.add(relayUrlField);
        return wrap;
    }

    private void attemptConnect() {
        String code = codeField.getText().trim();
        if (code.isEmpty()) {
            statusLabel.setForeground(new Color(150, 40, 40));
            statusLabel.setText("Введите код");
            return;
        }

        String relayUrl = relayUrlField.getText().trim();
        if (relayUrl.isEmpty()) relayUrl = config.relayUrl();
        if (!relayUrl.equals(config.relayUrl())) {
            saveRelayUrl(relayUrl);
        }

        codeField.setEnabled(false);
        relayUrlField.setEnabled(false);
        connectButton.setEnabled(false);
        statusLabel.setForeground(new Color(90, 90, 90));
        statusLabel.setText("Подключение…");

        RelayClient client = new RelayClient(URI.create(relayUrl), code, executor, tokenStore);
        clientRef.set(client);
        client.setStatusListener(new RelayStatusListener() {
            @Override
            public void onConnected() {
                SwingUtilities.invokeLater(() -> onPaired(client));
            }

            @Override
            public void onAuthRejected(String reason) {
                SwingUtilities.invokeLater(() -> onRejected(reason));
            }

            @Override
            public void onDisconnected(String reason) {
                // До первого успешного ответа это просто "сервер пока недоступен,
                // пробуем ещё" — RelayClient сам переподключается с задержкой.
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setForeground(new Color(150, 40, 40));
                    statusLabel.setText("Нет связи с сервером"
                            + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "")
                            + " — повторяем…");
                });
            }
        });
        client.start();
    }

    private void onPaired(RelayClient client) {
        paired = true;
        client.setStatusListener(tray); // дальше статус связи ведёт трей, как обычно
        cardPanel.add(buildDoneCard(), "done");
        cards.show(cardPanel, "done");
        pack();
    }

    private void onRejected(String reason) {
        statusLabel.setForeground(new Color(150, 40, 40));
        statusLabel.setText("Код не подошёл: " + (reason != null ? reason : "неизвестная причина"));
        codeField.setEnabled(true);
        relayUrlField.setEnabled(true);
        connectButton.setEnabled(true);
    }

    // --- Шаг 3: готово --------------------------------------------------------

    private JPanel buildDoneCard() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel("Компьютер подключен ✓");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel body = wrappedLabel("Осталось привязать телефон — отсканируйте QR-код "
                + "или введите код, который сейчас появится.");

        JButton pairPhone = new JButton("📱 Привязать телефон");
        pairPhone.setAlignmentX(Component.CENTER_ALIGNMENT);
        pairPhone.addActionListener(e -> {
            dispose();
            PairPhoneDialog.open(config, tokenStore);
        });

        JButton later = new JButton("Позже (из трея)");
        later.setAlignmentX(Component.CENTER_ALIGNMENT);
        later.addActionListener(e -> dispose());

        panel.add(title);
        panel.add(Box.createVerticalStrut(12));
        panel.add(body);
        panel.add(Box.createVerticalGlue());
        panel.add(pairPhone);
        panel.add(Box.createVerticalStrut(8));
        panel.add(later);
        return panel;
    }

    // --- мелочи -----------------------------------------------------------

    private static JLabel wrappedLabel(String text) {
        JLabel label = new JLabel("<html><div style='text-align:center;width:320px'>" + text + "</div></html>");
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /** Сохраняет изменённый адрес релея в agent.properties, не трогая остальные ключи
     *  (тот же приём чтения/записи, что уже в TokenStore). */
    private static void saveRelayUrl(String relayUrl) {
        Path file = Paths.get(System.getProperty("user.home"), ".jarvis-agent", "agent.properties");
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        props.setProperty("relayUrl", relayUrl);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Настройки агента jarvis.");
            }
        } catch (IOException e) {
            System.err.println("Не удалось сохранить " + file + ": " + e.getMessage());
        }
    }
}
```

---

## Промпт 5 — `AgentMain.java`: показать окно, когда токена ещё нет

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AgentMain.java`

```java
// было
        TokenStore tokenStore = new TokenStore();
        AgentTray tray = new AgentTray(AgentLog.filePath(), () -> PairPhoneDialog.open(config, tokenStore));
        tray.install();

        // Зависимость кольцевая: клиенту нужен исполнитель команд, а исполнителю —
        // send() клиента, чтобы слать кадры живого экрана самому. Разрываем её
        // ссылкой, которую заполняем сразу после создания клиента.
        AtomicReference<RelayClient> clientRef = new AtomicReference<>();
        Consumer<JarvisMessage> sender = msg -> {
            RelayClient c = clientRef.get();
            if (c != null) c.send(msg);
        };

        CommandExecutor executor = new CommandExecutor(config, tokenStore, tray, sender);
        RelayClient client = new RelayClient(URI.create(config.relayUrl()), pairingCode, executor, tokenStore);
        clientRef.set(client);
        client.setStatusListener(tray);

        client.start();
        if (openPairWindow) {
            PairPhoneDialog.open(config, tokenStore);
        }
// стало
        TokenStore tokenStore = new TokenStore();
        AgentTray tray = new AgentTray(AgentLog.filePath(),
                () -> PairPhoneDialog.open(config, tokenStore),
                () -> resetPairing(tokenStore));
        tray.install();

        // Зависимость кольцевая: клиенту нужен исполнитель команд, а исполнителю —
        // send() клиента, чтобы слать кадры живого экрана самому. Разрываем её
        // ссылкой, которую заполняем сразу после создания клиента.
        AtomicReference<RelayClient> clientRef = new AtomicReference<>();
        Consumer<JarvisMessage> sender = msg -> {
            RelayClient c = clientRef.get();
            if (c != null) c.send(msg);
        };

        CommandExecutor executor = new CommandExecutor(config, tokenStore, tray, sender);

        // Первый запуск без сохранённого токена и без кода аргументом — раньше
        // это значило "напечатать в консоль просьбу передать код и остановиться"
        // (см. RelayClient.Listener.onOpen). Теперь вместо этого показываем окно
        // приветствия — оно само создаст RelayClient с введённым там кодом.
        if (tokenStore.load() == null && pairingCode == null) {
            FirstRunWizard.show(config, tokenStore, executor, tray, clientRef);
        } else {
            RelayClient client = new RelayClient(URI.create(config.relayUrl()), pairingCode, executor, tokenStore);
            clientRef.set(client);
            client.setStatusListener(tray);
            client.start();
        }

        if (openPairWindow) {
            PairPhoneDialog.open(config, tokenStore);
        }
```

И добавить приватный метод в конец класса (перед закрывающей скобкой):

```java
    /** Пункт трея «Настроить заново»: подтверждение, сброс токена, просьба перезапустить агент. */
    private static void resetPairing(TokenStore tokenStore) {
        int choice = javax.swing.JOptionPane.showConfirmDialog(null,
                "Сбросить привязку этого ПК? Понадобится новый код от администратора сервера.",
                "Jarvis", javax.swing.JOptionPane.YES_NO_OPTION);
        if (choice != javax.swing.JOptionPane.YES_OPTION) return;
        tokenStore.clear();
        javax.swing.JOptionPane.showMessageDialog(null,
                "Токен сброшен. Закройте и снова откройте Jarvis Agent, чтобы привязать заново.",
                "Jarvis", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }
```

---

## Проверка

- [ ] Собирается: `TokenStore`/`RelayClient`/`AgentTray`/`AgentMain` компилируются, новый `FirstRunWizard` тоже.
- [ ] Удалить (или переименовать) `$HOME/.jarvis-agent/token.properties`, запустить агент БЕЗ аргументов → появляется окно «Добро пожаловать в Jarvis!» вместо тихого зависания в консоли.
- [ ] «Далее» → карточка с полем кода. Ввести код, которого не существует / уже использованный → «Код не подошёл: ...», поле и кнопка снова активны, можно ввести другой код без перезапуска агента.
- [ ] Ввести код, действительно выписанный `POST /api/pairing-codes` (agentId не указан) → «Подключение…» → в течение пары секунд карточка «Компьютер подключен ✓».
- [ ] После этого в `token.properties` появились `token` и `deviceId` — при следующем запуске агента (без аргументов) окно приветствия уже НЕ появляется, всё как раньше.
- [ ] На карточке «готово» нажать «📱 Привязать телефон» → открывается уже знакомый `PairPhoneDialog` с QR — отсканировать телефоном, привязка проходит как раньше (это старый код, не менялся).
- [ ] На релее временно остановить контейнер/сервис (или указать заведомо недоступный адрес в свёрнутом поле «Адрес сервера») → в окне вместо вечного «Подключение…» появляется «Нет связи с сервером (...) — повторяем…».
- [ ] Крестиком закрыть окно ДО успешной привязки → спрашивает подтверждение «Закрыть без привязки? Агент завершится» → «Да» действительно завершает процесс агента (проверить, что он не остался висеть в трее).
- [ ] В трее уже привязанного ПК выбрать «Настроить заново» → подтвердить → информационное сообщение о необходимости перезапуска → закрыть и снова открыть агент → появляется окно приветствия заново (токен действительно стёрт).
- [ ] Обычный повторный запуск уже привязанного агента (без сброса) по-прежнему НЕ показывает окно приветствия и подключается сразу, как до этого промпта.
