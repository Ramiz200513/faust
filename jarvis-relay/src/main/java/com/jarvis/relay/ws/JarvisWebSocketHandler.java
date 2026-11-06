package com.jarvis.relay.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.audit.AuditService;
import com.jarvis.relay.llm.IntentLessons;
import com.jarvis.relay.llm.IntentParser;
import com.jarvis.relay.pairing.DeviceAuthService;
import com.jarvis.relay.protocol.*;
import com.jarvis.relay.security.CommandWhitelist;
import org.slf4j.Logger;
import com.jarvis.relay.security.AttemptLimiter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.UUID;

/**
 * The heart of the relay. One instance handles every WebSocket connection.
 * Responsibilities:
 *   1. Authenticate a device on its first message (Auth) — either by its
 *      permanent token (already-paired device) or by a one-time pairing
 *      code (brand-new device), via DeviceAuthService.
 *   2. Route messages between the phone and the agent.
 *   3. Turn a spoken phrase into a concrete command (via IntentParser).
 *   4. Enforce the whitelist before anything reaches the agent.
 *   5. Write an audit row for every command (the "who did what" trail),
 *      now persisted in PostgreSQL instead of just the console.
 */
@Component
public class JarvisWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(JarvisWebSocketHandler.class);
    private static final String ATTR_ROLE = "role"; // key we store the role under
    private static final String ATTR_DEVICE_ID = "deviceId"; // key we store the deviceId under
    // Для телефона: id ПК, которым он управляет (null — старая схема, «любой агент»).
    private static final String ATTR_TARGET_AGENT = "targetAgentId";

    private final ObjectMapper mapper;
    private final CommandWhitelist whitelist;
    private final IntentParser intentParser;
    private final SessionRegistry sessions;
    private final DeviceAuthService deviceAuthService;
    private final AuditService auditService;
    private final IntentLessons lessons;
    /**
     * Неудачные входы и привязки по адресу клиента. Плюс к общему лимиту кодов
     * (PairingService): один шумный адрес не съедает запас попыток у всех.
     */
    private final AttemptLimiter badLogins;
    /**
     * Стоит ли перед релеем свой прокси (Caddy на сервере). Тогда адрес сокета —
     * адрес прокси, а настоящий клиент — в X-Forwarded-For. Без прокси этому
     * заголовку верить нельзя: клиент впишет туда что угодно и обойдёт лимит.
     */
    private final boolean behindProxy;

    public JarvisWebSocketHandler(ObjectMapper mapper,
                                  CommandWhitelist whitelist,
                                  IntentParser intentParser,
                                  SessionRegistry sessions,
                                  DeviceAuthService deviceAuthService,
                                  AuditService auditService,
                                  IntentLessons lessons,
                                  @Value("${jarvis.login-limit.max-failures:10}") int maxBadLogins,
                                  @Value("${jarvis.behind-proxy:false}") boolean behindProxy) {
        this.mapper = mapper;
        this.whitelist = whitelist;
        this.intentParser = intentParser;
        this.sessions = sessions;
        this.deviceAuthService = deviceAuthService;
        this.auditService = auditService;
        this.lessons = lessons;
        this.badLogins = new AttemptLimiter(maxBadLogins, Duration.ofMinutes(10));
        this.behindProxy = behindProxy;
    }

    /**
     * Адрес клиента для лимита. За прокси — последний адрес в X-Forwarded-For:
     * его дописал наш Caddy, а всё, что левее, мог прислать сам клиент.
     */
    static String clientKey(WebSocketSession session, boolean behindProxy) {
        if (behindProxy) {
            String xff = session.getHandshakeHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                return "ip:" + parts[parts.length - 1].strip();
            }
        }
        var addr = session.getRemoteAddress();
        return "ip:" + (addr != null && addr.getAddress() != null ? addr.getAddress().getHostAddress() : "unknown");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JarvisMessage msg = mapper.readValue(message.getPayload(), JarvisMessage.class);

        // Not authenticated yet? The only message we accept is Auth.
        DeviceRole role = (DeviceRole) session.getAttributes().get(ATTR_ROLE);
        if (role == null) {
            // С этого адреса уже много неудач подряд — похоже на перебор кодов
            // или токенов. Даже не проверяем: каждая проверка токена — это
            // ещё и bcrypt, то есть нагрузка на сервер.
            if (badLogins.isBlocked(clientKey(session, behindProxy))) {
                String why = "слишком много неудачных попыток — подождите 10 минут";
                if (msg instanceof JarvisMessage.PairRequest pair) {
                    send(session, new JarvisMessage.PairResult(pair.requestId(), false, null, null, null, why));
                } else {
                    send(session, new JarvisMessage.AuthResult(msg.requestId(), false, why, null));
                }
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            // Первое сообщение — либо привязка телефона к ПК, либо обычный вход.
            if (msg instanceof JarvisMessage.PairRequest pair) {
                handlePair(session, pair);
            } else {
                handleAuth(session, msg);
            }
            return;
        }

        switch (msg) {
            case JarvisMessage.Ping p ->
                    send(session, new JarvisMessage.Pong(p.requestId()));

            case JarvisMessage.CommandRequest req ->
                    handleCommand(session, role, req);

            // Results and screen frames come from the agent -> forward to phone.
            case JarvisMessage.CommandResult r -> {
                // Итог выполнения — заодно проверка «урока» для своей модели: фразу,
                // которую понял только Gemini, запоминаем, лишь если команда удалась.
                if (role == DeviceRole.AGENT) {
                    if (r.status() == CommandStatus.OK) lessons.confirm(r.requestId());
                    else lessons.reject(r.requestId());
                }
                forwardToPhones(session, role, r);
            }
            case JarvisMessage.ScreenFrame f  -> forwardToPhones(session, role, f);
            case JarvisMessage.AgentStep s    -> forwardToPhones(session, role, s);
            // Вопрос агента человеку — туда же, где шаги задачи: на телефоны этого ПК.
            case JarvisMessage.AgentQuestion q -> forwardToPhones(session, role, q);
            // План многоэтапной задачи — тоже только пересылаем.
            case JarvisMessage.AgentPlan p     -> forwardToPhones(session, role, p);

            default -> log.warn("Unexpected message from {}: {}", role, msg);
        }
    }

    /**
     * First message on a connection must be a valid Auth. The token field is
     * interpreted by DeviceAuthService: a permanent token for a known device,
     * or a one-time pairing code for a brand-new one.
     */
    private void handleAuth(WebSocketSession session, JarvisMessage msg) throws Exception {
        if (!(msg instanceof JarvisMessage.Auth auth)) {
            send(session, new JarvisMessage.AuthResult(reqId(), false, "expected auth first", null));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        DeviceAuthService.Result result = deviceAuthService.authenticate(
                auth.deviceId(), auth.token(), auth.role());

        if (!result.ok()) {
            badLogins.recordFailure(clientKey(session, behindProxy));
            // Секрет тут ни при чём — в reason только причина отказа, не сам токен.
            send(session, new JarvisMessage.AuthResult(auth.requestId(), false, result.reason(), null));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        session.getAttributes().put(ATTR_ROLE, auth.role());
        session.getAttributes().put(ATTR_DEVICE_ID, auth.deviceId());

        // agentOnline сообщаем только телефону: на связи ли его ПК.
        Boolean agentOnline = null;
        if (auth.role() == DeviceRole.AGENT) {
            sessions.registerAgent(auth.deviceId(), session);
        } else {
            String target = result.targetAgentId();
            if (target != null) session.getAttributes().put(ATTR_TARGET_AGENT, target);
            sessions.registerPhone(session, target);
            agentOnline = sessions.isAgentOnline(target);
        }

        // result.newToken() заполнен только один раз — сразу после успешного
        // паринга по одноразовому коду. Формат AuthResult(requestId, ok, reason)
        // не меняется: новый постоянный токен один раз передаётся именно в
        // reason, устройство сохраняет его и использует при следующих
        // подключениях вместо кода. В логах сервера токен не появляется.
        boolean freshlyPaired = result.newToken() != null;
        send(session, new JarvisMessage.AuthResult(auth.requestId(), true, result.newToken(), agentOnline));

        log.info("Authenticated {} (device {}){}", auth.role(), auth.deviceId(),
                freshlyPaired ? " — paired just now via pairing code" : "");
    }

    /**
     * Телефон привязывает ПК по коду. Отвечаем PairResult и закрываем
     * соединение: это разовый обмен, дальше телефон зайдёт обычным Auth.
     */
    private void handlePair(WebSocketSession session, JarvisMessage.PairRequest pair) throws Exception {
        DeviceAuthService.PairOutcome outcome = deviceAuthService.pairPhone(pair.code());
        if (outcome.ok()) {
            send(session, new JarvisMessage.PairResult(pair.requestId(), true,
                    outcome.agentId(), outcome.phoneToken(), outcome.agentName(), null));
            // Токен в лог не пишем — только факт привязки.
            log.info("Phone paired to agent {}", outcome.agentId());
        } else {
            badLogins.recordFailure(clientKey(session, behindProxy));
            send(session, new JarvisMessage.PairResult(pair.requestId(), false,
                    null, null, null, outcome.reason()));
        }
        session.close(CloseStatus.NORMAL);
    }

    /** A command from the phone: parse -> whitelist -> forward to agent. */
    private void handleCommand(WebSocketSession session, DeviceRole role,
                               JarvisMessage.CommandRequest req) throws Exception {
        // Only the phone issues commands. Ignore any from the agent side.
        if (role != DeviceRole.PHONE) return;

        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        Command command = req.command();

        // Layer 1: a raw phrase -> ask the parser for a concrete command.
        if (command instanceof Command.NaturalLanguage nl) {
            command = intentParser.parse(nl.text(), req.requestId());
            if (command == null) {
                auditService.log(deviceId, "nl", "ERROR", "не понял команду: " + nl.text());
                send(session, new JarvisMessage.CommandResult(
                        req.requestId(), CommandStatus.ERROR, "не понял команду", null));
                return;
            }
        }

        // Security gate: nothing outside the whitelist ever reaches the agent.
        String denied = whitelist.denyReason(command);
        if (denied != null) {
            log.warn("DENIED command '{}' (req {}): {}", command.name(), req.requestId(), denied);
            lessons.reject(req.requestId()); // запрещённому свою модель не учим
            auditService.log(deviceId, command.name(), "DENIED",
                    "команда запрещена: " + denied + " (req " + req.requestId() + ")");
            // Причина — человеку на телефон: «агенту нельзя поручать: выключение компьютера».
            send(session, new JarvisMessage.CommandResult(
                    req.requestId(), CommandStatus.DENIED, "команда запрещена: " + denied, null));
            return;
        }

        String targetAgentId = (String) session.getAttributes().get(ATTR_TARGET_AGENT);
        WebSocketSession agent = sessions.agentFor(targetAgentId);
        if (agent == null || !agent.isOpen()) {
            lessons.reject(req.requestId()); // команда не выполнялась — проверить урок нечем
            auditService.log(deviceId, command.name(), "ERROR",
                    "агент не в сети (req " + req.requestId() + ")");
            send(session, new JarvisMessage.CommandResult(
                    req.requestId(), CommandStatus.ERROR, "агент не в сети", null));
            return;
        }

        // Audit log — the "who did what" trail, now in the database.
        auditService.log(deviceId, command.name(), "SENT", "req " + req.requestId());

        String agentId = (String) agent.getAttributes().get(ATTR_DEVICE_ID);
        if (command instanceof Command.StartScreenStream) {
            sessions.setStreaming(agentId, true);
        } else if (command instanceof Command.StopScreenStream) {
            sessions.setStreaming(agentId, false);
        }

        // Forward the (possibly rewritten) command to the agent.
        send(agent, new JarvisMessage.CommandRequest(req.requestId(), command));
    }

    /** Результат/кадр от агента -> всем телефонам, которые управляют этим ПК. */
    private void forwardToPhones(WebSocketSession from, DeviceRole role, JarvisMessage msg) throws Exception {
        if (role != DeviceRole.AGENT) return; // от телефона такое не ждём
        String agentId = (String) from.getAttributes().get(ATTR_DEVICE_ID);
        for (WebSocketSession phone : sessions.phonesOf(agentId)) {
            send(phone, msg);
        }
    }

    /** Serialize and send. Synchronized: a session must not be written concurrently. */
    private void send(WebSocketSession session, JarvisMessage msg) throws Exception {
        String json = mapper.writeValueAsString(msg);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    private static String reqId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Connection closed: {}", status);
    }
}
