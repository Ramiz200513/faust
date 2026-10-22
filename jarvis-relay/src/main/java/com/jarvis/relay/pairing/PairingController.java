package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.DeviceRepository;
import com.jarvis.relay.persistence.PairingCode;
import com.jarvis.relay.protocol.DeviceRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

/**
 * Админский эндпоинт: сгенерировать код паринга, который человек вручную
 * вводит в агента/телефон при первом подключении (например, агент выводит
 * приглашение "введите код" в консоль при первом старте, а этот код ему
 * заранее выдаёт администратор релея через этот эндпоинт).
 *
 * Защищён отдельным секретом jarvis.admin-token — обычным устройствам он
 * не нужен, они парятся по одноразовому коду.
 */
@RestController
@RequestMapping("/api/pairing-codes")
public class PairingController {

    private final PairingService pairingService;
    private final DeviceRepository devices;

    @Value("${jarvis.admin-token}")
    private String adminToken;

    public PairingController(PairingService pairingService, DeviceRepository devices) {
        this.pairingService = pairingService;
        this.devices = devices;
    }

    /** agentId необязателен: указан — код для телефона к этому ПК. */
    public record GenerateRequest(String label, String agentId) {}

    public record GenerateResponse(String code, Instant expiresAt, String agentId) {}

    @PostMapping
    public ResponseEntity<?> generate(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                       @RequestBody(required = false) GenerateRequest request) {
        if (!isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad admin token"));
        }
        String label = request != null ? request.label() : null;
        String agentId = request != null && request.agentId() != null && !request.agentId().isBlank()
                ? request.agentId().trim() : null;

        // Код для телефона имеет смысл, только если такой ПК уже привязан.
        if (agentId != null) {
            boolean isKnownAgent = devices.findById(agentId)
                    .map(d -> d.getRole() == DeviceRole.AGENT)
                    .orElse(false);
            if (!isKnownAgent) {
                return ResponseEntity.badRequest().body(Map.of("error", "unknown agent: " + agentId));
            }
        }

        PairingCode code = pairingService.generateCode(label, agentId);
        return ResponseEntity.ok(new GenerateResponse(code.getCode(), code.getExpiresAt(), code.getAgentId()));
    }

    /** Сравнение за постоянное время — не даём угадывать секрет по времени ответа. */
    private boolean isAuthorized(String provided) {
        if (provided == null) return false;
        byte[] a = provided.getBytes(StandardCharsets.UTF_8);
        byte[] b = adminToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
