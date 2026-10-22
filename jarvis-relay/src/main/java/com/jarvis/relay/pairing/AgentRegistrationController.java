package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.PairingCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Самообслуживание для НОВОГО ПК: агент, который ещё ни разу не привязывался,
 * сам просит одноразовый код и тут же гасит его обычным Auth по WebSocket.
 * Благодаря этому установка агента не требует от человека вообще ничего —
 * скачал, запустил, ПК подключился.
 *
 * Чем это отличается от соседей:
 *  - {@link PairingController} (POST /api/pairing-codes) защищён
 *    X-Admin-Token и умеет выписать код для ЛЮБОГО ПК — остаётся как есть,
 *    для закрытых стендов и ручной выдачи;
 *  - {@link AgentPairingController} требует уже существующие deviceId+token
 *    агента и выдаёт код для ТЕЛЕФОНА;
 *  - этот эндпоинт не требует ничего, но и выдаёт только код регистрации
 *    нового устройства (agentId = null), то есть зарегистрировать можно
 *    исключительно самого себя.
 *
 * Компромисс осознанный: код перестаёт быть секретом, который кто-то кому-то
 * передаёт, и становится внутренней деталью рукопожатия. Доступ к ЧУЖОМУ ПК
 * это не даёт — телефон привязывается отдельным кодом, который показывает сам
 * этот ПК в окне «Привязать телефон». Остаётся лишь риск, что посторонний
 * насоздаёт пустых устройств, поэтому здесь есть выключатель
 * (jarvis.open-registration) и ограничение частоты по IP.
 */
@RestController
@RequestMapping("/api/agent/register")
public class AgentRegistrationController {

    private final PairingService pairingService;

    /** false — эндпоинт выключен, новые ПК регистрирует только администратор. */
    @Value("${jarvis.open-registration.enabled}")
    private boolean enabled;

    /** Сколько регистраций в час разрешено с одного адреса. */
    @Value("${jarvis.open-registration.per-hour}")
    private int perHour;

    /** Когда с каждого IP приходили последние запросы — на большее ограничение не претендует. */
    private final Map<String, Deque<Instant>> recentByIp = new ConcurrentHashMap<>();

    public AgentRegistrationController(PairingService pairingService) {
        this.pairingService = pairingService;
    }

    public record RegisterRequest(String label) {}

    public record RegisterResponse(String code, Instant expiresAt) {}

    @PostMapping
    public ResponseEntity<?> register(@RequestBody(required = false) RegisterRequest request,
                                      HttpServletRequest http) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "открытая регистрация выключена на этом сервере"));
        }
        if (!allow(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "слишком много регистраций с этого адреса, попробуйте позже"));
        }

        String label = request != null && request.label() != null && !request.label().isBlank()
                ? request.label().trim() : "pc";
        // agentId = null -> код именно для регистрации устройства, не для телефона
        // (см. PairingService.tryConsumeDeviceCode, который его потом погасит).
        PairingCode code = pairingService.generateCode(label, null);
        return ResponseEntity.ok(new RegisterResponse(code.getCode(), code.getExpiresAt()));
    }

    /**
     * Скользящее окно в час на каждый адрес. Хранится в памяти: перезапуск
     * релея счётчик обнуляет, и это нормально — задача не «защитить от
     * злоумышленника с ботнетом», а не дать одному клиенту зациклиться.
     */
    private boolean allow(String ip) {
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(1));
        Deque<Instant> hits = recentByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst().isBefore(since)) {
                hits.pollFirst();
            }
            if (hits.size() >= perHour) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }
}
