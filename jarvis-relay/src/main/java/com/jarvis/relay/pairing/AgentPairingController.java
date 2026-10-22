package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.Device;
import com.jarvis.relay.persistence.PairingCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Самообслуживание: уже привязанный агент (ПК) сам просит код для своего
 * телефона, не зная админского секрета (jarvis.admin-token). В отличие от
 * {@link PairingController} (который защищён X-Admin-Token и может выписать
 * код для ЛЮБОГО ПК), здесь агент аутентифицируется собственными
 * deviceId+token — теми же, что использует в Auth по WebSocket — и получает
 * код только для самого себя.
 */
@RestController
@RequestMapping("/api/agent/phone-pairing-code")
public class AgentPairingController {

    private final AgentAuthService agentAuth;
    private final PairingService pairingService;

    public AgentPairingController(AgentAuthService agentAuth, PairingService pairingService) {
        this.agentAuth = agentAuth;
        this.pairingService = pairingService;
    }

    public record GenerateRequest(String label) {}

    public record GenerateResponse(String code, Instant expiresAt) {}

    @PostMapping
    public ResponseEntity<?> generate(@RequestHeader("X-Device-Id") String deviceId,
                                       @RequestHeader("X-Device-Token") String token,
                                       @RequestBody(required = false) GenerateRequest request) {
        Optional<Device> device = agentAuth.authenticateAgent(deviceId, token);
        if (device.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad device id or token"));
        }

        String label = request != null && request.label() != null && !request.label().isBlank()
                ? request.label().trim() : "phone";
        PairingCode code = pairingService.generateCode(label, deviceId);
        return ResponseEntity.ok(new GenerateResponse(code.getCode(), code.getExpiresAt()));
    }
}
