package com.jarvis.relay.pairing;

import com.jarvis.relay.persistence.PairingCode;
import com.jarvis.relay.persistence.PairingCodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Генерирует одноразовые коды паринга и гасит их при подключении нового
 * устройства. Код — 6 цифр (удобно вбить руками в консоль агента),
 * живёт ограниченное время и работает ровно один раз.
 */
@Service
public class PairingService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PairingCodeRepository repository;

    @Value("${jarvis.pairing.ttl-minutes}")
    private long ttlMinutes;

    public PairingService(PairingCodeRepository repository) {
        this.repository = repository;
    }

    /**
     * agentId == null -> код для привязки самого устройства (агента) через Auth.
     * agentId != null -> код для телефона, дающий доступ к этому ПК (PairRequest).
     */
    @Transactional
    public PairingCode generateCode(String label, String agentId) {
        String code;
        do {
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (repository.existsByCode(code));

        Instant now = Instant.now();
        PairingCode pairingCode = new PairingCode(code, label, agentId, now, now.plus(Duration.ofMinutes(ttlMinutes)));
        return repository.save(pairingCode);
    }

    /** Погасить код для привязки устройства (агента) через Auth. Коды телефонов не подходят. */
    @Transactional
    public Optional<PairingCode> tryConsumeDeviceCode(String code) {
        return tryConsume(code, false);
    }

    /** Погасить код для телефона (PairRequest). Коды без agentId не подходят. */
    @Transactional
    public Optional<PairingCode> tryConsumePhoneCode(String code) {
        return tryConsume(code, true);
    }

    /**
     * Пытается погасить код. Возвращает его, если он существовал, ещё не был
     * использован, не истёк и выписан для нужного типа привязки. Второй вызов
     * с тем же кодом уже вернёт пусто — в этом и есть "одноразовость".
     */
    private Optional<PairingCode> tryConsume(String code, boolean forPhone) {
        Optional<PairingCode> found = repository.findByCode(code);
        Instant now = Instant.now();
        if (found.isEmpty() || !found.get().isValidNow(now)
                || found.get().isForPhone() != forPhone) {
            return Optional.empty();
        }
        PairingCode pairingCode = found.get();
        pairingCode.markUsed(now);
        repository.save(pairingCode);
        return Optional.of(pairingCode);
    }
}
