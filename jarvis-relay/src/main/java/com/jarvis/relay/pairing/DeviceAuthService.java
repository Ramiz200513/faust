package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.Device;
import com.jarvis.relay.persistence.DeviceRepository;
import com.jarvis.relay.persistence.PairingCode;
import com.jarvis.relay.persistence.PhoneBinding;
import com.jarvis.relay.persistence.PhoneBindingRepository;
import com.jarvis.relay.protocol.DeviceRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Проверяет, кто подключился. Три пути:
 *
 *  1. Телефон, привязанный к ПК (новая схема, экран «Устройства»):
 *     Auth(deviceId = id агента, token = токен телефона, role = PHONE).
 *     Ищем строки phone_bindings по agentId и сверяем токен с хэшами.
 *
 *  2. Устройство из таблицы devices (агент, либо телефон по старой схеме):
 *     deviceId известен -> token должен быть его постоянным токеном;
 *     deviceId новый    -> token трактуется как одноразовый код без agentId.
 *
 *  3. Паринг телефона к ПК (PairRequest) — метод {@link #pairPhone}.
 */
@Service
public class DeviceAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final PhoneBindingRepository phoneBindings;
    private final PairingService pairingService;
    private final PasswordEncoder passwordEncoder;
    /**
     * Пускать ли телефон по старой схеме (строка в devices, без конкретного ПК).
     * По умолчанию нет — см. SessionRegistry: такой телефон видел бы все ПК
     * релея. Тот же переключатель, что и там.
     */
    private final boolean legacyPhones;

    public DeviceAuthService(DeviceRepository devices, PhoneBindingRepository phoneBindings,
                             PairingService pairingService, PasswordEncoder passwordEncoder,
                             @Value("${jarvis.legacy-phones.enabled:false}") boolean legacyPhones) {
        this.devices = devices;
        this.phoneBindings = phoneBindings;
        this.pairingService = pairingService;
        this.passwordEncoder = passwordEncoder;
        this.legacyPhones = legacyPhones;
    }

    /**
     * Итог Auth.
     *  newToken     — заполнен только сразу после паринга по коду (единственный
     *                 момент, когда токен существует в открытом виде).
     *  targetAgentId — для телефона: каким ПК он управляет. null у агента и у
     *                 телефона по старой схеме (тот управляет «любым» агентом).
     */
    public record Result(boolean ok, String reason, String newToken, String targetAgentId) {
        static Result fail(String reason) {
            return new Result(false, reason, null, null);
        }

        static Result success(String targetAgentId) {
            return new Result(true, null, null, targetAgentId);
        }

        static Result paired(String newToken) {
            return new Result(true, null, newToken, null);
        }
    }

    /** Итог PairRequest: при ok — id и имя ПК плюс новый токен телефона. */
    public record PairOutcome(boolean ok, String agentId, String agentName, String phoneToken, String reason) {
        static PairOutcome fail(String reason) {
            return new PairOutcome(false, null, null, null, reason);
        }
    }

    @Transactional
    public Result authenticate(String deviceId, String presentedToken, DeviceRole role) {
        // Путь 1: телефон, привязанный к конкретному ПК.
        if (role == DeviceRole.PHONE) {
            List<PhoneBinding> bindings = phoneBindings.findByAgentId(deviceId);
            if (!bindings.isEmpty()) {
                boolean match = bindings.stream()
                        .anyMatch(b -> passwordEncoder.matches(presentedToken, b.getTokenHash()));
                return match ? Result.success(deviceId) : Result.fail("bad token");
            }
        }

        // Путь 2: устройство из таблицы devices. Для телефона это старая схема
        // «управляю любым ПК» — без явного разрешения не пускаем вовсе, в том
        // числе не заводим новый такой телефон по коду устройства.
        if (role == DeviceRole.PHONE && !legacyPhones) {
            return Result.fail("телефон не привязан к конкретному ПК — привяжите ПК заново по QR-коду");
        }
        Optional<Device> existing = devices.findById(deviceId);
        if (existing.isPresent()) {
            Device device = existing.get();
            if (device.getRole() != role) {
                return Result.fail("device registered with a different role");
            }
            if (!passwordEncoder.matches(presentedToken, device.getTokenHash())) {
                return Result.fail("bad token");
            }
            return Result.success(null);
        }

        Optional<PairingCode> pairingCode = pairingService.tryConsumeDeviceCode(presentedToken);
        if (pairingCode.isEmpty()) {
            return Result.fail("unknown device or invalid pairing code");
        }

        String newToken = generateToken();
        String label = pairingCode.get().getLabel();
        Device device = new Device(deviceId, label != null ? label : deviceId, role,
                passwordEncoder.encode(newToken), Instant.now());
        devices.save(device);
        return Result.paired(newToken);
    }

    /**
     * Путь 3: телефон прислал PairRequest с кодом, выписанным для конкретного ПК.
     * Гасим код, заводим строку phone_bindings, отдаём токен телефону.
     */
    @Transactional
    public PairOutcome pairPhone(String code) {
        if (code == null || code.isBlank()) {
            return PairOutcome.fail("пустой код");
        }
        Optional<PairingCode> pairingCode = pairingService.tryConsumePhoneCode(code.trim());
        if (pairingCode.isEmpty()) {
            return PairOutcome.fail("код неверный, истёк или уже использован");
        }

        String agentId = pairingCode.get().getAgentId();
        Optional<Device> agent = devices.findById(agentId);
        if (agent.isEmpty()) {
            return PairOutcome.fail("ПК для этого кода больше не существует");
        }

        String newToken = generateToken();
        phoneBindings.save(new PhoneBinding(agentId, pairingCode.get().getLabel(),
                passwordEncoder.encode(newToken), Instant.now()));
        return new PairOutcome(true, agentId, agent.get().getName(), newToken, null);
    }

    /** 32 случайных байта в base64url — постоянный токен. */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
