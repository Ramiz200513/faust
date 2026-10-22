package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.Device;
import com.jarvis.relay.persistence.DeviceRepository;
import com.jarvis.relay.protocol.DeviceRole;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Общая проверка "это точно наш агент": deviceId должен существовать в
 * таблице devices с ролью AGENT, а token — совпадать (BCrypt) с его хэшем.
 * Раньше эта проверка была продублирована внутри {@link AgentPairingController};
 * теперь оба REST-эндпоинта агента (запрос кода паринга, LLM-прокси) ходят сюда.
 */
@Service
public class AgentAuthService {

    private final DeviceRepository devices;
    private final PasswordEncoder passwordEncoder;

    public AgentAuthService(DeviceRepository devices, PasswordEncoder passwordEncoder) {
        this.devices = devices;
        this.passwordEncoder = passwordEncoder;
    }

    /** @return устройство, если deviceId/token верны и это агент; иначе пусто. */
    public Optional<Device> authenticateAgent(String deviceId, String token) {
        if (deviceId == null || deviceId.isBlank() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        return devices.findById(deviceId)
                .filter(d -> d.getRole() == DeviceRole.AGENT)
                .filter(d -> passwordEncoder.matches(token, d.getTokenHash()));
    }
}
