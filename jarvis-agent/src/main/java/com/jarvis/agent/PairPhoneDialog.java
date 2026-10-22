package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Окно «Привязать телефон»: просит у релея одноразовый код для телефона и
 * показывает его QR-кодом вместе с адресом, по которому телефон достучится
 * до релея.
 *
 * Строка в QR — строго в формате, который уже понимает приложение
 * (см. PairingQr.kt в :shared/androidApp):
 *   jarvis-pair://pair?code=<код>&relay=<ws-адрес>&name=<имя ПК>
 */
public class PairPhoneDialog extends JDialog {

    private static final int QR_SIZE = 280;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Вариант адреса в выпадающем списке: что показать человеку и что положить
     * в QR. mac — того же адаптера, что и адрес (может быть null), телефон по
     * нему будит ПК magic-пакетом.
     */
    private record AddressOption(String label, String relayUrl, String mac) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final AgentConfig config;
    private final TokenStore tokenStore;
    private final ObjectMapper mapper = new ObjectMapper();

    private final JLabel qrLabel = new JLabel();
    private final JLabel codeLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel countdownLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JComboBox<AddressOption> addressBox = new JComboBox<>();

    private String code;
    private Instant expiresAt;
    private Timer countdown;
    /** Полоса срока действия кода — тает, как в окне подтверждения. */
    private final FaustWindows.CountdownBar expiryBar = new FaustWindows.CountdownBar(QR_SIZE);
    private long totalSeconds = 1;

    /**
     * Точка входа: проверяет, что сам ПК уже привязан, и показывает окно.
     * Безопасно вызывать из любого потока.
     */
    public static void open(AgentConfig config, TokenStore tokenStore) {
        SwingUtilities.invokeLater(() -> {
            if (tokenStore.load() == null || tokenStore.loadDeviceId() == null) {
                JOptionPane.showMessageDialog(null,
                        "Сначала нужно подключить сам ПК — выбери «Настроить заново» в трее и перезапусти Jarvis.",
                        "Faust", JOptionPane.WARNING_MESSAGE);
                return;
            }
            new PairPhoneDialog(config, tokenStore).setVisible(true);
        });
    }

    private PairPhoneDialog(AgentConfig config, TokenStore tokenStore) {
        this(config, tokenStore, true);
    }

    /** @param online false — без запроса кода у релея (только для предпросмотра UiPreview) */
    private PairPhoneDialog(AgentConfig config, TokenStore tokenStore, boolean online) {
        super((java.awt.Frame) null, "Привязать телефон", false);
        this.config = config;
        this.tokenStore = tokenStore;

        buildUi();
        if (online) {
            fillAddresses();
            requestNewCode();
        }

        pack();
        setLocationRelativeTo(null);
        FaustWindows.darkTitleBar(this);
    }

    private void buildUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = JarvisSwingTheme.column(20);

        qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrLabel.setPreferredSize(new Dimension(QR_SIZE, QR_SIZE));
        // QR остаётся чёрным по белому (иначе камера его не возьмёт), но
        // лежит на скруглённой карточке — как карточки в приложении.
        JPanel qrCard = JarvisSwingTheme.card(qrLabel, 14);

        codeLabel.setFont(JarvisSwingTheme.font(Font.BOLD, 30f));
        codeLabel.setForeground(JarvisSwingTheme.PRIMARY);
        codeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        countdownLabel.setFont(JarvisSwingTheme.font(Font.PLAIN, 12f));
        countdownLabel.setForeground(JarvisSwingTheme.ON_SURFACE_VARIANT);
        countdownLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JarvisSwingTheme.style(addressBox);
        // Адрес сменили — код тот же, просто перерисовываем QR с другим relay.
        addressBox.addActionListener(e -> renderQr());

        JButton refresh = JarvisSwingTheme.outlinedButton("Новый код");
        refresh.addActionListener(e -> requestNewCode());

        JLabel logo = new JLabel(FaustWindows.logo(48));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(logo);
        root.add(JarvisSwingTheme.gap(10));
        root.add(JarvisSwingTheme.title("Привязать телефон"));
        root.add(JarvisSwingTheme.gap(6));
        root.add(JarvisSwingTheme.body("Отсканируйте его в приложении «Фауст»."));
        root.add(JarvisSwingTheme.gap(14));
        root.add(qrCard);
        root.add(JarvisSwingTheme.gap(12));
        JLabel orCode = JarvisSwingTheme.status();
        orCode.setText("или введите код вручную");
        root.add(orCode);
        root.add(codeLabel);
        root.add(JarvisSwingTheme.gap(6));
        expiryBar.setMaximumSize(new Dimension(QR_SIZE, 4));
        expiryBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        root.add(expiryBar);
        root.add(JarvisSwingTheme.gap(4));
        root.add(countdownLabel);
        root.add(JarvisSwingTheme.gap(12));
        root.add(addressBox);
        root.add(JarvisSwingTheme.gap(12));
        root.add(refresh);

        setContentPane(root);
        JarvisSwingTheme.styleWindow(root);
    }

    /** Собирает список адресов: сперва явный из конфига, затем автоопределённые. */
    private void fillAddresses() {
        List<AddressOption> options = new ArrayList<>();
        if (config.phoneRelayUrl() != null) {
            options.add(new AddressOption(config.phoneRelayUrl() + "  (из agent.properties)",
                    config.phoneRelayUrl(), null));
        }
        for (LanAddress.Candidate c : LanAddress.discover()) {
            String suffix = c.tailscale() ? ", Tailscale" : "";
            options.add(new AddressOption(c.address() + "  (" + c.adapterName() + suffix + ")",
                    relayUrlForHost(c.address()), c.mac()));
        }

        for (AddressOption option : options) {
            addressBox.addItem(option);
        }
        addressBox.setEnabled(options.size() > 1);

        if (options.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось определить адрес этого ПК в сети.\n"
                            + "Укажи его вручную: phoneRelayUrl=ws://<IP>:8080/ws в agent.properties.",
                    "Faust", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Подставляет найденный IP в адрес релея, сохраняя схему, порт и путь из relayUrl. */
    private String relayUrlForHost(String host) {
        URI base = URI.create(config.relayUrl());
        String path = (base.getPath() == null || base.getPath().isEmpty()) ? "/ws" : base.getPath();
        String port = base.getPort() > 0 ? ":" + base.getPort() : "";
        return base.getScheme() + "://" + host + port + path;
    }

    /** ws://host:8080/ws -> http://host:8080 (путь к REST свой, поэтому берём только схему, хост и порт). */
    static String httpBase(String relayWsUrl) {
        URI uri = URI.create(relayWsUrl);
        String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        return scheme + "://" + uri.getHost() + port;
    }

    private void requestNewCode() {
        String deviceId = tokenStore.loadDeviceId();
        String token = tokenStore.load();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(httpBase(config.relayUrl()) + "/api/agent/phone-pairing-code"))
                    .header("X-Device-Id", deviceId)
                    .header("X-Device-Token", token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                showError("Релей не узнал этот ПК (401). Похоже, он отвязан — выбери «Настроить заново» в трее.");
                return;
            }
            if (response.statusCode() != 200) {
                showError("Релей ответил " + response.statusCode() + ". Подробности в логе.");
                System.err.println("phone-pairing-code: HTTP " + response.statusCode() + " " + response.body());
                return;
            }

            JsonNode json = mapper.readTree(response.body());
            code = json.path("code").asText();
            expiresAt = Instant.parse(json.path("expiresAt").asText());
            // «342 900» читается и диктуется легче, чем «342900».
            codeLabel.setText(code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code);
            totalSeconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
            startCountdown();
            renderQr();
        } catch (IOException e) {
            showError("Не удалось связаться с релеем: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void renderQr() {
        AddressOption selected = (AddressOption) addressBox.getSelectedItem();
        if (code == null || selected == null) {
            qrLabel.setIcon(null);
            return;
        }
        String payload = buildPayload(code, selected.relayUrl(), computerName(), selected.mac());
        try {
            qrLabel.setIcon(new ImageIcon(qrImage(payload)));
            qrLabel.setText(null);
            System.out.println("QR для привязки телефона: relay=" + selected.relayUrl());
        } catch (WriterException e) {
            qrLabel.setIcon(null);
            qrLabel.setText("Не удалось нарисовать QR");
            System.err.println("Не удалось нарисовать QR: " + e.getMessage());
        }
    }

    /**
     * Строка для QR — ровно в том формате, который разбирает PairingQr.parse на
     * телефоне. mac дописываем, только если он известен: у Tailscale-адреса
     * hardware-адреса нет, а будить по нему всё равно нечего.
     */
    static String buildPayload(String code, String relayUrl, String computerName, String mac) {
        String payload = "jarvis-pair://pair?code=" + code
                + "&relay=" + encode(relayUrl)
                + "&name=" + encode(computerName);
        return (mac == null || mac.isBlank()) ? payload : payload + "&mac=" + encode(mac);
    }

    /**
     * URLEncoder кодирует пробел как "+", а android.net.Uri обратно в пробел его
     * НЕ раскодирует (только %XX) — поэтому меняем на %20, иначе имя ПК с
     * пробелом приедет на телефон с плюсами.
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static BufferedImage qrImage(String text) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
        BufferedImage image = new BufferedImage(QR_SIZE, QR_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < QR_SIZE; x++) {
            for (int y = 0; y < QR_SIZE; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }

    private static String computerName() {
        String fromEnv = System.getenv("COMPUTERNAME");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "PC";
        }
    }

    private void startCountdown() {
        if (countdown != null) countdown.stop();
        countdown = new Timer(1000, e -> updateCountdown());
        countdown.setInitialDelay(0);
        countdown.start();
    }

    private void updateCountdown() {
        long left = Duration.between(Instant.now(), expiresAt).getSeconds();
        if (left <= 0) {
            countdownLabel.setText("код истёк — нажмите «Новый код»");
            expiryBar.setFraction(0f);
            countdown.stop();
            return;
        }
        countdownLabel.setText(String.format("действует ещё %d:%02d", left / 60, left % 60));
        expiryBar.setFraction((float) left / totalSeconds);
    }

    @Override
    public void dispose() {
        if (countdown != null) countdown.stop();
        super.dispose();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Faust", JOptionPane.ERROR_MESSAGE);
    }

    /** Окно с выдуманным кодом и без сети — картинкой для UiPreview. */
    static PairPhoneDialog preview() {
        PairPhoneDialog d = new PairPhoneDialog(null, null, false);
        d.code = "342900";
        d.codeLabel.setText("342 900");
        d.countdownLabel.setText("действует ещё 7:42");
        d.expiryBar.setFraction(0.77f);
        d.qrLabel.setIcon(new javax.swing.ImageIcon(
                new java.awt.image.BufferedImage(QR_SIZE, QR_SIZE, java.awt.image.BufferedImage.TYPE_INT_RGB) {{
                    java.awt.Graphics2D g = createGraphics();
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, QR_SIZE, QR_SIZE);
                    g.setColor(java.awt.Color.BLACK);
                    java.util.Random r = new java.util.Random(7);
                    for (int y = 0; y < 29; y++) for (int x = 0; x < 29; x++) if (r.nextBoolean()) g.fillRect(x * 9 + 9, y * 9 + 9, 9, 9);
                    g.dispose();
                }}));
        return d;
    }
}
