package com.jarvis.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.agent.protocol.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a WebSocket connection to the relay open, authenticates, and runs
 * incoming commands through the CommandExecutor. Uses java.net.http.WebSocket
 * — built into the JDK, so no external client library.
 *
 * Key design point from the roadmap: the AGENT dials OUT to the relay. That's
 * why this works from behind NAT with no port forwarding — the PC opens the
 * connection, the relay never has to reach the PC.
 */
public class RelayClient {

    private final URI relayUri;
    /** Одноразовый код привязки — используется только при первом запуске, пока нет постоянного токена. */
    private final String pairingCode;
    private final TokenStore tokenStore;
    /** Стабильный id устройства — читается из хранилища, генерируется один раз при первой привязке. */
    private final String deviceId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CommandExecutor executor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /**
     * Отдельный поток под agent_task. Раньше задача выполнялась прямо в колбэке
     * onText, и до её конца агент НЕ ЧИТАЛ сокет (webSocket.request(1)
     * вызывается только после возврата из onText) — то есть команда отмены
     * физически не могла дойти. Одного потока достаточно: параллельно две
     * задачи всё равно не выполняются, за этим следит busyGoal в CommandExecutor.
     * daemon — чтобы не мешал JVM завершиться.
     */
    private final ExecutorService agentPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-agent-task");
        t.setDaemon(true);
        return t;
    });
    /**
     * Отдельный поток под команды с подтверждением на ПК (kill_process, передача
     * файлов): диалог ConfirmationGate может ждать до ~20 с, и сокет на это время
     * блокировать нельзя. Не agentPool: он однопоточный, и kill_process ждал бы в
     * очереди всю идущую agent_task (до 3 минут). Два окна одновременно всё равно
     * не откроются — ConfirmationGate.ask synchronized.
     */
    private final ExecutorService confirmPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-confirm");
        t.setDaemon(true);
        return t;
    });

    /**
     * В каком потоке выполнять команду: agent_task — agentPool, команды с
     * подтверждением на ПК — confirmPool, null — быстрая команда, прямо в потоке
     * чтения сокета (в том числе cancel_task, которому нужно сработать немедленно,
     * и answer_question: в agentPool она встала бы в очередь за той самой
     * задачей, которая ждёт этот ответ, — и не дождалась бы никогда).
     */
    private ExecutorService poolFor(Command command) {
        if (command instanceof Command.AgentTask) return agentPool;
        if (command instanceof Command.KillProcess
                || command instanceof Command.SendFileToPhone
                || command instanceof Command.SendFileToPc) return confirmPool;
        return null;
    }

    /** Постоянный токен. Изначально — то, что удалось прочитать с диска; после успешной
     *  привязки заменяется на выданный сервером и используется при всех реконнектах. */
    private volatile String token;
    private volatile WebSocket ws;
    /** true, если паринг явно отвергнут сервером — дальше реконнектиться бессмысленно (спам). */
    private volatile boolean stopReconnect = false;
    private int reconnectAttempt = 0;
    private volatile RelayStatusListener statusListener = new RelayStatusListener() {};

    /** Подписаться на статус связи (используется треем). Заменяет предыдущего слушателя. */
    public void setStatusListener(RelayStatusListener listener) {
        this.statusListener = (listener != null) ? listener : new RelayStatusListener() {};
    }

    public RelayClient(URI relayUri, String pairingCode, CommandExecutor executor) {
        this(relayUri, pairingCode, executor, new TokenStore());
    }

    RelayClient(URI relayUri, String pairingCode, CommandExecutor executor, TokenStore tokenStore) {
        this.relayUri = relayUri;
        this.pairingCode = pairingCode;
        this.executor = executor;
        this.tokenStore = tokenStore;
        this.token = tokenStore.load();
        // deviceId должен быть ОДИН И ТОТ ЖЕ между запусками, иначе релей не узнаёт
        // устройство по сохранённому токену. Читаем сохранённый; если нет — генерируем раз и сохраняем.
        String id = tokenStore.loadDeviceId();
        if (id == null) {
            id = "pc-" + UUID.randomUUID().toString().substring(0, 8);
            tokenStore.saveDeviceId(id);
        }
        this.deviceId = id;
    }

    /** Opens the connection; reconnects itself if it drops. */
    public void start() {
        connect();
    }

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

    private void scheduleReconnect() {
        if (stopReconnect) {
            System.out.println("Реконнект остановлен (паринг не пройден) — процесс можно завершить или перезапустить с верным кодом.");
            return;
        }
        long delay = Math.min(30, (long) Math.pow(2, reconnectAttempt++)); // 1,2,4,...,30s
        System.out.println("Reconnecting in " + delay + "s");
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /**
     * Serialize a message and send it. Synchronized: one send at a time.
     * Виден пакету, потому что этим же методом ScreenStreamer шлёт кадры
     * живого экрана (см. проброс callback в AgentMain).
     */
    synchronized void send(JarvisMessage msg) {
        try {
            if (ws != null) ws.sendText(mapper.writeValueAsString(msg), true).join();
        } catch (Exception e) {
            System.err.println("send failed: " + e.getMessage());
        }
    }

    /** Callbacks from the WebSocket. */
    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            webSocket.request(1); // ask for the first message

            // Приоритет: если уже есть сохранённый постоянный токен — используем его
            // (в т.ч. при реконнекте). Иначе — это первый запуск, шлём одноразовый
            // код привязки, переданный аргументом командной строки.
            String authValue = (token != null) ? token : pairingCode;
            if (authValue == null) {
                System.err.println("Нет сохранённого токена и не передан код привязки. "
                        + "Запустите агент с кодом привязки первым аргументом: <url> <код>.");
                stopReconnect = true;
                webSocket.abort();
                return;
            }

            reconnectAttempt = 0;
            // First message MUST be Auth.
            send(new JarvisMessage.Auth(
                    UUID.randomUUID().toString(), deviceId, authValue, DeviceRole.AGENT, 1));
            System.out.println("Connected. Authenticating as " + deviceId
                    + (token != null ? " (сохранённым токеном)" : " (кодом привязки)"));
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {                       // a message can arrive in fragments
                handle(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);             // ask for the next one
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("Disconnected: " + reason);
            executor.onDisconnected();
            statusListener.onDisconnected(reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
            executor.onDisconnected();
            statusListener.onDisconnected(error.getMessage());
            scheduleReconnect();
        }
    }

    /** Handle one full JSON message from the relay. */
    private void handle(String json) {
        try {
            JarvisMessage msg = mapper.readValue(json, JarvisMessage.class);
            switch (msg) {
                case JarvisMessage.CommandRequest req -> {
                    System.out.println("Command: " + req.command().name());
                    ExecutorService pool = poolFor(req.command());
                    if (pool != null) {
                        // agent_task (до 3 минут) и команды с подтверждением на ПК
                        // (до ~20 с на диалог ConfirmationGate) — в отдельный поток,
                        // иначе сокет перестанет читаться на это время и, например,
                        // cancel_task или следующая команда не дойдут вовремя.
                        // send() уже synchronized, писать из другого потока безопасно.
                        pool.submit(() -> {
                            try {
                                executor.execute(req.requestId(), req.command())
                                        .forEach(RelayClient.this::send);
                            } catch (Exception e) {
                                System.err.println("команда " + req.command().name() + " упала: " + e.getMessage());
                            }
                        });
                    } else {
                        // Всё остальное — миллисекунды, выполняем как раньше, прямо здесь.
                        // В том числе cancel_task: он только взводит флаг и должен
                        // сработать НЕМЕДЛЕННО, а не встать в очередь за задачей.
                        List<JarvisMessage> out = executor.execute(req.requestId(), req.command());
                        out.forEach(RelayClient.this::send);
                    }
                }
                case JarvisMessage.Ping p -> send(new JarvisMessage.Pong(p.requestId()));
                case JarvisMessage.AuthResult r -> handleAuthResult(r);
                default -> { /* ignore anything else */ }
            }
        } catch (Exception e) {
            System.err.println("bad message: " + e.getMessage());
        }
    }

    /**
     * Разбор ответа на Auth. По протоколу поле {@code reason} перегружено:
     *  - ok=true и reason непустой  -> это ВЫДАННЫЙ постоянный токен (после привязки по коду);
     *  - ok=true и reason пустой    -> уже были авторизованы постоянным токеном, всё штатно;
     *  - ok=false                   -> код/токен отвергнут, дальше не реконнектимся, чтобы не спамить relay.
     */
    private void handleAuthResult(JarvisMessage.AuthResult r) {
        if (!r.ok()) {
            System.err.println("Паринг не прошёл: " + r.reason());
            stopReconnect = true;
            statusListener.onAuthRejected(r.reason());
            return;
        }
        if (r.reason() != null && !r.reason().isBlank()) {
            // Токен не логируем открыто.
            token = r.reason();
            tokenStore.save(token);
            System.out.println("Auth: ok, получен и сохранён постоянный токен.");
        } else {
            System.out.println("Auth: ok (постоянный токен уже был сохранён).");
        }
        statusListener.onConnected();
    }
}
