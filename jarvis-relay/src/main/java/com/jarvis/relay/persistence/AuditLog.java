package com.jarvis.relay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Одна строка аудита: какое устройство, какую команду и с каким исходом
 * отправило. Раньше это был log.info в консоли — теперь строка в БД.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false, updatable = false)
    private Device device;

    @Column(name = "command", nullable = false)
    private String command;

    /** SENT / DENIED / ERROR — просто текст, не завязан на CommandStatus протокола. */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "details", columnDefinition = "text")
    private String details;

    protected AuditLog() {
        // для Hibernate
    }

    public AuditLog(Instant occurredAt, Device device, String command, String status, String details) {
        this.occurredAt = occurredAt;
        this.device = device;
        this.command = command;
        this.status = status;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Device getDevice() {
        return device;
    }

    public String getCommand() {
        return command;
    }

    public String getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }
}
