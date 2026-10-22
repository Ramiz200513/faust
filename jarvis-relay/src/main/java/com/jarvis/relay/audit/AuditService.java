package com.jarvis.relay.audit;

import com.jarvis.relay.persistence.AuditLog;
import com.jarvis.relay.persistence.AuditLogRepository;
import com.jarvis.relay.persistence.Device;
import com.jarvis.relay.persistence.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Пишет одну строку аудита на каждую команду: кто, что, с каким исходом. */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final DeviceRepository deviceRepository;

    public AuditService(AuditLogRepository auditLogRepository, DeviceRepository deviceRepository) {
        this.auditLogRepository = auditLogRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public void log(String deviceId, String command, String status, String details) {
        // getReferenceById не делает лишний SELECT: устройство точно существует —
        // мы только что по нему успешно аутентифицировались в этой же сессии.
        Device deviceRef = deviceRepository.getReferenceById(deviceId);
        auditLogRepository.save(new AuditLog(Instant.now(), deviceRef, command, status, details));
    }
}
