package com.jarvis.relay.persistence;

import com.jarvis.relay.protocol.DeviceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Устройство, один раз прошедшее паринг и получившее постоянный токен.
 * id — это deviceId из протокола (его присылает клиент в Auth), отдельный
 * суррогатный ключ не нужен: deviceId и так уникален и стабилен.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private DeviceRole role;

    /** BCrypt-хэш постоянного токена. В открытом виде токен в БД не хранится. */
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "paired_at", nullable = false, updatable = false)
    private Instant pairedAt;

    protected Device() {
        // для Hibernate
    }

    public Device(String id, String name, DeviceRole role, String tokenHash, Instant pairedAt) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.tokenHash = tokenHash;
        this.pairedAt = pairedAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public DeviceRole getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getPairedAt() {
        return pairedAt;
    }
}
