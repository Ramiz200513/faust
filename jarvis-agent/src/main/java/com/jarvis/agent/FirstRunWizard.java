package com.jarvis.agent;

import javax.swing.*;
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
 * строки. Карточки одного окна (CardLayout):
 *   1. приветствие — кнопка «Подключить», которая делает всё сама:
 *      просит у релея одноразовый код (SelfRegistration) и тут же гасит его
 *      обычным Auth через RelayClient;
 *   2. ручной код — запасной путь, если релей запретил самостоятельную
 *      регистрацию (jarvis.open-registration=false) или связь не удалась;
 *   3. готово — сразу кнопка «Привязать телефон» (существующий PairPhoneDialog).
 *
 * Смысл первой карточки в том, что от человека не требуется ничего: скачал
 * инсталлятор, запустил, нажал одну кнопку. Код никуда не делся — он стал
 * внутренней деталью рукопожатия вместо секрета, который кто-то кому-то
 * передаёт. Доступ к ЭТОМУ ПК он всё равно не даёт: телефон привязывается
 * отдельным кодом, который показывает сам этот компьютер.
 *
 * Оформление — общая с мобильным приложением палитра, см. JarvisSwingTheme.
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
    /** Статус показываем на обеих карточках — какая видна, та и покажет. */
    private JLabel welcomeStatus;
    private JLabel codeStatus;
    private JButton autoButton;
    private JButton manualButton;
    private JButton connectButton;
    private JButton backButton;
    private volatile boolean paired = false;

    /** Точка входа: показывает окно в потоке Swing. Безопасно звать из любого потока. */
    public static void show(AgentConfig config, TokenStore tokenStore, CommandExecutor executor,
                             AgentTray tray, AtomicReference<RelayClient> clientRef) {
        SwingUtilities.invokeLater(() ->
                new FirstRunWizard(config, tokenStore, executor, tray, clientRef).setVisible(true));
    }

    private FirstRunWizard(AgentConfig config, TokenStore tokenStore, CommandExecutor executor,
                            AgentTray tray, AtomicReference<RelayClient> clientRef) {
        super((Frame) null, "Faust", true);
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
                        "Закрыть без привязки? Агент завершится.", "Faust",
                        JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
            }
        });

        cardPanel.setBackground(JarvisSwingTheme.BACKGROUND);
        cardPanel.add(buildWelcomeCard(), "welcome");
        cardPanel.add(buildCodeCard(), "code");
        setContentPane(cardPanel);
        JarvisSwingTheme.styleWindow(cardPanel);
        setSize(440, 480);
        setLocationRelativeTo(null);
        FaustWindows.darkTitleBar(this);
    }

    // --- Шаг 1: приветствие и автоматическое подключение ---------------------

    private JPanel buildWelcomeCard() {
        JPanel panel = JarvisSwingTheme.column(28);

        JLabel logo = new JLabel(FaustWindows.logo(64));
        logo.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

        welcomeStatus = JarvisSwingTheme.status();

        autoButton = JarvisSwingTheme.filledButton("Подключить");
        autoButton.addActionListener(e -> autoConnect());
        getRootPane().setDefaultButton(autoButton);

        manualButton = JarvisSwingTheme.textButton("У меня есть код от администратора");
        manualButton.addActionListener(e -> cards.show(cardPanel, "code"));

        panel.add(logo);
        panel.add(JarvisSwingTheme.gap(10));
        panel.add(JarvisSwingTheme.title("Добро пожаловать в «Фауст»"));
        panel.add(JarvisSwingTheme.gap(14));
        panel.add(JarvisSwingTheme.body("Управляйте этим компьютером с телефона: голосом, текстом, "
                + "видеть экран и передавать файлы.\n\n"
                + "Нажмите «Подключить» — компьютер сам зарегистрируется на сервере."));
        panel.add(JarvisSwingTheme.gap(14));
        panel.add(buildAdvancedPanel());
        panel.add(Box.createVerticalGlue());
        panel.add(welcomeStatus);
        panel.add(JarvisSwingTheme.gap(10));
        panel.add(autoButton);
        panel.add(JarvisSwingTheme.gap(6));
        panel.add(manualButton);
        return panel;
    }

    /**
     * Весь первый запуск одной кнопкой: просим у релея одноразовый код на себя
     * и сразу же предъявляем его в Auth. Сеть — в фоновом потоке, чтобы окно
     * не подвисало.
     */
    private void autoConnect() {
        String relayUrl = chosenRelayUrl();
        setBusy(true);
        setStatus("Подключение…", JarvisSwingTheme.ON_SURFACE_VARIANT);

        Thread worker = new Thread(() -> {
            try {
                String code = SelfRegistration.requestCode(relayUrl, SelfRegistration.defaultLabel());
                SwingUtilities.invokeLater(() -> startClient(relayUrl, code));
            } catch (SelfRegistration.Unavailable e) {
                SwingUtilities.invokeLater(() -> onAutoFailed(e));
            }
        }, "jarvis-self-registration");
        worker.setDaemon(true);
        worker.start();
    }

    private void onAutoFailed(SelfRegistration.Unavailable e) {
        setBusy(false);
        if (e.forbidden) {
            // Релей закрыт для самостоятельной регистрации — дальше только
            // код, выписанный администратором. Показываем карточку с полем.
            setStatus("Нужен код от администратора сервера", JarvisSwingTheme.ERROR);
            cards.show(cardPanel, "code");
        } else {
            setStatus("Не удалось подключиться: " + e.getMessage(), JarvisSwingTheme.ERROR);
        }
    }

    // --- Шаг 2: код подключения вручную --------------------------------------

    private JPanel buildCodeCard() {
        JPanel panel = JarvisSwingTheme.column(28);

        codeField = JarvisSwingTheme.field("", 220, true);
        codeStatus = JarvisSwingTheme.status();

        connectButton = JarvisSwingTheme.filledButton("Подключить");
        connectButton.addActionListener(e -> connectWithTypedCode());

        backButton = JarvisSwingTheme.textButton("Назад");
        backButton.addActionListener(e -> cards.show(cardPanel, "welcome"));

        panel.add(JarvisSwingTheme.title("Код подключения"));
        panel.add(JarvisSwingTheme.gap(14));
        panel.add(JarvisSwingTheme.body("Введите одноразовый код, который вам дал администратор "
                + "сервера (6 цифр, действует ограниченное время)."));
        panel.add(JarvisSwingTheme.gap(16));
        panel.add(codeField);
        panel.add(Box.createVerticalGlue());
        panel.add(codeStatus);
        panel.add(JarvisSwingTheme.gap(10));
        panel.add(connectButton);
        panel.add(JarvisSwingTheme.gap(6));
        panel.add(backButton);
        return panel;
    }

    private void connectWithTypedCode() {
        String code = codeField.getText().trim();
        if (code.isEmpty()) {
            setStatus("Введите код", JarvisSwingTheme.ERROR);
            return;
        }
        setBusy(true);
        setStatus("Подключение…", JarvisSwingTheme.ON_SURFACE_VARIANT);
        startClient(chosenRelayUrl(), code);
    }

    /** Свёрнутое по умолчанию поле адреса релея — трогать нужно редко (свой сервер, тестовый стенд). */
    private JPanel buildAdvancedPanel() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setBackground(JarvisSwingTheme.BACKGROUND);
        wrap.setAlignmentX(Component.CENTER_ALIGNMENT);

        relayUrlField = JarvisSwingTheme.field(config.relayUrl(), 320, false);
        relayUrlField.setVisible(false);

        JButton toggle = JarvisSwingTheme.textButton("Другой адрес сервера");
        toggle.addActionListener(e -> {
            boolean show = !relayUrlField.isVisible();
            relayUrlField.setVisible(show);
            toggle.setText(show ? "Скрыть адрес сервера" : "Другой адрес сервера");
        });

        wrap.add(toggle);
        wrap.add(relayUrlField);
        return wrap;
    }

    /** Адрес релея: что вписали в свёрнутом поле, иначе — из agent.properties. */
    private String chosenRelayUrl() {
        String relayUrl = relayUrlField.getText().trim();
        if (relayUrl.isEmpty()) {
            relayUrl = config.relayUrl();
        }
        if (!relayUrl.equals(config.relayUrl())) {
            saveRelayUrl(relayUrl);
        }
        return relayUrl;
    }

    /** Общий для обоих путей шаг: предъявить код релею и ждать ответа. */
    private void startClient(String relayUrl, String code) {
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
                SwingUtilities.invokeLater(() -> setStatus("Нет связи с сервером"
                        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "")
                        + " — повторяем…", JarvisSwingTheme.ERROR));
            }
        });
        client.start();
    }

    private void onPaired(RelayClient client) {
        paired = true;
        client.setStatusListener(tray); // дальше статус связи ведёт трей, как обычно
        cardPanel.add(buildDoneCard(), "done");
        cards.show(cardPanel, "done");
    }

    private void onRejected(String reason) {
        setBusy(false);
        setStatus("Код не подошёл: " + (reason != null ? reason : "неизвестная причина"),
                JarvisSwingTheme.ERROR);
    }

    // --- Шаг 3: готово --------------------------------------------------------

    private JPanel buildDoneCard() {
        JPanel panel = JarvisSwingTheme.column(28);

        JLabel check = JarvisSwingTheme.glyph("✓", 34f, JarvisSwingTheme.TERTIARY);

        JButton pairPhone = JarvisSwingTheme.filledButton("Привязать телефон");
        pairPhone.addActionListener(e -> {
            dispose();
            PairPhoneDialog.open(config, tokenStore);
        });

        JButton later = JarvisSwingTheme.textButton("Позже — из значка в трее");
        later.addActionListener(e -> dispose());

        panel.add(check);
        panel.add(JarvisSwingTheme.gap(10));
        panel.add(JarvisSwingTheme.title("Компьютер подключен"));
        panel.add(JarvisSwingTheme.gap(14));
        panel.add(JarvisSwingTheme.body("Осталось привязать телефон — отсканируйте QR-код "
                + "или введите код, который сейчас появится."));
        panel.add(Box.createVerticalGlue());
        panel.add(pairPhone);
        panel.add(JarvisSwingTheme.gap(6));
        panel.add(later);
        return panel;
    }

    // --- мелочи -----------------------------------------------------------

    /** Пишет одно и то же в обе карточки — не важно, какая сейчас видна. */
    private void setStatus(String text, Color color) {
        for (JLabel label : new JLabel[] {welcomeStatus, codeStatus}) {
            if (label != null) {
                label.setForeground(color);
                label.setText(text);
            }
        }
    }

    /** Пока идёт попытка подключения — не даём нажать второй раз. */
    private void setBusy(boolean busy) {
        for (JComponent c : new JComponent[] {autoButton, manualButton, connectButton, backButton,
                codeField, relayUrlField}) {
            if (c != null) c.setEnabled(!busy);
        }
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
